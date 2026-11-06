package io.zer0.muse.ui.chat

import io.zer0.ai.core.ContextHistoryDigest
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.splitContextWindow

/**
 * montar"Ventana contextual + extracto del historial fuera de la ventana".
 *
 * Los mensajes fuera de la ventana ya no se descartan silenciosamente:Primero haga extractos deterministas de ellos.([ContextHistoryDigest],
 * retener usuario/Fragmentos de lenguaje natural del asistente y resúmenes de llamadas de herramientas),El mensaje extraído se inserta antes de la ventana.
 * De esta manera, incluso si una sesión que utiliza muchas herramientas llena la ventana,La modelo aún puede ver la esencia de la conversación anterior.,
 * no aparecerá"El contexto se pierde, como la amnesia."actuación.
 *
 * suministro resolveHistory(Genera cada ronda)Truncado con advertencia previa al envío(Cerca del límite superior del token)compartido.
 */
internal fun buildContextWindow(history: List<UIMessage>, maxSize: Int): List<UIMessage> {
    val (dropped, kept) = history.splitContextWindow(maxSize)
    if (dropped.isEmpty()) return kept
    val digest = ContextHistoryDigest.build(dropped)
    return if (digest == null) kept else listOf(digest) + kept
}
