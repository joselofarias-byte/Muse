package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.museBottomBarInsets
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

/**
 * v1.38: Selector de proveedores preestablecidos — página de pantalla completa(sustituto original ModalBottomSheet,Cura atascada después del cierre bug).
 *
 * diseño:
 *  - v1.0.6: 43 proveedores preestablecidos(Funcionario extranjero 15 + funcionario nacional 22 + estación de transferencia 6),
 *    8 de ellos son exclusivos de 1muse(deepinfra / lingyi /
 *    github-copilot / 6 plantilla de estación de transferencia). Cubre los principales fabricantes de modelos.
 *  - Navegación de tres secciones en la parte superior.(Retorno a la izquierda + título del medio + espacio a la derecha),con [SettingsSubPageScaffold] ser consistente
 *  - Filtrado en tiempo real de la barra de búsqueda displayName / baseUrl / id
 *  - tarjeta:surface fondo + contorno de 1dp. Borde fino variante + esquinas redondeadas grandes de 20dp.(Ninguno elevation)
 *  - avatar:colorScheme.primary/secondary/tertiary gradiente de baja saturación derivado
 *  - Título del grupo:small label + divisor horizontal(restringir,Sin logotipo de cantidad)
 *  - Fijado al final"proveedor personalizado"entrada
 *
 * llamador: [SettingsModelPage](punto"agregar Provider"Ingrese a la página de pantalla completa cuando,Volver a la página de edición después de la selección).
 *
 * historia alternativa:
 *  - v1.4 ~ v1.36:uso ModalBottomSheet,Las máscaras de malla no se pueden quitar correctamente en algunos dispositivos reales,Página atascada después de cerrar
 *  - v1.37:Cortar temporalmente esta función,Vaya directamente a la página de edición en blanco
 *  - v1.38:Utilice pantalla completa en su lugar Scaffold,Inserción del sistema pasada directamente desde la ventana Actividad,Estable y confiable
 */
@Composable
fun PresetProviderPickerDialog(onDismiss: () -> Unit, onPickPreset: (ProviderConfig) -> Unit, onPickCustom: () -> Unit) {
    // Nota:Mantenga el nombre de función original PresetProviderPickerDialog para que sea compatible con los llamantes existentes,Sólo la implementación interna se cambia a pantalla completa.
    PresetProviderPickerPage(
        onDismiss = onDismiss,
        onPickPreset = onPickPreset,
        onPickCustom = onPickCustom,
    )
}

/**
 * Página de pantalla completa de selección de proveedor preestablecida — Implementación real.
 *
 * Barra superior + Barra de búsqueda + LazyColumn.
 */
@Composable
private fun PresetProviderPickerPage(onDismiss: () -> Unit, onPickPreset: (ProviderConfig) -> Unit, onPickCustom: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val presetProviders = koinInject<PresetProviders>()

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_preset_select_provider),
                onBack = onDismiss,
            )
        },
        modifier = Modifier
            .fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .museBottomBarInsets()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // subtítulo(Texto de descripción)
            item {
                Text(
                    text = stringResource(R.string.settings_preset_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // barra de búsqueda(Fijar al principio de la lista,como desplazamiento)
            item {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                )
            }

            // proveedor personalizado — fijar hacia arriba,Al alcance
            item(key = "custom_top") {
                CustomItem(onClick = onPickCustom, highlighted = true)
            }

            // Lista preestablecida(Filtrar por categoría)
            val allPresets = presetProviders.all
            val filtered = if (query.isBlank()) {
                allPresets
            } else {
                val q = query.lowercase().trim()
                allPresets.filter { p ->
                    p.displayName.lowercase().contains(q) ||
                        p.baseUrl.lowercase().contains(q) ||
                        p.id.lowercase().contains(q)
                }
            }

            // Grupo oficial en el extranjero
            val overseas = filtered.filter {
                it.category == ProviderCategory.OFFICIAL &&
                    presetProviders.overseas.any { o -> o.id == it.id }
            }
            if (overseas.isNotEmpty()) {
                item(key = "label_overseas") { GroupLabel(stringResource(R.string.settings_preset_group_overseas)) }
                items(items = overseas, key = { it.id }) { preset ->
                    PresetItem(preset = preset, onClick = { onPickPreset(preset) })
                }
            }

            // Grupo oficial nacional
            val domestic = filtered.filter {
                it.category == ProviderCategory.OFFICIAL &&
                    presetProviders.domestic.any { d -> d.id == it.id }
            }
            if (domestic.isNotEmpty()) {
                item(key = "label_domestic") { GroupLabel(stringResource(R.string.settings_preset_group_domestic)) }
                items(items = domestic, key = { it.id }) { preset ->
                    PresetItem(preset = preset, onClick = { onPickPreset(preset) })
                }
            }

            // Agrupación de estaciones de transferencia
            val relay = filtered.filter { it.category == ProviderCategory.RELAY }
            if (relay.isNotEmpty()) {
                item(key = "label_relay") { GroupLabel(stringResource(R.string.settings_preset_group_relay)) }
                items(items = relay, key = { it.id }) { preset ->
                    PresetItem(preset = preset, onClick = { onPickPreset(preset) })
                }
            }

            // No se encontraron resultados
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_preset_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * barra de búsqueda:24dp Esquinas redondeadas de la cápsula + superficieFondo variante + sin bordes + botón transparente.
 */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    MuseTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = stringResource(R.string.settings_preset_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = { onQueryChange("") },
                    contentDescription = stringResource(R.string.settings_preset_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 18.dp,
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { /* Comportamiento predeterminado */ }),
    )
}

/**
 * Etiquetas de grupo:small label + divisor horizontal(estilo sobrio,Sin logotipo de cantidad).
 */
@Composable
private fun GroupLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        // divisor horizontal — outlineVariant linea delgada
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/**
 * Elementos de la lista de proveedores predeterminados:Ladrillo con el logotipo de la marca a la izquierda + información a la derecha(Nombre de dominio, número de modelo, flecha trasera).
 *
 * tarjeta:surface fondo + contorno de 1dp. Borde variante + esquinas redondeadas de 20dp.(Ninguno elevation).
 */
@Composable
private fun PresetItem(preset: ProviderConfig, onClick: () -> Unit) {
    val needBaseUrlText = stringResource(R.string.settings_preset_need_baseurl)
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // lado izquierdo:ladrillo del logotipo de la marca
            ProviderLogo(
                type = preset.type,
                name = preset.displayName,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            // medio:información
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = extractDomain(preset.baseUrl).ifBlank { needBaseUrlText },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // LAYOUT-01: La línea de subtítulos tiene un separador puramente decorativo en el medio y una placa con el número de modelo al final. —— No elija ninguno de ellos ampliamente
                    // (El ancho restante se ha asignado al texto del nombre de dominio a la izquierda. weight(fill = false)),Límite a 1 línea para evitar el ajuste palabra por palabra.
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.settings_preset_model_count, preset.models.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // v2.6.0: OAuth Mensaje predeterminado —— Haga clic para saltar a la autorización, no es necesario completarla Key
                if (preset.oauthConfig != null) {
                    Text(
                        text = stringResource(R.string.settings_preset_oauth_quick_login),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Entrada personalizada:Tarjeta grande independiente;highlighted Utilice el color principal del tema para resaltar la base clara.(entrada superior).
 */
@Composable
private fun CustomItem(onClick: () -> Unit, highlighted: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // + No. ronda
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_preset_custom),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_preset_custom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Extraer el nombre de dominio de baseUrl(Se utiliza para preestablecer la visualización de la tarjeta.);Vacío devuelve una cadena vacía. */
private fun extractDomain(baseUrl: String): String {
    if (baseUrl.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(baseUrl)
        uri.host ?: baseUrl
    }.getOrElse { baseUrl }
}
