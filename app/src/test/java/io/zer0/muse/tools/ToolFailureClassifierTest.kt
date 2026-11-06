package io.zer0.muse.tools

import io.zer0.muse.tools.ToolFailureClassifier.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ensayos para clasificación de fallos de herramientas.
 *
 * Autoexamen del asistente: "La restricción de que solo se permite una corrección en caso de falla es correcta, pero cuando el mensaje de error es vago, solo puedo adivinar, y una vez que lo adivino, se descartará".
 * La clasificación y la capacidad de reintento se concretan aquí: las clases de permisos no deben ser reintentables (el reintento es inútil), las clases de parámetros deben ser reintentables,
 * Si no se puede tomar la decisión, sea conservador y no vuelva a intentarlo.
 */
class ToolFailureClassifierTest {

    private fun classify(text: String, timedOut: Boolean = false) = ToolFailureClassifier.classify(text, timedOut)

    @Test
    fun `timeout is retryable`() {
        val v = classify("[tiempo de espera] La herramienta web_fetch no responde durante 120 segundos,terminado")

        assertEquals(FailureKind.TIMEOUT, v.kind)
        assertTrue("Los tiempos de espera suelen ser seguros para volver a intentarlo.", v.retryable)
    }

    @Test
    fun `timeout flag wins even when text looks like something else`() {
        val v = classify("La conexión de red falló", timedOut = true)

        assertEquals(FailureKind.TIMEOUT, v.kind)
    }

    @Test
    fun `cancellation is not a failure and must not be retried`() {
        val v = classify("[Cancelar] El usuario deja de generar")

        assertEquals(FailureKind.CANCELLED, v.kind)
        assertFalse("La cancelación no es un error.,Reintentar anularía la intención del usuario", v.retryable)
    }

    @Test
    fun `permission failure is not retryable`() {
        val v = classify("[error] Falta la licencia de Shizuku,No se puede ejecutar el comando de shell")

        assertEquals(FailureKind.PERMISSION, v.kind)
        assertFalse("Volver a intentarlo no hará que los permisos aparezcan de la nada", v.retryable)
        assertTrue("Las sugerencias deben dirigirse a tool_status", v.hint.contains("tool_status"))
    }

    @Test
    fun `accessibility channel failure is classified as permission`() {
        assertEquals(FailureKind.PERMISSION, classify("[error] Los servicios de accesibilidad no están habilitados").kind)
    }

    @Test
    fun `not found is not retryable with the same value`() {
        val v = classify("[error] El archivo no existe o no es un archivo común y corriente: notes/todo.md")

        assertEquals(FailureKind.NOT_FOUND, v.kind)
        assertFalse(v.retryable)
    }

    @Test
    fun `network hiccup is retryable`() {
        // Utilice "Error de conexión" en lugar de "timeout":Este último será afectado primero por la rama TIMEOUT anterior.,Ese es el comportamiento correcto.
        val v = classify("[error] La conexión de red falló: restablecer la conexión")

        assertEquals(FailureKind.TRANSIENT, v.kind)
        assertTrue(v.retryable)
    }

    @Test
    fun `rate limit is retryable`() {
        assertEquals(FailureKind.TRANSIENT, classify("[error] 429 Too Many Requests").kind)
    }

    @Test
    fun `blocked by approval is not retryable`() {
        val v = classify("[error] Operación bloqueada por política de seguridad")

        assertEquals(FailureKind.BLOCKED, v.kind)
        assertFalse("No pases por alto la aprobación", v.retryable)
    }

    @Test
    fun `invalid argument is retryable after fixing`() {
        val v = classify("[error] Falta el parámetro requerido: path")

        assertEquals(FailureKind.INVALID_ARGUMENT, v.kind)
        assertTrue(v.retryable)
    }

    @Test
    fun `unrecognised failure defaults to not retryable`() {
        val v = classify("[error] estado interno desconocido xyz")

        assertEquals(FailureKind.UNKNOWN, v.kind)
        assertFalse("Si no puedes juzgar, no te animes a intentarlo de nuevo.", v.retryable)
    }

    @Test
    fun `receipt line is machine readable`() {
        val line = ToolFailureClassifier.receiptLine(classify("[error] Permisos insuficientes"))

        assertTrue(line.contains("kind=permission"))
        assertTrue(line.contains("retryable=false"))
        assertTrue("deberia traer hint", line.contains("hint="))
    }

    @Test
    fun `english permission text is recognised`() {
        assertEquals(FailureKind.PERMISSION, classify("Error: permission denied for this operation").kind)
    }

    @Test
    fun `cancellation wins over permission wording`() {
        // Los mensajes de cancelación suelen contener palabras distintas a "permiso",No puede ser arrebatado por otras ramas.
        val v = classify("[Cancelar] Usuario interrumpido mientras esperaba autorización")

        assertEquals(FailureKind.CANCELLED, v.kind)
    }
}
