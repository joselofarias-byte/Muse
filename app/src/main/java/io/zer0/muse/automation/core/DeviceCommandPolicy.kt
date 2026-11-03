package io.zer0.muse.automation.core

/**
 * v2.x Automatización Fase I:Política de comando del dispositivo(pura logica,Se puede probar individualmente).
 *
 * device_shell La única puerta de seguridad para herramientas:El comando debe presionar el verbo de la lista blanca.(Algunos requieren subcomandos de segundo nivel),
 * y no se permiten tuberías/Redirigir/Caracteres de empalme de comandos múltiples. La capa de ejecución se encuentra en
 * [AutomationManager.execTiered](Shizuku Prioridad,Root Ten todo en cuenta).
 */
object DeviceCommandPolicy {
    /** Resultados de la verificación. */
    sealed class Check {
        object Valid : Check()

        data class Invalid(val reason: String) : Check()
    }

    /** personajes prohibidos:Canalizaciones/Redirecciones/Separación de comandos/Subexpresiones/Expansión del entorno. */
    private val FORBIDDEN_CHARS = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')', '{', '}', '\n', '\r')

    /**
     * lista blanca de verbos:value es nulo, lo que indica que el segundo parámetro está libre(Por ejemplo, el nombre del servicio de dumpsys);
     * No nulo indica que el segundo segmento debe llegar al conjunto de subcomandos.
     * v2.2.1: `input` adicional permitido `-d/--display <displayId>` prefijo(Inyección de entrada de pantalla virtual).
     */
    private val VERBS: Map<String, Set<String>?> =
        mapOf(
            "input" to setOf("tap", "swipe", "draganddrop", "text", "keyevent", "roll", "press", "motionevent"),
            "am" to setOf("start", "startservice", "broadcast", "force-stop", "kill"),
            "pm" to setOf("list", "path", "dump", "enable", "disable", "grant", "revoke", "clear"),
            "settings" to setOf("get", "put", "delete", "list"),
            "svc" to setOf("power", "wifi", "data", "bluetooth"),
            "wm" to setOf("size", "density"),
            "dumpsys" to null,
            "uiautomator" to setOf("dump"),
            "screencap" to null,
            "screendump" to null,
            "content" to setOf("query", "insert", "update", "delete", "call", "read"),
            "cmd" to setOf("statusbar", "notification", "activity", "package", "wifi", "power", "user", "media"),
            "getprop" to null,
            "date" to null,
        )

    /** para la descripción/Lista de verbos utilizados por el documento. */
    val allowedVerbs: List<String> get() = VERBS.keys.sorted()

    /** Verificar un comando. devolución legal [Check.Valid],De lo contrario, con un motivo legible. */
    fun validate(command: String): Check {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Check.Invalid("el comando está vacío")
        cmd.forEach { ch ->
            if (ch in FORBIDDEN_CHARS) {
                return Check.Invalid("Contiene caracteres no permitidos '$ch'(Las tuberías no son compatibles/Redirigir/Empalme de comandos múltiples,Por favor divida en varias llamadas)")
            }
        }
        val tokens = cmd.split(Regex("\\s+"))
        val verb = tokens.first()
        // Nota:confiar solo VERBS[verb] indistinguible"la clave no existe"con"El valor es null(verbo de argumento libre)",
        // Primero debe usar contieneKey para determinar,De lo contrario, screencap/getprop, etc. se rechazarán por error.
        if (verb !in VERBS) {
            return Check.Invalid("Comandos que no están en la lista blanca: $verb(permitir:${allowedVerbs.joinToString(" ")})")
        }
        val subVerbs = VERBS[verb]
        if (verb == "input" && subVerbs != null) {
            return validateInput(tokens, subVerbs)
        }
        if (subVerbs != null) {
            if (tokens.size < 2) return Check.Invalid("$verb Subcomando faltante(permitir:${subVerbs.joinToString("/")})")
            if (tokens[1] !in subVerbs) {
                return Check.Invalid("$verb Subcomandos no soportados: ${tokens[1]}(permitir:${subVerbs.joinToString("/")})")
            }
        }
        return Check.Valid
    }

    /**
     * v2.2.1 pantalla virtual:`input` verificación especial —— permitir soltero `-d/--display <numeros>` prefijo,
     * Debe ir seguido del subcomando lista blanca.;Todas las demás banderas serán rechazadas.
     */
    private fun validateInput(tokens: List<String>, subVerbs: Set<String>): Check {
        var index = 1
        var displaySpecified = false
        while (index < tokens.size && tokens[index].startsWith("-")) {
            val flag = tokens[index]
            if (flag != "-d" && flag != "--display") {
                return Check.Invalid("input Parámetros no admitidos $flag(solo permitido -d/--display <displayId>)")
            }
            if (displaySpecified) return Check.Invalid("input Especificación duplicada -d/--display")
            val value = tokens.getOrNull(index + 1)
            if (value == null || value.isEmpty() || !value.all(Char::isDigit)) {
                return Check.Invalid("input -d necesito un numero displayId")
            }
            displaySpecified = true
            index += 2
        }
        if (index >= tokens.size) {
            return Check.Invalid("input Subcomando faltante(permitir:${subVerbs.joinToString("/")})")
        }
        if (tokens[index] !in subVerbs) {
            return Check.Invalid("input Subcomandos no soportados: ${tokens[index]}(permitir:${subVerbs.joinToString("/")})")
        }
        return Check.Valid
    }
}
