package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRiskLevel

/** B8-03 Planificar B / v2.x (B3): Estrategias de entrada y salida de herramientas de medios de chat grupal. */
object GroupChatToolPolicy {

    /**
     * v2.x (B3): Se ha abierto el chat grupal."Generar resultados → Archivos adjuntos de mensajes"Herramientas multimedia para canales de display — Déjalo ir.
     *
     * El producto del gráfico generado se escribe en el mensaje del chat grupal. `imageBase64Json`(Representado por MessageImageGrid);
     * v2.x El legado termina,Generar mensaje de redacción de producto en vídeo. `videoFileUri` columna(por AssistantVideoCard
     * Renderizar, haga clic para abrir el reproductor.),ya no"No hay canal de visualización después del tono blanco". nivel de riesgo
     * [ToolPermissionResolver.riskLevelFor] ir `generate_` prefijo = SAFE,No se requiere aprobación del chat grupal.
     */
    val ENABLED_MEDIA_TOOLS: Set<String> = setOf(
        "generate_image",
        "generate_video",
        "generate_qr_code",
    )

    /**
     * v2.x (B3): Las herramientas multimedia permanecen deshabilitadas.
     *
     * Finalización del canal de vídeo(noticias `videoFileUri` Columna + AsistenteRepresentación de tarjeta de video)más tarde liberado,
     * Conjunto actualmente vacío;Mantenga esta colección como ubicación de bloqueo predeterminada para adiciones posteriores de herramientas multimedia.
     */
    val BLOCKED_MEDIA_TOOLS: Set<String> = emptySet()

    /**
     * Herramienta de filtrado de medios de chat grupal:Conserva solo aquellos que hayan abierto el canal de visualización. [ENABLED_MEDIA_TOOLS].
     *
     * v2.x (B3): Cambie con las herramientas generales del chat grupal(GroupChatScheduler.ENABLE_GROUP_CHAT_REGULAR_TOOLS)desacoplamiento —
     * Las herramientas normales están desactivadas de forma predeterminada para evitar tormentas de llamadas de herramientas vacías para modelos pequeños.,Pero las herramientas de medios ya tienen canales de visualización.,
     * Listo para usar inyectando solo(Aún filtrado por miembro toolIdsJson).
     */
    fun filterMediaTools(tools: List<ToolDefinition>): List<ToolDefinition> = tools.filter { it.name in ENABLED_MEDIA_TOOLS }

    /**
     * Lista de herramientas generales para filtrar chats grupales.
     *
     * P1-11 Front-end de lista blanca de riesgos:excepto [BLOCKED_MEDIA_TOOLS] afuera,Las herramientas de alto riesgo no ingresarán al canal de ejecución directa del chat grupal.
     * Las herramientas habituales de chat grupal no tienen proceso de aprobación,solo bajo/riesgo medio(SAFE/NORMAL)Las herramientas pueden ejecutarse directamente;
     * La evaluación de riesgos va [ToolPermissionResolver.riskLevelFor] única fuente de verdad(P0-3).
     */
    fun filterRegularTools(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filterNot { it.name in BLOCKED_MEDIA_TOOLS || ToolPermissionResolver.riskLevelFor(it.name) == ToolRiskLevel.HIGH }
}
