package io.zer0.muse.rag

/**
 * v1.55: Fábrica de índices vectoriales.
 *
 * Seleccione automáticamente una estrategia de indexación adecuada según el tamaño de los datos:
 *  - [IndexStrategy.BRUTE_FORCE]:pequeña escala(<5000 chunk),Reutilizar existente [VectorSearchService] recorrido violento
 *  - [IndexStrategy.HNSW]:gran escala(>=5000 chunk),uso [HnswVectorIndex] vecino más cercano aproximado
 *
 * La fábrica en sí no crea un índice transversal de fuerza bruta.(consiste en [RagService] sostenido directamente [VectorSearchService] darse cuenta);
 * Aquí sólo somos responsables de crear [VectorIndex] darse cuenta(Actualmente solo hay HNSW).
 */
object VectorIndexFactory {

    /** Enumeración de estrategias de índice. */
    enum class IndexStrategy {
        /** recorrido violento(pequeña escala,<5000 chunk):de existente [VectorSearchService] darse cuenta,RagService celebrado directamente. */
        BRUTE_FORCE,

        /** HNSW vecino más cercano aproximado(gran escala,>=5000 chunk):por [HnswVectorIndex] Darse cuenta. */
        HNSW,
    }

    /** Seleccionar umbral automáticamente:chunk numero >= Este valor se utiliza cuando HNSW,De lo contrario, utilice el recorrido por fuerza bruta. */
    const val AUTO_THRESHOLD = 5000

    /**
     * Crear una política específica [VectorIndex].
     *
     * - [IndexStrategy.BRUTE_FORCE] Regresar null(por [RagService] ir [VectorSearchService] camino)
     * - [IndexStrategy.HNSW] Regresar [HnswVectorIndex](Usar parámetros predeterminados M=16/efC=200/efS=50)
     *
     * @return [VectorIndex] Ejemplo;BRUTE_FORCE Retorno de la estrategia null
     */
    fun createIndex(strategy: IndexStrategy): VectorIndex? = when (strategy) {
        IndexStrategy.BRUTE_FORCE -> null // El recorrido de fuerza bruta se implementa mediante el vectorSearch existente de RagService
        IndexStrategy.HNSW -> HnswVectorIndex()
    }

    /**
     * Seleccione automáticamente una estrategia y cree un índice basado en la cantidad de fragmentos.
     *
     * @param chunkCount El número actual de fragmentos indexados.
     * @return [VectorIndex] Ejemplo;chunk numero < [AUTO_THRESHOLD] regresar cuando null(Caminar a través de la violencia)
     */
    fun createAutoIndex(chunkCount: Int): VectorIndex? = createIndex(autoStrategy(chunkCount))

    /** Elija una estrategia basada en la cantidad de fragmentos(>=5000 usar HNSW,De lo contrario BRUTE_FORCE). */
    fun autoStrategy(chunkCount: Int): IndexStrategy = if (chunkCount >= AUTO_THRESHOLD) IndexStrategy.HNSW else IndexStrategy.BRUTE_FORCE
}
