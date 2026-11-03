package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Valor de detección de CPU en la autoprueba: correcto cuando es normal, advierte cuando no está disponible y no afecta la conclusión general. */
class RuntimeSelfCheckCpuProbeTest {

    private val okProbe = RuntimeSelfCheckTool.CpuProbe(entries = 8, parallelism = 8, envCount = 8)
    private val emptyProbe = RuntimeSelfCheckTool.CpuProbe(entries = 0, parallelism = 0, envCount = 8)

    @Test
    fun `cpu probe item exists as its own self-check entry`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(okProbe)
        assertTrue("Debe ser un elemento independiente.", section.startsWith("\n- CPU detección: "))
        assertTrue("necesidad de informar os.cpus().length", section.contains("os.cpus().length=8"))
        assertTrue("necesidad de informar availableParallelism", section.contains("os.availableParallelism()=8"))
        assertTrue("Necesidad de informar el valor de la inyección", section.contains("MUSE_CPU_COUNT=8"))
    }

    @Test
    fun `ok when both values are positive`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(okProbe)
        assertTrue("Se debe marcar el valor obtenido. ok", section.contains("✓"))
        assertFalse("No debe aparecer cuando se obtiene el valor. warn", section.contains("warn"))
    }

    @Test
    fun `warn when values cannot be read`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(emptyProbe)
        assertTrue("El valor no se puede obtener y debe marcarse warn", section.contains("warn"))
        assertFalse("No debería pujar ok", section.contains("✓"))
    }

    @Test
    fun `probe failure only warns and leaves overall verdict alone`() {
        val section = RuntimeSelfCheckTool.buildCpuSection(null)
        assertTrue(section.contains("warn"))
        assertFalse("El fracaso no se puede escribir como un fracaso general.", section.contains("Incompleto en tiempo de ejecución"))
        assertFalse("No puedes traer tus propias líneas de resumen (solo puedes explicar que no afecta la conclusión)", section.contains("Conclusión:"))
        assertFalse("No debe informar cuando no se ejecuta la sonda. ok", section.contains("✓"))
    }

    @Test
    fun `tool definition is registered under the stable name`() {
        assertEquals("runtime_selfcheck", RuntimeSelfCheckTool.toolDef().name)
        assertEquals(ToolRiskLevel.SAFE, RuntimeSelfCheckTool.toolDef().riskLevel)
    }
}
