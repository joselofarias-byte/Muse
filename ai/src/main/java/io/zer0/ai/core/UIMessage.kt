package io.zer0.ai.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Rol del mensaje.Phase 1 Sólo uso SYSTEM/USER/ASSISTANT,
 * TOOL Déjelo para las llamadas a herramientas de la Fase 3.
 */
@Serializable
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/**
 * Una solicitud de llamada de herramienta(LLM Generado cuando se toma la decisión de invocar la herramienta.).
 *
 * OpenAI campo de protocolo:
 *  - [id] Sólo llamada de herramienta id(para relleno tool_call_id)
 *  - [name] nombre de la función
 *  - [arguments] Cadena JSON de parámetro(Posiblemente transmitiendo fragmentos delta)
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * v0.47: Información de la tarjeta de llamada de herramientas(Usado por MessageBubble para representar tarjetas colapsadas).
 *
 * no persistió en MessageEntity(Perdido después de reiniciar,aceptable,Las tarjetas de visita de herramientas son información instantánea,
 * Contenido de texto sin formato para conversaciones históricas.).
 */
@Immutable
@Serializable
data class ToolCallInfo(
    val toolName: String,
    val arguments: String,
    val result: String,
    val isSuccess: Boolean,
    /**
     * Tiempo de ejecución local (milisegundos).
     *
     * Solo cubre el período en el que Muse ejecuta sus propias herramientas, excluyendo la espera de la red y el tiempo de generación del modelo;
     * Es nulo durante la ejecución y el ejecutor lo reescribirá una vez finalizado. Se utiliza para mostrar el consumo de tiempo en el lado derecho del paso de la herramienta.
     * Permitir que los usuarios juzguen“¿Es lento en el modelo o en la herramienta?”.
     */
    val durationMs: Long? = null,
)

/**
 * v1.0.47: Referencia del archivo adjunto del mensaje(Persistencia estructurada de metadatos de archivos sin formato).
 *
 * Situación actual:Después del análisis del documento, fusionar en content,Se descartaron los metadatos del archivo original.
 * Este campo conserva la información del archivo adjunto original.,UI Chip de documento restaurable(Haga clic para ver el texto completo),
 * Provider Todavía se usa al enviar solicitudes. content(Compatible con el viejo Provider).
 *
 * @param id El único apego id
 * @param name nombre de archivo
 * @param mimeType MIME Tipo
 * @param size tamaño de archivo(bytes)
 * @param sourceType Tipo de fuente(FILE=archivo local / URL=enlace web)
 * @param extractedText Contenido de texto extraído(Después del análisis del documento)
 * @param workspacePath Ruta relativa del espacio de trabajo(Como por ejemplo `attachments/report.pdf`)
 */
@Immutable
@Serializable
data class AttachmentRef(
    val id: String = kotlin.uuid.Uuid.random().toString(),
    val name: String,
    val mimeType: String = "application/octet-stream",
    val size: Long = 0L,
    val sourceType: String = "FILE",
    val extractedText: String? = null,
    /**
     * Ruta relativa del espacio de trabajo.
     *
     * Es necesario reabrir el original del mensaje: el original se deja en el espacio de trabajo cuando se envía el archivo adjunto. `attachments/` abajo,
     * Deje solo una línea de texto de cita en el cuerpo del mensaje. Si la ruta no se guarda, el usuario sólo puede ir al espacio de trabajo y explorarlo él mismo después.
     * Vacío significa que no quedan copias accesibles (mensajes antiguos o una ruta de degradación si el espacio de trabajo no está disponible).
     */
    val workspacePath: String? = null,
)

/**
 * UI El cuerpo del mensaje utilizado por la capa. Independiente del formato de solicitud de cualquier proveedor,
 * Depende de cada Proveedor traducirlo en la carga útil de la API correspondiente.
 *
 * Phase 1 Sólo se admite texto sin formato;[parts] La estructura multimodal queda para la Fase 3.
 *
 * Phase 5-G: [imageUrls] Se utiliza para generar resultados de imágenes.(ASSISTANT noticias),
 *            Provider Ignore este campo al enviar una solicitud(Enviar solo texto content).
 *
 * Phase 7: [toolCalls] Para herramientas de toma de decisiones de LLM(ASSISTANT noticias),
 *          [toolCallId] Se utiliza para rellenar los resultados de ejecución de la herramienta.(TOOL noticias,corresponder tool_call_id).
 *
 * Phase 8.3: [favorite] marca favorita(Capa de interfaz de usuario pura,Provider Ignorar al enviar la solicitud).
 *
 * Phase 8.4: [citationUrls] Lista de URL de resultados de búsqueda en Internet(assistant Solo mensaje).
 *            Cuando se muestra en Respuesta AI [N] Al citar números,UI En consecuencia [N] Representar como enlace en el que se puede hacer clic,
 *            Haga clic para saltar a citationUrls[N-1].Provider Este campo se ignora al enviar la solicitud.
 *
 * v1.133: [ragCitations] Lista de citas de búsqueda de la base de conocimientos(assistant Solo mensaje).
 *         por RagService.buildInjectionContextWithCitations Regresar,
 *         MessageBubble Se puede hacer clic en chip(Haga clic para ampliar snippet,Mantenga presionado para saltar a los detalles del documento.).
 *         Provider Este campo se ignora al enviar la solicitud.
 *
 * Phase 8.6: [imageBase64List] USER Una lista base64 de imágenes locales adjuntas al mensaje.(Ninguno dato: prefijo),
 *            Para enviar al modelo visual.(Gemini inlineData / OpenAI image_url).
 *            ASSISTANT Los mensajes también pueden llevar(Gemini Dibujo devuelto inlineData).
 *            UI Representar la miniatura de la imagen del mensaje de USUARIO en consecuencia,Provider Construya solicitudes multimodales en consecuencia.
 */
@Immutable
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    /**
     * v1.80 (M-ANT4): Anthropic thinking firma de bloque(Se requieren múltiples rondas de reproducción del diálogo de pensamiento).
     *
     * Anthropic extended thinking Publicado al final del bloque de pensamiento. signature_delta,
     * La siguiente ronda de solicitudes debe devolver la firma en el bloque de pensamiento del mensaje del asistente para continuar la cadena de pensamiento.
     * Por ChatViewModel de [io.zer0.ai.core.ChatStreamEvent.ReasoningDelta.signature] Acumular y almacenar en este campo.
     * v1.121: AnthropicProvider.splitSystem razonamiento/pensamientoDevolución de firma procesada,
     * ASSISTANT El bloque de contenido de pensamiento del mensaje contiene thinking+signature,El servidor puede verificar la integridad del preámbulo.
     * Otros proveedores ignoran este campo.
     */
    val thinkingSignature: String? = null,
    /** B5-03: OpenAI Responses reasoning item contenido_cifrado. */
    val thinkingEncryptedContent: String? = null,
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Orden estable dentro de una sesión de base de datos;0 Representa una instantánea antigua./El mensaje no persistió. */
    val seq: Long = 0L,
    /** Nuevo orden de envío de enlaces de conversación; tiene prioridad sobre seq cuando está presente. */
    val commitSeq: Long = 0L,
    val imageUrls: List<String> = emptyList(),
    /** Referencia de audio (URI de archivo local o ruta absoluta) del mensaje de voz emitido por el asistente.Provider Ignorado al enviar la solicitud. */
    val voiceUri: String? = null,
    /** Duración de la voz, milisegundos.UI Se utiliza para visualización, devuelto por TTS o detectado por archivo durante la síntesis. */
    val voiceDurationMs: Long = 0L,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val favorite: Boolean = false,
    /**
     * v1.104 U7: Etiquetas de grupos favoritos(Definido por el usuario,Como por ejemplo"inspiración"/"fragmento de código").
     *
     * solo en [favorite]=true significativo;null Indica desagrupado(Los favoritos se muestran en"Todos"abajo).
     * Por pantalla de favoritos Mantenga presionada la tarjeta para que aparezca"Configurar grupos"Escritura de diálogos.
     */
    val favoriteTag: String? = null,
    val citationUrls: List<String> = emptyList(),
    /** v1.133: Lista de citas de búsqueda de la base de conocimientos(assistant Solo mensaje,Provider Ignorar al enviar la solicitud). */
    val ragCitations: List<RagCitation> = emptyList(),
    /** Phase 8.6: Lista de imágenes locales base64(Ninguno dato: prefijo,Predeterminado image/jpeg). */
    val imageBase64List: List<String> = emptyList(),
    /**
     * V-GEM1: Adjunto de vídeo(Subido a la API de archivos Gemini uri),Actualmente sólo lee el proveedor Gemini.
     *
     * - [videoFileUri] en forma de "https://generativelanguage.googleapis.com/v1beta/files/abc123"
     *   (uploadFile regresó file.uri),Completado por la persona que llama después de una carga exitosa
     * - [videoMimeType] Declarado al subir MIME(Como por ejemplo "video/mp4"),Consistente con fileData.mimeType
     * - Los proveedores que no pertenecen a Gemini actualmente ignoran este campo;En el futuro, cuando OpenAI y otros admitan video, se podrán expandir en la capa del Proveedor.
     */
    val videoFileUri: String? = null,
    val videoMimeType: String? = null,
    /** v1.43: Lista de identificadores de artefactos asociados con el mensaje(Generado por ArtifactExtractor). */
    val artifactIds: List<String> = emptyList(),
    /** B6-03/B6-02: Logotipo de piel emocional(rage/rage2/desire/vuoto/moonlight/off),con <moodfx> Correspondencia de etiquetas. */
    val moodSkin: String? = null,
    /**
     * v0.30-b: MOOD bloquear(LLM Salida manuscrita interna antes del texto principal.,6 Paso Flujo de trabajo Paso 2).
     *
     * Muse reducido a un solo <mood> etiqueta + 4 campos(Vibe/Sparks/Reflections/Will).
     * Este campo se almacena en este campo después de que MoodTagTransformer lo elimine del contenido.
     * UI Representar como tarjeta plegable(Plegado predeterminado,Similar a los bloques de pensamiento profundo).
     */
    val mood: String? = null,
    /**
     * v0.32 Autorreflexión experimental: el bloque de autorreflexión generado por LLM al final de una respuesta.
     *
     * Por MoodTagTransformer / ChatViewModel.updateAssistant del contenido
     * despegar `<reflection>...</reflection>` Luego guárdelo en este campo.
     * 3 campo(Precisión/integridad/tono).UI No hagas renderizado todavía,Las tareas posteriores de la interfaz de usuario se mostrarán nuevamente.
     */
    val reflection: String? = null,
    /**
     * v0.47: Información de la tarjeta de llamada de herramientas(Usado por MessageBubble para representar tarjetas colapsadas,Reemplazar texto sin formato"herramienta de llamada xxx"noticias).
     *
     * Cuando no esté vacío, MessageBubble se representará primero con ToolCallCard.,Ignora el contenido.
     * no persistió en MessageEntity(Al reproducir sesiones históricas, este campo es null,content Aún conservando el texto sin formato.).
     */
    val toolCallInfo: ToolCallInfo? = null,
    /**
     * Cotización Responder:El contenido del mensaje citado.(Solo utilizado por la capa UI,No persistido en la base de datos).
     * Cuando persista, comience con el contenido `> ` Portador de la marca.
     */
    val quotedContent: String? = null,
    /** Función1: Tecla de respuesta del emoticón del mensaje(ThumbUp/Favorite/SentimentSatisfied/SentimentDissatisfied/MoodBad/Bolt,null=Ninguno). */
    val reaction: String? = null,
    /** v1.0.30: ID de grupo variante (compartido por múltiples respuestas de asistente en la misma ubicación),null = Mensaje no variante. */
    val variantGroupId: String? = null,
    /** v1.0.30: Número de variante (0-based, posición en el grupo actual). */
    val variantIndex: Int = 0,
    /** v1.0.30: Número total de variantes (número de mensajes dentro del grupo). */
    val variantCount: Int = 1,
    /**
     * P0 árbol de diálogo: El grupo de variantes de preguntas del usuario al que pertenece la variante del asistente. ID(parentGroupId).
     * Se utiliza para montar con precisión las respuestas del asistente a la variante de usuario correspondiente.,Evite volver a intentar entre preguntas/Contaminación del interruptor.
     * ConversationTreeBuilder infiere cronológicamente los nodos principales cuando los datos antiguos son nulos.
     */
    val parentGroupId: String? = null,
    /**
     * v2.5.2: Referencia explícita del mensaje principal (v94 Ya existe un campo DB, que se agrega aquí para ser transportado por la capa UI).
     * El mensaje de visualización de la herramienta apunta al mensaje del asistente correspondiente, de modo que al eliminar el mensaje del asistente, presione la cascada principal para encontrar la tarjeta de la herramienta.
     * No dejes cartas huérfanas.
     */
    val parentMessageId: String? = null,
    /** v1.0.47: Lista de archivos adjuntos de mensajes(Persistencia estructurada de metadatos de archivos sin formato,Provider Ignorar al enviar la solicitud,usar content). */
    val attachments: List<AttachmentRef> = emptyList(),
    /**
     * A5: Metadatos de generación de mensajes(Para visualización de información,Provider Ignorar al enviar la solicitud).
     *
     * Rellenado por ChatViewModel al final de la generación(provider Se prefiere la medición real,Visualización de estimación local de UI cuando falta):
     *  - [durationMs] El tiempo total necesario para generar este mensaje.(milisegundos,Con ciclo de herramienta)
     *  - [promptTokens] provider Entrada medida token(Uso de streamingDelta último valor)
     *  - [completionTokens] provider Salida medida token
     *  - [reasoningTokens] provider razonamiento empírico token(o1/DeepSeek-R1 espera)
     *  - [cachedTokens] provider Caché de solicitud de prueba real**leer** token(Anthropic cache_read / OpenAI cached_tokens / Gemini cachedContentTokenCount)
     *  - [cacheWriteTokens] provider Caché de solicitud de prueba real**escribir** token(solo Anthropic cache_creation;OpenAI/Gemini Sin este artículo, es null)
     * Ambos son nulos, lo que indica que no hay ninguna medición real./No rellenado(Datos antiguos o proveedor no devueltos usage).
     *
     * Compatible con datos antiguos:antes de dividir [cachedTokens] El valor combinado "lectura + escritura" está instalado, no hay [cacheWriteTokens] columnas.
     * Al leer, se conserva el valor original (explicado según "lectura", sin relleno, sin recálculo) y la cantidad de escritura es nula (desconocida).
     */
    val durationMs: Long? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    /**
     * H11: La traducción conserva el texto original. — El mensaje de traducción apunta al mensaje fuente traducido. id(forma de cadena).
     * Cuando no es nula, la interfaz de usuario se proporciona debajo de la burbuja de traducción."Ver texto original"doblar,Se puede comparar con el texto original.
     * Solo para comparación en tiempo real dentro de conversaciones,provider Ignorado al enviar la solicitud.
     */
    val translationSourceId: String? = null,
    /**
     * v2.4.6: Mapeo de traducción para este mensaje(Nombre del idioma → traducción),Conserve el mensaje en sí y no abra ningún otro mensaje del asistente.
     *
     * UI Se muestra debajo del mensaje en el mismo bloque expandible que MOOD/Pensamiento,No ingrese al historial de conversaciones y no participe en variaciones.
     * provider Este campo se ignora al enviar la solicitud.
     */
    val translations: Map<String, String> = emptyMap(),
) {
    /** Deletrear texto sin formato para mostrar(Sin proceso de razonamiento). */
    fun toText(): String = content

    /**
     * v1.80 (L-CORE4): deletrear para resumen/Texto coincidente contextualmente(Contiene proceso de razonamiento).
     * memory abstracto,context Escenarios como el emparejamiento pueden requerir información de razonamiento.
     */
    fun toSummaryText(): String = buildString {
        append(content)
        reasoning?.takeIf { it.isNotBlank() }?.let { append("\n[reasoning]").append(it) }
    }
}

/**
 * Segmentación del contexto consciente de la dependencia de herramientas — Regresar (fuera de la ventana, ventana interior) Dos párrafos.
 *
 * fondo:sencillo `takeLast(N)` posiblemente de assistant(tool_call) con tool(result) cortar entre,
 * Hace que el primer mensaje después del truncamiento sea un resultado de herramienta huérfano o huérfano tool_call,Proveedor de activación HTTP 400.
 * Esta función funciona hacia atrás en el punto tangente.:Si el primer mensaje involucra herramientas(tool_call / tool_result),Luego continúa retrocediendo,
 * El primer mensaje hasta el punto de corte es un mensaje ordinario que no involucra herramientas.(o volver al principio de la lista).
 *
 * La persona que llama puede crear un extracto del historial de la parte que se encuentra fuera de la ventana.(ver [ContextHistoryDigest]),Evite descartar conversaciones en silencio.
 *
 * comportamiento:
 *  - maxSize <= 0 o size <= maxSize:la ventana esta vacia,Todos los mensajes en la ventana.
 *  - El primer mensaje del pointcut es ASSISTANT.toolCalls no vacío o role=TOOL: startIndex-- continuar
 *  - De lo contrario, detenga la reversión.,Dentro de la ventana está subList(startIndex, size)
 *
 * Nota:El número real de mensajes retenidos después de la reversión puede ser ligeramente superior a maxSize(Para mantener la integridad),Nada menos.
 * casos extremos(Toda la historia es una cadena de llamadas de herramientas.)startIndex caerá de nuevo a 0,Todo el historial se muestra en la ventana.
 *
 * @param maxSize El número máximo de mensajes que se espera conservar.(En realidad, puede que sea un poco más)
 */
fun List<UIMessage>.splitContextWindow(maxSize: Int): Pair<List<UIMessage>, List<UIMessage>> {
    if (maxSize <= 0 || this.size <= maxSize) return emptyList<UIMessage>() to this
    var startIndex = this.size - maxSize

    // Ajuste hacia atrás startIndex,Asegúrese de no romper el emparejamiento tool_call/tool_result
    while (startIndex > 0) {
        val current = this[startIndex]
        val hasToolCalls = current.toolCalls?.isNotEmpty() == true
        val isToolResult = current.role == MessageRole.TOOL

        if (hasToolCalls || isToolResult) {
            // Las noticias actuales involucran herramientas.,Continúe para encontrar USUARIO o ASSISTANT(tool_call)
            startIndex--
            continue
        }
        break
    }

    return subList(0, startIndex) to subList(startIndex, size)
}

/**
 * Truncamiento del contexto según la dependencia de la herramienta — [splitContextWindow] vista de ventana(Preservar la semántica de la persona que llama existente).
 *
 * @param maxSize El número máximo de mensajes que se espera conservar.(En realidad, puede que sea un poco más)
 */
fun List<UIMessage>.limitContextWithContext(maxSize: Int): List<UIMessage> = splitContextWindow(maxSize).second
