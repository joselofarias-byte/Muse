package io.zer0.muse.tools

/**
 * Verificación previa del canal antes de la ejecución de la herramienta.
 *
 * ## por qué es necesario
 *
 * Artículo 4 de autoevaluación del asistente: "Cuando el canal de tercer nivel Accesibilidad/Shizuku/Root no está listo, la herramienta fallará rápidamente.
 * Pero no existe ninguna conexión entre el mensaje de error y "cómo activar". Cuando los usuarios ven un informe de error, a menudo no saben a dónde ir a continuación. "
 *
 * Estado actual:`ToolRequirements` Se ha declarado de qué canal depende cada herramienta,`tool_status` También lo usaré,
 * pero**La ruta de ejecución no se utiliza**. Entonces, el modelo se llama como de costumbre, la herramienta falla como de costumbre y se devuelve el error subyacente.
 * ("Servicio de accesibilidad no conectado", etc.), sin indicarle al modelo que cambie de carretera ni indicarle al usuario adónde ir.
 *
 * Aquí, la verificación previa se realiza como una puerta antes de la ejecución: la herramienta no se ejecutará cuando falte el canal y el resultado con la ruta abierta se devolverá directamente.
 * Preste atención a la verificación previa**Sin reemplazo**Juicio de permiso durante la ejecución (TOCTOU: El usuario podrá revocar la autorización después de una verificación previa),
 * Simplemente convierte una decisión condenada al fracaso en una sentencia ejecutable de antemano.
 */
object ToolChannelPreflight {

    /**
     * Lea los canales actualmente listos desde AutomationManager.
     *
     * Solo cubre tres niveles de canales de "control de dispositivos" —— Permisos de tiempo de ejecución (Contacto/SMS/Posicionamiento…) Aquí no se realiza ningún sondeo:
     * Cada herramienta los verifica durante la ejecución y se devuelven errores claros. Agregar una capa de verificación previa será repetitivo y propenso a errores de juicio.
     */
    suspend fun channelsOf(automation: io.zer0.muse.automation.core.AutomationManager): Set<ToolRequirements.Channel> {
        val state = automation.refreshPermissions()
        return buildSet {
            if (state.accessibilityEnabled) add(ToolRequirements.Channel.ACCESSIBILITY)
            if (state.shellEnabled) add(ToolRequirements.Channel.SHELL)
            if (state.rootEnabled) add(ToolRequirements.Channel.ROOT)
        }
    }

    /** Conclusión previa al examen. */
    sealed interface Result {
        /** Se puede ejecutar. */
        data object Proceed : Result

        /** Falta el canal y no debe ejecutarse. */
        data class Blocked(val missing: Set<ToolRequirements.Channel>, val message: String) : Result
    }

    /**
     * Prevuelo.
     *
     * @param toolName Nombre de la herramienta
     * @param availableChannels Canal listo actual;**null Indica que el anfitrión no proporciona información y se salta la verificación previa.**
     */
    fun check(toolName: String, availableChannels: Set<ToolRequirements.Channel>?): Result {
        val req = ToolRequirements.of(toolName)
        val missing = if (availableChannels == null || req.isUnconditional) {
            emptySet()
        } else {
            missingChannels(req, availableChannels)
        }
        return if (missing.isEmpty()) Result.Proceed else Result.Blocked(missing, buildMessage(toolName, missing))
    }

    /**
     * v2.6.7 fix (AUDIT F3): Entrada a la ruta de producción —— Saque conclusiones únicamente en el canal donde "el presentador realmente lo informará".
     *
     * [channelsOf] Sólo se detectan tres niveles de Accesibilidad/Shell/Root. Contactos, mensajes de texto, calendario,
     * Los derechos de posicionamiento, uso, uso de notificaciones, etc. nunca han entrado en la colección; si se consideran "desaparecidos",
     * Estas herramientas siempre estarán bloqueadas si el usuario las ha autorizado y volver a intentarlo no mejorará.
     *
     * La determinación de estos canales se deja a la verdadera fuente existente:`ToolRegistry.permissionStatusProvider`
     * con las propias comprobaciones del tiempo de ejecución de cada herramienta.
     */
    fun checkPreflightable(toolName: String, availableChannels: Set<ToolRequirements.Channel>?): Result {
        val req = ToolRequirements.of(toolName)
        val missing = if (availableChannels == null || req.isUnconditional) {
            null
        } else {
            val probeableAllOf = req.allOf.filter { it in PREFLIGHT_CHANNELS }.toSet()
            val probeableAnyOf = req.anyOf.filter { it in PREFLIGHT_CHANNELS }.toSet()
            // No hay canales detectables en la solicitud. → Devuelva la herramienta para su autoinspección (missing es nulo).
            if (probeableAllOf.isEmpty() && probeableAnyOf.isEmpty()) {
                null
            } else {
                missingOf(probeableAllOf, probeableAnyOf, availableChannels)
            }
        }
        return missing?.let { Result.Blocked(it, buildMessage(toolName, it)) } ?: Result.Proceed
    }

    /** Sólo se cuentan los conjuntos faltantes para los canales detectables; Se devuelve null cuando está satisfecho. */
    private fun missingOf(
        probeableAllOf: Set<ToolRequirements.Channel>,
        probeableAnyOf: Set<ToolRequirements.Channel>,
        available: Set<ToolRequirements.Channel>,
    ): Set<ToolRequirements.Channel>? {
        val missingAll = probeableAllOf.filterNot { it in available }.toSet()
        if (missingAll.isNotEmpty()) return missingAll
        val anyUnsatisfied = probeableAnyOf.isNotEmpty() && probeableAnyOf.none { it in available }
        return if (anyUnsatisfied) probeableAnyOf else null
    }

    /** Examen previo de canales que pueden sacar conclusiones. —— debe ser con [channelsOf] El alcance de los informes sigue siendo consistente. */
    private val PREFLIGHT_CHANNELS: Set<ToolRequirements.Channel> = setOf(
        ToolRequirements.Channel.ACCESSIBILITY,
        ToolRequirements.Channel.SHELL,
        ToolRequirements.Channel.ROOT,
    )

    /** ¿Qué canales faltan?allOf Faltar a cualquiera significa fracaso;anyOf Una falta total se considera un fracaso. */
    private fun missingChannels(
        req: ToolRequirements.Requirement,
        available: Set<ToolRequirements.Channel>,
    ): Set<ToolRequirements.Channel> {
        val missingAll = req.allOf.filterNot { it in available }.toSet()
        if (missingAll.isNotEmpty()) return missingAll
        val anySatisfied = req.anyOf.isEmpty() || req.anyOf.any { it in available }
        return if (anySatisfied) emptySet() else req.anyOf
    }

    /**
     * La descripción del fallo se muestra al modelo.
     *
     * Tres elementos: qué falta, dónde abrirlo y qué se puede reemplazar ahora. El último es crucial. ——
     * Simplemente decir "no disponible" hace que el modelo lo intente una y otra vez, hasta que sepa cómo proceder cuando se le presente una ruta alternativa.
     */
    private fun buildMessage(toolName: String, missing: Set<ToolRequirements.Channel>): String {
        val labels = missing.joinToString(",") { it.openHint() }
        val alternatives = missing.flatMap { it.alternatives() }.distinct()
        return buildString {
            append("[canal no listo] Herramientas $toolName El canal del dispositivo dependiente no está disponible actualmente:$labels.")
            append("Esta vez no se ejecutó y al volver a intentarlo no estará disponible.")
            if (alternatives.isNotEmpty()) {
                append("Primero puedes usar:${alternatives.joinToString(",")};")
            }
            append("O pídale al usuario que abra el canal correspondiente en la configuración y llame a tool_status nuevamente para confirmar.")
        }
    }

    /** canal → Nombre de cara al usuario + ruta de aprovisionamiento. */
    private fun ToolRequirements.Channel.openHint(): String = when (this) {
        ToolRequirements.Channel.ACCESSIBILITY -> "Servicios de accesibilidad (Configuración → Accesibilidad → Servicios descargados → Muse)"
        ToolRequirements.Channel.SHELL -> "Shizuku / ADB Autorización (configuraciones → Avanzado → Shizuku)"
        ToolRequirements.Channel.ROOT -> "Root Permisos"
        ToolRequirements.Channel.USAGE_STATS -> "Acceso de uso (configuraciones → Solicitud → permisos especiales)"
        ToolRequirements.Channel.NOTIFICATION_LISTENER -> "Acceso a notificaciones (configuraciones → Notificación → Aviso de derechos de uso)"
        ToolRequirements.Channel.CONTACTS -> "Permisos de contacto"
        ToolRequirements.Channel.SMS -> "Permisos de SMS"
        ToolRequirements.Channel.PHONE_CALL -> "Permisos telefónicos"
        ToolRequirements.Channel.CALENDAR -> "Permisos de calendario"
        ToolRequirements.Channel.LOCATION -> "Permisos de ubicación"
        ToolRequirements.Channel.RUNTIME -> "permisos de tiempo de ejecución"
    }

    /** Medios alternativos disponibles para el modelo en ausencia de este canal (escriba nombres de herramientas específicos, no se limite a decir palabras vacías). */
    private fun ToolRequirements.Channel.alternatives(): List<String> = when (this) {
        ToolRequirements.Channel.ACCESSIBILITY -> listOf("ui_screenshot(si Shell está disponible)", "shell Herramientas del sistema (si están autorizadas)")
        ToolRequirements.Channel.SHELL -> listOf("Herramientas de accesibilidad (si están activadas)")
        ToolRequirements.Channel.ROOT -> listOf("Shell / Herramientas de accesibilidad (si están listas)")
        ToolRequirements.Channel.USAGE_STATS -> listOf("current_status(Ver reemplazos en aplicaciones para aplicaciones en primer plano)")
        else -> emptyList()
    }
}
