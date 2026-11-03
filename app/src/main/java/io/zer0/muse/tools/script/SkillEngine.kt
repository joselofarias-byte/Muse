package io.zer0.muse.tools.script

import io.zer0.muse.tools.JsSandbox

/**
 * Skill capa de abstracción del motor (P3-1).
 *
 * Defina un contrato unificado para la ejecución del script JS para habilitar la capa superior (SkillExecutor / Herramienta JS complementaria)
 * No depende directamente de la implementación de un motor específico (actualmente WebView V8, que puede ser reemplazado por QuickJS en el futuro).
 *
 * Puntos de diseño:
 *  - Las instancias del motor no son reentrantes: solo se puede ejecutar un script al mismo tiempo (WebView evaluateJavascript serie)
 *  - El mecanismo de tiempo de espera está garantizado por la capa de implementación ([JsSandbox.execute] 10 por defecto)
 *  - Las restricciones de seguridad están garantizadas por la capa de implementación (los saltos de página y el acceso a archivos locales están prohibidos, y la red está disponible; consulte [JsSandbox.INIT_JS])
 *
 * Descripción: Contrato de interfaz para el diseño backend WebView V8 de Muse.
 *
 * **estado de producción**: Esta interfaz es el contrato de ejecución de habilidades JS y el enlace de producción pasa [WebViewSkillEngine]
 * (.muse-plugin / Ejecución de la herramienta incorporada ejecutar_javascript);`__bridge__` puenteado por
 * [SkillBridge] Procesado en el resultado de la ejecución del script.
 */
interface SkillEngine {

    /**
     * Ejecutar expresión JS/Bloque de declaración, devuelve el resultado serializado JSON.
     *
     * @param script JS Código (expresión, bloque de declaraciones o llamada de función)
     * @param timeoutMs Tiempo de espera en milisegundos, por defecto 10 segundos
     * @param scopeKey C-30: Ejecute el complemento de su propiedad id(Transferencia de herramientas complementarias pluginId),Para aislamiento de sandbox JS mediante complemento
     *   estado del fusible;Incorporado/Las herramientas que no son complementos no utilizan el valor global predeterminado scope
     * @param pluginConfigJson B7-01: Cadena JSON de configuración del complemento,para inyección host.getConfig() puente.null Se omite la inyección.
     * @return Resultado de la ejecución: exitoso [SkillEngineResult.Success] Contiene valores JSON y registros de consola;
     *         en el fracaso [SkillEngineResult.Error] Contiene mensajes de error
     */
    suspend fun eval(
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
    ): SkillEngineResult

    /**
     * Llame a una función definida en un script cargado.
     *
     * primero [eval] Cargue la definición de función y luego llame al nombre de función especificado.
     *
     * @param functionDef Código JS de definición de función (como `function foo(a,b){return a+b}`)
     * @param functionName nombre de la función a llamar
     * @param argsJson Cadena JSON de matriz de parámetros (como `[1,2]`)
     * @param timeoutMs Tiempo de espera en milisegundos
     * @param scopeKey C-30: Ejecute el complemento de su propiedad id(Transferencia de herramientas complementarias pluginId),pasar por [eval]
     * @return Resultado de la ejecución
     */
    suspend fun callFunction(
        functionDef: String,
        functionName: String,
        argsJson: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        scopeKey: String? = null,
        pluginConfigJson: String? = null,
    ): SkillEngineResult {
        // Implementación predeterminada: empalmar llamadas IIFE
        val combined = buildString {
            append(functionDef)
            append("\n;")
            append("JSON.stringify(")
            append(functionName)
            append(".apply(null, ")
            append(argsJson)
            append("))")
        }
        return eval(combined, timeoutMs, scopeKey, pluginConfigJson)
    }

    /**
     * interrumpir la ejecución actual (mejor esfuerzo,WebView V8 Realmente no puedo romper JS).
     * La implementación debería permitir que el lado de Kotlin devuelva un error después de un tiempo de espera.
     */
    fun interrupt()

    companion object {
        /** El tiempo de espera predeterminado es de 10 segundos. */
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

/**
 * Skill Clase de sello de resultado de ejecución del motor.
 *
 * Utilice clase sellada en lugar de cualquier tipo de unión (conforme a AGENTS.md §5 especificación de seguridad de tipo).
 */
sealed class SkillEngineResult {
    /**
     * Ejecución exitosa.
     *
     * @param valueJson Devuelve una cadena JSON de valores (posiblemente "null" / "42" / '"text"' / '[1,2]' etc)
     * @param consoleLogs console.log/warn/error Registros recopilados
     */
    data class Success(
        val valueJson: String,
        val consoleLogs: List<String>,
    ) : SkillEngineResult()

    /**
     * Error de ejecución (tiempo de espera/excepción JS/motor no disponible).
     *
     * @param message mensaje de error
     * @param consoleLogs Registros recopilados antes del error (pueden ser útiles para solucionar problemas)
     */
    data class Error(
        val message: String,
        val consoleLogs: List<String> = emptyList(),
    ) : SkillEngineResult()
}
