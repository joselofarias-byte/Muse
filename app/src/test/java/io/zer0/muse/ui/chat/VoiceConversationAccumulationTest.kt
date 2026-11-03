package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.zer0.muse.R
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contratos de acumulación de texto para conversaciones de voz entre sesiones ASR.
 *
 * Escollos de la regresión:ASR Finalizado por razones ajenas al usuario(DashScope Final silencioso en el lado del servidor.Realtime desconectado/Error)tiempo,
 * La implementación anterior descartó todo el texto reconocido.,Si continúas hablando durante treinta segundos, es posible que solo te quede la última frase. —— Cuando el usuario hace clic en "Finalizar",
 * Lo dicho debe presentarse en su totalidad.
 */
class VoiceConversationAccumulationTest {

    private companion object {
        const val RETRY_HINT = "No te escuché claramente. Dilo de nuevo."
    }

    private lateinit var scope: CoroutineScope
    private lateinit var voiceState: ChatVoiceState
    private lateinit var stateStore: ChatStateStore
    private lateinit var audioCoordinator: ChatAudioCoordinator
    private lateinit var controller: ChatMediaController

    private val sent = mutableListOf<String>()
    private val inputUpdates = mutableListOf<String>()

    /** Devolución de llamada registrada cada vez que iniciaVoiceConversationListening,Se utiliza para simular resultados de reconocimiento ASR. */
    private val transcriptCallbacks = mutableListOf<(String) -> Unit>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        voiceState = ChatVoiceState()
        stateStore = ChatStateStore()

        val snapshot = mockk<ChatUiState>(relaxed = true)
        every { snapshot.isStreaming } returns false
        every { snapshot.isSpeaking } returns false
        val accessor = mockk<ChatStateAccessor>(relaxed = true)
        every { accessor.snapshot } returns snapshot
        every { accessor.coroutineScope } returns scope

        val ttsManager = mockk<TtsManager>(relaxed = true)
        every { ttsManager.speak(any(), any(), any()) } returns true
        every { ttsManager.isSpeaking() } returns false

        audioCoordinator = mockk(relaxed = true)
        every { audioCoordinator.shouldUseApiRecording() } returns true
        every { audioCoordinator.startVoiceConversationListening(capture(transcriptCallbacks)) } just runs

        val appContext = mockk<Context>(relaxed = true)
        every { appContext.getString(R.string.voice_conversation_retry_hint) } returns RETRY_HINT

        controller = ChatMediaController(
            accessor = accessor,
            artifactRepository = mockk<ArtifactRepository>(relaxed = true),
            voiceState = voiceState,
            stateStore = stateStore,
            audioCoordinator = audioCoordinator,
            ttsManager = ttsManager,
            appContext = appContext,
            onError = { _, _, _ -> },
            onSend = { sent.add(inputUpdates.lastOrNull().orEmpty()) },
            onStop = { },
            onUpdateInput = { inputUpdates.add(it) },
            settings = mockk<SettingsRepository>(relaxed = true),
            speakingWatchdogTimeoutMs = { 60_000L },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun uiState(status: ASRStatus, transcript: String = ""): ChatUiState {
        val state = mockk<ChatUiState>(relaxed = true)
        every { state.asrState } returns ASRState(status = status, transcript = transcript)
        every { state.isStreaming } returns false
        every { state.isSpeaking } returns false
        return state
    }

    /** Simular una devolución de llamada ASR(dentro de la misma sesión de grabación). */
    private fun asrCallback(text: String) {
        transcriptCallbacks.last().invoke(text)
    }

    /** El ASR simulado termina solo(El servidor finaliza en silencio/desconectado). */
    private fun asrEndsByItself(finalText: String) {
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = finalText)
    }

    @Test
    fun `speech survives an asr session that ends on its own`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("El clima de hoy")

        // DashScope Final silencioso del lado del servidor:El usuario no hizo clic para finalizar.
        asrEndsByItself("El clima de hoy")

        assertEquals("El clima de hoy", voiceState.transcript.value)
        assertTrue("No se enviarán mensajes hasta que el final sea por motivos ajenos al usuario.", sent.isEmpty())
        assertNull("No solicite \"Dilo de nuevo\" cuando haya texto acumulado", voiceState.hint.value)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        // La grabación se ha reiniciado.,seguir acumulando
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `reopened session accumulates into the same round`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("El clima de hoy")
        asrEndsByItself("El clima de hoy")

        // La conversación reabierta reenviará el texto completo que contiene el contenido antiguo.:Deduplicación de prefijo,Sin empalmes repetidos
        asrCallback("El clima de hoy esta agradable")
        assertEquals("El clima de hoy esta agradable", voiceState.transcript.value)

        // Continuar en la misma conversación.:Solo ponlo junto
        asrCallback("y manana")
        assertEquals("El clima de hoy esta agradable y manana", voiceState.transcript.value)
        assertTrue("Nadie ha terminado todavía", sent.isEmpty())
    }

    @Test
    fun `finish submits the whole accumulated text once and clears it`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("El clima de hoy")
        asrEndsByItself("El clima de hoy")
        asrCallback("El clima de hoy esta agradable")
        asrCallback("y manana")

        // ASR está escuchando después de reiniciar la grabación
        stateStore.state.value = uiState(ASRStatus.Listening)
        controller.finishVoiceConversationListening()
        verify { audioCoordinator.stopVoiceConversationListening() }
        // El estado de cierre sólo lleva el último párrafo de esta sesión.,El texto enviado debe acumularse a lo largo de la ronda.
        asrEndsByItself("y manana")

        assertEquals(listOf("El clima de hoy esta agradable y manana"), sent)
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)
        assertEquals("La acumulación se borra después del envío.", "", voiceState.transcript.value)
    }

    @Test
    fun `asr error keeps the accumulated text without reopening the microphone`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("El clima de hoy")

        stateStore.state.value = uiState(ASRStatus.Error, transcript = "El clima de hoy")

        assertEquals("El clima de hoy", voiceState.transcript.value)
        assertTrue(sent.isEmpty())
        assertNull("No solicite \"Dilo de nuevo\" cuando haya texto acumulado", voiceState.hint.value)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        // Error no se vuelve a abrir automáticamente después(prevenir start → Error bucle infinito),El usuario hace clic en el botón para continuar.
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `empty asr ending still asks the user to repeat`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)

        // Terminó solo sin reconocer una sola palabra.:Nada que guardar,Preguntar para repetir
        asrEndsByItself("")

        assertEquals("", voiceState.transcript.value)
        assertEquals(RETRY_HINT, voiceState.hint.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `cancel clears the accumulated text without sending`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("El clima de hoy")
        asrEndsByItself("El clima de hoy")
        asrCallback("El clima de hoy esta agradable")
        assertEquals("El clima de hoy esta agradable", voiceState.transcript.value)

        controller.interruptVoiceConversation()

        assertEquals(VoiceConversationState.IDLE, voiceState.state.value)
        assertEquals("Acumulación liquidada después de la cancelación", "", voiceState.transcript.value)
        assertTrue("Cancelar sin enviar", sent.isEmpty())
        verify { audioCoordinator.cancelVoiceConversationListening() }
    }

    @Test
    fun `exit clears the accumulated text`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        asrCallback("El clima de hoy")

        controller.stopVoiceConversation()

        assertEquals("", voiceState.transcript.value)
        assertTrue(sent.isEmpty())
    }
}
