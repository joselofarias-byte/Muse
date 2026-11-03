package io.zer0.muse.ui.settings

import io.zer0.muse.rag.EmbeddingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bloqueo de regla de resumen "Qué backend integrado está actualmente en uso".
 *
 * Configurar la tarjeta de estado de la página solo se representa [resolveActiveBackendSummary] El resultado de , por lo que cada afirmación aquí corresponde a
 * Una copia que puede o no ser vista por el usuario; la advertencia no debe estar vacía al degradar; de lo contrario, la degradación volverá a ser silenciosa.
 */
class ActiveBackendSummaryTest {

    private val fallback = "Se ha degradado a otro backend, el motivo específico no se registra"

    private fun state(
        displayName: String = "Local Keyword (Offline)",
        modelName: String = "local-keyword-v1",
        degraded: Boolean = false,
        reason: String? = null,
    ) = EmbeddingService.BackendState(
        backendId = "local-keyword",
        displayName = displayName,
        modelName = modelName,
        degraded = degraded,
        reason = reason,
    )

    /** Regla 1: aún no he realizado la búsqueda (state es nulo), devuelve nulo y la persona que llama no representa el bloque completo. */
    @Test
    fun `null state yields no summary`() {
        assertNull(resolveActiveBackendSummary(null, fallback))
    }

    /** Regla 2: solo existe la fila principal cuando no se degrada.warning Debe ser nulo (no se muestran líneas de advertencia). */
    @Test
    fun `healthy backend yields primary without warning`() {
        val summary = resolveActiveBackendSummary(state(), fallback)
        assertEquals("Local Keyword (Offline) · local-keyword-v1", summary?.primary)
        assertNull(summary?.warning)
    }

    /** Regla 3: Al bajar de categoría con motivos,warning Utilice la razón tal como está. */
    @Test
    fun `degraded backend exposes reason as warning`() {
        val reason = "Provider OpenAI La clave API está vacía"
        val summary = resolveActiveBackendSummary(
            state(degraded = true, reason = reason),
            fallback,
        )
        assertEquals("Local Keyword (Offline) · local-keyword-v1", summary?.primary)
        assertEquals(reason, summary?.warning)
    }

    /** Regla 4: al bajar de categoría pero el motivo está vacío, utilice texto encubierto y no se permite que la advertencia vuelva a ser nula. */
    @Test
    fun `degraded backend without reason falls back to default text`() {
        val empty = resolveActiveBackendSummary(state(degraded = true, reason = null), fallback)
        assertEquals(fallback, empty?.warning)

        val blank = resolveActiveBackendSummary(state(degraded = true, reason = "   "), fallback)
        assertEquals(fallback, blank?.warning)
    }

    /** Regla 5: cuando el nombre del modelo está vacío, la fila principal solo muestra el nombre del backend, sin dejar ningún separador huérfano. */
    @Test
    fun `blank model name yields display name only`() {
        val summary = resolveActiveBackendSummary(state(modelName = ""), fallback)
        assertEquals("Local Keyword (Offline)", summary?.primary)
    }
}
