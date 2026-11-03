package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * herramienta de ejecución de código(execute_javascript).
 *
 * Habilite a los agentes de IA para ejecutar código JavaScript en una zona de pruebas:Cálculos matemáticos, procesamiento de datos, algoritmos simples, etc.,
 * Amplíe las capacidades del agente. delegación subyacente [JsSandbox](WebView evaluateJavascript),No se agregan nuevas dependencias.
 *
 * parámetros de entrada:
 *  - code: Requerido,JS cadena de código
 *  - timeout_ms: Opcional,Tiempo de espera en milisegundos,Predeterminado 10000(10 segundos)
 *
 * Estructura de devolución(JSON):
 *  - result: Valor de retorno de ejecución(JSON forma de cadena)
 *  - logs: console.log/warn/error Matriz de registros recopilados
 *  - error: mensaje de error(tiempo de espera/JS anormal);Cuando la ejecución es exitosa, es null
 *
 * Instrucciones de puente:
 *  - [execute] es una función de suspensión(Interfaz de requisitos de tarea),Para llamadas directas de llamadores de rutina como SkillExecutor
 *  - [executeFromArgs] es una función sincrónica,Adaptar la firma ToolFn de ToolRegistry `(Map<String,String>) -> String`,
 *    RunBlocking utilizado internamente para establecer un puente suspend — porque [ToolRouteExecutionGuard.executeFromJson]
 *    Llamado en rutina IO,Bloquear hilo IO
 *    Espere la devolución de llamada de WebView del hilo principal,El hilo principal no está bloqueado.,Sin riesgo de estancamiento
 */
object CodeExecutionTool {

    /** Nombre de la herramienta(Registrado en ToolRegistry id). */
    const val TOOL_NAME = "execute_javascript"

    /** Tiempo de espera predeterminado en milisegundos. */
    private const val DEFAULT_TIMEOUT_MS = 10000L

    /**
     * Definición de herramienta(Regístrese para ToolRegistry).
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description =
            "Ejecute código JavaScript y devuelva resultados. Bibliotecas estándar disponibles como Math/JSON/Array;" +
                "El guión puede return Promise,La herramienta esperará a que se complete antes de devolver el resultado real." +
                "red:fetch/XHR Iniciado de forma nativa por el anfitrión(No es una solicitud del navegador),Por lo tanto**No restringido por CORS**," +
                "http/https todos pueden acceder;Se registrarán registros de auditoría para cada transacción saliente." +
                "No se puede saltar a páginas ni leer archivos locales. El tiempo de espera es de 10 segundos.",
        parameters = mapOf(
            "code" to "Requerido,Código JavaScript para ejecutar(Como por ejemplo '1+2*3' / 'Math.max(1,2,3)' / 'JSON.stringify([1,2,3])')",
            "timeout_ms" to "Opcional,Tiempo de espera en milisegundos,Predeterminado 10000(10 segundos)",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * Ejecutar código JavaScript(suspend interfaz,Llamado por corrutinas como SkillExecutor).
     *
     * @param input Objeto JSON que contiene código/timeout_ms
     * @return JSON objeto,contiene result / logs / error campo
     */
    suspend fun execute(input: JsonObject): JsonObject {
        // Analizar el parámetro de código requerido
        val codeElement = input["code"]
        val code = (codeElement as? JsonPrimitive)?.content
            ?: return buildResult(
                result = null,
                logs = emptyList(),
                error = "Falta el código de parámetro o no es una cadena",
            )
        if (code.isBlank()) {
            return buildResult(
                result = null,
                logs = emptyList(),
                error = "El código de parámetro no puede estar vacío",
            )
        }

        // Analizar el parámetro opcional timeout_ms
        val timeoutMs = (input["timeout_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: DEFAULT_TIMEOUT_MS
        if (timeoutMs <= 0 || timeoutMs > 60_000L) {
            return buildResult(
                result = null,
                logs = emptyList(),
                error = "El parámetro timeout_ms debe estar en el rango de 1..60000",
            )
        }

        // Llame a JsSandbox para ejecutar
        val result = JsSandbox.execute(code, timeoutMs)
        return result.fold(
            onSuccess = { jsResult ->
                buildResult(
                    result = jsResult.value,
                    logs = jsResult.consoleLogs,
                    error = jsResult.error,
                )
            },
            onFailure = { e ->
                Logger.e("CodeExecutionTool", "JsSandbox La ejecución falló: ${e.message}", e)
                // La falta de disponibilidad del entorno y los errores de escritura de código deben informarse por separado: lo primero no funcionará incluso si lo intenta nuevamente 10,000 veces.
                // Si la copia anterior revela el nombre de la clase interna al modelo, se considerará un error de parámetro y el reintento será en vano.
                val msg = if (e is JsSandbox.JsSandboxUnavailableException) {
                    "JS El entorno de ejecución no está disponible (la autoprueba del entorno falló:${e.message}). Esto no es un problema con la forma en que está escrito el código. Intentar el mismo fragmento de código nuevamente no tendrá éxito;" +
                        "Utilice enable_node_script en su lugar o informe al usuario que el entorno limitado de JS nativo no está disponible temporalmente."
                } else {
                    e.message ?: "Error de ejecución desconocido"
                }
                buildResult(
                    result = null,
                    logs = emptyList(),
                    error = msg,
                )
            },
        )
    }

    /**
     * Puente sincrónico:Adapte la firma ToolFn de ToolRegistry.
     *
     * Para uso interno [runBlocking] llamar suspend [execute]:
     *  - [ToolRouteExecutionGuard.executeFromJson] Llamado en rutina IO,
     *    El hilo actual es un hilo IO,El hilo principal está inactivo cuando se bloquea esperando la devolución de llamada de WebView del hilo principal,Sin riesgo de estancamiento
     *  - Primero se debe ajustar [JsSandbox.init] inyectar Application Context(idempotente)
     *
     * @param args parámetros map(code / timeout_ms)
     * @param context para inicialización JsSandbox
     * @return JSON cuerda,Misma estructura [execute] El resultado de la serialización de JsonObject
     */
    fun executeFromArgs(args: Map<String, String>, context: Context): String {
        JsSandbox.init(context)
        // poner Map<String, String> Convierta a JsonObject y luego delega en suspend execute
        val input = buildJsonObject {
            args["code"]?.let { put("code", it) }
            args["timeout_ms"]?.let { put("timeout_ms", it) }
        }
        return runBlocking {
            val output = execute(input)
            output.toString()
        }
    }

    /**
     * Resultado de la ejecución de la construcción JsonObject.
     *
     * convención de campo(Requisitos de la misión):
     *  - result: string — Valor de retorno de ejecución(JSON forma de cadena,null Indica que no hay valor de retorno)
     *  - logs: array — console matriz de registro,Cada elemento es una cadena.
     *  - error: string? — mensaje de error,Cuando la ejecución es exitosa, es null
     */
    private fun buildResult(result: Any?, logs: List<String>, error: String?): JsonObject = buildJsonObject {
        // result siempre string(Requisitos de la misión);Utilice una cadena vacía cuando no haya valor de retorno
        val resultStr = when (result) {
            null -> ""
            is String -> result
            else -> result.toString()
        }
        put("result", resultStr)
        // logs matriz
        put(
            "logs",
            buildJsonArray {
                logs.forEach { add(JsonPrimitive(it)) }
            },
        )
        // error puede ser null
        if (error != null) {
            put("error", error)
        } else {
            put("error", JsonNull)
        }
    }
}
