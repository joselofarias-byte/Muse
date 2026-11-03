package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.FileSizeFormatter
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.backup.BackupService
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.backup.CloudBackupService
import io.zer0.muse.backup.RemoteBackup
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.132: Página de configuración independiente de la copia de seguridad en la nube.
 *
 * Estructura de la página:
 *  1. Agrupación de configuración de almacenamiento en la nube — Selección de tipo + Formulario de campo S3/WebDAV + Contraseña de cifrado + Botón Guardar + Conexión de prueba
 *  2. Agrupación de operaciones — Realizar copia de seguridad ahora/restaurar desde la última copia de seguridad
 *  3. Lista de respaldo remoto — Actualizar + Restaurar por versión + Eliminar
 *  4. Grupo de respaldo automático — cambio + días de intervalo slider
 *
 * diseño:
 *  - Reutilizar [SettingsSubPageScaffold] Barra superior + contenedor de desplazamiento
 *  - Configurar formulario con borrador local,Haga clic en "Guardar configuración" antes de escribir DataStore(Consistente con ProxySettingsPage)
 *  - Las operaciones de red van al cuadro de diálogo de progreso de Dispatchers.IO +,Evitar clics repetidos
 *  - Contraseña/Máscara predeterminada del cuadro de entrada de clave,Haga clic en "Mostrar" para alternar
 */
@Composable
fun CloudBackupPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val backupService: BackupService = koinInject()
    val cloudBackupService: CloudBackupService = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val cloudConfig by settings.cloudBackupConfigFlow.collectAsStateWithLifecycle(
        initialValue = CloudBackupConfig(),
    )

    // borrador local(inicializado a actual config,Guardar y sincronizar)
    var draft by remember(cloudConfig) { mutableStateOf(cloudConfig) }
    // P0-1: Si el usuario ha editado activamente el campo de contraseña de respaldo — distinguir"Borrar contraseña explícitamente"(Limpiar y guardar → restablecer
    // backupPasswordSet)con"campo intacto"(Keystore La lectura fallida está vacía → bandera reservada,No pierdas la guardia)
    var passwordEdited by remember { mutableStateOf(false) }
    var secretVisible by remember { mutableStateOf(false) }

    // Estado de progreso de la operación
    var testing by remember { mutableStateOf(false) }
    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var cloudBackupDialogVisible by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    // U-4: Interruptor de confirmación de cobertura de recuperación en la nube(Restaurar la última/por versión,RESTORE Juega para confirmar antes,Ejecutar después de la confirmación)
    var confirmRestoreLatest by remember { mutableStateOf(false) }
    var confirmRestoreFile by remember { mutableStateOf<String?>(null) }

    // v1.141 F1: Fallo de recuperación especial UX — Registrar el tipo de operación fallida y el archivo de destino,Aparece un cuadro de diálogo de error reintentable
    var restoreFailKind by remember { mutableStateOf<RestoreFailKind?>(null) }
    var failedRestoreFile by remember { mutableStateOf<String?>(null) }

    // Lista de respaldo remoto
    var remoteBackups by remember { mutableStateOf<List<RemoteBackup>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }
    var listLoadFailed by remember { mutableStateOf(false) }

    // Intervalo de copia de seguridad automática(borrador local,Actualizaciones instantáneas al deslizar el dedo draft,Suelte el disco al guardar la configuración.)
    var intervalDaysDraft by remember(cloudConfig.autoSyncIntervalHours) {
        mutableStateOf(cloudConfig.autoSyncIntervalHours / 24f)
    }

    // Después de cambiar o guardar la configuración,Actualizar automáticamente la lista de copias de seguridad remotas
    LaunchedEffect(cloudConfig.type, cloudConfig.isConfigured) {
        if (cloudConfig.isConfigured) {
            listLoading = true
            listLoadFailed = false
            val result = resultOf { cloudBackupService.listBackups(cloudConfig) }
            result.onSuccess { remoteBackups = it }
                .onError { _, _ -> listLoadFailed = true }
            listLoading = false
        } else {
            remoteBackups = emptyList()
            listLoadFailed = false
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.cloud_backup_page_title), onBack = onBack) {
        // ── descripción superior ──
        item {
            Text(
                text = stringResource(R.string.cloud_backup_page_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.tightGap),
            )
        }

        // ── 1. Configuración de almacenamiento en la nube ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_config)) }
        item {
            SettingsGroup {
                // interruptor de habilitación(Cambiar tipo entre ninguno/tipo anterior)
                SettingsSwitchRow(
                    icon = MuseIcons.cloud,
                    title = stringResource(R.string.cloud_backup_enable),
                    subtitle = if (draft.type != "none") {
                        when (draft.type) {
                            "s3" -> stringResource(R.string.cloud_backup_type_s3)
                            "webdav" -> stringResource(R.string.cloud_backup_type_webdav)
                            else -> stringResource(R.string.cloud_backup_type_none)
                        }
                    } else {
                        stringResource(R.string.cloud_backup_type_none)
                    },
                    checked = draft.type != "none",
                    onCheckedChange = { enabled ->
                        // Volver al tipo utilizado anteriormente cuando está habilitado(Predeterminado webdav);Cuando esté deshabilitado, configúrelo en ninguno pero conserve el campo
                        draft = draft.copy(
                            type = if (enabled) {
                                if (draft.s3Endpoint.isNotBlank() || draft.s3Bucket.isNotBlank()) "s3" else "webdav"
                            } else {
                                "none"
                            },
                        )
                    },
                )
                SettingsGroupDivider()
                // Selección de tipo(Sólo se muestra cuando está habilitado)
                if (draft.type != "none") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        MuseDropdown(
                            value = draft.type,
                            onValueChange = { draft = draft.copy(type = it) },
                            label = stringResource(R.string.cloud_backup_type_label),
                            options = listOf(
                                "webdav" to stringResource(R.string.cloud_backup_type_webdav),
                                "s3" to stringResource(R.string.cloud_backup_type_s3),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SettingsGroupDivider()
                    // Mostrar campos por tipo
                    if (draft.type == "webdav") {
                        WebDavFields(
                            draft = draft,
                            onDraftChange = { draft = it },
                            secretVisible = secretVisible,
                            onToggleSecret = { secretVisible = !secretVisible },
                        )
                    } else if (draft.type == "s3") {
                        S3Fields(
                            draft = draft,
                            onDraftChange = { draft = it },
                            secretVisible = secretVisible,
                            onToggleSecret = { secretVisible = !secretVisible },
                        )
                    }
                    SettingsGroupDivider()
                    // Contraseña cifrada(Universales)
                    EncryptionPasswordField(
                        password = draft.backupPassword,
                        onPasswordChange = {
                            draft = draft.copy(backupPassword = it)
                            passwordEdited = true
                        },
                        secretVisible = secretVisible,
                        onToggleSecret = { secretVisible = !secretVisible },
                    )
                }
            }
        }
        // Guardar configuración + probar conexión
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // conexión de prueba
                MuseCapsuleButton(
                    text = stringResource(R.string.cloud_backup_test_connection),
                    onClick = {
                        if (testing) return@MuseCapsuleButton
                        if (!draft.isConfigured) {
                            MuseToast.show(context.getString(R.string.cloud_backup_test_failed))
                            return@MuseCapsuleButton
                        }
                        testing = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { cloudBackupService.testConnection(draft) }
                            testing = false
                            MuseToast.show(
                                context.getString(
                                    if (ok) R.string.cloud_backup_test_success else R.string.cloud_backup_test_failed,
                                ),
                            )
                        }
                    },
                    enabled = !testing && draft.isConfigured,
                    loading = testing,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                    modifier = Modifier.weight(1f),
                )
                // Guardar configuración
                MuseTactileButton(
                    icon = MuseIcons.database,
                    onClick = {
                        scope.launch {
                            // P0-1: Determinar en función del comportamiento del usuario antes de guardar backupPasswordSet —
                            // La contraseña no está vacía. → conjunto true;Borrar explícitamente → conjunto false(Permitir revocación);
                            // Campo de contraseña no tocado → Mantener el logotipo existente(Keystore La guardia no se perderá cuando la lectura fallida esté vacía.)
                            val finalDraft = when {
                                draft.backupPassword.isNotEmpty() -> draft.copy(backupPasswordSet = true)
                                passwordEdited -> draft.copy(backupPasswordSet = false)
                                else -> draft
                            }
                            resultOf { settings.saveCloudBackupConfig(finalDraft) }
                                .onSuccess {
                                    MuseToast.show(context.getString(R.string.cloud_backup_config_saved))
                                }
                                .onError { _, t ->
                                    MuseToast.show(t?.message ?: "Save failed", 2500)
                                }
                        }
                    },
                    contentDescription = null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ── 2. Agrupación de operaciones ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_actions)) }
        item {
            SettingsGroup {
                // v2.6.0: La copia de seguridad en la nube contiene la clave del proveedor (la misma semántica que la copia de seguridad local, desactivada de forma predeterminada)
                var cloudIncludeSecrets by remember { mutableStateOf(false) }
                SettingsSwitchRow(
                    icon = MuseIcons.key,
                    title = stringResource(R.string.settings_backup_include_secrets),
                    subtitle = stringResource(R.string.settings_backup_include_secrets_subtitle),
                    checked = cloudIncludeSecrets,
                    onCheckedChange = { cloudIncludeSecrets = it },
                )
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.cloudUpload,
                    title = stringResource(R.string.cloud_backup_backup_now),
                    subtitle = if (cloudConfig.isConfigured) {
                        stringResource(R.string.settings_backup_upload_subtitle_configured)
                    } else {
                        stringResource(R.string.settings_backup_upload_subtitle_unconfigured)
                    },
                    onClick = {
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@SettingsItemRow
                        }
                        if (backingUp || restoring) return@SettingsItemRow
                        backingUp = true
                        cloudBackupDialogVisible = true
                        scope.launch {
                            val outcome = backupService.exportToCloud(includeSecrets = cloudIncludeSecrets)
                            val ok = outcome == io.zer0.muse.backup.BackupService.CloudBackupOutcome.SUCCESS
                            backingUp = false
                            cloudBackupDialogVisible = false
                            MuseToast.show(
                                context.getString(
                                    if (ok) {
                                        R.string.cloud_backup_backup_success
                                    } else {
                                        R.string.cloud_backup_backup_failed
                                    },
                                ),
                            )
                            // Actualizar la lista remota después de una carga exitosa
                            if (ok) {
                                val list = resultOf { cloudBackupService.listBackups(cloudConfig) }.getOrNull() ?: emptyList()
                                remoteBackups = list
                            }
                        }
                    },
                )
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.cloudDownload,
                    title = stringResource(R.string.cloud_backup_restore_latest),
                    subtitle = if (cloudConfig.isConfigured) {
                        stringResource(R.string.settings_backup_restore_subtitle_configured)
                    } else {
                        stringResource(R.string.settings_backup_upload_subtitle_unconfigured)
                    },
                    onClick = {
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@SettingsItemRow
                        }
                        if (backingUp || restoring) return@SettingsItemRow
                        // U-4: Recuperación de sobrescritura,Primero abre el cuadro de confirmación.,Después de la confirmación, realice la recuperación real en onConfirm
                        confirmRestoreLatest = true
                    },
                )
            }
        }

        // ── 3. Lista de respaldo remoto ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_remote)) }
        item {
            SettingsGroup {
                // botón de actualización(fila independiente en la parte superior)
                SettingsItemRow(
                    icon = MuseIcons.refresh,
                    title = stringResource(R.string.cloud_backup_refresh_list),
                    subtitle = if (listLoading) stringResource(R.string.cloud_backup_list_loading) else null,
                    onClick = {
                        if (!cloudConfig.isConfigured) {
                            MuseToast.show(context.getString(R.string.settings_backup_configure_first))
                            return@SettingsItemRow
                        }
                        if (listLoading) return@SettingsItemRow
                        listLoading = true
                        listLoadFailed = false
                        scope.launch {
                            val result = resultOf { cloudBackupService.listBackups(cloudConfig) }
                            result.onSuccess { remoteBackups = it }
                                .onError { _, _ -> listLoadFailed = true }
                            listLoading = false
                        }
                    },
                )
                SettingsGroupDivider()
                when {
                    !cloudConfig.isConfigured -> {
                        Text(
                            text = stringResource(R.string.settings_backup_status_unconfigured),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                    listLoading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MusePaddings.cardInner),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            MuseSpinner(
                                size = 20.dp,
                            )
                            Spacer(Modifier.width(MusePaddings.contentGap))
                            Text(
                                text = stringResource(R.string.cloud_backup_list_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    listLoadFailed -> {
                        MuseErrorStateBox(
                            message = stringResource(R.string.cloud_backup_list_load_failed),
                            onRetry = {
                                scope.launch {
                                    listLoading = true
                                    listLoadFailed = false
                                    val result = resultOf { cloudBackupService.listBackups(cloudConfig) }
                                    result.onSuccess { remoteBackups = it }
                                        .onError { _, _ -> listLoadFailed = true }
                                    listLoading = false
                                }
                            },
                        )
                    }
                    remoteBackups.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.cloud_backup_list_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MusePaddings.cardInner),
                        )
                    }
                    else -> {
                        remoteBackups.forEachIndexed { idx, backup ->
                            if (idx > 0) SettingsGroupDivider()
                            RemoteBackupRow(
                                backup = backup,
                                onDelete = {
                                    if (deleting != null || restoring || backingUp) return@RemoteBackupRow
                                    deleting = backup.fileName
                                    scope.launch {
                                        val ok = cloudBackupService.deleteBackup(cloudConfig, backup.fileName)
                                        deleting = null
                                        MuseToast.show(
                                            context.getString(
                                                if (ok) {
                                                    R.string.cloud_backup_delete_success
                                                } else {
                                                    R.string.cloud_backup_delete_failed
                                                },
                                            ),
                                        )
                                        if (ok) {
                                            remoteBackups = resultOf {
                                                cloudBackupService.listBackups(cloudConfig)
                                            }.getOrNull() ?: emptyList()
                                        }
                                    }
                                },
                                onRestore = {
                                    if (deleting != null || restoring || backingUp) return@RemoteBackupRow
                                    // U-4: Recuperación de superposición por versión,Primero abre el cuadro de confirmación.,Después de la confirmación, realice la recuperación real en onConfirm
                                    confirmRestoreFile = backup.fileName
                                },
                                isDeleting = deleting == backup.fileName,
                            )
                        }
                    }
                }
            }
        }

        // ── 4. Copia de seguridad automática ──
        item { SectionLabel(stringResource(R.string.cloud_backup_section_auto)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.calendarTime,
                    title = stringResource(R.string.cloud_backup_auto_enable),
                    subtitle = stringResource(R.string.cloud_backup_auto_subtitle),
                    checked = cloudConfig.autoSync,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settings.saveCloudBackupConfig(cloudConfig.copy(autoSync = enabled))
                        }
                    },
                )
                if (cloudConfig.autoSync) {
                    SettingsGroupDivider()
                    // Días de intervalo slider(1-30 dia)
                    val intervalDays = cloudConfig.autoSyncIntervalHours / 24f
                    val sliderValue = intervalDays.coerceIn(1f, 30f)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MusePaddings.cardInner),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.cloud_backup_interval_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                // LAYOUT-01: La etiqueta toma el ancho restante + límite de línea.(Cuando solo se toma el ancho, el ancho restante aún puede ser extremadamente pequeño).
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${sliderValue.toInt()} dia",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                // LAYOUT-01: La columna de número de días está limitada a 1 fila,Sin aglomeración inversa de etiquetas.
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        MuseSlider(
                            value = sliderValue,
                            onValueChange = { intervalDaysDraft = it },
                            valueRange = 1f..30f,
                            // 1..30 Total 30 valores,steps = 30-2 = 28
                            steps = 28,
                            valueFormatter = { "${it.toInt()} dia" },
                        )
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        // v1.134 P2-3: advertencia de intervalo corto — intervalo ≤ 2 Mostrar tráfico por día/Recordatorio de batería
                        if (sliderValue.toInt() <= 2) {
                            Text(
                                text = stringResource(R.string.cloud_backup_interval_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(MusePaddings.contentGap))
                        }
                        // Arrastra para guardar(Simplificar:Guardar explícitamente con TextButton)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.cloud_backup_save_config),
                                onClick = {
                                    scope.launch {
                                        val hours = (intervalDaysDraft.toInt().coerceIn(1, 30)) * 24
                                        settings.saveCloudBackupConfig(cloudConfig.copy(autoSyncIntervalHours = hours))
                                        MuseToast.show(context.getString(R.string.cloud_backup_config_saved))
                                    }
                                },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                        }
                    }
                }
            }
        }
    }

    // Cuadro de diálogo de progreso de la operación(Conexión de prueba sin ventana emergente,Mostrar solo dentro del botón spinner)
    if (cloudBackupDialogVisible) {
        MuseDialog(
            // Regrese solo para cerrar la pantalla de progreso y la copia de seguridad en la nube./La tarea de recuperación continúa ejecutándose.
            onDismissRequest = { cloudBackupDialogVisible = false },
            title = if (backingUp) {
                stringResource(
                    R.string.settings_backup_uploading,
                )
            } else {
                stringResource(R.string.settings_backup_restoring)
            },
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseSpinner()
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        if (backingUp) {
                            stringResource(R.string.settings_backup_uploading_cloud)
                        } else {
                            stringResource(R.string.settings_backup_restoring_cloud)
                        },
                    )
                }
            },
            onConfirm = null,
            dismissText = null,
        )
    }

    // U-4: Confirmación de cobertura de recuperación en la nube — Restaurar la última/por versión,Iniciar la recuperación después de la confirmación(Generar automáticamente copias de protección antes de la recuperación.)
    val restoreFilePending = confirmRestoreFile
    if (restoreFilePending != null) {
        MuseDialog(
            onDismissRequest = { confirmRestoreFile = null },
            title = stringResource(R.string.cloud_backup_restore_this),
            content = { Text(stringResource(R.string.settings_backup_overwrite_confirm)) },
            confirmText = stringResource(R.string.settings_backup_overwrite_confirm_action),
            onConfirm = {
                val file = restoreFilePending
                confirmRestoreFile = null
                restoring = true
                cloudBackupDialogVisible = true
                scope.launch {
                    val result = backupService.importFromCloudFile(file)
                    restoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        // v1.141 F1: El fracaso ya no es sólo Toast,Cuadro de diálogo emergente especial(Se puede volver a intentar)
                        restoreFailKind = RestoreFailKind.FILE
                        failedRestoreFile = file
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                    }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { confirmRestoreFile = null },
        )
    } else if (confirmRestoreLatest) {
        MuseDialog(
            onDismissRequest = { confirmRestoreLatest = false },
            title = stringResource(R.string.cloud_backup_restore_latest),
            content = { Text(stringResource(R.string.settings_backup_overwrite_confirm)) },
            confirmText = stringResource(R.string.settings_backup_overwrite_confirm_action),
            onConfirm = {
                confirmRestoreLatest = false
                restoring = true
                cloudBackupDialogVisible = true
                scope.launch {
                    val result = backupService.importFromCloud()
                    restoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        // v1.141 F1: El fracaso ya no es sólo Toast,Cuadro de diálogo emergente especial(Se puede volver a intentar)
                        restoreFailKind = RestoreFailKind.LATEST
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                    }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { confirmRestoreLatest = false },
        )
    }

    // v1.141 F1: Fallo de recuperación especial UX — Diálogo de error + reintento de entrada(Si lo intentas de nuevo y fallas, volverá a aparecer.)
    val failKind = restoreFailKind
    if (failKind != null) {
        MuseDialog(
            onDismissRequest = { restoreFailKind = null },
            title = stringResource(R.string.cloud_backup_restore_failed_title),
            content = {
                Text(
                    text = stringResource(R.string.cloud_backup_restore_failed_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.cloud_backup_restore_retry),
            onConfirm = {
                val kind = failKind
                val file = failedRestoreFile
                restoreFailKind = null
                failedRestoreFile = null
                scope.launch {
                    restoring = true
                    cloudBackupDialogVisible = true
                    val result: Pair<Int, Int>? = when (kind) {
                        RestoreFailKind.LATEST -> backupService.importFromCloud()
                        RestoreFailKind.FILE -> {
                            // El nombre del archivo no debe estar vacío.(Error al iniciar sesión);Si el valor defensivo está vacío, se considerará un fracaso y volverá a aparecer.
                            if (file == null) null else backupService.importFromCloudFile(file)
                        }
                    }
                    restoring = false
                    cloudBackupDialogVisible = false
                    if (result == null) {
                        restoreFailKind = kind
                        failedRestoreFile = file
                    } else {
                        val (s, m) = result
                        MuseToast.show(context.getString(R.string.cloud_backup_restore_success, s, m))
                    }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { restoreFailKind = null },
        )
    }
}

/**
 * Tipo de error de recuperación en la nube — distinguir"Restaurar la última copia de seguridad"con"Restaurar por versión",Proporciona un cuadro de diálogo fallido para volver a intentar la operación original.
 */
private enum class RestoreFailKind { LATEST, FILE }

/**
 * WebDAV Formulario de campo(URL / Nombre de usuario/Contraseña/Directorio remoto).
 */
@Composable
private fun WebDavFields(
    draft: CloudBackupConfig,
    onDraftChange: (CloudBackupConfig) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        MuseTextField(
            value = draft.webdavUrl,
            onValueChange = { onDraftChange(draft.copy(webdavUrl = it)) },
            label = { Text(stringResource(R.string.settings_backup_webdav_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.webdavUsername,
            onValueChange = { onDraftChange(draft.copy(webdavUsername = it)) },
            label = { Text(stringResource(R.string.settings_backup_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.webdavPassword,
            onValueChange = { onDraftChange(draft.copy(webdavPassword = it)) },
            label = { Text(stringResource(R.string.settings_backup_password)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                MuseCapsuleButton(
                    text = if (secretVisible) {
                        stringResource(R.string.cloud_backup_hide_secret)
                    } else {
                        stringResource(R.string.cloud_backup_show_secret)
                    },
                    onClick = onToggleSecret,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            },
        )
        MuseTextField(
            value = draft.webdavPath,
            onValueChange = { onDraftChange(draft.copy(webdavPath = it)) },
            label = { Text(stringResource(R.string.settings_backup_remote_dir)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * S3 Formulario de campo(Endpoint / Region / Bucket / AccessKey / SecretKey / KeyPrefix).
 */
@Composable
private fun S3Fields(
    draft: CloudBackupConfig,
    onDraftChange: (CloudBackupConfig) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        MuseTextField(
            value = draft.s3Endpoint,
            onValueChange = { onDraftChange(draft.copy(s3Endpoint = it)) },
            label = { Text(stringResource(R.string.settings_backup_endpoint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3Region,
            onValueChange = { onDraftChange(draft.copy(s3Region = it)) },
            label = { Text(stringResource(R.string.settings_backup_region)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3Bucket,
            onValueChange = { onDraftChange(draft.copy(s3Bucket = it)) },
            label = { Text(stringResource(R.string.settings_backup_bucket)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3AccessKey,
            onValueChange = { onDraftChange(draft.copy(s3AccessKey = it)) },
            label = { Text(stringResource(R.string.settings_backup_access_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = draft.s3SecretKey,
            onValueChange = { onDraftChange(draft.copy(s3SecretKey = it)) },
            label = { Text(stringResource(R.string.settings_backup_secret_key)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                MuseCapsuleButton(
                    text = if (secretVisible) {
                        stringResource(R.string.cloud_backup_hide_secret)
                    } else {
                        stringResource(R.string.cloud_backup_show_secret)
                    },
                    onClick = onToggleSecret,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            },
        )
        MuseTextField(
            value = draft.s3KeyPrefix,
            onValueChange = { onDraftChange(draft.copy(s3KeyPrefix = it)) },
            label = { Text(stringResource(R.string.settings_backup_key_prefix)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

/**
 * Cifrar campo de contraseña(Universales,S3/WebDAV Usado igualmente).
 */
@Composable
private fun EncryptionPasswordField(
    password: String,
    onPasswordChange: (String) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Text(
            text = stringResource(R.string.settings_backup_encrypt_password),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MuseTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.settings_backup_encrypt_password_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                MuseCapsuleButton(
                    text = if (secretVisible) {
                        stringResource(R.string.cloud_backup_hide_secret)
                    } else {
                        stringResource(R.string.cloud_backup_show_secret)
                    },
                    onClick = onToggleSecret,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            },
        )
    }
}

/**
 * Línea de respaldo remota única — Nombre del archivo + hora + tamaño + botón restaurar/eliminar.
 */
@Composable
private fun RemoteBackupRow(backup: RemoteBackup, onRestore: () -> Unit, onDelete: () -> Unit, isDeleting: Boolean) {
    val fmt = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Text(
            text = backup.fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            text = buildString {
                if (backup.lastModified > 0) {
                    append(fmt.format(Date(backup.lastModified)))
                }
                if (backup.size > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatSize(backup.size))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTactileButton(
                icon = MuseIcons.cloudDownload,
                onClick = onRestore,
                contentDescription = null,
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            MuseCapsuleButton(
                text = stringResource(R.string.cloud_backup_delete_this),
                onClick = onDelete,
                enabled = !isDeleting,
                loading = isDeleting,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}

/** Formatee el número de bytes como KB/MB/GB. */
private fun formatSize(bytes: Long): String = FileSizeFormatter.format(bytes)
