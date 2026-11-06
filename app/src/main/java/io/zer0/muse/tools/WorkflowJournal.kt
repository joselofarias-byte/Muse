package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * v1.0.53 Phase 2: Registro de recuperación de puntos de interrupción del flujo de trabajo.
 *
 * Formato de archivo: `<journalDir>/<runId>.jsonl`,uno por línea JSON [Entry].
 * falló/Después de la interrupción, pase la bandera de reanudación para volver a ejecutar.,El nodo completado llega directamente al caché y regresa en segundos.,
 * Vuelva a ejecutar desde el primer nodo no almacenado en caché.
 *
 * ## granularidad de caché(v1.1 §5.4 caché jerárquico)
 *
 * [Entry.nodeKind] Determina el comportamiento de almacenamiento en caché al reanudar:
 *  - [NODE_KIND_TOOL_ONLY] / [NODE_KIND_LLM_DETERMINISTIC]:Volver al caché al golpear result(Salida determinista)
 *  - [NODE_KIND_LLM_GENERATIVE]:Después del hit, el estado se marca como completado pero el resultado se deja en blanco.,Obligar a la persona que llama a volver a ejecutar generando un nuevo result
 *    (temperatura>0 La salida de LLM no es reproducible,resume debe regenerarse cuando;pero"nodo completado"El estado de se puede almacenar en caché.,Evite volver a ejecutar accidentalmente los nodos frontales)
 *  - [NODE_KIND_DELEGATION]:Volver al caché después del hit result(Los resultados de la herramienta son deterministas y almacenables en caché.;LLM La parte resumida la decide la persona que llama si desea regenerarla.)
 *  - [NODE_KIND_SENSITIVE_TOOL]:Utilice Keystore para cifrar los resultados antes de escribirlos,Descifrado explícito al leer;Los errores de descifrado se marcan como fallidos para evitar que se repitan los efectos secundarios.
 *
 * ## tolerancia a fallas
 *  - Línea ilegal encontrada mientras leía(dañado)Saltar y log warn,No lanzar excepción(En general, degradado para volver a ejecutarse)
 *  - El archivo se trata como nuevo si no existe run(resume Devolver caché vacío)
 *
 * @param journalDir Directorio de registro(normalmente filesDir/workflow_journals),Crear automáticamente si no existe
 */
class WorkflowJournal(
    private val journalDir: File,
    private val resultCipher: io.zer0.muse.data.SecureKeyCipher? = null,
) {
    private val writeLocks = Array(32) { Any() }
    private val runLocks = Array(32) { Mutex() }

    companion object {
        private const val TAG = "WorkflowJournal"

        // nodeKind valor constante(Alineación §5.4 Clasificación de tipo de nodo)
        /** Nodo de llamada de herramienta pura(Ninguno LLM,Salida determinista). */
        const val NODE_KIND_TOOL_ONLY = "tool_only"

        /** LLM Nodo de resumen(temperatura=0,Como el juicio condicional YES/NO,El espacio mínimo de salida se considera determinista). */
        const val NODE_KIND_LLM_DETERMINISTIC = "llm_deterministic"

        /** LLM Generar nodo(temperatura>0,Como resultados agregados LLM_REVIEW,La salida no es reproducible). */
        const val NODE_KIND_LLM_GENERATIVE = "llm_generative"

        /** delegate_agent / subagent_run nodo(Los resultados de la herramienta se pueden almacenar en caché,LLM Resumir según las reglas de la fila superior.). */
        const val NODE_KIND_DELEGATION = "delegation"

        /** La salida puede contener claves, datos de usuario o resultados de scripts nativos; Keystore debe cifrarlo antes de escribirlo en el disco. */
        const val NODE_KIND_SENSITIVE_TOOL = "sensitive_tool"

        /** Tiempo de retención de registro predeterminado:7 Días. */
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 3600 * 1000

        private val RUN_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
    }

    init {
        if (!journalDir.exists()) journalDir.mkdirs()
    }

    /** Serialize resume-and-execute for the same runId within this process. */
    suspend fun <T> withRunLock(runId: String, block: suspend () -> T): T {
        pathOf(runId)
        val lock = runLocks[(runId.hashCode() and Int.MAX_VALUE) % runLocks.size]
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Registro de registro único.
     *
     * @property nodeSeq Número de serie del nodo(Incrementando desde 0,Orden de ejecución del nodo de flujo de trabajo correspondiente)
     * @property key Entrada de nodo+Hash determinista de identidad(SHA-256 16 mejores hex),Se utiliza para determinar si la entrada cambia cuando se reanuda
     * @property result Resultados de salida del nodo(llm_generative Se dejará en blanco cuando se reanude)
     * @property status done|failed
     * @property nodeKind Tipo de nodo(Decidir la estrategia de almacenamiento en caché,Ver comentarios de clase)
     * @property ts Registro de marca de tiempo
     */
    @Serializable
    data class Entry(
        val nodeSeq: Int,
        val key: String,
        val result: String,
        val status: String,
        val nodeKind: String,
        val ts: Long,
    )

    /**
     * resume resultados.
     *
     * @property resumeFromSeq El primer número de secuencia de nodo que debe volver a ejecutarse(Todos los aciertos de caché anteriores)
     * @property cached Pulsar entrada de caché(nodeSeq → Entry;llm_generative El resultado de la entrada se ha dejado en blanco.)
     */
    data class ResumeResult(
        val resumeFromSeq: Int,
        val cached: Map<Int, Entry>,
    )

    /**
     * Registre el resultado de finalización de un nodo.(Agregar incremental al archivo JSONL).
     *
     * @param runId Ejecución del flujo de trabajo id(Nombre de archivo correspondiente)
     * @param nodeSeq Número de serie del nodo
     * @param key hash de entrada de nodo(usar [computeKey] generar)
     * @param result Resultados de salida del nodo
     * @param status done|failed
     * @param nodeKind Tipo de nodo([NODE_KIND_*] constante)
     */
    suspend fun record(runId: String, nodeSeq: Int, key: String, result: String, status: String, nodeKind: String) {
        try {
            recordRequired(runId, nodeSeq, key, result, status, nodeKind)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(TAG, "record falló runId=$runId seq=$nodeSeq: ${error.message}", error)
        }
    }

    /** Durably append a checkpoint; callers that promise resumability must not swallow write failures. */
    suspend fun recordRequired(runId: String, nodeSeq: Int, key: String, result: String, status: String, nodeKind: String) =
        withContext(Dispatchers.IO) {
            val file = pathOf(runId)
            val parent = file.parentFile ?: error("workflow journal has no parent directory")
            check(parent.exists() || parent.mkdirs()) { "No se puede crear el directorio de registro del flujo de trabajo" }
            val storedResult = if (nodeKind == NODE_KIND_SENSITIVE_TOOL && result.isNotEmpty()) {
                requireNotNull(resultCipher) { "sensitive workflow result requires an encryption provider" }.encrypt(result)
            } else {
                result
            }
            val entry = Entry(
                nodeSeq = nodeSeq,
                key = key,
                result = storedResult,
                status = status,
                nodeKind = nodeKind,
                ts = System.currentTimeMillis(),
            )
            val bytes = (AppJson.encodeToString(Entry.serializer(), entry) + "\n").toByteArray(Charsets.UTF_8)
            synchronized(writeLocks[(runId.hashCode() and Int.MAX_VALUE) % writeLocks.size]) {
                FileOutputStream(file, true).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
            }
        }

    /**
     * Cargar todas las entradas,Regresar Map<nodeSeq, Entry>.
     *
     * Devuelve un mapa vacío si el archivo no existe o está completamente dañado.
     */
    suspend fun load(runId: String): Map<Int, Entry> = withContext(Dispatchers.IO) {
        val file = pathOf(runId)
        if (!file.exists()) return@withContext emptyMap()

        val map = mutableMapOf<Int, Entry>()
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parsed = resultOf {
                    AppJson.decodeFromString(Entry.serializer(), line)
                }.getOrNull()
                if (parsed == null) {
                    Logger.w(TAG, "Saltar líneas JSONL corruptas: ${line.take(80)}")
                    continue
                }
                val restored = if (parsed.nodeKind == NODE_KIND_SENSITIVE_TOOL && parsed.result.isNotEmpty()) {
                    val plaintext = resultCipher?.decryptOrNull(parsed.result)
                    if (plaintext == null) {
                        Logger.w(TAG, "Los resultados confidenciales del flujo de trabajo no se pueden descifrar, lo que impide la recuperación automática seq=${parsed.nodeSeq}")
                        parsed.copy(result = "", status = "failed")
                    } else {
                        parsed.copy(result = plaintext)
                    }
                } else {
                    parsed
                }
                // El mismo nodeSeq se escribe más tarde y se sobrescribe primero.(Admite reintentar sobrescribir registros fallidos después de un error)
                map[restored.nodeSeq] = restored
            }
        }
        map
    }

    /**
     * Restaurar desde runId especificado:Devuelve la entrada de la caché de visitas + el primer nodeSeq que se volverá a ejecutar.
     *
     * lógica de decisión:
     *  1. Recorrer las entradas registradas en orden ascendente por nodeSeq
     *  2. encuentro status!=done entrada → Esta secuencia se vuelve a ejecutar desde(La entrada anterior realizada llega al caché)
     *  3. Entrada encontrada con clave no coincidente(Cambios de entrada de nodo)→ Esta secuencia se vuelve a ejecutar desde
     *  4. Todos los éxitos → resumeFromSeq = maxSeq+1(No es necesario ejecutar nuevos nodos,Depende de la persona que llama decidir si finalizar)
     *
     * Manejo de entrada de caché(prensa nodeKind):
     *  - [NODE_KIND_LLM_GENERATIVE]:result dejar en blanco(Forzar a la persona que llama a regenerarse),status Reservar done
     *  - Otros:result Volver como está
     *
     * @param expectedKeys Lo que espera la persona que llama esta vez (nodeSeq → key) mapeo,Se utiliza para verificar que el caché todavía coincide con la entrada actual.
     *                     Sin verificación cuando está vacío key(Sólo juzga por el estatus),Adecuado para currículums simples donde la definición del nodo permanece sin cambios.
     */
    suspend fun resume(runId: String, expectedKeys: Map<Int, String> = emptyMap()): ResumeResult = withContext(Dispatchers.IO) {
        val loaded = load(runId)
        if (loaded.isEmpty()) return@withContext ResumeResult(resumeFromSeq = 0, cached = emptyMap())

        val cached = mutableMapOf<Int, Entry>()
        var resumeFromSeq = 0

        for (seq in loaded.keys.sorted()) {
            // Never reuse downstream results after a missing/corrupt checkpoint; sequence must be contiguous.
            if (seq != resumeFromSeq) break
            val entry = loaded[seq]!!
            // Condiciones1:status no done → La secuencia comienza con la ejecución.
            if (entry.status != "done") break
            // Condiciones2:key No cumple con las expectativas(Cambios de entrada de nodo)→ La secuencia comienza con la ejecución.
            val expectedKey = expectedKeys[seq]
            if (expectedKeys.isNotEmpty() && expectedKey == null) break
            if (expectedKey != null && entry.key != expectedKey) break
            // golpear caché:llm_generative dejar en blanco result,Devolver todo lo demás tal cual
            val cachedEntry = if (entry.nodeKind == NODE_KIND_LLM_GENERATIVE) {
                entry.copy(result = "")
            } else {
                entry
            }
            cached[seq] = cachedEntry
            resumeFromSeq = seq + 1
        }
        ResumeResult(resumeFromSeq = resumeFromSeq, cached = cached)
    }

    /**
     * Eliminar registros caducados(Mantenga los maxAgeMs más recientes).
     *
     * Juzgar en función de la hora de la última modificación del expediente;Si la eliminación falla, solo la advertencia de registro no generará una excepción.
     */
    suspend fun cleanup(maxAgeMs: Long = DEFAULT_MAX_AGE_MS) = withContext(Dispatchers.IO) {
        if (!journalDir.exists()) return@withContext
        val cutoff = System.currentTimeMillis() - maxAgeMs
        journalDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (!file.delete()) {
                    Logger.w(TAG, "cleanup Error al eliminar: ${file.name}")
                }
            }
        }
    }

    /** Diagnóstico:Obtenga la ruta del archivo de registro correspondiente a runId(puede que no exista). */
    fun pathOf(runId: String): File {
        require(runId.matches(RUN_ID_REGEX) && runId != "." && runId != "..") {
            "workflow runId must be a safe filename token"
        }
        val root = journalDir.canonicalFile
        val target = File(root, "$runId.jsonl").canonicalFile
        require(target.parentFile == root) { "workflow journal path escapes its directory" }
        return target
    }

    /**
     * Entrada del nodo de cálculo+Hash determinista de identidad(SHA-256 16 mejores hex).
     *
     * Se utiliza para determinar si la entrada del nodo cambia cuando se reanuda:Si la entrada cambia, el caché quedará invalidado.,El nodo se vuelve a ejecutar.
     *
     * @param prompt Entrada de nodo(Descripción de la tarea + resumen de los resultados de la dependencia)
     * @param nodeIdentity Identidad del nodo(nodo id + assistantId + taskTemplate)
     */
    fun computeKey(prompt: String, nodeIdentity: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$prompt|$nodeIdentity"
        val hash = md.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
}
