package io.zer0.muse.data.miniphone

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Segmentación y degradación de las marcas del habla.:Qué párrafos se sintetizan en voz, qué párrafos solo se pueden usar como texto, el orden y el límite superior del número de párrafos.
 *
 * La salida del modelo, el puerto de síntesis y el puerto de archivo de voz son falsos. —— Aquí solo probamos la división y degradación del host.,
 * No toque la red ni el motor TTS.
 */
class MiniPhoneVoiceScriptTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `Sintetizar voz dentro de la marca y enviar texto en el orden original fuera de la marca`() = runBlocking {
        val host = voiceHost(answer = "Hola[Voz]Lee esta frase[/Voz]Adiós")

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(
            listOf(
                MpReplyEvent.Text("Hola"),
                MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                MpReplyEvent.Text("Adiós"),
            ),
            events,
        )
    }

    @Test
    fun `En la versión bilingüe no se habla la parte sin chino`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        val host = voiceHost(answer = "[Voz]Okay let's go[/Voz]", synthesizer = synthesizer)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Text("Okay let's go")), events)
        coVerify(exactly = 1) { synthesizer.synthesizeTo(any(), any(), any()) }
    }

    @Test
    fun `Cuando hay más de cinco párrafos, los párrafos adicionales se degradan a texto`() = runBlocking {
        val script = (1..7).joinToString(separator = "") { "[Voz]No. $it frase[/Voz]" }
        val host = voiceHost(answer = script)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals("Solo sintetiza los primeros cinco párrafos.", 5, events.count { it is MpReplyEvent.Voice })
        assertEquals(
            "Los dos párrafos adicionales se degradan a texto.",
            listOf(MpReplyEvent.Text("No. 6 frase"), MpReplyEvent.Text("No. 7 frase")),
            events.drop(5),
        )
    }

    @Test
    fun `Cuando la síntesis falla, este párrafo se degrada a texto`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        coEvery { synthesizer.synthesizeTo(any(), any(), any()) } returns TtsSynthResult.Failure("La síntesis falló")
        val host = voiceHost(answer = "[Voz]Lee esta frase[/Voz]", synthesizer = synthesizer)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Text("Lee esta frase")), events)
    }

    @Test
    fun `Este párrafo también se degrada a texto cuando el archivo no se puede guardar`() = runBlocking {
        val store = archiver(archivedUri = null)
        val host = voiceHost(answer = "[Voz]Lee esta frase[/Voz]", store = store)

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Text("Lee esta frase")), events)
    }

    @Test
    fun `Sintetizado sin emoción`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        coEvery { synthesizer.synthesizeTo(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Success
        }
        val host = voiceHost(answer = "[Voz]Lee esta frase[/Voz]", synthesizer = synthesizer)

        host.respond("a1", USER_TURN).toList()

        coVerify(exactly = 1) { synthesizer.synthesizeTo("Lee esta frase", any(), null) }
    }

    @Test
    fun `La colocación de pedidos y las etiquetas no cerradas no están incluidas en el texto`() = runBlocking {
        val host = voiceHost(answer = "[Voz]Lee esta frase[/Voz][/Voz] fin[Voz]la mitad")

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(
            listOf(
                MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                // La señal de estar solo solo se borra,Deletrear un párrafo de texto antes y después
                MpReplyEvent.Text("finla mitad"),
            ),
            events,
        )
    }

    @Test
    fun `Si el marcado puro no tiene cuerpo, se considerará fallido`() = runBlocking {
        val host = voiceHost(answer = "[Voz][/Voz]")

        val events = host.respond("a1", USER_TURN).toList()

        assertEquals(listOf(MpReplyEvent.Failed), events)
    }

    @Test
    fun `La identificación china solo se fija en los caracteres chinos y no en la puntuación ni en las letras latinas`() {
        assertTrue(hasChineseCharacters("buenas noches"))
        assertTrue(hasChineseCharacters("Okay, buenas noches"))
        assertTrue(hasChineseCharacters("Okay let's go"))
        assertFalse(hasChineseCharacters("\"\"\"\"."))
    }

    // ── accesorio ────────────────────────────────────────────────────────────

    /** La respuesta generada en una ronda está dada por [answer] decidir,Puerto de archivo y síntesis de voz. */
    private fun voiceHost(
        answer: String,
        synthesizer: VoiceSynthesizer = okSynthesizer(),
        store: VoiceMessageArchiver = archiver(archivedUri = VOICE_URI),
    ): AppMiniPhoneHost {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(MpReplyEvent.Text(answer))
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery {
            contextBuilder.buildMessages(assistant = any(), userContent = any(), sessionId = any(), voiceMessageEnabled = any())
        } answers {
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "eres un ling"),
                UIMessage(role = MessageRole.USER, content = secondArg()),
            )
        }
        return AppMiniPhoneHost(
            assistantRepository = defaultRepo(),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            textGenerator = mockk<MiniPhoneTextGenerator>(relaxed = true),
            contextBuilder = contextBuilder,
            toolTurnRunner = runner,
            voiceSynthesizer = synthesizer,
            voiceStore = store,
            cacheDir = tempDir.newFolder("cache"),
        )
    }

    private fun okSynthesizer(): VoiceSynthesizer = mockk {
        coEvery { synthesizeTo(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Success
        }
    }

    private fun archiver(archivedUri: String?): VoiceMessageArchiver = mockk(relaxed = true) {
        coEvery { persist(any(), any()) } returns archivedUri
        every { probeDurationMs(any()) } returns DURATION_MS
    }

    private fun defaultRepo(): AssistantRepository {
        val repo = mockk<AssistantRepository>(relaxed = true)
        val assistant = AssistantEntity(id = "a1", name = "un ling")
        every { repo.observeAll } returns flowOf(listOf(assistant))
        coEvery { repo.getById("a1") } returns assistant
        return repo
    }

    private companion object {
        val USER_TURN = listOf(MpChatTurn(MpActorType.USER, "¿Está ahí?"))
        const val TEMP_BYTES: Int = 64
        const val VOICE_URI: String = "file:///data/voice_messages/abc.wav"
        const val DURATION_MS: Long = 1_500L
    }
}
