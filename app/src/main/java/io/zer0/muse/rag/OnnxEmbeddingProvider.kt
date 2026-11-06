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
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * v1.134: locales ONNX Embedding Provider(Implementación completa).
 *
 * Reemplaza el esqueleto de marcador de posición v1.54,Proporcionar incrustaciones semánticas de redes neuronales verdaderamente locales.
 *
 * Flujo de trabajo:
 *  1. Carga diferida [OrtSession] + vocab.txt(de [modelPath] / [vocabPath])
 *  2. WordPiece participio → input_ids / attention_mask / token_type_ids
 *  3. ONNX razonamiento → last_hidden_state(o pooler_output)
 *  4. Mean Pooling(Ponderado por atención_máscara)+ L2 normalización
 *
 * Convenciones de archivos de modelo:
 *  - [modelPath]:Ruta absoluta o ruta relativa a filesDir,señalar a `.onnx` Archivo
 *  - [vocabPath]:BERT estilo `vocab.txt`(uno por línea token),Pruebe con una cadena vacía `[modelPath].vocab.txt`
 *  - El modelo no está configurado o falta el archivo → [embed] tirar [IllegalStateException],
 *    [EmbeddingService] será capturado y degradado a [LocalKeywordEmbeddingProvider]
 *
 * Modelo recomendado(Los usuarios pueden descargar el filesDir/muse_onnx/):
 *  - `bge-small-zh-v1.5`(512 dimensión,optimización china,~95MB)
 *  - `all-MiniLM-L6-v2`(384 dimensión,multilingüe,~23MB)
 *  - `mteb-chinese-tiny`(768 dimensión,chino,~25MB)
 *
 * @param modelPath ONNX Ruta del archivo del modelo
 * @param vocabPath BERT vocab.txt camino(cadena vacía=inferencia automática)
 * @param seqLen Longitud máxima de secuencia(Predeterminado 512,Truncamiento de texto largo)
 */
class OnnxEmbeddingProvider(
    private val modelPath: String,
    private val vocabPath: String = "",
    private val seqLen: Int = MAX_SEQ_LEN,
) : EmbeddingProvider {

    override val id: String = "local-onnx"
    override val displayName: String = "Local ONNX"
    override val modelName: String = if (modelPath.isBlank()) {
        "Not Configured"
    } else {
        File(modelPath).nameWithoutExtension
    }

    /** dimensiones inferidas:Haga coincidir las dimensiones comunes del modelo según las palabras clave del nombre del archivo del modelo. */
    override val dimension: Int by lazy {
        val name = modelPath.lowercase()
        when {
            name.contains("minilm") -> 384
            name.contains("bge-small") || name.contains("bge-tiny") -> 512
            name.contains("bge-base") || name.contains("bge-large") || name.contains("mteb") -> 768
            name.contains("e5-small") -> 384
            name.contains("e5-base") || name.contains("e5-large") -> 768
            name.contains("gte") -> 768
            else -> 768
        }
    }

    @Volatile private var session: OrtSession? = null

    @Volatile private var env: OrtEnvironment? = null

    @Volatile private var vocab: Map<String, Int>? = null

    @Volatile private var cachedInputNames: List<String>? = null

    @Volatile private var cachedOutputNames: List<String>? = null
    private val mutex = Mutex()

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (modelPath.isBlank() || !File(modelPath).exists()) {
            throw IllegalStateException(
                "ONNX embedding model not found: '$modelPath'. " +
                    "Please place a .onnx file at filesDir/muse_onnx/embedding.onnx " +
                    "and configure RagConfig.localModelPath, or switch to CLOUD/LOCAL_KEYWORD mode.",
            )
        }
        ensureInitialized()
        val activeSession = session ?: throw IllegalStateException("ONNX session init failed")
        val activeVocab = vocab ?: throw IllegalStateException("ONNX vocab load failed")
        val activeEnv = env ?: throw IllegalStateException("ONNX env not available")
        val inputNames = cachedInputNames ?: throw IllegalStateException("input names not cached")
        val outputNames = cachedOutputNames ?: throw IllegalStateException("output names not cached")

        texts.map { text ->
            embedSingle(activeEnv, activeSession, activeVocab, inputNames, outputNames, text)
        }
    }

    /** Incrustación de texto único: segmentación de palabras → padding → ONNX razonamiento → mean pool + L2 Normalizar. */
    private fun embedSingle(
        env: OrtEnvironment,
        session: OrtSession,
        vocab: Map<String, Int>,
        inputNames: List<String>,
        outputNames: List<String>,
        text: String,
    ): FloatArray {
        // 1. participio → token ids(agregar [CLS] / [SEP])
        val tokenIds = encode(text, vocab)
        // 2. truncar / rellenar a seqLen
        val padded = padOrTruncate(tokenIds, seqLen)
        val inputIds = padded.first
        val attentionMask = padded.second
        val tokenTypeIds = LongArray(seqLen) { 0 }

        // 3. Construir tensor de entrada ONNX(shape: [1, seqLen])
        val inputs = mutableMapOf<String, OnnxTensor>()
        var inputIdsTensor: OnnxTensor? = null
        var attnMaskTensor: OnnxTensor? = null
        var tokenTypeTensor: OnnxTensor? = null
        try {
            inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            attnMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
            tokenTypeTensor = OnnxTensor.createTensor(env, arrayOf(tokenTypeIds))

            // Asignado según el nombre de entrada real del modelo.(compatibles input_ids / input_ids.1 / ids esperando el nombre)
            assignInput(inputNames, "input_ids", inputIdsTensor, inputs)
            assignInput(inputNames, "attention_mask", attnMaskTensor, inputs)
            assignInput(inputNames, "token_type_ids", tokenTypeTensor, inputs)
            assignInput(inputNames, "token_type_ids.1", tokenTypeTensor, inputs)

            // 4. Ejecutar inferencia
            val result = session.run(inputs)
            try {
                // 5. Extraer salida — Prioridad pooler_output([1, hidden])De lo contrario last_hidden_state([1, seq, hidden])
                val pooled = extractPooledOutput(result, outputNames, attentionMask)
                // 6. L2 normalización(Requisito de similitud del coseno)
                return l2Normalize(pooled)
            } finally {
                result.close()
            }
        } finally {
            inputIdsTensor?.close()
            attnMaskTensor?.close()
            tokenTypeTensor?.close()
        }
    }

    /** Sesión de carga diferida + vocabulario. Hilo seguro. */
    private suspend fun ensureInitialized() {
        if (session != null && vocab != null) return
        mutex.withLock {
            if (session != null && vocab != null) return@withLock
            val ortEnv = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            // Cargar modelo(Puede tardar cientos de milisegundos,Pero seguro en Dispatchers.Default)
            val newSession = ortEnv.createSession(modelPath, opts)
            val newVocab = loadVocab(resolveVocabPath())

            session = newSession
            env = ortEnv
            vocab = newVocab
            cachedInputNames = newSession.inputNames.toList()
            cachedOutputNames = newSession.outputNames.toList()
            Logger.i(
                "OnnxEmbeddingProvider",
                "ONNX session cargado: model=${File(modelPath).name}, vocab=${newVocab.size} tokens, " +
                    "inputs=${newSession.inputNames}, outputs=${newSession.outputNames}",
            )
        }
    }

    /** Cargar estilo BERT vocab.txt(uno por línea token,El número de línea es token id). */
    private fun loadVocab(path: String): Map<String, Int> {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalStateException("ONNX vocab.txt not found: '$path'")
        }
        val map = HashMap<String, Int>(21128) // BERT-base-chinese vocab size
        file.useLines { lines ->
            lines.forEachIndexed { idx, line ->
                map[line.trimEnd()] = idx
            }
        }
        return map
    }

    /** Inferir la ruta vocab.txt:Específicamente especificado → Mismo nombre que el directorio .vocab.txt → Mismo directorio que vocab.txt. */
    private fun resolveVocabPath(): String {
        if (vocabPath.isNotBlank()) return vocabPath
        if (modelPath.isBlank()) return ""
        val modelFile = File(modelPath)
        val sameNameVocab = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".vocab.txt")
        if (sameNameVocab.exists()) return sameNameVocab.absolutePath
        val defaultVocab = File(modelFile.parentFile, "vocab.txt")
        if (defaultVocab.exists()) return defaultVocab.absolutePath
        return sameNameVocab.absolutePath // Volver a la ruta predeterminada,loadVocab Lanzará una excepción
    }

    /**
     * texto → BERT token ids.
     * agregar [CLS] = 101 / [SEP] = 102 Marcadores de límites.
     */
    private fun encode(text: String, vocab: Map<String, Int>): List<Long> {
        val tokens = WordPieceTokenizer.tokenize(text, vocab)
        val ids = mutableListOf<Long>()
        ids.add(vocab["[CLS]"]?.toLong() ?: 101L)
        for (token in tokens) {
            ids.add(vocab[token]?.toLong() ?: (vocab["[UNK]"]?.toLong() ?: 100L))
        }
        ids.add(vocab["[SEP]"]?.toLong() ?: 102L)
        return ids
    }

    /** Truncar/rellenar a longitud fija,Regresar (inputIds, attentionMask). */
    private fun padOrTruncate(tokenIds: List<Long>, maxLen: Int): Pair<LongArray, LongArray> {
        val inputIds = LongArray(maxLen) { 0 }
        val attentionMask = LongArray(maxLen) { 0 }
        val take = tokenIds.coerceAtMost(maxLen)
        for (i in 0 until take) {
            inputIds[i] = tokenIds[i]
            attentionMask[i] = 1
        }
        return inputIds to attentionMask
    }

    private fun List<Long>.coerceAtMost(maxLen: Int): Int = minOf(size, maxLen)

    /** Encuentre nombres de campos coincidentes por inputNames,Agregue el tensor al mapa de entradas. */
    private fun assignInput(inputNames: List<String>, candidate: String, tensor: OnnxTensor, inputs: MutableMap<String, OnnxTensor>) {
        if (inputNames.contains(candidate)) {
            inputs[candidate] = tensor
        }
    }

    /**
     * Extraiga la incrustación después de la agrupación de OrtSession.Result.
     *
     * estrategia prioritaria:
     *  1. El nombre de salida contiene "pooler_output" / "pooled" → Regresar directamente [hidden]
     *  2. De lo contrario toma last_hidden_state([1, seq, hidden]),Hazlo por atención_mask mean pool
     */
    private fun extractPooledOutput(result: OrtSession.Result, outputNames: List<String>, attentionMask: LongArray): FloatArray {
        // Prioridad pooler_output
        val pooledName = outputNames.firstOrNull { name ->
            name.contains("pooler", ignoreCase = true) ||
                name.contains("pooled", ignoreCase = true) ||
                name == "sentence_embedding"
        }
        if (pooledName != null) {
            val onnxValue = result.get(pooledName).orElse(null)
                ?: throw IllegalStateException("output '$pooledName' not found in result")
            val value = onnxValue.value
            return when (value) {
                is FloatArray -> value // [hidden]
                is Array<*> -> (value[0] as FloatArray) // [1, hidden] → [hidden]
                else -> throw IllegalStateException("unexpected pooler_output shape: ${value::class}")
            }
        }

        // revertir:last_hidden_state mean pool
        val hiddenName = outputNames.firstOrNull { name ->
            name.contains("last_hidden_state", ignoreCase = true) ||
                name == "hidden_states" || name == "encoder_outputs"
        } ?: outputNames.first()
        val onnxValue = result.get(hiddenName).orElse(null)
            ?: throw IllegalStateException("output '$hiddenName' not found in result")
        // shape [1, seqLen, hiddenSize] → Array<FloatArray> of size seqLen
        val rawValue = onnxValue.value
        val hiddenStates: Array<FloatArray> = when (rawValue) {
            is Array<*> -> {
                // 3D: [1, seq, hidden] → rawValue[0] is Array<FloatArray>
                @Suppress("UNCHECKED_CAST")
                rawValue[0] as Array<FloatArray>
            }
            else -> throw IllegalStateException("unexpected last_hidden_state shape: ${rawValue::class}")
        }
        return meanPool(hiddenStates, attentionMask)
    }

    /** Mean Pooling:Promedio ponderado por atencion_mask,Sólo estadísticas mask=1 ubicación. */
    private fun meanPool(hiddenStates: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        val hiddenSize = hiddenStates.firstOrNull()?.size ?: return FloatArray(0)
        val sum = FloatArray(hiddenSize)
        var validCount = 0
        for (i in hiddenStates.indices) {
            if (i < attentionMask.size && attentionMask[i] == 1L) {
                val vec = hiddenStates[i]
                for (j in 0 until hiddenSize) sum[j] += vec[j]
                validCount++
            }
        }
        if (validCount == 0) return FloatArray(hiddenSize)
        for (j in 0 until hiddenSize) sum[j] /= validCount.toFloat()
        return sum
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0f
        for (v in vec) sum += v * v
        val norm = sqrt(sum)
        if (norm == 0f) return vec
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }

    /** cerrar session,Liberar recursos nativos. */
    fun close() {
        resultOf {
            session?.close()
            session = null
            vocab = null
            cachedInputNames = null
            cachedOutputNames = null
            // env se comparte globalmente,no cerrado
            TimeUnit.MILLISECONDS.sleep(50) // Dar tiempo para el reciclaje de recursos nativos
        }.onError { msg, e -> Logger.w("OnnxEmbeddingProvider", "close falló: $msg", e) }
    }

    /** Compruebe si ONNX local está disponible(El archivo de modelo + vocabulario están configurados y existen.). */
    fun isAvailable(): Boolean {
        if (modelPath.isBlank() || !File(modelPath).exists()) return false
        val vPath = resolveVocabPath()
        return File(vPath).exists()
    }

    companion object {
        /** Longitud máxima de secuencia predeterminada(BERT Estándar 512). */
        const val MAX_SEQ_LEN = 512
    }
}
