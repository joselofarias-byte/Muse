package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.voice.MpVoiceCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Máquina de estado de entrada de voz.
 *
 * Lo que se afirma es la transición de estado y la cantidad de devoluciones de llamada provocadas por un clic.,No toques el micrófono ni Internet:
 * La entrada de la grabadora y la transcripción son implementaciones falsas.,Por lo tanto, se puede contar "si la escritura se ha ajustado o no" y "cuántas veces se ha transferido el texto".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpVoiceInputControllerTest {

    @Test
    fun `Cuando esté inactivo, haga clic en el micrófono para ingresar a la grabación y comenzar a contar desde cero segundos.`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)

        assertEquals(MpVoiceInputState.Idle, controller.state.value)

        controller.start()

        assertEquals(MpVoiceInputState.Recording(0), controller.state.value)
        assertEquals("El punto de partida sólo se ajusta una vez", 1, rig.capture.startCount)
    }

    @Test
    fun `Añade uno cada segundo durante la grabación.`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        advanceTimeBy(3_100)

        assertEquals(MpVoiceInputState.Recording(3), controller.state.value)
    }

    @Test
    fun `Después de finalizar la grabación, ingrese primero la transcripción, luego entregue el texto y vuelva al estado inactivo.`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()

        assertEquals("Debería transcribirse inmediatamente después de detener la minería.", MpVoiceInputState.Transcribing, controller.state.value)

        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("El texto solo se devuelve una vez", listOf("Hola"), rig.transcripts)
        assertEquals("Transcribe solo una vez", 1, rig.transcribeCount)
        assertEquals("Deje de minar y ajuste solo una vez", 1, rig.capture.stopCount)
        assertSame("La parte entregada al transcriptor debe ser la parte entregada por el registrador. WAV", rig.capture.wav, rig.lastWav)
    }

    @Test
    fun `Cancelar solo pierde el audio pero no lo transcribe`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.cancel()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("Cancelar para notificar al registrador que descarte", 1, rig.capture.cancelCount)
        assertEquals("Cancelar la transferencia que no se debe hacer", 0, rig.transcribeCount)
        assertNull("Cancelar texto que no debe enviarse", rig.transcripts.firstOrNull())

        runCurrent()

        assertEquals("El texto no debería aparecer después de un tiempo.", 0, rig.transcripts.size)
    }

    @Test
    fun `Se ignora hacer clic nuevamente en el micrófono durante la transcripción.`() = runTest {
        val rig = Rig()
        val gate = CompletableDeferred<String?>()
        rig.onTranscribe = { gate.await() }
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()
        runCurrent()
        assertEquals(MpVoiceInputState.Transcribing, controller.state.value)

        controller.start()

        assertEquals("No debes comenzar un nuevo párrafo antes de que finalice la transcripción.", MpVoiceInputState.Transcribing, controller.state.value)
        assertEquals("La grabadora nunca debe recuperarse.", 1, rig.capture.startCount)

        gate.complete("Transcrito")
        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals(listOf("Transcrito"), rig.transcripts)
    }

    @Test
    fun `Cuando el resultado del reconocimiento está vacío, indica que el mensaje no se escuchó claramente y vuelve al estado inactivo.`() = runTest {
        val rig = Rig()
        rig.onTranscribe = { "   " }
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()
        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("Solicitar resultados en blanco", 1, rig.emptyCount)
        assertTrue("Los resultados en blanco no se pueden ingresar en el cuadro de entrada", rig.transcripts.isEmpty())
    }

    @Test
    fun `Cuando no se recopile ningún audio, se mostrará el mensaje y el sistema volverá al estado inactivo.`() = runTest {
        val rig = Rig(capture = FakeCapture(wav = null))
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.stop()
        runCurrent()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals(1, rig.emptyCount)
        assertEquals("No debes solicitar una transcripción si no hay audio", 0, rig.transcribeCount)
    }

    @Test
    fun `Detiene automáticamente la grabación y transcribe después de un minuto`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        advanceTimeBy(MpVoiceInputController.MAX_RECORDING_SECONDS * 1_000L + 100L)
        runCurrent()

        assertEquals("Debes parar por tu cuenta después de llegar al punto.", 1, rig.capture.stopCount)
        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals(listOf("Hola"), rig.transcripts)
    }

    @Test
    fun `Cuando no se puede iniciar la grabadora, el estado sigue inactivo y se emite una devolución de llamada de error.`() = runTest {
        val rig = Rig(capture = FakeCapture(startResult = false))
        val controller = rig.controller(backgroundScope)

        controller.start()

        assertEquals("Si no puedes levantarte, no debes ingresar al modo de grabación", MpVoiceInputState.Idle, controller.state.value)
        assertEquals("Para decirle al usuario que el micrófono no está encendido", 1, rig.startFailedCount)
        assertEquals("No empieces a transcribir si fallas.", 0, rig.transcribeCount)
    }

    @Test
    fun `Cuando la configuración no admite la grabación, el micrófono no entrará en la grabación.`() = runTest {
        val rig = Rig(canRecord = false)
        val controller = rig.controller(backgroundScope)

        controller.start()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("Si no lo soportas, no debes tocar el micrófono.", 0, rig.capture.startCount)
        assertEquals("Este es un problema de configuración,No debería informar un error de inicio.", 0, rig.startFailedCount)
    }

    @Test
    fun `Descartar la grabación en curso al salir de la página`() = runTest {
        val rig = Rig()
        val controller = rig.controller(backgroundScope)
        controller.start()

        controller.release()

        assertEquals(MpVoiceInputState.Idle, controller.state.value)
        assertEquals("Eliminar archivos temporales al salir de la página", 1, rig.capture.cancelCount)
        assertEquals(0, rig.transcribeCount)
    }

    /** La escena de un caso de uso.:Grabadora falsa + devolución de llamada grabada. */
    private class Rig(
        val capture: FakeCapture = FakeCapture(),
        private val canRecord: Boolean = true,
    ) {

        val transcripts = mutableListOf<String>()
        var emptyCount: Int = 0
        var startFailedCount: Int = 0
        var transcribeCount: Int = 0
        var lastWav: ByteArray? = null

        /** Entrada de transcripción;Los casos de uso se pueden cambiar a versiones pendientes.,Se utiliza para observar el estado de \"traducir\". */
        var onTranscribe: suspend (ByteArray) -> String? = { "Hola" }

        fun controller(scope: CoroutineScope): MpVoiceInputController = MpVoiceInputController(
            recorder = capture,
            transcribe = { wav ->
                transcribeCount += 1
                lastWav = wav
                onTranscribe(wav)
            },
            onTranscript = { text -> transcripts += text },
            onEmptyTranscript = { emptyCount += 1 },
            onStartFailed = { startFailedCount += 1 },
            canRecord = { canRecord },
            scope = scope,
        )
    }

    private class FakeCapture(
        private val startResult: Boolean = true,
        val wav: ByteArray? = ByteArray(8),
    ) : MpVoiceCapture {

        var startCount: Int = 0
            private set
        var stopCount: Int = 0
            private set
        var cancelCount: Int = 0
            private set

        override fun start(): Boolean {
            startCount += 1
            return startResult
        }

        override suspend fun stop(): ByteArray? {
            stopCount += 1
            return wav
        }

        override fun cancel() {
            cancelCount += 1
        }
    }
}
