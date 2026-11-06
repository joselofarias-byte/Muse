package io.zer0.muse.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseCardPress
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.util.ShareIntentHelper

/**
 * v2.0+: Safe Mode minimalista UI(Mostrado después del último accidente).
 *
 * Interfaz de recuperación minimalista,Utilice Muse para diseñar un sistema de fichas.:
 *  - arriba:icono de advertencia(MuseIcons.alertTriangle) + "Fallo en el último inicio" Título
 *  - céntrico:Tiempo de falla + resumen de la pila de fallas(desplazamiento vertical,fuente monoespaciada)
 *  - abajo:Botones de acción dispuestos verticalmente(MuseCardPress estilo táctil,sin ondulaciones)
 *    a) Continúe comenzando normalmente — Borrar el indicador de Modo seguro y finalizar el proceso para un arranque en frío
 *    b) Ver registro de fallos completo — Compartir archivos de registro de fallos mediante intención
 *    c) Borrar todos los datos y reiniciar — ActivityManager.clearApplicationUserData
 *    d) Copiar información sobre fallos — copiar al portapapeles
 *
 * restricciones de diseño:
 *  - no depende de Koin(El bloqueo puede deberse a una falla en el inicio de Koin.),Todos usan métodos estáticos de MuseCrashHandler
 *  - La información sobre fallos se lee desde SharedPreferences(safe_mode_pending + crash_time + crash_trace),
 *    SP Volver al archivo de registro de fallos cuando esté vacío readLatestCrashLog
 *  - warm-paper El estilo lo proporciona el MuseTheme exterior.(Ver el sitio de llamadas de MainActivity),usado aquí MaterialTheme.colorScheme
 */
@Composable
fun SafeModeScreen() {
    val context = LocalContext.current

    // v1.141 F2: arranque de recuperación de fallos — Mostrar instrucciones de integridad de datos y rutas de recuperación y copia de seguridad en la nube.
    var showRecoveryGuide by remember { mutableStateOf(false) }
    var showClearDataConfirm by remember { mutableStateOf(false) }

    // Priorizar la lectura de información estructurada sobre fallos del SP(v2.0+),Volver al archivo de registro de fallos cuando esté vacío
    val crashTime = remember { MuseCrashHandler.getCrashTime(context) }
    val crashTrace = remember {
        MuseCrashHandler.getCrashTrace(context) ?: MuseCrashHandler.readLatestCrashLog(context)
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = MusePaddings.screen)
                .padding(vertical = MusePaddings.sectionGap)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(MusePaddings.sectionGap))

            // ── arriba:icono de advertencia + título ────────────────────────────────────────
            WarningHeader()

            Spacer(Modifier.height(MusePaddings.sectionGap))

            // ── céntrico:Tarjeta de información sobre accidentes ──────────────────────────────────────────
            CrashInfoCard(crashTime = crashTime, crashTrace = crashTrace)

            Spacer(Modifier.height(MusePaddings.sectionGap))

            // ── abajo:Botón de acción(disposición vertical,MuseCardPress estilo táctil) ──────────────
            ActionButtons(
                crashTrace = crashTrace,
                onContinueNormal = {
                    // Borrar indicador de modo seguro(Bandera de archivo + capa de persistencia SP)
                    MuseCrashHandler.clearSafeModeFlag(context)
                    // v1.91-hotfix experiencia:Debe matar el proceso!recreate() Reconstruir solo Activity,
                    // Application.onCreate no será ejecutado nuevamente → startKoin no llamado
                    // → MainActivity by inject() colapsar "KoinApplication has been started".
                    // Después de finalizar el proceso, el sistema se reiniciará la próxima vez que se inicie en frío. Application.onCreate → startKoin Ejecutar normalmente.
                    (context as? Activity)?.finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                },
                onViewFullLog = { shareCrashLog(context) },
                onClearData = { showClearDataConfirm = true },
                onCopyInfo = { copyCrashInfo(context, crashTime, crashTrace) },
                onShowRecoveryGuide = { showRecoveryGuide = true },
            )

            Spacer(Modifier.height(MusePaddings.sectionGap))
        }
    }

    // v1.141 F2: Cuadro de diálogo de inicio de recuperación de fallas(Orientación pura sobre redacción publicitaria,no depende de Koin)
    if (showRecoveryGuide) {
        MuseDialog(
            onDismissRequest = { showRecoveryGuide = false },
            title = stringResource(R.string.safe_mode_data_recovery_title),
            content = {
                Text(
                    text = stringResource(R.string.safe_mode_data_recovery_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.safe_mode_data_recovery_ok),
            onConfirm = { showRecoveryGuide = false },
            dismissText = null,
            onDismiss = { showRecoveryGuide = false },
        )
    }

    if (showClearDataConfirm) {
        MuseDialog(
            onDismissRequest = { showClearDataConfirm = false },
            title = stringResource(R.string.safe_mode_clear_data_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.safe_mode_clear_data_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.safe_mode_clear_data_confirm_action),
            onConfirm = {
                showClearDataConfirm = false
                clearApplicationData(context)
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showClearDataConfirm = false },
            destructive = true,
        )
    }
}

/**
 * icono de advertencia superior + "Fallo en el último inicio" título.
 *
 * error redondo Fondo del contenedor + icono de contorno de advertencia,Alinear los estilos de advertencia del sistema.
 */
@Composable
private fun WarningHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MuseIcons.alertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(MusePaddings.itemGap))
        Text(
            text = stringResource(R.string.safe_mode_warning_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            text = stringResource(R.string.main_activity_safe_mode_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tarjeta central de información sobre accidentes — Tiempo de caída + resumen de pila(Fuente monoespaciada + desplazamiento vertical).
 */
@Composable
private fun CrashInfoCard(crashTime: String?, crashTrace: String?) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
        ) {
            // Línea de tiempo del accidente
            if (!crashTime.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.safe_mode_crash_time_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    Text(
                        text = crashTime,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(MusePaddings.contentGap))
            }
            // Resumen de la pila de fallos
            Text(
                text = stringResource(R.string.safe_mode_stack_trace_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            val traceText = crashTrace?.take(4000) ?: stringResource(R.string.safe_mode_no_crash_log)
            Text(
                text = traceText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MuseMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * grupo de botones de acción(disposición vertical,Utilice el estilo táctil MuseCardPress para cada botón).
 *
 * cada botón:Icono + texto del título,alineado a la izquierda,Comentarios de prensa sin fluctuaciones,Alinear elementos de la lista del sistema.
 */
// 6 Los parámetros de devolución de llamada son la estructura inherente del grupo de botones de operación.(Una acción por botón),La inversión dividida daña la legibilidad
@Suppress("LongParameterList")
@Composable
private fun ActionButtons(
    crashTrace: String?,
    onContinueNormal: () -> Unit,
    onViewFullLog: () -> Unit,
    onClearData: () -> Unit,
    onCopyInfo: () -> Unit,
    onShowRecoveryGuide: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        // a) Continúe comenzando normalmente — primary color de acento
        SafeModeActionButton(
            icon = MuseIcons.refresh,
            label = stringResource(R.string.safe_mode_continue_normal),
            description = stringResource(R.string.safe_mode_continue_normal_desc),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onContinueNormal,
        )
        // a2) v1.141 F2: guía de recuperación de datos — tertiary color de acento,Ruta de recuperación de arranque después de un fallo
        SafeModeActionButton(
            icon = MuseIcons.cloudDownload,
            label = stringResource(R.string.safe_mode_data_recovery),
            description = stringResource(R.string.safe_mode_data_recovery_desc_short),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = onShowRecoveryGuide,
        )
        // b) Ver registro de fallos completo
        SafeModeActionButton(
            icon = MuseIcons.fileText,
            label = stringResource(R.string.safe_mode_view_full_log),
            description = stringResource(R.string.safe_mode_view_full_log_desc),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onViewFullLog,
            enabled = !crashTrace.isNullOrBlank(),
        )
        // c) Borrar todos los datos y reiniciar — error color de acento,Operación peligrosa
        SafeModeActionButton(
            icon = MuseIcons.wand,
            label = stringResource(R.string.safe_mode_clear_data_restart),
            description = stringResource(R.string.safe_mode_clear_data_restart_desc),
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onClearData,
        )
        // d) Copiar información sobre fallos
        SafeModeActionButton(
            icon = MuseIcons.copy,
            label = stringResource(R.string.safe_mode_copy_crash_info),
            description = stringResource(R.string.safe_mode_copy_crash_info_desc),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onCopyInfo,
            enabled = !crashTrace.isNullOrBlank(),
        )
    }
}

/**
 * Botón de acción de modo seguro único — MuseCardPress embalaje,Icono + texto a la izquierda.
 *
 * @param icon icono
 * @param label texto del botón
 * @param containerColor color de fondo
 * @param contentColor Colores de texto e íconos
 * @param onClick Haga clic en devolución de llamada
 * @param enabled ¿Se puede hacer clic?(Reducir la transparencia y tragar eventos de clic cuando esté deshabilitado)
 */
@Composable
private fun SafeModeActionButton(
    icon: ImageVector,
    label: String,
    description: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    MuseCardPress(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.medium,
        containerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(MusePaddings.iconPadding))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) contentColor else contentColor.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        contentColor.copy(alpha = 0.78f)
                    } else {
                        contentColor.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

/**
 * Compartir archivos de registro de fallos mediante intención(txt).
 *
 * Obtenga primero el archivo de registro de fallos más reciente,Expuesto a través de FileProvider URI,
 * Compartir con aplicaciones externas usando ACTION_SEND(Aplicación de correo/gestión de archivos/chat, etc.).
 */
private fun shareCrashLog(context: Context) {
    val logs = MuseCrashHandler.listCrashLogs(context)
    val logFile = logs.firstOrNull()
    if (logFile == null) {
        Toast.makeText(context, R.string.safe_mode_no_crash_log, Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "muse crash log — ${logFile.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ShareIntentHelper.startChooserSafely(
            context,
            shareIntent,
            context.getString(R.string.safe_mode_view_full_log),
        )
    }.onFailure { e ->
        Logger.w("SafeModeScreen", "shareCrashLog failed", e)
        Toast.makeText(context, R.string.safe_mode_share_log_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Borre todos los datos de la aplicación y reinicie.
 *
 * API 19+: ActivityManager.clearApplicationUserData Limpieza a nivel del sistema(files / databases / SP / cache),
 *           Matar proceso automáticamente,El próximo inicio equivale a una nueva instalación.
 * Respaldo del dispositivo antiguo: elimine manualmente el directorio principal y luego finalice el proceso.
 */
private fun clearApplicationData(context: Context) {
    Logger.w("SafeModeScreen", "clearApplicationData — El usuario borra activamente todos los datos")
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        @Suppress("DEPRECATION")
        val cleared = am.clearApplicationUserData()
        if (cleared) {
            // El sistema finalizará automáticamente el proceso.,Manténgase a salvo aquí para evitar el regreso.
            (context as? Activity)?.finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        } else {
            // Usuario cancelado o fallido,fallback a la limpieza manual
            manualClearData(context)
        }
    } else {
        manualClearData(context)
    }
}

/** Dispositivos antiguos/limpieza manual alternativa cuando falla clearApplicationUserData. */
private fun manualClearData(context: Context) {
    runCatching {
        context.filesDir.deleteRecursively()
        context.cacheDir.deleteRecursively()
        context.deleteSharedPreferences("muse_safe_mode")
    }
    (context as? Activity)?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
}

/**
 * Copiar información sobre fallos(tiempo + pila)al portapapeles.
 */
private fun copyCrashInfo(context: Context, crashTime: String?, crashTrace: String?) {
    val text = buildString {
        if (!crashTime.isNullOrBlank()) {
            appendLine("tiempo de choque: $crashTime")
            appendLine()
        }
        if (!crashTrace.isNullOrBlank()) {
            appendLine("pila de choque:")
            append(crashTrace)
        }
        if (isEmpty()) {
            append("(Sin información sobre accidentes)")
        }
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("muse crash info", text))
    Toast.makeText(context, R.string.main_activity_crash_log_copied, Toast.LENGTH_SHORT).show()
}
