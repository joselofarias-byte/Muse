package io.zer0.memory.observe

import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * D3-P1: Registro de operaciones de canalización de memoria(JSONL).
 *
 * Ligero, formato de archivo, inamovible. DB schema:
 *  - Cada paso de la ejecución agrega una línea de JSON a [file](Predeterminado `filesDir/memory/pipeline_log.jsonl`)
 *  - El archivo excede [maxBytes] desplácese hasta `.1`(Guarde solo una copia del historial.,El importe total está limitado)
 *  - Todas las fallas de IO solo se registran y nunca se descartan. —— La observación no puede derribar el oleoducto
 *
 * Seguridad del hilo:Usar para escribir ruta [lock] serialización(append con rotar se lee-cambiar-escribe todo).
 */
class PipelineLog(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    init {
        // Completar si el directorio principal no existe,El fracaso no es fatal
        runCatching { file.parentFile?.mkdirs() }
    }

    /** Agregue un registro en ejecución. Nunca lances una excepción. */
    fun append(record: PipelineLogRecord) {
        val line = runCatching { json.encodeToString(PipelineLogRecord.serializer(), record) }.getOrNull() ?: return
        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                file.appendText(line + "\n")
            }.onFailure {
                // La falla de observación no afecta el ducto
                Logger.w(TAG, "pipeline_log Error de escritura: ${it.message}")
            }
        }
    }

    /** de [MemoryStepRunner.StepResult] Construir y agregar registros(Entrada conveniente). */
    fun appendStep(
        stage: MemoryStage,
        step: String,
        trigger: String,
        ok: Boolean,
        durationMs: Long,
        failureKind: FailureKind?,
        error: Throwable?,
        scope: String? = null,
        spaceId: String? = null,
        detail: String? = null,
        counters: StepCounters? = null,
    ) {
        append(
            PipelineLogRecord(
                ts = clock(),
                stage = stage.name,
                step = step,
                trigger = trigger,
                ok = ok,
                durationMs = durationMs,
                failureKind = failureKind?.name,
                errorType = error?.let { it::class.java.simpleName },
                errorMsg = error?.message?.let { truncate(it) },
                scope = scope,
                spaceId = spaceId,
                detail = detail,
                total = counters?.total,
                failures = counters?.failures,
            ),
        )
    }

    /** Ruta del archivo de registro actual(prueba/para diagnóstico). */
    val path: String get() = file.absolutePath

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() <= maxBytes) return
        val rotated = File(file.parentFile, file.name + ROTATED_SUFFIX)
        runCatching { if (rotated.exists()) rotated.delete() }
        if (!file.renameTo(rotated)) {
            // rename falló:Truncar el archivo actual,evitar el crecimiento infinito
            Logger.w(TAG, "pipeline_log desplazamiento fallido,Truncar el archivo actual")
            file.writeText("")
        }
    }

    private fun truncate(msg: String): String = if (msg.length <= MAX_ERROR_MSG_CHARS) msg else msg.take(MAX_ERROR_MSG_CHARS) + "…"

    companion object {
        private const val TAG = "PipelineLog"
        private const val ROTATED_SUFFIX = ".1"
        private const val MAX_ERROR_MSG_CHARS = 300

        /** Límite superior predeterminado 512KB,más allá de desplazarse como `.1`,El tamaño total es de aproximadamente 1 MB delimitado. */
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024

        /**
         * Cree un registro en el directorio indicado(llenar automáticamente `memory/` subdirectorio).
         * @param memoryDir directorio de memoria(Como por ejemplo `filesDir/memory`)
         */
        fun create(memoryDir: File, maxBytes: Long = DEFAULT_MAX_BYTES): PipelineLog =
            PipelineLog(File(memoryDir, "pipeline_log.jsonl"), maxBytes)
    }
}

/**
 * Un registro de operación del oleoducto. Mantenga los campos lo más planos posible,Conveniente para la posterior agregación de scripts(prensa stage/step/failureKind).
 */
@Serializable
data class PipelineLogRecord(
    /** Marca de tiempo de milisegundos. */
    val ts: Long,
    /** [MemoryStage] Nombre. */
    val stage: String,
    /** clave de paso(Alineado con MemoryTicker.STEP_KEYS,Como por ejemplo compileFacts). */
    val step: String,
    /** fuente de disparo:threshold / session_end / manual / backfill / daily / force / turn. */
    val trigger: String,
    /** Si tiene éxito o no. */
    val ok: Boolean,
    /** Consume mucho tiempo(milisegundos). */
    val durationMs: Long,
    /** [FailureKind] Nombre;Solo no vacío en caso de falla. */
    val failureKind: String? = null,
    /** Nombre de clase extraordinariamente simple. */
    val errorType: String? = null,
    /** Mensaje de excepción(Truncado a 300 palabras). */
    val errorMsg: String? = null,
    /** alcance de la memoria. */
    val scope: String? = null,
    /** espacio de memoria. */
    val spaceId: String? = null,
    /** Instrucciones adicionales para los pasos(Como RollingSummary's changed/unchanged). */
    val detail: String? = null,
    /** El número acumulado de ejecuciones de este paso.(Incluyendo esta vez). */
    val total: Int? = null,
    /** El número acumulado de errores de este paso.(Incluyendo esta vez). */
    val failures: Int? = null,
)
