package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.chat.MpGroupChatStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Cuando una ronda de respuestas contiene voz,Cómo almacenar y organizar mensajes.
 *
 * El orden de los eventos dado por el anfitrión es el orden de los mensajes.:una pieza de texto,Una pieza de voz. Sólo se guarda el texto del mensaje de voz.
 * Vista previa de copia para lista de conversaciones,La referencia de audio y la duración ingresan cada una en sus propios campos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatVoiceMessageTest {

    private lateinit var db: MiniPhoneDb
    private var idCounter: Int = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = newInMemoryMiniPhoneDb()
        idCounter = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun textAndVoiceSegmentsLandInEventOrder() = runBlocking {
        seedThread()
        val viewModel = createViewModel(
            ScriptHost(
                listOf(
                    MpReplyEvent.Text("habla primero"),
                    MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                    MpReplyEvent.Text("hablar de nuevo"),
                ),
            ),
        )

        viewModel.send("¿Está ahí?")

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 4 && !chat.isGenerating }
        assertEquals(
            listOf(MpMessageType.TEXT, MpMessageType.TEXT, MpMessageType.VOICE, MpMessageType.TEXT),
            state.messages.map { it.type },
        )
        val voice = state.messages[2]
        assertEquals(MpActorType.CHARACTER, voice.sender)
        assertEquals("[Voz]", voice.content)
        assertEquals(VOICE_URI, voice.mediaUri)
        assertEquals(DURATION_MS, voice.voiceDurationMs)
        assertTrue("Los mensajes de voz toman la rama de representación de voz", voice.isVoice)
    }

    @Test
    fun storedVoiceRowKeepsTheAudioReferenceAndDuration() = runBlocking {
        seedThread()
        val viewModel = createViewModel(ScriptHost(listOf(MpReplyEvent.Voice(VOICE_URI, DURATION_MS))))

        viewModel.send("¿Está ahí?")
        awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }

        val stored = db.messageDao().observeMessages(THREAD_ID).first().last()
        assertEquals(MpMessageType.VOICE, stored.type)
        assertEquals(VOICE_URI, stored.mediaUri)
        assertEquals(DURATION_MS, stored.voiceDurationMs)
        assertEquals("[Voz]", stored.content)
    }

    @Test
    fun activitiesBeforeTheVoiceDoNotBecomeMessages() = runBlocking {
        seedThread()
        val viewModel = createViewModel(
            ScriptHost(
                listOf(
                    MpReplyEvent.Activity(MpActivityKind.SEARCHING),
                    MpReplyEvent.Voice(VOICE_URI, DURATION_MS),
                ),
            ),
        )

        viewModel.send("¿Está ahí?")

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        assertEquals("El recordatorio de actividad no ingresa a la lista de mensajes", listOf(MpMessageType.TEXT, MpMessageType.VOICE), state.messages.map { it.type })
        assertEquals("Ocultar recordatorio de actividad una vez finalizada", null, state.activity)
    }

    private fun createViewModel(host: MiniPhoneHost): MiniPhoneChatViewModel {
        var clock = NOW_BASE
        return MiniPhoneChatViewModel(
            threadId = THREAD_ID,
            messageDao = db.messageDao(),
            threadDao = db.threadDao(),
            memberDao = db.threadMemberDao(),
            groupStore = MpGroupChatStore(
                db = db,
                threadDao = db.threadDao(),
                memberDao = db.threadMemberDao(),
                messageDao = db.messageDao(),
            ),
            host = host,
            imageStore = MpImageStore(
                directory = File(System.getProperty("java.io.tmpdir"), "mp-chat-voices"),
                openStream = { null },
            ),
            clock = ChatWriteClock(
                now = {
                    clock += 1_000L
                    clock
                },
                newId = {
                    idCounter += 1
                    "new-$idCounter"
                },
            ),
        )
    }

    private suspend fun seedThread() {
        db.threadDao().upsert(
            MpThreadEntity(
                id = THREAD_ID,
                characterId = CHARACTER_ID,
                unreadCount = 0,
                lastMessageAt = 0L,
            ),
        )
    }

    private suspend fun awaitChat(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    private companion object {
        const val THREAD_ID: String = "t1"
        const val CHARACTER_ID: String = "c1"
        const val TIMEOUT_MS: Long = 5_000L
        const val NOW_BASE: Long = 10_000_000L
        const val VOICE_URI: String = "file:///data/voice_messages/abc.wav"
        const val DURATION_MS: Long = 7_000L
    }
}

/** Envía hosts de eventos en un orden determinado:Se utiliza para precisar la \"secuencia de eventos\". = Secuencia de mensajes\". */
private class ScriptHost(
    private val events: List<MpReplyEvent>,
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(listOf(MpCharacter(id = "c1", name = "ali")))

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        events.forEach { emit(it) }
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
