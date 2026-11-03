package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage

/**
 * La unidad de visualización de la capa de representación de la lista de chat.
 *
 * poner"Un elemento de lista por mensaje"refinado a"Una única/grupo de llamadas de herramientas consecutivas",
 * Para convertir operaciones de herramientas con múltiples ruedas de"Una larga lista de cartas scatter"converge a"Una tarjeta de grupo de operaciones".
 *
 * Nota:esto es solo**capa de renderizado**grupo,Los datos del mensaje en sí no se modifican.
 * [messageIds] Grabe el mensaje cubierto por esta unidad de visualización. id,para posicionamiento de desplazamiento/Resaltado de búsqueda"noticias → elemento de la lista"Mapeo de índices.
 */
sealed interface ChatDisplayItem {
    /** Mensajes cubiertos por esta unidad de visualización id(en orden original). */
    val messageIds: List<String>

    /** Mensaje único. */
    data class Single(val msg: UIMessage) : ChatDisplayItem {
        override val messageIds: List<String> get() = listOf(msg.id.toString())
    }

    /** un grupo**continuo**mensajes agregables(como llamadas continuas a herramientas). Condiciones de establecimiento:Cantidad >= [ChatDisplayGrouper.MIN_RUN_SIZE]. */
    data class Grouped(val msgs: List<UIMessage>) : ChatDisplayItem {
        override val messageIds: List<String> get() = msgs.map { it.id.toString() }
    }
}

/**
 * Presione la lista de mensajes"segmento polimerizable continuo"Grupo.
 *
 * reglas:
 *  - continuo >= [MIN_RUN_SIZE] satisfacer [isGroupable] noticias → uno [ChatDisplayItem.Grouped];
 *  - El resto de novedades son aparte. [ChatDisplayItem.Single];
 *  - Mensaje único agregable**No**en grupos(Una sola llamada a la herramienta se convierte en"grupo"Al contrario, es aún más extraño.,Mantener una sola tarjeta).
 *
 * La condición de juicio la determina la persona que llama con [isGroupable] entrante(por ejemplo"Hay toolCallInfo y no hay tarjeta de tarea"),
 * De esta manera, el estado de la interfaz de usuario, como taskCard, no tiene por qué filtrarse a esta función. —— Mantenga las funciones puras y comprobables.
 */
object ChatDisplayGrouper {

    /**
     * El número mínimo de elementos consecutivos necesarios para formar un grupo.
     *
     * Toma 1: Un único mensaje de proceso puro (solo se ajusta una herramienta en una ronda, o solo un período de reflexión)
     * También debería integrarse en tarjetas de proceso en lugar de herramientas separadas./Aparecen tarjetas de pensamiento.
     * El valor original de 2 hará que el escenario normal de "una herramienta por ronda" no se doble en absoluto.
     */
    const val MIN_RUN_SIZE = 1

    fun group(
        messages: List<UIMessage>,
        isGroupable: (UIMessage) -> Boolean,
        /**
         * Juicio de transmisión transparente: este tipo de noticias**No participar en el grupo.**, pero tampoco trunca la agrupación.
         *
         * El mensaje típico es el mensaje de "tarjeta de tareas". —— La tarjeta de tarea debe renderizarse sola (la tarjeta de grupo no la reconoce),
         * Pero pertenece a la misma corriente y no deberíamos pensar en ello antes y después./Las herramientas se dividen en dos grupos.
         */
        isTransparent: (UIMessage) -> Boolean = { false },
    ): List<ChatDisplayItem> {
        if (messages.isEmpty()) return emptyList()

        val result = ArrayList<ChatDisplayItem>(messages.size)
        var index = 0
        while (index < messages.size) {
            if (!isGroupable(messages[index])) {
                result += ChatDisplayItem.Single(messages[index])
                index++
                continue
            }
            // Recopilar segmentos agregables continuos; cuando encuentre un mensaje transparente, omítalo y continúe, pero no lo combine con el grupo.
            var end = index
            val run = ArrayList<UIMessage>()
            val transparentInRun = ArrayList<UIMessage>()
            while (end < messages.size) {
                val current = messages[end]
                when {
                    isGroupable(current) -> {
                        run += current
                        end++
                    }
                    // Mensaje transparente: permitido cruzar, seguir buscando; se renderiza de forma independiente
                    isTransparent(current) -> {
                        transparentInRun += current
                        end++
                    }
                    else -> break
                }
            }
            if (run.isEmpty()) {
                // No hay elementos agregables en este párrafo: salida uno por uno en el orden original
                messages.subList(index, end).forEach { result += ChatDisplayItem.Single(it) }
            } else {
                result += ChatDisplayItem.Grouped(run)
                // Los mensajes de paso se clasifican según el grupo, manteniendo su orden relativo original.
                transparentInRun.forEach { result += ChatDisplayItem.Single(it) }
            }
            index = end
        }
        return result
    }

    /**
     * crear"noticias id → Subíndice de unidad de visualización"índice de,para posicionamiento de desplazamiento/Los aspectos destacados de la búsqueda aún se pueden ubicar después de la agrupación.
     *
     * Si el objetivo del salto de búsqueda es un mensaje del grupo,Debería saltar al índice de elementos de la lista del grupo.
     */
    fun indexByMessageId(items: List<ChatDisplayItem>): Map<String, Int> {
        val map = HashMap<String, Int>()
        items.forEachIndexed { itemIndex, item ->
            item.messageIds.forEach { id -> map.putIfAbsent(id, itemIndex) }
        }
        return map
    }
}

/**
 * Busque el ID del mensaje en pantalla de la herramienta "El producto ha sido extraído mediante la tarjeta de tarea".
 *
 * Aparecerán dos mensajes en la rueda de herramientas.:un mensaje de asistente(Colgar tarjeta de tarea)+ Un mensaje en pantalla por herramienta.
 * Cada paso de la tarjeta de tareas representará directamente el producto.(Imágenes y archivos generados…),
 * La información de visualización de la herramienta en sí también utilizará el mismo conjunto de productos para renderizarse directamente. —— Entonces la misma tarjeta aparece dos veces en la pantalla.
 * (Comentarios de los usuarios "Se muestran dos tarjetas en una tarjeta y en ese lugar").
 *
 * Determinación basada en la asociación de padres.:mensaje en pantalla de herramienta [UIMessage.parentMessageId] Señalar el mensaje del asistente correspondiente.,
 * Este último está en [taskCardOwnerIds] Significa que los productos de esta ronda han sido sorteados.
 *
 * Nota:Herramientas entregables(show_card)La exención no está en esta función. —— Esta es una función pura, una estrategia de renderizado inconsciente.;
 * llamador(ChatScreen)Elimine los entregables del conjunto de resultados. Ver
 * [io.zer0.muse.ui.isInlineDeliverableTool].
 *
 * función pura,Conveniente para pruebas individuales.
 */
internal fun toolMessageIdsCoveredByTaskCards(messages: List<UIMessage>, taskCardOwnerIds: Set<String>): Set<String> {
    if (taskCardOwnerIds.isEmpty()) return emptySet()
    val out = HashSet<String>()
    messages.forEach { msg ->
        if (msg.toolCallInfo == null) return@forEach
        val parentId = msg.parentMessageId ?: return@forEach
        if (parentId in taskCardOwnerIds) out.add(msg.id.toString())
    }
    return out
}
