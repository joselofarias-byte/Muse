package io.zer0.muse.intent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AUD-20261011-TASKER-ABUSE(.audit REPORT_SECURITY F1 / Rendimiento F2):
 *
 * TaskerTriggerReceiver si exported=true Entrada de difusión sin permiso
 * (AndroidManifest.xml:263-269), cualquier aplicación puede enviarlo cíclicamente
 * io.zer0.muse.action.TASKER_TRIGGER. Mitigación verificada:completeText Sin herramientas,
 * La transmisión externa no puede desencadenar indirectamente la ejecución de la herramienta y no existe riesgo de fuga de datos del usuario. Pero faltan tres líneas de defensa de costos:
 *  1) Sin interruptor —— Incluso si el usuario nunca ha habilitado la vinculación de Tasker, responderá;
 *  2) Sin límite superior de longitud —— LLM de facturación de transferencia extra larga y extra larga;
 *  3) Frecuencia ilimitada —— Las llamadas continuas se pueden activar en un bucle.
 *
 * La decisión se extrae en una función pura para pruebas únicas (Receiver Depende de BroadcastReceiver + Koin, no se puede construir directamente dentro de la unidad).
 */
class TaskerTriggerPolicyTest {

    @Test
    fun `disabled by default rejects the trigger`() {
        assertEquals(
            "Debe ser rechazado cuando está desactivado de forma predeterminada.(Tasker El enlace es una característica opcional.,No debería estar abierto por defecto)",
            TaskerTriggerGuard.Decision.REJECTED_DISABLED,
            TaskerTriggerGuard.evaluate(
                enabled = false,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `blank prompt is rejected`() {
        assertEquals(
            TaskerTriggerGuard.Decision.REJECTED_BLANK,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 0,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `oversized prompt is rejected`() {
        assertEquals(
            "Se deben rechazar las indicaciones demasiado largas.,Evite que aplicaciones externas incluyan texto enorme en el modelo de facturación.",
            TaskerTriggerGuard.Decision.REJECTED_TOO_LONG,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 100_000,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `rapid retrigger inside the interval is throttled`() {
        assertEquals(
            "Las transmisiones repetidas dentro de la ventana de limitación de frecuencia deben limitarse,De lo contrario, el ciclo puede activarlo para eliminar la facturación.",
            TaskerTriggerGuard.Decision.REJECTED_THROTTLED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = 800L,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `first trigger with valid prompt is accepted`() {
        assertEquals(
            TaskerTriggerGuard.Decision.ACCEPTED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 1_000L,
                lastAcceptedAtMs = null,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `trigger after the interval elapsed is accepted`() {
        assertEquals(
            TaskerTriggerGuard.Decision.ACCEPTED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 10_000L,
                lastAcceptedAtMs = 1_000L,
                minIntervalMs = 5_000L,
            ),
        )
    }

    @Test
    fun `clock regression does not permanently block`() {
        // Devolución de hora del sistema:now antes que lastAccepted,no será juzgado como"Nunca limites la frecuencia"
        assertEquals(
            TaskerTriggerGuard.Decision.ACCEPTED,
            TaskerTriggerGuard.evaluate(
                enabled = true,
                promptLength = 10,
                maxPromptLength = 4000,
                nowMs = 500L,
                lastAcceptedAtMs = 9_000L,
                minIntervalMs = 5_000L,
            ),
        )
    }
}
