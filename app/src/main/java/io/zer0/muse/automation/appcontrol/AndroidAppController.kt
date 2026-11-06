package io.zer0.muse.automation.appcontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementación de Android del control de aplicaciones: poner [AppControlCore] La decisión recae en el comando PackageManager/Intent/Device.
 *
 * División del trabajo:
 *  - Acciones que requieren omitir el entorno limitado de la aplicación (detención forzada/borrar datos/desinstalación)→ `AutomationManager.execTiered`
 *    (Shizuku prioridad,Root En pocas palabras, la misma ruta que el comando del dispositivo, sin escribir shell en el nivel de herramienta);
 *  - Otras acciones (listar/iniciar/abrir página de configuración)→ PackageManager Con intención,**No se requiere autorización automática**,
 *    esto también es"El Agente aún puede funcionar cuando el usuario no instala ninguna capacidad adicional."parte.
 */
class AndroidAppController(
    private val context: Context,
    private val manager: AutomationManager,
) {
    /**
     * Lista de aplicaciones ejecutables.
     *
     * usar `queryIntentActivities(MAIN/LAUNCHER)` en lugar de `getInstalledApplications`:
     * El primero da**Aplicaciones que los usuarios realmente pueden abrir**(exactamente el conjunto que necesita el Agente) y no depende de
     * Android 11+ exención de visibilidad del paquete; este último no está declarado en `QUERY_ALL_PACKAGES` será castrado por el sistema.
     */
    suspend fun listLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // Los tipos de retorno de dos sobrecargas no se pueden unificar en la misma expresión if (Kotlin será empujado a Cualquiera),
            // Por lo tanto, las sucursales se pueden fusionar después de completar sus respectivas consultas.
            val resolved: List<android.content.pm.ResolveInfo> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        intent,
                        android.content.pm.PackageManager.ResolveInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val appInfo = runCatching { info.activityInfo.applicationInfo }.getOrNull()
                InstalledAppInfo(
                    packageName = pkg,
                    label = runCatching { pm.getApplicationLabel(appInfo!!).toString().trim() }
                        .getOrDefault(pkg),
                    versionName = runCatching {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0).versionName.orEmpty()
                    }.getOrDefault(""),
                    system = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                    self = pkg == context.packageName,
                )
            }.distinctBy { it.packageName }
        }.onFailure { Logger.w(TAG, "No se pudieron enumerar las aplicaciones: ${it.message}") }.getOrDefault(emptyList())
    }

    /** Localice aplicaciones por nombre de paquete o nombre visible para el usuario. */
    suspend fun findApp(query: String): InstalledAppInfo? = AppControlCore.matchApp(listLaunchableApps(), query)

    /** Disponibilidad actual del canal (las fallas de detección se considerarán no disponibles y las capacidades no se informarán falsamente). */
    suspend fun channels(): ChannelAvailability = runCatching {
        val state = manager.permissionState.value.let { cached ->
            if (cached.shellEnabled || cached.rootEnabled) {
                cached
            } else {
                manager.refreshPermissions()
            }
        }
        ChannelAvailability(shell = state.shellEnabled, root = state.rootEnabled)
    }.getOrElse {
        Logger.w(TAG, "Falló la detección del canal: ${it.message}")
        ChannelAvailability(shell = false, root = false)
    }

    /**
     * Realizar una acción de control de la aplicación.
     *
     * @param query Nombre del paquete o nombre de la aplicación
     */
    suspend fun perform(action: AppControlAction, query: String): AppActionResult {
        val channels = channels()
        // Primero, determine el nombre del paquete tal como está; si no es un nombre de paquete legal, intente resolverlo por nombre (el modelo suele dar un nombre chino).
        val direct = if (AppControlCore.isValidPackageName(query)) query else null
        val target = if (direct != null) {
            listLaunchableApps().firstOrNull { it.packageName == direct }
        } else {
            findApp(query)
        }
        val packageName = direct ?: target?.packageName
        if (packageName == null) {
            return AppActionResult.Unavailable(UnavailableReason.PACKAGE_NOT_FOUND)
        }

        // evaluate Si no se aprueba, se devolverá tal cual (incluido"canal faltante"con"Aplicación del sistema/autointerceptación"etc. puede explicar las razones);
        // Después de pasar, la variable local ya no está vacía y se puede distribuir directamente.
        val decision = AppControlCore.evaluate(action, packageName, channels, target)
        return if (decision !is AppActionResult.Success) {
            decision
        } else {
            when (action) {
                AppControlAction.LAUNCH -> launchViaIntent(packageName)
                AppControlAction.OPEN_APP_SETTINGS -> openAppSettings(packageName)
                AppControlAction.FORCE_STOP,
                AppControlAction.CLEAR_DATA,
                AppControlAction.UNINSTALL,
                -> runDeviceCommand(action, packageName)
            }
        }
    }

    /** Inicie la aplicación: priorice la aplicación de su propia intención (no se requiere autorización) y luego vuelva a la accesibilidad si falla./canal. */
    private suspend fun launchViaIntent(packageName: String): AppActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            val ok = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.onFailure { Logger.w(TAG, "Intent El inicio falló: ${it.message}") }.getOrDefault(false)
            if (ok) return AppActionResult.Success(ExecutionChannel.APP)
        }
        // Sin intención de inicio (como una aplicación oculta)→ Déjalo en manos de canales jerárquicos.
        return if (manager.launchApp(packageName)) {
            AppActionResult.Success(ExecutionChannel.APP)
        } else {
            AppActionResult.Failed("No se puede comenzar $packageName(No hay entrada de inicio y el canal de automatización no está disponible)")
        }
    }

    /** Abra la página de detalles de configuración del sistema de la aplicación (no se requiere autorización automática, los usuarios pueden cambiar los permisos por sí mismos)/datos claros). */
    private suspend fun openAppSettings(packageName: String): AppActionResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .map { AppActionResult.Success(ExecutionChannel.APP) }
            .getOrElse {
                Logger.w(TAG, "No se pudo abrir la página de configuración de la aplicación: ${it.message}")
                AppActionResult.Failed("no puedo abrir $packageName página de configuración")
            }
    }

    /** Acción para cruzar la zona de pruebas de la aplicación: use uniformemente execTiered(Shizuku prioridad,Root bolsillo). */
    private suspend fun runDeviceCommand(action: AppControlAction, packageName: String): AppActionResult {
        val command = AppControlCore.buildCommand(action, packageName)
        val executed = command?.let { manager.execTiered(it) }
        return when {
            command == null -> AppActionResult.Failed("Error interno: acción $action Comando del dispositivo no registrado")
            // Ambos canales no están disponibles: falta de capacidad para informar con veracidad, dejar que el modelo le explique al usuario
            executed == null -> AppActionResult.Unavailable(UnavailableReason.NEED_SHELL_OR_ROOT)
            else -> {
                val (channelName, detail) = executed
                val via = if (channelName == "Root") ExecutionChannel.ROOT else ExecutionChannel.SHELL
                // ExecDetail.output es texto combinado stdout+stderr (no isSuccess(), sujeto a código de salida);
                // pm clear / pm uninstall Responder exitosamente "Success",am force-stop No hay resultados en caso de éxito.
                val ok = detail.exitCode == 0 || detail.output.contains("Success", ignoreCase = true)
                if (ok) {
                    AppActionResult.Success(via)
                } else {
                    AppActionResult.Failed(detail.output.ifBlank { "El comando falló (código de salida ${detail.exitCode})" })
                }
            }
        }
    }

    private companion object {
        const val TAG = "AndroidAppController"
    }
}
