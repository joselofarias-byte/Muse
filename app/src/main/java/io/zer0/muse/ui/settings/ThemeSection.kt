package io.zer0.muse.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.CustomTheme
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.PresetTheme
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Etapa 7: Aparición section — Lista agrupada.
 *
 * diseño:
 *  - usar [SettingsGroup] Envolver varias líneas item,Finas líneas divisorias entre líneas.
 *  - modo tema(sistema/color claro/oscuro)Controles segmentados mediante cápsulas.
 *  - Selección del tamaño de fuente(pequeño/en/grande/extra grande)Controles segmentados mediante cápsulas.
 *  - variaciones del tema(mono/warm_paper/ocean_blue)Convergido,Mantenga solo colores claros y oscuros.
 *  - Apenas usado elevation,Agrupados por superficieBloques de colores variantes
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeSection(themeMode: String, fontSizeScale: String, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(initialValue = AppearanceSettingsStore.DEFAULT_THEME_ID)

    // v1.65: Interruptor de selección de color dinámico(El código ya está listo.,La interfaz de usuario anterior no tenía ningún interruptor y nunca estuvo disponible.)
    val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    // Android 12+ Solo admite selección de color dinámica,Las versiones inferiores están atenuadas(El número de versión no cambia durante el ciclo de vida del dispositivo.,Utilice el almacenamiento en caché de memoria para evitar lecturas repetidas durante la reorganización.)
    val dynamicColorSupported = remember { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S }
    // v1.97 gap7: Lista de temas definidos por el usuario + editar/Eliminar estado de ventana emergente
    val customThemes by settings.customThemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // E2: Ruta de fuente de texto personalizada(filesDir/fonts/ abajo;null=Valor predeterminado del sistema)
    val customFontPath by settings.customFontPathFlow.collectAsStateWithLifecycle(initialValue = null)
    // H5: Cambio de tema de alto contraste(Mejorar las perspectivas/contraste de fondo,Para usuarios con baja visión)
    val highContrast by settings.highContrastFlow.collectAsStateWithLifecycle(initialValue = false)
    // v2.5.0: modo de vidrio líquido(off/water/frost)con intensidad(0..100) — La configuración está oculta,Conservar la colección para su posterior publicación.

    @Suppress("UnusedPrivateProperty")
    val liquidGlassMode by settings.liquidGlassModeFlow.collectAsStateWithLifecycle(initialValue = "off")

    @Suppress("UnusedPrivateProperty")
    val liquidGlassStrength by settings.liquidGlassStrengthFlow.collectAsStateWithLifecycle(initialValue = 55)
    // E2: SAF Abrir archivo de fuente(TTF/OTF) → Copiar al directorio privado de la aplicación → guardar ruta
    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = importFontFile(context, uri)
            if (path != null) {
                settings.setCustomFontPath(path)
            } else {
                MuseToast.show(context.getString(R.string.settings_font_import_failed))
            }
        }
    }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var deletingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    // R-UI-05: Ventana emergente de selección de tiempo de conmutación programado
    var wakeTimeDialog by remember { mutableStateOf(false) }
    var sleepTimeDialog by remember { mutableStateOf(false) }
    // La vista previa del tema personalizado debe generarse de acuerdo con el modo de tema actual ColorScheme(system El patrón sigue la profundidad del sistema.)
    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    // ── modo tema ──
    SectionLabel(stringResource(R.string.settings_theme_appearance))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val followSystem = stringResource(R.string.settings_theme_follow_system)
        val lightLabel = stringResource(R.string.settings_theme_light)
        val darkLabel = stringResource(R.string.settings_theme_dark)
        // Los controles segmentados reemplazan los botones de la cápsula
        val modeOptions = listOf(followSystem, lightLabel, darkLabel)
        val selectedMode = when (themeMode) {
            "system" -> 0
            "light" -> 1
            "dark" -> 2
            else -> 0
        }
        MuseCapsuleTab(
            tabs = modeOptions,
            selectedIndex = selectedMode,
            onSelect = { idx ->
                scope.launch {
                    settings.saveThemeMode(
                        when (idx) {
                            0 -> "system"
                            1 -> "light"
                            2 -> "dark"
                            else -> "system"
                        },
                    )
                }
            },
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        SettingsGroupDivider()
        val modeLabel = when (themeMode) {
            "system" -> followSystem
            "light" -> lightLabel
            "dark" -> darkLabel
            else -> followSystem
        }
        val modeIcon = when (themeMode) {
            "system" -> MuseIcons.sun
            "light" -> MuseIcons.sun
            "dark" -> MuseIcons.moon
            else -> MuseIcons.sun
        }
        SettingsItemRow(
            icon = modeIcon,
            title = stringResource(R.string.settings_theme_current_mode),
            subtitle = modeLabel,
        )
        SettingsGroupDivider()
        // H5: Cambio de tema de alto contraste — perspectivas/Contraste de fondo completo,Para usuarios con baja visión
        HighContrastRow(
            enabled = highContrast,
            onToggle = { v -> scope.launch { settings.saveHighContrast(v) } },
        )
        SettingsGroupDivider()
        // v2.5.2: vidrio liquido — Versión rediseñada(fondo borroso+resaltar el borde+Accidente cerebrovascular),Restaurar configuración
        LiquidGlassSection(
            mode = liquidGlassMode,
            strength = liquidGlassStrength,
            onModeChange = { m -> scope.launch { settings.saveLiquidGlassMode(m) } },
            onStrengthChange = { s -> scope.launch { settings.saveLiquidGlassStrength(s) } },
        )
    }

    // ── v0.22: cambio de tema(Selector de cuadrícula de temas predeterminado) ──
    SectionLabel(stringResource(R.string.section_theme))
    // cuadrícula de temas: 3 Tarjetas de columna,Cada imagen muestra el color principal.+color de la superficie+Nombre+Estado seleccionado
    ThemeGridPicker(
        themes = PresetThemes,
        selectedThemeId = themeId,
        isDark = isDark,
        onSelect = { theme -> scope.launch { settings.saveThemeId(theme.id) } },
    )
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // v1.65: Interruptor de selección de color dinámico — Android 12+ Extraer colores del fondo de pantalla del sistema,Anular el tema predeterminado
        DynamicColorRow(
            enabled = dynamicColor,
            supported = dynamicColorSupported,
            onToggle = { v -> scope.launch { settings.saveDynamicColor(v) } },
        )
    }

    // ── v1.97 gap7: Área temática personalizada(Generar según el color de la semilla. ColorScheme) ──
    // E1: Exportación JSON del tema/importar — Compartir como texto(ACTION_SEND),Importar archivos seleccionados SAF
    val shareTheme: (CustomTheme) -> Unit = { theme ->
        val json = AppJson.encodeToString(CustomTheme.serializer(), theme)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Muse Theme: ${theme.name}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.settings_theme_custom_export)),
        )
    }
    val importThemeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val theme = try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                MuseToast.show(context.getString(R.string.settings_theme_custom_import_failed))
                return@rememberLauncherForActivityResult
            }
            AppJson.decodeFromString(CustomTheme.serializer(), text)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Logger.w("ThemeSection", "Error de lectura de importación de tema: ${e.message}")
            MuseToast.show(context.getString(R.string.settings_theme_custom_import_failed))
            return@rememberLauncherForActivityResult
        } catch (e: kotlinx.serialization.SerializationException) {
            Logger.w("ThemeSection", "Error al analizar la importación del tema: ${e.message}")
            MuseToast.show(context.getString(R.string.settings_theme_custom_import_failed))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            settings.upsertCustomTheme(theme)
            // Cambiar automáticamente a este tema después de importar,Consistente con el comportamiento de creación.
            settings.saveThemeId(theme.id)
        }
        MuseToast.show(context.getString(R.string.settings_theme_custom_import_success))
    }
    CustomThemeSection(
        customThemes = customThemes,
        themeId = themeId,
        isDark = isDark,
        onSelect = { theme -> scope.launch { settings.saveThemeId(theme.id) } },
        onAdd = {
            editingTheme = null
            showEditDialog = true
        },
        onEdit = { theme ->
            editingTheme = theme
            showEditDialog = true
        },
        onDelete = { theme -> deletingTheme = theme },
        onImport = {
            importThemeLauncher.launch(
                arrayOf("application/json", "text/plain", "application/octet-stream"),
            )
        },
        onExport = shareTheme,
    )

    // Editar/Crear ventana emergente(MuseDialog sustituto ModalBottomSheet — ModalBottomSheet tiene malla atascada en una máquina real bug)
    if (showEditDialog) {
        CustomThemeEditDialog(
            theme = editingTheme,
            isDark = isDark,
            onDismiss = {
                showEditDialog = false
                editingTheme = null
            },
            onSave = { theme ->
                scope.launch {
                    settings.upsertCustomTheme(theme)
                    // Cambiar automáticamente a este tema después de guardar
                    settings.saveThemeId(theme.id)
                }
                showEditDialog = false
                editingTheme = null
            },
        )
    }

    // Eliminar ventana emergente de confirmación
    deletingTheme?.let { theme ->
        MuseDialog(
            onDismissRequest = { deletingTheme = null },
            title = stringResource(R.string.settings_theme_custom_delete_confirm),
            content = {
                Text(
                    text = stringResource(R.string.settings_theme_custom_delete_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_theme_custom_delete),
            onConfirm = {
                scope.launch {
                    settings.deleteCustomTheme(theme.id)
                    // Después de la eliminación, si el themeId actual apunta al tema eliminado,Volver al valor preestablecido predeterminado
                    if (themeId == theme.id) {
                        settings.saveThemeId(AppearanceSettingsStore.DEFAULT_THEME_ID)
                    }
                }
                deletingTheme = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deletingTheme = null },
            destructive = true,
        )
    }

    // v1.0.92: La entrada del tema independiente del modo oscuro ha estado desconectada — Se corrigió después de la versión oscura del tema claro.,
    // A los usuarios ya no se les presentan opciones de tema oscuro(Requisitos del usuario:Mantenga únicamente sistemas temáticos de colores brillantes).

    // ── Cambio programado de tema(Feature 4) ──
    val schedule by settings.themeScheduleFlow.collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.ThemeScheduleConfig())
    SectionLabel(stringResource(R.string.settings_theme_schedule))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsSwitchRow(
            icon = MuseIcons.sun,
            title = stringResource(R.string.settings_theme_schedule_auto),
            subtitle = stringResource(R.string.settings_theme_schedule_auto_subtitle),
            checked = schedule.enabled,
            onCheckedChange = { v ->
                scope.launch { settings.saveThemeSchedule(schedule.copy(enabled = v)) }
            },
        )
        if (schedule.enabled) {
            SettingsGroupDivider()
            SettingsItemRow(
                icon = MuseIcons.sun,
                title = stringResource(R.string.settings_theme_wake_time_title),
                subtitle = "%02d:%02d".format(schedule.wakeUpHour, schedule.wakeUpMinute),
                onClick = {
                    wakeTimeDialog = true
                },
            )
            SettingsGroupDivider()
            SettingsItemRow(
                icon = MuseIcons.moon,
                title = stringResource(R.string.settings_theme_sleep_time_title),
                subtitle = "%02d:%02d".format(schedule.sleepHour, schedule.sleepMinute),
                onClick = {
                    sleepTimeDialog = true
                },
            )
        }
    }

    if (wakeTimeDialog) {
        val timeState = rememberTimePickerState(
            initialHour = schedule.wakeUpHour,
            initialMinute = schedule.wakeUpMinute,
            is24Hour = true,
        )
        MuseDialog(
            onDismissRequest = { wakeTimeDialog = false },
            title = stringResource(R.string.settings_theme_wake_time_title),
            content = { TimePicker(state = timeState) },
            onConfirm = {
                scope.launch {
                    settings.saveThemeSchedule(
                        schedule.copy(wakeUpHour = timeState.hour, wakeUpMinute = timeState.minute),
                    )
                }
                wakeTimeDialog = false
            },
            onDismiss = { wakeTimeDialog = false },
        )
    }

    if (sleepTimeDialog) {
        val timeState = rememberTimePickerState(
            initialHour = schedule.sleepHour,
            initialMinute = schedule.sleepMinute,
            is24Hour = true,
        )
        MuseDialog(
            onDismissRequest = { sleepTimeDialog = false },
            title = stringResource(R.string.settings_theme_sleep_time_title),
            content = { TimePicker(state = timeState) },
            onConfirm = {
                scope.launch {
                    settings.saveThemeSchedule(
                        schedule.copy(sleepHour = timeState.hour, sleepMinute = timeState.minute),
                    )
                }
                sleepTimeDialog = false
            },
            onDismiss = { sleepTimeDialog = false },
        )
    }

    // ── Selección del tamaño de fuente ──
    SectionLabel(stringResource(R.string.settings_theme_font_size))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val smallLabel = stringResource(R.string.settings_theme_font_small)
        val mediumLabel = stringResource(R.string.settings_theme_font_medium)
        val largeLabel = stringResource(R.string.settings_theme_font_large)
        val xlargeLabel = stringResource(R.string.settings_theme_font_xlarge)
        val smallScale = stringResource(R.string.settings_theme_font_small_label)
        val largeScale = stringResource(R.string.settings_theme_font_large_label)
        val xlargeScale = stringResource(R.string.settings_theme_font_xlarge_label)
        val mediumScale = stringResource(R.string.settings_theme_font_medium_label)
        // Los controles segmentados reemplazan los botones de la cápsula
        val fontOptions = listOf(smallLabel, mediumLabel, largeLabel, xlargeLabel)
        val selectedFont = when (fontSizeScale) {
            "small" -> 0
            "large" -> 2
            "xlarge" -> 3
            else -> 1
        }
        MuseCapsuleTab(
            tabs = fontOptions,
            selectedIndex = selectedFont,
            onSelect = { idx ->
                scope.launch {
                    settings.saveFontSizeScale(
                        when (idx) {
                            0 -> "small"
                            1 -> "medium"
                            2 -> "large"
                            3 -> "xlarge"
                            else -> "medium"
                        },
                    )
                }
            },
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        SettingsGroupDivider()
        val scaleLabel = when (fontSizeScale) {
            "small" -> smallScale
            "large" -> largeScale
            "xlarge" -> xlargeScale
            else -> mediumScale
        }
        SettingsItemRow(
            icon = MuseIcons.typography,
            title = stringResource(R.string.settings_theme_current_font_size),
            subtitle = scaleLabel,
        )
        // Etapa K: vista previa del tamaño de fuente(Visualización en tiempo real del efecto de texto bajo el tamaño de fuente actual,Sensación de lujo)
        SettingsGroupDivider()
        FontSizePreview(fontSizeScale)
    }

    // ── E2: Importación de fuentes de texto personalizadas/Borrar ──
    FontSection(
        customFontPath = customFontPath,
        onImport = { fontImportLauncher.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream")) },
        onClear = {
            scope.launch {
                // Después de borrar la ruta, los archivos antiguos permanecen en filesDir/fonts/,La próxima importación sobrescribirá el archivo con el mismo nombre.,No es necesario eliminar activamente
                settings.setCustomFontPath(null)
            }
        },
    )

    // ── Phase 4: Opciones de piel de burbuja/Vista previa(Compartir con la página de chat BubbleSkinResolver) ──
    BubbleSkinSection(
        fontSizeScale = fontSizeScale,
        defaultDark = isDark,
    )
}

/**
 * v1.65: Selección de color dinámica(Material You)Cambiar de fila.
 * Android 12+ Extraer el color principal del fondo de pantalla del sistema,Anular el tema predeterminado;La versión inferior aparece atenuada y se le indica que no es compatible.
 */
@Composable
private fun DynamicColorRow(enabled: Boolean, supported: Boolean, onToggle: (Boolean) -> Unit) {
    val dynamicColorLabel = stringResource(R.string.settings_theme_dynamic_color)
    val supportedText = stringResource(R.string.settings_theme_dynamic_color_supported)
    val unsupportedText = stringResource(R.string.settings_theme_dynamic_color_unsupported)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = supported) { onToggle(!enabled) }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MuseIcons.palette,
            contentDescription = null,
            tint = if (supported) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            modifier = Modifier.size(MuseIconSizes.icon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dynamicColorLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (supported) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Text(
                text = if (supported) supportedText else unsupportedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MuseSwitch(
            checked = enabled && supported,
            onCheckedChange = if (supported) {
                { onToggle(it) }
            } else {
                null
            },
            contentDescription = dynamicColorLabel,
        )
    }
}

/**
 * H5: Fila de cambio de tema de alto contraste — Después de su apertura, MuseTheme será negro puro./fondo blanco puro/renderizado en primer plano(Contraste 21:1),
 * Para usuarios con baja visión;Se conserva el color semántico primario del tema.
 */
@Composable
private fun HighContrastRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val highContrastLabel = stringResource(R.string.settings_theme_high_contrast)
    val descText = stringResource(R.string.settings_theme_high_contrast_desc)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onToggle(!enabled) }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MuseIcons.eye,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.icon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highContrastLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = descText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MuseSwitch(
            checked = enabled,
            onCheckedChange = onToggle,
            contentDescription = highContrastLabel,
        )
    }
}

/** v2.5.0: Área de colocación de vidrio líquido — modo(cerrar/vaso de agua/helado) + Control deslizante de fuerza(0..100). Oculto temporalmente,Se liberará nuevamente después de ajustar el efecto. */
@Suppress("FunctionNaming", "UnusedPrivateMember")
@Composable
private fun LiquidGlassSection(mode: String, strength: Int, onModeChange: (String) -> Unit, onStrengthChange: (Int) -> Unit) {
    val glassOff = stringResource(R.string.settings_glass_off)
    val glassWater = stringResource(R.string.settings_glass_water)
    val glassFrost = stringResource(R.string.settings_glass_frost)
    val modeOptions = listOf(glassOff, glassWater, glassFrost)
    val modeValues = listOf(
        io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_OFF,
        io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_WATER,
        io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_FROST,
    )
    val selectedMode = modeValues.indexOf(mode).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = MuseIcons.sparkle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.icon),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_theme_liquid_glass),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_theme_liquid_glass_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // modo: Cerrar / vaso de agua / vidrio esmerilado
        MuseCapsuleTab(
            tabs = modeOptions,
            selectedIndex = selectedMode,
            onSelect = { idx -> onModeChange(modeValues[idx]) },
        )
        // v2.6.0: La intensidad se cambia a tres niveles fijos.(bajo/en/alto) —— Control deslizante continuo en“No puedo permitirme un viaje de negocios”en el intervalo
        // Sólo puede resultar confuso; Los engranajes fijos aseguran que cada engranaje sea un salto visible.
        if (mode != io.zer0.muse.ui.theme.LiquidGlassConfig.MODE_OFF) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_glass_level),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val levelLabels =
                listOf(
                    stringResource(R.string.settings_glass_level_low),
                    stringResource(R.string.settings_glass_level_medium),
                    stringResource(R.string.settings_glass_level_high),
                )
            // Guardar 0..100 sin cambios (compatible con valores antiguos); aumente el valor del engranaje a 20/55/95 para garantizar un salto obvio en apariencia y sensación.
            val levelValues = listOf(20, 55, 95)
            // Asigna el valor actual al último nivel (se repite cuando se selecciona).
            val currentLevel = levelValues.indices.minByOrNull { kotlin.math.abs(levelValues[it] - strength) } ?: 1
            MuseCapsuleTab(
                tabs = levelLabels,
                selectedIndex = currentLevel,
                onSelect = { idx -> onStrengthChange(levelValues[idx]) },
            )
        }
    }
}

/**
 * Etapa K: vista previa del tamaño de fuente — Representa texto de muestra utilizando el tamaño de fuente seleccionado actualmente,Permita que los usuarios sientan intuitivamente los cambios en el tamaño de fuente.
 *
 * Ejemplo de representación de tres líneas en una superficieTarjeta translúcida variante(Título/Texto/Letra pequeña),
 * El tamaño de fuente se escala en tiempo real según la escala.,encarnar"Lo que ves es lo que obtienes".
 */
@Composable
private fun FontSizePreview(scale: String) {
    val factor = when (scale) {
        "small" -> 0.85f
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> 1.0f
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_font_preview_title),
                style = TextStyle(
                    fontSize = (MaterialTheme.typography.titleMedium.fontSize.value * factor).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_theme_font_preview_body),
                style = TextStyle(
                    fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value * factor).sp,
                    lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight.value * factor).sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_theme_font_preview_note),
                style = TextStyle(
                    fontSize = (MaterialTheme.typography.bodySmall.fontSize.value * factor).sp,
                ),
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * v0.22: línea de asunto — Nombre del tema a la izquierda,lado derecho Check icon(Mostrar solo cuando se selecciona).
 *
 * estilo con [SettingsItemRow] Alineación(16/12 padding),como"Tema"Elección única dentro de un grupo;
 * Cuando se selecciona, el nombre del tema y el ícono se resaltan en el color primario.
 */
@Composable
private fun ThemeOptionRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            // LAYOUT-01: Row Anchura el texto interior + limita la línea para evitar que el icono seleccionado a la derecha la comprima en una columna cuando se amplía el tamaño de fuente
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isSelected) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = stringResource(R.string.settings_theme_selected),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * Selector de cuadrícula de temas — 3 Las tarjetas de columnas muestran temas predeterminados,Cada tarjeta muestra el color principal.+color de la superficie+Nombre.
 *
 * Estado seleccionado: Borde de color del tema + marca de verificación. Color de luz de apoyo/Vista previa del modo oscuro.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ThemeGridPicker(themes: List<PresetTheme>, selectedThemeId: String, isDark: Boolean, onSelect: (PresetTheme) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
    ) {
        themes.forEach { theme ->
            val isSelected = theme.id == selectedThemeId
            val scheme = if (isDark) theme.darkScheme else theme.lightScheme
            ThemeGridCard(
                name = stringResource(theme.nameResId),
                primaryColor = scheme.primary,
                surfaceColor = scheme.surface,
                isSelected = isSelected,
                onClick = { onSelect(theme) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Tarjeta de cuadrícula de un solo tema — Vista previa de muestra + nombre + marca de verificación.
 */
@Composable
private fun ThemeGridCard(
    name: String,
    primaryColor: Color,
    surfaceColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) primaryColor else Color.Transparent
    Surface(
        modifier = modifier
            .heightIn(min = 80.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        shape = MuseShapes.medium,
        color = surfaceColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 0.dp,
            color = borderColor,
        ),
    ) {
        Box(modifier = Modifier.padding(10.dp)) {
            Column {
                // Muestras: Puntos de color principal + barras de color de superficie
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(primaryColor),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(MuseShapes.tiny)
                            .background(primaryColor.copy(alpha = 0.3f)),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            // marca de verificación
            if (isSelected) {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp),
                )
            }
        }
    }
}

/**
 * v1.60-C: Área de selección de idioma — Controles segmentados de cápsulas,Imitación del modo ThemeModeOption.
 * Opciones:Siga el sistema/chino/inglés/japonés/coreano/ruso.
 * v1.132: Se agregaron tres nuevos idiomas ja/ko/ru,6 Opción de utilizar el diseño FlowRow de dos filas y tres columnas,Evite apretar demasiado la pantalla.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSection(language: String, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()

    SectionLabel(stringResource(R.string.settings_theme_language))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val followSystem = stringResource(R.string.settings_theme_follow_system)
        val esLabel = stringResource(R.string.settings_theme_language_es)
        val enLabel = stringResource(R.string.settings_theme_language_en)
        val jaLabel = stringResource(R.string.settings_theme_language_ja)
        val koLabel = stringResource(R.string.settings_theme_language_ko)
        val ruLabel = stringResource(R.string.settings_theme_language_ru)
        val ptLabel = stringResource(R.string.settings_theme_language_pt)
        // v1.132: 6 opciones envueltas con FlowRow,Máximo 3 por línea,Evite apretar demasiado la pantalla.
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3,
        ) {
            // I4: Cambio de idioma en caliente — La actividad ya no se reinicia.
            // plano original(recreate/Intent+finish)Hay problemas con ANR bloqueado y la configuración regional de ROM no tiene efecto al reconstruir la Actividad;
            // Ahora monitoreado por MainActivity.RuntimeLocaleProvider languageFlow,Alternar solo reorganización Compose UI,
            // El idioma inicial del arranque en frío todavía está garantizado mediante adjuntoBaseContext + wrapWithLanguage.
            val switchLanguage: (String) -> Unit = { lang ->
                scope.launch { settings.saveLanguage(lang) }
            }
            ThemeModeOption(followSystem, language == "system") { switchLanguage("system") }
            ThemeModeOption(esLabel, language == "es") { switchLanguage("es") }
            ThemeModeOption(enLabel, language == "en") { switchLanguage("en") }
            ThemeModeOption(ptLabel, language == "pt-rBR") { switchLanguage("pt-rBR") }
            // v1.132: Idioma japonés agregado/coreano/Opciones de tres idiomas rusos
            ThemeModeOption(jaLabel, language == "ja") { switchLanguage("ja") }
            ThemeModeOption(koLabel, language == "ko") { switchLanguage("ko") }
            ThemeModeOption(ruLabel, language == "ru") { switchLanguage("ru") }
        }
        SettingsGroupDivider()
        val langLabel = when (language) {
            "es", "zh" -> esLabel
            "en" -> enLabel
            "pt-rBR" -> ptLabel
            "ja" -> jaLabel
            "ko" -> koLabel
            "ru" -> ruLabel
            else -> followSystem
        }
        SettingsItemRow(
            icon = MuseIcons.languages,
            title = stringResource(R.string.settings_theme_current_language),
            subtitle = langLabel,
        )
    }
}

/**
 * v1.95: Activar el área de selección de página predeterminada — Controles segmentados de cápsulas,Imitación del patrón LanguageSection.
 * Opciones:Tareas/Agente/Chat grupal. Determinar la página inicial solo al inicio,Cambiar la configuración sobre la marcha no restablece la página actual.
 */
@Composable
internal fun DefaultHomePageSection(settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val defaultPage by settings.defaultHomePageFlow.collectAsStateWithLifecycle(initialValue = 0)

    SectionLabel(stringResource(R.string.settings_default_home_page_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val taskLabel = stringResource(R.string.home_tab_tasks)
        val agentLabel = "Agent"
        val groupChatLabel = stringResource(R.string.home_tab_group_chat)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeModeOption(taskLabel, defaultPage == 0) {
                scope.launch { settings.saveDefaultHomePage(0) }
            }
            ThemeModeOption(agentLabel, defaultPage == 1) {
                scope.launch { settings.saveDefaultHomePage(1) }
            }
            ThemeModeOption(groupChatLabel, defaultPage == 2) {
                scope.launch { settings.saveDefaultHomePage(2) }
            }
        }
        SettingsGroupDivider()
        val pageLabel = when (defaultPage) {
            1 -> agentLabel
            2 -> groupChatLabel
            else -> taskLabel
        }
        SettingsItemRow(
            icon = MuseIcons.home,
            title = stringResource(R.string.settings_default_home_page_desc),
            subtitle = pageLabel,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// v1.97 gap7: Tema personalizado UI(Generar según el color de la semilla. ColorScheme)
// Implementación de referencia:rikkahub SettingThemePage.kt(Apache 2.0)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * gap7: Área temática personalizada — Lista + botón Agregar + mensaje de lista vacía.
 *
 * Usuario creado aquí/Editar/Eliminar/Elija un tema personalizado basado en los colores de las semillas,Alineado visualmente con el área temática preestablecida arriba:
 *  - usar [SectionLabel] + [SettingsGroup] paquete,Consistente con el modo de tema/área de selección de tamaño de fuente
 *  - Fila fija en la parte superior"Agregar tema"linea de operacion
 *  - Mostrar texto de aviso cuando la lista esté vacía,Evite las tarjetas en blanco
 *  - Cada artículo [CustomThemeItemRow] Contiene muestras de cuadrantes de 4 colores + nombre + edición/boton borrar
 */
@Composable
@Suppress(
    "LongParameterList",
    "LongMethod",
    // colección de devolución de llamada(elegir/agregar/Editar/Eliminar/importar/Exportar)es una estructura inherente a nivel de pantalla;El área de lista incluye agregar/Importar entradas duales y ramas vacías
)
private fun CustomThemeSection(
    customThemes: List<CustomTheme>,
    themeId: String,
    isDark: Boolean,
    onSelect: (CustomTheme) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CustomTheme) -> Unit,
    onDelete: (CustomTheme) -> Unit,
    /** E1: Abra el selector de archivos SAF para importar el tema JSON. */
    onImport: () -> Unit,
    /** E1: Exporte temas individuales como JSON para compartir. */
    onExport: (CustomTheme) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_theme_custom_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // agregar/Importar líneas de acción de temas(Se puede hacer clic en ancho completo,icono izquierdo,Texto correcto)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onAdd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.settings_theme_custom_add),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onImport),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.settings_theme_custom_import),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // Lista vacía de mensajes/temas
        if (customThemes.isEmpty()) {
            SettingsGroupDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_theme_custom_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            customThemes.forEachIndexed { index, theme ->
                if (index > 0) SettingsGroupDivider()
                CustomThemeItemRow(
                    theme = theme,
                    isSelected = theme.id == themeId,
                    isDark = isDark,
                    onSelect = { onSelect(theme) },
                    onEdit = { onEdit(theme) },
                    onDelete = { onDelete(theme) },
                    onExport = { onExport(theme) },
                )
            }
        }
    }
}

/**
 * gap7: Línea de asunto personalizada única — 4 muestras de cuadrante de color a la izquierda,segundo nombre,Editar a la derecha/Botón eliminar.
 *
 * Para paleta de colores [Canvas] Dibuja una cuadrícula de 2x2:primaryContainer / secondaryContainer /
 * tertiaryContainer / punto primario central(Cuando se selecciona, el punto se amplía y se superpone a una marca de verificación blanca.).
 * Las esquinas redondeadas se utilizan en este proyecto. [MuseShapes] Simbólico.
 */
@Composable
@Suppress(
    "LongParameterList",
    "LongMethod",
    // Una sola línea contiene 4 devoluciones de llamada.(elegir/Editar/Eliminar/Exportar)+ Tema y estado seleccionado.,Un conjunto de parámetros intrínsecos a nivel de pantalla.
)
private fun CustomThemeItemRow(
    theme: CustomTheme,
    isSelected: Boolean,
    isDark: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** E1: Exporte el tema JSON. */
    onExport: () -> Unit,
) {
    val scheme = remember(theme, isDark) { theme.generateColorScheme(isDark) }
    val unnamedLabel = stringResource(R.string.settings_theme_custom_unnamed)
    // v2.x unificación: Las acciones en línea convergen en el menú "Más"(Evite que las columnas de texto de la pantalla estrechas sean comprimidas por varios botones)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 4 muestras de cuadrante de color(40dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(40.dp),
            ) {
                // arriba a la izquierda:primaryContainer
                drawRect(color = scheme.primaryContainer, size = size)
                // arriba a la derecha:secondaryContainer
                drawRect(
                    color = scheme.secondaryContainer,
                    size = size,
                    topLeft = Offset(x = size.width / 2f, y = 0f),
                )
                // abajo a la izquierda:tertiaryContainer
                drawRect(
                    color = scheme.tertiaryContainer,
                    size = size,
                    topLeft = Offset(x = 0f, y = size.height / 2f),
                )
                // abajo a la derecha:surface(Espacio en blanco)
                drawRect(
                    color = scheme.surface,
                    size = size,
                    topLeft = Offset(x = size.width / 2f, y = size.height / 2f),
                )
                // punto primario central(Ampliar cuando se selecciona)
                drawCircle(
                    color = scheme.primary,
                    radius = if (isSelected) 12.dp.toPx() else 6.dp.toPx(),
                    center = Offset(x = size.width / 2f, y = size.height / 2f),
                )
            }
            // Marca de verificación blanca superpuesta cuando se selecciona
            if (isSelected) {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = stringResource(R.string.settings_theme_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Nombre del tema(Reserva de nombre vacío"Sin nombre")
        Text(
            text = theme.name.ifEmpty { unnamedLabel },
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        MuseTactileButton(
            icon = MuseIcons.moreVertical,
            onClick = { menuExpanded = true },
            contentDescription = moreCd,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            iconSize = MuseIconSizes.iconMedium,
        )
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MuseActionSheetRow(
                        icon = MuseIcons.share,
                        text = stringResource(R.string.settings_theme_custom_export),
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.edit,
                        text = stringResource(R.string.settings_theme_custom_edit),
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.trash,
                        text = stringResource(R.string.settings_theme_custom_delete),
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }
}

/**
 * gap7: Edición de temas personalizados/Crear ventana emergente — Basado en [MuseDialog] en lugar de ModalBottomSheet.
 *
 * muse En el proyecto, ModalBottomSheet tiene malla pegada a la máquina real. bug,Reemplace todo con MuseDialog.
 * El contenido de la ventana emergente se puede desplazar(Evite que el contenido extenso se desborde),contiene:
 *  - Cuadro de entrada del nombre del tema
 *  - 3 un [ColorPickerRow](primary/secondary/tertiary)
 *  - [ThemeColorPreview] Vista previa de coincidencia de colores en tiempo real
 *
 * El botón Guardar está deshabilitado cuando el nombre está vacío.
 */
@Composable
private fun CustomThemeEditDialog(theme: CustomTheme?, isDark: Boolean, onDismiss: () -> Unit, onSave: (CustomTheme) -> Unit) {
    // Heredar valores al editar un tema existente;Utilice valores predeterminados al crear nuevos temas(Color de semilla verde laurel)
    var currentTheme by remember {
        mutableStateOf(theme ?: CustomTheme())
    }
    val title = if (theme == null) {
        stringResource(R.string.settings_theme_custom_create)
    } else {
        stringResource(R.string.settings_theme_custom_edit)
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            // editar formulario(El desplazamiento está unificado por Box.verticalScroll del MuseDialog externo.,
            // Agregar otra capa de desplazamiento vertical aquí provocará la caída de restricciones de altura infinitas.,
            // Por lo tanto, utilice únicamente Column + spacedBy normal para la composición tipográfica.).
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Nombre del tema
                MuseTextField(
                    value = currentTheme.name,
                    onValueChange = { currentTheme = currentTheme.copy(name = it) },
                    label = { Text(stringResource(R.string.settings_theme_custom_name)) },
                    placeholder = { Text(stringResource(R.string.settings_theme_custom_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                // v1.97: Botones de acceso directo de colores seleccionados — Haga clic para llenar tres colores primario/secundario/terciario con un solo clic
                Text(
                    text = stringResource(R.string.settings_theme_custom_presets),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PRESET_COLOR_SCHEMES.forEach { scheme ->
                        PresetColorChip(
                            name = scheme.name,
                            primary = Color(scheme.primary),
                            secondary = Color(scheme.secondary),
                            tertiary = Color(scheme.tertiary),
                            onClick = {
                                currentTheme = currentTheme.copy(
                                    primaryColorArgb = scheme.primary.toLong() and 0xFFFFFFFFL,
                                    secondaryColorArgb = scheme.secondary.toLong() and 0xFFFFFFFFL,
                                    tertiaryColorArgb = scheme.tertiary.toLong() and 0xFFFFFFFFL,
                                )
                            },
                        )
                    }
                }
                // color principal(Requerido)
                Text(
                    text = stringResource(R.string.settings_theme_custom_primary_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPickerRow(
                    color = Color(currentTheme.primaryColorArgb.toInt()),
                    onColorChange = {
                        currentTheme = currentTheme.copy(
                            primaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL,
                        )
                    },
                )
                // color secundario(Opcional,Mostrar resultados derivados automáticamente cuando no se especifica)
                Text(
                    text = stringResource(R.string.settings_theme_custom_secondary_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPickerRow(
                    color = currentTheme.secondaryColorArgb?.toInt()?.let { Color(it) }
                        ?: run {
                            // Cuando no se especifica, se muestra automáticamente derivado de la información primaria. secondary,Permitir que los usuarios vean los valores predeterminados
                            Color(currentTheme.generateColorScheme(isDark).secondary.toArgb())
                        },
                    onColorChange = {
                        currentTheme = currentTheme.copy(
                            secondaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL,
                        )
                    },
                )
                // color de acento(Opcional)
                Text(
                    text = stringResource(R.string.settings_theme_custom_tertiary_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPickerRow(
                    color = currentTheme.tertiaryColorArgb?.toInt()?.let { Color(it) }
                        ?: Color(currentTheme.generateColorScheme(isDark).tertiary.toArgb()),
                    onColorChange = {
                        currentTheme = currentTheme.copy(
                            tertiaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL,
                        )
                    },
                )
                // Vista previa de coincidencia de colores en tiempo real
                ThemeColorPreview(theme = currentTheme, isDark = isDark)
            }
        },
        confirmText = stringResource(R.string.settings_theme_custom_save),
        onConfirm = { onSave(currentTheme) },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * gap7: HSL selector de color — Paleta de colores circular + tres controles deslizantes H/S/L + entrada de texto HSL.
 *
 * uso [androidx.core.graphics.ColorUtils] llevar a cabo
 * HSL ↔ Color Convertir(Misma implementación que el selector de color del sistema Android).
 *
 * Soporte de entrada de texto `hsl(267 36% 48%)` / `hsl(267, 36%, 48%)` / `267 36 48` formato etc.,
 * Cuando el análisis falla, se muestra un mensaje de error pero no bloquea la operación del control deslizante.
 */
@Composable
private fun ColorPickerRow(color: Color, onColorChange: (Color) -> Unit) {
    val hsl = remember(color) {
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    }
    var hue by remember(color) { mutableFloatStateOf(hsl[0]) }
    var saturation by remember(color) { mutableFloatStateOf(hsl[1]) }
    var lightness by remember(color) { mutableFloatStateOf(hsl[2]) }
    var hslCode by remember(color) { mutableStateOf(formatHslCode(hsl[0], hsl[1], hsl[2])) }
    var hslCodeError by remember(color) { mutableStateOf(false) }

    fun updateColor(newHue: Float, newSaturation: Float, newLightness: Float) {
        hue = newHue
        saturation = newSaturation
        lightness = newLightness
        hslCode = formatHslCode(newHue, newSaturation, newLightness)
        hslCodeError = false
        onColorChange(Color(ColorUtils.HSLToColor(floatArrayOf(newHue, newSaturation, newLightness))))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Vista previa de la paleta de colores circular(40dp)
            Canvas(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
            ) {
                drawCircle(color = color)
            }
            Column(modifier = Modifier.weight(1f)) {
                // H control deslizante(0-360)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_theme_custom_color_h),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(16.dp),
                        // LAYOUT-01: El ancho fijo es 16.dp. La marca de escala está limitada a 1 línea. Cuando se amplía el tamaño de la fuente, ya no se doblará en dos líneas ni se recortará.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSlider(
                        value = hue,
                        onValueChange = { updateColor(it, saturation, lightness) },
                        valueRange = 0f..360f,
                        showValueLabel = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                // S control deslizante(0-1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_theme_custom_color_s),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(16.dp),
                        // LAYOUT-01: Igual que arriba, el etiquetado de escala está limitado a 1 línea
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSlider(
                        value = saturation,
                        onValueChange = { updateColor(hue, it, lightness) },
                        valueRange = 0f..1f,
                        showValueLabel = false,
                        modifier = Modifier.weight(1f),
                    )
                }
                // L control deslizante(0-1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_theme_custom_color_l),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(16.dp),
                        // LAYOUT-01: Igual que arriba, el etiquetado de escala está limitado a 1 línea
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSlider(
                        value = lightness,
                        onValueChange = { updateColor(hue, saturation, it) },
                        valueRange = 0f..1f,
                        showValueLabel = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // HSL Entrada de texto(apoyo hsl(267 36% 48%) formato etc.)
        MuseTextField(
            value = hslCode,
            onValueChange = { value ->
                hslCode = value
                val parsedHsl = parseHslCode(value)
                hslCodeError = parsedHsl == null
                if (parsedHsl != null) {
                    hue = parsedHsl[0]
                    saturation = parsedHsl[1]
                    lightness = parsedHsl[2]
                    onColorChange(Color(ColorUtils.HSLToColor(parsedHsl)))
                }
            },
            label = { Text("HSL") },
            placeholder = { Text("hsl(267 36% 48%)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = hslCodeError,
        )
    }
}

/**
 * gap7: Vista previa del color del tema personalizado — Muestra 6 parches de colores clave.
 *
 * Utilice el actualmente editado [theme] Generación en tiempo real ColorScheme,mostrar primary / secondary / tertiary /
 * primaryContainer / secondaryContainer / surface seis colores,Permita a los usuarios obtener una vista previa del efecto antes de guardarlo.
 */
@Composable
private fun ThemeColorPreview(theme: CustomTheme, isDark: Boolean) {
    val scheme = remember(theme, isDark) { theme.generateColorScheme(isDark) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_theme_custom_preview),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MuseShapes.medium)
                .background(scheme.surface)
                .padding(MusePaddings.screen),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ColorSwatch(scheme.primary, "P")
            ColorSwatch(scheme.secondary, "S")
            ColorSwatch(scheme.tertiary, "T")
            ColorSwatch(scheme.primaryContainer, "PC")
            ColorSwatch(scheme.secondaryContainer, "SC")
            ColorSwatch(scheme.surface, "Sf")
        }
    }
}

/**
 * gap7: bloque de un solo color — Muestra redonda + etiqueta debajo.
 */
@Composable
private fun ColorSwatch(color: Color, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        ) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── HSL función auxiliar de análisis ──

/** HSL Regularidad para hacer coincidir números en la entrada de texto(Soporta números enteros y decimales). */
private val hslNumberRegex = Regex("""[-+]?\d*\.?\d+""")

/**
 * Analizar texto HSL como FloatArray(longitud 3:[hue, saturation, lightness]).
 *
 * Formatos soportados:
 *  - `hsl(267 36% 48%)` (CSS Color 4 gramática moderna)
 *  - `hsl(267, 36%, 48%)` (CSS Color 3 gramática tradicional)
 *  - `267 36 48` (Números puros separados por espacios.)
 *
 * Devolver si falla el análisis null(La persona que llama muestra un mensaje de error en consecuencia).
 */
private fun parseHslCode(value: String): FloatArray? {
    val values = buildList {
        for (match in hslNumberRegex.findAll(value)) {
            add(match.value.toFloatOrNull() ?: return null)
            if (size == 3) break
        }
    }
    if (values.size != 3) return null
    val hue = values[0].coerceIn(0f, 360f)
    val saturation = parseHslPercentOrFraction(values[1]) ?: return null
    val lightness = parseHslPercentOrFraction(values[2]) ?: return null
    return floatArrayOf(hue, saturation, lightness)
}

/**
 * Analizar la saturación de HSL/componente de ligereza — Porcentaje de soporte(>1 como porcentaje)o decimal(0-1).
 *
 * Ejemplo:`36%` → 0.36;`36` → 0.36;`0.36` → 0.36;`1.5` → 1.0(límite superior de saturación).
 */
private fun parseHslPercentOrFraction(value: Float): Float? {
    if (!value.isFinite()) return null
    return if (value > 1f) {
        (value / 100f).coerceIn(0f, 1f)
    } else {
        value.coerceIn(0f, 1f)
    }
}

/** Formatee un triplete HSL como `hsl(267 36% 48%)` cuerda(Se utiliza para el eco del cuadro de entrada de texto.). */
private fun formatHslCode(hue: Float, saturation: Float, lightness: Float): String {
    return "hsl(${hue.roundToInt()} ${(saturation * 100).roundToInt()}% ${(lightness * 100).roundToInt()}%)"
}

/**
 * v1.97: Datos de combinación de colores destacados.
 * ARGB int valor,Tres colores para rellenar temas personalizados con un solo clic.
 */
private data class PresetColorScheme(
    val name: String,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
)

/** v1.97: 8 Conjunto de colores seleccionados(apto para daltónicos,Cubrir caliente/frio/tonos neutros). */
private val PRESET_COLOR_SCHEMES = listOf(
    PresetColorScheme("verde laurel", 0xFF2E7D32.toInt(), 0xFF66BB6A.toInt(), 0xFFA5D6A7.toInt()),
    PresetColorScheme("azul marino profundo", 0xFF1565C0.toInt(), 0xFF42A5F5.toInt(), 0xFF90CAF9.toInt()),
    PresetColorScheme("morado crepusculo", 0xFF6A1B9A.toInt(), 0xFFAB47BC.toInt(), 0xFFCE93D8.toInt()),
    PresetColorScheme("Chixiahong", 0xFFC62828.toInt(), 0xFFEF5350.toInt(), 0xFFFFCDD2.toInt()),
    PresetColorScheme("naranja ámbar", 0xFFE65100.toInt(), 0xFFFF7043.toInt(), 0xFFFFCC80.toInt()),
    PresetColorScheme("verde celadón", 0xFF00695C.toInt(), 0xFF26A69A.toInt(), 0xFF80CBC4.toInt()),
    PresetColorScheme("Papel tinta gris", 0xFF424242.toInt(), 0xFF757575.toInt(), 0xFFBDBDBD.toInt()),
    PresetColorScheme("polvo de sakura", 0xFFAD1457.toInt(), 0xFFEC407A.toInt(), 0xFFF8BBD0.toInt()),
)

/**
 * v1.97: Fichas rápidas de combinación de colores seleccionadas — Mostrar puntos de tres colores + nombre,Haga clic para llenar.
 */
@Composable
private fun PresetColorChip(name: String, primary: Color, secondary: Color, tertiary: Color, onClick: () -> Unit) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(primary))
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(secondary))
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(tertiary))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * E2: Área de configuración de fuente de texto personalizada.
 *
 * Inmediatamente después de importar [io.zer0.muse.ui.theme.loadCustomFontFamily] Globalmente eficaz
 * (MainActivity Recopilar la reconstrucción personalizada de FontPathFlow MuseTheme Typography).
 */
@Composable
private fun FontSection(customFontPath: String?, onImport: () -> Unit, onClear: () -> Unit) {
    SectionLabel(stringResource(R.string.settings_font_section_title))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        val currentName = customFontPath?.let { File(it).name }
        SettingsItemRow(
            icon = MuseIcons.typography,
            title = stringResource(R.string.settings_font_body_label),
            subtitle = if (currentName != null) {
                currentName
            } else {
                stringResource(R.string.settings_font_body_default)
            },
        )
        SettingsGroupDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_font_import),
                onClick = onImport,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.settings_font_clear),
                onClick = onClear,
                enabled = customFontPath != null,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * E2: Copie los archivos de fuentes seleccionados por SAF al directorio privado de la aplicación filesDir/fonts/custom_body_font.ttf.
 *
 * Nombre de archivo de destino fijo,Sobrescribir directamente la siguiente importación;Retorno del fracaso null(La persona que llama le pregunta al usuario).
 * La replicación y la creación de directorios son ambas en disco. IO,Ejecute en el programador IO para evitar bloquear el hilo principal.
 */
private suspend fun importFontFile(context: android.content.Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val target = File(dir, "custom_body_font.ttf")
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext null
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            target.absolutePath
        } catch (e: java.io.IOException) {
            Logger.w("ThemeSection", "Error al copiar el archivo de fuente: ${e.message}")
            null
        } catch (e: SecurityException) {
            Logger.w("ThemeSection", "Lectura del archivo de fuente rechazada: ${e.message}")
            null
        }
    }
}
