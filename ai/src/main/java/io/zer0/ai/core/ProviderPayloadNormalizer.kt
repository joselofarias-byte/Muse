package io.zer0.ai.core

import io.zer0.common.Logger

/**
 * v1.0.5: Provider La capa inferior de la bolsa de exportación está unificada.
 *
 * En proveedor poner [UIMessage] Antes de que la lista se traduzca a un cuerpo de solicitud de protocolo específico,Haga una ronda de análisis independiente del proveedor
 * limpieza general,eliminar"HERRAMIENTA huérfana/marca de imagen redundante restante en mensajes históricos"etc. hará que la estación de transferencia devuelva 400.
 *
 * Responsabilidades:
 *  1. [stripOrphanToolMessages] — Eliminar mensajes de HERRAMIENTA huérfanos que no tengan un predecesor ASSISTANT tool_calls coincidente
 *  2. [stripNativeMediaAttachmentMarkers] — Cuando los mensajes llevan imágenes reales/vídeo,Limpiar del contenido
 *     `[attached_image:...]` / `[attached_video:...]` / `[attached_audio:...]` marca
 *  3. [stripCrossModelReasoning] — El bloque de pensamiento histórico solo se reproduce cuando el modelo de salida es consistente con el modelo de solicitud actual.(v2.6.14)
 *  4. [ToolTraceProjection] — Solo comprima los giros de herramientas antiguos, completos y exitosos en las proyecciones enviadas al modelo;
 *     Rondas recientes, rondas fallidas e incompletas/Las rondas escalonadas permanecen como están para evitar romper el emparejamiento llamada_herramienta/resultado.
 *
 * La proyección de seguimiento de la herramienta no tiene efectos secundarios: el historial de la base de datos original y la lista de mensajes de la interfaz de usuario no se sobrescriben.
 *
 * No procesado en este nivel(Lo determina directamente cada Proveedor al construir el DTO.):
 *  - stripEmptyTools: OpenAIProvider/AnthropicProvider en construccion OpenAIRequest/AnthropicRequest
 *    el tiempo esta vacio `tools` La lista cambia a null(evitar serializar `"tools": []` Rechazado por estricta estación de tránsito)
 *  - stripDisabledReasoningEffort: OpenAIProvider Al construir OpenAIRequest, establezca el valor en
 *    false/none/off El esfuerzo_razonamiento se cambia a null
 *  - stripIncompatibleThinking: v1.0.7 Después de presionar OpenAIProvider.injectThinkingFormat
 *    [ThinkingFormat] Inyecte activamente los campos de extensión del fabricante correspondientes(thinking / enable_thinking /
 *    chat_template_kwargs / reasoning),No se generarán campos incompatibles;ANTHROPIC El formato es no-op
 *    (AnthropicProvider Vuélvete nativo Messages API,Sin pasar por la ruta OpenAIProvider).
 *    Por lo tanto, 1muse actualmente no necesita la cubierta general stripIncompatibleThinking.
 *    Si el futuro se hace realidad ProviderSpecificConfig.Custom.requestTemplate camino(Plantilla de solicitud definida por el usuario
 *    Posiblemente inyectar campo de pensamiento),Debe agregarse después de injectThinkingFormat stripIncompatibleThinking
 *    Ten todo en cuenta,Elimina los campos de pensamiento que no coinciden con el formato de pensamiento.
 *
 * tiempo de llamada:OpenAIProvider.buildRequestBody / AnthropicProvider.splitSystem antes,
 * Provider poner `request.messages` Revísalo primero [normalizeMessages],Luego haz la traducción del protocolo.
 */
object ProviderPayloadNormalizer {
    private const val TAG = "ProviderPayloadNormalizer"

    // ── Reglas de etiquetado de archivos adjuntos de medios ──
    // partido [attached_image: cualquier personaje] + nueva línea opcional,global reemplazar
    private val ATTACHED_IMAGE_MARKER_RE = Regex("""\[attached_image:\s*[^\]]+\]\n?""")
    private val ATTACHED_VIDEO_MARKER_RE = Regex("""\[attached_video:\s*[^\]]+\]\n?""")
    private val ATTACHED_AUDIO_MARKER_RE = Regex("""\[attached_audio:\s*[^\]]+\]\n?""")
    private val MULTIPLE_BLANK_LINES_RE = Regex("""\n{3,}""")

    /**
     * si [UIMessage] Las listas realizan una limpieza general independiente del proveedor.
     *
     * @param messages Historial de conversación original(Contiene system / user / assistant / tool)
     * @param model modelo objetivo(Actualmente no en uso,Reservado para una limpieza futura basada en la diferenciación de capacidades del modelo)
     * @return Lista de mensajes limpia(Puede ser más corta que la lista original.)
     */
    fun normalizeMessages(messages: List<UIMessage>, model: Model): List<UIMessage> {
        if (messages.isEmpty()) return messages
        var result = messages
        result = stripInvalidToolCalls(result)
        result = stripOrphanToolMessages(result)
        result = stripNativeMediaAttachmentMarkers(result)
        result = stripArtifactMarkers(result)
        // v2.5.3 (P2-2): Desnudo uniformemente antes de enviar —— razonamiento/La firma es una carga útil opaca y solo debe estar presente en los mensajes del asistente;
        // Los mensajes que no son del asistente llevan estos campos (importar/Migrar/Estructuras anormales) deben limpiarse para evitar que se filtren en línea.
        result = stripReasoningFromNonAssistant(result)
        // v2.6.14: mismo modelo de guardia —— El bloque de pensamiento histórico solo se reproduce cuando el modelo de salida es consistente con el modelo de solicitud actual.
        result = stripCrossModelReasoning(result, model)
        // La compresión de la trayectoria de la herramienta solo ocurre en las proyecciones de solicitudes del proveedor;UI/El historial de la base de datos permanece sin cambios.
        result = ToolTraceProjection.project(result)
        return result
    }

    /**
     * v2.5.3 (P2-2): Los mensajes que no sean del asistente no deben contener ningún razonamiento./Campo de firma.
     *
     * Razonamiento (reasoning) y firma (thinkingSignature / thinkingEncryptedContent) es turno de asistente
     * Carga útil opaca exclusiva; una vez que aparece en los mensajes del usuario/herramienta/sistema, no tiene sentido y es engañoso para los usuarios, y debe eliminarse.
     */
    private fun stripReasoningFromNonAssistant(messages: List<UIMessage>): List<UIMessage> {
        var changed = false
        val result =
            messages.map { msg ->
                if (msg.role == MessageRole.ASSISTANT) {
                    msg
                } else if (
                    msg.reasoning != null || msg.thinkingSignature != null || msg.thinkingEncryptedContent != null
                ) {
                    changed = true
                    msg.copy(reasoning = null, thinkingSignature = null, thinkingEncryptedContent = null)
                } else {
                    msg
                }
            }
        if (changed) {
            Logger.w(TAG, "stripReasoningFromNonAssistant: Se aclararon razonamientos incorrectos en mensajes que no eran del asistente./campo de firma")
        }
        return if (changed) result else messages
    }

    /**
     * v2.6.14: Mismo modelo de guardia para la repetición del bloque de pensamiento.
     *
     * Antecedentes: razonamiento/Las firmas son cargas útiles opacas que sólo tienen significado para el modelo que las produce. Después de que el usuario cambia de modelo a mitad de sesión,
     * Los bloques de pensamiento producidos por el modelo anterior en la historia aún se enviarán al nuevo modelo junto con el enlace de reproducción: al menos se incluirá contenido de razonamiento irrelevante en él.
     * El contexto del nuevo modelo (respuesta contaminada), especialmente porque la firma no pertenece al modelo./La sesión fue sentenciada a 400 por el upstream.
     *
     * Juicio: comparar [UIMessage.modelId](Modelo de salida de mensaje histórico) y el modelo de destino de esta solicitud [Model.id].
     *  - Consistente: retenido, reproducción normal;
     *  - Inconsistente: degradado a 'Descartar'—— Borrar reasoning / thinkingSignature / thinkingEncryptedContent,
     *    El contenido del texto se deja tal cual.
     *  - Modelos de salida históricamente no documentados (datos antiguos, escrituras de enlaces que no son de conversación): no se pueden probar en todos los modelos, permanecen como están.
     *    Aquí no se utilizan deliberadamente fuentes desconocidas. Fallo cerrado: quitar todos los bloques de pensamiento de fuentes desconocidas a la vez destruirá los que aún se utilizan
     *    Piense en sesiones existentes reproducidas (Anthropic El bloque de pensamiento característico de Toolwheel) se elimina en conjunto, en lugar de hacer 400.
     *
     * ¿Por qué degradar para descartar en lugar de incorporar el contenido de pensamiento en el texto principal? El bloque de pensamiento es una carga útil opaca con una firma, y la firma es solo para la salida.
     * El modelo es válido; incorporar el texto pensante al contenido del asistente equivale a forjar una ronda de "el modelo dijo esto", que contaminará
     * Los hechos históricos, los resúmenes y el caché de mensajes posteriores siguen siendo ilegales para los protocolos que requieren verificación de firma. El descarte se realiza por cada protocolo.
     * La acción más pequeña aceptable (Anthropic También hay 400 bolsillos de autorreparación y sin firma en el lateral).
     */
    private fun stripCrossModelReasoning(messages: List<UIMessage>, model: Model): List<UIMessage> {
        val target = replayModelKey(model.id)
        if (target.isEmpty()) return messages
        var changed = false
        val result =
            messages.map { msg ->
                if (msg.role != MessageRole.ASSISTANT) return@map msg
                val source = msg.modelId?.takeIf { it.isNotBlank() }?.let(::replayModelKey)
                if (source.isNullOrEmpty() || source == target) return@map msg
                if (msg.reasoning == null && msg.thinkingSignature == null && msg.thinkingEncryptedContent == null) {
                    return@map msg
                }
                changed = true
                Logger.w(
                    TAG,
                    "stripCrossModelReasoning: Bloques de pensamiento histórico a partir de modelos ${msg.modelId},con modelo actual ${model.id} inconsistente,despojado",
                )
                msg.copy(reasoning = null, thinkingSignature = null, thinkingEncryptedContent = null)
            }
        return if (changed) result else messages
    }

    /**
     * Normalice las ID de modelo utilizadas para determinar el mismo modelo: elimine etiquetas de agrupación de canales, elimine prefijos de estaciones de agregación e ignore mayúsculas y minúsculas.
     *
     * Los registros históricos y las selecciones actuales a menudo provienen de diferentes formatos de visualización (`[ais]gemini-3.8-flash` / `openrouter/x/y` /
     * Diferencias de casos), solo pueden juzgarse como el mismo modelo después de la normalización; si siguen siendo diferentes después de la normalización, se tratarán como modelos cruzados.
     */
    private fun replayModelKey(modelId: String): String {
        val stripped = stripChannelGroupLabels(modelId).trim().lowercase()
        return stripped.substringAfterLast('/').ifBlank { stripped }
    }

    /**
     * v1.x: Quitar etiquetas de marcador de posición de artefactos de los mensajes del asistente `[artifact:uuid]`.
     *
     * El modelo puede aprender del historial para generar directamente este marcador de posición.(en lugar de en parejas `<artifact>` etiqueta),
     * Pero la entidad correspondiente al marcador de posición no existe. Después de pelar:El usuario no ve el texto claro del UUID,
     * El historial del modelo también está limpio.,Evite seguir imitando el formato. Los productos reales se generan a partir de rutas de etiquetas por pares.
     */
    private fun stripArtifactMarkers(messages: List<UIMessage>): List<UIMessage> {
        return messages.map { msg ->
            if (msg.role == MessageRole.ASSISTANT && msg.content.contains("[artifact:")) {
                msg.copy(content = msg.content.replace(ARTIFACT_MARKER_RE, ""))
            } else {
                msg
            }
        }
    }

    private val ARTIFACT_MARKER_RE = Regex("""\[artifact:[0-9a-fA-F-]{36}\]""")

    /**
     * v1.0.62: Eliminar mensajes ilegales del ASISTENTE tool call(name o los argumentos están vacíos).
     * antes [stripOrphanToolMessages] Ejecutar, para que los mensajes de HERRAMIENTA correspondientes a las llamadas eliminadas se borren juntos.
     */
    private fun stripInvalidToolCalls(messages: List<UIMessage>): List<UIMessage> {
        var changed = false
        val result = messages.map { msg ->
            if (msg.role != MessageRole.ASSISTANT || msg.toolCalls.isNullOrEmpty()) {
                msg
            } else {
                val sanitized = ToolCallSanitizer.sanitize(msg.toolCalls)
                if (sanitized.size == msg.toolCalls.size) {
                    msg
                } else {
                    changed = true
                    msg.copy(toolCalls = sanitized.ifEmpty { null })
                }
            }
        }
        return if (changed) result else messages
    }

    // ── 1. Limpieza de mensajes de HERRAMIENTA huérfana ──

    /**
     * Elimine los mensajes de HERRAMIENTA huérfanos que no tengan un predecesor ASSISTANT tool_calls coincidente.
     *
     * escena:
     *  - Historial de edición del manual de usuario,Se eliminó un mensaje de ASISTENTE pero se retuvo el mensaje de reposición de HERRAMIENTA correspondiente
     *  - El mensaje ASISTENTE fue interrumpido anormalmente en el bucle de llamada de herramienta.,Pero el mensaje HERRAMIENTA se ha agregado al historial.
     *  - La lógica de transformación de mensajes del SDK ascendente descarta el campo tool_calls de ASSISTANT
     *
     * OpenAI Protocolo compatible(Estación de transferencia especialmente estricta)solicitar cada uno `role: tool` Todos los mensajes tienen precursores que coinciden
     * `tool_calls` mensaje del asistente,De lo contrario regresar 400 invalid_request_error:
     * `An assistant message with 'tool_calls' must be turned before ...`
     *
     * Instrucciones de implementación:Recoge todos los mensajes del ASISTENTE llevados por tool_calls id,entonces mantén toolCallId
     * Mensajes de HERRAMIENTA en la colección. Esto es equivalente a"hay una coincidencia"——en el flujo normal de conversación,ASSISTANT de
     * tool_calls Aparece siempre antes del mensaje HERRAMIENTA correspondiente,Por lo tanto"hay una coincidencia"con"Coincidencia de ruedas delanteras"Equivalente.
     */
    private fun stripOrphanToolMessages(messages: List<UIMessage>): List<UIMessage> {
        // Recoge todos los mensajes del ASISTENTE llevados por tool_calls id
        val parentToolCallIds = HashSet<String>()
        messages.forEach { msg ->
            if (msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                msg.toolCalls.forEach { tc ->
                    if (tc.id.isNotBlank()) parentToolCallIds.add(tc.id)
                }
            }
        }

        val hasToolMsg = messages.any { it.role == MessageRole.TOOL }
        if (!hasToolMsg) return messages

        if (parentToolCallIds.isEmpty()) {
            // sin ningún ASSISTANT tool_calls,Todos los mensajes de HERRAMIENTA son huérfanos
            val removed = messages.count { it.role == MessageRole.TOOL }
            Logger.w(TAG, "stripOrphanToolMessages: Ninguno ASSISTANT tool_calls,Eliminar $removed Mensajes de HERRAMIENTA huérfana")
            return messages.filter { it.role != MessageRole.TOOL }
        }

        // Mantenga los mensajes que no sean de HERRAMIENTA + toolCallId en parentToolCallIds para los mensajes de HERRAMIENTA
        var removed = 0
        val filtered = messages.filter { msg ->
            if (msg.role != MessageRole.TOOL) {
                true
            } else {
                val matched = msg.toolCallId != null && msg.toolCallId in parentToolCallIds
                if (!matched) {
                    removed++
                    Logger.w(
                        TAG,
                        "stripOrphanToolMessages: Eliminar mensajes de HERRAMIENTA huérfanos toolCallId=${msg.toolCallId}",
                    )
                }
                matched
            }
        }
        return if (removed == 0) messages else filtered
    }

    // ── 2. Limpieza de marcado de archivos adjuntos de medios nativos ──

    /**
     * Cuando los mensajes llevan imágenes reales([UIMessage.imageBase64List])o vídeo([UIMessage.videoFileUri])tiempo,
     * Limpiar del contenido `[attached_image:...]` / `[attached_video:...]` / `[attached_audio:...]` marca.
     *
     * escena:
     *  - llamador ascendente(Como referencias de archivos/resultados de herramientas.)insertado en el contenido `[attached_image: path]`
     *    como marcador de posición,Al mismo tiempo, imageBase64List incluye imágenes reales. base64
     *  - Provider traducirá imágenes reales a `image_url` / `source.base64` Enviar al modelo
     *  - Si no limpias la marca,El modelo ve tanto texto etiquetado como imágenes reales.,causar confusión
     *
     * Cuando un mensaje no tiene una imagen real/vídeo,mantener la marca(Hazle saber al modelo al menos que hay una referencia de imagen.,Incluso si no puedes ver la imagen original).
     */
    private fun stripNativeMediaAttachmentMarkers(messages: List<UIMessage>): List<UIMessage> {
        var changed = false
        val result = messages.map { msg ->
            val hasImage = msg.imageBase64List.isNotEmpty()
            val hasVideo = !msg.videoFileUri.isNullOrBlank()
            if (!hasImage && !hasVideo) return@map msg
            val cleaned = stripMediaMarkers(msg.content, stripImage = hasImage, stripVideo = hasVideo)
            if (cleaned == msg.content) return@map msg
            changed = true
            msg.copy(content = cleaned)
        }
        return if (changed) result else messages
    }

    /**
     * Eliminar etiquetas de archivos adjuntos multimedia del texto,Y recorte las líneas en blanco redundantes.
     */
    private fun stripMediaMarkers(text: String, stripImage: Boolean, stripVideo: Boolean): String {
        if (text.isEmpty()) return text
        var result = text
        if (stripImage) result = result.replace(ATTACHED_IMAGE_MARKER_RE, "")
        if (stripVideo) {
            result = result.replace(ATTACHED_VIDEO_MARKER_RE, "")
            // Las escenas de vídeo suelen ir acompañadas también de pistas de audio.,limpiar juntos
            result = result.replace(ATTACHED_AUDIO_MARKER_RE, "")
        }
        // Regular 3+ Cambiar filas a 2 consecutivamente,Y recorte los espacios en blanco iniciales y finales.
        result = result.replace(MULTIPLE_BLANK_LINES_RE, "\n\n").trim()
        return result
    }
}
