package io.zer0.muse.data

/**
 * v2.6.2(comentarios de los usuarios): Análisis alternativo del fondo de chat de tres capas (función pura, conveniente para pruebas individuales).
 *
 * Prioridad: nivel de sesión > nivel asistente > Incumplimiento global.
 * Cuando el valor de cualquier capa es una cadena en blanco, se considera como"no establecido", continúa cayendo de nuevo.
 *
 * @param sessionId ID de sesión actual (Agent El modo pasa agentSessionId;null Indica que no hay sesión)
 * @param assistantId asistente actual id
 * @param sessionBackgrounds sessionId → Ruta de la imagen
 * @param assistantBackgrounds assistantId → Ruta de la imagen
 * @param globalBackground Fondo global predeterminado
 * @return La ruta de fondo final que entra en vigor; devuelve nulo si no hay tres capas (volviendo al color de fondo predeterminado del tema)
 */
internal fun resolveChatBackground(
    sessionId: String?,
    assistantId: String?,
    sessionBackgrounds: Map<String, String>,
    assistantBackgrounds: Map<String, String>,
    globalBackground: String?,
): String? {
    val fromSession = sessionId?.takeIf { it.isNotBlank() }?.let { sessionBackgrounds[it] }
    val fromAssistant = assistantId?.takeIf { it.isNotBlank() }?.let { assistantBackgrounds[it] }
    return when {
        !fromSession.isNullOrBlank() -> fromSession
        !fromAssistant.isNullOrBlank() -> fromAssistant
        else -> globalBackground?.takeIf { it.isNotBlank() }
    }
}
