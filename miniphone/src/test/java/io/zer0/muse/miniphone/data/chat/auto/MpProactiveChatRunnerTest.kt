package io.zer0.muse.miniphone.data.chat.auto

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Un proceso completo de mensajería privada proactiva:juicio → encontrar conversación → generación de host → Cayó en la biblioteca → no leído +1 → Lleve un registro del tiempo.
 *
 * el curry es realmente bueno Room(banco de memoria),Alias de host y configuración:De esta manera, "realmente lo escribí" y "no escribí nada cuando fallé".
 * Es una afirmación más que una evidencia. La sentencia misma es [MpProactiveChatPlanTest] lo clavó uno por uno,
 * La preocupación aquí es lo que se hace después de la sentencia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpProactiveChatRunnerTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sendsOneCharacterMessageAndBumpsUnread() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)
        val store = FakeChatStore(enabled = true)

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpProactiveChatOutcome.Sent(threadId = THREAD_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(1, host.composeCalls)
        val messages = db.messageDao().pageMessages(THREAD_ID, limit = 10, offset = 0)
        assertEquals(1, messages.size)
        assertEquals(MpActorType.CHARACTER, messages[0].sender)
        assertEquals(MpMessageType.TEXT, messages[0].type)
        assertEquals(COMPOSED_TEXT, messages[0].content)
        assertEquals(NOW, messages[0].createdAt)
        // Tanto las actualizaciones de +1 no leídas como las de vista previa se encuentran en la línea de conversación.:Esa fila de la lista parece "acaba de llegar un mensaje".
        assertEquals(1, db.threadDao().unreadCount(THREAD_ID))
        assertEquals(COMPOSED_TEXT, db.threadDao().getThread(THREAD_ID)?.lastMessagePreview)
        assertEquals(NOW, store.markedAt)
    }

    @Test
    fun blankContentIsNotStoredAndDoesNotMarkTheTime() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val store = FakeChatStore(enabled = true)

        val outcome = runner(host = FakeChatHost(content = "   "), store = store).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_CONTENT), outcome)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
        assertEquals(0, db.threadDao().unreadCount(THREAD_ID))
        // La marca de tiempo no se registrará si la base de datos no está en uso.,De lo contrario, la siguiente ronda quedará bloqueada por su propio intervalo de 4 horas.
        assertNull(store.markedAt)
    }

    @Test
    fun noHistorySkipsWithoutCallingHost() = runBlocking {
        // Una conversación sin un solo mensaje no se considera "conversable":Si un personaje habla de la nada, sonará como acoso.
        db.threadDao().upsert(thread(lastMessageAt = 0L))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_HISTORY), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun disabledByDefaultSkipsEverything() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = false)).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.DISABLED), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun userJustTalkedSkipsWithoutCallingHost() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        // El usuario acaba de hablar hace 3 minutos.:Interrumpir en este momento sería interrumpir.
        db.messageDao().insert(
            MpMessageEntity(
                id = "recent-user",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = MpActorType.USER,
                type = MpMessageType.TEXT,
                content = "acabo de regresar",
                createdAt = NOW - THREE_MINUTES_MS,
            ),
        )
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.USER_JUST_ACTIVE), outcome)
        assertEquals(0, host.composeCalls)
        // Solo ese mensaje de usuario,Nada nuevo escrito
        assertEquals(1, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun threeHoursAfterLastProactiveChatSkipsAsTooSoon() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)
        val store = FakeChatStore(enabled = true, lastProactiveAt = NOW - THREE_HOURS_MS)

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.TOO_SOON), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun picksTheMostRecentlyActiveThread() = runBlocking {
        db.threadDao().upsert(thread(id = "older", characterId = "c2", lastMessageAt = LAST_USER_AT))
        db.threadDao().upsert(thread(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = LAST_USER_AT + 5_000L))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals(MpProactiveChatOutcome.Sent(threadId = THREAD_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(CHARACTER_ID, host.lastCharacterId)
        assertEquals(0, db.messageDao().countInThread("older"))
    }

    @Test
    fun groupThreadsAreNeverPickedForAProactiveMessage() = runBlocking {
        // Actualización de la sesión grupal:Tampoco puede utilizarse como un lugar para "encontrar a alguien con quien hablar".
        db.threadDao().upsert(
            thread(id = GROUP_THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = LAST_USER_AT + 5_000L)
                .copy(isGroup = true, title = "Ayuan, Xiaoman"),
        )
        db.threadDao().upsert(thread(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = LAST_USER_AT))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals("Cayó en el mensaje privado", MpProactiveChatOutcome.Sent(threadId = THREAD_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(0, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertEquals(1, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun aGroupWithoutADirectMessageSkipsInsteadOfTalkingInTheGroup() = runBlocking {
        db.threadDao().upsert(
            thread(lastMessageAt = LAST_USER_AT).copy(isGroup = true, title = "Ayuan, Xiaoman"),
        )
        val host = FakeChatHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeChatStore(enabled = true)).run()

        assertEquals("Cuando sólo hay una conversación grupal, no hay nadie con quien hablar a solas.", MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_HISTORY), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.messageDao().countInThread(THREAD_ID))
    }

    @Test
    fun sendsWithRecentChatContextAndTimeThenTheFocus() = runBlocking {
        db.threadDao().upsert(thread(lastMessageAt = LAST_USER_AT))
        db.messageDao().insert(message(id = "m1", sender = MpActorType.USER, content = "Trabajé hasta tarde hoy", at = LAST_USER_AT))
        db.messageDao().insert(message(id = "m2", sender = MpActorType.CHARACTER, content = "¿Ya estás en casa?", at = LAST_USER_AT + 1L))
        val host = FakeChatHost(content = COMPOSED_TEXT)

        runner(host = host, store = FakeChatStore(enabled = true)).run()

        val hint = host.lastHint.orEmpty()
        assertTrue("Los materiales deben incluir conversaciones recientes.: $hint", hint.contains("Usuario: hoy trabajé horas extras muy tarde"))
        assertTrue("El material debe contener palabras dichas por el propio personaje.: $hint", hint.contains("Yo: ¿Estás en casa?"))
        assertTrue("El material debe tener la hora actual.: $hint", hint.contains("Son las dos de la tarde del martes."))
        assertTrue("Ponlo desde el principio hasta la última frase.: $hint", hint.lines().last().startsWith("El motivo de este momento:"))
    }

    private fun runner(host: MiniPhoneHost, store: MpMomentAutoStore): MpProactiveChatRunner = MpProactiveChatRunner(
        host = host,
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        store = store,
        material = MpCharacterMaterial(
            threadDao = db.threadDao(),
            messageDao = db.messageDao(),
            momentDao = db.momentDao(),
            now = { NOW },
            zone = ZONE,
        ),
        now = { NOW },
        random = { 0.1f },
        zone = ZONE,
        newId = { MESSAGE_ID },
    )

    private fun message(id: String, sender: MpActorType, content: String, at: Long): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = THREAD_ID,
        characterId = CHARACTER_ID,
        sender = sender,
        type = MpMessageType.TEXT,
        content = content,
        createdAt = at,
    )

    private fun thread(id: String = THREAD_ID, characterId: String = CHARACTER_ID, lastMessageAt: Long): MpThreadEntity {
        return MpThreadEntity(
            id = id,
            characterId = characterId,
            lastMessagePreview = "¿Está ahí?",
            lastMessageAt = lastMessageAt,
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** Durante el día 14:00: no durante las horas tranquilas. */
        val NOW: Long = LocalDateTime.of(2026, 5, 12, 14, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val THREE_HOURS_MS: Long = 3L * 60L * 60L * 1000L

        const val THREE_MINUTES_MS: Long = 3L * 60L * 1000L

        const val LAST_USER_AT: Long = 1_000L

        const val THREAD_ID: String = "t1"

        const val GROUP_THREAD_ID: String = "g1"

        const val CHARACTER_ID: String = "c1"

        const val MESSAGE_ID: String = "proactive-1"

        const val COMPOSED_TEXT: String = "Acabo de pasar por la tienda que mencionaste y había bastante gente."
    }
}

/** anfitrión falso:El resultado generado es fijo.,Por cierto, toma nota de cuántas veces fuiste transferido y por quién. */
private class FakeChatHost(
    private val content: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "ali")),
) : MiniPhoneHost {

    var composeCalls: Int = 0
        private set

    var lastCharacterId: String? = null
        private set

    var lastHint: String? = null
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? {
        composeCalls += 1
        lastCharacterId = characterId
        lastHint = hint
        return content
    }

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** Asumir la configuración:Cambio de mensaje privado activo y sustituto variable para la última vez,El valor escrito se deja en el campo para la afirmación. */
private class FakeChatStore(
    enabled: Boolean = false,
    lastProactiveAt: Long? = null,
) : MpMomentAutoStore {

    private val chatProactiveState = MutableStateFlow(enabled)

    private val momentState = MutableStateFlow(true)

    var markedAt: Long? = lastProactiveAt
        private set

    override val enabledFlow: Flow<Boolean> = momentState

    override suspend fun isEnabled(): Boolean = momentState.value

    override suspend fun setEnabled(enabled: Boolean) {
        momentState.value = enabled
    }

    override suspend fun lastGeneratedAt(): Long? = null

    override suspend fun markGenerated(atMillis: Long) = Unit

    override val chatProactiveEnabledFlow: Flow<Boolean> = chatProactiveState

    override suspend fun isChatProactiveEnabled(): Boolean = chatProactiveState.value

    override suspend fun setChatProactiveEnabled(enabled: Boolean) {
        chatProactiveState.value = enabled
    }

    override suspend fun lastProactiveChatAt(): Long? = markedAt

    override suspend fun markProactiveChat(atMillis: Long) {
        markedAt = atMillis
    }
}
