package io.zer0.muse.ui.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tres salidas después de que falla la síntesis de la nube.
 *
 * Si el motor TTS del sistema realmente se inicia una vez, no puede obtener estas ramas (devolución de llamada asíncrona + diferencia de dispositivo), por lo que la lógica de juicio cobrará una comisión.
 * [resolveCloudSynthOutcome], haga clic en "Éxito en la nube/Retroceso disponible/No hay ningún lugar donde caer" para fijarlos uno por uno.
 */
class TtsSynthOutcomeTest {

    @Test
    fun `Cuando la nube tiene éxito, la nube se encuentra en un estado exitoso sin ajustar el sistema. TTS`() = runBlocking {
        var systemCalls = 0

        val result = resolveCloudSynthOutcome(
            cloudOk = true,
            failureReason = "Razones por las que no se debe utilizar",
            systemTtsReady = true,
        ) {
            systemCalls++
            true
        }

        assertEquals(TtsSynthResult.Success, result)
        assertEquals("El éxito de la nube no debería costar otra síntesis del sistema", 0, systemCalls)
    }

    @Test
    fun `Retroceder con los motivos cuando la nube falla pero el sistema está disponible`() = runBlocking {
        val result = resolveCloudSynthOutcome(
            cloudOk = false,
            failureReason = "API Key esta vacio",
            systemTtsReady = true,
        ) { true }

        assertTrue("Si la nube falla y el sistema está disponible, debe estar en estado de reserva. De hecho,: $result", result is TtsSynthResult.SystemFallback)
        assertEquals("API Key esta vacio", (result as TtsSynthResult.SystemFallback).reason)
    }

    @Test
    fun `Cuando el sistema no está disponible, es un fracaso total y la síntesis del sistema no se ajusta.`() = runBlocking {
        var systemCalls = 0

        val result = resolveCloudSynthOutcome(
            cloudOk = false,
            failureReason = "API Key esta vacio",
            systemTtsReady = false,
        ) {
            systemCalls++
            true
        }

        assertTrue("Si el TTS del sistema no está listo, solo puede estar en un estado fallido. De hecho,: $result", result is TtsSynthResult.Failure)
        assertTrue(
            "La razón debe ser que tanto la nube como el sistema no están disponibles.: $result",
            (result as TtsSynthResult.Failure).reason.contains("API Key esta vacio"),
        )
        assertEquals(0, systemCalls)
    }

    @Test
    fun `Cuando la síntesis del sistema también falla, es un completo fracaso.`() = runBlocking {
        val result = resolveCloudSynthOutcome(
            cloudOk = false,
            failureReason = "Error en la síntesis de TTS en la nube",
            systemTtsReady = true,
        ) { false }

        assertTrue("El fracaso de la síntesis del sistema también es un completo fracaso. De hecho,: $result", result is TtsSynthResult.Failure)
    }
}
