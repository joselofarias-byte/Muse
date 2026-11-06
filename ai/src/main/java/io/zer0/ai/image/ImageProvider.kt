package io.zer0.ai.image

/**
 * v1.0.18: Interfaz abstracta del proveedor de generación de imágenes.
 *
 * registro de adaptadores(AdapterRegistry)modo,dejar OpenAI / Agnes / Gemini Espera a que cada uno se realice,
 * Evite dibujos fuertemente vinculados al protocolo OpenAI.ImageService Pase [ImageProviderRegistry] Elige el correcto provider,
 * Luego, confíelo para que complete la llamada HTTP real y el análisis de la respuesta.
 *
 * Puntos de diseño:
 *  - Sincronizar tareas: [submit] directamente en [ImageSubmitResult.images] Devolver resultados en,[taskId] es nulo.
 *  - Tareas asincrónicas: [submit] Regresar [ImageSubmitResult.taskId],Encuestado por la persona que llama [poll] Hasta el estado final.
 *  - Imagen de referencia(Tu Sheng Tu): Pase [ImageGenRequest.referenceImages] entrante(base64 o URL),
 *    provider Decide cómo codificar(multipart / extra_body.image espera).
 */
interface ImageProvider {
    /** Identificación del proveedor,Como por ejemplo "openai" / "agnes" / "gemini". */
    val providerId: String

    /** Ya sea para apoyar a Tushengtu(Imagen de referencia). */
    val supportsImageEdit: Boolean

    /** Ya sea para admitir tareas asincrónicas(Necesito encuestar). */
    val supportsAsync: Boolean

    /**
     * Enviar tarea de generación de imágenes.
     *
     * @param request Generar solicitud(prompt / model / size / Imágenes de referencia, etc.)
     * @return La tarea de sincronización regresa directamente. [ImageSubmitResult.images];Devoluciones de tareas asincrónicas [ImageSubmitResult.taskId].
     */
    suspend fun submit(request: ImageGenRequest): ImageSubmitResult

    /**
     * Consultar el estado de la tarea asincrónica(solo [supportsAsync]=true llamado cuando).
     *
     * @param taskId [submit] Tareas devueltas ID
     * @return Contiene [ImagePollResult.status] con(estado final)Lista de imágenes.
     */
    suspend fun poll(taskId: String): ImagePollResult
}

/**
 * Solicitud de generación de imágenes(Para [ImageProvider] parámetros unificados de).
 *
 * [size] Ambos admiten valores de píxeles("1024x1024")También soporta proporciones.("1:1"),Está mapeado por cada proveedor.
 * [referenceImages] Los elementos pueden ser base64(Ninguno dato: prefijo)o http/https/file URL,provider Analizar bajo demanda.
 */
data class ImageGenRequest(
    val prompt: String,
    val model: String,
    val size: String, // "1024x1024" o "1:1" proporción
    val quality: String = "standard",
    val style: String = "",
    val n: Int = 1,
    /** Imagen de referencia(base64 o URL),Activa la ruta del gráfico cuando no está vacía. */
    val referenceImages: List<String> = emptyList(),
    /** Formato de devolución esperado:"b64_json" / "url";Una cadena vacía significa que no es obligatorio(provider a tu propia discreción). */
    val responseFormat: String = "b64_json",
    /** Contexto adicional pasado de forma transparente al proveedor.(apiKey / baseUrl / specific espera),Montado por ImageService. */
    val config: io.zer0.ai.core.ProviderConfig? = null,
)

/**
 * [submit] resultado:Las tareas de sincronización incluyen directamente images,Las tareas asincrónicas contienen taskId.
 */
data class ImageSubmitResult(
    /** Tareas asincrónicas ID(solo [isAsync]=true a veces valioso). */
    val taskId: String? = null,
    /** Lista de imágenes de resultados de sincronización(solo [isAsync]=false a veces valioso). */
    val images: List<GeneratedImage> = emptyList(),
    val isAsync: Boolean = false,
)

/**
 * [poll] resultados.
 */
data class ImagePollResult(
    /** Estado de la tarea:PENDING / SUCCESS / FAILED. */
    val status: PollStatus,
    val images: List<GeneratedImage> = emptyList(),
    val errorMessage: String? = null,
)

enum class PollStatus { PENDING, SUCCESS, FAILED }

/**
 * Generar imagen de resultado.
 * - [base64]: fotos base64(Ninguno dato: prefijo);con [url] Al menos uno de ellos no está vacío.
 * - [url]: Las imágenes son accesibles. URL(http/https).
 */
data class GeneratedImage(
    val base64: String? = null,
    val url: String? = null,
)
