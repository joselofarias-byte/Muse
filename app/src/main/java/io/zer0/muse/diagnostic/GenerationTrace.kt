package io.zer0.muse.diagnostic

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.x(Elemento de exportación de diagnóstico A): genere un registrador de seguimiento de eventos de enlace.
 *
 * Propósito: encuentro de los usuarios"Transmisión truncada/atascada/se agotó el tiempo de espera"Al resolver problemas, puede exportar una copia de**Sin contenido sensible**de
 * Con trayectorias estructuradas, los desarrolladores ya no necesitan adivinar a ciegas después de obtenerlo.
 *
 * Diseño:
 *  - buffer de anillo de memoria reciente [RING_CAPACITY] ronda (nivel de proceso), agregue una línea inmediatamente después de que se complete la generación JSONL
 *    Colocación (filesDir/diagnostics/generation-trace.jsonl, límite superior rodante [MAX_FILE_BYTES]);
 *  - **Línea roja: no grabar ningún texto de mensaje,prompt,API key**. Solo recuerda las estadísticas, el cronograma del evento,
 *    ID de configuración (modelId/providerType/host).sessionId Desensibilice con los primeros 8 bits del hash SHA-256;
 *  - Observador puro: cualquier excepción de escritura de seguimiento será absorbida y advertida, y nunca afectará la generación del enlace principal;
 *  - No tiene nada que ver con el modo de depuración.——Los usuarios normales también lo graban (el contenido en sí ha sido insensibilizado) y sólo lo leen después de exportarlo.
 *
 * Punto de entierro:ChatGenerationController.recordDebugSummary(Resumen de estado final, punto de suspensión natural).
 */
object GenerationTrace {

    /** Capacidad del buffer anular (rueda). */
    const val RING_CAPACITY = 20

    /** JSONL Límite de desplazamiento de archivos. Tras superarlo, se corta en la segunda mitad. */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private const val TRACE_FILE_NAME = "generation-trace.jsonl"

    private val ring = ArrayDeque<TraceRecord>()
    private val lock = Any()

    @Volatile
    private var traceFile: File? = null

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Una trayectoria estructurada generada en una ronda (todos los campos son estadísticas/Identidad, sin texto y sin clave). */
    @Serializable
    data class TraceRecord(
        /** SHA-256 primeros 8 dígitos del ID de sesión (insensibilizado, usado solo para distinguir sesiones). */
        val sessionHash: String,
        val modelId: String,
        val providerType: String,
        /** Estado final:completed / failed / cancelled / context_overflow Esperar. */
        val outcome: String,
        val elapsedMs: Long,
        /** Retraso del primer token;-1 Indica que no se ha recibido el primer token. */
        val ttftMs: Long,
        val contentChars: Int,
        val reasoningChars: Int,
        val toolCallCount: Int,
        val round: Int,
        val uiFlushCount: Int,
        /** Provider Interrupción de flujo reportada/Clasificación de cierre (por [noteStreamEnd] escribir). */
        val streamEndKind: String = "",
        /** finish_reason(null→cadena vacía). */
        val finishReason: String = "",
        /** Si la compresión se activa en esta ronda (determinada por [noteCompression] escribir). */
        val compressionTriggered: Boolean = false,
        val ts: String,
    )

    /** Inicializado cuando se inicia la aplicación (MuseApp.onCreate). Idempotente. */
    fun init(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir("diagnostics") ?: File(context.filesDir, "diagnostics")
            dir.mkdirs()
            traceFile = File(dir, TRACE_FILE_NAME)
        }.onFailure { Logger.w("GenerationTrace", "init falló: ${it.message}") }
    }

    /**
     * Registre el resumen del estado final generado en una ronda. Llamado por ChatGenerationController en cada ruta de estado final.
     * Cualquier excepción se traga internamente.——trace Es un observador puro y no debe afectar a la generación.
     */
    fun record(summary: GenerationSummary) {
        runCatching {
            val rec = TraceRecord(
                sessionHash = hashSession(summary.sessionId),
                modelId = summary.modelId,
                providerType = summary.providerType,
                outcome = summary.outcome,
                elapsedMs = summary.elapsedMs,
                ttftMs = summary.ttftMs,
                contentChars = summary.contentChars,
                reasoningChars = summary.reasoningChars,
                toolCallCount = summary.toolCallCount,
                round = summary.round,
                uiFlushCount = summary.uiFlushCount,
                streamEndKind = takePending(pendingStreamEndKind, ""),
                finishReason = takePending(pendingFinishReason, ""),
                compressionTriggered = takePending(pendingCompression, false),
                ts = tsFormat.format(Date()),
            )
            synchronized(lock) {
                ring.addLast(rec)
                while (ring.size > RING_CAPACITY) ring.removeFirst()
            }
            appendToFile(rec)
        }.onFailure { Logger.w("GenerationTrace", "record falló(tragado): ${it.message}") }
    }

    /** Resumen de estados finales generados en una ronda (record Agregación de parámetros de entrada, evite LongParameterList). */
    data class GenerationSummary(
        val sessionId: String,
        val modelId: String,
        val providerType: String,
        val outcome: String,
        val elapsedMs: Long,
        val ttftMs: Long,
        val contentChars: Int,
        val reasoningChars: Int,
        val toolCallCount: Int,
        val round: Int,
        val uiFlushCount: Int,
    )

    // ── Anotación de clasificación antes del estado final (escrita primero por Proveedor/ruta de compresión,record (cuando se graban juntos)──

    private val pendingStreamEndKind = ThreadLocal.withInitial { "" }
    private val pendingFinishReason = ThreadLocal.withInitial { "" }
    private val pendingCompression = ThreadLocal.withInitial { false }

    /**
     * Clasificación final del flujo de registros (llamada por la ruta del estado final de OpenAIProvider):
     *  - "done"          Recibido normalmente [DONE]/finishReason
     *  - "closed_no_finish" La conexión se cerró pero no se recibió el motivo final (se sospecha de truncamiento silencioso por parte de la estación de tránsito)
     *  - "failure"       onFailure(Error de red/tiempo de espera)
     *  - "fallback"      stream-guard Activar respaldo sin transmisión
     */
    fun noteStreamEnd(kind: String, finishReason: String? = null) {
        runCatching {
            pendingStreamEndKind.set(kind)
            pendingFinishReason.set(finishReason.orEmpty())
        }
    }

    /** La grabación de esta ronda activa la compresión de contexto (llamada por la ruta de orquestación de compresión). */
    fun noteCompression() {
        runCatching { pendingCompression.set(true) }
    }

    /** Contenido actual del búfer (para exportación y prueba). */
    fun snapshot(): List<TraceRecord> = synchronized(lock) { ring.toList() }

    /** trace Archivo (para embalaje de exportación). Puede ser nulo (no inicial). */
    fun traceFileOrNull(): File? = traceFile?.takeIf { it.exists() }

    /** Borre el búfer y los archivos (para probar). */
    internal fun clearForTest() {
        synchronized(lock) { ring.clear() }
        runCatching { traceFile?.delete() }
    }

    private fun <T> takePending(local: ThreadLocal<T>, fallback: T): T {
        val v = local.get() ?: fallback
        local.set(fallback)
        return v
    }

    private fun hashSession(sessionId: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(8)
    }.getOrDefault("unknown")

    private fun appendToFile(rec: TraceRecord) {
        val file = traceFile ?: return
        synchronized(lock) {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                // Rolling: conservar la segunda mitad (estrategia de truncamiento simple para evitar reescrituras completas)
                val bytes = file.readBytes()
                val half = bytes.copyOfRange(bytes.size / 2, bytes.size)
                file.writeBytes(half)
            }
            file.appendText(AppJson.encodeToString(rec) + "\n")
        }
    }
}
