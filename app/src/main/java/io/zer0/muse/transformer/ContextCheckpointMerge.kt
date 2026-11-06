package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.summary.ContextCheckpointEntity

/**
 * Fusionar el punto de control de la sesión en el historial para enviarlo —— "contexto comprimido"El verdadero punto de efecto.
 *
 * ## Semántica
 *
 * El puesto de control es"Límites fusionados en resumen". Lo que se le da al modelo después de la fusión es:
 * **Resumen del punto de control + mensaje después del límite**;Los mensajes de límites y anteriores ya no entrarán en esta solicitud.
 *
 * Este paso sólo se aplica a**Historia de los modelos emitidos.**, la sesión en sí no se modifica: los mensajes antiguos todavía están en la biblioteca y en la interfaz.
 * Simplemente ya no ocupa el contexto del modelo (consulte el separador de contexto para ver la presentación en la interfaz).
 *
 * ## Cómo localizar límites
 *
 * usar [ContextCheckpointEntity.lastCoveredMessageId] Localizar mensajes de límites en el historial:
 *  - encontró → Esta posición y su mensaje anterior (en el lado del asistente) son reemplazados por el resumen; Los mensajes de usuario y los prefijos del sistema se conservan.
 *    evitar"Lo que dijeron los usuarios"Todo el párrafo desaparece del contexto;
 *  - No encontrado pero el historial actual es solo una ventana de paginación → uso [ContextCheckpointEntity.coveredSeq] Determinar si la ventana
 *    Ya detrás del límite; Sea conservador y no fusione cuando no se pueda probar (es mejor traer más historial que borrarlo por error).
 *
 * Coloque el mensaje del SISTEMA primero en el resumen y déjelo claro en el texto."Este es un resultado condensado de una conversación anterior.", para evitar que el modelo lo tome
 * Trátelo como una entrada de usuario recién recibida.
 */
object ContextCheckpointMerge {

    /** Marcado visible para mensajes de resumen (la base para identificar separadores de contexto, consistentes con las marcas de compresión existentes). */
    const val MARKER = "[COMPRESSED]"

    /**
     * en la interfaz"separador de contexto"marca.
     *
     * con [MARKER] separado: mensaje resumido ([MARKER]) es**Enviado al modelo**contenido; la línea separadora es**solo en la interfaz**de
     * Punto de anclaje de posicionamiento, utilizado para indicarle al usuario"El diálogo anterior aquí se ha comprimido y el modelo ya no es visible.". Mezclar los dos hará"¿Qué ve el modelo?"
     * con"¿Qué muestra la interfaz?"contaminarse unos a otros.
     */
    const val DIVIDER_MARKER = "[CONTEXT_DIVIDER]"

    /** El encabezado del texto del resumen: informe al modelo que este párrafo es"historia anterior", no es una nueva entrada en esta ronda. */
    private const val HEADER = "Resumen de conversaciones históricas (el contenido anterior se ha condensado, los aspectos más destacados a continuación)"

    /** Fusionar resultados.[applied]=false Indica que no es efectivo (ningún punto de control/límite no se puede colocar de manera segura) y regresa como está. */
    data class Result(
        val messages: List<UIMessage>,
        val applied: Boolean,
    ) {
        companion object {
            fun unchanged(messages: List<UIMessage>): Result = Result(messages, applied = false)
        }
    }

    /**
     * Dentro de la cobertura de los puntos de control,**será reemplazado por un resumen**La identificación del mensaje (es decir, el límite y el contenido del lado anterior del asistente).
     *
     * con [apply] Las compensaciones deben ser consistentes: los mensajes de usuario y los prefijos del sistema no se reemplazan ni se cuentan como"contexto guardado",
     * De lo contrario, la estimación de la tasa de ocupación será falsamente baja y los ingresos mostrados también serán falsos.
     */
    fun coveredMessageIds(messages: List<UIMessage>, checkpoint: ContextCheckpointEntity?): Set<String> {
        val boundaryId = checkpoint?.lastCoveredMessageId.orEmpty()
        val boundaryIndex = messages.indexOfFirst { it.id.toString() == boundaryId }
        return if (boundaryId.isBlank() || boundaryIndex < 0) {
            emptySet()
        } else {
            messages.take(boundaryIndex + 1)
                .filterNot { it.role == MessageRole.USER || it.role == MessageRole.SYSTEM }
                .mapTo(mutableSetOf()) { it.id.toString() }
        }
    }

    /**
     * Fusionar puntos de control.
     *
     * @param messages Historial ordenado y limpio de llamadas a herramientas huérfanas
     * @param checkpoint Punto de control válido de la sesión actual (la persona que llama debe determinar primero la validez)
     */
    fun apply(messages: List<UIMessage>, checkpoint: ContextCheckpointEntity?): Result {
        val currentCheckpoint = checkpoint ?: return Result.unchanged(messages)
        val boundaryId = currentCheckpoint.lastCoveredMessageId
        val boundaryIndex = messages.indexOfFirst { it.id.toString() == boundaryId }
        val alreadySummarized = messages.any { it.role == MessageRole.SYSTEM && it.content.startsWith(MARKER) }
        // Situaciones que no deben fusionarse: no hay puntos de control/el historial está vacío/falta el límite/el límite no está en el historial/el resumen ya existe
        val noCheckpoint = messages.isEmpty() || boundaryId.isBlank()
        if (noCheckpoint || alreadySummarized) return Result.unchanged(messages)

        // La página de chat generalmente solo contiene la página de mensajes más reciente; el límite del punto de control persistente puede ser anterior a esta página.
        // En este momento, use seq/commitSeq para determinar que la página actual está detrás del límite y que el resumen aún se puede insertar de forma segura.
        // no puedo poner“La paginación no se lleva al borde.”confundido con“historial eliminado”.
        if (boundaryIndex < 0) {
            val coveredSeq = currentCheckpoint.coveredSeq
            val stableOrder: (UIMessage) -> Long = { message ->
                when {
                    message.seq > 0L -> message.seq
                    message.commitSeq > 0L -> message.commitSeq
                    else -> 0L
                }
            }
            val hasStableOrder = messages.any { stableOrder(it) > 0L }
            if (coveredSeq <= 0L || !hasStableOrder) return Result.unchanged(messages)
            val retained = messages.filter { message ->
                message.role == MessageRole.USER ||
                    message.role == MessageRole.SYSTEM ||
                    stableOrder(message) > coveredSeq
            }
            return Result(listOf(summaryMessage(currentCheckpoint)) + retained, applied = true)
        }

        // Mensajes después del límite: todos se conservan (el resultado de la herramienta y la respuesta del asistente se conservan)."trabajo en progreso")
        val kept = messages.drop(boundaryIndex + 1)
        // Dentro de los límites: recicle solo el contenido extenso en el lado del asistente (respuesta/pensar/resultados de la herramienta).
        // Los mensajes de usuario y el prefijo del sistema deben dejarse —— ellos son"¿Qué dijo el usuario?"registros originales,
        // Y el prefijo del sistema (system prompt / RAG / Libro Mundial) es una inyección irrecuperable, y no puede deberse a caer en
        // Los límites ya se han desechado antes.
        val retained = messages.take(boundaryIndex + 1)
            .filter { it.role == MessageRole.USER || it.role == MessageRole.SYSTEM }

        val merged = ArrayList<UIMessage>(retained.size + kept.size + 1)
        merged += summaryMessage(currentCheckpoint)
        merged += retained
        merged += kept
        return Result(merged, applied = true)
    }

    private fun summaryMessage(checkpoint: ContextCheckpointEntity): UIMessage = UIMessage(
        role = MessageRole.SYSTEM,
        content = buildString {
            append(MARKER)
            append(' ')
            append(HEADER)
            append("\n\n")
            append(checkpoint.summary.trim())
        },
    )
}
