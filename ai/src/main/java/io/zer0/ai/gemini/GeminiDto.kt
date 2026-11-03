package io.zer0.ai.gemini

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Google Gemini API Objeto de transferencia de datos.
 *
 * API: generateContent (sin transmisión) / streamGenerateContent?alt=sse (transmisión)
 *  - URL Formato: /v1beta/models/{model}:generateContent?key=...
 *  - Certificación: API key como query param
 *  - systemInstruction es un campo de nivel superior(parts matriz)
 *  - transmisión: usar alt=sse estándar de devolución SSE,Cada dato: es un GenerateContentResponse
 *  - incremento de contenido: candidates[0].content.parts[0].text
 *
 * Phase 8.6 Expandir:
 *  - GeminiPart apoyo inlineData(Entrada de imagen/salida de imagen)
 *  - GeminiGenerationConfig apoyo responseModalities(Activar el modo de dibujo)
 *  - responseModalities = ["TEXT", "IMAGE"] Deje que Gemini devuelva contenido mixto de texto e imagen
 *
 * H-GEM2/H-GEM4 Expandir(v1.80):
 *  - tools / functionDeclarations / functionCall / functionResponse(llamada de función)
 *  - safetySettings(Configuración del umbral de seguridad)
 *  - promptFeedback.blockReason(Intercepción de seguridad de nivel rápido)
 */

@Serializable
internal data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
    /** H-GEM2: lista de definición de herramientas,Habilitar la llamada a funciones. */
    val tools: List<GeminiTool>? = null,
    /** H-GEM4: Configuración del umbral de seguridad(Opcional,Controlar el nivel de interceptación de cada categoría de riesgo.). */
    val safetySettings: List<GeminiSafetySetting>? = null,
)

@Serializable
internal data class GeminiContent(
    val role: String, // "user" / "model"(Gemini usar "model" en vez de "assistant")
    val parts: List<GeminiPart>,
)

/**
 * Part Texto de soporte e imágenes.(inlineData), archivos(fileData), llamada de función(functionCall), respuesta de la función(functionResponse).
 *
 * - texto plano: Solo completa [text]
 * - Entrada de imagen/salida: Solo completa [inlineData]
 * - Archivos grandes como videos.: Solo completa [fileData](V-GEM1,Consulte la API de archivos después de cargar uri)
 * - Llamadas a herramientas iniciadas por el modelo: Solo completa [functionCall](H-GEM2)
 * - Resultados de ejecución de la herramienta de relleno: Solo completa [functionResponse](H-GEM3)
 * - La misma pieza no contendrá múltiples cargas al mismo tiempo.(Gemini Requisitos del acuerdo)
 *
 * Al serializar,Los campos vacíos utilizan valores predeterminados(cadena vacía),Ignorado por el servidor Gemini.
 */
@Serializable
internal data class GeminiPart(
    val text: String = "",
    val inlineData: GeminiInlineData? = null,
    /** V-GEM1: Archivos de referencia cargados por Files API(vídeo/Audio y otros archivos grandes). */
    val fileData: GeminiFileData? = null,
    /** H-GEM2: Devuelto cuando se llama a la herramienta de decisión del modelo. */
    val functionCall: GeminiFunctionCall? = null,
    /** H-GEM3: Rellene los resultados de ejecución de la herramienta al modelo. */
    val functionResponse: GeminiFunctionResponse? = null,
)

@Serializable
internal data class GeminiInlineData(
    // L-GEM9: mimeType Predeterminado image/png
    val mimeType: String = "image/png",
    val data: String, // base64 codificación(Ninguno dato: prefijo)
)

/**
 * V-GEM1: Haga referencia al archivo cargado utilizando la API de archivos.
 *
 * proceso de carga:
 *  1. POST https://generativelanguage.googleapis.com/upload/v1beta/files?key=...
 *     (multipart: metadata + bytes de archivo) → Regresar file.uri / file.name
 *  2. GET https://generativelanguage.googleapis.com/v1beta/files/{name}?key=...
 *     encuesta hasta state=ACTIVE,Se puede hacer referencia a los archivos mediante generateContent
 *  3. Usado en parte [fileUri] Cotización(fileData.fileUri == carga devuelta file.uri)
 *
 * Solo compatible con API de lenguaje generativo;Vertex AI Utilice inlineData o GCS uri.
 */
@Serializable
internal data class GeminiFileData(
    val mimeType: String,
    val fileUri: String,
)

/** H-GEM2: Solicitud de llamada a función iniciada por el modelo. */
@Serializable
internal data class GeminiFunctionCall(
    val name: String,
    val args: JsonElement? = null,
)

/** H-GEM3: Rellene los resultados de la ejecución de la función en el modelo. */
@Serializable
internal data class GeminiFunctionResponse(
    val name: String,
    val response: JsonElement? = null,
)

/** H-GEM2: Definición de herramienta(Gemini Envuelva la matriz functionDeclarations con una sola herramienta). */
@Serializable
internal data class GeminiTool(
    /** Gemini generateContent herramienta de función; vacío cuando se busca de forma nativa. */
    val functionDeclarations: List<GeminiFunctionDeclaration>? = null,
    /** Google Search grounding Herramientas incorporadas. */
    @SerialName("google_search")
    val googleSearch: GeminiGoogleSearch? = null,
)

@Serializable
internal class GeminiGoogleSearch

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    /** parámetros JSON Schema(OpenAI Formatos compatibles,Resuelto por el Proveedor como JsonElement). */
    val parameters: JsonElement? = null,
)

/** H-GEM4: Configuración de umbrales de seguridad para categorías de riesgo individuales. */
@Serializable
internal data class GeminiSafetySetting(
    val category: String,
    val threshold: String,
)

@Serializable
internal data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    /** Phase 8.5 Reparación: Gemini 2.5 Serie de reflexiones sobre la elaboración de presupuestos(thinkingConfig.thinkingBudget). */
    val thinkingConfig: GeminiThinkingConfig? = null,
    /**
     * Phase 8.6: Modalidad de respuesta.
     *  - ["TEXT"]: Predeterminado,texto plano
     *  - ["TEXT", "IMAGE"]: texto + imagen(Gemini 2.5 Flash Image / Nano Banana espera)
     *  - ["IMAGE"]: Fotos puras(Imagen via generateContent interfaz)
     *
     *  M-GEM12: Utilice siempre letras mayúsculas.
     */
    val responseModalities: List<String>? = null,
)

@Serializable
internal data class GeminiThinkingConfig(
    /** Especifique el presupuesto de pensamiento en tokens.Gemini 2 Generaciones y anteriores utilizan este campo. */
    val thinkingBudget: Int? = null,
    /**
     * Especifique la profundidad del pensamiento por equipo.Gemini 3 Sólo este campo se puede utilizar para generación (incluido 4.ª generación/Argón).
     * Pasando pensando, el presupuesto volverá directamente. 400“Thinking budget is not supported for this model”.
     */
    val thinkingLevel: String? = null,
)

@Serializable
internal data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    /** H-GEM4: Comentarios de seguridad de nivel rápido(Cuando se incluye blockReason, significa que la solicitud se intercepta en su totalidad.). */
    val promptFeedback: GeminiPromptFeedback? = null,
    /** v1.0.53 Phase 3: token Dosis(Gemini usageMetadata). */
    val usageMetadata: GeminiUsageMetadata? = null,
)

/** v1.0.53 Phase 3: Gemini token Dosis(Alineación Google API usageMetadata). */
@Serializable
internal data class GeminiUsageMetadata(
    @SerialName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int = 0,
    @SerialName("totalTokenCount") val totalTokenCount: Int = 0,
    /** A5: acierto de caché de contexto token(Gemini 2.5+ de usageMetadata.cachedContentTokenCount). */
    @SerialName("cachedContentTokenCount") val cachedContentTokenCount: Int = 0,
)

/** H-GEM4: Comentarios de seguridad de nivel rápido. */
@Serializable
internal data class GeminiGroundingMetadata(
    val groundingChunks: List<GeminiGroundingChunk> = emptyList(),
)

@Serializable
internal data class GeminiGroundingChunk(
    val web: GeminiGroundingWeb? = null,
)

@Serializable
internal data class GeminiGroundingWeb(
    val uri: String? = null,
    val title: String? = null,
)

@Serializable
internal data class GeminiPromptFeedback(
    val blockReason: String? = null,
)

@Serializable
internal data class GeminiCandidate(
    val content: GeminiContent? = null,
    /** Gemini Grounding with Google Search Metadatos de referencia devueltos. */
    val groundingMetadata: GeminiGroundingMetadata? = null,
    val finishReason: String? = null,
    /** v1.80 (L-GEM3): Calificación de seguridad del candidato(Opcional,Utilizado para el control de riesgos./Pantalla de depuración). */
    val safetyRatings: List<GeminiSafetyRating>? = null,
)

/** v1.80 (L-GEM3): Clasificaciones de seguridad para categorías de riesgo individuales(nivel de candidato). */
@Serializable
internal data class GeminiSafetyRating(
    val category: String? = null,
    val probability: String? = null,
    /** M-L-GEM3: Si el modelo fue bloqueado para esta categoría(true El contenido de esta categoría no se muestra cuando). */
    val blocked: Boolean? = null,
)

// ── error ──

@Serializable
internal data class GeminiErrorBody(
    val error: GeminiErrorDetail? = null,
)

@Serializable
internal data class GeminiErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

/**
 * GET /v1beta/models respuesta.models[].name en forma de "models/gemini-1.5-pro".
 * v1.80 (M-GEM5): Agregue nextPageToken para admitir la extracción de paginación.
 */
@Serializable
internal data class GeminiModelsResponse(
    val models: List<GeminiModelInfo> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
internal data class GeminiModelInfo(
    val name: String = "",
    val displayName: String? = null,
    val supportedGenerationMethods: List<String>? = null,
)

// ── V-GEM1: Files API(Sube archivos grandes como videos.)──

/**
 * POST /upload/v1beta/files respuesta.
 *
 * Volver después de una carga exitosa [file] Recursos(Contiene [name] para el estado de la votación,[uri] utilizado para generar referencia de contenido).
 */
@Serializable
internal data class GeminiFileUploadResponse(
    val file: GeminiFileResource? = null,
)

/**
 * Files API recursos.
 *
 * - [name] en forma de "files/abc123",usado para GET /v1beta/files/{name} Estado de sondeo
 * - [uri] en forma de "https://generativelanguage.googleapis.com/v1beta/files/abc123",
 *   como [GeminiFileData.fileUri] Referenciado en generar contenido
 * - [state] Después de cargar, el valor inicial es PROCESSING,Puede ser utilizado por generateContent solo después de cambiar a ACTIVO
 * - [mimeType] Especificado al cargar MIME(Como por ejemplo video/mp4)
 */
@Serializable
internal data class GeminiFileResource(
    val name: String = "",
    val uri: String = "",
    val mimeType: String = "",
    val state: String = "",
    val error: GeminiErrorDetail? = null,
)

/** v1.0.53 Phase 3: Gemini usageMetadata -> [io.zer0.ai.core.UsageTokens]. */
internal fun GeminiUsageMetadata.toUsageTokens(): io.zer0.ai.core.UsageTokens = io.zer0.ai.core.UsageTokens(
    promptTokens = promptTokenCount,
    completionTokens = candidatesTokenCount,
    reasoningTokens = 0, // Gemini No se cuentan por separado reasoning tokens
    cachedTokens = cachedContentTokenCount,
)
