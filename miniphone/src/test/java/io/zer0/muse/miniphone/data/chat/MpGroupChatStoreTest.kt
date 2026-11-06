package io.zer0.muse.miniphone.data.chat

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reglas para la creación de grupos y nombres de grupos.
 *
 * el curry es realmente bueno Room(banco de memoria):¿Se escriben juntos "una conversación grupal + varias filas de miembros"?
 * ¿Es confiable la secuencia?,Sólo puedes verlo si realmente ejecutas SQL. El nombre del grupo y "¿Puedes presionar Listo?" son puras reglas,Afirmar solo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpGroupChatStoreTest {

    private lateinit var db: MiniPhoneDb
    private lateinit var store: MpGroupChatStore

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        store = MpGroupChatStore(
            db = db,
            threadDao = db.threadDao(),
            memberDao = db.threadMemberDao(),
            messageDao = db.messageDao(),
            now = { JOINED_AT },
            newId = { GROUP_THREAD_ID },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun selectingThreeCharactersWritesOneGroupThreadAndThreeMembers() = runBlocking {
        val threadId = store.createGroup(listOf("c1", "c2", "c3"), groupTitleOf(listOf("Ayuan", "Xiaoman", "Awu")))

        assertEquals(GROUP_THREAD_ID, threadId)
        val thread = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
        assertTrue("La conversación es un chat grupal.", thread.isGroup)
        assertEquals("El nombre del grupo es el resultado del empalme.", "Ayuan, Xiaoman, Awu", thread.title)
        assertEquals("Cuando busques a alguien con quien hablar, utiliza el primer miembro.", "c1", thread.characterId)

        val members = db.threadMemberDao().observeMembers(GROUP_THREAD_ID).first()
        assertEquals("Tabla de miembros tres filas", 3, members.size)
        assertEquals("El orden es el orden de selección.", listOf("c1", "c2", "c3"), members.map { it.characterId })
        assertEquals("El tiempo de entrada al grupo aumenta fila por fila", listOf(JOINED_AT, JOINED_AT + 1, JOINED_AT + 2), members.map { it.joinedAt })
        assertEquals("Un grupo solo ocupa una sesión", 1, db.threadDao().observeThreads().first().size)
    }

    @Test
    fun fewerThanTwoMembersCreatesNothing() = runBlocking {
        assertNull("Una persona no forma un grupo.", store.createGroup(listOf("c1"), "Ayuan, yo"))
        assertNull("No basta con que una persona lo repita dos veces.", store.createGroup(listOf("c1", "c1"), "Ayuan, yo"))
        assertNull("La lista vacía no es un grupo", store.createGroup(emptyList(), "yo"))

        assertTrue("La mesa de sesiones no ha sido tocada.", db.threadDao().observeThreads().first().isEmpty())
        assertEquals("La lista de miembros tampoco ha sido tocada.", 0, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
    }

    @Test
    fun groupTitleJoinsSelectedNamesAndSkipsBlanks() {
        assertEquals("Ayuan, Xiaoman", groupTitleOf(listOf("Ayuan", "Xiaoman")))
        assertEquals("Solo hay uno y es verdad.", "Ayuan", groupTitleOf(listOf(" Ayuan ")))
        assertEquals("¿Tres o uno por uno?", "Ayuan, Xiaoman, Awu", groupTitleOf(listOf("Ayuan", "Xiaoman", "Awu")))
        assertEquals("Una persona sin nombre no ocupa un puesto", "Ayuan, Xiaoman, Awu", groupTitleOf(listOf("Ayuan", "", "Xiaoman", "  ", "Awu")))
        assertEquals("Si no tienes un nombre, deja que la interfaz se encargue de ello.", "", groupTitleOf(listOf("", "  ")))
    }

    @Test
    fun groupTitleFoldsLongMemberListsIntoACount() {
        val names = listOf("Ayuan", "Xiaoman", "Awu", "azen", "Ah Chi")

        assertEquals("Si son más de tres personas, sólo se enumerarán las dos primeras.,El número es el número total de caracteres.", "Ayuan, Xiaoman y 5 personas más", groupTitleOf(names))
        assertEquals("Sólo cuatro de ellos comenzaron a recolectar", "Ayuan, Xiaoman y otras 4 personas.", groupTitleOf(names.take(4)))
        assertEquals("La N en la notación final no incluye a los usuarios locales.", "Esperando a 5 personas", groupTitleTailOf(5))
    }

    @Test
    fun finishIsOnlyAllowedFromTwoSelectedCharactersOn() {
        assertEquals(false, canCreateGroup(0))
        assertEquals(false, canCreateGroup(1))
        assertEquals(true, canCreateGroup(2))
        assertEquals(true, canCreateGroup(3))
    }

    @Test
    fun aGroupDoesNotStealTheDirectMessageOfItsFirstMember() = runBlocking {
        db.threadDao().upsert(MpThreadEntity(id = "t-1v1", characterId = "c1", lastMessageAt = 1_000L))
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")

        assertEquals("Encuentra conversaciones por rol y reconoce solo mensajes privados", "t-1v1", db.threadDao().findThreadByCharacter("c1")?.id)
        assertNull("La segunda persona del grupo no tiene mensaje privado para empezar.", db.threadDao().findThreadByCharacter("c2"))
    }

    @Test
    fun renamingWritesTheTitleAndMarksItCustomized() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")

        assertTrue(store.renameGroup(GROUP_THREAD_ID, "  equipo de fin de semana  "))

        val thread = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
        assertEquals("Elimine los espacios en blanco iniciales y finales y escriba", "equipo de fin de semana", thread.title)
        assertTrue("Nombre cambiado de usuario", thread.titleCustomized)
    }

    @Test
    fun blankTitleIsRejectedAndTooLongIsCut() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")

        assertFalse("No se permite guardar nombres vacíos.", store.renameGroup(GROUP_THREAD_ID, "   "))
        val untouched = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
        assertEquals("Un nombre vacío no sobrescribirá el nombre del grupo original.", "Ayuan, Xiaoman", untouched.title)
        assertFalse("Si el nombre está vacío, el grupo no debe marcarse como si se le hubiera cambiado el nombre.", untouched.titleCustomized)

        val tooLong = "Uno, dos, tres, cuatro, cinco, seis, siete, ocho, noventa".repeat(3)
        assertTrue(store.renameGroup(GROUP_THREAD_ID, tooLong))
        val cut = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID)).title
        assertEquals("Límite a 20 palabras", GROUP_TITLE_MAX_LENGTH, cut.length)
        assertEquals(tooLong.take(GROUP_TITLE_MAX_LENGTH), cut)
    }

    @Test
    fun addingMembersSkipsThoseAlreadyInAndRecomputesTheDefaultTitle() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")

        assertTrue(store.addMembers(GROUP_THREAD_ID, listOf("c2", "c3", "c3"), autoTitle = "Ayuan, Xiaoman, Awu"))

        assertEquals("Se omitirán los duplicados y los que ya estén en el grupo.", listOf("c1", "c2", "c3"), memberIds())
        assertEquals("Los grupos cuyos nombres no hayan sido cambiados se volverán a calcular en consecuencia.", "Ayuan, Xiaoman, Awu", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
        assertFalse("El recálculo no cuenta los cambios de nombre de usuario", db.threadDao().getThread(GROUP_THREAD_ID)?.titleCustomized ?: true)
    }

    @Test
    fun addingMembersLeavesACustomizedTitleAlone() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")
        store.renameGroup(GROUP_THREAD_ID, "equipo de fin de semana")

        assertTrue(store.addMembers(GROUP_THREAD_ID, listOf("c3"), autoTitle = null))

        assertEquals("El nombre cambiado no es pasivo.", "equipo de fin de semana", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
        assertEquals(3, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
    }

    @Test
    fun removingAMemberDropsTheRowButKeepsHisWords() = runBlocking {
        store.createGroup(listOf("c1", "c2", "c3"), "Ayuan, Xiaoman, Awu")
        db.messageDao().insert(
            MpMessageEntity(
                id = "said-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c2",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "Yo dije",
                createdAt = 1L,
            ),
        )

        assertTrue(store.removeMember(GROUP_THREAD_ID, "c2", autoTitle = "Ayuan, Awu"))

        assertEquals(listOf("c1", "c3"), memberIds())
        assertEquals("Lo que dijo queda en el grupo.", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertEquals("El nombre predeterminado se recalcula según el número de personas.", "Ayuan, Awu", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
    }

    @Test
    fun theLastMemberCannotBeRemoved() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")
        assertTrue(store.removeMember(GROUP_THREAD_ID, "c2", autoTitle = "Ayuan"))

        assertFalse("Cuando solo quede una persona, no se permitirá ningún movimiento adicional.", store.removeMember(GROUP_THREAD_ID, "c1", autoTitle = ""))

        assertEquals(1, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
        assertEquals("El grupo sigue ahí", GROUP_THREAD_ID, db.threadDao().getThread(GROUP_THREAD_ID)?.id)
    }

    @Test
    fun removingTheOpeningCharacterHandsTheRoleToTheNextMember() = runBlocking {
        store.createGroup(listOf("c1", "c2", "c3"), "Ayuan, Xiaoman, Awu")

        assertTrue(store.removeMember(GROUP_THREAD_ID, "c1", autoTitle = null))

        assertEquals("Es necesario que haya alguien en el grupo que pueda hablar.", "c2", db.threadDao().getThread(GROUP_THREAD_ID)?.characterId)
    }

    @Test
    fun disbandingClearsThreadMembersAndMessages() = runBlocking {
        store.createGroup(listOf("c1", "c2"), "Ayuan, Xiaoman")
        db.messageDao().insert(
            MpMessageEntity(
                id = "msg-1",
                threadId = GROUP_THREAD_ID,
                characterId = "c1",
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = "¿Está ahí?",
                createdAt = 1L,
            ),
        )

        assertTrue(store.disbandGroup(GROUP_THREAD_ID))

        assertNull("la sesion se fue", db.threadDao().getThread(GROUP_THREAD_ID))
        assertEquals("los miembros se han ido", 0, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
        assertEquals("la noticia se fue", 0, db.messageDao().countInThread(GROUP_THREAD_ID))
        assertTrue("No queda en la lista", db.threadDao().observeThreads().first().isEmpty())
    }

    @Test
    fun privateThreadsCannotBeManaged() = runBlocking {
        db.threadDao().upsert(MpThreadEntity(id = "t-dm", characterId = "c1", lastMessageAt = 1L))

        assertFalse(store.renameGroup("t-dm", "nombre"))
        assertFalse(store.addMembers("t-dm", listOf("c2"), autoTitle = "x"))
        assertFalse(store.removeMember("t-dm", "c1", autoTitle = null))
        assertFalse(store.disbandGroup("t-dm"))

        assertEquals("La línea de mensajes privados no se ha movido en absoluto.", "", db.threadDao().getThread("t-dm")?.title)
        assertTrue(db.threadMemberDao().membersOf("t-dm").isEmpty())
    }

    @Test
    fun normalizeCutsBlanksAndOverlongNames() {
        assertEquals("equipo de fin de semana", normalizeGroupTitle("  equipo de fin de semana  "))
        assertEquals("", normalizeGroupTitle("   "))
        assertEquals(20, normalizeGroupTitle("uno".repeat(30)).length)
    }

    private suspend fun memberIds(): List<String> = db.threadMemberDao().membersOf(GROUP_THREAD_ID).map { member -> member.characterId }

    private companion object {
        const val GROUP_THREAD_ID: String = "t-group"
        const val JOINED_AT: Long = 5_000L
    }
}
