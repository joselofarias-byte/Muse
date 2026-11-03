package io.zer0.muse.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.MediaGenKind
import io.zer0.muse.tools.MediaGenResult
import io.zer0.muse.tools.MediaGenSlot
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * Cuando se envían varias voces en la misma ronda, los mensajes pertenecen a la barandilla.
 *
 * El mensaje de voz solo ocupa un campo,La segunda escritura sobrescribirá la anterior. —— Entonces esta prueba se centra en
 * [ChatMediaGenHost] desvío:El primero está colgado en el mensaje asistente de esta ronda.,La segunda publicación y las siguientes tienen cada una un mensaje nuevo.,
 * Y el pedido es igual al pedido de entrega. fotos/Se debe mantener la ruta del vídeo."Sólo se genera un mensaje".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatMediaGenHostVoiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `En la misma ronda, tres voces caen sobre tres mensajes del asistente y están ordenadas en el orden de entrega`() = runBlocking {
        val fixture = displayedFixture()
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val messages = fixture.accessor.messagesSnapshot
        assertEquals("Mensaje ancla + dos mensajes nuevos", 3, messages.size)
        assertEquals("El primero todavía está colgado en el mensaje ancla.", ANCHOR_ID, messages.first().id)
        assertTrue("Los tres son mensajes del asistente.", messages.all { it.role == MessageRole.ASSISTANT })
        assertEquals("Las voces están ordenadas en orden de entrega y no se cubren entre sí.", listOf(VOICE_1, VOICE_2, VOICE_3), messages.map { it.voiceUri })
        assertEquals("La duración depende de cada voz.", listOf(1000L, 2000L, 3000L), messages.map { it.voiceDurationMs })
    }

    @Test
    fun `El primer mensaje de voz sólo está vinculado al mensaje ancla y no envía mensajes adicionales`() = runBlocking {
        val fixture = displayedFixture()
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val messages = fixture.accessor.messagesSnapshot
        assertEquals(VOICE_1, messages.first().voiceUri)
        assertEquals("Sólo se añaden dos mensajes nuevos.", 2, messages.count { it.id != ANCHOR_ID })
        val placeholder = context.getString(R.string.err_chat_voice_generated)
        assertEquals("Los mensajes nuevos utilizan la misma copia de marcador de posición", placeholder, messages[1].content)
        assertEquals(placeholder, messages[2].content)
        // Tanto la colocación como el registro de cierre deben cubrir nueva información.,De lo contrario, los nuevos mensajes de voz desaparecerán después de reiniciar.
        assertEquals(3, fixture.persisted.toSet().size)
        assertEquals(3, fixture.registered.toSet().size)
    }

    @Test
    fun `Cuando el mensaje ancla ya tenga texto, el texto no se sobrescribirá`() = runBlocking {
        val fixture = displayedFixture(anchorContent = EXISTING_TEXT)
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val messages = fixture.accessor.messagesSnapshot
        assertTrue("El texto principal está cubierto por una copia de marcador de posición.: ${messages.first().content}", messages.first().content.contains(EXISTING_TEXT))
        assertEquals(VOICE_1, messages.first().voiceUri)
        assertEquals("Los dos últimos artículos tienen cada uno nuevas novedades.", 2, messages.count { it.id != ANCHOR_ID })
        assertEquals(listOf(VOICE_2, VOICE_3), messages.drop(1).map { it.voiceUri })
    }

    @Test
    fun `Publicar una imagen todavía solo genera un mensaje`() = runBlocking {
        val repository = mockk<SessionRepository>(relaxed = true)
        coEvery { repository.toPersistableImageUrls(any(), any()) } returns listOf(PERSISTED_IMAGE)
        val fixture = displayedFixture(repository = repository)
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.IMAGE))

        slot.report(MediaGenResult.Success(imageUrls = listOf(RAW_IMAGE)))

        val messages = fixture.accessor.messagesSnapshot
        assertEquals("La ruta de la imagen mantiene una imagen y un mensaje.", 1, messages.size)
        assertEquals(ANCHOR_ID, messages.single().id)
        assertEquals(listOf(PERSISTED_IMAGE), messages.single().imageUrls)
    }

    @Test
    fun `Cuando no se muestra la conversación, se insertan tres voces en la biblioteca y se adjuntan a diferentes mensajes a través de adjuntarMediaToMessage`() = runBlocking {
        val repository = VoiceRepositoryFake()
        val fixture = displayedFixture(displayed = false, repository = repository.asMock())
        val slot = requireNotNull(fixture.host.begin(MediaGenKind.VOICE))

        deliverThree(slot)

        val targets = repository.attachTargets
        assertEquals("Cuelga una vez por cada una de las tres voces.", 3, targets.size)
        assertEquals("El primer mensaje ancla", ANCHOR_ID, targets.first())
        assertEquals("Los tres mensajes de destino son diferentes entre sí.", 3, targets.toSet().size)
        assertNotEquals("Los dos últimos deben ser mensajes nuevos.", ANCHOR_ID, targets[1])
        assertEquals("Dos nuevos mensajes", 2, repository.appended.size)
        assertEquals("Se insertan nuevos mensajes en el pedido de entrega.", listOf(targets[1], targets[2]), repository.appended.map { it.id })
        assertEquals("El primer mensaje de voz recae sobre el mensaje ancla.", VOICE_1, repository.message(ANCHOR_ID)?.voiceUri)
        assertEquals(
            "Las voces llegan al almacén en el orden de entrega.",
            listOf(VOICE_2, VOICE_3),
            listOf(targets[1], targets[2]).map { repository.message(it)?.voiceUri },
        )
        assertEquals(
            "El contenido del nuevo mensaje es la misma copia del marcador de posición de la oración.",
            context.getString(R.string.err_chat_voice_generated),
            repository.message(targets[1])?.content,
        )
        assertEquals("Las ramas que no se muestran no agregan mensajes de memoria", 1, fixture.accessor.messagesSnapshot.size)
        assertNull("El mensaje ancla en la memoria no está escrito por voz.", fixture.accessor.messagesSnapshot.single().voiceUri)
    }

    // ── accesorio ────────────────────────────────────────────────────────────

    private class Fixture(
        val host: ChatMediaGenHost,
        val accessor: FakeAccessor,
        val persisted: MutableList<Uuid>,
        val registered: MutableList<Uuid>,
    )

    private fun displayedFixture(
        displayed: Boolean = true,
        anchorContent: String = "",
        repository: SessionRepository = mockk(relaxed = true),
    ): Fixture {
        val accessor = FakeAccessor(if (displayed) SESSION_ID else OTHER_SESSION_ID)
        accessor.seed(listOf(anchor(anchorContent)))
        val persisted = mutableListOf<Uuid>()
        val registered = mutableListOf<Uuid>()
        val coordinator = ChatStreamCoordinator(
            accessor = accessor,
            sessionRepository = repository,
            memoryTicker = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            appContext = context,
            notificationManager = mockk(relaxed = true),
            assistantRepository = mockk(relaxed = true),
            visionBridge = mockk(relaxed = true),
            toolRegistry = mockk(relaxed = true),
            skillRepository = mockk(relaxed = true),
            idListJson = Json,
            lorebookRepository = mockk(relaxed = true),
            promptInjectionRepository = mockk(relaxed = true),
            transformerPipeline = mockk(relaxed = true),
            checkpointReader = mockk(relaxed = true),
        )
        val host = ChatMediaGenHost(
            accessor = accessor,
            sessionRepository = repository,
            streamCoordinator = coordinator,
            appContext = context,
            toolAssistantIdProvider = { ANCHOR_ID },
            toolSessionIdProvider = { SESSION_ID },
            generationTokenProvider = { GENERATION_TOKEN },
            updateAssistant = { id, content -> coordinator.appendMediaToAssistant(id = id, content = content) },
            persistMediaMessage = { _, id -> persisted += id },
            registerMediaMessage = { id -> registered += id },
        )
        return Fixture(host, accessor, persisted, registered)
    }

    private fun anchor(content: String) = UIMessage(
        id = ANCHOR_ID,
        role = MessageRole.ASSISTANT,
        content = content,
    )

    private suspend fun deliverThree(slot: MediaGenSlot) {
        slot.report(MediaGenResult.Success(voiceUri = VOICE_1, voiceDurationMs = 1000))
        slot.report(MediaGenResult.Success(voiceUri = VOICE_2, voiceDurationMs = 2000))
        slot.report(MediaGenResult.Success(voiceUri = VOICE_3, voiceDurationMs = 3000))
    }

    /** estado de memoria [ChatStateAccessor]:Lista de mensajes + sesión de visualización actual. */
    private class FakeAccessor(displayedSessionId: String?) : ChatStateAccessor {
        private val state = MutableStateFlow(
            ChatUiState(sessionState = ChatSessionState(currentSessionId = displayedSessionId)),
        )
        private val messages = MutableStateFlow<List<UIMessage>>(emptyList())

        override val snapshot: ChatUiState get() = state.value

        override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)

        override val messagesSnapshot: List<UIMessage> get() = messages.value

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = messages.update(transform)

        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        fun seed(list: List<UIMessage>) {
            messages.value = list
        }
    }

    /** Suplente del repositorio de sesiones con estado:Los mensajes anclados con adjuntaMediaToMessage recuerdan su propio discurso. */
    private class VoiceRepositoryFake {

        private val stored = linkedMapOf(ANCHOR_ID.toString() to anchor(""))
        val attachTargets = mutableListOf<Uuid>()
        val appended = mutableListOf<UIMessage>()

        fun message(id: Uuid): UIMessage? = stored[id.toString()]

        fun asMock(): SessionRepository = mockk<SessionRepository>(relaxed = true).also { repository ->
            coEvery { repository.getMessageAsUiMessage(any()) } answers {
                stored[invocation.args[0] as String]
            }
            coEvery { repository.appendMessage(any(), any()) } answers {
                val message = invocation.args[1] as UIMessage
                stored[message.id.toString()] = message
                appended += message
                message.id.toString()
            }
            coEvery {
                repository.attachMediaToMessage(any(), any(), any(), any(), any(), any(), any())
            } answers {
                val args = invocation.args
                val messageId = args[1] as Uuid
                attachTargets += messageId
                stored[messageId.toString()] = stored.getValue(messageId.toString()).copy(
                    content = args[2] as String,
                    voiceUri = args[5] as String?,
                    voiceDurationMs = args[6] as Long,
                )
            }
        }

        private fun anchor(content: String) = UIMessage(
            id = ANCHOR_ID,
            role = MessageRole.ASSISTANT,
            content = content,
        )
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val OTHER_SESSION_ID = "session-2"
        const val GENERATION_TOKEN = 7L
        const val EXISTING_TEXT = "que decir primero"
        const val VOICE_1 = "file:///voice/1.wav"
        const val VOICE_2 = "file:///voice/2.wav"
        const val VOICE_3 = "file:///voice/3.wav"
        const val RAW_IMAGE = "data:image/png;base64,AAAA"
        const val PERSISTED_IMAGE = "file:///images/1.png"

        val ANCHOR_ID: Uuid = Uuid.random()
    }
}
