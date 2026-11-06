package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MiniPhoneHost
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
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import io.zer0.muse.miniphone.ui.shell.UNKNOWN_CHARACTER_NAME
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * La ubicación, selección y ritmo del chat grupal.
 *
 * Úsalo de verdad Room(banco de memoria):"Quién dijo qué" y "quién lo dejó caer primero" son afirmaciones que se leen desde aquí.
 * El anfitrión utiliza un sustituto para registrar a quién se llama cada vez, el historial visto en ese momento y cuántos mensajes hay en la biblioteca. ——
 * La serialización y el estancamiento sólo pueden probarse de esta manera.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneGroupChatViewModelTest {

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
    fun groupMessagesRecordTheSpeakerAndTheOwnersMessageRecordsNothing() = runBlocking {
        seedGroup()
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("Buenos dias a todos")

        val state = awaitState(viewModel) { it.messages.size == 3 && !it.isGenerating }
        val stored = storedMessages()

        assertEquals("no recuerdo el papel", listOf(""), stored.filter { it.sender == MpActorType.USER }.map { it.characterId })
        assertEquals("Ambas respuestas recuerdan el papel de orador.", host.calls.toSet(), stored.filter { it.sender == MpActorType.CHARACTER }.map { it.characterId }.toSet())
        assertTrue(state.isGroup)
        assertEquals("Ayuan, Xiaoman", state.groupTitle)
        assertEquals(listOf("Ayuan", "Xiaoman"), state.members.map { it.name })
        assertEquals("El número de personas en la columna superior te incluye a ti.", 3, state.memberCount)
    }

    @Test
    fun groupMembersArriveInJoinOrderAndKeepThePlaceholderWhenACharacterIsGone() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2", "c-gone"))
        val viewModel = createViewModel(CastHost())

        val state = awaitState(viewModel) { it.members.size == 3 }

        assertEquals(listOf("c1", "c2", "c-gone"), state.members.map { it.characterId })
        assertEquals(listOf("Ayuan", "Xiaoman", "Rol desconectado"), state.members.map { it.name })
        assertEquals("Incluso si falta una persona, seguimos siendo Sanxing + yo", 4, state.memberCount)
    }

    @Test
    fun fiveMemberGroupAnswersWithAtMostTwoCharacters() = runBlocking {
        seedGroup(memberIds = FIVE_MEMBER_IDS)
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 5)

        viewModel.send("buenas noches a todos")

        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 3 }
        assertEquals("Un máximo de dos personas pueden recibir un mensaje.", 2, host.calls.size)
        assertEquals("No lo puede decir dos veces la misma persona", 2, host.calls.distinct().size)
        assertEquals(2, storedMessages().count { it.sender == MpActorType.CHARACTER })
    }

    @Test
    fun aNamedMemberAlwaysAnswers() = runBlocking {
        seedGroup(memberIds = FIVE_MEMBER_IDS)
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 5)

        viewModel.send("Xiaoman, ¿estás ahí?")

        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 3 }
        assertTrue("Cualquier persona nombrada debe responder.: ${host.calls}", host.calls.contains("c2"))
        assertEquals(2, host.calls.size)
    }

    @Test
    fun aNamedMemberPlusTheRecentSpeakerAreExactlyTheTwo() = runBlocking {
        seedGroup(memberIds = FIVE_MEMBER_IDS)
        // Azen acaba de decir algo:Azen no pasará lista.,Pero Xiaoman fue nombrado,Atzen fue el orador más reciente.
        db.messageDao().insert(
            MpMessageEntity(
                id = "recent-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c4",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "Déjame decir algo primero",
                createdAt = NOW_BASE - 1_000L,
            ),
        )
        val host = CastHost()
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 5)

        viewModel.send("Xiaoman, ¿qué opinas?")

        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 4 }
        assertEquals("Los nombrados primero,Complementar a los que hablaron recientemente.", listOf("c2", "c4"), host.calls)
    }

    @Test
    fun theSecondSpeakerSeesTheFirstOnesWordsPrefixedWithTheirName() = runBlocking {
        seedGroup()
        val host = CastHost(answerOf = { characterId -> if (characterId == "c1") "en" else "yo también estoy aquí" })
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("¿Está ahí?")

        awaitState(viewModel) { !it.isGenerating && it.messages.size == 3 }
        val first = host.calls[0]
        val second = host.calls[1]
        val firstReply = if (first == "c1") "en" else "yo también estoy aquí"
        val seenBySecond = requireNotNull(host.histories[second])
        assertEquals(
            "La persona que habla después puede ver lo que dijo la persona que le precede.,y con un nombre",
            "${nameOf(first)}:$firstReply",
            seenBySecond.last().content,
        )
        assertEquals(MpActorType.CHARACTER, seenBySecond.last().sender)
    }

    @Test
    fun duplicateSubmissionStartsOnlyOneRound() = runBlocking {
        seedGroup()
        val gate = CompletableDeferred<Unit>()
        val host = CastHost(gate = gate)
        val viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("¿Está ahí?")
        awaitCalls(host, 1)
        // Se volvió a presentar la misma sentencia.(Manos temblorosas, inténtalo de nuevo.):No más rondas
        viewModel.send("¿Está ahí?")
        awaitDuplicateSettled()

        gate.complete(Unit)
        awaitState(viewModel) { !it.isGenerating && it.messages.size >= 3 }

        assertEquals("Sólo una ronda de respuesta", 2, host.calls.size)
        assertEquals("Ambos mensajes de usuario se han perdido.", 2, storedMessages().count { it.sender == MpActorType.USER })
    }

    @Test
    fun respondersRunOneAfterAnotherWithAGapBetweenThem() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2", "c3"))
        val storedAtCall = mutableListOf<Int>()
        val storedAtPause = mutableListOf<Int>()
        val host = CastHost(onCall = { _, _ -> storedAtCall += db.messageDao().countInThread(GROUP_THREAD_ID) })
        val viewModel = createViewModel(host, pause = { storedAtPause += db.messageDao().countInThread(GROUP_THREAD_ID) })
        readyGroup(viewModel, 3)

        viewModel.send("¿Está ahí?")

        awaitState(viewModel) { !it.isGenerating && it.messages.size == 3 }
        assertEquals("Detente solo una vez entre dos personajes.", 1, storedAtPause.size)
        assertEquals("La primera respuesta ya se abandonó cuando se produce la pausa.", 2, storedAtPause.single())
        assertEquals("La primera persona comienza solo con el nombre del usuario.,La segunda persona comienza con la primera respuesta.", listOf(1, 2), storedAtCall)
        assertEquals("Dos personas llaman cada una una vez", 2, host.calls.size)
    }

    @Test
    fun typingCarriesTheNameOfTheCharacterWhoIsAboutToSpeak() = runBlocking {
        seedGroup()
        val gate = CompletableDeferred<Unit>()
        val typingNames = mutableListOf<String?>()
        lateinit var viewModel: MiniPhoneChatViewModel
        val host = CastHost(gate = gate, onCall = { _, _ -> typingNames += viewModel.uiState.value.typingName })
        viewModel = createViewModel(host)
        readyGroup(viewModel, 2)

        viewModel.send("¿Está ahí?")
        awaitCalls(host, 1)

        assertEquals("Cuando la primera persona habla, su nombre aparece escrito en la interfaz.", listOf(nameOf(host.calls.single())), typingNames)

        gate.complete(Unit)
        awaitState(viewModel) { !it.isGenerating && it.messages.size == 3 }
        assertNull("Ya no tendrás que preguntarte quién estaba escribiendo después de una ronda.", viewModel.uiState.value.typingName)
    }

    @Test
    fun renamingUpdatesTheTitleAndStopsAutoRenaming() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2"), title = "Ayuan, Xiaoman")
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 2)

        viewModel.groupAdmin.renameGroup("  equipo de fin de semana ")

        val renamed = awaitState(viewModel) { state -> state.groupTitle == "equipo de fin de semana" }
        assertEquals("El nombre del grupo en la interfaz se cambiará en consecuencia.", "equipo de fin de semana", renamed.groupTitle)
        assertTrue("Curry escribió \"nombre cambiado\"", db.threadDao().getThread(GROUP_THREAD_ID)?.titleCustomized ?: false)

        viewModel.groupAdmin.addMembers(listOf("c3"))
        val afterAdd = awaitState(viewModel) { state -> state.members.size == 3 }
        assertEquals("Una vez cambiado el nombre, ya no se volverá a calcular automáticamente.", "equipo de fin de semana", afterAdd.groupTitle)
    }

    @Test
    fun addingPeopleRecalculatesTheDefaultTitle() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2"), title = "Ayuan, Xiaoman")
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 2)

        viewModel.groupAdmin.addMembers(listOf("c2", "c3"))

        val state = awaitState(viewModel) { current -> current.members.size == 3 }
        assertEquals("Las personas duplicadas que ya están en el grupo no se consideran personas nuevas.", listOf("c1", "c2", "c3"), state.members.map { it.characterId })
        assertEquals("Nombre predeterminado seguido de recálculo", "Ayuan, Xiaoman, Awu", state.groupTitle)
    }

    @Test
    fun removingSomeoneUpdatesTheMembersButKeepsTheirWords() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2", "c3"), title = "Ayuan, Xiaoman, Awu")
        db.messageDao().insert(
            MpMessageEntity(
                id = "said-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c2",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "Yo dije",
                createdAt = NOW_BASE - 1_000L,
            ),
        )
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 3)

        viewModel.groupAdmin.removeMember("c2")

        val state = awaitState(viewModel) { current -> current.members.size == 2 }
        assertEquals(listOf("c1", "c3"), state.members.map { it.characterId })
        assertEquals("Lo que dijo sigue ahí", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertEquals("El nombre predeterminado se volverá a calcular con las personas restantes.", "Ayuan, Awu", state.groupTitle)
    }

    @Test
    fun disbandingRemovesTheWholeGroup() = runBlocking {
        seedGroup(memberIds = listOf("c1", "c2"))
        val viewModel = createViewModel(CastHost())
        readyGroup(viewModel, 2)

        viewModel.groupAdmin.disbandGroup()

        withTimeout(TIMEOUT_MS) {
            while (db.threadDao().getThread(GROUP_THREAD_ID) != null) delay(POLL_MS)
        }
        assertEquals(0, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
        assertEquals(0, db.messageDao().countInThread(GROUP_THREAD_ID))
    }

    @Test
    fun directMessagesStillAnswerOnce() = runBlocking {
        db.threadDao().upsert(MpThreadEntity(id = "t-dm", characterId = "c1", lastMessageAt = NOW_BASE))
        val host = CastHost()
        val viewModel = createViewModel(host, threadId = "t-dm")

        viewModel.send("¿Está ahí?")

        awaitState(viewModel) { !it.isGenerating && it.messages.size == 2 }
        assertEquals("Sólo una persona puede contestar el mensaje privado.", listOf("c1"), host.calls)
        val stored = db.messageDao().observeMessages("t-dm").first()
        assertEquals("También recuerdo el papel conversacional de mi frase en el mensaje privado.", "c1", stored.first { it.sender == MpActorType.USER }.characterId)
    }

    private fun createViewModel(
        host: MiniPhoneHost,
        threadId: String = GROUP_THREAD_ID,
        pause: suspend () -> Unit = {},
    ): MiniPhoneChatViewModel {
        var clock = NOW_BASE
        return MiniPhoneChatViewModel(
            threadId = threadId,
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
            imageStore = MpImageStore(directory = File(System.getProperty("java.io.tmpdir"), "mp-group-images"), openStream = { null }),
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
            pauseBetweenResponders = pause,
        )
    }

    private suspend fun seedGroup(memberIds: List<String> = listOf("c1", "c2"), title: String = "Ayuan, Xiaoman") {
        db.threadDao().upsert(
            MpThreadEntity(
                id = GROUP_THREAD_ID,
                characterId = memberIds.first(),
                lastMessageAt = NOW_BASE,
                isGroup = true,
                title = title,
            ),
        )
        db.threadMemberDao().upsertAll(
            memberIds.mapIndexed { index, characterId ->
                MpThreadMemberEntity(threadId = GROUP_THREAD_ID, characterId = characterId, joinedAt = index.toLong())
            },
        )
    }

    private suspend fun storedMessages(): List<MpMessageEntity> = db.messageDao().observeMessages(GROUP_THREAD_ID).first()

    private suspend fun awaitState(viewModel: MiniPhoneChatViewModel, predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) }

    /** Espere hasta que llamen al anfitrión [count] veces:El registro es asincrónico,En el momento en que se envía el mensaje no ha comenzado a generarse. */
    private suspend fun awaitCalls(host: CastHost, count: Int) = withTimeout(TIMEOUT_MS) {
        while (host.calls.size < count) delay(POLL_MS)
    }

    /** Espere a que se reconozcan los envíos duplicados:El segundo mensaje de usuario cae directamente en \"Enviado\",No tiene su propia rueda de respuestas. */
    private suspend fun awaitDuplicateSettled() = withTimeout(TIMEOUT_MS) {
        while (true) {
            val mine = storedMessages().filter { it.sender == MpActorType.USER }.sortedBy { it.createdAt }
            if (mine.size >= 2 && mine.last().status == MpMessageStatus.SENT) return@withTimeout
            delay(POLL_MS)
        }
    }

    /**
     * Espere hasta que la lista de miembros esté lista antes de enviar un mensaje.
     *
     * La lista de miembros y la lista de roles son dos corrientes independientes.:El pase de lista no se puede igualar antes de que llegue el nombre.,Espera hasta que lleguen todos primero.,
     * Las siguientes afirmaciones ilustran las reglas de selección.,En lugar de "qué estado ocurrió en ese segundo".
     */
    private suspend fun readyGroup(viewModel: MiniPhoneChatViewModel, size: Int): ChatUiState = withTimeout(TIMEOUT_MS) {
        viewModel.uiState.first { state ->
            state.members.size == size && state.members.none { it.name == UNKNOWN_CHARACTER_NAME }
        }
    }

    private fun nameOf(characterId: String): String = CAST.first { it.id == characterId }.name

    private companion object {
        const val GROUP_THREAD_ID: String = "g1"
        const val NOW_BASE: Long = 10_000_000L
        const val TIMEOUT_MS: Long = 5_000L
        const val POLL_MS: Long = 10L

        val CAST: List<MpCharacter> = listOf(
            MpCharacter(id = "c1", name = "Ayuan"),
            MpCharacter(id = "c2", name = "Xiaoman"),
            MpCharacter(id = "c3", name = "Awu"),
            MpCharacter(id = "c4", name = "azen"),
            MpCharacter(id = "c5", name = "Ah Chi"),
        )
        val FIVE_MEMBER_IDS: List<String> = CAST.map { it.id }
    }
}

/**
 * Un anfitrión que recuerda cosas.:Quién fue llamado, qué historia se vio y cuántos mensajes tenía Curry cuando lo llamaron por primera vez.
 *
 * [gate] Se utiliza para detener una ronda de generación atrapada en el medio.:La prueba debe verificar el estado de la interfaz o enviar otro mensaje en este momento.
 */
private class CastHost(
    private val answerOf: (String) -> String? = { "bueno" },
    private val gate: CompletableDeferred<Unit>? = null,
    private val onCall: suspend (String, List<MpChatTurn>) -> Unit = { _, _ -> },
) : MiniPhoneHost {

    val calls: MutableList<String> = mutableListOf()

    val histories: MutableMap<String, List<MpChatTurn>> = mutableMapOf()

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(CAST)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        calls += characterId
        histories[characterId] = history
        onCall(characterId, history)
        gate?.await()
        val answer = answerOf(characterId)
        emit(if (answer == null) MpReplyEvent.Failed else MpReplyEvent.Text(answer))
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false

    private companion object {
        val CAST: List<MpCharacter> = listOf(
            MpCharacter(id = "c1", name = "Ayuan"),
            MpCharacter(id = "c2", name = "Xiaoman"),
            MpCharacter(id = "c3", name = "Awu"),
            MpCharacter(id = "c4", name = "azen"),
            MpCharacter(id = "c5", name = "Ah Chi"),
        )
    }
}
