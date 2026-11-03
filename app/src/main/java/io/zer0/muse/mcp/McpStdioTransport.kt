package io.zer0.muse.mcp

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.MuseRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1-A: MCP stdio transmisión — proceso hijo local (Node Protocolo JSON-RPC para servidor MCP).
 *
 * Límites de responsabilidades:
 *  - Esta clase solo es responsable del "proceso + envío y recepción de líneas";JSON-RPC Semántica (id coincidencia, distribución de notificaciones) por [McpClient] tratamiento.
 *  - El proceso se ejecuta sobre el tiempo de ejecución de Nodo integrado de la aplicación ([McpStdioCommand] comando de análisis,
 *    [MuseRuntime.buildEnv] entorno de construcción), el directorio de trabajo es `filesDir/muse-runtime/mcp/<serverId>/`.
 *
 * Formato del mensaje:stdio Transmitido como "JSON por línea" (NDJSON);stdout Las líneas que no son JSON se ignoran.
 *
 * @param onMessage Se recibe un mensaje JSON (la persona que llama descarga la respuesta/notificación/solicitud del servidor).
 * @param onExit Devolución de llamada de salida del proceso hijo (activa [stop] También se activará cuando la persona que llama necesite distinguirlo por sí mismo).
 */
class McpStdioTransport(
    private val config: McpServerConfig,
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMessage: (JsonObject) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    private val writeLock = Any()

    fun isAlive(): Boolean = process?.isAlive == true

    /** Inicie un subproceso y monte el bucle de procesamiento stdout/stderr. Devuelve si el inicio fue exitoso. */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        val argv = when (val r = McpStdioCommand.resolve(config.command, context)) {
            is McpStdioCommand.Result.Ok -> r.argv
            is McpStdioCommand.Result.Error -> {
                Logger.w(TAG, "[${config.name}] Falló el análisis del comando: ${r.message}")
                return@withContext false
            }
        }
        val workdir = File(MuseRuntime.runtimeDir(context), "mcp/${config.id}").apply { mkdirs() }
        runCatching {
            val pb = ProcessBuilder(argv)
            pb.directory(workdir)
            pb.environment().clear()
            pb.environment().putAll(MuseRuntime.buildEnv(context))
            val p = pb.start()
            process = p
            writer = p.outputStream.bufferedWriter()
            mountStdout(p)
            mountStderr(p)
            mountExit(p)
            Logger.i(TAG, "[${config.name}] stdio Proceso iniciado: ${argv.firstOrNull()}")
            true
        }.getOrElse {
            Logger.e(TAG, "[${config.name}] stdio El inicio falló: ${it.message}", it)
            stop()
            false
        }
    }

    /** stdout bucle de fila → JSON Distribución de mensajes. */
    private fun mountStdout(p: Process) {
        scope.launch(Dispatchers.IO) {
            val reader = p.inputStream.bufferedReader()
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && trimmed.startsWith("{")) {
                        runCatching { AppJson.parseToJsonElement(trimmed) as? JsonObject }
                            .getOrNull()
                            ?.let(onMessage)
                    }
                }
            } catch (_: Exception) {
                // readLine se lanza cuando finaliza el proceso/EOF,Salir del bucle en silencio
            }
        }
    }

    /** stderr → Registro (límite actual,Evite deslizar la pantalla del lado del servidor). */
    private fun mountStderr(p: Process) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            runCatching {
                p.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { ln ->
                        if (ln.isNotBlank() && count < 300) {
                            count++
                            Logger.d(TAG, "[${config.name}] $ln")
                        }
                    }
                }
            }
        }
    }

    /** Monitoreo de salidas de proceso (vía [onExit] devolución de llamada; parada activa distinguida por el llamante). */
    private fun mountExit(p: Process) {
        scope.launch(Dispatchers.IO) {
            val code = runCatching { p.waitFor() }.getOrDefault(-1)
            // Devolución de llamada solo cuando el proceso actual sigue siendo esta instancia (para evitar que la salida del proceso anterior afecte al nuevo proceso después del reinicio)
            if (process === p) {
                onExit(code)
            }
        }
    }

    /** Envía una línea de JSON (seguro para subprocesos; devuelve falso si el proceso no está disponible). */
    fun send(json: String): Boolean {
        val w = writer ?: return false
        return runCatching {
            synchronized(writeLock) {
                w.write(json)
                w.write("\n")
                w.flush()
            }
            true
        }.getOrElse {
            Logger.w(TAG, "[${config.name}] stdin Error de escritura: ${it.message}")
            false
        }
    }

    /** Detener el proceso hijo (idempotente). */
    fun stop() {
        runCatching { writer?.close() }
        writer = null
        process?.let { p ->
            p.destroy()
            runCatching {
                if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        process = null
    }

    companion object {
        private const val TAG = "McpStdioTransport"
    }
}
