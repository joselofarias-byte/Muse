package io.zer0.muse.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.zer0.muse.automation.core.AutomationManager

internal fun formatSettingsPutResult(tier: String, namespace: String, name: String): String = "[$tier] Setting '$namespace:$name' updated"

internal fun formatInputInjectResult(tier: String, textLength: Int, viaClipboard: Boolean): String =
    "[$tier] Text injected${if (viaClipboard) " via clipboard paste" else ""} ($textLength chars)"

/**
 * v2.x Automatización Fase I:Registrador de herramientas a nivel de dispositivo(Originales Root-level,Ahora cambiado a ejecución en capas.).
 *
 * Exponiendo las capacidades de los dispositivos con altos privilegios como herramientas a las que se puede recurrir mediante IA;Realizar unificado [AutomationManager.execTiered] ——
 * **Shizuku prioridad,Root Ten todo en cuenta**,Se devuelve un error explícito cuando ninguno de los archivos está autorizado.
 * Todas las herramientas [ToolRiskLevel.HIGH],Siga la cadena de aprobación y la infraestructura de auditoría existentes.
 *
 * Tools:
 * - settings_get / settings_put — Leer y escribir configuraciones del sistema
 * - network_toggle — svc Cambiar wifi/datos
 * - am_start — empezar Activity(Contiene extras)
 * - list_packages — Listar paquetes instalados
 * - logcat_tail — Leer registros recientes
 * - input_inject — Inyecte texto en el cuadro de entrada enfocado(Fondo de bolsillo con portapapeles adhesivo.)
 */
class RootToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val manager: AutomationManager,
    private val context: Context,
) {
    private val builder get() = manager.root

    init {
        registerAll()
    }

    private fun tierUnavailable(): String = "El canal de comando del dispositivo no está disponible:Requiere autorización de Shizuku o Root(ajustes → Asistente de configuración de permisos),Ninguno de los equipos está listo actualmente."

    /** Ejecutar un comando de dispositivo;[tier] El prefijo facilita a los modelos y usuarios distinguir qué marcha están utilizando. */
    private suspend fun runCommand(command: String): Pair<String, io.zer0.muse.automation.executors.ShellExecutor.ExecDetail>? =
        manager.execTiered(command)

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_get",
                description = "Leer la configuración del sistema Android por nombre(system / secure / global)." +
                    "Verifique el modo de vuelo,Wi-Fi Estado, modo de posicionamiento, etc." +
                    "El formato es namespace:name,Por ejemplo seguro: modo_ubicación." +
                    "Requiere autorización de Shizuku o Root.",
                parameters = mapOf(
                    "name" to
                        "Requerido. Nombre de configuración,Puede tener el prefijo de espacio de nombres(global / secure / system)," +
                        "por ejemplo secure:location_mode",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val cmd = builder.buildSettingsGet(name) ?: return@register "Error: invalid setting name '$name'"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] $name = ${detail.output.trim()}"
            } else {
                formatRootFailureOutput(tier, "Error de lectura", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "network_toggle",
                description = "Activa y desactiva Wi-Fi o datos celulares con el comando svc." +
                    "Requiere autorización de Shizuku o Root. Usar con precaución,Puede interrumpir la conectividad de la red.",
                parameters = mapOf(
                    "service" to "Required. Service to toggle: wifi or data",
                    "enabled" to "Required. true to enable, false to disable",
                ),
                required = setOf("service", "enabled"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val service = args["service"]?.takeIf { it.isNotBlank() } ?: return@register "Error: service is required"
            val enabled = args["enabled"]?.trim()?.lowercase()?.toBooleanStrictOrNull()
                ?: return@register "Error: enabled must be true or false"
            val cmd = builder.buildSvcToggle(service, enabled)
                ?: return@register "Error: unsupported service '$service' (only wifi/data)"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] $service ${if (enabled) "enabled" else "disabled"}"
            } else {
                formatRootFailureOutput(tier, "El interruptor falló", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_put",
                description = "Escribir la configuración del sistema Android(system / secure / global)." +
                    "Requiere autorización de Shizuku o Root. Usar con precaución,Cambiará el comportamiento del dispositivo.",
                parameters = mapOf(
                    "namespace" to "Required. Setting namespace: global, secure, or system",
                    "name" to "Required. Setting name",
                    "value" to "Required. Value to set",
                ),
                required = setOf("namespace", "name", "value"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val namespace = args["namespace"]?.takeIf { it.isNotBlank() } ?: return@register "Error: namespace is required"
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val value = args["value"]?.takeIf { it.isNotBlank() } ?: return@register "Error: value is required"
            val cmd = builder.buildSettingsPut(namespace, name, value)
                ?: return@register "Error: invalid settings parameters"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                formatSettingsPutResult(tier, namespace, name)
            } else {
                formatRootFailureOutput(tier, "Error de escritura", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "am_start",
                description = "Inicie la actividad con el comando am start." +
                    "extras usar key=value forma,Varios artículos con | Separado." +
                    "Requiere autorización de Shizuku o Root.",
                parameters = mapOf(
                    "package" to "Required. Package name, e.g., com.android.settings",
                    "class" to "Optional. Activity class name, e.g., .Settings\$WifiSettingsActivity",
                    "extras" to "Optional. Key-value extras separated by |, e.g., title=Hello|count=5",
                ),
                required = setOf("package"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pkg = args["package"]?.takeIf { it.isNotBlank() } ?: return@register "Error: package is required"
            val cls = args["class"]
            val extras = args["extras"]
            val cmd = builder.buildAmStart(pkg, cls, extras) ?: return@register "Error: invalid package/class/extras"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] Activity started: $pkg${cls?.let { "/$it" }.orEmpty()}"
            } else {
                formatRootFailureOutput(tier, "El inicio falló", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_packages",
                description = "Listar los nombres de los paquetes instalados,Puede filtrar por subcadena. El nombre del paquete utilizado para encontrar la aplicación." +
                    "Requiere autorización de Shizuku o Root.",
                parameters = mapOf(
                    "filter" to "Optional. Substring to filter package names",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val filter = args["filter"]
            val cmd = builder.buildListPackages(filter) ?: return@register "Error: invalid filter"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode != 0) {
                formatRootFailureOutput(tier, "Lista fallida", detail.output, detail.exitCode)
            } else {
                val packages = detail.output.lineSequence()
                    .mapNotNull { line -> Regex("package:(.+)").find(line)?.groupValues?.get(1) }
                    .distinct()
                    .sorted()
                    .toList()
                if (packages.isEmpty()) {
                    "No packages found"
                } else {
                    formatRootPackageListOutput(tier, packages)
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "logcat_tail",
                description = "Lea las últimas N líneas de logcat. Se utiliza para solucionar problemas de fallas de aplicaciones o eventos del sistema." +
                    "Requiere autorización de Shizuku o Root.",
                parameters = mapOf(
                    "lines" to "Optional. Number of lines to read, default 100",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val lines = (args["lines"]?.toIntOrNull() ?: 100).coerceIn(1, 2_000)
            val result = runCommand(builder.buildLogcatTail(lines)) ?: return@register tierUnavailable()
            val (tier, detail) = result
            formatRootLogcatOutput(tier, detail.output)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "input_inject",
                description = "Inyecte texto en el cuadro de entrada actualmente enfocado." +
                    "Más confiable que la entrada accesible cuando se encuentran caracteres especiales." +
                    "Requiere autorización de Shizuku o Root.",
                parameters = mapOf(
                    "text" to "Required. Text to inject",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "Error: text is required"
            val cmd = builder.buildInputInject(text) ?: return@register "Error: empty text"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                formatInputInjectResult(tier, text.length, viaClipboard = false)
            } else {
                // Ten todo en cuenta:Escribir en el portapapeles + tecla PEGAR(El texto de entrada de algunas ROM no es confiable para caracteres especiales)
                val pasteResult = runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("muse-inject", text))
                    manager.execTiered("input keyevent 279")
                }.getOrNull()
                if (pasteResult != null && pasteResult.second.exitCode == 0) {
                    formatInputInjectResult(tier, text.length, viaClipboard = true)
                } else {
                    formatRootFailureOutput(tier, "La inyección falló", detail.output, detail.exitCode)
                }
            }
        }
    }
}

internal fun formatRootLogcatOutput(tier: String, output: String): String = "[$tier] Logcat output (${output.length} chars):\n$output"

internal fun formatRootPackageListOutput(tier: String, packages: List<String>): String =
    "[$tier] Found ${packages.size} packages:\n${packages.joinToString("\n")}"

internal fun formatRootFailureOutput(tier: String, action: String, output: String, exitCode: Int): String =
    "[$tier] $action: ${output.ifBlank { "exit=$exitCode" }}"
