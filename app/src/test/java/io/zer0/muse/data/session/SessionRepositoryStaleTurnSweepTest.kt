package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.session.TurnPhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * A9: Inicia el comportamiento de barrido de convergencia pendientes de giros.
 *
 * Fijar tres cosas:
 *  1. solo convergencia `finishedAt IS NULL` y `startedAt` filas antes de la ventana,Caer al estado final y cubrir el tiempo de finalización.;
 *  2. Las líneas en la ventana y las líneas completadas no se moverán.;
 *  3. Los resultados de la ejecución repetida son consistentes. —— La segunda vez devuelve 0 filas sin cambiar ningún dato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryStaleTurnSweepTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "stale-turn-${UUID.randomUUID()}")),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val grace = SessionRepository.STALE_TURN_GRACE_MS

    private suspend fun seedSession(id: String) {
        database.sessionDao().insert(
            SessionEntity(id = id, title = id, createdAt = 1_000L, updatedAt = 1_000L),
        )
    }

    /** Insertar un turn;[finishedAt] es nulo es decir“fila colgante”(En producción, el proceso muere y se deja atrás.). */
    private suspend fun seedTurn(
        turnId: String,
        sessionId: String,
        startedAt: Long,
        phase: String = "STREAMING",
        finishedAt: Long? = null,
    ) {
        database.conversationTurnDao().upsert(
            ConversationTurnEntity(
                turnId = turnId,
                sessionId = sessionId,
                inputUserMessageId = "$turnId-user",
                assistantMessageId = "$turnId-assistant",
                phase = phase,
                startedAt = startedAt,
                finishedAt = finishedAt,
                updatedAt = finishedAt ?: startedAt,
            ),
        )
    }

    private suspend fun turn(turnId: String): ConversationTurnEntity? =
        database.conversationTurnDao().getById(turnId)

    @Test
    fun `sweep closes stale open turns with a terminal phase and finishedAt`() = runBlocking {
        val now = 10_000_000L
        seedSession("session-a")
        seedSession("session-b")
        seedTurn("stale", "session-a", startedAt = now - grace - 60_000L)
        seedTurn("stale-2", "session-b", startedAt = now - grace - 1L)
        seedTurn("fresh", "session-a", startedAt = now - 60_000L)
        seedTurn("boundary", "session-a", startedAt = now - grace)
        seedTurn("done", "session-a", startedAt = now - grace - 60_000L, phase = "COMPLETED", finishedAt = now - 500L)

        assertEquals(2, repository.closeStaleOpenTurns(now))

        val stale = turn("stale")!!
        assertEquals(TurnPhase.FAILED.name, stale.phase)
        assertEquals(now, stale.finishedAt)
        assertEquals(now, stale.updatedAt)
        // Convergencia única entre sesiones,No es necesario procesar cada elemento por sessionId
        assertEquals(TurnPhase.FAILED.name, turn("stale-2")!!.phase)
        assertEquals(now, turn("stale-2")!!.finishedAt)

        // ventana interior(Contiene justo dentro del límite de la ventana)La suspensión no funciona.
        assertEquals("STREAMING", turn("fresh")!!.phase)
        assertNull(turn("fresh")!!.finishedAt)
        assertEquals("STREAMING", turn("boundary")!!.phase)
        assertNull(turn("boundary")!!.finishedAt)
        // Las filas completadas no retroceden ni se sobrescriben con el tiempo de finalización
        val done = turn("done")!!
        assertEquals("COMPLETED", done.phase)
        assertEquals(now - 500L, done.finishedAt)
    }

    @Test
    fun `sweep is idempotent`() = runBlocking {
        val now = 10_000_000L
        seedSession("session-a")
        seedTurn("stale", "session-a", startedAt = now - grace - 60_000L)
        seedTurn("fresh", "session-a", startedAt = now - 1_000L)

        assertEquals(1, repository.closeStaleOpenTurns(now))
        val firstPass = listOf(turn("stale"), turn("fresh"))

        assertEquals(0, repository.closeStaleOpenTurns(now))
        assertEquals(0, repository.closeStaleOpenTurns(now))
        assertEquals(firstPass, listOf(turn("stale"), turn("fresh")))
    }

    @Test
    fun `a turn already swept is never swept again even when time advances`() = runBlocking {
        val now = 10_000_000L
        seedSession("session-a")
        seedTurn("stale", "session-a", startedAt = now - grace - 60_000L)

        assertEquals(1, repository.closeStaleOpenTurns(now))
        // El reloj sigue corriendo:Se ha seguido la misma línea finishedAt,Los barridos iniciados posteriormente no deberían volver a golpearlo.
        assertEquals(0, repository.closeStaleOpenTurns(now + 2 * grace))
        assertEquals(TurnPhase.FAILED.name, turn("stale")!!.phase)
        assertEquals(now, turn("stale")!!.finishedAt)

        // Las filas dentro de la ventana convergen solo después de cruzar la ventana
        seedTurn("later", "session-a", startedAt = now - 60_000L)
        assertEquals(1, repository.closeStaleOpenTurns(now + grace))
        assertEquals(TurnPhase.FAILED.name, turn("later")!!.phase)
        assertEquals(now + grace, turn("later")!!.finishedAt)
        assertEquals(0, repository.closeStaleOpenTurns(now + grace))
    }
}
