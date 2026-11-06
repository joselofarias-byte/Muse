package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.PythonRuntime
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v2.7 Tiempo de ejecución extendido (Python):Python Herramienta de ejecución de scripts (execute_python_script).
 *
 * Ejecute scripts de Python en el tiempo de ejecución de Python integrado: biblioteca estándar completa (incluidos ssl/sqlite3/ctypes, etc.)
 * C extensión), lectura y escritura de archivos, red. si `import` Área de paquetes de sitio en tiempo de ejecución
 * (filesDir/muse-python/lib/python3.14/site-packages).
 *
 * con [NodeScriptTool](execute_node_script) Isomorfismo: herramientas de alto riesgo,ASK / STRICT modo
 * solicitar aprobación;TRUSTED El modo está sujeto a la selección explícita del usuario sin confirmación.
 *
 * estructura de retorno (JSON, alineado con ejecutar_node_script / ejecutar_javascript):
 *  - result: string — guión `result` Forma de cadena JSON de variable
 *  - logs: array — print Matriz de filas de salida
 *  - error: string | null
 */
object PythonScriptTool {

    const val TOOL_NAME = "execute_python_script"

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_SCRIPT_CHARS = 128_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Ejecute scripts de Python en el tiempo de ejecución integrado de Python 3.14(Biblioteca estándar completa:ssl/sqlite3/" +
            "ctypes/json/re espera;Lectura y escritura de archivos, red.;Puede importar paquetes de Python puros de terceros)." +
            "Asigne el resultado que se devolverá a la variable resultado. Regresar JSON(result/logs/error). El tiempo de espera predeterminado es 30 segundos." +
            "Este es el único punto de entrada para ejecutar Python.:No hay ningún comando python/python3 en el tiempo de ejecución integrado,No lo uses " +
            "terminal_exec/execute_shell Encuentre el intérprete o explore el directorio bin(esta vacio ahi);" +
            "Cuando necesite iniciar un proceso en el script, use sys.executable o os.environ['MUSE_PYTHON_BIN'].",
        parameters = mapOf(
            "code" to "Requerido,Python código de secuencia de comandos(Biblioteca estándar importable;Asignar resultado a variable result)",
            "timeout_ms" to "Opcional,Tiempo de espera en milisegundos,Predeterminado 30000,máximo 300000",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(context: Context, args: Map<String, String>): String {
        val code = args["code"].orEmpty()
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        return when {
            code.isBlank() -> errorJson("Falta el código de parámetro o está vacío")
            code.length > MAX_SCRIPT_CHARS -> errorJson("El guión supera la longitud máxima($MAX_SCRIPT_CHARS personaje)")
            else -> formatResultJson(evaluate(context, code, timeoutMs))
        }
    }

    /** Rutas de ejecución compartidas (para reutilización mediante herramientas de script directas y flujos de trabajo de agentes celulares). */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("Falta el código de parámetro o está vacío")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("El guión supera la longitud máxima($MAX_SCRIPT_CHARS personaje)")
            else -> {
                // Garantizar los datos en tiempo de ejecución (stdlib) ha sido descomprimido; se descomprimirá una vez cuando se llame por primera vez.
                PythonRuntime.ensureData(context).getOrElse { e ->
                    return SkillEngineResult.Error("Python Los datos de tiempo de ejecución no están listos: ${e.message}")
                }
                PythonSkillEngine(context).eval(
                    script = code,
                    timeoutMs = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS),
                    scopeKey = "builtin-python-script",
                    pluginConfigJson = null,
                )
            }
        }
    }

    fun formatResultJson(result: SkillEngineResult): String = when (result) {
        is SkillEngineResult.Success -> resultJson(result.valueJson, result.consoleLogs, null)
        is SkillEngineResult.Error -> errorJson(result.message, result.consoleLogs)
    }

    /** Puente síncrono (adaptado a ToolRegistry `(Map<String,String>) -> String` firma). */
    fun executeFromArgs(args: Map<String, String>, context: Context): String = runBlocking {
        execute(context, args)
    }

    private fun resultJson(result: String, logs: List<String>, error: String?): String = buildJsonObject {
        put("result", result)
        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
        put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    private fun errorJson(error: String, logs: List<String> = emptyList()): String = buildJsonObject {
        put("result", "")
        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
        put("error", JsonPrimitive(error))
    }.toString()
}

/**
 * v2.7 Tiempo de ejecución extendido (Python):Python Registrador de herramientas de ejecución de scripts.
 */
class PythonScriptToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PythonScriptTool.toolDef()) { args ->
            PythonScriptTool.executeFromArgs(args, context)
        }
    }
}
