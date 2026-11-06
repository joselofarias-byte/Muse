package io.zer0.muse.tools.script

import io.zer0.muse.runtime.MuseRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nodo integrado de script de CPU: afirmaciones estructurales (texto)+ Combinando aserciones (funciones puras).
 *
 * Verificación de comportamiento (ejecutándose vacío en un nodo real)/Dos escenarios no vacíos) no pueden ingresar a la prueba única de JVM, solo están bloqueados aquí
 * "¿Bajo qué condiciones se puede cubrir? ¿Están completos los campos de entrada sintéticos? ¿Están aislados los ámbitos?"
 */
class NodeCpuShimTest {

    private val shim = NodeCpuShim.script()

    @Test
    fun `env key matches runtime injection`() {
        assertEquals(MuseRuntime.ENV_CPU_COUNT, NodeCpuShim.ENV_KEY)
        assertTrue("shim Las variables de entorno inyectadas deben leerse", shim.contains("process.env.MUSE_CPU_COUNT"))
    }

    @Test
    fun `cpus override only when native list is empty`() {
        assertTrue(
            "Se reemplaza solo cuando la matriz nativa devuelve una matriz vacía os.cpus",
            shim.contains("if (envCount > 0 && readNativeCpus().length === 0)"),
        )
        assertTrue("Es necesario sobrescribir el propio os.cpus", shim.contains("os.cpus = function ()"))
        assertTrue("El número de entradas sintéticas toma el valor inyectado.", shim.contains("for (var i = 0; i < envCount; i++)"))
    }

    @Test
    fun `synthetic entries carry fields npm packages read`() {
        assertTrue(shim.contains("model: 'Muse virtual CPU'"))
        assertTrue(shim.contains("speed: 0"))
        assertTrue(shim.contains("times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 }"))
    }

    @Test
    fun `availableParallelism override only when native is not positive`() {
        assertTrue("Sobrescriba solo si el número original no es positivo o se produce un error.", shim.contains("if (!(nativeValue > 0))"))
        assertTrue(shim.contains("os.availableParallelism = function () { return effective; }"))
        assertTrue("El valor sobrescrito utiliza preferentemente el número central detectado de forma nativa.", shim.contains("var effective = detected > 0 ? detected :"))
    }

    @Test
    fun `shim is scoped in an iife and does not leak globals`() {
        val lines = shim.lines().filter { it.isNotBlank() }
        assertEquals("La primera línea debe comenzar con una función autoejecutable.", ";(function () {", lines.first().trim())
        assertEquals("La última línea debe cerrar la función autoejecutable.", "})();", lines.last().trim())
    }

    @Test
    fun `wrap puts shim before body and keeps body intact`() {
        val body = "console.log('user script');"
        val wrapped = NodeCpuShim.wrap(body)
        assertEquals(shim + "\n" + body, wrapped)
        assertTrue("shim Debe ejecutarse antes del cuerpo del script.", wrapped.indexOf("os.cpus = function") < wrapped.indexOf(body))
    }
}
