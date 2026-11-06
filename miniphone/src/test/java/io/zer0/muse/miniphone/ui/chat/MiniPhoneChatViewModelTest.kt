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
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Estado de la página de chat:Enviar bucle, serialización, borrar no leídos, paginación inversa.
 *
 * Úsalo de verdad Room(banco de memoria)Verifique el asunto de "entregar primero y luego solicitar" —— El DAO falso solo puede probar la secuencia de llamada,
 * No se puede demostrar que Curry realmente tenga usuarios primero.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatViewModelTest {

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
    fun userMessageIsPersistedBeforeTheCharacterIsAsked() = runBlocking {
        seedThread()
        val host = GatedHost(answer = "en")
        val viewModel = createViewModel(host)

        viewModel.send("¿Está ahí?")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }

        // La compilación ya se está ejecutando.,Ya debe haber información del usuario en la biblioteca en este momento.,Y aún no hay respuesta de rol
        assertEquals(listOf(MpActorType.USER), storedSenders())
        assertEquals("¿Está ahí?", host.lastHistory.last().content)

        host.letThrough()

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        assertEquals(listOf(MpActorType.USER, MpActorType.CHARACTER), storedSenders())
        assertEquals(listOf("¿Está ahí?", "en"), bubbleContents(state))
        assertEquals("ali", state.peerName)
    }

    @Test
    fun failedReplyBecomesASystemNotice() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = null))

        viewModel.send("¿Está ahí?")

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        val notice = state.messages.last()
        assertEquals(MpMessageType.SYSTEM, notice.type)
        assertEquals("La otra parte no respondió.", notice.content)
        assertFalse("El sistema solicita no participar en la división de columnas izquierda y derecha", notice.isMine)
        assertEquals(listOf(MpActorType.USER, MpActorType.SYSTEM), storedSenders())
    }

    @Test
    fun activityIsShownWhileWorkingAndClearedAfterTheText() = runBlocking {
        seedThread()
        val host = GatedHost(answer = "lo encontré")
        val viewModel = createViewModel(host)

        viewModel.send("Ayúdame a encontrarlo")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }

        val working = awaitChat(viewModel) { it.activity != null }
        assertEquals(MpActivityKind.SEARCHING, working.activity)
        assertEquals("El recordatorio de actividad no ingresa a la lista de mensajes", 1, working.messages.size)

        host.letThrough()

        val done = awaitChat(viewModel) { chat -> chat.messages.size == 2 && chat.activity == null }
        assertEquals("lo encontré", done.messages.last().content)
    }

    @Test
    fun repliesQueueUpInsteadOfRunningConcurrently() = runBlocking {
        seedThread()
        val host = GatedHost(answer = "bueno")
        val viewModel = createViewModel(host)

        viewModel.send("primera frase")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }
        // Si la respuesta aún no ha llegado, enviaré otra.:permitir,Pero solo puedes hacer cola
        viewModel.send("segunda frase")
        // Espere hasta que la segunda oración se coloque en la biblioteca antes de publicarla. Primera respuesta: de lo contrario, "quién lo coloca primero en la biblioteca" depende de la programación del hilo.,en lugar de comportamiento
        withTimeout(TIMEOUT_MS) {
            while (db.messageDao().countInThread(THREAD_ID) < 2) delay(5L)
        }
        assertEquals("El primero aún no ha vuelto, por lo que no deberías iniciar la segunda generación.", 1, host.callCount)

        host.letThrough()

        val state = awaitChat(viewModel) { chat -> chat.messages.size == 4 && !chat.isGenerating }
        assertEquals(2, host.callCount)
        assertEquals("Dos generaciones no pueden superponerse", 1, host.maxConcurrent)
        // Los mensajes de usuario no esperan a la red.:La segunda frase se elimina primero.,Se ponen en cola dos respuestas una tras otra.
        assertEquals(listOf("primera frase", "segunda frase", "bueno", "bueno"), bubbleContents(state))
    }

    @Test
    fun openingTheChatClearsUnreadCount() = runBlocking {
        seedThread(unread = 5)
        assertEquals(5, db.threadDao().unreadCount(THREAD_ID))

        createViewModel(FakeHost(answer = "en"))

        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }
    }

    @Test
    fun reEnteringTheChatClearsUnreadAgain() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = "en"))
        awaitChat(viewModel) { chat -> chat.characterId.isNotEmpty() }

        // Reutilizar el mismo ViewModel en la misma sesión: hay que borrar el nuevo no leído al ingresar nuevamente a la página
        db.threadDao().setUnread(THREAD_ID, 3)
        viewModel.markRead()

        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }
    }

    @Test
    fun newMessageWhileChatIsVisibleClearsUnread() = runBlocking {
        seedThread(unread = 1)
        val viewModel = createViewModel(FakeHost(answer = "en"))
        // Página visible:Limpia la página una vez,Luego, bórrelo nuevamente cada vez que llegue un mensaje nuevo.
        viewModel.setVisible(true)
        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }

        // Coloca un mensaje de personaje en segundo plano y agrega uno como no leído(Utilice la misma ruta de escritura que el mensaje privado activo)
        db.messageDao().insert(
            MpMessageEntity(
                id = "bg-1",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "¿Estás ocupado?",
                createdAt = NOW_BASE + MINUTE_MS,
            ),
        )
        db.threadDao().increaseUnread(THREAD_ID)

        // Si la cantidad de mensajes cambia, bórrelo nuevamente.:El usuario está viendo esta conversación.,No debería haber una marca de no leído en la lista.
        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().unreadCount(THREAD_ID) != 0) delay(POLL_INTERVAL_MS)
        }
        viewModel.setVisible(false)
    }

    @Test
    fun leavingTheChatStopsClearingUnread() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = "en"))
        viewModel.setVisible(true)
        awaitChat(viewModel) { chat -> chat.characterId.isNotEmpty() }
        viewModel.setVisible(false)

        // Después de salir de la página, apareció otro mensaje de fondo.:Debe permanecer sin leer,De lo contrario, el usuario nunca verá el mensaje.
        db.messageDao().insert(
            MpMessageEntity(
                id = "bg-2",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "¿Has dormido?",
                createdAt = NOW_BASE + MINUTE_MS,
            ),
        )
        db.threadDao().increaseUnread(THREAD_ID)
        delay(QUIET_CHECK_MS)

        assertEquals(1, db.threadDao().unreadCount(THREAD_ID))
    }

    @Test
    fun firstPageLoadsThirtyAndOlderPageCompletesTheHistory() = runBlocking {
        seedThread(messageCount = 40)
        val viewModel = createViewModel(FakeHost(answer = "en"))

        val first = awaitChat(viewModel) { chat -> chat.messages.size == CHAT_PAGE_SIZE }
        assertTrue("hay noticias anteriores", first.hasMore)
        assertEquals((11..40).map { "No. $it frase" }, bubbleContents(first))

        viewModel.loadOlder()

        val full = awaitChat(viewModel) { chat -> chat.messages.size == 40 }
        assertFalse("Todo el historial ha sido cargado.", full.hasMore)
        assertEquals((1..40).map { "No. $it frase" }, bubbleContents(full))
        assertTrue("Las noticias más antiguas son lo primero", full.messages.first().createdAt < full.messages.last().createdAt)
    }

    @Test
    fun unknownCharacterFallsBackToThePlaceholderName() = runBlocking {
        seedThread()
        val viewModel = createViewModel(FakeHost(answer = "en", characters = listOf(MpCharacter(id = "someone-else", name = "otros"))))

        val state = awaitChat(viewModel) { chat -> chat.peerName.isNotEmpty() }
        assertEquals("Rol desconectado", state.peerName)
    }

    @Test
    fun failedSendIsMarkedAndResendingTurnsItGreen() = runBlocking {
        seedThread()
        val host = SwitchableHost(answer = null)
        val viewModel = createViewModel(host)

        viewModel.send("¿Está ahí?")
        val failed = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        val mine = failed.messages.first { it.isMine }
        assertEquals("No salió ni una sola palabra después de toda la ronda.,La entrada del usuario se considera un fracaso.", MpMessageStatus.FAILED, mine.status)
        assertEquals(MpMessageStatus.FAILED, db.messageDao().getMessage(mine.id)?.status)

        // Reenviar:La misma línea vuelve a "Enviando",Después de recibir la respuesta, se envía.
        host.answer = "en"
        viewModel.resend(mine.id)

        val done = awaitChat(viewModel) { chat -> chat.messages.lastOrNull()?.content == "en" && !chat.isGenerating }
        assertEquals(MpMessageStatus.SENT, db.messageDao().getMessage(mine.id)?.status)
        assertEquals(MpMessageStatus.SENT, done.messages.first { it.id == mine.id }.status)
        assertEquals("La reedición no puede contener más que la misma.", 1, done.messages.count { it.isMine && it.content == "¿Está ahí?" })
    }

    @Test
    fun failedSendStartsAsSendingAndEndsAsFailedInTheDatabase() = runBlocking {
        seedThread()
        val host = GatedHost(answer = null)
        val viewModel = createViewModel(host)

        viewModel.send("¿Está ahí?")
        withTimeout(TIMEOUT_MS) { host.firstCallReached.await() }

        // La solicitud aún está en camino:Este elemento es "Enviando",Ni fracaso ni éxito
        val sending = awaitChat(viewModel) { chat -> chat.messages.size == 1 }
        assertEquals(MpMessageStatus.SENDING, sending.messages.single().status)
        assertEquals(MpMessageStatus.SENDING, db.messageDao().getMessage(sending.messages.single().id)?.status)

        host.letThrough()

        val failed = awaitChat(viewModel) { chat -> chat.messages.size == 2 && !chat.isGenerating }
        assertEquals(MpMessageStatus.FAILED, failed.messages.first { it.isMine }.status)
        assertTrue("Si falla, aún aparecerá un mensaje del sistema.", failed.messages.last().isSystem)
    }

    @Test
    fun deletingAMessageRemovesItFromTheWindowAndTheDatabase() = runBlocking {
        seedThread(messageCount = 2)
        val viewModel = createViewModel(FakeHost(answer = "en"))
        val loaded = awaitChat(viewModel) { chat -> chat.messages.size == 2 }
        val target = loaded.messages.first()

        viewModel.deleteMessage(target.id)

        val after = awaitChat(viewModel) { chat -> chat.messages.size == 1 }
        assertEquals(listOf("Oración 2"), after.messages.map { it.content })
        assertNull(db.messageDao().getMessage(target.id))
        assertEquals("Solo borrar mensajes,Conversación retenida", 1, db.messageDao().countInThread(THREAD_ID))
        assertNotNull(db.threadDao().getThread(THREAD_ID))
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
            imageStore = MpImageStore(directory = File(System.getProperty("java.io.tmpdir"), "mp-chat-images"), openStream = { null }),
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

    private suspend fun seedThread(messageCount: Int = 0, unread: Int = 0) {
        db.threadDao().upsert(
            MpThreadEntity(
                id = THREAD_ID,
                characterId = CHARACTER_ID,
                unreadCount = unread,
                lastMessageAt = messageCount * MINUTE_MS,
            ),
        )
        repeat(messageCount) { index ->
            val number = index + 1
            db.messageDao().insert(
                MpMessageEntity(
                    id = "seed-$number",
                    threadId = THREAD_ID,
                    characterId = CHARACTER_ID,
                    sender = if (number % 2 == 0) MpActorType.CHARACTER else MpActorType.USER,
                    type = MpMessageType.TEXT,
                    content = "No. $number frase",
                    createdAt = number * MINUTE_MS,
                ),
            )
        }
    }

    private suspend fun storedSenders(): List<MpActorType> = db.messageDao().observeMessages(THREAD_ID).first().map { it.sender }

    private suspend fun awaitChat(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    /** Obtener solo líneas de mensaje,La separación temporal no permite comparación. */
    private fun bubbleContents(state: ChatUiState): List<String> =
        state.items.filterIsInstance<ChatItem.Message>().map { it.message.content }

    private companion object {
        const val THREAD_ID: String = "t1"
        const val CHARACTER_ID: String = "c1"
        const val TIMEOUT_MS: Long = 5_000L
        const val POLL_INTERVAL_MS: Long = 5L

        /** El período de ventana que se debe esperar al verificar \"No más lectura después de abandonar la página\":mucho más largo que el intervalo de votación,Lo suficiente como para provocar la huelga de un coleccionista defectuoso. */
        const val QUIET_CHECK_MS: Long = 300L
        const val MINUTE_MS: Long = 60_000L

        /** El punto de partida del reloj falso,Se garantiza que el nuevo mensaje debe ser más reciente que el mensaje inicial. */
        const val NOW_BASE: Long = 10_000_000L
    }
}

/** Un anfitrión que puede cambiar sus palabras a mitad de camino.:No hay respuesta por primera vez.,poner [answer] Una vez cumplimentado, regresar con normalidad. */
private class SwitchableHost(
    var answer: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "ali")),
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        val reply = answer
        emit(if (reply == null) MpReplyEvent.Failed else MpReplyEvent.Text(reply))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** El anfitrión que da la respuesta de inmediato.`answer` Emitido cuando es nulo `Failed`,Corresponde a \"la otra parte no respondió\". */
private class FakeHost(
    private val answer: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "ali")),
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        emit(if (answer == null) MpReplyEvent.Failed else MpReplyEvent.Text(answer))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/**
 * Un anfitrión estancado:Primero di "lo estoy buscando",y luego esperar hasta [letThrough] Sólo da el texto.
 *
 * Por cierto, registre la cantidad de llamadas y la concurrencia máxima. —— Lo del "serial" sólo se puede leer desde aquí.
 */
private class GatedHost(
    private val answer: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "ali")),
) : MiniPhoneHost {

    val firstCallReached: CompletableDeferred<Unit> = CompletableDeferred()

    @Volatile
    var callCount: Int = 0
        private set

    @Volatile
    var maxConcurrent: Int = 0
        private set

    @Volatile
    var lastHistory: List<MpChatTurn> = emptyList()
        private set

    private val release: CompletableDeferred<Unit> = CompletableDeferred()

    @Volatile
    private var active: Int = 0

    fun letThrough() {
        release.complete(Unit)
    }

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        callCount += 1
        lastHistory = history
        active += 1
        maxConcurrent = maxOf(maxConcurrent, active)
        try {
            emit(MpReplyEvent.Activity(MpActivityKind.SEARCHING))
            firstCallReached.complete(Unit)
            release.await()
            emit(if (answer == null) MpReplyEvent.Failed else MpReplyEvent.Text(answer))
        } finally {
            active -= 1
        }
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}
