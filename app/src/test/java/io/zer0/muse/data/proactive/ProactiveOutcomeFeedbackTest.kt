package io.zer0.muse.data.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.proactive.ProactiveCandidateFilter
import io.zer0.muse.proactive.ProactiveCycleRunner
import io.zer0.muse.proactive.ProactiveScheduler
import io.zer0.muse.proactive.ProactiveStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
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

/**
 * Hay cuatro destinos para los mensajes no solicitados: tachados/en los que se hizo clic/caducados/aprendidos a “no volver a decir esto”.
 *
 * Ejecutar con almacén real + sala de memoria: estas cuatro cosas dependen de las condiciones de estado de SQL (el estado final no está degradado,
 * Tome solo el último, solo PENDIENTE) y reemplácelo con el resultado de reproducción falso del almacén, lo que equivale a no verificar nada.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveOutcomeFeedbackTest {

    private companion object {
        const val ASSISTANT = "a1"
        const val SESSION = "s1"
        const val OTHER_SESSION = "s2"
        const val HOUR = 3_600_000L

        /** El valor inicial de la intimidad, y [ProactiveRelationEntity] El valor predeterminado es el mismo. */
        const val INITIAL_INTIMACY = 0.15f
        const val EPS = 1e-4f
    }

    private lateinit var database: MuseDb
    private lateinit var relations: ProactiveRelationRepository
    private lateinit var outcomes: ProactiveOutcomeRepository
    private lateinit var scheduler: ProactiveScheduler

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        relations = ProactiveRelationRepository(database.proactiveRelationDao())
        outcomes = ProactiveOutcomeRepository(database.proactiveOutcomeDao(), relations)
        scheduler = ProactiveScheduler(
            stores = ProactiveStores(
                thoughts = ProactiveThoughtRepository(
                    readDao = database.pendingThoughtDao(),
                    writeDao = database.pendingThoughtWriteDao(),
                ),
                relations = relations,
                outcomes = outcomes,
            ),
            cycle = mockk<ProactiveCycleRunner>(relaxed = true),
            appScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ══════════════════════════════════════════════════════════════
    // 1. "Esta vez no" en el aviso
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Haga clic en No marcar este tiempo como tachado y enfriado`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)

        scheduler.onProactiveDismissed(id)

        val stored = outcome(id)
        assertEquals(OutcomeState.DISMISSED, stored.stateEnum())
        assertNotNull("Debes anotar el tiempo que tachaste, de lo contrario no podrás contar cuánto tiempo tomó tacharlo.", stored.dismissedAt)
        val expected = ProactiveLearning.applyOutcome(INITIAL_INTIMACY, OutcomeState.DISMISSED)
        assertEquals(expected, intimacy(), EPS)
        assertTrue("Tacharlo debería significar enfriarse, no lo hagas en sentido contrario.", expected < INITIAL_INTIMACY)
    }

    @Test
    fun `Tachar el mismo elemento dos veces no provocará un enfriamiento repetido`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)
        scheduler.onProactiveDismissed(id)
        val first = outcome(id)
        val afterFirst = intimacy()

        scheduler.onProactiveDismissed(id)

        val second = outcome(id)
        assertEquals(OutcomeState.DISMISSED, second.stateEnum())
        assertEquals("Hacer clic en el botón repetidamente no debería actualizar la marca de tiempo", first.dismissedAt, second.dismissedAt)
        assertEquals("Hacer clic en el botón repetidamente no debería deducir el nivel de intimidad nuevamente", afterFirst, intimacy(), EPS)
    }

    @Test
    fun `Tachar la notificación después de responder no degradará la respuesta`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)
        outcomes.resolve(id, OutcomeState.REPLIED, now)

        scheduler.onProactiveDismissed(id)

        assertEquals(OutcomeState.REPLIED, outcome(id).stateEnum())
    }

    // ══════════════════════════════════════════════════════════════
    // 2. Abre la sesión y léela.
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Abre la conversación y marca la respuesta pendiente como leída.`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now, assistantId = ASSISTANT)

        scheduler.onSessionOpened(SESSION)

        val stored = outcome(id)
        assertEquals(OutcomeState.OPENED, stored.stateEnum())
        assertNotNull("Anota el horario de apertura", stored.openedAt)
    }

    @Test
    fun `Abra una conversación y marque solo la entrada más reciente de la conversación.`() = runTest {
        val older = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 2 * HOUR, assistantId = ASSISTANT)
        val newer = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - HOUR, assistantId = ASSISTANT)
        val elsewhere = outcomes.recordSent(thought = null, sessionId = OTHER_SESSION, now = now, assistantId = ASSISTANT)

        scheduler.onSessionOpened(SESSION)

        assertEquals("El más reciente ha sido leído.", OutcomeState.OPENED, outcome(newer).stateEnum())
        assertEquals("El anterior tampoco debe marcarse como leído.", OutcomeState.PENDING, outcome(older).stateEnum())
        assertEquals("Otras conversaciones no deberían estar involucradas.", OutcomeState.PENDING, outcome(elsewhere).stateEnum())
    }

    @Test
    fun `Abrir una sesión no sobrescribe los registros ya respondidos`() = runTest {
        val id = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - HOUR, assistantId = ASSISTANT)
        outcomes.resolve(id, OutcomeState.REPLIED, now - HOUR)

        scheduler.onSessionOpened(SESSION)

        val stored = outcome(id)
        assertEquals(OutcomeState.REPLIED, stored.stateEnum())
        assertNull("Los registros respondidos no deben tener un tiempo de apertura adicional", stored.openedAt)
    }

    // ══════════════════════════════════════════════════════════════
    // 3. Caducidad
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Si a nadie le importa durante un día, se considerará caducado.`() = runTest {
        val stale = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 25 * HOUR, assistantId = ASSISTANT)

        val expired = outcomes.expireOverdue(now)

        assertEquals(1, expired)
        assertEquals(OutcomeState.EXPIRED, outcome(stale).stateEnum())
        val expected = ProactiveLearning.applyOutcome(INITIAL_INTIMACY, OutcomeState.EXPIRED)
        assertEquals("El final también debe considerarse como retroalimentación y el grado de intimidad debe ajustarse en consecuencia.", expected, intimacy(), EPS)
    }

    @Test
    fun `Los registros de respuesta pendientes que tengan menos de un día de antigüedad no se moverán.`() = runTest {
        val fresh = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 23 * HOUR, assistantId = ASSISTANT)

        val expired = outcomes.expireOverdue(now)

        assertEquals(0, expired)
        assertEquals(OutcomeState.PENDING, outcome(fresh).stateEnum())
        assertNull("No debes tocar el nivel de intimidad antes de que llegue al punto, y ni siquiera deberías armar una pelea.", relations.get(ASSISTANT))
    }

    @Test
    fun `Los registros que se hayan abierto no se sobrescribirán cuando caduquen`() = runTest {
        val opened = outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 30 * HOUR, assistantId = ASSISTANT)
        outcomes.resolve(opened, OutcomeState.OPENED, now - 2 * HOUR)

        assertEquals(0, outcomes.expireOverdue(now))
        assertEquals(OutcomeState.OPENED, outcome(opened).stateEnum())
    }

    @Test
    fun `Caducado solo se ejecuta una vez`() = runTest {
        outcomes.recordSent(thought = null, sessionId = SESSION, now = now - 25 * HOUR, assistantId = ASSISTANT)

        assertEquals(1, outcomes.expireOverdue(now))
        assertEquals("No deberías volver a cobrarlo en la segunda ronda.", 0, outcomes.expireOverdue(now))
    }

    // ══════════════════════════════════════════════════════════════
    // 4. Los tipos impopulares ingresan al filtrado de candidatos
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Los tipos que estén tachados repetidamente serán bloqueados para los candidatos.`() = runTest {
        repeat(3) { seedOutcome("d$it", ThoughtKind.MILESTONE, OutcomeState.DISMISSED, now - it * HOUR) }
        seedOutcome("r1", ThoughtKind.MILESTONE, OutcomeState.REPLIED, now - 3 * HOUR)
        seedOutcome("c1", ThoughtKind.CARE, OutcomeState.REPLIED, now - 4 * HOUR)

        val banned = outcomes.unpopularKinds(ASSISTANT, listOf(ThoughtKind.MILESTONE, ThoughtKind.CARE))

        assertEquals("Sólo las categorías con más de la mitad de la tasa de tachaduras deben incluirse en la lista negra.", setOf(ThoughtKind.MILESTONE), banned)
        val candidates = listOf(
            thought("t1", ThoughtKind.MILESTONE, weight = 0.9f),
            thought("t2", ThoughtKind.CARE, weight = 0.2f),
        )
        assertTrue("Premisa: El archivo estándar permite estas dos categorías.", ProactiveIntensity.STANDARD.allows(ThoughtKind.MILESTONE))
        assertEquals(
            "Después de que aquellos con mayor peso sean incluidos en la lista negra, sólo queda otra categoría entre los candidatos.",
            listOf("t2"),
            ProactiveCandidateFilter.select(candidates, ProactiveIntensity.STANDARD, banned).map { it.id },
        )
        assertEquals(
            "Ambos estaban presentes cuando no estaba bloqueado, lo que demuestra que el anterior se perdió debido al filtrado en lugar de la clasificación.",
            listOf("t1", "t2"),
            ProactiveCandidateFilter.select(candidates, ProactiveIntensity.STANDARD).map { it.id },
        )
    }

    @Test
    fun `Si no hay muestras suficientes, no se considerará no interesado.`() = runTest {
        repeat(2) { seedOutcome("d$it", ThoughtKind.MILESTONE, OutcomeState.DISMISSED, now - it * HOUR) }
        repeat(3) { seedOutcome("r$it", ThoughtKind.MILESTONE, OutcomeState.REPLIED, now - (it + 3) * HOUR) }

        val banned = outcomes.unpopularKinds(ASSISTANT, listOf(ThoughtKind.MILESTONE))

        assertTrue("La tasa de tachados es inferior a la mitad y las muestras son insuficientes, por lo que no debería incluirlo en la lista negra.", banned.isEmpty())
        assertFalse(outcomes.isKindUnpopular(ASSISTANT, ThoughtKind.CARE))
    }

    // ══════════════════════════════════════════════════════════════

    private suspend fun outcome(id: String): ProactiveOutcomeEntity =
        requireNotNull(database.proactiveOutcomeDao().getById(id)) { "El resultado debería estar en la cuenta. $id" }

    private suspend fun intimacy(): Float =
        requireNotNull(relations.get(ASSISTANT)) { "Para llevar cuentas debe haber intimidad" }.intimacy

    private suspend fun seedOutcome(id: String, kind: ThoughtKind, state: OutcomeState, sentAt: Long) {
        database.proactiveOutcomeDao().upsert(
            ProactiveOutcomeEntity(
                id = id,
                assistantId = ASSISTANT,
                sessionId = SESSION,
                kind = kind.name,
                sentAt = sentAt,
                state = state.name,
            ),
        )
    }

    private fun thought(id: String, kind: ThoughtKind, weight: Float) = PendingThoughtEntity(
        id = id,
        assistantId = ASSISTANT,
        kind = kind.name,
        seed = "para probar",
        weight = weight,
        createdAt = now,
    )
}
