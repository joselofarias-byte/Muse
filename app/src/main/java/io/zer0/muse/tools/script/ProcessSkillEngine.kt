package io.zer0.muse.tools.script

import android.content.Context
import android.util.JsonReader
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.MuseRuntime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/**
 * P1-C: Basado en el tiempo de ejecución de Node incorporado [SkillEngine] Darse cuenta.
 *
 * con [WebViewSkillEngine] La diferencia:
 *  - WebView Versión: sandbox ligero (sin sistema de archivos/sin API de nodo; la red está abierta), adecuada para informática pura;
 *  - Esta implementación: entorno real de Node.js (capacidades completas:fs / módulo web/child_process/npm),
 *    Para tareas de script que "requieren habilidades reales" (los scripts de habilidades están conectados al tiempo de ejecución del nodo).
 *
 * Método de implementación: envuelva el script en un archivo JS temporal → Ejecución de nodo incorporado → Analiza los resultados estructurados de stdout.
 * Protocolo de salida (JSON de una sola línea):{ ok: boolean, value: unknown, logs: string[], error?: string }
 * Semántica alineada con los resultados de JsSandbox (valueJson + consoleLogs).
 */
class ProcessSkillEngine(private val context: Context) : SkillEngine {

    @Suppress("TooGenericExceptionCaught") // Node Explicación unificada del fallo del proceso hijo (tiempo de espera/empezar/IO)
    override suspend fun eval(script: String, timeoutMs: Long, scopeKey: String?, pluginConfigJson: String?): SkillEngineResult {
        val work = File(MuseRuntime.runtimeDir(context), "scripts").apply { mkdirs() }
        val tmp = File(work, "eval-${UUID.randomUUID()}.js")
        return try {
            tmp.writeText(buildWrapper(script, pluginConfigJson))
            val r = MuseRuntime.execNode(
                context,
                args = listOf(tmp.absolutePath),
                timeoutMs = timeoutMs,
            )
            if (r.captureError != null) {
                val savedOutput = listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
                return SkillEngineResult.Error(
                    message = "Node No se pudo guardar la salida completa: ${r.captureError}" +
                        if (savedOutput.isNotBlank()) "\n$savedOutput" else "",
                )
            }
            if (r.timedOut) {
                return SkillEngineResult.Error(
                    message = "Tiempo de espera de ejecución" + listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n"),
                )
            }
            r.stdoutFile?.let { return parseLargeNodePayload(it, r.stdout) }
            parseResult(r.stdout, r.stderr, r.exitCode, r.timedOut)
        } catch (e: Exception) {
            Logger.w(TAG, "Node La ejecución del script falló: ${e.message}", e)
            SkillEngineResult.Error(message = e.message ?: "excepción de ejecución")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    override fun interrupt() {
        // Haz tu mejor esfuerzo:Node Los procesos secundarios finalizan mediante el control de tiempo de espera del lado de la ejecución; aquí no se mantiene ninguna referencia de proceso.
        Logger.d(TAG, "interrupt() Llamado (esperando que finalice el tiempo de espera del lado de ejecución)")
    }

    /**
     * Generar contenedor de ejecución:
     *  - La entrada del script se instala como una cubierta de CPU ([NodeCpuShim]): nativo os.cpus() Sintetizar por MUSE_CPU_COUNT cuando esté vacío
     *  - Inyectar el puente host.getConfig (alineado con la semántica del sandbox de WebView)
     *  - Capturar console.* Iniciar sesión
     *  - eval Ejecutar script de usuario (Promise espera automática)
     *  - Protocolo de resultados JSON de salida de una sola línea
     */
    private fun buildWrapper(script: String, pluginConfigJson: String?): String {
        val scriptJson = JsonPrimitive(script).toString()
        val configJson = pluginConfigJson ?: "null"
        val nodeModules = File(MuseRuntime.runtimeDir(context), "node_modules").absolutePath
        val body =
            """
            |// muse-node-skill-wrapper (auto-generated)
            |globalThis.__musePluginConfig = $configJson;
            |if (typeof globalThis.host === 'undefined') {
            |  globalThis.host = {
            |    getConfig: function(key) {
            |      var v = globalThis.__musePluginConfig && globalThis.__musePluginConfig[key];
            |      return v !== undefined ? JSON.stringify(v) : null;
            |    },
            |    getPluginId: function() { return null; }
            |  };
            |}
            |module.paths.push(${JsonPrimitive(nodeModules)});
            |(async function() {
            |  var __logs = [];
            |  var __fmt = function(a) {
            |    try { return typeof a === 'string' ? a : JSON.stringify(a); } catch (e) { return String(a); }
            |  };
            |  ['log','warn','error','info'].forEach(function(k) {
            |    console[k] = function() {
            |      __logs.push('[' + k + '] ' + Array.prototype.map.call(arguments, __fmt).join(' '));
            |    };
            |  });
            |  var __out;
            |  try {
            |    __out = eval($scriptJson);
            |    if (__out && typeof __out.then === 'function') { __out = await __out; }
            |    var __payload = { ok: true, value: null, logs: __logs };
            |    try { __payload.value = (__out === undefined) ? null : JSON.parse(JSON.stringify(__out)); }
            |    catch (e) { __payload.value = String(__out); }
            |    process.stdout.write(JSON.stringify(__payload));
            |  } catch (e) {
            |    process.stdout.write(JSON.stringify({ ok: false, error: String((e && e.message) || e), logs: __logs }));
            |  }
            |})();
        """.trimMargin()
        // La primera oración del script es instalar la CPU primero: nativo os.cpus() Sintetizado con MUSE_CPU_COUNT cuando está vacío,
        // De lo contrario, los paquetes npm que dependen de la cantidad de núcleos se ejecutarán en 0/1 núcleo en algunos dispositivos. El alcance del script normal y los valores de retorno no se ven afectados.
        return NodeCpuShim.wrap(body)
    }

    private fun parseResult(stdout: String, stderr: String, exitCode: Int, timedOut: Boolean): SkillEngineResult {
        if (timedOut) {
            return SkillEngineResult.Error(message = "Tiempo de espera de ejecución")
        }
        val line = stdout.lines().lastOrNull { it.trim().startsWith("{") }?.trim()
        return if (line == null) {
            SkillEngineResult.Error(
                message = "Sin salida válida(exit=$exitCode)" +
                    if (stderr.isNotBlank()) ": ${stderr.take(300)}" else "",
            )
        } else {
            parseLine(line)
        }
    }

    /** Analice la línea de salida del protocolo contenedor ({ ok, value, logs, error }). */
    private fun parseLine(line: String): SkillEngineResult = runCatching {
        val obj = AppJson.parseToJsonElement(line) as? JsonObject
        if (obj == null) {
            SkillEngineResult.Error(message = "Error al analizar la salida: ${line.take(200)}")
        } else {
            val logs = (obj["logs"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val ok = (obj["ok"] as? JsonPrimitive)?.content?.toBoolean() == true
            if (ok) {
                SkillEngineResult.Success(
                    valueJson = obj["value"]?.toString() ?: "null",
                    consoleLogs = logs,
                )
            } else {
                SkillEngineResult.Error(
                    message = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: "error desconocido",
                    consoleLogs = logs,
                )
            }
        }
    }.getOrElse { e ->
        SkillEngineResult.Error(message = "Excepción de análisis de resultados: ${e.message}")
    }

    companion object {
        private const val TAG = "ProcessSkillEngine"
    }
}

internal fun parseLargeNodePayload(file: File, outputReference: String): SkillEngineResult {
    val succeeded =
        runCatching {
            var success: Boolean? = null
            JsonReader(file.reader(Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "ok" -> success = reader.nextBoolean()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            success ?: error("Node A la salida le falta el estado correcto")
        }.getOrNull()

    return when (succeeded) {
        true ->
            SkillEngineResult.Success(
                valueJson = JsonPrimitive(outputReference).toString(),
                consoleLogs = emptyList(),
            )
        false ->
            SkillEngineResult.Error(
                message = "Node La ejecución del script falló; La salida completa se puede leer en partes a través de read_file.:\n$outputReference",
            )
        null ->
            SkillEngineResult.Error(
                message = "Node Error al analizar la salida; la salida original permanece intacta:\n$outputReference",
            )
    }
}
