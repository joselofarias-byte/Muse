package io.zer0.muse.automation.appcontrol

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Conjunto de herramientas de control de aplicaciones —— poner [AndroidAppController] Expuesto como una herramienta invocable por IA.
 *
 * Prefijo de nomenclatura unificado `app_`, con `screen_*`(UI automatización),`device_*`(comando del dispositivo) paralelo:
 *  - `app_list`        Lista de aplicaciones ejecutables (no se requiere autorización)
 *  - `app_launch`      Inicie la aplicación (no se requiere autorización)
 *  - `app_settings`    Abra la página de configuración del sistema de la aplicación (no se requiere autorización, los usuarios pueden cambiar los permisos por sí mismos)/borrar datos)
 *  - `app_force_stop`  Forzar detención (requiere Shizuku/adb o Root)
 *  - `app_clear_data`  Borrar datos (se requiere Shizuku/adb o Root)
 *  - `app_uninstall`   Desinstalar (requiere Shizuku/adb o Root)
 *
 * La inscripción se divide en dos grupos:**Percepción/Capa de aplicación**(NORMAL, no se requiere autorización) y**destructivo**(HIGH, pasar por aprobación)——
 * La agrupación no es sólo para la duración de la función, sino también para"¿Qué herramientas no deberían aprobarse y cuáles sí?"Reconocible a simple vista.
 * Cuando falta canal**regresar sinceramente"¿Qué habilidad se necesita?"**, no mientas sobre el éxito——Este es el requisito previo para la credibilidad del Agente.
 */
class AppControlTools(
    private val context: Context,
    private val controller: AndroidAppController,
) {
    fun register(registry: ToolRegistry) {
        registerInspectionTools(registry)
        registerDestructiveTools(registry)
    }

    /** Acciones de capa de percepción y aplicación: solo se requieren los permisos propios de la aplicación, no se requiere autorización automatizada. */
    private fun registerInspectionTools(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "app_list",
                description = "Enumere las aplicaciones que se pueden iniciar en esta máquina (nombre + nombre del paquete). Cuando un usuario menciona una aplicación por su nombre chino," +
                    "Cuando no esté seguro del nombre del paquete, primero use esta herramienta para buscar el nombre del paquete y luego llame a app_launch y otras operaciones.",
                parameters = mapOf(
                    "query" to "Opcional, filtre por nombre o nombre del paquete, como \"WeChat\"",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val apps = controller.listLaunchableApps()
            val query = args["query"]?.trim().orEmpty()
            val filtered = if (query.isBlank()) {
                apps
            } else {
                apps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
            AppControlCore.renderAppList(filtered)
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "app_launch",
                description = "Inicie una aplicación (por nombre de paquete o nombre chino). Después de un inicio exitoso, se debe llamar a screen_read para confirmar que la interfaz está lista.",
                parameters = mapOf("app" to "Obligatorio, nombre del paquete o nombre de la aplicación, como com.tencent.mm o \"WeChat\""),
                required = setOf("app"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            runAction(AppControlAction.LAUNCH, R.string.app_control_launch_action, args["app"])
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "app_settings",
                description = "Abra la página de detalles de configuración del sistema de una aplicación (puede ver/Modificar permisos, notificaciones, uso de almacenamiento)." +
                    "Este tipo de operación es adecuado para que los usuarios lo confirmen por sí mismos, lo cual es más seguro que borrar los datos directamente.",
                parameters = mapOf("app" to "Requerido, nombre del paquete o nombre de la aplicación"),
                required = setOf("app"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            runAction(AppControlAction.OPEN_APP_SETTINGS, R.string.app_control_settings_action, args["app"])
        }
    }

    /** Acciones destructivas: Requiere Shizuku/adb o Root, riesgo ALTO (tomar aprobación). */
    private fun registerDestructiveTools(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "app_force_stop",
                description = "Detener con fuerza una aplicación (equivalente a \"Forzar detención\" en la configuración). Requiere Shizuku/adb o Root;" +
                    "Si no tienes estos permisos, esta herramienta te informará claramente y no volverás a intentarlo.",
                parameters = mapOf("app" to "Requerido, nombre del paquete o nombre de la aplicación"),
                required = setOf("app"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            runAction(AppControlAction.FORCE_STOP, R.string.app_control_force_stop_action, args["app"])
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "app_clear_data",
                description = "Borrar todos los datos de una aplicación (irreversible). Requiere Shizuku/adb o Root. Debe confirmarse con el usuario antes de la ejecución.",
                parameters = mapOf("app" to "Requerido, nombre del paquete o nombre de la aplicación"),
                required = setOf("app"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            runAction(AppControlAction.CLEAR_DATA, R.string.app_control_clear_data_action, args["app"])
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "app_uninstall",
                description = "Desinstalar una aplicación. Requiere Shizuku/adb o Root; Se rechazarán las aplicaciones del sistema. Debe confirmarse con el usuario antes de la ejecución.",
                parameters = mapOf("app" to "Requerido, nombre del paquete o nombre de la aplicación"),
                required = setOf("app"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            runAction(AppControlAction.UNINSTALL, R.string.app_control_uninstall_action, args["app"])
        }
    }

    /** unificado"Ejecutar + Traducir"Entrada: El procesamiento de parámetros de entrada vacíos solo se escribe aquí una vez. */
    private suspend fun runAction(action: AppControlAction, actionLabelRes: Int, rawApp: String?): String {
        val app = rawApp?.trim().orEmpty()
        return if (app.isEmpty()) {
            context.getString(R.string.app_control_need_app_arg)
        } else {
            describe(context.getString(actionLabelRes), app, controller.perform(action, app))
        }
    }

    /** poner [AppActionResult] Tradúcelo en una oración para el modelo; Si no se puede hacer, explique qué habilidad falta. */
    private fun describe(action: String, target: String, result: AppActionResult): String = when (result) {
        is AppActionResult.Success -> context.getString(
            R.string.app_control_ok,
            action,
            readableTarget(target),
            context.getString(
                when (result.via) {
                    ExecutionChannel.APP -> R.string.app_control_via_app
                    ExecutionChannel.SHELL -> R.string.app_control_via_shell
                    ExecutionChannel.ROOT -> R.string.app_control_via_root
                },
            ),
        )

        is AppActionResult.Unavailable -> context.getString(
            R.string.app_control_unavailable,
            action,
            readableTarget(target),
            context.getString(
                when (result.reason) {
                    UnavailableReason.INVALID_PACKAGE_NAME -> R.string.app_control_reason_invalid_name
                    UnavailableReason.PACKAGE_NOT_FOUND -> R.string.app_control_reason_not_found
                    UnavailableReason.NEED_SHELL_OR_ROOT -> R.string.app_control_reason_need_elevated
                    UnavailableReason.BLOCKED_SYSTEM_APP -> R.string.app_control_reason_system_app
                    UnavailableReason.BLOCKED_SELF -> R.string.app_control_reason_self
                },
            ),
        )

        is AppActionResult.Failed -> context.getString(
            R.string.app_control_failed,
            action,
            readableTarget(target),
            result.message,
        )
    }

    /**
     * Represente el parámetro de entrada de la herramienta (que puede ser un nombre de paquete o un nombre chino) en"Nombre(nombre del paquete)"Muéstralo a los demás.
     *
     * Si no se puede encontrar la etiqueta, devuelva los parámetros de entrada tal como están.——Prefiero mostrarlo claramente que dejarlo pasar porque PackageManager genera una excepción.
     * Toda la herramienta resultó ser un fracaso (eso fue"La operación fue exitosa pero reportó un fracaso."accidente típico).
     */
    private fun readableTarget(raw: String): String {
        if (!AppControlCore.isValidPackageName(raw)) return raw
        val label = runCatching {
            val pm = context.packageManager

            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(raw, 0)
            pm.getApplicationLabel(info).toString().trim()
        }.getOrNull()
        return if (label.isNullOrBlank() || label == raw) raw else "$label($raw)"
    }
}
