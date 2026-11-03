package io.zer0.muse.ui.groupchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado de actividad de los miembros del chat grupal(modelo de cinco estados).
 *
 * Se utiliza para que la interfaz de usuario muestre la etapa actual de cada agente en la rotación del chat grupal en tiempo real.,Permitir que los usuarios perciban"¿Quién está leyendo las noticias?/quien esta respondiendo/quien saltó".
 * El estado consiste en [GroupChatActivityHub] Gestión unificada,GroupChatScheduler En cada etapa de rotación upsert,
 * UI Pase [GroupChatActivityHub.activities] StateFlow Suscripción responsiva.
 */
enum class AgentActivityStatus {
    /** gratis(Predeterminado/Estado de reserva final,UI no mostrar). */
    IDLE,

    /** leyendo las noticias(invokeAgent empezar,Aún no transmitido). */
    VIEWING,

    /** Respondiendo(LLM Salida de transmisión). */
    REPLYING,

    /** Elige no responder(Regresar [PASS_MARKER] marca,Ver detalles GroupChatScheduler). */
    NO_REPLY,

    /** error(LLM llamada fallida/tiempo de espera). */
    ERROR,
}

/**
 * Registro del estado de actividad de un único Agente.
 *
 * @property assistantId asistente id
 * @property assistantName Nombre para mostrar del asistente(UI chip mostrar)
 * @property status Estado de actividad actual
 * @property updatedAt Marca de tiempo de actualización de estado(UI Se puede utilizar para determinar el vencimiento.)
 */
data class AgentActivity(
    val assistantId: String,
    val assistantName: String,
    val status: AgentActivityStatus,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Administrador de estado de actividad de chat grupal.
 *
 * capa de transmisión de memoria — GroupChatScheduler Estado de actualización en cada etapa de rotación.,
 * UI Pase [activities] StateFlow Pantalla de suscripción responsiva.
 *
 * Realizar compensaciones:
 *  - Administrar solo dimensiones de chat grupal(groupChatId → List<AgentActivity>),No participar en sesiones de chat individuales.
 *  - Sin respaldo de persistencia(Se borra cuando se reinicia el proceso.,El historial se conserva en la tabla de mensajes.)
 *  - Las máquinas de estado se mantienen simples:Solo enumeración de cinco estados,No contiene campos de flujo de trabajo(kind/summary/parentTaskId espera)
 *
 * Seguridad del hilo:[MutableStateFlow] La escritura de valores es atómica.,updateStatus Se puede llamar desde cualquier hilo.
 */
class GroupChatActivityHub {

    private val _activities = MutableStateFlow<Map<String, List<AgentActivity>>>(emptyMap())
    val activities: StateFlow<Map<String, List<AgentActivity>>> = _activities.asStateFlow()

    /**
     * Actualice el estado de actividad del Agente especificado en el chat grupal especificado.
     *
     * Se sobrescribirán los estados antiguos del mismo agente.;Si el agente no está en la lista, se adjunta.
     *
     * @param groupChatId chat grupal id
     * @param assistantId asistente id
     * @param assistantName Nombre para mostrar del asistente
     * @param status nuevo estado
     */
    fun updateStatus(groupChatId: String, assistantId: String, assistantName: String, status: AgentActivityStatus) {
        // Reparación de auditoría (3.5): Utilice MutableStateFlow.update para completar la lectura de forma atómica-cambiar-escribir,
        // Evite llamadas simultáneas basadas en instantáneas antiguas toMutableMap() Cubrirse unos a otros después de la operación.
        _activities.update { current ->
            val list = current[groupChatId]?.toMutableList() ?: mutableListOf()
            val idx = list.indexOfFirst { it.assistantId == assistantId }
            val activity = AgentActivity(assistantId, assistantName, status)
            if (idx >= 0) list[idx] = activity else list.add(activity)
            current + (groupChatId to list)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // v1.x: Transmisión de contenido de salida de transmisión de chat grupal(Empujado en tiempo real durante la generación. UI,Vaciar después del almacenamiento)
    // ══════════════════════════════════════════════════════════════════

    private val _streamingContent = MutableStateFlow<Map<String, String>>(emptyMap())

    /** chatId → Contenido de respuesta del asistente generado por streaming(Un mapa vacío significa que no hay salida de streaming). */
    val streamingContent: StateFlow<Map<String, String>> = _streamingContent.asStateFlow()

    /** Actualiza la salida de transmisión de un chat grupal. */
    fun updateStreamingContent(chatId: String, content: String) {
        _streamingContent.update { it + (chatId to content) }
    }

    /** Finaliza la salida de transmisión del chat grupal(Llamado después de abandonar la biblioteca.,Borrar contenido temporal de la interfaz de usuario). */
    fun clearStreamingContent(chatId: String) {
        _streamingContent.update { it - chatId }
    }

    /**
     * Obtenga la lista de actividades del chat grupal especificado.
     *
     * Recomendar UI para suscribirse directamente [activities] En lugar de llamar a este método(La capacidad de respuesta es mejor que la consulta).
     */
    fun getActivities(groupChatId: String): List<AgentActivity> {
        return _activities.value[groupChatId] ?: emptyList()
    }

    /**
     * Borrar todo el estado de actividad del chat grupal especificado(Eliminar chat grupal/Llamado al salir).
     */
    fun clear(groupChatId: String) {
        // Reparación de auditoría (3.5): update eliminación atómica,Evite perder actualizaciones cuando coincida con updateStatus.
        _activities.update { it - groupChatId }
    }
}
