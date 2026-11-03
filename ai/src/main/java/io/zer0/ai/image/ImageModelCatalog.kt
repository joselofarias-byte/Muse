package io.zer0.ai.image

/**
 * v0.36: Metadatos del modelo de dibujo.
 *
 * Describe el tamaño, la calidad, el estilo, el dibujo y las capacidades de dibujo admitidas por un modelo de dibujo.,
 * Se utiliza para el filtrado de opciones de la interfaz de usuario y la verificación de parámetros de la capa de servicio.
 */
data class ImageModel(
    /** modelo ID,Como por ejemplo dall-e-2 / dall-e-3 / gpt-image-1. */
    val id: String,
    /** Nombre para mostrar. */
    val displayName: String,
    /** proveedor,Como por ejemplo OpenAI / DashScope / MiniMax. */
    val provider: String,
    /** Lista de tamaños admitidos. */
    val supportedSizes: List<String>,
    /** Opciones de calidad admitidas. */
    val supportedQualities: List<String> = emptyList(),
    /** Opciones de estilo admitidas. */
    val supportedStyles: List<String> = emptyList(),
    /** Ya sea para apoyar a Tushengtu(/images/edits). */
    val supportsReferenceImage: Boolean = false,
    /** Número máximo de desoves n. */
    val maxN: Int = 1,
    /** Tamaño predeterminado. */
    val defaultSize: String = supportedSizes.firstOrNull() ?: "1024x1024",
    /** Calidad predeterminada. */
    val defaultQuality: String = supportedQualities.firstOrNull() ?: "",
    /** Estilo predeterminado. */
    val defaultStyle: String = supportedStyles.firstOrNull() ?: "",
    /** Ya sea para apoyar response_format=b64_json. */
    val supportsB64Json: Boolean = true,
    /**
     * H-IMG2: Si se admite el parámetro Response_format.
     * dall-e-2 / dall-e-3 = true;gpt-image-1 = false(Este parámetro será rechazado por el servidor.).
     */
    val supportsResponseFormatParam: Boolean = true,
    /**
     * M-IMG8: Imagen de salida tipo MIME(Usado para la ortografía b64_json data URI).
     * La salida actual de los modelos de dibujo de la serie OpenAI es PNG.
     */
    val outputMime: String = "image/png",
)

/**
 * v0.36: Directorio de modelo de dibujo preestablecido.
 *
 * Mantener una lista de modelos de dibujo comúnmente utilizados.,UI Mostrar opciones en consecuencia,
 * Evite que los usuarios ingresen manualmente los ID de los modelos.
 */
object ImageModelCatalog {
    /**
     * v1.136: ID de modelo de dibujo predeterminado.
     *
     * Cuando la persona que llama no pasa un modelo y ProviderSpecificConfig.OpenAI.imageModel no está configurado,
     * Recurrir a este modelo,Asegúrese de que el cuerpo de la solicitud siempre lleve el campo modelo.
     */
    const val DEFAULT_MODEL_ID = "dall-e-3"

    /** Metadatos del modelo de dibujo predeterminado. */
    fun defaultModel(): ImageModel = findById(DEFAULT_MODEL_ID) ?: models.first()

    // Nota:wanx-v1(Tongyi Wanxiang)y minimax-image-01 han sido eliminados —
    // Los dos protocolos son incompatibles. OpenAI /images/generations,Requiere adaptador independiente.
    // v1.0.18: agnes-image-2.1-flash Acceso AgnesImageProvider,Ya no tomaremos el camino de compatibilidad con OpenAI.
    private val models = listOf(
        ImageModel(
            id = "dall-e-2",
            displayName = "DALL-E 2",
            provider = "OpenAI",
            supportedSizes = listOf("256x256", "512x512", "1024x1024"),
            supportedQualities = emptyList(),
            supportedStyles = emptyList(),
            supportsReferenceImage = true,
            maxN = 10,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
            // L-IMG3: El tamaño predeterminado cambió a 1024x1024(Originalmente, volvió al primer 256x256, que era demasiado pequeño.)
            defaultSize = "1024x1024",
        ),
        ImageModel(
            id = "dall-e-3",
            displayName = "DALL-E 3",
            provider = "OpenAI",
            supportedSizes = listOf("1024x1024", "1792x1024", "1024x1792"),
            supportedQualities = listOf("standard", "hd"),
            supportedStyles = listOf("vivid", "natural"),
            supportsReferenceImage = false,
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
        ),
        ImageModel(
            id = "gpt-image-1",
            displayName = "GPT Image 1",
            provider = "OpenAI",
            supportedSizes = listOf(
                "1024x1024",
                "1024x1536",
                "1536x1024",
                "1536x1536",
            ),
            supportedQualities = listOf("high", "medium", "low", "auto"),
            supportedStyles = emptyList(),
            // M-IMG4: gpt-image-1 Imágenes de referencia de soporte(/images/edits)
            supportsReferenceImage = true,
            maxN = 1,
            supportsB64Json = true,
            // H-IMG2: gpt-image-1 El parámetro Response_Format no es compatible.
            supportsResponseFormatParam = false,
        ),
        ImageModel(
            // v1.0.18: Agnes AI modelo de imagen,ir AgnesImageProvider(Adaptador independiente,No compatible con OpenAI).
            // Dimensiones expresadas como proporciones.(AgnesImageProvider.SIZE_RATIOS se asignará a valores de píxeles);
            // Imágenes de referencia de soporte(Pase en extra_body.image);Retorno sincrónico;B64_json predeterminado.
            id = "agnes-image-2.1-flash",
            displayName = "Agnes Image 2.1 Flash",
            provider = "Agnes",
            supportedSizes = listOf("1:1", "16:9", "21:9", "9:16", "4:3", "3:4", "3:2", "2:3"),
            supportedQualities = emptyList(),
            supportedStyles = emptyList(),
            supportsReferenceImage = true,
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
            // Predeterminado 3:2
            defaultSize = "3:2",
            // Agnes salida PNG
            outputMime = "image/png",
        ),
        ImageModel(
            // v1.0.18 Seguimiento (B14-2): Gemini 2.5 Flash Image(Nano Banana),
            // ir GeminiImageProvider(Generar contenido nativo + respuestaModalidades salida de imagen).
            // Imágenes de referencia de soporte(entrada como parte inlineData);Retorno sincrónico;response_format El parámetro no es aplicable.
            id = "gemini-2.5-flash-image",
            displayName = "Gemini 2.5 Flash Image",
            provider = "Google",
            supportedSizes = listOf("1024x1024"),
            supportedQualities = emptyList(),
            supportedStyles = emptyList(),
            supportsReferenceImage = true,
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = false,
            outputMime = "image/png",
        ),
    )

    /** Todos los modelos preestablecidos. */
    fun all(): List<ImageModel> = models

    /** Encuentra modelos por ID. */
    fun findById(id: String?): ImageModel? = models.find { it.id == id }

    /** Determine si hay un ID de modelo en el directorio. */
    fun contains(id: String?): Boolean = id != null && models.any { it.id == id }

    /** Obtener el tamaño predeterminado del modelo.;Si no lo encuentra, recurra. */
    fun defaultSizeFor(id: String?, fallback: String = "1024x1024"): String {
        return findById(id)?.defaultSize ?: fallback
    }

    /**
     * v0.36-fix: Analizar los metadatos del modelo de dibujo.
     *
     * Para modelos conocidos en el catálogo.(Como por ejemplo dall-e-3 / gpt-image-1)Devolver datos del directorio;
     * Para modelos definidos por el usuario o no recopilados,Devuelve un conjunto de valores predeterminados relajados,
     * para que aún pueda mostrar el tamaño normal/calidad/opciones de estilo y vaya a Image API.
     */
    fun resolveById(id: String?): ImageModel? {
        if (id.isNullOrBlank()) return null
        return findById(id) ?: ImageModel(
            id = id,
            displayName = id,
            provider = "",
            supportedSizes = listOf(
                "1024x1024",
                "1792x1024",
                "1024x1792",
                "1024x1536",
                "1536x1024",
                "1536x1536",
            ),
            supportedQualities = listOf("standard", "hd", "high", "medium", "low", "auto"),
            supportedStyles = listOf("vivid", "natural"),
            supportsReferenceImage = false,
            // L-IMG10: El modelo desconocido maxN toma un valor conservador 1(Originalmente 10 excedentes de pago por evento fáciles de activar)
            maxN = 1,
            supportsB64Json = true,
            supportsResponseFormatParam = true,
        )
    }
}
