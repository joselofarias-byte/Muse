package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
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
 * Un contrato de "punto final totalmente manual" para conversaciones de voz.
 *
 * Escollos de la regresión:ASR la sesión termina(Cierre silencioso del lado del servidor, reconocimiento de errores e informes de errores)Me trataron como a un usuario y terminé.,
 * Envía media frase directamente al modelo.,Y el micrófono sigue captando durante la transmisión TTS.,Reconozca su propia voz de lectura como el discurso del usuario.
 *
 * nuevo contrato:
 *  - Sólo el usuario hace clic en "Finalizar"([ChatMediaController.finishVoiceConversationListening])Enviar
 *  - ASR Termina solo → Ningún mensaje,Vuelva a ESCUCHAR para escuchar de nuevo;El texto reconocido permanece en el texto acumulado y continúa acumulándose.,
 *    Por lo tanto, el mensaje luminoso "Decir de nuevo" solo aparece cuando el texto acumulado aún está vacío.
 *  - THINKING / SPEAKING El micrófono permanece apagado durante,Vuelva a abrir solo cuando regrese a ESCUCHAR
 */
class VoiceConversationManualCommitTest {

    private companion object {
        const val RETRY_HINT = "No te escuché claramente. Dilo de nuevo."
    }

    private lateinit var scope: CoroutineScope
    private lateinit var voiceState: ChatVoiceState
    private lateinit var stateStore: ChatStateStore
    private lateinit var ttsManager: TtsManager
    private lateinit var audioCoordinator: ChatAudioCoordinator
    private lateinit var controller: ChatMediaController

    private val sent = mutableListOf<String>()
    private val inputUpdates = mutableListOf<String>()

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

        ttsManager = mockk(relaxed = true)
        every { ttsManager.speak(any(), any(), any()) } returns true
        every { ttsManager.isSpeaking() } returns false

        audioCoordinator = mockk(relaxed = true)
        every { audioCoordinator.shouldUseApiRecording() } returns true

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
            // El organismo de control no utiliza la afirmación de esta prueba.,Dar un tiempo de espera lo suficientemente largo para evitar interferencias.
            speakingWatchdogTimeoutMs = { 60_000L },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun uiState(
        status: ASRStatus,
        transcript: String = "",
        isStreaming: Boolean = false,
        isSpeaking: Boolean = false,
    ): ChatUiState {
        val state = mockk<ChatUiState>(relaxed = true)
        every { state.asrState } returns ASRState(status = status, transcript = transcript)
        every { state.isStreaming } returns isStreaming
        every { state.isSpeaking } returns isSpeaking
        return state
    }

    @Test
    fun `asr ending on its own keeps accumulated text and restarts without hint`() {
        controller.startVoiceConversation()
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)

        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "Compruébalo por mí"
        // Final silencioso del lado del servidor:ASR regresar solo Idle,El usuario no hizo clic en finalizar.
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "Compruébalo por mí")

        assertTrue("ASR No envíe mensajes al modelo si termina solo.", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals("Las palabras dichas deben permanecer", "Compruébalo por mí", voiceState.transcript.value)
        assertNull("No solicitar repetir cuando hay texto acumulado", voiceState.hint.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `asr error returns to listening without sending and keeps accumulated text`() {
        controller.startVoiceConversation()

        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "Hola"
        stateStore.state.value = uiState(ASRStatus.Error, transcript = "Hola")

        assertTrue("ASR Al informar un error, no debe enviar un mensaje al modelo.", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals("Hola", voiceState.transcript.value)
        assertNull("No solicitar repetir cuando hay texto acumulado", voiceState.hint.value)
        // Error No se vuelve a abrir automáticamente cuando está en estado,evitar start → Error bucle infinito
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `explicit finish submits the whole transcript exactly once`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)

        // Terminar sin hacer clic:No importa cómo cambie el texto de reconocimiento, no se envía.
        voiceState.transcript.value = "ayudame"
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "Ayúdame a comprobar el tiempo en Beijing mañana."
        stateStore.state.value = uiState(ASRStatus.Listening)
        assertTrue("No enviar hasta que el usuario diga que ha terminado", sent.isEmpty())

        controller.finishVoiceConversationListening()
        verify { audioCoordinator.stopVoiceConversationListening() }

        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "Ayúdame a comprobar el tiempo en Beijing mañana.")

        assertEquals(listOf("Ayúdame a comprobar el tiempo en Beijing mañana."), sent)
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)
        assertNull("Borrar el mensaje de luz después del envío", voiceState.hint.value)
        assertEquals("Borrar texto acumulado después del envío", "", voiceState.transcript.value)
        // THINKING No vuelva a encender el micrófono durante este período.
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `empty transcript commit returns to listening without sending`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)

        controller.finishVoiceConversationListening()
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "")

        assertTrue("No se permite texto vacío para enviar mensajes.", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals(RETRY_HINT, voiceState.hint.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `commit followed by asr error still does not send`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "ayúdame a ver"

        controller.finishVoiceConversationListening()
        // Identificar errores durante el proceso de cierre.:Este texto no es creíble.,No se puede enviar al modelo
        stateStore.state.value = uiState(ASRStatus.Error, transcript = "ayúdame a ver")

        assertTrue("Error No enviar mensajes al final.", sent.isEmpty())
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        assertEquals("Retención acumulativa de texto,El usuario puede hacer clic una vez más para finalizar el", "ayúdame a ver", voiceState.transcript.value)
        assertNull("No solicitar repetir cuando hay texto acumulado", voiceState.hint.value)
    }

    @Test
    fun `microphone stays closed while speaking and reopens only on listening`() {
        controller.startVoiceConversation()
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "¿Está ahí?"
        controller.finishVoiceConversationListening()
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "¿Está ahí?")
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)

        // THINKING → SPEAKING:El micrófono debe estar apagado antes de transmitir.,y no volverá a abrir
        stateStore.messages.value = listOf(UIMessage(role = MessageRole.ASSISTANT, content = "En."))
        stateStore.state.value = uiState(ASRStatus.Idle, isStreaming = true)
        stateStore.state.value = uiState(ASRStatus.Idle, isStreaming = false)
        assertEquals(VoiceConversationState.SPEAKING, voiceState.state.value)
        // detenerse en el camino de envío + al entrar HABLAR stop,puede golpear;La clave es que en este momento no hay start
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }

        // El estado cambió varias veces más durante la transmisión.,Aún no vuelves a encender el micrófono
        stateStore.state.value = uiState(ASRStatus.Idle, isSpeaking = true)
        stateStore.state.value = uiState(ASRStatus.Idle, isSpeaking = true)
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }

        // Fin de la lectura → LISTENING,Vuelve a abrirlo ahora
        stateStore.state.value = uiState(ASRStatus.Idle, isSpeaking = false)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
    }

    @Test
    fun `main button toggles between start recording and finish`() {
        controller.startVoiceConversation()
        verify(exactly = 1) { audioCoordinator.startVoiceConversationListening(any()) }

        // LISTENING Pero ASR no está grabando.:Haga clic = Empezar a grabar de nuevo,no enviar
        stateStore.state.value = uiState(ASRStatus.Idle)
        controller.toggleVoiceConversation()
        verify(exactly = 2) { audioCoordinator.startVoiceConversationListening(any()) }
        assertTrue(sent.isEmpty())

        // LISTENING Y grabando:Haga clic = Finalizar y enviar
        stateStore.state.value = uiState(ASRStatus.Listening)
        voiceState.transcript.value = "hola"
        controller.toggleVoiceConversation()
        verify { audioCoordinator.stopVoiceConversationListening() }
        stateStore.state.value = uiState(ASRStatus.Idle, transcript = "hola")
        assertEquals(listOf("hola"), sent)
        assertEquals(VoiceConversationState.THINKING, voiceState.state.value)

        // THINKING Haga clic a continuación = interrumpir(Mantener la semántica existente)
        controller.toggleVoiceConversation()
        assertEquals(VoiceConversationState.IDLE, voiceState.state.value)
        verify { audioCoordinator.cancelVoiceConversationListening() }
    }
}
