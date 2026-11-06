package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Escribir operaciones en la lista de sesiones:Fijar arriba, marcar como leído, eliminar conversación, borrar.
 *
 * el curry es realmente bueno Room(banco de memoria):Ninguna de estas cosas son funciones puras.,Si la escritura es correcta o no, solo se puede ver ejecutando SQL. ——
 * Ordenar por ORDER BY, eliminación por DELETE y transacciones,La base de datos suplente no demuestra nada de eso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpConversationStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpConversationStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpConversationStore(db = db, threadDao = db.threadDao(), messageDao = db.messageDao(), memberDao = db.threadMemberDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pinnedThreadSortsAboveTheNewerOne() = runBlocking {
        // El "viejo" es anterior.,Pero después de ser fijado en la cima, abrumará a los "nuevos" más recientes.
        db.threadDao().upsert(thread(id = "old", characterId = "c1", lastMessageAt = 1_000L))
        db.threadDao().upsert(thread(id = "new", characterId = "c2", lastMessageAt = 5_000L))
        assertEquals(listOf("new", "old"), db.threadDao().observeThreads().first().map { it.id })

        store.setPinned("old", true)

        val ordered = db.threadDao().observeThreads().first()
        assertEquals("Primera fila superior,En el área fijada, presione el último mensaje en orden inverso", listOf("old", "new"), ordered.map { it.id })
        assertEquals(true, ordered.first().pinned)
        assertEquals(false, ordered.last().pinned)

        store.setPinned("old", false)

        assertEquals("Volver al orden cronológico después de desanclar", listOf("new", "old"), db.threadDao().observeThreads().first().map { it.id })
    }

    @Test
    fun deletingAThreadTakesItsMessagesAndLeavesTheOthers() = runBlocking {
        db.threadDao().upsert(thread(id = "t1", characterId = "c1", lastMessageAt = 2_000L))
        db.threadDao().upsert(thread(id = "t2", characterId = "c2", lastMessageAt = 1_000L))
        db.messageDao().insert(message(id = "m1", threadId = "t1", createdAt = 1_000L))
        db.messageDao().insert(message(id = "m2", threadId = "t1", createdAt = 2_000L))
        db.messageDao().insert(message(id = "m3", threadId = "t2", createdAt = 1_000L))
        db.threadMemberDao().upsertAll(
            listOf(
                MpThreadMemberEntity(threadId = "t1", characterId = "c1", joinedAt = 1L),
                MpThreadMemberEntity(threadId = "t1", characterId = "c2", joinedAt = 2L),
            ),
        )

        store.deleteThread("t1")

        assertNull("La sesión en sí fue eliminada.", db.threadDao().getThread("t1"))
        assertEquals("No queda ni un solo mensaje a su nombre.", 0, db.messageDao().countInThread("t1"))
        assertEquals("Los miembros bajo su nombre tampoco se conservan.", 0, db.threadMemberDao().countInThread("t1"))
        assertEquals("Otras conversaciones siguen ahí", "t2", db.threadDao().observeThreads().first().single().id)
        assertEquals("Los mensajes de otras conversaciones siguen ahí.", 1, db.messageDao().countInThread("t2"))
    }

    @Test
    fun markingThreadReadZeroesItsUnreadCount() = runBlocking {
        db.threadDao().upsert(thread(id = "t1", characterId = "c1", lastMessageAt = 3_000L, unread = 3))
        assertEquals(3, db.threadDao().unreadCount("t1"))

        store.markRead("t1")

        assertEquals(0, db.threadDao().unreadCount("t1"))
    }

    @Test
    fun clearingChatsEmptiesBothTablesButKeepsMoments() = runBlocking {
        db.threadDao().upsert(thread(id = "t1", characterId = "c1", lastMessageAt = 2_000L))
        db.threadDao().upsert(thread(id = "t2", characterId = "c2", lastMessageAt = 1_000L))
        db.messageDao().insert(message(id = "m1", threadId = "t1", createdAt = 1_000L))
        db.messageDao().insert(message(id = "m2", threadId = "t2", createdAt = 2_000L))
        db.threadMemberDao().upsertAll(
            listOf(MpThreadMemberEntity(threadId = "t1", characterId = "c1", joinedAt = 1L)),
        )
        db.momentDao().insert(
            MpMomentEntity(
                id = "moment-1",
                authorId = "c1",
                authorName = "ali",
                content = "mantener las noticias",
                createdAt = 500L,
                source = MpMomentSource.AI,
            ),
        )

        store.clearAll()

        assertEquals("Borrar tabla de sesiones", emptyList<MpThreadEntity>(), db.threadDao().observeThreads().first())
        assertEquals(0, db.messageDao().countInThread("t1"))
        assertEquals(0, db.messageDao().countInThread("t2"))
        assertEquals("La lista de miembros también se borra.", 0, db.threadMemberDao().countInThread("t1"))
        assertEquals("El círculo de amigos no se mueve.", 1, db.momentDao().count())
        assertEquals("El contenido dinámico todavía está ahí", "mantener las noticias", db.momentDao().getMoment("moment-1")?.content)
    }

    private fun thread(id: String, characterId: String, lastMessageAt: Long, unread: Int = 0): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        unreadCount = unread,
        lastMessageAt = lastMessageAt,
    )

    private fun message(id: String, threadId: String, createdAt: Long): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = threadId,
        characterId = "c1",
        sender = MpActorType.CHARACTER,
        type = MpMessageType.TEXT,
        content = id,
        createdAt = createdAt,
    )
}
