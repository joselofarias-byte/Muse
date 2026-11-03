package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoDecision
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoPlan
import io.zer0.muse.miniphone.data.moment.auto.MpMomentSkipReason
import io.zer0.muse.miniphone.data.moment.decodeMomentImages
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Estado de los momentos:La paginación de la línea de tiempo, la publicación, los me gusta, los comentarios y la actualización desplegable activan la generación de caracteres.
 *
 * el curry es realmente bueno Room(banco de memoria),la imagen es real [MpImageStore](directorio temporal):Ninguna de estas dos cosas debería utilizar un sustituto.,
 * De lo contrario, las dos afirmaciones "la dinámica está realmente escrita" y "el archivo de imagen está realmente ahí" son sólo autoverificación.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneMomentsViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: MiniPhoneDb
    private lateinit var imageDir: File
    private lateinit var repository: MpMomentsRepository
    private val imageBytes = mutableMapOf<String, ByteArray>()
    private var clock: Long = NOW_BASE
    private var idSeq: Int = 0
    private val autoStore: FakeAutoStore = FakeAutoStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = newInMemoryMiniPhoneDb()
        imageDir = File(tempFolder.newFolder(), "images")
        repository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = db.momentLikeDao(),
            commentDao = db.momentCommentDao(),
            imageStore = MpImageStore(directory = imageDir, openStream = ::openImage),
            now = { clock++ },
            newId = { "id-${idSeq++}" },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun timelineLoadsTwentyThenTheRestNewestFirst() = runBlocking {
        seedMoments(count = 25)

        val viewModel = createViewModel(FakeMomentsHost())
        val first = awaitState(viewModel) { state -> state.isLoaded }
        assertEquals("Hay 20 elementos por página en la primera pantalla.", 20, first.moments.size)
        assertTrue("Hay mayores que no lo han leído.", first.hasMore)
        assertEquals("orden inverso:Lo más nuevo en la parte superior", "m25", first.moments.first().id)
        assertEquals("m6", first.moments.last().id)

        viewModel.loadMore()
        val all = awaitState(viewModel) { state -> state.moments.size == 25 }
        assertEquals(25, all.moments.size)
        assertFalse("Después de leer la última página, no hay más.", all.hasMore)
        assertEquals("m25", all.moments.first().id)
        assertEquals("m1", all.moments.last().id)
    }

    @Test
    fun publishKeepsThreeImagesOnDiskAndInRow() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "El clima esta agradable hoy", imageUris = listOf(pickImage("a"), pickImage("b"), pickImage("c")))

        val state = awaitState(viewModel) { current -> current.moments.size == 1 }
        val row = state.moments.single()
        assertEquals("El clima esta agradable hoy", row.content)
        assertEquals(3, row.images.size)
        assertTrue("Puedes eliminar las actualizaciones que publicaste.", row.isMine)

        val stored = requireNotNull(db.momentDao().getMoment(row.id))
        assertEquals(MpMomentSource.USER, stored.source)
        val paths = decodeMomentImages(stored.imagesJson)
        assertEquals("imagesJson Caen tres artículos", 3, paths.size)
        paths.forEach { path -> assertTrue("Los archivos de imagen realmente deberían existir:$path", File(path).exists()) }
    }

    @Test
    fun likingTwiceEndsUpUnliked() = runBlocking {
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.toggleLike(MOMENT_ID)
        val liked = awaitState(viewModel) { state -> state.moments.single().likes.isNotEmpty() }
        assertEquals("La persona que le gusta soy yo.", listOf(SELF_NAME), liked.moments.single().likes)
        assertTrue(liked.moments.single().likedByMe)
        assertEquals(1, db.momentLikeDao().likesOf(MOMENT_ID).size)

        viewModel.toggleLike(MOMENT_ID)
        val unliked = awaitState(viewModel) { state -> state.moments.single().likes.isEmpty() }
        assertFalse("El segundo clic es para cancelar.", unliked.moments.single().likedByMe)
        assertEquals("Sólo hay una clave primaria compuesta garantizada", 0, db.momentLikeDao().likesOf(MOMENT_ID).size)
    }

    @Test
    fun commentAndReplyFormTwoLevels() = runBlocking {
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.comment(MOMENT_ID, parentId = null, content = "Artículo 1")
        val withTop = awaitState(viewModel) { state -> state.moments.single().comments.size == 1 }
        val top = withTop.moments.single().comments.single()
        assertEquals("Artículo 1", top.content)
        assertTrue(top.replies.isEmpty())

        viewModel.comment(MOMENT_ID, parentId = top.id, content = "Responder")
        val withReply = awaitState(viewModel) { state -> state.moments.single().comments.single().replies.size == 1 }

        val comment = withReply.moments.single().comments.single()
        assertEquals("Responder bajo el mismo comentario de nivel superior", 1, comment.replies.size)
        assertEquals("Responder", comment.replies.single().content)
        assertEquals(SELF_NAME, comment.replies.single().senderName)

        val stored = db.momentCommentDao().observeComments(MOMENT_ID).first()
        val reply = stored.single { it.senderType == MpActorType.USER && it.content == "Responder" }
        assertEquals("El parentId de la respuesta apunta al comentario principal.", top.id, reply.parentId)
        assertNull("No hay comentarios destacados parent", stored.single { it.content == "Artículo 1" }.parentId)
    }

    @Test
    fun refreshInsertsOneGeneratedMomentFromACharacter() = runBlocking {
        val host = FakeMomentsHost(composed = "Acabo de ver una lluvia")
        val viewModel = createViewModel(host)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.refresh()

        val state = awaitState(viewModel) { current -> !current.isRefreshing && current.moments.isNotEmpty() }
        val row = state.moments.single()
        assertEquals("Acabo de ver una lluvia", row.content)
        assertEquals(CHARACTER_ID, row.authorId)
        assertEquals(CHARACTER_NAME, row.authorName)
        assertEquals(1, host.composeCalls)
        assertEquals(MpMomentSource.AI, requireNotNull(db.momentDao().getMoment(row.id)).source)
    }

    @Test
    fun manualRefreshCountsAsGenerationForBackgroundDebounce() = runBlocking {
        // Empezar durante el día(14:00):Desviarse del juicio más avanzado de "período de tranquilidad",Sólo prueba anti-vibración
        clock = DAYTIME_MS
        val host = FakeMomentsHost(composed = "Este fue dibujado a mano.")
        val viewModel = createViewModel(host)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.refresh()

        val state = awaitState(viewModel) { current -> !current.isRefreshing && current.moments.isNotEmpty() }
        val createdAt = requireNotNull(db.momentDao().getMoment(state.moments.single().id)).createdAt
        assertEquals("El cepillado de manos exitoso también quedará registrado en el libro de contabilidad anti-vibración.", createdAt, autoStore.lastGeneratedAtMillis)

        val decision = MpMomentAutoPlan.decide(
            nowMillis = createdAt + THIRTY_MINUTES_MS,
            lastGeneratedAtMillis = autoStore.lastGeneratedAtMillis,
            roll = 0.1,
            hasCharacter = true,
            zone = ZONE,
        )
        assertEquals("30 minutos después del cepillado de manos,Las tareas en segundo plano deben considerarse demasiado rápidas y omitirse", MpMomentAutoDecision.Skip(MpMomentSkipReason.TOO_SOON), decision)
    }

    @Test
    fun refreshWithoutGeneratedTextLeavesTimelineAlone() = runBlocking {
        seedMoments(count = 1)
        val host = FakeMomentsHost(composed = null)
        val viewModel = createViewModel(host)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.refresh()

        val state = awaitState(viewModel) { current -> !current.isRefreshing }
        assertEquals("La generación falló sin insertar", 1, state.moments.size)
        assertEquals(1, host.composeCalls)
        assertNotNull(db.momentDao().getMoment(MOMENT_ID))
    }

    @Test
    fun ownMomentCanBeDeletedButACharacterMomentCannot() = runBlocking {
        db.momentDao().insert(
            MpMomentEntity(
                id = SELF_MOMENT_ID,
                authorId = MP_LOCAL_ACTOR_ID,
                authorName = SELF_NAME,
                content = "propia dinámica",
                createdAt = 1L,
                source = MpMomentSource.USER,
            ),
        )
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.deleteMoment(MOMENT_ID)
        viewModel.deleteMoment(SELF_MOMENT_ID)

        val afterDelete = awaitState(viewModel) { state -> state.moments.none { it.id == SELF_MOMENT_ID } && state.moments.size == 1 }
        assertEquals("La entrada del personaje no se eliminará.", MOMENT_ID, afterDelete.moments.single().id)
        assertNull("Mi actividad ha sido eliminada", db.momentDao().getMoment(SELF_MOMENT_ID))
        assertNotNull("Las actualizaciones de otras personas permanecen sin cambios.", db.momentDao().getMoment(MOMENT_ID))
    }

    @Test
    fun deletingOwnCommentTakesItsRepliesWithIt() = runBlocking {
        seedMoments(count = 1)
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.comment(MOMENT_ID, parentId = null, content = "mi comentario")
        val top = awaitState(viewModel) { state -> state.moments.single().comments.size == 1 }
            .moments.single().comments.single()
        viewModel.comment(MOMENT_ID, parentId = top.id, content = "mi respuesta")
        awaitState(viewModel) { state -> state.moments.single().comments.single().replies.size == 1 }
        // Otros responden bajo el mismo artículo.:Sigue el piso superior
        db.momentCommentDao().insert(comment(id = "k-other", momentId = MOMENT_ID, parentId = top.id, at = 90L))
        assertEquals(3, db.momentCommentDao().count(MOMENT_ID))

        viewModel.deleteComment(MOMENT_ID, top.id)

        val after = awaitState(viewModel) { state -> state.moments.single().comments.isEmpty() }
        assertTrue(after.moments.single().comments.isEmpty())
        assertEquals("El comentario se eliminará junto con la respuesta debajo.", 0, db.momentCommentDao().count(MOMENT_ID))
    }

    @Test
    fun someoneElsesCommentCannotBeDeleted() = runBlocking {
        seedMomentsWithSocial()
        val viewModel = createViewModel(FakeMomentsHost())
        val loaded = awaitState(viewModel) { state -> state.isLoaded && state.moments.isNotEmpty() }
        val top = loaded.moments.first { it.id == "m1" }.comments.single()
        assertTrue("Los comentarios escritos por A Yuan no le pertenecen.", !top.isMine)

        viewModel.deleteComment("m1", top.id)

        val after = awaitState(viewModel) { state -> state.moments.isNotEmpty() }
        assertEquals("El comentario de Ayuan y la respuesta a continuación todavía están ahí.", 2, db.momentCommentDao().count("m1"))
        assertEquals("Todavía está ahí en la interfaz.", 1, after.moments.first { it.id == "m1" }.comments.size)
    }

    @Test
    fun publishDefaultsToEveryoneVisible() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "Visible por defecto", imageUris = emptyList())

        val row = awaitState(viewModel) { state -> state.moments.size == 1 }.moments.single()
        val stored = requireNotNull(db.momentDao().getMoment(row.id))
        assertEquals("Cadena vacía predeterminada = Visible para todos", "", stored.visibleToJson)
    }

    @Test
    fun publishWithOnlySelfWritesOwnId() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost())
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "solo para ti", imageUris = emptyList(), visibleToSelf = true)

        val row = awaitState(viewModel) { state -> state.moments.size == 1 }.moments.single()
        val stored = requireNotNull(db.momentDao().getMoment(row.id))
        assertEquals("Después de comprobarlo, escribe el tuyo. id", MP_LOCAL_ACTOR_ID, stored.visibleToJson)
        assertEquals("La interfaz también obtuvo esta marca.", MP_LOCAL_ACTOR_ID, row.visibleToJson)
    }

    @Test
    fun onlyMineTimelineShowsOnlyMineNewestFirst() = runBlocking {
        db.momentDao().insert(ownMoment(id = "mine-early", createdAt = 10L, content = "Mi propio temprano"))
        seedMoments(count = 3)
        db.momentDao().insert(ownMoment(id = "mine-late", createdAt = 50L, content = "El mio llega tarde"))

        val host = FakeMomentsHost(composed = "El personaje acaba de ser lanzado.")
        val viewModel = createViewModel(host, onlyMine = true)

        val state = awaitState(viewModel) { current -> current.isLoaded }

        assertEquals("No aparece ninguna de las actualizaciones de otras personas.,propio en orden cronológico inverso", listOf("mine-late", "mine-early"), state.moments.map { it.id })
        assertTrue("Cada línea es suya", state.moments.all { moment -> moment.isMine })

        viewModel.refresh()

        assertEquals("Mi círculo de amigos no se llama generación de personajes.", 0, host.composeCalls)
        assertEquals("No hay muchas noticias sobre Curry.", 5, db.momentDao().count())
    }

    @Test
    fun onlyMineTimelineStillTakesMyNewMoment() = runBlocking {
        val viewModel = createViewModel(FakeMomentsHost(), onlyMine = true)
        awaitState(viewModel) { state -> state.isLoaded }

        viewModel.publish(content = "Sólo una publicación en esta página.", imageUris = emptyList())

        val row = awaitState(viewModel) { state -> state.moments.size == 1 }.moments.single()
        assertEquals("Sólo una publicación en esta página.", row.content)
        assertTrue(row.isMine)
        assertEquals(MpMomentSource.USER, requireNotNull(db.momentDao().getMoment(row.id)).source)
    }

    @Test
    fun aPageLoadsLikesAndCommentsInTwoBatchQueries() = runBlocking {
        seedMomentsWithSocial()
        val countingLikes = CountingLikeDao(db.momentLikeDao())
        val countingComments = CountingCommentDao(db.momentCommentDao())
        val batchRepository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = countingLikes,
            commentDao = countingComments,
            imageStore = MpImageStore(directory = imageDir, openStream = ::openImage),
            now = { clock++ },
            newId = { "batch-${idSeq++}" },
        )

        val viewModel = MiniPhoneMomentsViewModel(
            repository = batchRepository,
            host = FakeMomentsHost(),
            settings = autoStore,
            material = material(),
            random = Random(0),
        )
        val state = awaitState(viewModel) { current -> current.isLoaded }

        assertEquals(3, state.moments.size)
        val byId = state.moments.associateBy { moment -> moment.id }
        assertEquals("m1 un me gusta", listOf("Ayuan"), byId.getValue("m1").likes)
        assertEquals("m1 un comentario superior", 1, byId.getValue("m1").comments.size)
        assertEquals("m1 Hay una respuesta debajo del comentario de nivel superior.", 1, byId.getValue("m1").comments.single().replies.size)
        assertEquals("m2 Dos me gusta en orden cronológico", listOf("Ayuan", "Xiaoman"), byId.getValue("m2").likes)
        assertEquals(1, byId.getValue("m2").comments.size)
        assertEquals(1, byId.getValue("m3").likes.size)
        assertEquals(1, byId.getValue("m3").comments.size)

        assertEquals("Me gusta una página y consulta en lotes a la vez", 1, countingLikes.batchCalls)
        assertEquals("Consulta por lotes para una página de comentarios", 1, countingComments.batchCalls)
        assertEquals("No revises los Me gusta uno por uno.", 0, countingLikes.singleCalls)
        assertEquals("No revises los comentarios uno por uno", 0, countingComments.singleCalls)
    }

    /** hacer [count] Noticias:createdAt incremento,id De m1 a mN. */
    private suspend fun seedMoments(count: Int) {
        (1..count).forEach { index ->
            db.momentDao().insert(
                MpMomentEntity(
                    id = "m$index",
                    authorId = CHARACTER_ID,
                    authorName = CHARACTER_NAME,
                    content = "Dinámico $index",
                    createdAt = index.toLong(),
                    source = MpMomentSource.AI,
                ),
            )
        }
    }

    /** Cada una de las tres actualizaciones tiene me gusta y comentarios.:m2 dos me gusta,m1 Con una respuesta. */
    private suspend fun seedMomentsWithSocial() {
        seedMoments(count = 3)
        db.momentLikeDao().insert(like(momentId = "m1", likerId = "c2", likerName = "Ayuan", at = 10L))
        db.momentLikeDao().insert(like(momentId = "m2", likerId = "c2", likerName = "Ayuan", at = 11L))
        db.momentLikeDao().insert(like(momentId = "m2", likerId = "c3", likerName = "Xiaoman", at = 12L))
        db.momentLikeDao().insert(like(momentId = "m3", likerId = "c2", likerName = "Ayuan", at = 13L))
        db.momentCommentDao().insert(comment(id = "k1", momentId = "m1", parentId = null, at = 20L))
        db.momentCommentDao().insert(comment(id = "k2", momentId = "m1", parentId = "k1", at = 21L))
        db.momentCommentDao().insert(comment(id = "k3", momentId = "m2", parentId = null, at = 22L))
        db.momentCommentDao().insert(comment(id = "k4", momentId = "m3", parentId = null, at = 23L))
    }

    private fun ownMoment(id: String, createdAt: Long, content: String): MpMomentEntity = MpMomentEntity(
        id = id,
        authorId = MP_LOCAL_ACTOR_ID,
        authorName = SELF_NAME,
        content = content,
        createdAt = createdAt,
        source = MpMomentSource.USER,
    )

    private fun like(momentId: String, likerId: String, likerName: String, at: Long): MpMomentLikeEntity = MpMomentLikeEntity(
        momentId = momentId,
        likerType = MpActorType.CHARACTER,
        likerId = likerId,
        likerName = likerName,
        createdAt = at,
    )

    private fun comment(id: String, momentId: String, parentId: String?, at: Long): MpMomentCommentEntity = MpMomentCommentEntity(
        id = id,
        momentId = momentId,
        parentId = parentId,
        senderType = MpActorType.CHARACTER,
        senderId = "c2",
        senderName = "Ayuan",
        content = "Comentario $id",
        createdAt = at,
    )

    private fun createViewModel(host: MiniPhoneHost, onlyMine: Boolean = false): MiniPhoneMomentsViewModel = MiniPhoneMomentsViewModel(
        repository = repository,
        host = host,
        settings = autoStore,
        material = material(),
        onlyMine = onlyMine,
        random = Random(0),
    )

    /** Montaje de materiales reales:Tanto las sesiones como las dinámicas se leen desde la biblioteca de memoria.,Hora usando el reloj de prueba. */
    private fun material(): MpCharacterMaterial = MpCharacterMaterial(
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        momentDao = db.momentDao(),
        now = { clock },
        zone = ZONE,
    )

    private suspend fun awaitState(vm: MiniPhoneMomentsViewModel, match: (MomentsUiState) -> Boolean): MomentsUiState =
        withTimeout(TIMEOUT_MS) { vm.uiState.first(match) }

    /** Crea una imagen falsa legible y registra sus bytes.;Seleccionar la misma imagen dos veces dará como resultado el mismo archivo. */
    private fun pickImage(name: String): Uri {
        val uri = Uri.parse("content://mp-test/$name")
        imageBytes[uri.toString()] = PNG_MAGIC + name.toByteArray()
        return uri
    }

    private fun openImage(uri: Uri): InputStream? = imageBytes[uri.toString()]?.let(::ByteArrayInputStream)

    private companion object {
        const val MOMENT_ID: String = "m1"
        const val SELF_MOMENT_ID: String = "mine"
        const val CHARACTER_ID: String = "c1"
        const val CHARACTER_NAME: String = "ali"
        const val SELF_NAME: String = "Ziqi"
        const val TIMEOUT_MS: Long = 5_000L

        /** Punto de partida del reloj falso,Nuevos lanzamientos garantizados/Me gusta/Todos los comentarios son más recientes que los datos iniciales. */
        const val NOW_BASE: Long = 100_000L

        /** diurno 14:00(Octavo Distrito Este):Para casos de uso anti-vibración,Evite los momentos de tranquilidad. */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        val DAYTIME_MS: Long = LocalDateTime.of(2026, 5, 12, 14, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val THIRTY_MINUTES_MS: Long = 30L * 60L * 1000L

        val PNG_MAGIC: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/** Haz solo las dos cosas que tu círculo de amigos necesita:Dar una lista de roles,Generar según script(o no generado)Un texto. */
private val CHARACTER = MpCharacter(id = "c1", name = "ali", tagline = "vivir abajo")

private class FakeMomentsHost(
    private val composed: String? = null,
) : MiniPhoneHost {

    var composeCalls: Int = 0
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(listOf(CHARACTER))

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? {
        composeCalls += 1
        return composed
    }

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** Asumir la configuración:Solo sirve afirmaciones anti-vibración —— Tenga en cuenta la hora en que fue escrito,El interruptor está siempre encendido. */
private class FakeAutoStore : MpMomentAutoStore {

    private val enabledState = MutableStateFlow(true)

    var lastGeneratedAtMillis: Long? = null
        private set

    override val enabledFlow: Flow<Boolean> = enabledState

    override suspend fun isEnabled(): Boolean = enabledState.value

    override suspend fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    override suspend fun lastGeneratedAt(): Long? = lastGeneratedAtMillis

    override suspend fun markGenerated(atMillis: Long) {
        lastGeneratedAtMillis = atMillis
    }

    override val chatProactiveEnabledFlow: Flow<Boolean> = flowOf(false)

    override suspend fun isChatProactiveEnabled(): Boolean = false

    override suspend fun setChatProactiveEnabled(enabled: Boolean) = Unit

    override suspend fun lastProactiveChatAt(): Long? = null

    override suspend fun markProactiveChat(atMillis: Long) = Unit
}

/** Como el decorador de DAO:Existir solo para una cosa —— Cuente si \"una vez en lotes\" o \"N veces una por una\". */
private class CountingLikeDao(private val delegate: MpMomentLikeDao) : MpMomentLikeDao {

    var batchCalls: Int = 0
        private set

    var singleCalls: Int = 0
        private set

    override suspend fun likesOf(momentId: String): List<MpMomentLikeEntity> {
        singleCalls += 1
        return delegate.likesOf(momentId)
    }

    override suspend fun likesOfMomentIds(momentIds: List<String>): List<MpMomentLikeEntity> {
        batchCalls += 1
        return delegate.likesOfMomentIds(momentIds)
    }

    override fun observeLikeCount(momentId: String): Flow<Int> = delegate.observeLikeCount(momentId)

    override suspend fun isLiked(momentId: String, likerType: MpActorType, likerId: String): Boolean =
        delegate.isLiked(momentId, likerType, likerId)

    override suspend fun insert(like: MpMomentLikeEntity) = delegate.insert(like)

    override suspend fun delete(momentId: String, likerType: MpActorType, likerId: String) = delegate.delete(momentId, likerType, likerId)
    override suspend fun deleteAllOf(momentId: String) = delegate.deleteAllOf(momentId)
}

/** Comentar sobre decoradores DAO:Lo mismo que arriba. */
private class CountingCommentDao(private val delegate: MpMomentCommentDao) : MpMomentCommentDao {

    var batchCalls: Int = 0
        private set

    var singleCalls: Int = 0
        private set

    override fun observeComments(momentId: String): Flow<List<MpMomentCommentEntity>> = delegate.observeComments(momentId)

    override suspend fun commentsOf(momentId: String): List<MpMomentCommentEntity> {
        singleCalls += 1
        return delegate.commentsOf(momentId)
    }

    override suspend fun commentsOfMomentIds(momentIds: List<String>): List<MpMomentCommentEntity> {
        batchCalls += 1
        return delegate.commentsOfMomentIds(momentIds)
    }

    override suspend fun count(momentId: String): Int = delegate.count(momentId)

    override suspend fun insert(comment: MpMomentCommentEntity) = delegate.insert(comment)

    override suspend fun delete(commentId: String) = delegate.delete(commentId)

    override suspend fun deleteByIds(commentIds: List<String>) = delegate.deleteByIds(commentIds)

    override suspend fun deleteAllOf(momentId: String) = delegate.deleteAllOf(momentId)
}
