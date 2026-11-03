package io.zer0.muse.tools

import io.zer0.ai.core.UIMessage

/**
 * v1.200: Constructor de contexto de delegación.
 *
 * Extraiga contexto condensado de mensajes de conversación,suministro [SkillExecutor.delegateAgent] Usar.
 * Mantenga solo el más reciente [DEFAULT_MAX_MESSAGES] mensaje de texto,Filtrar imágenes/Contenido que no es texto, como audio.,
 * Evite contextos de ayuda infantil que sean demasiado largos o estén contaminados con contenido multimodal.
 */
object DelegationContextBuilder {

    /** El número máximo de mensajes de contexto transportados de forma predeterminada. */
    const val DEFAULT_MAX_MESSAGES = 8

    /**
     * Extraiga el contexto condensado de los mensajes de conversación.
     *
     * @param sessionMessages Todos los mensajes de la conversación actual
     * @param maxMessages Número máximo de artículos a transportar.(Obtenga los N elementos más recientes)
     * @param includeImages Si conservar mensajes con imágenes(Predeterminado false,No pasar imagen al delegar subasistente)
     */
    fun build(sessionMessages: List<UIMessage>, maxMessages: Int = DEFAULT_MAX_MESSAGES, includeImages: Boolean = false): List<UIMessage> {
        return sessionMessages
            .takeLast(maxMessages)
            .filter { msg ->
                msg.content.isNotBlank() && (includeImages || msg.imageUrls.isEmpty())
            }
            .map { msg ->
                if (includeImages) {
                    // Incluso si conservas la foto,Elimine también los campos redundantes, como los metadatos de llamadas a herramientas y la cadena de inferencia.,
                    // Evite solicitudes de subayudante con llamadas a herramientas incompletas o contenido de razonamiento no compatible.
                    msg.copy(
                        toolCalls = null,
                        toolCallId = null,
                        toolCallInfo = null,
                        reasoning = null,
                        thinkingSignature = null,
                        voiceUri = null,
                        voiceDurationMs = 0L,
                    )
                } else {
                    // Utilice la ruta de texto sin formato al delegar ayudantes infantiles:Borre campos como imágenes, videos, llamadas a herramientas, cadenas de inferencia, etc.,
                    // Evitar el envío de imágenes a modelos de solo texto,O envíe llamadas de herramientas huérfanas a solicitudes que no tengan habilitadas las llamadas de herramientas.
                    msg.copy(
                        imageUrls = emptyList(),
                        imageBase64List = emptyList(),
                        videoFileUri = null,
                        videoMimeType = null,
                        voiceUri = null,
                        voiceDurationMs = 0L,
                        toolCalls = null,
                        toolCallId = null,
                        toolCallInfo = null,
                        reasoning = null,
                        thinkingSignature = null,
                        citationUrls = emptyList(),
                        ragCitations = emptyList(),
                        artifactIds = emptyList(),
                        mood = null,
                        reflection = null,
                        quotedContent = null,
                        reaction = null,
                    )
                }
            }
    }
}
