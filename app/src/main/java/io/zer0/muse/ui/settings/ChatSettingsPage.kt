package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ChatGradient
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * v0.31: Página de configuración secundaria del chat — Ofrezca a los usuarios más control.
 *
 * Eliminar los interruptores relacionados con el chat de la página de apariencia,pagina separada,Organizado en 4 grupos.:
 *  1. visualización de mensajes — MOOD/pensar/token/Nombre del modelo/Marca de tiempo
 *  2. Estado expandido predeterminado — MOOD/Piense en la expansión predeterminada
 *  3. comportamiento interactivo — transmisión/desplazamiento automático/Teclas de volumen/Entra para enviar/haptic
 *  4. Avanzado — Umbral de mensaje largo/Detalles de llamada de herramienta/24 reloj de hora
 *
 * Todos los interruptores están empaquetados en [ChatPreferences] Un almacenamiento serializado en la clase de datos.
 * MessageBubble / ChatScreen / InputBar La lectura de estos interruptores determina el comportamiento de renderizado e interacción.
 *
 * v1.0.20: Nuevo"Aprobación de llamada de herramienta"grupo(fijar hacia arriba),Contiene tres niveles de modos de permiso de sesión.
 * Las políticas a nivel de herramienta única todavía están configuradas en la página de herramientas,Esta página no proporciona entrada de salto.
 */
@Composable
fun ChatSettingsPage(
    onBack: () -> Unit,
    /** v2.6.0: Abra la página de personalización del botón de acceso directo al mensaje. */
    onOpenQuickActions: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val prefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(initialValue = ChatPreferences())
    val scope = rememberCoroutineScope()

    fun update(block: (ChatPreferences) -> ChatPreferences) {
        // v2.5.3 (P1-1): Ya no use toda la instantánea de preferencias recopiladas para escribir (cuando arrastre rápidamente el control deslizante/escriba rápidamente)
        // Las modificaciones realizadas en otros lugares se sobrescribirán con instantáneas desactualizadas); en cambio, las lecturas atómicas se realizan dentro de la transacción de edición del DataStore.-cambiar-Escribir.
        scope.launch { settings.updateChatPreferences(block) }
    }

    // v1.0.47 P5-3: Token Cambio de estimación(Desactivado de forma predeterminada,Experimental,Evite la sobrecarga de rendimiento de BPE)
    val tokenEstimateEnabled by settings.tokenEstimateEnabledFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val customTitlePrompt by settings.customTitlePromptFlow.collectAsStateWithLifecycle(initialValue = null)
    val pasteAsFileThreshold by settings.pasteAsFileThresholdFlow.collectAsStateWithLifecycle(initialValue = 2000)
    var showTitlePromptDialog by remember { mutableStateOf(false) }
    var titlePromptDraft by remember(customTitlePrompt) { mutableStateOf(customTitlePrompt.orEmpty()) }
    // v1.0.47 P5-2: Cambio de pegado de texto largo a archivo(Habilitado por defecto)
    val pasteAsFileEnabled by settings.pasteAsFileEnabledFlow
        .collectAsStateWithLifecycle(initialValue = true)
    // v1.0.51: Política de notificación de respuesta(Mover desde la página de configuración de memoria,Más relevante para el comportamiento del chat)
    val notificationPolicy by settings.notificationPolicyFlow
        .collectAsStateWithLifecycle(initialValue = "when_unfocused")
    val toolLoopMaxRounds by settings.toolLoopMaxRoundsFlow
        .collectAsStateWithLifecycle(initialValue = 0)
    // Política de aprobación predeterminada global (la página de herramientas comparte la misma fuente de datos que esta página)
    val permissionMode by settings.defaultSessionPermissionModeFlow
        .collectAsStateWithLifecycle(initialValue = SessionPermissionMode.ASK)

    SettingsSubPageScaffold(title = stringResource(R.string.settings_chat_title), onBack = onBack) {
        // ── 0. Aprobación de llamada de herramienta (arriba)──
        item { SectionLabel(stringResource(R.string.settings_chat_tool_approval_section)) }
        item {
            SettingsGroup {
                val modeOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tool_approval_trusted),
                        stringResource(R.string.settings_chat_tool_approval_ask),
                        stringResource(R.string.settings_chat_tool_approval_strict),
                    )
                val modeIndex =
                    when (permissionMode) {
                        SessionPermissionMode.TRUSTED -> 0
                        SessionPermissionMode.ASK -> 1
                        SessionPermissionMode.STRICT -> 2
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.shieldCheck,
                    title = stringResource(R.string.settings_chat_tool_approval_title),
                    subtitle = stringResource(R.string.settings_chat_tool_approval_subtitle),
                    options = modeOptions,
                    selectedIndex = modeIndex,
                    onSelectedChange = { idx ->
                        val newMode =
                            when (idx) {
                                0 -> SessionPermissionMode.TRUSTED
                                2 -> SessionPermissionMode.STRICT
                                else -> SessionPermissionMode.ASK
                            }
                        scope.launch { settings.setDefaultSessionPermissionMode(newMode) }
                    },
                )
                SettingsGroupDivider()
                // Descripción del modo actual (cambio dinámico con la marcha seleccionada)
                val modeDescRes =
                    when (permissionMode) {
                        SessionPermissionMode.TRUSTED -> R.string.settings_chat_tool_approval_trusted_desc
                        SessionPermissionMode.ASK -> R.string.settings_chat_tool_approval_ask_desc
                        SessionPermissionMode.STRICT -> R.string.settings_chat_tool_approval_strict_desc
                    }
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Text(
                        text = stringResource(modeDescRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // ── 1. visualización de mensajes ──
        // ── v1.0.74: Fondo de chat(charlar/Agent/Compartir chat grupal) ──
        item { SectionLabel(stringResource(R.string.settings_chat_background_section)) }
        item {
            val chatBackground by settings.chatBackgroundFlow.collectAsStateWithLifecycle(initialValue = null)
            val bgScope = rememberCoroutineScope()
            val bgContext = LocalContext.current
            val bgLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    uri?.let { picked ->
                        bgScope.launch {
                            // Reparación de auditoría (6.5): Cambie la imagen de fondo a la ruta del archivo,DataStore Sólo se almacenan cadenas de ruta.
                            // Implementación original ~500KB base64 escribe en DataStore Preferences,Completamente reescrito para cada edición.,
                            // Expansión del archivo de configuración, el inicio en frío se ralentiza,ANR riesgo. Almacenamiento de archivos filesDir,El volumen es controlable.
                            val savedPath =
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val bytes =
                                            bgContext.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                                                ?: return@runCatching null
                                        val bmp =
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                ?: return@runCatching null
                                        val maxSide = 1920
                                        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
                                        val scaled =
                                            if (scale < 1f) {
                                                android.graphics.Bitmap.createScaledBitmap(
                                                    bmp,
                                                    (bmp.width * scale).toInt(),
                                                    (bmp.height * scale).toInt(),
                                                    true,
                                                )
                                            } else {
                                                bmp
                                            }
                                        val out = java.io.ByteArrayOutputStream()
                                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                                        if (scaled !== bmp) scaled.recycle()
                                        bmp.recycle()
                                        val target = java.io.File(bgContext.filesDir, "chat_background.jpg")
                                        java.io.FileOutputStream(target).use { fos ->
                                            fos.write(out.toByteArray())
                                        }
                                        out.close()
                                        // Compatible con valores antiguos:Continuar usando base64 existente,Nueva ruta de almacenamiento de valor(Comience con file:// para distinguir)
                                        "file://" + target.absolutePath
                                    }.getOrNull()
                                }
                            if (savedPath != null) {
                                settings.saveChatBackground(savedPath)
                                // v1.0.74 fix: previamente exitoso/No hay comentarios sobre el fracaso,Los usuarios pensaron que estaba roto.
                                MuseToast.show(bgContext.getString(R.string.settings_chat_background_saved))
                            } else {
                                MuseToast.show(bgContext.getString(R.string.settings_chat_background_failed))
                            }
                        }
                    }
                }
            SettingsGroup {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            bgLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_background_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_background_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (chatBackground != null) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    bgScope.launch {
                                        settings.saveChatBackground(null)
                                        io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(bgContext, null)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_chat_background_choose),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        item {
            // v2.6.2(comentarios de los usuarios): Fondo dedicado al modo oscuro —— Con el cambio automático de claro y oscuro, el fondo cambia en consecuencia.
            val darkBg by settings.chatBackgroundDarkFlow.collectAsStateWithLifecycle(initialValue = null)
            val darkBgScope = rememberCoroutineScope()
            val darkBgContext = LocalContext.current
            val darkBgLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    uri?.let { picked ->
                        darkBgScope.launch {
                            val savedPath = withContext(Dispatchers.IO) {
                                io.zer0.muse.ui.settings.ChatBackgroundStore.saveFromUri(
                                    darkBgContext,
                                    picked,
                                    "chat_background_dark",
                                )
                            }
                            if (savedPath != null) {
                                settings.saveChatBackgroundDark(savedPath)
                                MuseToast.show(darkBgContext.getString(R.string.settings_chat_background_saved))
                            } else {
                                MuseToast.show(darkBgContext.getString(R.string.chat_background_save_failed))
                            }
                        }
                    }
                }
            SettingsGroup {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            darkBgLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_background_dark_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_background_dark_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (darkBg != null) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    darkBgScope.launch {
                                        settings.saveChatBackgroundDark(null)
                                        io.zer0.muse.ui.settings.ChatBackgroundStore.deleteSlot(
                                            darkBgContext,
                                            "chat_background_dark",
                                        )
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_chat_background_choose),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
        item {
            // E3: Fondo degradado dinámico — Preajuste de degradado lineal de dos colores(La imagen de fondo tiene prioridad,El degradado tiene efecto cuando no hay imagen de fondo)
            val chatGradient by settings.chatGradientFlow.collectAsStateWithLifecycle(initialValue = null)
            val gradScope = rememberCoroutineScope()
            SettingsGroup {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_chat_gradient_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_chat_gradient_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (chatGradient != null) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    gradScope.launch { settings.saveChatGradient(null) }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                SettingsGroupDivider()
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_CHAT_GRADIENTS.forEach { (start, end) ->
                        val selected = chatGradient?.startColorArgb == start && chatGradient?.endColorArgb == end
                        Box(
                            modifier =
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(start.toInt()), Color(end.toInt())),
                                    ),
                                )
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color =
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    gradScope.launch { settings.saveChatGradient(ChatGradient(start, end)) }
                                },
                        )
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_chat_message_display)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_show_mood),
                    subtitle = stringResource(R.string.settings_chat_show_mood_subtitle),
                    checked = prefs.showMoodBlock,
                    onCheckedChange = { v -> update { it.copy(showMoodBlock = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_show_reasoning),
                    subtitle = stringResource(R.string.settings_chat_show_reasoning_subtitle),
                    checked = prefs.showReasoning,
                    onCheckedChange = { v -> update { it.copy(showReasoning = v) } },
                )
                SettingsGroupDivider()
                // v2.6.1(comentarios de los usuarios): Pensamiento de retorno del modelo de fuerza —— Después de la apertura, no será suprimida por el nivel de asistente OFF/AUTO.
                SettingsSwitchRow(
                    icon = MuseIcons.brain,
                    title = stringResource(R.string.settings_chat_force_reasoning),
                    subtitle = stringResource(R.string.settings_chat_force_reasoning_subtitle),
                    checked = prefs.forceReasoning,
                    onCheckedChange = { v -> update { it.copy(forceReasoning = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_show_reflection),
                    subtitle = stringResource(R.string.settings_chat_show_reflection_subtitle),
                    checked = prefs.showReflectionBlock,
                    onCheckedChange = { v -> update { it.copy(showReflectionBlock = v) } },
                )
                SettingsGroupDivider()
                // v1.0.52: Eliminar el interruptor muerto showTokenEstimate — El interruptor nunca es leído por ninguna interfaz de usuario,
                // Los usuarios lo encienden repetidamente sin ningún efecto. Lo que realmente tiene efecto es"Rendimiento"en grupo"Token estimación"cambiar.
                SettingsSwitchRow(
                    icon = MuseIcons.switch,
                    title = stringResource(R.string.settings_chat_show_model),
                    subtitle = stringResource(R.string.settings_chat_show_model_subtitle),
                    checked = prefs.showModelName,
                    onCheckedChange = { v -> update { it.copy(showModelName = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.user,
                    title = stringResource(R.string.settings_chat_show_assistant_name),
                    subtitle = stringResource(R.string.settings_chat_show_assistant_name_subtitle),
                    checked = prefs.showAssistantName,
                    onCheckedChange = { v -> update { it.copy(showAssistantName = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.calendarTime,
                    title = stringResource(R.string.settings_chat_show_timestamp),
                    subtitle = stringResource(R.string.settings_chat_show_timestamp_subtitle),
                    checked = prefs.showTimestamp,
                    onCheckedChange = { v -> update { it.copy(showTimestamp = v) } },
                )
            }
        }

        // ── v2.6.10: Conexión entre sesiones ──
        // Al abrir una nueva conversación, la conversación al final de la conversación anterior se pone en contexto.
        // Cerrado de forma predeterminada: ocupará el token de entrada y se puede activar cuando el usuario claramente necesita una sensación de continuidad.
        item { SectionLabel(stringResource(R.string.settings_chat_carry_over_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.chat,
                    title = stringResource(R.string.settings_chat_carry_over),
                    subtitle = stringResource(R.string.settings_chat_carry_over_subtitle),
                    checked = prefs.carryOverEnabled,
                    onCheckedChange = { v -> update { it.copy(carryOverEnabled = v) } },
                )
                if (prefs.carryOverEnabled) {
                    SettingsGroupDivider()
                    Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                        Text(
                            text = stringResource(
                                R.string.settings_chat_carry_over_count,
                                prefs.carryOverMessageCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_chat_carry_over_count_hint,
                                prefs.carryOverMessageCount / 2,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = prefs.carryOverMessageCount.toFloat(),
                            onValueChange = { v ->
                                // Presione 2 barras por engranaje (una pregunta y una respuesta) para evitar que los usuarios sintonicen números impares extraños.
                                val snapped = (v.toInt() / 2 * 2).coerceIn(4, 40)
                                update { it.copy(carryOverMessageCount = snapped) }
                            },
                            valueRange = 4f..40f,
                            steps = 17,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ── v2.6.0: Personalización del botón de acceso directo a mensajes (Via (página de nivel de fórmula 3)──
        item { SectionLabel(stringResource(R.string.settings_chat_quick_actions)) }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.copy,
                    title = stringResource(R.string.settings_chat_quick_customize),
                    subtitle = stringResource(R.string.settings_chat_quick_actions_desc),
                    onClick = onOpenQuickActions,
                ) { ChevronRight() }
            }
        }

        // ── Diseño de texto(Tamaño de fuente del mensaje/espaciado entre caracteres, v2.0)──
        item { SectionLabel(stringResource(R.string.settings_chat_typography_section)) }
        // v2.5.0: Panel de vista previa de diseño —— Un efecto compuesto de la configuración actual de apariencia del chat.,Lo que ves es lo que obtienes
        item {
            SettingsGroup {
                BubblePreviewPanel(prefs = prefs)
            }
        }
        item {
            SettingsGroup {
                // Tamaño de fuente del mensaje:0.85~1.25 veces,Predeterminado 100%
                var fontScaleDraft by remember { mutableStateOf(prefs.messageFontScale) }
                LaunchedEffect(prefs.messageFontScale) {
                    if (prefs.messageFontScale != fontScaleDraft) {
                        fontScaleDraft = prefs.messageFontScale
                    }
                }
                LaunchedEffect(fontScaleDraft) {
                    if (fontScaleDraft != prefs.messageFontScale) {
                        delay(400)
                        update { it.copy(messageFontScale = fontScaleDraft) }
                    }
                }
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // LAYOUT-01: El nombre del elemento de configuración toma el ancho restante + límite de línea,El porcentaje es un valor corto de ancho fijo,Límite de 1 línea.
                        Text(
                            text = stringResource(R.string.settings_chat_message_font_size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${(fontScaleDraft * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_chat_message_font_size_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseSlider(
                        value = fontScaleDraft,
                        onValueChange = { v -> fontScaleDraft = v },
                        valueRange = 0.85f..1.25f,
                        steps = 7,
                        valueFormatter = { v -> "${(v * 100).toInt()}%" },
                    )
                }
                SettingsGroupDivider()
                // espaciado de palabras:-0.02em ~ 0.08em
                var letterSpacingDraft by remember { mutableStateOf(prefs.messageLetterSpacingEm) }
                LaunchedEffect(prefs.messageLetterSpacingEm) {
                    if (prefs.messageLetterSpacingEm != letterSpacingDraft) {
                        letterSpacingDraft = prefs.messageLetterSpacingEm
                    }
                }
                LaunchedEffect(letterSpacingDraft) {
                    if (letterSpacingDraft != prefs.messageLetterSpacingEm) {
                        delay(400)
                        update { it.copy(messageLetterSpacingEm = letterSpacingDraft) }
                    }
                }
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // LAYOUT-01: Igual que arriba,Configuración del ancho del nombre del elemento + límite de línea,Las columnas numéricas están limitadas a 1 fila.
                        Text(
                            text = stringResource(R.string.settings_chat_letter_spacing),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "%.2f em".format(letterSpacingDraft),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_chat_letter_spacing_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    MuseSlider(
                        value = letterSpacingDraft,
                        onValueChange = { v -> letterSpacingDraft = v },
                        valueRange = -0.02f..0.08f,
                        steps = 9,
                        valueFormatter = { v -> "%.2f".format(v) },
                    )
                }
            }
        }

        // ── generar estilo(Temperatura global + estilo de tono + tono)──
        item { SectionLabel(stringResource(R.string.settings_chat_generation_style)) }
        item {
            SettingsGroup {
                // Control deslizante de temperatura:0-2,tamaño del paso 0.1
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.temperature,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        // Calado local + antivibración de 400 ms:Solo actualiza el borrador al arrastrar,Detente y luego escribe. DataStore,Evite persistir en cada ajuste fino
                        var temperatureDraft by remember { mutableStateOf(prefs.globalTemperature) }
                        LaunchedEffect(prefs.globalTemperature) {
                            if (prefs.globalTemperature != temperatureDraft) {
                                temperatureDraft = prefs.globalTemperature
                            }
                        }
                        LaunchedEffect(temperatureDraft) {
                            if (temperatureDraft != prefs.globalTemperature) {
                                delay(400)
                                update { it.copy(globalTemperature = temperatureDraft) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // LAYOUT-01: Igual que arriba(En este momento, los iconos y Column.weight),El nombre está ampliado + restringido.
                            Text(
                                text = stringResource(R.string.settings_chat_randomness),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "%.1f".format(temperatureDraft),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = stringResource(temperatureHint(temperatureDraft)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        MuseSlider(
                            value = temperatureDraft,
                            onValueChange = { v -> temperatureDraft = v },
                            valueRange = 0f..2f,
                            // tamaño del paso 0.1,0-2 21 puntos en total,steps = 21 - 2
                            steps = 19,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                SettingsGroupDivider()
                // elección de estilo:concise/balanced/detailed
                val styleOptions =
                    listOf(
                        stringResource(R.string.settings_chat_style_concise),
                        stringResource(R.string.settings_chat_style_balanced),
                        stringResource(R.string.settings_chat_style_detailed),
                    )
                val selectedStyleIndex =
                    when (prefs.responseStyle) {
                        "concise" -> 0
                        "detailed" -> 2
                        else -> 1
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_chat_style),
                    subtitle = stringResource(R.string.settings_chat_style_subtitle),
                    options = styleOptions,
                    selectedIndex = selectedStyleIndex,
                    onSelectedChange = { idx ->
                        val value =
                            when (idx) {
                                0 -> "concise"
                                2 -> "detailed"
                                else -> "balanced"
                            }
                        update { it.copy(responseStyle = value) }
                    },
                )
                SettingsGroupDivider()
                // elección de tono:neutral/friendly/formal/humorous
                val toneOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tone_neutral),
                        stringResource(R.string.settings_chat_tone_friendly),
                        stringResource(R.string.settings_chat_tone_formal),
                        stringResource(R.string.settings_chat_tone_humorous),
                    )
                val selectedToneIndex =
                    when (prefs.responseTone) {
                        "neutral" -> 0
                        "friendly" -> 1
                        "formal" -> 2
                        "humorous" -> 3
                        else -> 0
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.switch,
                    title = stringResource(R.string.settings_chat_tone),
                    subtitle = stringResource(R.string.settings_chat_tone_subtitle),
                    options = toneOptions,
                    selectedIndex = selectedToneIndex,
                    onSelectedChange = { idx ->
                        val value =
                            when (idx) {
                                0 -> "neutral"
                                1 -> "friendly"
                                2 -> "formal"
                                3 -> "humorous"
                                else -> "neutral"
                            }
                        update { it.copy(responseTone = value) }
                    },
                )
            }
        }

        // ── 2. Estado expandido predeterminado ──
        item { SectionLabel(stringResource(R.string.settings_chat_default_expand)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_mood_expand),
                    subtitle = stringResource(R.string.settings_chat_mood_expand_subtitle),
                    checked = prefs.moodExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(moodExpandedByDefault = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_expand_button),
                    subtitle = stringResource(R.string.settings_chat_expand_button_subtitle),
                    checked = prefs.showExpandButton,
                    onCheckedChange = { v -> update { it.copy(showExpandButton = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_reasoning_expand),
                    subtitle = stringResource(R.string.settings_chat_reasoning_expand_subtitle),
                    checked = prefs.reasoningExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reasoningExpandedByDefault = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.maximize,
                    title = stringResource(R.string.settings_chat_reflection_expand),
                    subtitle = stringResource(R.string.settings_chat_reflection_expand_subtitle),
                    checked = prefs.reflectionExpandedByDefault,
                    onCheckedChange = { v -> update { it.copy(reflectionExpandedByDefault = v) } },
                )
            }
        }

        // ── 3. comportamiento interactivo ──
        item { SectionLabel(stringResource(R.string.settings_chat_interaction)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.chat,
                    title = stringResource(R.string.settings_chat_streaming),
                    subtitle = stringResource(R.string.settings_chat_streaming_subtitle),
                    checked = prefs.streamResponse,
                    onCheckedChange = { v -> update { it.copy(streamResponse = v) } },
                )
                SettingsGroupDivider()
                // issue #3.5: Modo de respuesta rápida —— Omitir la recuperación de memoria relevante para cada ronda(Encabezado retrasado de la primera palabra).
                SettingsSwitchRow(
                    icon = MuseIcons.arrowRight,
                    title = stringResource(R.string.settings_chat_fast_reply),
                    subtitle = stringResource(R.string.settings_chat_fast_reply_subtitle),
                    checked = prefs.fastReplyMode,
                    onCheckedChange = { v -> update { it.copy(fastReplyMode = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.arrowRight,
                    title = stringResource(R.string.settings_chat_auto_scroll),
                    subtitle = stringResource(R.string.settings_chat_auto_scroll_subtitle),
                    checked = prefs.autoScrollToBottom,
                    onCheckedChange = { v -> update { it.copy(autoScrollToBottom = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.volume,
                    title = stringResource(R.string.settings_chat_volume_scroll),
                    subtitle = stringResource(R.string.settings_chat_volume_scroll_subtitle),
                    checked = prefs.volumeKeyScroll,
                    onCheckedChange = { v -> update { it.copy(volumeKeyScroll = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.handFinger,
                    title = stringResource(R.string.settings_chat_enter_send),
                    subtitle = stringResource(R.string.settings_chat_enter_send_subtitle),
                    checked = prefs.enterToSend,
                    onCheckedChange = { v -> update { it.copy(enterToSend = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.bolt,
                    title = stringResource(R.string.settings_chat_haptic),
                    subtitle = stringResource(R.string.settings_chat_haptic_subtitle),
                    checked = prefs.hapticFeedback,
                    onCheckedChange = { v -> update { it.copy(hapticFeedback = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.handFinger,
                    title = stringResource(R.string.settings_chat_predictive_back),
                    subtitle = stringResource(R.string.settings_chat_predictive_back_subtitle),
                    checked = prefs.predictiveBackEnabled,
                    onCheckedChange = { v -> update { it.copy(predictiveBackEnabled = v) } },
                )
            }
        }

        // ── 4. Avanzado ──
        item { SectionLabel(stringResource(R.string.settings_common_advanced)) }
        item {
            SettingsGroup {
                // Nota:Umbral de plegado de mensajes largos(longMessageThreshold)Elemento de configuración eliminado ——
                // MessageBubble Ya no doblar el texto de la respuesta del asistente en su totalidad,Este umbral no tiene consumidor.,La retención puede engañar a los usuarios.
                SettingsSwitchRow(
                    icon = MuseIcons.eye,
                    title = stringResource(R.string.settings_chat_show_tool_calls),
                    subtitle = stringResource(R.string.settings_chat_show_tool_calls_subtitle),
                    checked = prefs.showToolCallDetails,
                    onCheckedChange = { v -> update { it.copy(showToolCallDetails = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.pause,
                    title = stringResource(R.string.settings_chat_pause_tool_execution),
                    subtitle = stringResource(R.string.settings_chat_pause_tool_execution_subtitle),
                    checked = prefs.pauseToolExecution,
                    onCheckedChange = { v -> update { it.copy(pauseToolExecution = v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.calendarTime,
                    title = stringResource(R.string.settings_chat_24h),
                    subtitle = stringResource(R.string.settings_chat_24h_subtitle),
                    checked = prefs.use24Hour,
                    onCheckedChange = { v -> update { it.copy(use24Hour = v) } },
                )
                SettingsGroupDivider()
                // v1.0.4 (P3-4): Modo de rendimiento — Acceso MessagePaginator,Las sesiones muy largas solo muestran los últimos N elementos
                SettingsSwitchRow(
                    icon = MuseIcons.gauge,
                    title = stringResource(R.string.settings_chat_performance_mode),
                    subtitle = stringResource(R.string.settings_chat_performance_mode_subtitle),
                    checked = prefs.performanceMode,
                    onCheckedChange = { v -> update { it.copy(performanceMode = v) } },
                )
                SettingsGroupDivider()
                // v2.x: Límite de caché de sesión(Avanzado) — Número de copias en memoria de mensajes de sesión retenidas en segundo plano,La actualización en caliente entra en vigor
                SessionCacheSizeRow(settings = settings, scope = scope)
                SettingsGroupDivider()
                // v1.0.47 P5-3: Token estimación(Desactivado de forma predeterminada)——Después de abrir, el campo de entrada muestra el botón de conteo de tokens.,Haga clic para ver la ocupación del contexto
                // v1.0.52: Este es el único cambio de token que realmente tiene efecto.(Grupo de visualización de mensajes"mostrarToken"Eliminado,porque nunca funcionó)
                SettingsSwitchRow(
                    icon = MuseIcons.chartBar,
                    title = stringResource(R.string.settings_chat_token_estimate),
                    subtitle = stringResource(R.string.settings_chat_token_estimate_subtitle),
                    checked = tokenEstimateEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveTokenEstimateEnabled(v) } },
                )
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.clipboard,
                    title = stringResource(R.string.settings_chat_paste_as_file),
                    subtitle = stringResource(R.string.settings_chat_paste_as_file_subtitle),
                    checked = pasteAsFileEnabled,
                    onCheckedChange = { v -> scope.launch { settings.savePasteAsFileEnabled(v) } },
                )
                if (pasteAsFileEnabled) {
                    SettingsGroupDivider()
                    val thresholdOptions = listOf("1000", "2000", "5000")
                    val thresholdIndex =
                        when (pasteAsFileThreshold) {
                            1000 -> 0
                            5000 -> 2
                            else -> 1
                        }
                    SettingsSegmentedRow(
                        icon = MuseIcons.clipboard,
                        title = stringResource(R.string.settings_chat_paste_as_file_threshold),
                        subtitle = stringResource(R.string.settings_chat_paste_as_file_threshold_subtitle, pasteAsFileThreshold),
                        options = thresholdOptions,
                        selectedIndex = thresholdIndex,
                        onSelectedChange = { idx ->
                            val threshold =
                                when (idx) {
                                    0 -> 1000
                                    2 -> 5000
                                    else -> 2000
                                }
                            scope.launch { settings.savePasteAsFileThreshold(threshold) }
                        },
                    )
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.edit,
                    title = stringResource(R.string.settings_chat_custom_title_prompt),
                    subtitle = customTitlePrompt?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_chat_custom_title_prompt_default),
                    onClick = {
                        titlePromptDraft = customTitlePrompt.orEmpty()
                        showTitlePromptDialog = true
                    },
                ) { ChevronRight() }
            }
        }

        // ── v2.x: Dividir subgrupos — La etiqueta de repetición "Avanzada" original se ha cambiado a tres grupos semánticos.(contexto/memoria/Notificación)──
        item { SectionLabel(stringResource(R.string.settings_chat_context_section)) }
        // v2.x: Límite de ronda de herramientas(Ilimitado por defecto) — Agent Loop Después de la liberación, las tareas largas ya no se verán interrumpidas por rondas.;
        // atascado/llamadas repetidas/La protección de bucle, como los fallos continuos, sigue teniendo efecto de forma independiente.
        item {
            SettingsGroup {
                val roundOptions =
                    listOf(
                        stringResource(R.string.settings_chat_tool_rounds_unlimited),
                        "100",
                        "50",
                        "25",
                    )
                val roundIndex =
                    when (toolLoopMaxRounds) {
                        100 -> 1
                        50 -> 2
                        25 -> 3
                        else -> 0
                    }
                SettingsSegmentedRow(
                    icon = MuseIcons.refresh,
                    title = stringResource(R.string.settings_chat_tool_rounds),
                    subtitle = stringResource(R.string.settings_chat_tool_rounds_subtitle),
                    options = roundOptions,
                    selectedIndex = roundIndex,
                    onSelectedChange = { idx ->
                        val limit =
                            when (idx) {
                                1 -> 100
                                2 -> 50
                                3 -> 25
                                else -> 0
                            }
                        scope.launch { settings.saveToolLoopMaxRounds(limit) }
                    },
                )
            }
        }
        // ── Notificación ──
        item { SectionLabel(stringResource(R.string.settings_memory_notification_section)) }
        item {
            SettingsGroup {
                // Política de notificación de respuesta(never / when_unfocused / always)
                NotificationPolicyRow(
                    current = notificationPolicy,
                    onChange = { v ->
                        scope.launch { settings.saveNotificationPolicy(v) }
                    },
                )
            }
        }
    }

    if (showTitlePromptDialog) {
        MuseDialog(
            onDismissRequest = { showTitlePromptDialog = false },
            title = stringResource(R.string.settings_chat_custom_title_prompt),
            content = {
                MuseTextField(
                    value = titlePromptDraft,
                    onValueChange = { titlePromptDraft = it },
                    label = { Text(stringResource(R.string.settings_chat_custom_title_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                scope.launch { settings.saveCustomTitlePrompt(titlePromptDraft.trim().takeIf { it.isNotBlank() }) }
                showTitlePromptDialog = false
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showTitlePromptDialog = false },
        )
    }
}

/**
 * v1.0.51: Política de notificación de respuesta Seleccionar línea(Se mudó desde MemorySettingsPage).
 *
 * tercera marcha:never / when_unfocused / always,Dispóngalos horizontalmente con MuseChip.
 */
@Composable
private fun NotificationPolicyRow(current: String, onChange: (String) -> Unit) {
    val replyNotificationCd = stringResource(R.string.settings_memory_reply_notification_cd)
    val replyNotificationTitle = stringResource(R.string.settings_memory_reply_notification)
    val replyNotificationSubtitle = stringResource(R.string.settings_memory_reply_notification_subtitle)
    val policies =
        listOf(
            "never" to R.string.settings_memory_policy_never,
            "when_unfocused" to R.string.settings_memory_policy_when_unfocused,
            "always" to R.string.settings_memory_policy_always,
        )
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = MuseIcons.cloudOff,
                contentDescription = replyNotificationCd,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = replyNotificationTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = replyNotificationSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            policies.forEach { (value, labelRes) ->
                MuseChip(
                    selected = current == value,
                    onClick = { onChange(value) },
                    label = stringResource(labelRes),
                )
            }
        }
    }
}

/** Un recurso que convierte los valores de temperatura en indicaciones legibles. ID(0=OK 1=equilibrio 2=creatividad). */
private fun temperatureHint(value: Float): Int = when {
    value <= 0.3f -> R.string.settings_chat_temp_hint_certain
    value <= 0.7f -> R.string.settings_chat_temp_hint_balanced_low
    value <= 1.2f -> R.string.settings_chat_temp_hint_balanced
    value <= 1.6f -> R.string.settings_chat_temp_hint_creative_low
    else -> R.string.settings_chat_temp_hint_creative
}

/** Analizar el nombre para mostrar de un modelo comprimido(Texto predeterminado de reserva independiente). */

/**
 * v2.x: Línea de selección de límite de caché de sesión(Avanzado) — El número de copias en memoria de los mensajes de sesión retenidas en segundo plano.
 *
 * cuanto mas grande:El cambio de sesión es más rápido,El uso de memoria es mayor. La actualización en caliente entra en vigor(El límite superior se lee en tiempo real mediante SettingsRepository).
 *
 * @param settings Configurar almacén(leer y escribir session_cache_size)
 * @param scope alcance de rutina(Guardar operación)
 */
@Composable
private fun SessionCacheSizeRow(settings: SettingsRepository, scope: CoroutineScope) {
    val current by settings.sessionCacheSizeFlow.collectAsStateWithLifecycle(initialValue = 5)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MuseIcons.stack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_chat_session_cache),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_chat_session_cache_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(3, 5, 8, 12).forEach { size ->
                    MuseChip(
                        selected = current == size,
                        onClick = { scope.launch { settings.saveSessionCacheSize(size) } },
                        label = size.toString(),
                    )
                }
            }
        }
    }
}
