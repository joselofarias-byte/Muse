package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.tools.script.ProcessSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P1-C: Node Herramienta de ejecución de scripts (execute_node_script).
 *
 * Ejecute scripts de JavaScript en el tiempo de ejecución de Node integrado: capacidades completas (lectura y escritura de archivos/red/subproceso),
 * Puede requerir la sección node_modules del tiempo de ejecución (filesDir/muse-runtime/node_modules) en el paquete npm.
 *
 * Herramientas de alto riesgo:ASK / STRICT El patrón requiere aprobación;TRUSTED El modo está sujeto a la selección explícita del usuario sin confirmación.
 *
 * estructura de retorno (JSON, alineado con ejecutar_javascript):
 *  - result: string — Valor de retorno de la ejecución (JSON forma de cadena)
 *  - logs: array — console matriz de registro
 *  - error: string | null
 */
object NodeScriptTool {

    const val TOOL_NAME = "execute_node_script"

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_SCRIPT_CHARS = 128_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Ejecute scripts de JavaScript en el tiempo de ejecución integrado de Node.js(capacidad total:Lectura y escritura de archivos, red, subproceso.;" +
            "Puede requerir módulos npm de terceros). Regresar JSON(result/logs/error). El tiempo de espera predeterminado es 30 segundos.",
        parameters = mapOf(
            "code" to "Requerido,Node código de secuencia de comandos(bloque de expresión o declaración;Disponible require/fs/fetch, etc.)",
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

    /** Shared execution path for the direct script tool and durable phone-agent workflows. */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("Falta el código de parámetro o está vacío")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("El guión supera la longitud máxima($MAX_SCRIPT_CHARS personaje)")
            else ->
                ProcessSkillEngine(context).eval(
                    script = code,
                    timeoutMs = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS),
                    scopeKey = "builtin-node-script",
                    pluginConfigJson = null,
                )
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
