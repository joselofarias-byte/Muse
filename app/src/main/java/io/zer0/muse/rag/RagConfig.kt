package io.zer0.muse.rag

import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import kotlinx.serialization.Serializable

/**
 * v1.54: RAG configuración.
 *
 * v1.133 Expandir:
 *  - [mmrLambda]:MMR Reponderación de la diversidad(0=pura diversidad,1=pura similitud,Predeterminado 0.5)
 *  - [hybridEnabled]:Si se debe habilitar la recuperación híbrida vectorial BM25+(RRF Fusión)
 *  - [rerankEnabled]:Si se debe habilitar el reordenamiento entre codificadores
 *  - [rerankProvider]:Reordenar tipos de proveedores(cohere/jina/local)
 *  - [tokenBudget]:Inyectar presupuesto de token(0 = sin limite;>0 = Acumular en orden descendente de similitud,Detener si se excede el presupuesto)
 *  - [markdownAware]:Si se debe detectar la estructura Markdown al fragmentar(bloque de código/Título/mesa)
 *  - [chunkByToken]:Ya sea para dividir en trozos por número simbólico(true=prensa [chunkSize] token,false=por personaje)
 *
 * persistir en DataStore(rag_config_json key),Gestionado por SettingsRepository.
 */
@Serializable
data class RagConfig(
    /** RAG interruptor principal(inyección automática). Después del cierre, la herramienta Knowledge_search aún se puede buscar manualmente en la base de conocimientos. */
    val enabled: Boolean = true,
    /**
     * v1.0.92: Recuperación de archivos adjuntos de sesión(De experimental a regular,Activado de forma predeterminada).
     *
     * Después de abrir,Los documentos cargados en la sesión se analizarán e indexarán en la base de datos de búsqueda.(knowledge_search Su contenido puede ser afectado.);
     * Sesión de enlace del ciclo de vida del índice,Se eliminará cuando se limpie la sesión.
     */
    val sessionAttachmentEnabled: Boolean = true,
    /**
     * Embedding Fuente.
     *
     * v1.0.53: El valor predeterminado se cambia a [EmbeddingSource.LOCAL_KEYWORD] — Utilice la búsqueda de palabras clave locales al abrir la aplicación por primera vez,
     * Evite proveedores agregados por usuarios que no proporcionen modelos integrados(Como por ejemplo tokenrhythm.studio)
     * Como resultado, se sigue informando el error MODEL_NOT_AVAILABLE cuando se inyecta RAG.
     *
     * Después de agregar la API, los usuarios pueden cambiar manualmente a CLOUD,UI No ocultes esta opción.
     */
    val embeddingSource: EmbeddingSource = EmbeddingSource.LOCAL_KEYWORD,
    /**
     * Utilizado por la incrustación en la nube Provider ID(Corresponde al repositorio de configuración ProviderConfig.id).
     * Una cadena vacía indica que se utiliza el proveedor de chat actualmente activado.
     */
    val cloudProviderId: String = "",
    /** Nombre del modelo de incrustación en la nube(Una cadena vacía indica que se utiliza el modelo de incrustación predeterminado del proveedor.). */
    val cloudModel: String = "",
    /** Ruta del archivo del modelo ONNX local(filesDir ruta relativa bajo). */
    val localModelPath: String = "",
    /** Inyecte automáticamente los números top-k recuperados. */
    val topK: Int = 3,
    /** Umbral de similitud para inyección automática(0-1,Ninguna inyección por debajo de este valor). */
    val threshold: Float = 0.35f,
    /** tamaño objetivo del fragmento([chunkByToken]=true el tiempo es el número de fichas,false el tiempo es el número de caracteres). */
    val chunkSize: Int = 500,
    /** Trozos superpuestos(Lo mismo [chunkSize] unidad). */
    val chunkOverlap: Int = 50,

    // ── v1.133 Nuevo ──
    /** MMR Reponderación de la diversidad(0=pura diversidad,1=pura similitud,Predeterminado 0.5). */
    val mmrLambda: Float = 0.5f,
    /** Si se debe habilitar la recuperación híbrida vectorial BM25+(RRF Fusión). */
    val hybridEnabled: Boolean = false,
    /** Búsqueda híbrida BM25 peso de vía(RRF Para fusión,Predeterminado 1.0). */
    val hybridBm25Weight: Float = 1.0f,
    /** Pesos de ruta de vector de recuperación híbrida(RRF Para fusión,Predeterminado 1.0). */
    val hybridVectorWeight: Float = 1.0f,
    /** Ya sea para habilitar el reordenamiento entre codificadores. */
    val rerankEnabled: Boolean = false,
    /** Reordenar tipos de proveedores(cohere/jina/local,Una cadena vacía indica presionar LocalRerankProvider). */
    val rerankProvider: String = "",
    /**
     * P2-6(informe de auditoría): Configuración de reordenamiento en la nube.
     *
     * cuando [rerankProvider] para "remote" activado. El protocolo es compatible con OpenAI.
     * `POST {baseUrl}/rerank`,SiliconFlow / Jina / Cohere Todo se puede reutilizar.
     */
    val rerankBaseUrl: String = "",
    /** Nombre del modelo de reordenación de la nube (como BAAI/bge-reranker-v2-m3). */
    val rerankModel: String = "",
    /** Clave API para reclasificación en la nube (si la cadena está vacía, no se enviará la autorización). */
    val rerankApiKey: String = "",
    /**
     * Inyectar presupuesto de token(0 = sin limite;>0 = Acumular en orden descendente de similitud,Detener si se excede el presupuesto).
     * Recomendado = modelo actual contextWindow × 0.3.
     */
    val tokenBudget: Int = 0,
    /** Si se debe detectar la estructura Markdown al fragmentar(bloque de código/Título/Mantener la mesa entera.). */
    val markdownAware: Boolean = true,
    /** Ya sea para dividir en trozos por número simbólico(true=prensa [chunkSize] token,false=por personaje). */
    val chunkByToken: Boolean = false,

    // ── v1.0.47 P7-1 Nuevo ──
    /** Tipo de analizador de documentos. Predeterminado LOCAL(análisis local),Opcional CLOUD/MINERU(Necesidad de configurar endpoint). */
    val documentParserType: ParserType = ParserType.LOCAL,
    /** analizador de nubes endpoint(CLOUD Para tipo,Cadena vacía significa no configurado). */
    val cloudParserEndpoint: String = "",
    /** MinerU analizador endpoint(MINERU Para tipo,Cadena vacía significa no configurado). */
    val mineruEndpoint: String = "",
    /** MinerU analizador Token(MINERU Para tipo,Una cadena vacía significa que no se requiere autenticación). */
    val mineruToken: String = "",
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0, current: $chunkSize" }
        require(chunkOverlap >= 0) { "chunkOverlap cannot be negative, current: $chunkOverlap" }
        require(chunkOverlap < chunkSize) { "chunkOverlap($chunkOverlap) must be < chunkSize($chunkSize)" }
        require(threshold in 0f..1f) { "threshold must be in 0..1 range, current: $threshold" }
        require(topK > 0) { "topK must be > 0, current: $topK" }
        require(mmrLambda in 0f..1f) { "mmrLambda must be in 0..1 range, current: $mmrLambda" }
        require(tokenBudget >= 0) { "tokenBudget cannot be negative, current: $tokenBudget" }
        require(hybridBm25Weight >= 0f) { "hybridBm25Weight cannot be negative, current: $hybridBm25Weight" }
        require(hybridVectorWeight >= 0f) { "hybridVectorWeight cannot be negative, current: $hybridVectorWeight" }
    }

    /** Embedding Enumeración de fuentes. */
    @Serializable
    enum class EmbeddingSource {
        /** nube API(Calidad primero,Requiere conexión a internet). */
        CLOUD,

        /**
         * v1.133: locales ONNX(Incrustación semántica de redes neuronales,Necesita descargar el archivo del modelo).
         * Si no se introduce la dependencia onnxruntime-android,RagService Se degradará a LOCAL_KEYWORD.
         */
        LOCAL,

        /** v1.133: hash de palabras clave locales(Escenario de degradación sin conexión sin ONNX,no es realmente semántico). */
        LOCAL_KEYWORD,
    }

    /**
     * v1.0.47 P7-1: Tipo de analizador de documentos.
     *
     * - LOCAL: análisis local(Predeterminado,DocumentParser autoimplementable,apoyo txt/pdf/docx/pptx/xlsx/epub)
     * - CLOUD: Análisis de la nube API(Necesidad de configurar endpoint,Adecuado para diseños complejos/copia escaneada,Bajar a cuando no esté configurado LOCAL)
     * - MINERU: MinerU API(Necesidad de configurar endpoint,Bueno en PDF/fórmula académica/mesa,Bajar a cuando no esté configurado LOCAL)
     */
    @Serializable
    enum class ParserType {
        LOCAL,
        CLOUD,
        MINERU,
    }

    companion object {
        /**
         * F-33: Calcule el indicador de estabilidad de la configuración de incrustación actual,para la detección"Ya sea para cambiar el modelo integrado".
         *
         * embedding Configurado por [embeddingSource] + Provider/modelo de decisión conjunta,Cualquier cambio conducirá a
         * Dimensiones de índice vectorial existentes/desajuste semántico,Buscar"Parece que hay un índice pero no se puede encontrar.". Esta clave es para las páginas de la base de conocimientos.
         * con"El índice más reciente utilizado key"Comparar,Solicita al usuario que vuelva a indexar cuando se producen cambios.
         */
        fun embeddingModelKey(config: RagConfig): String = when (config.embeddingSource) {
            EmbeddingSource.LOCAL_KEYWORD -> "embed:local_keyword"
            EmbeddingSource.LOCAL -> "embed:local_onnx:${config.localModelPath}"
            EmbeddingSource.CLOUD -> "embed:cloud:${config.cloudProviderId}:${config.cloudModel}"
        }
    }
}

/**
 * P2-2(informe de auditoría): Fusione los campos de cobertura de una determinada KB en la configuración global de RAG.
 *
 * Todos los campos de cobertura están vacíos/NULL La configuración global se devuelve tal cual y el comportamiento existente permanece sin cambios.
 * Persona que llama: si se conoce la identificación de KB antes de la recuperación, combínela primero y luego pásela a RagService.
 */
fun RagConfig.withKbOverrides(kb: KnowledgeBaseEntity?): RagConfig {
    if (kb == null) return this
    return copy(
        chunkSize = kb.chunkSizeOverride ?: chunkSize,
        chunkOverlap = kb.chunkOverlapOverride ?: chunkOverlap,
        topK = kb.topKOverride ?: topK,
        threshold = kb.thresholdOverride ?: threshold,
        cloudModel = kb.embeddingModelOverride.takeIf { it.isNotBlank() } ?: cloudModel,
    )
}
