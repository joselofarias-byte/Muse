package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.channels.Channel
import kotlin.uuid.Uuid

/**
 * v5: Solicitud de cola de envío de mensajes — Serialización,Evite que el envío rápido y continuo provoque condiciones de carrera.
 *
 * Reversión de actualización optimista:enqueueSend Mensaje de usuario/asistente creado id;P0 Reparación y transporte completo. userMessage
 * (Contiene original id + createdAt),El lado del consumidor reutiliza directamente el disco.,Evite que el consumidor renueve el mensaje UIMessage causado por
 * createdAt Obtener tiempo de consumo(más tarde que assistantMsg.createdAt),La clasificación se desordena después de recargar el corte de página.
 */
internal data class SendRequest(
    val text: String,
    val images: List<String>,
    val sessionId: String,
    val retryCount: Int = 0,
    val userMessage: UIMessage,
    val assistantMessageId: Uuid,
    // v1.0.15: outbox grabar id(Cola de envío persistente,Se utiliza para recuperarse después de que se finaliza el proceso.)
    val outboxId: String,
    /** Resultados de persistencia antes de unirse a la cola; Las nuevas solicitudes de envío deben llegar primero a la bandeja de salida antes de que se permita al consumidor comenzar la generación. */
    val outboxReady: kotlinx.coroutines.Deferred<Boolean>? = null,
    /** Enrutamiento automático de tareas para la solicitud actual;No escribir sobrescritura del modelo permanente de sesión,Evite que los resultados del enrutamiento se ciñan a mensajes posteriores. */
    val taskRouteSelection: io.zer0.muse.data.SettingsRepository.TaskRouteSelection? = null,
)

/**
 * v1.x: generar/Un contenedor de estado mutable compartido para la canalización de envío.
 *
 * oso sendChannel(Enviar cola),outbox Restauración de conjuntos deduplicados y números de serie intergeneracionales/Token, registro de medios de herramientas,
 * Agent Crear indicador de reingreso a la sesión.ChatViewModel Delegar en este contenedor a través de getter/setter,Mantén lo que tienes
 * Referencia sin cambios,Allanando el camino para que todo el bucle de envío/lanzamiento/consumo se traslade a ChatGenerationController.
 */
internal class ChatGenerationState {
    // La capacidad limitada es 8,Evite la acumulación ilimitada de solicitudes que contengan imágenes base64. OOM
    val sendChannel = Channel<SendRequest>(capacity = 8)
    val outboxRecoveryQueuedIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // exec* ejecución de herramientas(La generación de imágenes puede tardar decenas de segundos)Durante este período, si el usuario envía un nuevo mensaje/Cambiar de sesión,Los resultados de las herramientas antiguas se escribirán en
    // mensaje de error(Contaminación de medios entre sesiones);exec* La entrada captura el token actual.,Verifique que el token no haya cambiado antes de escribir a los medios.
    // L-3: incremento+La lectura requiere atomicidad. Anteriormente no atómico var(Sólo @Volatile garantiza visibilidad),Una vez reestructurado en el futuro
    // La introducción de incrementos concurrentes perderá actualizaciones;Utilice AtomicLong en su lugar para aclarar la semántica de compareAndIncrement.
    private val toolGenerationTokenCounter = java.util.concurrent.atomic.AtomicLong(0L)

    var toolGenerationToken: Long
        get() = toolGenerationTokenCounter.get()
        set(value) {
            toolGenerationTokenCounter.set(value)
        }

    /** Incremento atómico y devolución de nuevo token.,suministro exec* Se utiliza para el cambio intergeneracional. */
    fun nextToolGenerationToken(): Long = toolGenerationTokenCounter.incrementAndGet()

    // B-24: Número de secuencia de generación de streaming — Cada vez que se incrementa y escribe launchStream StreamRunState.generationSerial,
    // Refinamiento/Verifique la ruta del error antes de borrar isStreaming"Sigue siendo la última generación.".
    @Volatile
    var streamGenerationSerial: Long = 0L

    // Los valores predeterminados globales y las anulaciones de sesión se guardan por separado; El cambio de página de chat solo cambia la superposición de la sesión actual y no sobrescribe la configuración global.
    @Volatile
    var globalSelectedModelId: String? = null

    @Volatile
    var globalActiveProviderId: String? = null

    @Volatile
    var sessionModelOverrides: Map<String, String> = emptyMap()

    @Volatile
    var sessionProviderOverrides: Map<String, String> = emptyMap()

    // Mensaje de la rueda de herramientas para los medios adjuntos en esta generación id(Seguridad de concurrencia),runToolLoop Al final, se coloca el fondo del bolsillo sobre la placa.
    val toolMediaMessages: MutableSet<Uuid> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // v1.135: Mensaje del asistente correspondiente a la ronda de llamada de herramienta actual id,suministro exec* La herramienta multimedia actualiza los campos multimedia del mensaje.
    @Volatile
    var toolAssistantId: Uuid? = null

    // Reparación de auditoría (S-01): toolAssistantId Sesión de generación correspondiente id,Mismo ciclo de vida que toolAssistantId.
    @Volatile
    var activeToolSessionId: String? = null

    // v1.79 (M-CV8): Impedir el reingreso a la sesión de creación del modo agente
    @Volatile
    var isCreatingAgentSession: Boolean = false
}
