package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.export.ConversationExporter
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.settings.StatusDot
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Etapa 7 + Reparación P1: Copia de seguridad y estadísticas section — Lista agrupada.
 *
 * tres [SettingsGroup]:
 *  - estadísticas de uso:Número total de sesiones + Número total de mensajes
 *  - Copia de seguridad y recuperación local:Exportar + Importar(SAF launcher)
 *  - Copia de seguridad en la nube(P1 Nuevo):Selección de tipo + Configuración S3/WebDAV + Cambio de sincronización automática + Sincronizar ahora + Hora de la última sincronización
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Agrupación de configuraciones a nivel de pantalla: estadísticas de uso/copia de seguridad local/Copia de seguridad en la nube/El registro de respaldo de cuatro grupos de configuración con múltiples cuadros de diálogo es una estructura inherentemente larga(Prácticas de proyecto,ver ChatListScreen)
@Composable
internal fun BackupSection(
    sessionCount: Int,
    messageCount: Int,
    backupService: BackupService,
    settings: SettingsRepository,
    sessionRepository: SessionRepository,
    /** F-04: Persistencia del registro de respaldo(Los resultados de la copia de seguridad reciente son visibles en la página de diagnóstico). */
    autoBackupLogDao: io.zer0.muse.data.stats.AutoBackupLogDao,
    onOpenCloudBackup: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig(),
    )
    val exportableSessions by sessionRepository.observeAllSessions()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var sessionExportQuery by rememberSaveable { mutableStateOf("") }
    val filteredExportSessions =
        exportableSessions.filter { session ->
            sessionExportQuery.isBlank() ||
                session.title.contains(sessionExportQuery.trim(), ignoreCase = true)
        }
    // P3-4: La configuración de la copia de seguridad en la nube y la edición automática del intervalo de sincronización convergen en una página separada "Copia de seguridad en la nube"(CloudBackupPage).
    // v2.x: Todo el grupo de páginas de inicio converge en una única línea de entrada.(Visualización de estado + navegación),Las operaciones de interruptores y accesos directos están unificadas en la página de respaldo en la nube.
    val showGoToPageHint: () -> Unit = onOpenCloudBackup

    // estado de retroalimentación de progreso
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var localBackupDialogVisible by remember { mutableStateOf(false) }
    var showSessionExportDialog by remember { mutableStateOf(false) }
    var pendingSessionExport by remember { mutableStateOf<SessionEntity?>(null) }
    // F-27: Copia de seguridad y recuperación automáticas
    var autoRestoreTarget by remember { mutableStateOf<io.zer0.muse.data.stats.AutoBackupLogEntity?>(null) }
    var autoRestoring by remember { mutableStateOf(false) }

    // Estado de la copia de seguridad en la nube
    var hasCloudBackup by remember { mutableStateOf(false) }
    var checkingCloudBackup by remember { mutableStateOf(false) }
    // v1.48: h17 distinguir"Verificación fallida"con"Sin respaldo",No más errores de deglución silenciosa
    var cloudCheckError by remember { mutableStateOf(false) }

    // Vuelva a verificar el estado de la copia de seguridad en la nube después de los cambios de configuración
    LaunchedEffect(cloudConfig.type, cloudConfig.isConfigured) {
        if (cloudConfig.isConfigured) {
            checkingCloudBackup = true
            cloudCheckError = false
            // v1.48: h17 Utilice onSuccess/onFailure para distinguir estados de error,El fracaso ya no pretende ser"Sin respaldo"
            resultOf { backupService.hasCloudBackup() }
                .onSuccess { hasCloudBackup = it }
                .onError { _, _ -> cloudCheckError = true }
            checkingCloudBackup = false
        } else {
            hasCloudBackup = false
            cloudCheckError = false
        }
    }

    // Manejar explícitamente los casos en los que no se establece una contraseña de cifrado antes de exportar,Esto evita que los usuarios generen copias de seguridad en texto sin formato sin su conocimiento.
    var pendingPlainExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // v2.6.0: Si la exportación a realizar contiene la clave (SAF Registrar la verificación del usuario antes de la devolución de llamada)
    var pendingExportSecrets by remember { mutableStateOf(false) }

    fun startExport(uri: android.net.Uri, includeSecrets: Boolean = false) {
        scope.launch {
            exporting = true
            localBackupDialogVisible = true
            resultOf {
                val (s, m) = backupService.exportStreaming(context, uri, includeSecrets)
                MuseToast.show(context.getString(R.string.settings_backup_export_success, s, m))
            }.onError { _, t ->
                MuseToast.show(context.getString(R.string.settings_backup_export_failed, t?.message), 3500)
            }
            exporting = false
            localBackupDialogVisible = false
        }
    }

    fun startSessionExport(session: SessionEntity, uri: android.net.Uri) {
        scope.launch {
            exporting = true
            localBackupDialogVisible = true
            resultOf {
                val messages = sessionRepository.getAllMessagesForBackfill(session.id)
                val json =
                    ConversationExporter.exportToJson(
                        sessionId = session.id,
                        messages = messages,
                        chatTitle = session.title.ifBlank { context.getString(R.string.session_repo_default_title) },
                        locale = Locale.getDefault(),
                    )
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error(context.getString(R.string.backup_cannot_write, uri))
                }
                MuseToast.show(
                    context.getString(
                        R.string.settings_backup_export_session_success,
                        messages.size,
                    ),
                )
            }.onError { _, t ->
                MuseToast.show(
                    context.getString(R.string.settings_backup_export_session_failed, t?.message),
                    3500,
                )
            }
            exporting = false
            localBackupDialogVisible = false
        }
    }

    // Exportar launcher(SAF CreateDocument)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            if (cloudConfig.backupPassword.isBlank() && !cloudConfig.backupPasswordSet) {
                pendingPlainExportUri = it
            } else {
                startExport(it, pendingExportSecrets)
            }
        }
    }

    val sessionExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val session = pendingSessionExport
        pendingSessionExport = null
        if (uri != null && session != null) {
            startSessionExport(session, uri)
        }
    }

    // U-4: Copia de seguridad local para importar URI — Después de seleccionar el archivo, aparece el cuadro de confirmación de sobrescritura.,En realidad, la importación se ejecuta después de la confirmación.
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // importar launcher(SAF OpenDocument)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // U-4: La importación anulada no se ejecuta directamente,Grabe el archivo seleccionado primero para activar el cuadro de confirmación,Importación iniciada por el diálogo de confirmación al confirmar
        uri?.let { pendingImportUri = it }
    }

    // ── estadísticas de uso ──
    SectionLabel(stringResource(R.string.settings_backup_usage_stats))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = MuseIcons.messages,
            title = stringResource(R.string.settings_backup_session_count),
        ) {
            Text(
                text = "$sessionCount",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.chat,
            title = stringResource(R.string.settings_backup_message_count),
        ) {
            Text(
                text = "$messageCount",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ── Copia de seguridad y recuperación local ──
    SectionLabel(stringResource(R.string.settings_backup_local_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // v2.6.0: Contiene claves de proveedor (comentarios de los usuarios: hay demasiadas claves y espero que la copia de seguridad pueda eliminarlas todas).
        // Desactivado predeterminado —— Después de habilitarlo, el texto claro de proveedores_json (incluido el token apiKey/OAuth) se ingresará en el archivo de respaldo.
        var includeSecrets by rememberSaveable { mutableStateOf(false) }
        SettingsSwitchRow(
            icon = MuseIcons.key,
            title = stringResource(R.string.settings_backup_include_secrets),
            subtitle = stringResource(R.string.settings_backup_include_secrets_subtitle),
            checked = includeSecrets,
            onCheckedChange = { includeSecrets = it },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.upload,
            title = stringResource(R.string.settings_backup_export),
            subtitle = stringResource(R.string.settings_backup_export_subtitle),
            onClick = {
                runCatching {
                    pendingExportSecrets = includeSecrets
                    exportLauncher.launch(
                                    // Utilice fechas legibles en lugar de milisegundos de 13 dígitos: estos últimos se truncarán en el selector,
                                    // No se puede ver ni la hora de exportación ni el sufijo .json, y varias copias de seguridad tienen el mismo nombre.
                                    "muse-backup-${
                                        java.time.format
                                            .DateTimeFormatter
                                            .ofPattern("yyyyMMdd-HHmmss")
                                            .format(java.time.LocalDateTime.now())
                                    }.json",
                                )
                }.onFailure {
                    MuseToast.show(context.getString(R.string.settings_backup_export_start_failed, it.message))
                }
            },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.file,
            title = stringResource(R.string.settings_backup_export_session),
            subtitle = stringResource(R.string.settings_backup_export_session_subtitle),
            onClick = { showSessionExportDialog = true },
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.download,
            title = stringResource(R.string.settings_backup_import),
            subtitle = stringResource(R.string.settings_backup_import_subtitle),
            onClick = {
                runCatching {
                    // Algunos administradores de archivos marcarán .json/.ndjson/.zip como octeto-stream o tipo desconocido;
                    // El propio servicio de copia de seguridad reconoce formatos por contenido.,Los selectores no pueden filtrar solo aplicación/json.
                    importLauncher.launch(
                        arrayOf(
                            "application/json",
                            "application/zip",
                            "application/octet-stream",
                            "text/plain",
                            "*/*",
                        ),
                    )
                }.onFailure {
                    MuseToast.show(context.getString(R.string.settings_backup_import_start_failed, it.message))
                }
            },
        )
    }

    if (showSessionExportDialog) {
        MuseDialog(
            onDismissRequest = { showSessionExportDialog = false },
            title = stringResource(R.string.settings_backup_export_session_title),
            content = {
                if (exportableSessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_backup_export_session_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MuseTextField(
                            value = sessionExportQuery,
                            onValueChange = { sessionExportQuery = it },
                            placeholder = {
                                Text(stringResource(R.string.settings_backup_export_session_search))
                            },
                            singleLine = true,
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(filteredExportSessions, key = { it.id }) { session ->
                                // v2.5.2 fix: con lista de páginas de inicio(CHAT-16)Mismo calibre —— Importación de historia/denominación automática
                                // Quizás guarde el título como "…"/"..." puntos suspensivos puros iguales,Anteriormente, la lista de exportación se mostraba directamente como datos incorrectos.
                                val sessionDefaultTitle = stringResource(R.string.session_repo_default_title)
                                SettingsItemRow(
                                    icon = MuseIcons.chat,
                                    title = io.zer0.muse.data.session.displaySessionTitle(
                                        session.title,
                                        sessionDefaultTitle,
                                    ),
                                    titleMaxLines = 2,
                                    subtitle = stringResource(
                                        R.string.settings_backup_export_session_count,
                                        session.messageCount,
                                    ),
                                    onClick = {
                                        showSessionExportDialog = false
                                        sessionExportQuery = ""
                                        pendingSessionExport = session
                                        runCatching {
                                            sessionExportLauncher.launch(
                                                "muse-session-${safeExportFileName(session.title)}.json",
                                            )
                                        }.onFailure {
                                            pendingSessionExport = null
                                            MuseToast.show(
                                                context.getString(
                                                    R.string.settings_backup_export_start_failed,
                                                    it.message,
                                                ),
                                            )
                                        }
                                    },
                                )
                                if (session != filteredExportSessions.lastOrNull()) {
                                    SettingsGroupDivider()
                                }
                            }
                        }
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showSessionExportDialog = false },
        )
    }

    // ── Copia de seguridad en la nube(P1 Nuevo;v2.x: Convergencia a la entrada de una sola línea — La configuración, los conmutadores y las operaciones están unificados en la página de respaldo en la nube independiente)──
    SectionLabel(stringResource(R.string.settings_backup_cloud_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // Estado de la copia de seguridad en la nube(StatusDot + texto);Haga clic para ingresar a la página de respaldo en la nube independiente
        val statusColor = when {
            !cloudConfig.isConfigured -> MaterialTheme.colorScheme.outlineVariant
            checkingCloudBackup -> MaterialTheme.colorScheme.tertiary
            cloudCheckError -> MaterialTheme.colorScheme.error
            hasCloudBackup -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        val statusText = when {
            !cloudConfig.isConfigured -> stringResource(R.string.settings_backup_status_unconfigured)
            checkingCloudBackup -> stringResource(R.string.settings_backup_status_checking)
            cloudCheckError -> stringResource(R.string.settings_backup_status_check_failed)
            hasCloudBackup -> stringResource(R.string.settings_backup_status_has_backup)
            else -> stringResource(R.string.settings_backup_status_no_backup)
        }
        SettingsItemRow(
            icon = MuseIcons.cloud,
            title = stringResource(R.string.settings_backup_cloud_title),
            subtitle = statusText,
            onClick = showGoToPageHint,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.semantics { contentDescription = statusText },
                    ) {
                        StatusDot(color = statusColor, pulse = checkingCloudBackup)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    ChevronRight()
                }
            },
        )
    }

    // ── F-04: Registros de respaldo recientes(El diagnóstico es visible,De la tabla auto_backup_log)──
    SectionLabel(stringResource(R.string.settings_backup_log_title))
    SettingsGroup {
        val logs by autoBackupLogDao.observeRecent(10)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (logs.isEmpty()) {
            SettingsItemRow(
                icon = MuseIcons.info,
                title = stringResource(R.string.settings_backup_log_empty),
            )
        } else {
            val logFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, Locale.getDefault()) }
            logs.forEach { log ->
                val isSuccess = log.status == "success"
                SettingsItemRow(
                    icon = if (isSuccess) MuseIcons.check else MuseIcons.x,
                    title = logFmt.format(Date(log.createdAt)) + " · " +
                        stringResource(
                            if (isSuccess) {
                                R.string.settings_backup_log_success
                            } else {
                                R.string.settings_backup_log_failed
                            },
                        ),
                    // Volumen de copia de seguridad mostrado correctamente;Mostrar resumen de errores en caso de falla(errorMessage Cadena de diagnóstico para inglés,
                    // Copia de falla alternativa cuando falta,Facilite la comprensión a los usuarios en lugar de mostrar líneas en blanco.)
                    subtitle = if (isSuccess) {
                        // F-27: Se puede hacer clic en las indicaciones de la línea de éxito para restaurar(Con memoria/Datos empíricos)
                        stringResource(R.string.settings_backup_log_restore_hint, formatBackupSize(log.fileSizeBytes))
                    } else {
                        log.errorMessage.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.settings_backup_log_failed)
                    },
                    // F-27: Las copias de seguridad automáticas exitosas se pueden restaurar con un solo clic(Contiene un cuadro de diálogo de confirmación + comentarios sobre el progreso.)
                    onClick = if (isSuccess && !autoRestoring) {
                        { autoRestoreTarget = log }
                    } else {
                        null
                    },
                )
            }
        }
    }

    // F-27: Cuadro de diálogo de confirmación para restaurar desde una copia de seguridad automática
    autoRestoreTarget?.let { target ->
        MuseDialog(
            onDismissRequest = { if (!autoRestoring) autoRestoreTarget = null },
            title = stringResource(R.string.settings_backup_restore_auto_confirm_title),
            content = {
                Text(stringResource(R.string.settings_backup_restore_auto_confirm))
            },
            confirmText = stringResource(R.string.settings_backup_restore_auto_confirm_action),
            onConfirm = {
                autoRestoring = true
                localBackupDialogVisible = true
                scope.launch {
                    val ok = runCatching { backupService.restoreFromAutoBackup(target.backupPath) }
                        .onSuccess { (s, m) ->
                            MuseToast.show(context.getString(R.string.settings_backup_restore_auto_done, s, m))
                        }
                        .onFailure { e ->
                            Logger.w("BackupSection", "La copia de seguridad y la restauración automáticas fallaron: ${e.message}", e)
                            MuseToast.show(context.getString(R.string.settings_backup_restore_auto_failed))
                        }
                        .isSuccess
                    autoRestoring = false
                    localBackupDialogVisible = false
                    autoRestoreTarget = null
                    if (ok) {
                        MuseToast.show(context.getString(R.string.settings_backup_restore_auto_restart_hint))
                    }
                }
            },
            dismissText = stringResource(R.string.memory_screen_cancel),
            onDismiss = { if (!autoRestoring) autoRestoreTarget = null },
        )
    }

    // Exportar confirmación en texto claro
    pendingPlainExportUri?.let { uri ->
        MuseDialog(
            onDismissRequest = { pendingPlainExportUri = null },
            title = stringResource(R.string.settings_backup_plaintext_export_title),
            content = { Text(stringResource(R.string.settings_backup_plaintext_export_message)) },
            confirmText = stringResource(R.string.settings_backup_plaintext_export_action),
            onConfirm = {
                pendingPlainExportUri = null
                startExport(uri)
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { pendingPlainExportUri = null },
        )
    }

    // U-4: Confirmación de cobertura de importación local(operación de superposición,Comience a importar después de la confirmación;Generar automáticamente copias de protección antes de la recuperación.)
    pendingImportUri?.let { uri ->
        // B-8: Puede ingresar manualmente la contraseña de la copia de seguridad al importar copias de seguridad cifradas(olvidar/Las copias de seguridad antiguas aún se pueden descifrar después de cambiar la contraseña de la copia de seguridad en la nube)
        var importPassword by remember { mutableStateOf("") }
        MuseDialog(
            onDismissRequest = { pendingImportUri = null },
            title = stringResource(R.string.settings_backup_import),
            content = {
                Column {
                    Text(stringResource(R.string.settings_backup_overwrite_confirm))
                    Spacer(Modifier.height(12.dp))
                    MuseTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        placeholder = { Text(stringResource(R.string.settings_backup_import_password_hint)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
            },
            confirmText = stringResource(R.string.settings_backup_overwrite_confirm_action),
            onConfirm = {
                val pwd = importPassword.trim().takeIf { it.isNotEmpty() }
                pendingImportUri = null
                importPassword = ""
                scope.launch {
                    importing = true
                    localBackupDialogVisible = true
                    resultOf {
                        val (s, m) = backupService.import(context, uri, pwd)
                        MuseToast.show(context.getString(R.string.settings_backup_import_success, s, m))
                    }.onError { _, t ->
                        MuseToast.show(
                            context.getString(
                                R.string.settings_backup_import_failed,
                                friendlyImportError(t?.message),
                            ),
                            3500,
                        )
                    }
                    importing = false
                    localBackupDialogVisible = false
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { pendingImportUri = null },
        )
    }

    // Exportar/Importación en curso:diálogo de progreso
    if (localBackupDialogVisible) {
        MuseDialog(
            // Volver para cerrar solo la visualización de progreso y exportar/La tarea de importación continúa ejecutándose.
            onDismissRequest = { localBackupDialogVisible = false },
            title = if (exporting) {
                stringResource(
                    R.string.settings_backup_exporting,
                )
            } else {
                stringResource(R.string.settings_backup_importing)
            },
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MuseSpinner()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        if (exporting) {
                            stringResource(
                                R.string.settings_backup_exporting_data,
                            )
                        } else {
                            stringResource(R.string.settings_backup_importing_data)
                        },
                    )
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // La edición de la configuración de la copia de seguridad en la nube se ha reunido en una página separada "Copia de seguridad en la nube"(CloudBackupPage),Los formularios de campo y las operaciones de acceso directo ya no están integrados aquí.
}

/**
 * F-04: Visualización del volumen de copia de seguridad(B/KB/MB,Sin literales CJK).
 */
private fun safeExportFileName(title: String): String = title
    .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
    .trim()
    .take(48)
    .ifBlank { "session" }

private fun formatBackupSize(bytes: Long): String = when {
    // I18N-06: Sistema de seguimiento del formato numérico Locale(Ex Locale.US arreglado)
    bytes >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Traduzca el error interno de error de importación en una oración que los usuarios puedan entender.
 *
 * La información original contiene términos internos como nombres de tablas de bases de datos (como KnowledgeDocs) y "declaración/almacenamiento".
 * Mostrar directamente significa no decir nada. A continuación se ofrecen consejos prácticos basados ​​en causas comunes.
 */
private fun friendlyImportError(raw: String?): String {
    if (raw.isNullOrBlank()) return "El archivo de copia de seguridad no se puede leer. Confirme que el archivo esté completo."
    val lower = raw.lowercase()
    val rules = listOf(
        listOf("La cantidad de almacenamiento es inconsistente", "verify", "check") to "El archivo de copia de seguridad no coincide con la versión actual y se ha revertido al estado anterior a la importación.",
        listOf("password", "Contraseña", "decrypt") to "Error de descompresión, verifique si la contraseña de respaldo es correcta",
        listOf("version", "versión") to "La versión de copia de seguridad es demasiado nueva o demasiado antigua y la aplicación actual no puede leerla.",
        listOf("no such file", "not found", "no encontrado") to "No se encontró el archivo de copia de seguridad; es posible que se haya movido o eliminado",
        listOf("space", "disco", "espacio") to "Espacio de almacenamiento insuficiente; límpielo e inténtelo nuevamente.",
    )
    val hit = rules.firstOrNull { (keys, _) -> keys.any { it in lower || it in raw } }
    return hit?.second ?: "El archivo de copia de seguridad es anormal y se ha revertido al estado anterior a la importación."
}
