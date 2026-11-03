package io.zer0.muse.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.exp

/**
 * v1.134: Proveedor local de reclasificación de codificador cruzado ONNX.
 *
 * Realización de reordenamiento de codificadores cruzados con el tiempo de ejecución de ONNX(bge-reranker-base / ms-marco-MiniLM espera),
 * entrada `[CLS] query [SEP] candidate [SEP]`,correlación de salida logit,Normalizado por sigmoide a una puntuación de 0-1.
 *
 * Convenciones de archivos de modelo:
 *  - [modelPath]:Ruta absoluta o ruta relativa a filesDir,señalar a `.onnx` rerank modelo
 *  - [vocabPath]:BERT estilo vocab.txt(cadena vacía=inferencia automática)
 *  - Modelo no configurado → [rerank] tirar [IllegalStateException],
 *    [RagService] será capturado y degradado a [LocalRerankProvider](Mantener el orden original)
 *
 * Modelo recomendado(Los usuarios pueden descargar el filesDir/muse_onnx/):
 *  - `bge-reranker-base`(768 dimensión,chino e ingles,~1.1GB,mejor calidad)
 *  - `bge-reranker-small`(Opcional,tamaño más pequeño)
 *  - `ms-marco-MiniLM-L-12-v2`(ingles,~420MB)
 *
 * @param modelPath ONNX rerank ruta del modelo
 * @param vocabPath BERT vocab.txt camino(cadena vacía=inferencia automática)
 * @param seqLen Longitud máxima de secuencia(Predeterminado 512,query+candidate Truncar)
 */
class OnnxRerankProvider(
    private val modelPath: String,
    private val vocabPath: String = "",
    private val seqLen: Int = MAX_SEQ_LEN,
) : RerankProvider {

    @Volatile private var session: OrtSession? = null

    @Volatile private var env: OrtEnvironment? = null

    @Volatile private var vocab: Map<String, Int>? = null

    @Volatile private var cachedInputNames: List<String>? = null

    @Volatile private var cachedOutputNames: List<String>? = null
    private val mutex = Mutex()

    override suspend fun rerank(query: String, candidates: List<RerankCandidate>, topK: Int): List<RerankResult> =
        withContext(Dispatchers.Default) {
            if (candidates.isEmpty()) return@withContext emptyList()

            if (modelPath.isBlank() || !File(modelPath).exists()) {
                throw IllegalStateException(
                    "ONNX rerank model not found: '$modelPath'. " +
                        "Please place a rerank .onnx file at filesDir/muse_onnx/rerank.onnx, " +
                        "or switch rerankProvider to '' (local heuristic) / 'cohere' / 'jina'.",
                )
            }
            ensureInitialized()
            val activeSession = session ?: throw IllegalStateException("ONNX rerank session init failed")
            val activeVocab = vocab ?: throw IllegalStateException("ONNX rerank vocab load failed")
            val activeEnv = env ?: throw IllegalStateException("ONNX rerank env not available")
            val inputNames = cachedInputNames ?: throw IllegalStateException("input names not cached")
            val outputNames = cachedOutputNames ?: throw IllegalStateException("output names not cached")

            // Puntuación por lotes
            val scored = candidates.map { c ->
                val score = scoreSingle(activeEnv, activeSession, activeVocab, inputNames, outputNames, query, c.content)
                RerankResult(c.chunkId, c.docId, c.docTitle, c.content, c.chunkIndex, score)
            }
            scored.sortedByDescending { it.score }.take(topK)
        }

    /** soltero (query, candidate) Calificación:empalme → participio → ONNX razonamiento → sigmoid Normalizar. */
    private fun scoreSingle(
        env: OrtEnvironment,
        session: OrtSession,
        vocab: Map<String, Int>,
        inputNames: List<String>,
        outputNames: List<String>,
        query: String,
        candidate: String,
    ): Float {
        // 1. Empalmado en la entrada del codificador cruzado BERT:[CLS] query [SEP] candidate [SEP]
        val tokenIds = encodePair(query, candidate, vocab)
        // 2. Truncar / padding
        val padded = padOrTruncate(tokenIds, seqLen)
        val inputIds = padded.first
        val attentionMask = padded.second
        // token_type_ids:sección frontal(query)para 0,sección trasera(candidate)para 1
        val tokenTypeIds = buildTokenTypeIds(tokenIds, seqLen)

        // 3. ONNX razonamiento
        val inputs = mutableMapOf<String, OnnxTensor>()
        var inputIdsTensor: OnnxTensor? = null
        var attnMaskTensor: OnnxTensor? = null
        var tokenTypeTensor: OnnxTensor? = null
        try {
            inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            attnMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
            tokenTypeTensor = OnnxTensor.createTensor(env, arrayOf(tokenTypeIds))

            assignInput(inputNames, "input_ids", inputIdsTensor, inputs)
            assignInput(inputNames, "attention_mask", attnMaskTensor, inputs)
            assignInput(inputNames, "token_type_ids", tokenTypeTensor, inputs)
            assignInput(inputNames, "token_type_ids.1", tokenTypeTensor, inputs)

            val result = session.run(inputs)
            try {
                val logit = extractLogit(result, outputNames)
                // 4. sigmoid normalizado a [0, 1]
                return sigmoid(logit)
            } finally {
                result.close()
            }
        } finally {
            inputIdsTensor?.close()
            attnMaskTensor?.close()
            tokenTypeTensor?.close()
        }
    }

    private suspend fun ensureInitialized() {
        if (session != null && vocab != null) return
        mutex.withLock {
            if (session != null && vocab != null) return@withLock
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val newSession = ortEnv.createSession(modelPath, opts)
            val newVocab = loadVocab(resolveVocabPath())

            session = newSession
            env = ortEnv
            vocab = newVocab
            cachedInputNames = newSession.inputNames.toList()
            cachedOutputNames = newSession.outputNames.toList()
            Logger.i(
                "OnnxRerankProvider",
                "ONNX rerank session cargado: model=${File(modelPath).name}, vocab=${newVocab.size} tokens",
            )
        }
    }

    private fun loadVocab(path: String): Map<String, Int> {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalStateException("ONNX rerank vocab.txt not found: '$path'")
        }
        val map = HashMap<String, Int>(21128)
        file.useLines { lines ->
            lines.forEachIndexed { idx, line -> map[line.trimEnd()] = idx }
        }
        return map
    }

    private fun resolveVocabPath(): String {
        if (vocabPath.isNotBlank()) return vocabPath
        if (modelPath.isBlank()) return ""
        val modelFile = File(modelPath)
        val sameNameVocab = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".vocab.txt")
        if (sameNameVocab.exists()) return sameNameVocab.absolutePath
        val defaultVocab = File(modelFile.parentFile, "vocab.txt")
        if (defaultVocab.exists()) return defaultVocab.absolutePath
        return sameNameVocab.absolutePath
    }

    /** empalme query + candidate → token ids:[CLS] q_tokens [SEP] c_tokens [SEP]. */
    private fun encodePair(query: String, candidate: String, vocab: Map<String, Int>): List<Long> {
        val queryTokens = WordPieceTokenizer.tokenize(query, vocab)
        val candidateTokens = WordPieceTokenizer.tokenize(candidate, vocab)
        // Truncar:dejar de lado [CLS] [SEP] [SEP] 3 poco especial
        val maxEach = (seqLen - 3) / 2
        val qTrunc = queryTokens.take(maxEach)
        val cTrunc = candidateTokens.take(maxEach)

        val ids = mutableListOf<Long>()
        ids.add(vocab["[CLS]"]?.toLong() ?: 101L)
        qTrunc.forEach { ids.add(vocab[it]?.toLong() ?: (vocab["[UNK]"]?.toLong() ?: 100L)) }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)
        cTrunc.forEach { ids.add(vocab[it]?.toLong() ?: (vocab["[UNK]"]?.toLong() ?: 100L)) }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)
        return ids
    }

    /** token_type_ids:query Duan Wei 0,candidate El segmento es 1. */
    private fun buildTokenTypeIds(tokenIds: List<Long>, maxLen: Int): LongArray {
        val typeIds = LongArray(maxLen) { 0 }
        // Encuentra el segundo [SEP](=102)ubicación,El token siguiente se cambia a 1
        var sepCount = 0
        for (i in tokenIds.indices) {
            if (i >= maxLen) break
            if (tokenIds[i] == 102L) {
                sepCount++
                if (sepCount == 1) {
                    // El siguiente token comienza con el segmento candidato.
                    for (j in (i + 1) until minOf(tokenIds.size, maxLen)) {
                        typeIds[j] = 1
                    }
                    break
                }
            }
        }
        return typeIds
    }

    private fun padOrTruncate(tokenIds: List<Long>, maxLen: Int): Pair<LongArray, LongArray> {
        val inputIds = LongArray(maxLen) { 0 }
        val attentionMask = LongArray(maxLen) { 0 }
        val take = minOf(tokenIds.size, maxLen)
        for (i in 0 until take) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1
        }
        return inputIds to attentionMask
    }

    private fun assignInput(inputNames: List<String>, candidate: String, tensor: OnnxTensor, inputs: MutableMap<String, OnnxTensor>) {
        if (inputNames.contains(candidate)) inputs[candidate] = tensor
    }

    /** Extracto de resultados logit(típico shape [1, 2] o [1, 1]). */
    private fun extractLogit(result: OrtSession.Result, outputNames: List<String>): Float {
        val name = outputNames.firstOrNull { it.contains("logits", ignoreCase = true) }
            ?: outputNames.first()
        val onnxValue = result.get(name).orElse(null)
            ?: throw IllegalStateException("output '$name' not found in rerank result")
        val value = onnxValue.value
        return when (value) {
            is FloatArray -> {
                // [2] → tomar [1](true puntuación de clase);[1] → Tómalo directamente
                if (value.size >= 2) value[1] else value[0]
            }
            is Array<*> -> {
                // [1, 2] o [1, 1]
                val row = value[0]
                when (row) {
                    is FloatArray -> if (row.size >= 2) row[1] else row[0]
                    else -> (row as Number).toFloat()
                }
            }
            is Number -> value.toFloat()
            else -> throw IllegalStateException("unexpected logits shape: ${value::class}")
        }
    }

    /** sigmoid:1 / (1 + e^-x),Comprimir logit a [0, 1]. */
    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x))).coerceIn(0f, 1f)

    /** cerrar session,Liberar recursos nativos. */
    fun close() {
        resultOf {
            session?.close()
            session = null
            vocab = null
            cachedInputNames = null
            cachedOutputNames = null
        }.onError { msg, e -> Logger.w("OnnxRerankProvider", "close falló: $msg", e) }
    }

    /** Compruebe si la reclasificación de ONNX local está disponible. */
    fun isAvailable(): Boolean {
        if (modelPath.isBlank() || !File(modelPath).exists()) return false
        val vPath = resolveVocabPath()
        return File(vPath).exists()
    }

    companion object {
        const val MAX_SEQ_LEN = 512
    }
}
