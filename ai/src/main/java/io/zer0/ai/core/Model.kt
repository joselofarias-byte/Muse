package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * Identificación del modelo. Múltiples modelos bajo el mismo pase de Proveedor [id] distinguir,
 * Complete OpenAI al llamar `model` campo.
 *
 * Phase 8.1 Expandir: agregar [abilities] / [tools] / [inputModalities] / [outputModalities] campo,
 * Escrito de forma independiente.
 * Se utiliza para declarar capacidades del modelo.(llamada de herramienta/razonamiento/Visión),Deje que la interfaz de usuario y el proveedor decidan qué funciones están disponibles en consecuencia.
 *
 * @param abilities Conjunto de capacidades del modelo(Un conjunto vacío significa no declarado,conservador"No compatible"Ten todo en cuenta)
 * @param tools Declaración de herramienta incorporada(como Géminis Search/UrlContext/ImageGeneration)
 * @param inputModalities modo de entrada(Predeterminado ["text"]);modelo visual plus "image"
 * @param outputModalities Modo de salida(Predeterminado ["text"]);modelo de dibujo más "image"
 */
@Serializable
data class Model(
    val id: String,
    val name: String = id,
    val providerId: String,
    val maxOutputTokens: Int? = null,
    /**
     * v0.45: Tamaño de la ventana de contexto del modelo(token numero).
     *
     * Utilizado para la visualización de capas de UI"El contexto ocupa un pequeño círculo."(Relación token de conversación actual/ventana de contexto).
     * null o 0 para desconocido,UI Los anillos de ocupación no se muestran.
     * Compatible con serialización:El antiguo JSON vuelve al valor predeterminado de nulo cuando este campo no está presente.
     */
    val contextWindow: Int? = null,
    val supportsVision: Boolean = false,
    val supportsStreaming: Boolean = true,
    val supportsVideo: Boolean = false,
    val abilities: Set<ModelAbility> = emptySet(),
    val tools: Set<BuiltInTool> = emptySet(),
    val inputModalities: Set<String> = setOf("text"),
    val outputModalities: Set<String> = setOf("text"),
    /**
     * v1.0.4: Declaración de capacidad visual(grounding Posicionamiento de coordenadas + formato de salida).
     *
     * Cuando el modelo admite el posicionamiento de objetos en la imagen.(Marco de coordenadas de retorno)establecido en no cuando null,VisionBridge Tomará el camino estructurado+primitivos.,
     * Deje que los modelos visuales describan más que solo imágenes,También devuelve coordenadas normalizadas.,El modelo de texto plano puede basarse en la teoría de coordenadas"Haga clic [320,240]".
     *
     * null o grounding=false Indica que el modelo solo puede describir imágenes.(note-only camino).
     */
    val visionCapabilities: VisionCapabilities? = null,
    /**
     * v1.0.53: Fuente de datos del modelo y anotación de credibilidad,Deje que la interfaz de usuario avise al usuario"No verificado/Campo de excepción".
     *
     * - [ModelVerification.VERIFIED]: Acceda al documento de especificaciones local(KnownModels),Calibrado en campo,Puede usarse con confianza
     * - [ModelVerification.UNVERIFIED]: Documento local perdido,Adopte completamente los datos de retorno ascendentes,Los campos pueden ser incorrectos
     * - [ModelVerification.SUSPICIOUS]: Llegando al documento local pero hay una excepción obvia en el retorno ascendente(Como por ejemplo contextWindow=0,
     *   Los modelos de solo texto están etiquetados vision),Sobrescrito con datos locales,pero le indica al usuario que la declaración ascendente es sospechosa
     *
     * Predeterminado UNVERIFIED,por [ModelRegistry.enhanceModel] Se rellena en el proceso listModels.
     */
    val verification: ModelVerification = ModelVerification.UNVERIFIED,
    /**
     * v2.6.1: Si el usuario ha editado explícitamente el modelo en la página de configuración.
     *
     * Cuando se establece en verdadero [io.zer0.ai.registry.ModelRegistry.enhanceModel] Regresar directamente como está,
     * No más registro/El documento de especificaciones anula la configuración del usuario —— Reparación“Encienda el interruptor multimodal y vuelva a cambiarlo después de guardar.”.
     */
    val userEdited: Boolean = false,
) {
    /** Provider Referencias de unión estable a modelos para evitar el enrutamiento de cadenas entre proveedores de modelos con el mismo nombre. */
    fun reference(): ModelReference = ModelReference.of(this)

    /**
     * Juicio conveniente:Ya sea para admitir llamadas a herramientas.
     * v1.80 (M-CORE6): conjunto vacío(No declarado)Cambiar a conservador false,Evite enviar el campo de herramientas a modelos no compatibles, lo que generará 400.
     */
    fun supportsToolCalling(): Boolean = ModelAbility.TOOL in abilities

    /** Juicio conveniente:Si el razonamiento está respaldado.v1.80: Lo mismo M-CORE6,El conjunto vacío es conservador falso. */
    fun supportsReasoning(): Boolean = ModelAbility.REASONING in abilities

    /** Juicio conveniente:Ya sea para admitir información visual. */
    fun supportsVisionInput(): Boolean = supportsVision || "image" in inputModalities

    /** Juicio conveniente:Ya sea para admitir la salida de imágenes(modelo de dibujo). */
    fun supportsImageOutput(): Boolean = "image" in outputModalities

    /** Juicio conveniente:Ya sea para admitir salida de video. */
    fun supportsVideoOutput(): Boolean = supportsVideo

    /** v1.0.4: Juicio conveniente:Ya sea para apoyar la visión grounding(Posicionamiento coordinado). */
    fun supportsVisionGrounding(): Boolean = visionCapabilities?.grounding == true

    /**
     * v2.7: El modelo está en el catálogo de capacidades.(model-catalog)El formato de pensamiento indicado aquí.
     *
     * Campo de directorio(`ModelCatalogEntry.compat.thinkingFormat`)Resulta que solo analiza pero no consume.,El comportamiento está enteramente determinado por
     * [ProviderCompatRules] La tabla codificada del host lo determina. Aquí, cuelgue la conclusión del directorio para [Model] en,dejar sostener el modelo
     * La persona que llama y el período de solicitud obtienen la misma respuesta —— Compartido por ambas partes [ProviderCompatRules.catalogThinkingFormat]
     * un mapa de vocabulario,Los calibres no se repiten.
     *
     * null "El catálogo no dice(O el vocabulario no se puede mapear)",En este momento, prevalecerá la tabla codificada.,De lo contrario, volverá al valor predeterminado.
     * prioridad completa:[Directorio] > [tabla codificada] > [Valor predeterminado].
     */
    val catalogThinkingFormat: ThinkingFormat?
        get() = ProviderCompatRules.catalogThinkingFormat(id, providerId)
}

/**
 * v1.0.4: Declaración de capacidad visual.
 *
 * @param grounding Ya sea para admitir el posicionamiento de objetos en imágenes(Marco de coordenadas de retorno)
 * @param outputFormat Formato de salida de coordenadas,Determinar los requisitos de palabras rápidas y la lógica de normalización.:
 *  - "gemini": box_2d: [ymin, xmin, ymax, xmax](Gemini Nativo yxyx,normalización 0-1000)
 *  - "qwen": bbox_2d: [x1, y1, x2, y2] + point_2d: [x, y](Qwen-VL Nativo)
 *  - "anchor": visual_anchors traer role/center/box(Claude computer-use estilo)
 *  - "muse-box": box: [x1, y1, x2, y2](Formato de coordenadas unificado,xyxy + norm-1000)
 *  - null: no especificado,prensa "muse-box" Manejo de formato predeterminado
 */
@Serializable
data class VisionCapabilities(
    val grounding: Boolean = false,
    val outputFormat: String? = null,
)

/**
 * Enumeración de capacidades del modelo.
 * - [TOOL]: Llamadas a funciones de soporte(function calling)
 * - [REASONING]: Cadena de razonamiento de soporte(thinking / o1 Serie)
 */
@Serializable
enum class ModelAbility { TOOL, REASONING }

/**
 * Enumeración de herramientas incorporadas(Compatible de forma nativa con el proveedor,no function calling).
 * Se utiliza principalmente para herramientas nativas de Gemini.
 * - [SEARCH]: Google Search(Gemini grounding)
 * - [URL_CONTEXT]: URL Extracción de contenido(Gemini)
 * - [IMAGE_GENERATION]: Dibujo incorporado(Gemini Imagen)
 */
@Serializable
enum class BuiltInTool { SEARCH, URL_CONTEXT, IMAGE_GENERATION }

/**
 * v1.0.53: Anotación de credibilidad de datos del modelo.
 *
 * Se utiliza para que la interfaz de usuario le pregunte al usuario si los campos del modelo están calibrados localmente.:
 * - [VERIFIED]: Acceda al documento de especificaciones local(KnownModels),Los campos clave están calibrados
 * - [UNVERIFIED]: Documento local perdido,Adopte completamente los datos de retorno ascendentes
 * - [SUSPICIOUS]: Acceda al documento local pero hay una excepción obvia en la declaración ascendente,Sobrescrito con datos locales
 *
 * por [io.zer0.ai.registry.ModelRegistry.enhanceModel] Completar en el proceso listModels,
 * UI Puede agregar una marca de esquina en la página de selección de modelo en consecuencia.(Como por ejemplo"Calibrado"/"No verificado"/"Las afirmaciones upstream son sospechosas").
 */
@Serializable
enum class ModelVerification {
    VERIFIED,
    UNVERIFIED,
    SUSPICIOUS,
}
