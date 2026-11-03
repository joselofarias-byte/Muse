package io.zer0.muse.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.63: palabras clave locales Embedding Provider(No es necesario ONNX,No se requiere internet).
 *
 * Principio de implementación:Texto del segmento(Chino por carácter bigram + Inglés por palabra),Luego usa la función hash
 * Asigne cada token a varias dimensiones de un vector de dimensión fija,Normalización L2 después de ponderación acumulativa.
 * Este es un esquema similar a HashingVectorizer.,Aunque no es tan semántico como la incorporación de redes neuronales
 * rico,Pero completamente fuera de línea, sin dependencia, extremadamente rápido.,Adecuado para la recuperación de palabras clave de bases de conocimiento pequeñas y medianas.
 *
 * Ventajas:
 *  - No se requiere tiempo de ejecución/archivos de modelo/red de ONNX
 *  - Las dimensiones vectoriales son fijas.(512 dimensión),Compatible con la similitud del coseno de VectorSearchService
 *  - El bigram chino puede capturar"base de conocimientos""Buscar"Características de las palabras compuestas
 *
 * limitaciones:
 *  - Sin comprensión semántica profunda(sinónimos/Habilidades débiles de razonamiento contextual.)
 *  - Adecuado para escenarios de concordancia de palabras clave,No apto para responder preguntas semánticas complejas.
 *  - texto muy corto(Menos de 3 palabras)Efecto promedio
 */
class LocalKeywordEmbeddingProvider : EmbeddingProvider {

    override val id: String = "local-keyword"
    override val displayName: String = "Local Keyword (Offline)"
    override val modelName: String = "local-keyword-v1"
    override val dimension: Int = HASH_DIM

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        texts.map { embedSingle(it) }
    }

    /** Genere un vector de frecuencia de palabras hash normalizado para un solo texto. */
    private fun embedSingle(text: String): FloatArray {
        val vec = FloatArray(HASH_DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vec

        for (token in tokens) {
            val hash = stableHash(token)
            val index = (hash and 0x7FFFFFFF).toInt() % HASH_DIM
            // acumulación de frecuencia de palabras:Repita el token para aumentar el peso.
            vec[index] += 1f
        }

        // L2 normalización,Hacer efectiva la similitud del coseno
        var sum = 0f
        for (v in vec) sum += v * v
        val n = kotlin.math.sqrt(sum)
        if (n > 0f) {
            for (i in vec.indices) vec[i] /= n
        }
        return vec
    }

    companion object {
        /** Dimensiones del vector hash(512 dimensión,Equilibrio precisión y memoria). */
        private const val HASH_DIM = 512

        /**
         * participio:Chino por caracter bigram,ingles por palabra(Minúsculas).
         * "Búsqueda de base de conocimientos" -> ["conocimiento", "base de conocimientos", "inspección de inventario", "Buscar"]
         * "RAG retrieval" -> ["rag", "retrieval"]
         */
        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val cleaned = text.lowercase().trim()
            if (cleaned.isEmpty()) return tokens

            // Párrafos separados en chino e inglés
            val currentChinese = StringBuilder()
            for (ch in cleaned) {
                if (ch.code in 0x4E00..0x9FFF) {
                    // CJK Unificar caracteres chinos
                    currentChinese.append(ch)
                } else {
                    // Caracteres no chinos:Primero elimina los chinos acumulados. bigram
                    if (currentChinese.isNotEmpty()) {
                        addChineseBigrams(currentChinese.toString(), tokens)
                        currentChinese.clear()
                    }
                    // ingles/Las palabras numéricas se procesan uniformemente dividiéndolas a continuación
                }
            }
            if (currentChinese.isNotEmpty()) {
                addChineseBigrams(currentChinese.toString(), tokens)
            }

            // Segmentación de palabras en inglés(Dividido por no alfanumérico)
            val englishWords = cleaned.split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 2 }
            tokens.addAll(englishWords)

            // No quitar peso:La frecuencia de palabras de tokens repetidos es significativa para el cálculo de similitud
            return tokens
        }

        /** Bigram chino: dos palabras adyacentes forman una ficha. */
        private fun addChineseBigrams(text: String, tokens: MutableList<String>) {
            if (text.length == 1) {
                tokens.add(text)
                return
            }
            for (i in 0 until text.length - 1) {
                tokens.add(text.substring(i, i + 2))
            }
        }

        /** hash estable(FNV-1a Variantes,Consistente en todas las plataformas). */
        private fun stableHash(str: String): Long {
            var hash = 2166136261L
            for (ch in str) {
                hash = hash xor ch.code.toLong()
                hash *= 16777619L
                hash = hash and 0xFFFFFFFFL
            }
            return hash
        }
    }
}
