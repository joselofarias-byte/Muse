package io.zer0.muse.tools

/**
 * v1.0.53: La herramienta ingresa reglas de seguridad de contenido.
 *
 * cada regla: toolName + Coincidencia de contenido + acción(interceptar)+ reason + ruleId.
 * Comprobaciones unificadas antes de la ejecución de la herramienta.;Pulsar devuelve el resultado rechazado(traer ruleId,Trazabilidad de registros).
 *
 * Nota: [ParamPolicies] Responsable de la interceptación en la capa de aprobación.(Visible para los usuarios),Esta regla es un límite estricto antes de la ejecución.
 * (Bloquear independientemente de la aprobación),Las dos líneas de defensa se complementan.
 */
data class ContentSafetyRule(
    val toolName: String,
    val matcher: (String) -> Boolean,
    val reason: String,
    val ruleId: String,
    val risk: String = "high",
    /**
     * Utilice únicamente el valor de este parámetro para hacer coincidir;null Indica la cadena después de concatenar todos los valores de los parámetros (comportamiento predeterminado).
     *
     * Las reglas de clase de ruta deben especificar este campo. De lo contrario, el contenido del texto se combinará con la ruta. `contains("..")`,
     * Escribir un texto que contenga elipses se bloqueará como cruce de camino.
     */
    val targetArg: String? = null,
)

object ContentSafetyRules {

    val RULES: List<ContentSafetyRule> = listOf(
        // execute_javascript modo peligro
        ContentSafetyRule(
            toolName = "execute_javascript",
            matcher = { it.contains("rm -rf") || it.contains("ProcessBuilder") || it.contains("Runtime.getRuntime().exec") },
            reason = "Las operaciones de ejecución de código peligroso están bloqueadas por políticas de seguridad.",
            ruleId = "js-dangerous-process",
        ),
        ContentSafetyRule(
            toolName = "execute_javascript",
            matcher = { input ->
                // H-SEC-3: Detección mejorada de fuga de tokens/claves
                // 1) Detección de ejecución de procesos peligrosos originales.
                val hasDangerousProcess = input.contains("rm -rf") ||
                    input.contains("ProcessBuilder") ||
                    input.contains("Runtime.getRuntime().exec")
                // 2) cookie fuga
                val hasCookieExfil = input.contains("document.cookie")
                // 3) Red saliente:**Búsqueda normal/XHR liberado**(La zona de pruebas se cambia a OkHttp del lado de Kotlin,
                //    Sin cookies, límite de 2 MB, registros de auditoría para cada solicitud, consulte JsSandbox.httpRequest).
                //    Lo que sigue bloqueado es "con credenciales/La forma de "Historia paralela activa":cookie,sendBeacon,WebSocket.
                //    Historial: originalmente, la búsqueda ordinaria estaba bloqueada aquí, lo que resultó en que las capacidades de red prometidas en la descripción de la herramienta no estuvieran disponibles en absoluto.
                val hasWsExfil = Regex("""new\s+WebSocket\s*\(\s*['"`]wss?://""").containsMatchIn(input)
                val hasBeacon = input.contains("sendBeacon") &&
                    Regex("""https?://""").containsMatchIn(input)
                // 4) La URL general saliente contiene palabras confidenciales
                val hasUrlWithSensitive = Regex("""https?://[^\s`'")\]]+""").findAll(input)
                    .any {
                        it.value.contains("token") || it.value.contains("secret") ||
                            it.value.contains("key=") || it.value.contains("auth")
                    }
                hasDangerousProcess || hasCookieExfil ||
                    hasWsExfil || hasBeacon || hasUrlWithSensitive
            },
            reason = "La política de seguridad interceptó información confidencial saliente sospechosa",
            ruleId = "js-exfil-token",
        ),
        // open_url archivo local
        ContentSafetyRule(
            toolName = "open_url",
            matcher = { it.startsWith("file://") },
            reason = "No permitir que se abran enlaces de archivos locales",
            ruleId = "url-local-file",
        ),
        // Los límites estrictos de la ruta de las herramientas de escritura de archivos se amplían según el nombre de la herramienta en writePathRules a continuación.:solo mira path,No toques el texto
    ) + writePathRules("workspace_write") + writePathRules("workspace_edit")

    /**
     * Ruta de límites estrictos compartidos por herramientas de escritura de archivos.
     *
     * Trae ambas reglas [ContentSafetyRule.targetArg]: Solo se utiliza la ruta para hacer coincidir y el contenido del texto no está involucrado.
     * De lo contrario, si escribe un texto con puntos suspensivos, se bloqueará como cruce de camino.
     *
     * @param toolName Nombre de la herramienta de escritura de archivos (workspace_write / workspace_edit)
     */
    private fun writePathRules(toolName: String): List<ContentSafetyRule> =
        listOf(
            ContentSafetyRule(
                toolName = toolName,
                matcher = { it.contains("..") },
                reason = "La ruta contiene... bloqueado por la política de seguridad",
                ruleId = "workspace-traversal",
                targetArg = "path",
            ),
            ContentSafetyRule(
                toolName = toolName,
                matcher = { it.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(it) },
                reason = "La política de seguridad bloquea la escritura de ruta absoluta(Permitir solo rutas relativas dentro del espacio de trabajo)",
                ruleId = "workspace-absolute-path",
                targetArg = "path",
            ),
        )

    /**
     * Consultar reglas;Volver a la primera regla al acertar,Devuelve nulo en caso de error.
     *
     * @param toolName Nombre de la herramienta
     * @param input Ingresar contenido(Empalme de valores de parámetros)
     */
    fun check(toolName: String, input: String): ContentSafetyRule? = RULES.firstOrNull { it.toolName == toolName && it.matcher(input) }

    /**
     * Verificar según tabla de parámetros. traer [ContentSafetyRule.targetArg] Las reglas solo toman los campos correspondientes,
     * Las reglas restantes aún toman la cadena después de concatenar todos los valores de los parámetros (con [check] comportamiento consistente).
     *
     * El enlace de ejecución debe pasar por esta entrada: la persona que llama pasa la tabla de parámetros tal como está, no la empalma externamente y luego la ajusta. [check],
     * De lo contrario, la regla de ruta también comprobará el cuerpo del archivo.
     */
    fun checkArgs(toolName: String, args: Map<String, String>): ContentSafetyRule? {
        val joined by lazy { args.values.joinToString("\n") }
        return RULES.firstOrNull { rule ->
            if (rule.toolName != toolName) return@firstOrNull false
            val input = rule.targetArg?.let { argName -> args[argName] ?: return@firstOrNull false } ?: joined
            rule.matcher(input)
        }
    }
}
