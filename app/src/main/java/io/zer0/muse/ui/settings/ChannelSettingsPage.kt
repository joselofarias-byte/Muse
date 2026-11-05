package io.zer0.muse.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConfig
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.channel.ChannelManager
import io.zer0.muse.channel.ChannelPlatform
import io.zer0.muse.channel.DingtalkReceiver
import io.zer0.muse.channel.FeishuReceiver
import io.zer0.muse.channel.QqReceiver
import io.zer0.muse.channel.TelegramReceiver
import io.zer0.muse.channel.WeClawClient
import io.zer0.muse.channel.WeClawReceiver
import io.zer0.muse.channel.buildCredentials
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.92: Página de configuración del canal de mensajes — Puente de mensajería instantánea externa(Feishu/QQ, etc.).
 *
 * Responsabilidades:
 *  - lista de canales(Iniciar/detener interruptor/editar/eliminar);
 *  - agregar/Editar cuadro de diálogo(AppID/AppSecret/objetivo ID,Las credenciales se guardan cifradas localmente);
 *  - Breve descripción(Capacidad de inserción + Límite de seguridad de credenciales + Dónde registrarse).
 */
@Composable
fun ChannelSettingsScreen(onBack: () -> Unit, onOpenConversations: (String) -> Unit = {}) {
    val manager: ChannelManager = koinInject()
    val weClawReceiver: WeClawReceiver = koinInject()
    val telegramReceiver: TelegramReceiver = koinInject()
    val dingtalkReceiver: DingtalkReceiver = koinInject()
    val qqReceiver: QqReceiver = koinInject()
    val feishuReceiver: FeishuReceiver = koinInject()
    val restartReceiverFor: (ChannelPlatform) -> Unit = { platform ->
        when (platform) {
            ChannelPlatform.WECLAW -> weClawReceiver.restart()
            ChannelPlatform.TELEGRAM -> telegramReceiver.restart()
            ChannelPlatform.DINGTALK -> dingtalkReceiver.restart()
            ChannelPlatform.QQ -> qqReceiver.restart()
            ChannelPlatform.FEISHU -> feishuReceiver.restart()
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val channels by manager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ChannelConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ChannelConfig?>(null) }

    LaunchedEffect(Unit) {
        manager.refresh()
        ChannelInbox.attach(context)
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.channel_page_title),
        onBack = onBack,
    ) {
        // ── tarjeta de instrucciones ──
        item(key = "intro") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Text(
                            text = stringResource(R.string.channel_intro_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.channel_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        // ── lista de canales ──
        channels.forEach { cfg ->
            item(key = cfg.id) {
                CardGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    item {
                        ChannelRow(
                            config = cfg,
                            onOpenChat = { onOpenConversations(cfg.id) },
                            onToggle = { enabled ->
                                scope.launch {
                                    manager.upsert(cfg.copy(enabled = enabled))
                                    restartReceiverFor(cfg.platform)
                                }
                            },
                            onEdit = { editTarget = cfg },
                            onDelete = { deleteTarget = cfg },
                        )
                    }
                }
            }
        }

        if (channels.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.channel_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }

        // ── Botón Agregar (v2.0.1: Ir a antes de "Recibidos recientemente") ──
        item(key = "add") {
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { showAdd = true },
            ) {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = stringResource(R.string.channel_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ── Mensajes entrantes recientes(webhook recibir) ──
        if (inbox.isNotEmpty()) {
            item(key = "inbox_title") {
                Text(
                    text = stringResource(R.string.channel_inbox_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            inbox.take(5).forEach { msg ->
                item(key = "inbox_${msg.timestamp}_${msg.platform}") {
                    CardGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        item {
                            // v2.0.1: Localización del nombre de la plataforma (los datos históricos se almacenan en nombres de enumeración, como WECLAW).
                            val inboxPlatform =
                                remember(msg.platform) {
                                    runCatching { ChannelPlatform.valueOf(msg.platform) }.getOrNull()
                                }
                            val platformLabel =
                                if (inboxPlatform != null) {
                                    stringResource(channelPlatformNameRes(inboxPlatform))
                                } else {
                                    msg.platform
                                }
                            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                                Text(
                                    text = "$platformLabel · ${msg.from.ifBlank { "-" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = msg.summary.ifBlank { "-" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Cuadro de diálogo Agregar/Editar ──
    if (showAdd || editTarget != null) {
        ChannelEditDialog(
            initial = editTarget,
            onDismiss = {
                showAdd = false
                editTarget = null
            },
            onSave = { cfg ->
                scope.launch {
                    manager.upsert(cfg)
                    restartReceiverFor(cfg.platform)
                    showAdd = false
                    editTarget = null
                }
            },
        )
    }

    // ── Eliminar confirmación ──
    deleteTarget?.let { cfg ->
        MuseDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.channel_delete_confirm),
            content = {
                val label = stringResource(channelPlatformNameRes(cfg.platform))
                Text(cfg.name.ifBlank { label })
            },
            confirmText = stringResource(R.string.skill_delete),
            onConfirm = {
                scope.launch {
                    manager.remove(cfg.id)
                    restartReceiverFor(cfg.platform)
                }
                deleteTarget = null
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** Tarjeta monocanal:Nombre/Objetivo de plataforma + interruptor de inicio/parada(primera linea);Botones de operación en una fila separada,evitar nombres largos/Deformación de extrusión de ID larga. */
@Composable
private fun ChannelRow(config: ChannelConfig, onOpenChat: () -> Unit, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val platformLabel = stringResource(channelPlatformNameRes(config.platform))
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = config.name.ifBlank { platformLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$platformLabel · ${shortTargetId(config.targetId)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MuseSwitch(
                checked = config.enabled,
                onCheckedChange = onToggle,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            ChannelActionText(
                text = stringResource(R.string.channel_open_chat),
                color = MaterialTheme.colorScheme.primary,
                onClick = onOpenChat,
            )
            ChannelActionText(
                text = stringResource(R.string.channel_edit),
                color = MaterialTheme.colorScheme.primary,
                onClick = onEdit,
            )
            ChannelActionText(
                text = stringResource(R.string.skill_delete),
                color = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

/** Texto del botón de acción del canal(Área en la que se puede hacer clic con relleno,dispuestos en una fila separada). */
@Composable
private fun ChannelActionText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier =
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** La parte central se omite cuando el ID del objetivo es demasiado largo.(primero 8 + … + cola 6),Evite que los documentos de identidad largos se doblen verticalmente en pantallas estrechas. */
private fun shortTargetId(raw: String): String {
    val id = raw.ifBlank { "-" }
    if (id.length <= 18) return id
    return id.take(8) + "…" + id.takeLast(6)
}

/** agregar/Editar cuadro de diálogo de canal (v2.0.1: Accede a campos personalizados por plataforma). */
@Composable
private fun ChannelEditDialog(initial: ChannelConfig?, onDismiss: () -> Unit, onSave: (ChannelConfig) -> Unit) {
    var platform by remember { mutableStateOf(initial?.platform ?: ChannelPlatform.FEISHU) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var appId by remember { mutableStateOf(initial?.appId.orEmpty()) }
    var secretInput by remember { mutableStateOf("") }
    var webhookEnabled by remember { mutableStateOf(initial?.webhookEnabled ?: false) }
    var webhookVerificationTokenInput by remember { mutableStateOf("") }
    var targetId by remember { mutableStateOf(initial?.targetId.orEmpty()) }
    var qqType by remember { mutableStateOf(initial?.targetType ?: "group") }
    var robotCode by remember { mutableStateOf(initial?.robotCode.orEmpty()) }
    var corpId by remember { mutableStateOf(initial?.corpId.orEmpty()) }
    var apiBaseUrl by remember { mutableStateOf(initial?.apiBaseUrl.orEmpty()) }
    var region by remember { mutableStateOf(initial?.region ?: "cn") }
    var autoReply by remember { mutableStateOf(initial?.autoReply ?: false) }
    var assistantId by remember { mutableStateOf(initial?.assistantId.orEmpty()) }
    var showBind by remember { mutableStateOf(false) }
    var showTutorial by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var showWebhookVerificationToken by remember { mutableStateOf(false) }
    var validating by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // null = Sin modificar; cadena vacía = Desvincularse explícitamente; no vacio = El token obtenido al escanear nuevamente el código QR.
    var weclawTokenOverride by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val manager: ChannelManager = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    val assistants by assistantRepository.observeAll.collectAsStateWithLifecycle(initialValue = emptyList())
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val storedSecret = initial?.appSecret.orEmpty()
    val storedSecretForPlatform = if (platform == initial?.platform) storedSecret else ""
    val storedWebhookVerificationToken =
        if (
            platform == initial?.platform &&
            platform in setOf(ChannelPlatform.FEISHU, ChannelPlatform.QQ)
        ) {
            initial.webhookVerificationToken
        } else {
            ""
        }
    val effectiveWebhookVerificationToken =
        webhookVerificationTokenInput.trim().ifBlank { storedWebhookVerificationToken }
    val effectiveSecret =
        if (platform == ChannelPlatform.WECLAW) {
            weclawTokenOverride ?: storedSecretForPlatform
        } else {
            secretInput.trim().ifBlank { storedSecretForPlatform }
        }
    val accountOptions =
        remember(inbox, platform) {
            inbox.filter { it.platform == platform.name && it.from.isNotBlank() }
                .map { it.from }
                .distinct()
                .take(6)
        }
    val validationFailedText = stringResource(R.string.channel_test_failed)
    // v2.7: Las reglas requeridas están integradas en el propio tipo de bono y las nuevas plataformas no cambiarán esto aquí.
    val canSave =
        platform.buildCredentials(
            appId = appId,
            secret = effectiveSecret,
            robotCode = robotCode,
            apiBaseUrl = apiBaseUrl,
            region = region,
        ).isComplete() &&
            (!webhookEnabled || effectiveWebhookVerificationToken.isNotBlank())
    val validate = {
        scope.launch {
            validating = true
            validationResult = null
            val probe =
                ChannelConfig(
                    id = initial?.id ?: "validate",
                    platform = platform,
                    appId = appId.trim(),
                    appSecret = effectiveSecret,
                    targetId = targetId.trim(),
                    targetType = qqType,
                    robotCode = robotCode.trim(),
                    corpId = corpId.trim(),
                    apiBaseUrl = apiBaseUrl.trim(),
                    region = region,
                )
            val result = manager.validate(probe)
            validationResult =
                result.fold(
                    onSuccess = { true to it },
                    onFailure = { false to (it.message ?: validationFailedText) },
                )
            validating = false
        }
        Unit
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (initial == null) R.string.channel_add else R.string.channel_edit_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.channel_how_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                    Modifier
                        .align(Alignment.End)
                        .clickable { showTutorial = true }
                        .padding(vertical = 2.dp),
                )
                val platformOptions = mutableListOf<Pair<String, String>>()
                for (p in ChannelPlatform.entries) {
                    platformOptions += p.name to stringResource(channelPlatformNameRes(p))
                }
                MuseDropdown(
                    value = platform.name,
                    onValueChange = { value ->
                        ChannelPlatform.entries.firstOrNull { it.name == value }?.let {
                            if (platform != it) {
                                webhookEnabled = false
                                webhookVerificationTokenInput = ""
                            }
                            platform = it
                            validationResult = null
                        }
                    },
                    label = stringResource(R.string.channel_field_platform),
                    options = platformOptions,
                )
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.channel_field_name)) },
                    singleLine = true,
                )

                when (platform) {
                    ChannelPlatform.WECLAW -> {
                        val bound = effectiveSecret.isNotBlank()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text =
                                stringResource(
                                    if (bound) {
                                        R.string.channel_weclaw_logged_in
                                    } else {
                                        R.string.channel_weclaw_need_scan
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                if (bound) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (bound) {
                                Text(
                                    text = stringResource(R.string.channel_weclaw_unbind),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier =
                                    Modifier
                                        .clickable {
                                            weclawTokenOverride = ""
                                            targetId = ""
                                        }
                                        .padding(4.dp),
                                )
                            }
                        }
                        Surface(
                            shape = MuseShapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { showBind = true },
                        ) {
                            Text(
                                text =
                                stringResource(
                                    if (bound) {
                                        R.string.channel_weclaw_rescan
                                    } else {
                                        R.string.channel_weclaw_scan
                                    },
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.channel_weclaw_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.channel_weclaw_exclusive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Surface(
                            shape = MuseShapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.channel_weclaw_reply_window),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    ChannelPlatform.FEISHU -> {
                        val regionOptions =
                            listOf(
                                "cn" to stringResource(R.string.channel_region_feishu_cn),
                                "intl" to stringResource(R.string.channel_region_lark_global),
                            )
                        MuseDropdown(
                            value = region,
                            onValueChange = { region = it },
                            label = stringResource(R.string.channel_field_region),
                            options = regionOptions,
                        )
                        MuseTextField(
                            value = appId,
                            onValueChange = { appId = it },
                            label = { Text(stringResource(R.string.channel_field_app_id)) },
                            singleLine = true,
                        )
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_app_secret),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        WebhookReceiverToggle(
                            checked = webhookEnabled,
                            onCheckedChange = { webhookEnabled = it },
                            hint = stringResource(R.string.channel_webhook_feishu_hint),
                        )
                        if (webhookEnabled) {
                            SecretInputField(
                                value = webhookVerificationTokenInput,
                                onValueChange = { webhookVerificationTokenInput = it },
                                label = stringResource(R.string.channel_webhook_verification_token),
                                storedSecret = storedWebhookVerificationToken,
                                visible = showWebhookVerificationToken,
                                onToggleVisible = { showWebhookVerificationToken = !showWebhookVerificationToken },
                            )
                        }
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_feishu_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                    ChannelPlatform.TELEGRAM -> {
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_telegram_token),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_telegram_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                    ChannelPlatform.DINGTALK -> {
                        MuseTextField(
                            value = corpId,
                            onValueChange = { corpId = it },
                            label = { Text(stringResource(R.string.channel_field_corp_id)) },
                            singleLine = true,
                        )
                        MuseTextField(
                            value = appId,
                            onValueChange = { appId = it },
                            label = { Text(stringResource(R.string.channel_field_client_id)) },
                            singleLine = true,
                        )
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_client_secret),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        MuseTextField(
                            value = robotCode,
                            onValueChange = { robotCode = it },
                            label = { Text(stringResource(R.string.channel_field_robot_code)) },
                            supportingText = { Text(stringResource(R.string.channel_robot_code_hint)) },
                            singleLine = true,
                        )
                        MuseTextField(
                            value = apiBaseUrl,
                            onValueChange = { apiBaseUrl = it },
                            label = { Text(stringResource(R.string.channel_field_api_base)) },
                            placeholder = { Text(stringResource(R.string.channel_dingtalk_api_default)) },
                            singleLine = true,
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_dingtalk_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                    ChannelPlatform.QQ -> {
                        MuseTextField(
                            value = appId,
                            onValueChange = { appId = it },
                            label = { Text(stringResource(R.string.channel_field_app_id)) },
                            singleLine = true,
                        )
                        SecretInputField(
                            value = secretInput,
                            onValueChange = { secretInput = it },
                            label = stringResource(R.string.channel_field_app_secret),
                            storedSecret = storedSecretForPlatform,
                            visible = showSecret,
                            onToggleVisible = { showSecret = !showSecret },
                        )
                        WebhookReceiverToggle(
                            checked = webhookEnabled,
                            onCheckedChange = { webhookEnabled = it },
                            hint = stringResource(R.string.channel_webhook_qq_hint),
                        )
                        if (webhookEnabled) {
                            SecretInputField(
                                value = webhookVerificationTokenInput,
                                onValueChange = { webhookVerificationTokenInput = it },
                                label = stringResource(R.string.channel_webhook_verification_token),
                                storedSecret = storedWebhookVerificationToken,
                                visible = showWebhookVerificationToken,
                                onToggleVisible = { showWebhookVerificationToken = !showWebhookVerificationToken },
                            )
                        }
                        MuseDropdown(
                            value = qqType,
                            onValueChange = { qqType = it },
                            label = stringResource(R.string.channel_field_qq_type),
                            options =
                            listOf(
                                "group" to stringResource(R.string.channel_type_group),
                                "c2c" to stringResource(R.string.channel_type_c2c),
                            ),
                        )
                        PlatformCredentialFooter(
                            hint = stringResource(R.string.channel_qq_hint),
                            validating = validating,
                            result = validationResult,
                            onValidate = validate,
                        )
                    }
                }

                if (platform != ChannelPlatform.WECLAW) {
                    Text(
                        text = stringResource(R.string.channel_owner_select),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (accountOptions.isNotEmpty()) {
                        val accountPairs = mutableListOf<Pair<String, String>>()
                        for (account in accountOptions) {
                            accountPairs += account to prettifyAccount(account)
                        }
                        MuseDropdown(
                            value = targetId,
                            onValueChange = { targetId = it },
                            label = stringResource(R.string.channel_owner_select),
                            options = accountPairs,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.channel_owner_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        text = stringResource(R.string.channel_owner_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseTextField(
                        value = targetId,
                        onValueChange = { targetId = it },
                        label = { Text(stringResource(R.string.channel_field_target)) },
                        singleLine = true,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.channel_auto_reply),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.channel_auto_reply_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    MuseSwitch(checked = autoReply, onCheckedChange = { autoReply = it })
                }
                // v2.0.1: Asistente para usar en respuestas automáticas — Hereda su carácter, modelo y parámetros.
                val assistantOptions = mutableListOf<Pair<String, String>>()
                assistantOptions += "" to stringResource(R.string.channel_assistant_default)
                for (a in assistants) {
                    assistantOptions += a.id to a.name
                }
                MuseDropdown(
                    value = assistantId,
                    onValueChange = { assistantId = it },
                    label = stringResource(R.string.channel_field_assistant),
                    options = assistantOptions,
                )
                Text(
                    text = stringResource(R.string.channel_assistant_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmText = stringResource(R.string.channel_save),
        confirmEnabled = canSave,
        onConfirm = {
            onSave(
                ChannelConfig(
                    id = initial?.id ?: ("ch_" + System.currentTimeMillis()),
                    platform = platform,
                    name = name.trim(),
                    enabled = initial?.enabled ?: true,
                    appId = appId.trim(),
                    appSecret = effectiveSecret.trim(),
                    webhookEnabled = webhookEnabled &&
                        (platform == ChannelPlatform.FEISHU || platform == ChannelPlatform.QQ),
                    webhookVerificationToken =
                    if (platform == ChannelPlatform.FEISHU || platform == ChannelPlatform.QQ) {
                        effectiveWebhookVerificationToken
                    } else {
                        ""
                    },
                    targetId = targetId.trim(),
                    targetType = qqType,
                    robotCode = robotCode.trim(),
                    corpId = corpId.trim(),
                    apiBaseUrl = apiBaseUrl.trim(),
                    region = region,
                    autoReply = autoReply,
                    assistantId = assistantId,
                    createdAt = initial?.createdAt ?: System.currentTimeMillis(),
                ),
            )
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )

    if (showBind) {
        WeClawBindDialog(
            onDismiss = { showBind = false },
            onBound = { status ->
                weclawTokenOverride = status.botToken
                appId = status.botId
                if (status.userId.isNotBlank()) targetId = status.userId
                // v2.0.1: Escanear el código QR para vincularse es una intención clara — Respuesta automática activada de forma predeterminada,evitar"Empatado pero sin respuesta".
                autoReply = true
                showBind = false
                // v2.2.1: Después de escanear el código QR para confirmar, debes dar una respuesta clara.(No hay aviso antes,El usuario pensó que no estaba conectado y hizo clic en Cancelar.)
                MuseToast.show(context.getString(R.string.channel_weclaw_logged_in))
            },
        )
    }
    if (showTutorial) {
        ChannelTutorialDialog(
            platform = platform,
            onDismiss = { showTutorial = false },
        )
    }
}

/** ClawBot El código QR actualiza automáticamente el límite superior(get_qrcode_status Cambiar automáticamente a un nuevo código cuando se devuelva el código caducado,Consistente con la implementación oficial). */
private const val MAX_QR_REFRESH_COUNT = 3

/** v2.0: ClawBot Cuadro de diálogo de enlace de código de escaneo(Tirar código QR → Estado de sondeo → Rellenar credenciales). */
@Composable
private fun WeClawBindDialog(onDismiss: () -> Unit, onBound: (WeClawClient.QrStatus) -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("loading") }
    var errorMsg by remember { mutableStateOf("") }
    var pollFailures by remember { mutableStateOf(0) }
    var refreshCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        var qr =
            WeClawClient.getQrCode().getOrElse { e ->
                status = "error"
                errorMsg = e.message.orEmpty()
                return@LaunchedEffect
            }
        bitmap = QrCodeGenerator.generateQrBitmap(qr.imgContent, 600)
        if (bitmap == null) {
            status = "error"
            return@LaunchedEffect
        }
        status = "wait"
        // v2.2.1: get_qrcode_status Es una encuesta larga(El servidor tiene aproximadamente 30s,El tiempo de espera es normal,Reintentar oficialmente con espera);
        // Renovar automáticamente el código cuando caduque(acción oficial,hasta 3 veces). Registra cada resultado,Conveniente para localizar el enlace del código de escaneo en línea.
        while (isActive) {
            val st = WeClawClient.getQrStatus(qr.qrcode).getOrNull()
            if (st == null) {
                pollFailures++
                if (pollFailures >= 3) status = "unstable"
                delay(1_500)
                continue
            }
            pollFailures = 0
            Logger.i("WeClawBind", "Estado del código de escaneo: ${st.status}")
            when (st.status.lowercase()) {
                "confirmed" -> {
                    onBound(st)
                    return@LaunchedEffect
                }
                "expired" -> {
                    if (refreshCount >= MAX_QR_REFRESH_COUNT) {
                        status = "expired"
                        return@LaunchedEffect
                    }
                    refreshCount++
                    val fresh = WeClawClient.getQrCode().getOrNull()
                    if (fresh == null) {
                        status = "expired"
                        return@LaunchedEffect
                    }
                    qr = fresh
                    bitmap = QrCodeGenerator.generateQrBitmap(fresh.imgContent, 600)
                    status = "refreshed"
                }
                "scaned", "scanned" -> status = "scaned"
                "wait" -> if (status == "unstable") status = "wait"
                else -> Unit // Estado desconocido ignorado,Continuar encuestando
            }
            delay(1_000)
        }
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_weclaw_bind_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    status == "error" ->
                        Text(
                            text =
                            stringResource(R.string.channel_weclaw_bind_failed) +
                                errorMsg.take(120),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(220.dp),
                        )
                        Text(
                            text =
                            when (status) {
                                "scaned" -> stringResource(R.string.channel_weclaw_status_scaned)
                                "expired" -> stringResource(R.string.channel_weclaw_status_expired)
                                "unstable" -> stringResource(R.string.channel_weclaw_status_unstable)
                                "refreshed" -> stringResource(R.string.channel_weclaw_status_refreshed)
                                else -> stringResource(R.string.channel_weclaw_bind_hint)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else ->
                        Text(
                            text = stringResource(R.string.channel_weclaw_bind_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                }
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/** Cuadro de entrada de clave: las claves existentes no se repetirán, solo se pueden ingresar nuevos valores para sobrescribirlas. */
@Composable
private fun SecretInputField(value: String, onValueChange: (String) -> Unit, label: String, storedSecret: String, visible: Boolean, onToggleVisible: () -> Unit) {
    MuseTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder =
        if (storedSecret.isNotBlank() && value.isBlank()) {
            { Text(stringResource(R.string.channel_secret_stored_placeholder)) }
        } else {
            null
        },
        trailingIcon = {
            Text(
                text =
                stringResource(
                    if (visible) R.string.channel_secret_hide else R.string.channel_secret_show,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                Modifier
                    .clickable(onClick = onToggleVisible)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        },
        singleLine = true,
        visualTransformation =
        if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun WebhookReceiverToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.channel_webhook_receive),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.channel_webhook_receive_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        MuseSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Descripción de credenciales + conexión de prueba + resultados. */
@Composable
private fun PlatformCredentialFooter(hint: String, validating: Boolean, result: Pair<Boolean, String>?, onValidate: () -> Unit) {
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.channel_test_connection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier =
            Modifier
                .clickable(enabled = !validating, onClick = onValidate)
                .padding(vertical = 4.dp),
        )
        if (validating) {
            MuseSpinner(size = MuseIconSizes.iconSmall)
        }
        if (result != null) {
            val (ok, message) = result
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Tutorial de acceso a la plataforma: Sigue los pasos oficiales de configuración de la plataforma. */
@Composable
private fun ChannelTutorialDialog(platform: ChannelPlatform, onDismiss: () -> Unit) {
    val stepsRes =
        when (platform) {
            ChannelPlatform.WECLAW -> R.array.channel_tutorial_weclaw_steps
            ChannelPlatform.TELEGRAM -> R.array.channel_tutorial_telegram_steps
            ChannelPlatform.FEISHU -> R.array.channel_tutorial_feishu_steps
            ChannelPlatform.DINGTALK -> R.array.channel_tutorial_dingtalk_steps
            ChannelPlatform.QQ -> R.array.channel_tutorial_qq_steps
        }
    val steps = stringArrayResource(stepsRes)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_tutorial_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (index in steps.indices) {
                    Text(
                        text = "${index + 1}. ${steps[index]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.channel_tutorial_close),
        onConfirm = onDismiss,
        dismissText = null,
        onDismiss = onDismiss,
    )
}

/** v2.0.1: Embellecimiento de la visualización del ID de cuenta — Si es demasiado largo, mantén el principio y el final. */
private fun prettifyAccount(raw: String): String = if (raw.length <= 18) raw else raw.take(8) + "…" + raw.takeLast(4)

/**
 * v2.0.1: Nombre para mostrar localizado en la plataforma — UI No muestre directamente el nombre de la constante de enumeración (FEISHU / WECLAW etc. escritura británica).
 *
 * value El partido aún termina con [ChannelPlatform.name] Es una clave estable; sólo la capa de presentación cambia con el idioma.
 */
@androidx.annotation.StringRes
private fun channelPlatformNameRes(platform: ChannelPlatform): Int = when (platform) {
    ChannelPlatform.FEISHU -> R.string.channel_platform_feishu
    ChannelPlatform.QQ -> R.string.channel_platform_qq
    ChannelPlatform.WECLAW -> R.string.channel_platform_weclaw
    ChannelPlatform.TELEGRAM -> R.string.channel_platform_telegram
    ChannelPlatform.DINGTALK -> R.string.channel_platform_dingtalk
}
