package io.zer0.muse.miniphone.data.moment.auto

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Un proceso completo generado en segundo plano.:juicio → Elige un papel → generación de host → Cayó en la biblioteca → Lleve un registro del tiempo.
 *
 * el curry es realmente bueno Room(banco de memoria),Alias de host y configuración:De esta manera, "realmente lo escribí" y "no escribí nada cuando fallé".
 * Es una afirmación más que una evidencia. La sentencia misma es [MpMomentAutoPlanTest] Ha sido clavado uno por uno.,
 * La preocupación aquí es "lo que se hizo después de la sentencia".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpMomentAutoRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: MiniPhoneDb
    private lateinit var repository: MpMomentsRepository

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
        val imageDir = File(tempFolder.newFolder(), "images")
        repository = MpMomentsRepository(
            momentDao = db.momentDao(),
            likeDao = db.momentLikeDao(),
            commentDao = db.momentCommentDao(),
            imageStore = MpImageStore(directory = imageDir, openStream = { null }),
            now = { MOMENT_AT },
            newId = { MOMENT_ID },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun publishesOneAiMomentAndRecordsGenerationTime() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)
        val store = FakeAutoStore()

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpMomentAutoOutcome.Published(momentId = MOMENT_ID, characterId = CHARACTER_ID), outcome)
        assertEquals(1, host.composeCalls)
        assertEquals(1, db.momentDao().count())
        val saved = db.momentDao().getMoment(MOMENT_ID)
        assertNotNull(saved)
        assertEquals(MpMomentSource.AI, saved?.source)
        assertEquals(CHARACTER_ID, saved?.authorId)
        assertEquals(COMPOSED_TEXT, saved?.content)
        assertEquals(NOW, store.lastGeneratedAtMillis)
        // El mensaje del período de tiempo se entrega al anfitrión junto con el material.:14 Esa frase sigue ahí
        assertTrue(host.lastHint.orEmpty().contains("Fragmentos de tiempo de la tarde"))
        assertTrue("El material debe incluir lo que sucedió recientemente.: ${host.lastHint}", host.lastHint.orEmpty().contains("Aún no he chateado con el usuario"))
    }

    @Test
    fun composeReturningNullInsertsNothingAndDoesNotRetry() = runBlocking {
        val host = FakeHost(content = null)
        val store = FakeAutoStore()

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpMomentAutoOutcome.Failed("compose_null"), outcome)
        // Ajuste solo una vez por ronda:No reintentar en caso de error,Vuelve naturalmente en el próximo ciclo.
        assertEquals(1, host.composeCalls)
        assertEquals(0, db.momentDao().count())
        // La marca de tiempo no se registrará si la base de datos no está en uso.,De lo contrario, la siguiente ronda será bloqueada por su propio antivibración.
        assertNull(store.lastGeneratedAtMillis)
    }

    @Test
    fun blankContentIsNotStored() = runBlocking {
        val outcome = runner(host = FakeHost(content = "   "), store = FakeAutoStore()).run()

        assertEquals(MpMomentAutoOutcome.Failed("compose_null"), outcome)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun quietHoursSkipBeforeCallingHost() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeAutoStore(), now = NIGHT).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.QUIET_HOURS), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun thirtyMinutesAfterLastGenerationSkips() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)
        val store = FakeAutoStore(lastGeneratedAt = NOW - THIRTY_MINUTES_MS)

        val outcome = runner(host = host, store = store).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.TOO_SOON), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun highRollSkipsAsProbability() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeAutoStore(), roll = 0.9).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.PROBABILITY), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun noCharacterSkips() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT, characters = emptyList())

        val outcome = runner(host = host, store = FakeAutoStore()).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.NO_CHARACTER), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    @Test
    fun switchOffSkipsEverything() = runBlocking {
        val host = FakeHost(content = COMPOSED_TEXT)

        val outcome = runner(host = host, store = FakeAutoStore(enabled = false)).run()

        assertEquals(MpMomentAutoOutcome.Skipped(MpMomentSkipReason.DISABLED), outcome)
        assertEquals(0, host.composeCalls)
        assertEquals(0, db.momentDao().count())
    }

    private fun runner(host: MiniPhoneHost, store: MpMomentAutoStore, now: Long = NOW, roll: Double = 0.1): MpMomentAutoRunner =
        MpMomentAutoRunner(
            host = host,
            repository = repository,
            store = store,
            material = MpCharacterMaterial(
                threadDao = db.threadDao(),
                messageDao = db.messageDao(),
                momentDao = db.momentDao(),
                now = { now },
                zone = ZONE,
            ),
            now = { now },
            random = FixedRandom(roll),
            zone = ZONE,
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** Durante el día 14:00: no durante las horas tranquilas. */
        val NOW: Long = LocalDateTime.of(2026, 5, 12, 14, 0).atZone(ZONE).toInstant().toEpochMilli()

        /** Noche 23:00: Hora de tranquilidad. */
        val NIGHT: Long = LocalDateTime.of(2026, 5, 12, 23, 0).atZone(ZONE).toInstant().toEpochMilli()

        const val THIRTY_MINUTES_MS: Long = 30L * 60L * 1000L

        const val CHARACTER_ID: String = "c1"

        const val MOMENT_ID: String = "moment-auto-1"

        const val MOMENT_AT: Long = 1_000L

        const val COMPOSED_TEXT: String = "El viento era un poco fuerte por la tarde, así que compré una taza de té con leche caliente."
    }
}

/** dados fijos:Léelo para juzgar la probabilidad y seleccionar personajes.,Haga que el proceso de ronda sea completamente predecible. */
private class FixedRandom(
    private val value: Double,
) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}

/** anfitrión falso:La lista de caracteres y los resultados de la generación son valores fijos.,Por cierto, tomé nota de cuántas veces me trasladaron.hint Qué es. */
private class FakeHost(
    private val content: String?,
    private val characters: List<MpCharacter> = listOf(MpCharacter(id = "c1", name = "ali")),
) : MiniPhoneHost {

    var composeCalls: Int = 0
        private set

    var lastHint: String? = null
        private set

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = "Ziqi"))

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? {
        composeCalls++
        lastHint = hint
        return content
    }

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** Asumir la configuración:Dobles variables para cambios y últimos tiempos de generación,Deje el valor escrito en el campo para la afirmación. */
private class FakeAutoStore(
    enabled: Boolean = true,
    lastGeneratedAt: Long? = null,
) : MpMomentAutoStore {

    private val enabledState = MutableStateFlow(enabled)

    var lastGeneratedAtMillis: Long? = lastGeneratedAt
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
