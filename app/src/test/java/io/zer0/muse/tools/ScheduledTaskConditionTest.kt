package io.zer0.muse.tools

import io.zer0.muse.data.schedule.AutomationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de construcción de parámetros de condición de tarea cronometrada.
 *
 * Comentarios de los usuarios "condition_type De hecho, sólo siempre es confiable." Después de la verificación, la causa raíz no es que el motor de condiciones no esté implementado.
 * (`ScheduledTaskRunner.evaluateCondition` Hay 8 especies), pero**Ruta de creación de herramientas solo escritura
 * `{"type":"x"}`, no escriba parámetros**:
 *  - time_range desaparecido startHour/endHour → Degenerado 0..23, constante verdadera
 *  - contains desaparecido keyword → vacaciones permanentes
 * Por lo tanto, el modelo pensó que las condiciones estaban dadas, pero en realidad no lo estaban. Fije aquí "Rechazar la creación si los parámetros están incompletos".
 *
 * Pruebas puras de JVM: solo prueba construcciones JSON, no se requiere tiempo de ejecución de Android.
 */
class ScheduledTaskConditionTest {

    private fun build(
        type: String,
        params: ScheduledTaskToolsImpl.Companion.ConditionParams =
            ScheduledTaskToolsImpl.Companion.ConditionParams(),
    ): String? = ScheduledTaskToolsImpl.buildConditionJson(conditionType = type, params = params)

    private fun params(
        startHour: String? = null,
        endHour: String? = null,
        keyword: String? = null,
        tag: String? = null,
        minLevel: String? = null,
    ) = ScheduledTaskToolsImpl.Companion.ConditionParams(startHour, endHour, keyword, tag, minLevel)

    private fun decode(json: String): AutomationConfig.Condition =
        io.zer0.common.AppJson.decodeFromString(AutomationConfig.Condition.serializer(), json)

    @Test
    fun `always writes an empty string for backward compatibility`() {
        assertEquals("", build(AutomationConfig.Condition.ALWAYS))
    }

    @Test
    fun `time range keeps both hours so it does not degrade to always true`() {
        val json = build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "9", endHour = "18"))

        assertNotNull("time_range Debe poder construir con todos los parámetros.", json)
        val condition = decode(json!!)
        assertEquals(AutomationConfig.Condition.TIME_RANGE, condition.type)
        assertEquals("9", condition.config["startHour"].toString().trim('"'))
        assertEquals("18", condition.config["endHour"].toString().trim('"'))
    }

    @Test
    fun `time range without hours is rejected instead of silently becoming always`() {
        assertNull(build(AutomationConfig.Condition.TIME_RANGE))
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "9")))
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(endHour = "18")))
    }

    @Test
    fun `time range rejects out of range hours`() {
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "24", endHour = "30")))
        assertNull(build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "-1", endHour = "8")))
    }

    @Test
    fun `contains keeps the keyword so it does not become always false`() {
        val json = build(AutomationConfig.Condition.CONTAINS, params(keyword = "reunión"))

        assertNotNull(json)
        assertEquals("reunión", decode(json!!).config["keyword"].toString().trim('"'))
    }

    @Test
    fun `contains without keyword is rejected`() {
        assertNull(build(AutomationConfig.Condition.CONTAINS))
        assertNull(build(AutomationConfig.Condition.CONTAINS, params(keyword = "   ")))
    }

    @Test
    fun `quick note exists accepts keyword or tag but not neither`() {
        assertNotNull(build(AutomationConfig.Condition.QUICK_NOTE_EXISTS, params(keyword = "compras")))
        assertNotNull(build(AutomationConfig.Condition.QUICK_NOTE_EXISTS, params(tag = "trabajo")))
        assertNull("Ambos son vacíos e iguales a la verdad constante.,debe negarse", build(AutomationConfig.Condition.QUICK_NOTE_EXISTS))
    }

    @Test
    fun `battery level requires a sane threshold`() {
        val json = build(AutomationConfig.Condition.BATTERY_LEVEL, params(minLevel = "30"))

        assertNotNull(json)
        assertEquals("30", decode(json!!).config["minLevel"].toString().trim('"'))
        assertNull(build(AutomationConfig.Condition.BATTERY_LEVEL))
        assertNull(build(AutomationConfig.Condition.BATTERY_LEVEL, params(minLevel = "0")))
        assertNull(build(AutomationConfig.Condition.BATTERY_LEVEL, params(minLevel = "101")))
    }

    @Test
    fun `network available and charging need no extra parameters`() {
        assertNotNull(build(AutomationConfig.Condition.NETWORK_AVAILABLE))
        assertNotNull(build(AutomationConfig.Condition.CHARGING))
    }

    @Test
    fun `unknown condition type is rejected rather than stored as an unusable task`() {
        assertNull(build("some_future_type"))
    }

    @Test
    fun `condition json round trips through the runner parser`() {
        val json = build(AutomationConfig.Condition.TIME_RANGE, params(startHour = "8", endHour = "22"))!!

        // Runner Así se analiza el lateral(conditionJson.toCondition())
        val parsed = with(AutomationConfig) { json.toCondition() }

        assertEquals(AutomationConfig.Condition.TIME_RANGE, parsed.type)
        assertTrue(parsed.config.containsKey("startHour"))
    }
}
