package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Prueba única del motor de decisión de mensajes activo.
 *
 * Este motor solo responde "¿puedes saberlo ahora?", por lo que la prueba cubre:
 *  - Cada puerta dura se puede interceptar de forma independiente (no molestar/el usuario solo habló/generando/enfriando/demasiado frecuente)
 *  - La relación de tres niveles entre piso, techo y valor de aprendizaje presupuestario
 *  - Asimetría de incrementos de retroalimentación.
 *
 * "Qué decir" no se prueba aquí. —— Eso se determina observando el diálogo reciente en el paso de generación, y el motor de juicio no está involucrado.
 *
 * Utilice una marca de tiempo fija en su lugar System.currentTimeMillis():día cruzado/afirmación a través del tiempo
 * Confiar en el tiempo absoluto se volverá inestable, y lo que más teme esta lógica es el azar.
 */
class ProactiveTimingEngineTest {

    /** 2026-10-25 14:00 Hora local (tarde, no durante ningún período DND predeterminado). */
    private val baseNow = localMillis(2026, 10, 25, 14, 0)

    // ══════════════════════════════════════════════════════════════
    // Momento: ¿Puedo decirlo?
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Interceptar directamente durante el período de no molestar`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(now = localMillis(2026, 10, 25, 23, 30)),
        )
        assertEquals(ProactiveTimingEngine.Verdict.QUIET_HOUR, decision.verdict)
        assertFalse(decision.shouldSend)
    }

    @Test
    fun `Si se permite durante el día, también se permitirá a altas horas de la noche`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(now = localMillis(2026, 10, 25, 23, 30), allDayAllowed = true),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `El usuario acaba de decir algo pero no lo envió`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastUserMessageAt = baseNow - 3 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.USER_ACTIVE, decision.verdict)
    }

    @Test
    fun `Un usuario ya no está activo después de 11 minutos de conversación`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastUserMessageAt = baseNow - 11 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `No interrumpir mientras se genera una respuesta`() {
        val decision = ProactiveTimingEngine.evaluate(input().copy(isGenerating = true))
        assertEquals(ProactiveTimingEngine.Verdict.IN_CONVERSATION, decision.verdict)
    }

    @Test
    fun `No emitido durante el período de enfriamiento`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(relation = relation().copy(cooldownUntil = baseNow + 24 * 3600_000L)),
        )
        assertEquals(ProactiveTimingEngine.Verdict.COOLDOWN, decision.verdict)
    }

    @Test
    fun `El último mensaje activo está demasiado cerca para enviarse`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveSentAt = baseNow - 30 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
    }

    @Test
    fun `Puedes enviarlo si el intervalo es suficiente`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveSentAt = baseNow - 4 * 3600_000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `Cuando la ronda anterior todavía se estaba generando, haz clic demasiado cerca para detenerla`() {
        // Como resultado, la cuenta está vacía (el mensaje aún no se ha grabado), pero se ha registrado la hora de inicio de la ronda anterior.
        // Esta es exactamente la fuente del envío duplicado dentro de la ventana de generación: con sólo mirar la cuenta de resultados te hará pensar "la ronda anterior no existió".
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveSentAt = 0L, lastProactiveAttemptAt = baseNow - 5 * 60 * 1000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.shouldSend)
    }

    @Test
    fun `Ha pasado mucho tiempo desde la última ronda de carrera, así que no pararé más`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(lastProactiveAttemptAt = baseNow - 4 * 3600_000L),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `El tiempo de prueba no enmascara los tiempos de envío más recientes`() {
        // Cuando existan dos marcas de tiempo al mismo tiempo, prevalecerá la más reciente.
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(
                lastProactiveSentAt = baseNow - 10 * 60 * 1000L,
                lastProactiveAttemptAt = baseNow - 5 * 3600_000L,
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
    }

    @Test
    fun `El estado del libro mayor ya no afecta la conclusión del juicio`() {
        // El material proviene de conversaciones recientes, no de libros de contabilidad. Se deben liberar los mismos insumos siempre que se superen el calendario y la cuota.
        val decision = ProactiveTimingEngine.evaluate(input())
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
        assertTrue(decision.shouldSend)
    }

    // ══════════════════════════════════════════════════════════════
    // presupuesto
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Informar con sinceridad cuando se agote el presupuesto y utilizarlo`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(
                relation = relation().copy(
                    // La cuota estándar es de 15 líneas por día y la cuota se “agotará” realmente una vez que se haya emitido por completo.
                    spentToday = 15,
                    intimacy = 0.15f,
                    intensity = ProactiveIntensity.STANDARD.name,
                ),
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.NO_BUDGET, decision.verdict)
    }

    @Test
    fun `Todavía hay una cuota cuando se emite el expediente estándar al artículo 14`() {
        val decision = ProactiveTimingEngine.evaluate(
            input().copy(
                relation = relation().copy(
                    spentToday = 14,
                    intensity = ProactiveIntensity.STANDARD.name,
                ),
            ),
        )
        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
    }

    @Test
    fun `El nivel estándar recibe 15 cuotas cada día`() {
        val relation = relation().copy(intimacy = 0.15f, intensity = ProactiveIntensity.STANDARD.name)
        // El cupo sólo depende del nivel y ya no se descontará por intimidad.
        assertEquals(15f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `El nivel de rigidez no se ve afectado por el nivel de intimidad`() {
        // intimacy 0 también debería ser ilimitado, en lugar de volver a caer a un valor mínimo muy pequeño
        val relation = relation().copy(intimacy = 0f, intensity = ProactiveIntensity.CLINGY.name)
        val remaining = ProactiveTimingEngine.remainingBudget(relation)
        assertTrue("No debería haber límite para el apego. De hecho, $remaining", remaining > 1_000_000f)
    }

    @Test
    fun `Las marchas silenciosas se fijan en 3 y no se ven afectadas por la intimidad`() {
        val relation = relation().copy(intimacy = 1f, intensity = ProactiveIntensity.QUIET.name)
        assertEquals(3f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `El monto restante disminuirá a medida que el monto se emita hoy`() {
        val idle = relation().copy(intimacy = 0.5f, intensity = ProactiveIntensity.ACTIVE.name, spentToday = 0)
        val used = idle.copy(spentToday = 3)
        assertEquals(30f, ProactiveTimingEngine.remainingBudget(idle), 0.001f)
        assertEquals(27f, ProactiveTimingEngine.remainingBudget(used), 0.001f)
    }

    @Test
    fun `El monto volverá a cero una vez que se haya emitido en su totalidad`() {
        val relation = relation().copy(intensity = ProactiveIntensity.RESERVED.name, spentToday = 8)
        assertEquals(0f, ProactiveTimingEngine.remainingBudget(relation), 0.001f)
    }

    @Test
    fun `El reabastecimiento interactivo ya no cambia el recuento de barras diarias`() {
        val base = relation().copy(intimacy = 0.5f, spentToday = 2, intensity = ProactiveIntensity.STANDARD.name)
        val earned = base.copy(earnedToday = 1f)
        assertEquals(
            ProactiveTimingEngine.remainingBudget(base),
            ProactiveTimingEngine.remainingBudget(earned),
            0.001f,
        )
    }

    @Test
    fun `Los recuentos de presupuesto deben restablecerse entre días`() {
        val relation = relation().copy(budgetDate = "2026-10-24")
        assertTrue(ProactiveTimingEngine.needsBudgetReset(relation, "2026-10-25"))
        assertFalse(ProactiveTimingEngine.needsBudgetReset(relation, "2026-10-24"))
    }

    // ══════════════════════════════════════════════════════════════
    // retroalimentación → intimidad
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `Los puntos de bonificación por responder son mucho mayores que la deducción por tachar`() {
        val replied = ProactiveLearning.intimacyDelta(OutcomeState.REPLIED)
        val dismissed = ProactiveLearning.intimacyDelta(OutcomeState.DISMISSED)
        assertTrue(replied > 0f)
        assertTrue(dismissed < 0f)
        // Es necesario tacharlo varias veces antes de que valga la pena responder. —— Porque tacharlo probablemente sea solo porque está ocupado en este momento.
        assertTrue(
            "La magnitud de la retroalimentación positiva y negativa debería ser significativamente asimétrica:reply=$replied dismiss=$dismissed",
            abs(replied) > abs(dismissed) * 1.5f,
        )
    }

    @Test
    fun `La cercanía se fija entre 0 y 1`() {
        assertEquals(1f, ProactiveLearning.applyOutcome(0.99f, OutcomeState.REPLIED), 0.001f)
        assertEquals(0f, ProactiveLearning.applyOutcome(0.01f, OutcomeState.DISMISSED), 0.001f)
    }

    @Test
    fun `Evite sacar conclusiones impopulares cuando la muestra sea demasiado pequeña`() {
        // 3 No importa si tachas todos los elementos. —— Muestra insuficiente
        assertFalse(ProactiveLearning.isKindUnpopular(dismissed = 3, total = 3))
        // 4 Sólo cuenta si 3 de los ítems están tachados
        assertTrue(ProactiveLearning.isKindUnpopular(dismissed = 3, total = 4))
        // 4 No cuenta si se tachan 2 elementos
        assertFalse(ProactiveLearning.isKindUnpopular(dismissed = 2, total = 4))
    }

    // ══════════════════════════════════════════════════════════════
    // Ayuda a la construcción
    // ══════════════════════════════════════════════════════════════

    /**
     * El auxiliar de construcción siempre tiene cero parámetros y por defecto es + copiar.
     *
     * De esta manera, cada caso de uso solo escribe una o dos diferencias que realmente le interesan y el lector no necesita contar las posiciones.
     */
    private fun input(): ProactiveTimingEngine.Input = ProactiveTimingEngine.Input(
        now = baseNow,
        relation = relation(),
        allowedHourStart = 8,
        allowedHourEnd = 22,
        allDayAllowed = false,
        lastUserMessageAt = 0L,
        isGenerating = false,
        lastProactiveSentAt = 0L,
    )

    private fun relation(): ProactiveRelationEntity = ProactiveRelationEntity(
        assistantId = "default",
        intimacy = 0.5f,
        cooldownUntil = 0L,
        intensity = ProactiveIntensity.STANDARD.name,
        budgetDate = "2026-10-25",
        spentToday = 0,
        earnedToday = 0f,
    )

    /** Cree una marca de tiempo en la zona horaria local para evitar la desviación al ejecutar pruebas en otras zonas horarias utilizando un desplazamiento fijo. */
    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
