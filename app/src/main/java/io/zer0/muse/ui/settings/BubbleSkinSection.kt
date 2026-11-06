package io.zer0.muse.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.BubbleRole
import io.zer0.muse.ui.theme.BubbleSkin
import io.zer0.muse.ui.theme.BubbleSkinCatalog
import io.zer0.muse.ui.theme.BubbleSkinResolver
import io.zer0.muse.ui.theme.BubbleSkinStore
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.PluginSkinSource
import kotlinx.coroutines.launch

/**
 * Phase 4: Selección de máscara de burbuja y área de vista previa.
 *
 * Reutilizar [CustomThemeSection] de"lista + importar/Exportar + Seleccionar"modo:
 *  - almacenamiento:[BubbleSkinStore](Compartido con AppearanceSettingsStore `muse_settings` DataStore);
 *  - Fuente:Valor predeterminado integrado + importación de usuario/Complemento personalizado + instalado(ui-skin,por [PluginSkinSource] Proporcionado después de una nueva verificación);
 *  - Vista previa:Compartir con la página de chat [BubbleSkinResolver],Cubriendo tres roles: usuario/asistente/asistente de chat grupal,
 *    light/dark Dos modos,Y escale el texto de muestra de acuerdo con el tamaño de fuente actual y el skin fontScale;
 *  - La máscara no está seleccionada o la máscara seleccionada es ilegal/Cuando el complemento está deshabilitado, tanto la vista previa como la representación real vuelven a la versión integrada. default(null piel),
 *    El comportamiento de la página de confirmación es exactamente el mismo que antes de la transformación.
 *
 * @param fontSizeScale Equipo de tamaño de fuente actual("small"/"medium"/"large"/"xlarge"),
 * Se utiliza para realizar cambios en la vista previa según la configuración del tamaño de fuente.
 * @param defaultDark Vista previa del modo inicial,Tome el tono del tema actual.
 */
@Composable
internal fun BubbleSkinSection(fontSizeScale: String, defaultDark: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Phase 4: Fuente de máscara de complemento — El complemento ui-skin se instaló y se volvió a verificar para ingresar a la lista opcional.;
    // Desactivar/Desinstalar/la tienda ya no lo devuelve cuando se revoca la confianza,Tanto la vista previa como la representación real recurren al valor predeterminado integrado.
    val pluginSkinSource: PluginSkinSource = org.koin.compose.koinInject()
    val store = remember(context, pluginSkinSource) {
        BubbleSkinStore(context, pluginSkinSource)
    }
    val customSkins by store.customSkinsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pluginSkins by store.pluginSkinsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedId by store.selectedSkinIdFlow.collectAsStateWithLifecycle(
        initialValue = BubbleSkinCatalog.DEFAULT_SKIN_ID,
    )
    val selectedSkin by store.selectedSkinFlow.collectAsStateWithLifecycle(initialValue = null)
    var previewDark by rememberSaveable { mutableStateOf(defaultDark) }
    var deletingSkin by remember { mutableStateOf<BubbleSkin?>(null) }

    // E1 Mismo estilo:SAF Seleccione el archivo para importar la máscara JSON;analizar/Si la verificación falla, la máscara actual permanecerá sin cambios y aparecerá un mensaje.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onFailure { e ->
            Logger.w("BubbleSkinSection", "Error al leer la importación de piel: ${e.message}")
        }.getOrNull()
        scope.launch {
            val imported = store.importSkin(text)
            if (imported != null) {
                // Cambiar automáticamente a una nueva máscara después de importar,Consistente con el comportamiento de importación de temas personalizados
                store.saveSelectedSkinId(imported.id)
                MuseToast.show(context.getString(R.string.settings_bubble_skin_import_success))
            } else {
                MuseToast.show(context.getString(R.string.settings_bubble_skin_import_failed))
            }
        }
    }

    val shareSkin: (BubbleSkin) -> Unit = { skin ->
        val json = AppJson.encodeToString(BubbleSkin.serializer(), skin)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Muse Bubble Skin: ${skin.name}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.settings_bubble_skin_export)),
        )
    }

    SectionLabel(stringResource(R.string.settings_bubble_skin_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // Máscara predeterminada incorporada:Seleccione para borrar selecciones personalizadas,El renderizado mantiene el aspecto existente
        BubbleSkinRow(
            name = stringResource(R.string.settings_bubble_skin_builtin),
            subtitle = stringResource(R.string.settings_bubble_skin_builtin_desc),
            isSelected = selectedId == BubbleSkinCatalog.DEFAULT_SKIN_ID,
            onClick = { scope.launch { store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID) } },
            onExport = null,
            onDelete = null,
        )
        customSkins.forEach { skin ->
            SettingsGroupDivider()
            BubbleSkinRow(
                name = skin.name,
                subtitle = skin.author.ifBlank {
                    stringResource(R.string.settings_bubble_skin_custom_desc)
                },
                isSelected = skin.id == selectedId,
                onClick = { scope.launch { store.saveSelectedSkinId(skin.id) } },
                onExport = { shareSkin(skin) },
                onDelete = { deletingSkin = skin },
            )
        }
        SettingsGroupDivider()
        // línea de acción de importación(El mismo estilo que la entrada de importación del área temática personalizada.)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                }
                .padding(MusePaddings.cardInner),
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
                text = stringResource(R.string.settings_bubble_skin_import),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ── Phase 4: Máscara declarativa para complementos instalados(pantalla de solo lectura/elegir,No se admiten la exportación ni la eliminación.) ──
    if (pluginSkins.isNotEmpty()) {
        SectionLabel(stringResource(R.string.settings_bubble_skin_plugin_section))
        SettingsGroup(
            modifier = Modifier.padding(top = 8.dp),
        ) {
            pluginSkins.forEachIndexed { index, installed ->
                if (index > 0) SettingsGroupDivider()
                BubbleSkinRow(
                    name = installed.skin.name,
                    subtitle = stringResource(
                        R.string.settings_bubble_skin_plugin_source,
                        installed.pluginName,
                        installed.version,
                    ),
                    isSelected = installed.skin.id == selectedId,
                    onClick = { scope.launch { store.saveSelectedSkinId(installed.skin.id) } },
                    onExport = null,
                    onDelete = null,
                    enabled = installed.enabled,
                    note = if (installed.enabled) {
                        null
                    } else {
                        stringResource(R.string.settings_bubble_skin_plugin_disabled)
                    },
                )
            }
        }
    }

    // La identificación seleccionada existe pero la máscara es ilegal./perdido(Se endurecen las reglas de redacción y verificación de versiones antiguas)→ Un recordatorio claro de que se ha devuelto el valor predeterminado;
    // Razón de la dimensión del complemento dada al acceder a un diseño de complemento instalado pero no disponible,Para evitar que los usuarios piensen erróneamente que su piel está dañada.
    if (selectedId != BubbleSkinCatalog.DEFAULT_SKIN_ID && selectedSkin == null) {
        val pluginEntry = pluginSkins.firstOrNull { it.skin.id == selectedId }
        Text(
            text = stringResource(
                if (pluginEntry != null && !pluginEntry.enabled) {
                    R.string.settings_bubble_skin_plugin_unavailable_fallback
                } else {
                    R.string.settings_bubble_skin_invalid_fallback
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
        )
    }

    BubbleSkinPreviewPanel(
        skin = selectedSkin,
        dark = previewDark,
        fontSizeScale = fontSizeScale,
        onDarkChange = { previewDark = it },
    )

    deletingSkin?.let { skin ->
        MuseDialog(
            onDismissRequest = { deletingSkin = null },
            title = stringResource(R.string.settings_bubble_skin_delete_confirm),
            content = {
                Text(
                    text = stringResource(R.string.settings_bubble_skin_delete_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_bubble_skin_delete),
            onConfirm = {
                scope.launch { store.deleteSkin(skin.id) }
                deletingSkin = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deletingSkin = null },
            destructive = true,
        )
    }
}

/**
 * línea de piel única — nombre + autor/Descripción + Seleccionado + Exportar/borrar.
 *
 * [enabled] = false(Complemento no confirmado/Discapacitado/fracaso de confianza)No se puede hacer clic,Usar juntos [note] Explique el motivo de la indisponibilidad.;
 * En este estado, no se puede seleccionar ninguno.,No cambiará la piel actualmente activa.
 */
@Composable
private fun BubbleSkinRow(
    name: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    enabled: Boolean = true,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = stringResource(R.string.settings_theme_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
        if (onExport != null) {
            // CMP-11: desnudo IconButton → Convergencia con los componentes de Muse MuseTopBarIconButton
            MuseTopBarIconButton(
                icon = MuseIcons.share,
                contentDescription = stringResource(R.string.settings_bubble_skin_export),
                onClick = onExport,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // UI-FIX A: Las operaciones pequeñas en línea de la fila de lista mantienen los íconos desnudos (dos círculos negros dentro de la fila son demasiado ruidosos)
                solid = false,
            )
        }
        if (onDelete != null) {
            MuseTopBarIconButton(
                icon = MuseIcons.trash,
                contentDescription = stringResource(R.string.settings_bubble_skin_delete),
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
                solid = false,
            )
        }
    }
}

/**
 * Panel de vista previa de la piel — Usuario de portada/asistente/asistente de chat grupal × light / dark × Escalado de fuentes.
 *
 * Usa cada burbuja [BubbleSkinResolver.resolve] analizar,con MessageBubble /
 * GroupChatMessageBubble Tome el mismo enlace alternativo:Cuando el aspecto es nulo o la verificación falla, el resultado del análisis está integrado de forma predeterminada.
 */
@Composable
private fun BubbleSkinPreviewPanel(skin: BubbleSkin?, dark: Boolean, fontSizeScale: String, onDarkChange: (Boolean) -> Unit) {
    SectionLabel(stringResource(R.string.settings_bubble_skin_preview))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        MuseCapsuleTab(
            tabs = listOf(
                stringResource(R.string.settings_theme_light),
                stringResource(R.string.settings_theme_dark),
            ),
            selectedIndex = if (dark) 1 else 0,
            onSelect = { onDarkChange(it == 1) },
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        SettingsGroupDivider()
        BubbleSkinPreviewBubble(
            role = BubbleRole.USER,
            roleLabel = stringResource(R.string.settings_bubble_skin_role_user),
            text = stringResource(R.string.settings_theme_preview_hello),
            skin = skin,
            dark = dark,
            fontSizeScale = fontSizeScale,
            alignEnd = true,
        )
        SettingsGroupDivider()
        BubbleSkinPreviewBubble(
            role = BubbleRole.ASSISTANT,
            roleLabel = stringResource(R.string.settings_bubble_skin_role_assistant),
            text = stringResource(R.string.settings_theme_preview_reply),
            skin = skin,
            dark = dark,
            fontSizeScale = fontSizeScale,
            alignEnd = false,
        )
        SettingsGroupDivider()
        BubbleSkinPreviewBubble(
            role = BubbleRole.GROUP_ASSISTANT,
            roleLabel = stringResource(R.string.settings_bubble_skin_role_group_assistant),
            text = stringResource(R.string.settings_theme_preview_reply),
            skin = skin,
            dark = dark,
            fontSizeScale = fontSizeScale,
            alignEnd = false,
        )
        Text(
            text = stringResource(R.string.settings_bubble_skin_preview_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * burbuja de vista previa única — Renderice directamente la salida de las esquinas redondeadas mediante el solucionador/color de fondo/color de primer plano/relleno/ancho máximo/Escalado de fuentes.
 */
@Composable
private fun BubbleSkinPreviewBubble(
    role: BubbleRole,
    roleLabel: String,
    text: String,
    skin: BubbleSkin?,
    dark: Boolean,
    fontSizeScale: String,
    alignEnd: Boolean,
) {
    val resolved = remember(skin, role, dark) {
        BubbleSkinResolver.resolve(skin, role, darkTheme = dark)
    }
    val style = resolved.style
    // Mismo factor de escala que ThemeSection.FontSizePreview,Deje que la vista previa cambie en tiempo real según la configuración del tamaño de fuente;
    // propia de la piel fontScale(0.75~1.5)Superponer encima de esto.
    val fontScale = fontSizeScaleFactor(fontSizeScale) * style.fontScale
    val contentStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = (MaterialTheme.typography.bodyMedium.fontSize.value * fontScale).sp,
        lineHeight = (MaterialTheme.typography.bodyMedium.lineHeight.value * fontScale).sp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                color = Color(style.surfaceArgb),
                shape = RoundedCornerShape(style.radiusDp.dp),
                border = if (style.outlineWidthDp > 0f && style.outlineArgb != 0L) {
                    BorderStroke(style.outlineWidthDp.dp, Color(style.outlineArgb))
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(style.maxWidthFraction.coerceIn(0.35f, 1f)),
            ) {
                Text(
                    text = text,
                    style = contentStyle,
                    color = Color(style.contentArgb),
                    modifier = Modifier.padding(
                        horizontal = style.paddingHorizontalDp.dp,
                        vertical = style.paddingVerticalDp.dp,
                    ),
                )
            }
        }
    }
}

/** Coeficiente de engranaje del tamaño de fuente,Consistente con MuseTypography.scaled. */
private fun fontSizeScaleFactor(scale: String): Float = when (scale) {
    "small" -> 0.85f
    "large" -> 1.15f
    "xlarge" -> 1.3f
    else -> 1f
}
