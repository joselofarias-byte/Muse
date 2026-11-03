package io.zer0.muse.rag

/**
 * v1.54: Embedding Interfaz del proveedor.
 *
 * Clase de implementación:
 *  - [OpenAIEmbeddingProvider]: nube API(OpenAI / Interfaces compatibles con OpenAI como Zhipu/Tongyi, etc.)
 *  - [LocalKeywordEmbeddingProvider]: hash de palabras clave locales embedding(Sin conexión,No es necesario ONNX)
 *  - [OnnxEmbeddingProvider]: Inferencia ONNX nativa(reservado,La implementación completa estará habilitada en versiones posteriores.)
 *
 * @see RagConfig.EmbeddingSource
 */
interface EmbeddingProvider {
    /** ID del proveedor(Se utiliza para registros y visualización de UI). */
    val id: String

    /** Nombre para mostrar. */
    val displayName: String

    /** Dimensiones del vector de salida(Diferentes modelos tienen diferentes dimensiones.,Las dimensiones deben ser consistentes al recuperar). */
    val dimension: Int

    /** Nombre del modelo(para seguimiento knowledge_docs.embedding_model). */
    val modelName: String

    /**
     * v2.6.0 (AUD-RAG-F3): HNSW embedding Apertura dimensional utilizada para huellas dactilares.
     *
     * [dimension] Podría ser solo una suposición antes de la primera inserción (consulte la guía de OpenAIEmbeddingProvider).
     * guessDimension). Escribir valores de conjetura en las huellas dactilares da como resultado "adivinar"→Salto "verdadero", juzgado cada arranque en frío
     * La huella dactilar cambia y se reconstruye el índice. Devoluciones de la clase de implementación**estable**Cadena de dimensiones: se ha medido la dimensión real,
     * La ocupación fija no se utiliza en las pruebas reales.
     */
    val fingerprintDimension: String
        get() = dimension.toString()

    /**
     * Generar incrustaciones en lotes.
     * @param texts Lista de texto a codificar
     * @return Lista de vectores correspondiente(El orden es consistente con la entrada.),Cada elemento es FloatArray
     * @throws Exception red/Se lanza cuando se produce un error en el modelo.,Manejado por la persona que llama runCatching
     */
    suspend fun embed(texts: List<String>): List<FloatArray>

    /** texto único embedding(método de conveniencia,ajuste interno [embed]). */
    suspend fun embed(text: String): FloatArray = embed(listOf(text)).firstOrNull()
        ?: throw IllegalStateException("Provider returned empty vector list")
}
