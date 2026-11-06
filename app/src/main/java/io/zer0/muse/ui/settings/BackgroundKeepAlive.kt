package io.zer0.muse.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * Sigue ejecutando el arranque en segundo plano. — Detectar el fabricante de ROM actual,Dale un camino de configuración único.
 *
 * principios de diseño:
 *  - entrada pasiva:Solo disponible en la página de configuración de mensajes activos,No moleste activamente a los usuarios con ventanas emergentes.
 *  - mensajes no solicitados/Los recordatorios periódicos se basan en procesos en segundo plano(Una vez finalizado el proceso, el sistema lo detiene.),Predeterminado de ROM nacional
 *    Evitar que las aplicaciones de terceros se inicien automáticamente/actividad de fondo,El usuario debe ir a la configuración del sistema para liberarlo.
 *  - Algunos fabricantes no tienen saltos estándar. intent(Como Huawei),Guíe a los usuarios para realizar operaciones manuales + abrir detalles de la aplicación.
 */

/** Resultados de identificación de proveedores y contenido de orientación. */
data class KeepAliveGuide(
    /** Nombre para mostrar de la marca,Como por ejemplo"Xiaomi". */
    val brandName: String,
    /** Pasos guiados(Ruta de operación del manual de usuario). */
    val steps: List<String>,
    /** Intenta saltar a la configuración del sistema.;Regrese a la página de detalles de la aplicación cuando falle o no haya una intención dedicada. Devuelve si el salto se inició correctamente. */
    val openSystemSettings: (Context) -> Boolean,
)

/**
 * v2.0: Pruebe los saltos de componentes candidatos en secuencia;Si todo falla, regrese a la página de detalles de la solicitud.
 *
 * Todas las actividades de inicio vienen con FLAG_ACTIVITY_NEW_TASK — desde la ventana emergente ContextThemeWrapper
 * Iniciar startActivity sin este indicador generará una excepción y se consumirá en silencio.(El usuario se comporta como"No hay salto al hacer clic").
 */
private fun attemptSettings(ctx: Context, candidates: List<ComponentName>, fallback: (Context) -> Boolean): Boolean {
    for (component in candidates) {
        val ok = runCatching {
            ctx.startActivity(
                Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (ok) return true
    }
    return fallback(ctx)
}

/** Detecta el fabricante actual del dispositivo y devuelve el arranque correspondiente. */
fun detectKeepAliveGuide(): KeepAliveGuide {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val fallbackToDetails: (Context) -> Boolean = { ctx ->
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    }
    return when {
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> KeepAliveGuide(
            brandName = "Huawei",
            steps = listOf(
                "Abra el sistema \"Configuración\"→\"Aplicación\"→\"Gestión de inicio de aplicaciones\"",
                "Busque Muse, desactive la \"Administración automática\" y permita manualmente el \"Inicio automático\", el \"Inicio asociado\" y las \"Actividades en segundo plano\".",
                "En \"Configuración\"→\"Batería\"→Desactive la restricción \"Permanecer siempre conectado a la red durante la hibernación\" en \"Más configuraciones de batería\"",
            ),
            openSystemSettings = fallbackToDetails,
        )
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
            KeepAliveGuide(
                brandName = "Xiaomi",
                steps = listOf(
                    "Abra \"Configuración\"→\"Configuración de la aplicación\"→\"Gestión de aplicaciones\"→ encontró Muse",
                    "Active \"Inicio automático\" y seleccione \"Ilimitado\" en \"Política de ahorro de energía\"",
                    "O vaya directamente a la página de administración de inicio automático del sistema (botón a continuación)",
                ),
                openSystemSettings = { ctx ->
                    attemptSettings(
                        ctx,
                        listOf(
                            ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity",
                            ),
                        ),
                        fallbackToDetails,
                    )
                },
            )
        manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") ->
            KeepAliveGuide(
                brandName = "OPPO",
                steps = listOf(
                    "Abra \"Configuración\"→\"Aplicación\"→\"Gestión de aplicaciones\"→ encontró Muse",
                    "Active \"Inicio automático\" y vaya a \"Batería\"→Seleccione \"Permitir la ejecución en segundo plano\" en \"Administración del consumo de energía\".",
                    "O vaya directamente a la página de administración de inicio automático del sistema (botón a continuación)",
                ),
                openSystemSettings = { ctx ->
                    attemptSettings(
                        ctx,
                        listOf(
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                            ),
                            ComponentName(
                                "com.oplus.safecenter",
                                "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                            ),
                            ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.startupapp.StartupAppListActivity",
                            ),
                        ),
                        fallbackToDetails,
                    )
                },
            )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> KeepAliveGuide(
            brandName = "vivo",
            steps = listOf(
                "Abra \"Configuración\"→\"Aplicaciones y permisos\"→\"Gestión de permisos\"→ encontró Muse",
                "Active \"Inicio automático\" y vaya a \"Batería\"→Seleccione \"Permitir ejecución en segundo plano\" en \"Alto consumo de energía en segundo plano\".",
                "O vaya directamente a la página de administración de inicio automático del sistema (botón a continuación)",
            ),
            openSystemSettings = { ctx ->
                attemptSettings(
                    ctx,
                    listOf(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                        ),
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                        ),
                    ),
                    fallbackToDetails,
                )
            },
        )
        else -> KeepAliveGuide(
            brandName = "Universales",
            steps = listOf(
                "Permitir el \"inicio automático/ejecución en segundo plano\" de Muse en la configuración del sistema",
                "Configure Muse en \"Sin restricciones\" en optimización de batería (accesible directamente desde el botón a continuación)",
                "No utilices \"Forzar detención\", simplemente tacha la tarea más reciente",
            ),
            openSystemSettings = { ctx ->
                val ok = runCatching {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
                if (ok) true else fallbackToDetails(ctx)
            },
        )
    }
}

/** Ventana emergente de guía:Marca + Pasos + Botón de salto. */
@Composable
fun KeepAliveGuideDialog(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val guide = rememberKeepAliveGuide()
    MuseDialog(
        onDismissRequest = onDismiss,
        title = context.getString(R.string.keep_alive_dialog_title, guide.brandName),
        content = {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.lifebuoy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = context.getString(R.string.keep_alive_dialog_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    guide.steps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmText = context.getString(R.string.keep_alive_dialog_open),
        onConfirm = {
            val jumped = guide.openSystemSettings(context)
            onDismiss()
            if (!jumped) {
                io.zer0.muse.ui.common.feedback.MuseToast.show(
                    context.getString(R.string.keep_alive_manual_hint),
                    3500L,
                )
            }
        },
        dismissText = context.getString(R.string.keep_alive_dialog_dismiss),
        onDismiss = onDismiss,
    )
}

/** Recuerde los resultados de las pruebas del fabricante.(Sólo se detecta una vez dentro del proceso.). */
@Composable
private fun rememberKeepAliveGuide(): KeepAliveGuide = androidx.compose.runtime.remember { detectKeepAliveGuide() }
