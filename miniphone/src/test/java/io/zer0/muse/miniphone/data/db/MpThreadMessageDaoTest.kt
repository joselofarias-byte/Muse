package io.zer0.muse.miniphone.data.db

import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lectura y escritura básica de sesión y mensaje DAO.:Crear hilo → observado → agregar mensaje → Cambios no leídos → Claro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpThreadMessageDaoTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var threads: MpThreadDao
    private lateinit var messages: MpMessageDao

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        threads = db.threadDao()
        messages = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertedThreadShowsUpInObservation() = runBlocking {
        threads.upsert(thread("t1", "c1", lastMessageAt = 1_000L))

        val observed = withTimeout(TIMEOUT_MS) { threads.observeThreads().first() }

        assertEquals(listOf("t1"), observed.map { it.id })
        assertEquals(0, observed.single().unreadCount)
    }

    @Test
    fun threadsAreOrderedByPinnedThenLastMessageAt() = runBlocking {
        threads.upsert(thread("old", "c1", lastMessageAt = 1_000L))
        threads.upsert(thread("new", "c2", lastMessageAt = 3_000L))
        threads.upsert(thread("pinned", "c3", lastMessageAt = 2_000L, pinned = true))

        val observed = withTimeout(TIMEOUT_MS) { threads.observeThreads().first() }

        assertEquals(listOf("pinned", "new", "old"), observed.map { it.id })
    }

    @Test
    fun appendedMessagesMoveUnreadCounterAndClearResetsIt() = runBlocking {
        threads.upsert(thread("t1", "c1"))

        messages.insert(message("m1", "t1", createdAt = 1_000L, content = "¿Está ahí?"))
        threads.updateLastMessage("t1", "¿Está ahí?", 1_000L)
        threads.increaseUnread("t1")
        messages.insert(message("m2", "t1", createdAt = 2_000L, content = "en"))
        threads.updateLastMessage("t1", "en", 2_000L)
        threads.increaseUnread("t1")

        assertEquals(2, threads.unreadCount("t1"))
        assertEquals(2, messages.countInThread("t1"))
        assertEquals("en", threads.getThread("t1")?.lastMessagePreview)

        threads.clearUnread("t1")

        assertEquals(0, threads.unreadCount("t1"))
    }

    @Test
    fun messagesAreObservedInAscendingOrder() = runBlocking {
        threads.upsert(thread("t1", "c1"))
        messages.insert(message("m2", "t1", createdAt = 2_000L, content = "segunda frase"))
        messages.insert(message("m1", "t1", createdAt = 1_000L, content = "primera frase"))

        val observed = withTimeout(TIMEOUT_MS) { messages.observeMessages("t1").first() }

        assertEquals(listOf("m1", "m2"), observed.map { it.id })
    }

    @Test
    fun pageMessagesWalksFromNewestToOldest() = runBlocking {
        threads.upsert(thread("t1", "c1"))
        (1..5).forEach { index ->
            messages.insert(message("m$index", "t1", createdAt = index * 1_000L, content = "No. $index frase"))
        }
        messages.insert(message("other", "t2", createdAt = 9_000L, content = "otras conversaciones"))

        assertEquals(listOf("m5", "m4"), messages.pageMessages("t1", limit = 2, offset = 0).map { it.id })
        assertEquals(listOf("m3", "m2"), messages.pageMessages("t1", limit = 2, offset = 2).map { it.id })
        assertEquals(listOf("m1"), messages.pageMessages("t1", limit = 2, offset = 4).map { it.id })
        // Los mensajes de otras conversaciones no se mezclan
        assertEquals(5, messages.countInThread("t1"))
    }

    @Test
    fun messageEnumColumnsSurviveRoundTrip() = runBlocking {
        threads.upsert(thread("t1", "c1"))
        messages.insert(
            message("v1", "t1", createdAt = 1_000L, content = "Voz", type = MpMessageType.VOICE)
                .copy(sender = MpActorType.CHARACTER, mediaUri = "file:///voice/1.m4a", voiceDurationMs = 2_400L),
        )

        val loaded = requireNotNull(messages.getMessage("v1"))

        assertEquals(MpActorType.CHARACTER, loaded.sender)
        assertEquals(MpMessageType.VOICE, loaded.type)
        assertEquals("file:///voice/1.m4a", loaded.mediaUri)
        assertEquals(2_400L, loaded.voiceDurationMs)
    }

    @Test
    fun findThreadByCharacterReturnsThatCharactersOnlyThread() = runBlocking {
        threads.upsert(thread("t1", "c1"))
        threads.upsert(thread("t2", "c2"))

        // Un mismo personaje solo puede ser uno.,No captará las conversaciones de otras personas entre roles.
        assertEquals("t1", threads.findThreadByCharacter("c1")?.id)
        assertEquals("t2", threads.findThreadByCharacter("c2")?.id)
        assertNull(threads.findThreadByCharacter("c9"))
    }

    @Test
    fun statusUpdatesAndDeletionTouchOnlyTheRowTheyName() = runBlocking {
        threads.upsert(thread("t1", "c1"))
        messages.insert(message("m1", "t1", createdAt = 1_000L, content = "¿Está ahí?"))
        messages.insert(message("m2", "t1", createdAt = 2_000L, content = "en"))
        assertEquals("Las nuevas líneas se emiten de forma predeterminada.", MpMessageStatus.SENT, messages.getMessage("m1")?.status)

        messages.updateStatus("m1", MpMessageStatus.FAILED)
        assertEquals(MpMessageStatus.FAILED, messages.getMessage("m1")?.status)
        assertEquals("El otro no se ve afectado.", MpMessageStatus.SENT, messages.getMessage("m2")?.status)

        messages.deleteMessage("m1")
        assertNull(messages.getMessage("m1"))
        assertEquals(1, messages.countInThread("t1"))
        assertEquals("Eliminar mensajes y dejar conversaciones sin cambios", 1, threads.observeThreads().first().size)
    }

    private fun thread(id: String, characterId: String, lastMessageAt: Long = 0L, pinned: Boolean = false): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        pinned = pinned,
        lastMessageAt = lastMessageAt,
    )

    private fun message(
        id: String,
        threadId: String,
        createdAt: Long,
        content: String,
        type: MpMessageType = MpMessageType.TEXT,
    ): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = threadId,
        characterId = "c1",
        sender = MpActorType.USER,
        type = type,
        content = content,
        createdAt = createdAt,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
    }
}
