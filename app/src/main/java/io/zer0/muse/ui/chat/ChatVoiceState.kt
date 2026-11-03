package io.zer0.muse.ui.chat

import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.x: Modo de conversación de voz(Grabación → identificar → pensar → bucle de transmisión)contenedor de estado compartido.
 *
 * Estado del rodamiento/Transcribir/AI Responda tres referencias de rutinas de observación de bucles y StateFlow.ChatViewModel Pase
 * getter/setter Delegar en este contenedor,Mantén lo que tienes `_voiceConversationState.xxx` Espere a que la referencia permanezca sin cambios.,
 * para el mango startVoiceConversation/stopVoiceConversation Esperando a que todo el clúster se traslade a ChatMediaController para allanar el camino.
 */
internal class ChatVoiceState {
    val state = MutableStateFlow(VoiceConversationState.IDLE)

    /**
     * Texto de reconocimiento acumulado en esta ronda(esta ronda = Desde que el usuario comienza a grabar hasta que hace clic en "Finalizar").
     *
     * ASR La sesión puede finalizar debido al silencio del servidor./desconectado y terminado a mitad de camino,El texto reconocido antes del final no se puede perder:Incorporado en cada devolución de llamada
     * aquí,La acumulación continuará después de reiniciar la grabación.,La sección completa se envía cuando el usuario hace clic en "Finalizar".UI También muestra este texto directamente.
     */
    val transcript = MutableStateFlow("")

    val aiReply = MutableStateFlow("")

    /** recordatorio de luz(Como \"no escuché claramente,Dilo de nuevo\"): Sólo en ESCUCHA y no se muestra durante la grabación. Borrado después de la grabación. */
    val hint = MutableStateFlow<String?>(null)

    @Volatile
    var job: Job? = null

    /** Incorporar un texto de devolución de llamada ASR(Segmento completo o incremento,prensa [mergeTranscript] Eliminar duplicados). */
    fun accumulateTranscript(incoming: String) {
        transcript.value = mergeTranscript(transcript.value, incoming)
    }

    /**
     * Texto de envío al hacer clic en "Finalizar":La acumulación de esta ronda + el texto final llevado en el estado final de ASR(Deduplicación de partes superpuestas).
     *
     * El texto de cierre puede duplicar el contenido que ya se ha incorporado.(Las devoluciones de llamada y las actualizaciones de estado son dos canales reconocidos al mismo tiempo.),Se garantiza que las reglas de fusión serán idempotentes.
     */
    fun commitText(asrSessionFinal: String): String = mergeTranscript(transcript.value, asrSessionFinal)

    /** Limpia la acumulación de esta ronda.:Se llama cuando el envío es exitoso, el usuario cancela o sale de la conversación de voz. */
    fun clearTranscript() {
        transcript.value = ""
    }
}
