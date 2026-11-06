package io.zer0.muse.tools.script

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.runtime.PythonRuntime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

/**
 * v2.7 Tiempo de ejecución extendido (Python): basado en el tiempo de ejecución integrado de Python [SkillEngine] Darse cuenta.
 *
 * con [ProcessSkillEngine](Node) Isomorfismo: envuelve el guión en una capa → ejecución de proceso hijo → analizar salida estándar
 * Resultados estructurados. El protocolo de salida sigue siendo coherente (JSON de una sola línea:`{ ok, value, logs, error }`),
 * Por lo tanto, las herramientas de la capa superior, la ubicación de discos de salida grandes, el procesamiento de tiempo de espera y otros mecanismos se pueden reutilizar directamente.
 *
 * ## Diferencias con la versión de Node
 *
 *  - El script contenedor es Python, no JS (captura salida estándar, inyecta protocolo de resultado);
 *  - usar [PythonRuntime.execPython] ejecutar;
 *  - `host.getConfig` Los puentes se inyectan como funciones de Python (alineadas con la semántica de la versión de Node).
 *
 * ## Protocolo de salida
 *
 * El contenedor imprime una sola línea de JSON al final del script:
 * ```
 * {"ok": true, "value": <Cualquier objeto JSONizable>, "logs": ["..."]}
 * ```
 * Cuando el guión es anormal, es `{"ok": false, "error": "...", "logs": [...]}`.
 */
class PythonSkillEngine(private val context: Context) : SkillEngine {

    @Suppress("TooGenericExceptionCaught") // Python Explicación unificada del fallo del proceso hijo (tiempo de espera/empezar/IO)
    override suspend fun eval(
        script: String,
        timeoutMs: Long,
        scopeKey: String?,
        pluginConfigJson: String?,
    ): SkillEngineResult {
        val work = File(PythonRuntime.runtimeDir(context), "scripts").apply { mkdirs() }
        val tmp = File(work, "eval-${UUID.randomUUID()}.py")
        return try {
            tmp.writeText(buildWrapper(script, pluginConfigJson))
            val r = PythonRuntime.execPython(
                context,
                args = listOf(tmp.absolutePath),
                timeoutMs = timeoutMs,
                workdir = sandboxDir(scopeKey) ?: PythonRuntime.homeDir(context),
                env = sandboxEnv(scopeKey),
            )
            interpret(r)
        } catch (e: Exception) {
            Logger.w(TAG, "Python La ejecución del script falló: ${e.message}", e)
            SkillEngineResult.Error(message = e.message ?: "excepción de ejecución")
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * Directorio de zona de pruebas del complemento (`filesDir/workspace/plugins/<pluginId>`).
     *
     * con complemento JS `fsRoot` Apuntando al mismo directorio: dos tiempos de ejecución sobre "¿dónde deben colocarse los archivos del complemento?"
     * Dada la misma respuesta, el autor del complemento no necesita cambiar la convención de ruta al cambiar de idioma.
     *
     * Devuelve nulo cuando no hay una clave de alcance (script integrado, llamada sin complemento) y se utiliza el directorio de trabajo predeterminado del tiempo de ejecución.
     */
    private fun sandboxDir(scopeKey: String?): File? =
        scopeKey?.takeIf { it.isNotBlank() }?.let { key ->
            File(context.filesDir, "workspace/plugins/$key").apply { mkdirs() }
        }

    /**
     * Variables de entorno Sandbox: apunte HOME / TMPDIR también al directorio del complemento.
     *
     * Simplemente configurar cwd no es suficiente —— Python archivos temporales,`~` expandir,pip El caché analiza estas dos variables.
     * Si no se cambian juntos, los elementos escritos por el complemento se dispersarán en el directorio público en tiempo de ejecución y se contaminarán entre sí.
     *
     * NOTA: Esto no impide que los complementos escriban rutas absolutas (sin aislamiento real del sistema de archivos bajo el mismo UID).
     * Lo que garantiza es que "el comportamiento predeterminado se encuentra en su propio directorio".
     */
    private fun sandboxEnv(scopeKey: String?): Map<String, String> {
        val dir = sandboxDir(scopeKey) ?: return emptyMap()
        val tmp = File(dir, ".tmp").apply { mkdirs() }
        return mapOf(
            "HOME" to dir.absolutePath,
            "TMPDIR" to tmp.absolutePath,
        )
    }

    /** Interpretar los resultados de la ejecución del proceso secundario como resultados del motor (divididos para controlar la cantidad de declaraciones devueltas). */
    private fun interpret(r: PythonRuntime.ExecResult): SkillEngineResult {
        val savedOutput = listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n")
        return when {
            r.captureError != null ->
                SkillEngineResult.Error(
                    message = "Python No se pudo guardar la salida completa: ${r.captureError}" +
                        if (savedOutput.isNotBlank()) "\n$savedOutput" else "",
                )
            r.timedOut -> SkillEngineResult.Error(message = "Tiempo de espera de ejecución" + savedOutput)
            r.stdoutFile != null -> parseLargePayload(r.stdoutFile, r.stdout)
            else -> parseResult(r.stdout, r.stderr, r.exitCode, r.timedOut)
        }
    }

    override fun interrupt() {
        // Mejor esfuerzo: el proceso hijo finaliza mediante el control de tiempo de espera del lado de la ejecución; aquí no se mantiene ninguna referencia de proceso.
        Logger.d(TAG, "interrupt() Llamado (esperando que finalice el tiempo de espera del lado de ejecución)")
    }

    /**
     * v2.7 Complemento: llame a la función definida en el módulo de entrada del complemento de Python.
     *
     * con [SkillEngine.callFunction](JS Semántica) correspondiente: cargue el código de entrada en un espacio de nombres independiente,
     * Saque la función con el mismo nombre y llámela con el parámetro dict. El parámetro JSON (objeto único) de la herramienta complementaria se utiliza como primer parámetro de la función.
     *
     * @param entryCode Entrada de complemento Código fuente de Python
     * @param functionName El nombre de la función definida en la entrada (acepta un parámetro dict)
     * @param argsJson Llame a la matriz de parámetros JSON (formada como `[{"x":1}]`;Tomar el primer elemento como parámetro de función)
     * @param scopeKey ID de enchufe (fusible de aislamiento/espacio de nombres)
     * @param pluginConfigJson Configuración del complemento JSON (host.getConfig uso)
     */
    @Suppress("LongParameterList") // Mismo tipo que SkillEngine.callFunction: entrada/nombre de la función/parámetros/tiempo de espera/Alcance/Configuración, semántica de interfaz arreglada.
    suspend fun callPluginFunction(
        entryCode: String,
        functionName: String,
        argsJson: String,
        timeoutMs: Long = SkillEngine.DEFAULT_TIMEOUT_MS,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
    ): SkillEngineResult {
        val wrapper = buildPluginCallWrapper(entryCode, functionName, argsJson, pluginConfigJson)
        return eval(wrapper, timeoutMs, scopeKey, pluginConfigJson)
    }

    /**
     * Generar contenedor de llamada de función de complemento: cargando entrada → Obtener función → Llamar con el primer parámetro → Protocolo de resultados de salida.
     *
     * El código de entrada puede definir múltiples funciones; Se da un error legible cuando la función no existe (en lugar de fallar silenciosamente).
     */
    private fun buildPluginCallWrapper(entryCode: String, functionName: String, argsJson: String, pluginConfigJson: String?): String {
        val entryJson = JsonPrimitive(entryCode).toString()
        val fnJson = JsonPrimitive(functionName).toString()
        val argsJsonLiteral = JsonPrimitive(argsJson).toString()
        val configPy = pluginConfigJson ?: "None"
        return """
            |import json as __json
            |__muse_plugin_config = $configPy
            |__muse_entry = $entryJson
            |__muse_fn_name = $fnJson
            |__muse_args_raw = $argsJsonLiteral
            |
            |__muse_ns = {"__name__": "__muse_plugin__"}
            |exec(compile(__muse_entry, "<muse-plugin>", "exec"), __muse_ns)
            |__muse_fn = __muse_ns.get(__muse_fn_name)
            |if __muse_fn is None:
            |    raise AttributeError("Función indefinida de entrada de complemento %s" % __muse_fn_name)
            |__muse_args = __json.loads(__muse_args_raw)
            |__muse_arg = __muse_args[0] if __muse_args else {}
            |result = __muse_fn(__muse_arg)
            |
        """.trimMargin()
    }

    /**
     * Generar contenedor de ejecución:
     *  - Inyectar el puente host.getConfig (alineado con la semántica del sandbox de Node)
     *  - Capturando la salida stdout/stderr como logs
     *  - Ejecutar script de usuario (código de nivel superior/resultado de expresión)
     *  - Protocolo de resultados JSON de salida de una sola línea
     *
     * Estrategia de empaquetado: coloque el script de usuario en un espacio de nombres independiente para su ejecución y tome su valor de retorno (si corresponde) o
     * Variables a nivel de módulo `result`. Si el script imprime líneas de protocolo JSON, eso tendrá prioridad.
     */
    private fun buildWrapper(script: String, pluginConfigJson: String?): String {
        val scriptJson = JsonPrimitive(script).toString()
        // Configuración del complemento:JS El nulo en Python es Ninguno. Al pasar nulo, páselo directamente a Ninguno.
        val configPy = pluginConfigJson ?: "None"
        return """
            |# muse-python-skill-wrapper (auto-generated)
            |import sys, json, io
            |
            |__muse_plugin_config = $configPy
            |
            |class __MuseHost:
            |    def getConfig(self, key):
            |        v = (__muse_plugin_config or {}).get(key)
            |        return json.dumps(v) if v is not None else None
            |    def getPluginId(self):
            |        return None
            |
            |host = __MuseHost()
            |
            |__muse_script = $scriptJson
            |__muse_logs = []
            |
            |def __muse_emit(ok, value=None, error=None):
            |    payload = {"ok": ok, "value": value, "logs": __muse_logs}
            |    if error is not None:
            |        payload["error"] = error
            |    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
            |    sys.stdout.flush()
            |
            |try:
            |    __muse_ns = {"host": host, "__name__": "__muse_script__", "logs": __muse_logs}
            |    __muse_buf = io.StringIO()
            |    __muse_old_stdout = sys.stdout
            |    sys.stdout = __muse_buf
            |    try:
            |        exec(compile(__muse_script, "<muse-script>", "exec"), __muse_ns)
            |    finally:
            |        sys.stdout = __muse_old_stdout
            |        __muse_printed = __muse_buf.getvalue()
            |        if __muse_printed:
            |            __muse_logs.append(__muse_printed.rstrip("\n"))
            |    __muse_value = __muse_ns.get("result", None)
            |    try:
            |        json.dumps(__muse_value, ensure_ascii=False)
            |    except (TypeError, ValueError):
            |        __muse_value = str(__muse_value)
            |    __muse_emit(True, __muse_value)
            |except SystemExit as e:
            |    __muse_emit(True, None if e.code is None else e.code)
            |except BaseException as e:
            |    __muse_emit(False, None, "%s: %s" % (type(e).__name__, e))
        """.trimMargin()
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
        private const val TAG = "PythonSkillEngine"
    }
}

/**
 * Escenario de gran producción:Python Las líneas de protocolo resultantes se han incluido en el archivo de salida y solo se leen aquí `ok` estado,
 * Evite cargar valores grandes en la memoria por completo.
 */
internal fun parseLargePayload(file: File, outputReference: String): SkillEngineResult {
    val succeeded =
        runCatching {
            var success: Boolean? = null
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val protocolLine = lines.lastOrNull { it.trim().startsWith("{") }
                    ?: error("Python A la salida le faltan líneas de protocolo")
                val obj = AppJson.parseToJsonElement(protocolLine.trim()) as? JsonObject
                success = (obj?.get("ok") as? JsonPrimitive)?.content?.toBoolean()
            }
            success ?: error("Python A la salida le falta el estado correcto")
        }.getOrNull()

    return when (succeeded) {
        true ->
            SkillEngineResult.Success(
                valueJson = JsonPrimitive(outputReference).toString(),
                consoleLogs = emptyList(),
            )
        false ->
            SkillEngineResult.Error(
                message = "Python La ejecución del script falló; La salida completa se puede leer en partes a través de read_file.:\n$outputReference",
            )
        null ->
            SkillEngineResult.Error(
                message = "Python Error al analizar la salida; la salida original permanece intacta:\n$outputReference",
            )
    }
}
