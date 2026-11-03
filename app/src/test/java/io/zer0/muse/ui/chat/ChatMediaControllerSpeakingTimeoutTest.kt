package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-33: El estado de voz HABLANDO expira.
 *
 * Escenario de defecto:`ttsManager.speak()` Regresar true(El motor acepta la solicitud.),Pero la devolución de llamada de estado TTS nunca llega
 * (Devoluciones de llamada perdidas/StateFlow fusionadas),`state.isSpeaking` nunca para true,Paso 3 de
 * SPEAKING → LISTENING La migración nunca desencadena,La máquina de estados está permanentemente atascada en HABLAR.
 *
 * Después de la reparación:Entra HABLAR, es decir, perros guardianes armados.(Tiempo de espera adaptable según la longitud del texto,Tiempo de espera corto de inyección de prueba),
 * No se recibió ninguna devolución de llamada de estado TTS después del tiempo de espera → Vuelva a ESCUCHAR y registre el motivo.
 */
class ChatMediaControllerSpeakingTimeoutTest {

    private lateinit var voiceState: ChatVoiceState
    private lateinit var stateStore: ChatStateStore
    private lateinit var ttsManager: TtsManager
    private lateinit var audioCoordinator: ChatAudioCoordinator
    private lateinit var scope: CoroutineScope

    /** Construir para inyectar tiempo de espera de vigilancia(60ms)de controller;La ruta alternativa es coherente con el código de producción. */
    private fun controller(timeoutMs: Long = 60L): ChatMediaController {
        val uiState = mockk<ChatUiState>(relaxed = true)
        every { uiState.asrState } returns ASRState()
        every { uiState.isStreaming } returns false
        every { uiState.isSpeaking } returns false
        val accessor = mockk<ChatStateAccessor>(relaxed = true)
        every { accessor.snapshot } returns uiState
        every { accessor.coroutineScope } returns scope
        return ChatMediaController(
            accessor = accessor,
            artifactRepository = mockk<ArtifactRepository>(relaxed = true),
            voiceState = voiceState,
            stateStore = stateStore,
            audioCoordinator = audioCoordinator,
            ttsManager = ttsManager,
            appContext = mockk<Context>(relaxed = true),
            onError = { _, _, _ -> },
            onSend = { },
            onStop = { },
            onUpdateInput = { },
            settings = mockk<SettingsRepository>(relaxed = true),
            speakingWatchdogTimeoutMs = { timeoutMs },
        )
    }

    private fun uiState(isRecording: Boolean, isStreaming: Boolean, isSpeaking: Boolean = false): ChatUiState {
        val state = mockk<ChatUiState>(relaxed = true)
        every { state.asrState } returns ASRState(status = if (isRecording) ASRStatus.Listening else ASRStatus.Idle)
        every { state.isStreaming } returns isStreaming
        every { state.isSpeaking } returns isSpeaking
        return state
    }

    private suspend fun awaitState(expected: VoiceConversationState, timeoutMs: Long = 3_000) {
        withTimeout(timeoutMs) {
            while (voiceState.state.value != expected) delay(5)
        }
    }

    @Test
    fun `speaking without any tts callback falls back on watchdog timeout`() = runBlocking {
        val logs = mutableListOf<String>()
        val previousSink = Logger.sink
        Logger.sink = { _, tag, msg, _ -> synchronized(logs) { logs.add("$tag: $msg") } }
        try {
            scope = CoroutineScope(Dispatchers.Unconfined)
            voiceState = ChatVoiceState()
            stateStore = ChatStateStore()
            ttsManager = mockk(relaxed = true)
            every { ttsManager.speak(any(), any(), any()) } returns true
            // condición de defecto crítico:El motor no informa ningún estado de isSpeaking(devolución de llamada perdida)
            every { ttsManager.isSpeaking() } returns false
            audioCoordinator = mockk(relaxed = true)
            every { audioCoordinator.shouldUseApiRecording() } returns true

            val controller = controller(timeoutMs = 60L)
            controller.startVoiceConversation()
            awaitState(VoiceConversationState.LISTENING)

            // 1) Después de que el usuario hace clic en "Finalizar", el ASR finaliza con el texto de reconocimiento. → THINKING enviar
            //    cambio de comportamiento:isRecording true→false Ya no activa una confirmación por sí solo.,Primero debes finalizar explícitamente esta sección de entrada.
            stateStore.state.value = uiState(isRecording = true, isStreaming = false)
            voiceState.transcript.value = "Hola"
            controller.finishVoiceConversationListening()
            stateStore.state.value = uiState(isRecording = false, isStreaming = false)
            awaitState(VoiceConversationState.THINKING)

            // 2) Respuesta de transmisión completada → SPEAKING(speak() éxito,Pero no hay devolución de llamada de estado TTS)
            val reply = UIMessage(role = MessageRole.ASSISTANT, content = "OK,Estoy aquí.")
            stateStore.messages.value = listOf(reply)
            stateStore.state.value = uiState(isRecording = false, isStreaming = true)
            stateStore.state.value = uiState(isRecording = false, isStreaming = false)
            awaitState(VoiceConversationState.SPEAKING)

            // 3) tiempo de espera de vigilancia → retroceder LISTENING(en lugar de quedarse permanentemente SPEAKING)
            awaitState(VoiceConversationState.LISTENING)
            assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
            assertTrue("Debe borrarse al retroceder. aiReply", voiceState.aiReply.value.isEmpty())
            assertTrue(
                "Se debe registrar el motivo de la baja.,registro real=$logs",
                logs.any { it.contains("SPEAKING disparador de vigilancia") && it.contains("No se recibió ninguna devolución de llamada de estado TTS") },
            )
        } finally {
            Logger.sink = previousSink
            scope.cancel()
        }
    }

    @Test
    fun `tts callback before timeout keeps speaking state and no fallback`() = runBlocking {
        scope = CoroutineScope(Dispatchers.Unconfined)
        voiceState = ChatVoiceState()
        stateStore = ChatStateStore()
        ttsManager = mockk(relaxed = true)
        every { ttsManager.speak(any(), any(), any()) } returns true
        every { ttsManager.isSpeaking() } returns false
        audioCoordinator = mockk(relaxed = true)
        every { audioCoordinator.shouldUseApiRecording() } returns true

        val controller = controller(timeoutMs = 400L)
        controller.startVoiceConversation()
        awaitState(VoiceConversationState.LISTENING)

        // 1) El usuario hace clic en "Finalizar"→ ASR Refinamiento → THINKING(cambio de comportamiento:El final de la grabación en sí ya no es una señal de confirmación.)
        stateStore.state.value = uiState(isRecording = true, isStreaming = false)
        voiceState.transcript.value = "¿Está ahí?"
        controller.finishVoiceConversationListening()
        stateStore.state.value = uiState(isRecording = false, isStreaming = false)
        awaitState(VoiceConversationState.THINKING)

        stateStore.messages.value = listOf(UIMessage(role = MessageRole.ASSISTANT, content = "En."))
        stateStore.state.value = uiState(isRecording = false, isStreaming = true)
        stateStore.state.value = uiState(isRecording = false, isStreaming = false)
        awaitState(VoiceConversationState.SPEAKING)
        assertEquals("SPEAKING Mostrar el texto a leer durante el periodo.", "En.", voiceState.aiReply.value)

        // TTS La devolución de llamada de estado llega normalmente y finaliza normalmente. → Sigue el camino original SPEAKING → LISTENING Migrar,perro guardián cancelado
        stateStore.state.value = uiState(isRecording = false, isStreaming = false, isSpeaking = true)
        stateStore.state.value = uiState(isRecording = false, isStreaming = false, isSpeaking = false)
        awaitState(VoiceConversationState.LISTENING)
        assertTrue("Después de la finalización normal, se borrará de acuerdo con la semántica original. aiReply", voiceState.aiReply.value.isEmpty())

        // Se superó el tiempo de espera del mecanismo de vigilancia inyectado:Fin normal y el watchdog ha sido cancelado.,Ya no debería haber deriva estatal
        delay(2 * 400L)
        assertEquals(VoiceConversationState.LISTENING, voiceState.state.value)
        scope.cancel()
    }
}
