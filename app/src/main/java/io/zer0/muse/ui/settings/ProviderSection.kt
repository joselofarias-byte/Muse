@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.data.ProviderCollisionDetector
import io.zer0.muse.data.catalog.ModelCatalogRepository
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch

/**
 * v1.133: Estado de verificación del estado del lote del proveedor(encabezado de lista"Todas las pruebas"disparador de botón).
 *
 * - [Idle]: No probado(Predeterminado),No mostrar estado chip
 * - [Testing]: Pruebas en progreso(mostrar CircularProgressIndicator)
 * - [Success]: Prueba superada(retraso de visualización ms,verde ✓)
 * - [Failed]: Error de detección(Mostrar descripción del error,rojo ✗)
 *
 * con [ModelTestStatus] distinguir:Este estado es para la conectividad del punto final /models de todo el proveedor.,
 * No envíes mensajes de chat reales;Y llevar datos retrasados para clasificarlos/Contraste.
 */
sealed class ProviderTestStatus {
    data object Idle : ProviderTestStatus()
    data object Testing : ProviderTestStatus()
    data class Success(val latencyMs: Long, val modelCount: Int) : ProviderTestStatus()
    data class Failed(val error: String) : ProviderTestStatus()
}

/**
 * Provider lista section — Lista agrupada.
 *
 * usar [SettingsGroup] Empaquetar todo Provider,Una línea por Proveedor.
 * Ícono de marca en el lado izquierdo de la línea, nombre y tipo en el medio, habilitado en el lado derecho/Deshabilitar etiqueta de estado + flecha derecha.
 *
 * v1.133: Nuevo [testStatuses] / [isTestingAll] / [onTestAll] usado para"Todas las pruebas"simultaneidad por lotes
 * chequeo de salud(prensa [ProviderEditPage] La lógica de prueba única testConnection,Promocionar a simultaneidad por lotes).
 */
internal fun LazyListScope.providerListSection(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    onActivate: (String) -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onDelete: (String) -> Unit,
    onAddProvider: () -> Unit = {},
    onScanQr: () -> Unit = {},
    // v1.133: Pruebas de salud por lotes(encabezado de lista"Todas las pruebas"disparador de botón)
    testStatuses: Map<String, ProviderTestStatus> = emptyMap(),
    isTestingAll: Boolean = false,
    onTestAll: () -> Unit = {},
) {
    item {
        ProviderListHeader(
            totalCount = providers.size,
            isTestingAll = isTestingAll,
            onTestAll = onTestAll,
        )
    }

    // Renderizado agrupado por categoría(Oficial / Estación de Transferencia / Personalizado)
    val officialProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.OFFICIAL }
    val relayProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.RELAY }
    val customProviders = providers.filter { it.category == io.zer0.ai.core.ProviderCategory.CUSTOM }

    if (providers.isEmpty()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MuseEmptyState(
                    icon = MuseIcons.atom,
                    title = stringResource(R.string.settings_provider_empty_title),
                    subtitle = stringResource(R.string.settings_provider_empty_subtitle),
                    actionText = stringResource(R.string.settings_provider_empty_action),
                    onAction = onAddProvider,
                )
            }
        }
        return
    }

    // P2-1: Provider detección de conflictos — Mostrar tarjeta de advertencia en la parte superior de la lista(Sólo si hay configuraciones duplicadas)
    item { ModelCatalogRow() }
    item { ProviderCollisionWarning(providers) }

    // "agregar Provider" entrada —— Quédese en la parte superior (comentarios de los usuarios: cuando hay muchos grupos personalizados, debe desplazarse hasta la parte inferior cada vez)
    item {
        SettingsGroup(
            modifier = Modifier.padding(top = MusePaddings.itemGap),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAddProvider() }
                    .padding(horizontal = MusePaddings.screen, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Icon(
                    imageVector = MuseIcons.plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.settings_provider_add),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            // v1.97: Escanea el código QR para importar Provider
            SettingsGroupDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScanQr() }
                    .padding(horizontal = MusePaddings.screen, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                Icon(
                    imageVector = MuseIcons.qrcode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
                Text(
                    text = stringResource(R.string.qr_scan_btn),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    // Agrupación oficial de fabricantes.
    if (officialProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_official), officialProviders.size) }
        item {
            SettingsGroup {
                officialProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        onActivate = { onActivate(config.id) },
                        onDelete = { onDelete(config.id) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // Agrupación de estaciones de transferencia
    if (relayProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_relay), relayProviders.size) }
        item {
            SettingsGroup {
                relayProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        onActivate = { onActivate(config.id) },
                        onDelete = { onDelete(config.id) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }

    // Agrupación personalizada
    if (customProviders.isNotEmpty()) {
        item { ProviderCategoryHeader(stringResource(R.string.settings_provider_category_custom), customProviders.size) }
        item {
            SettingsGroup {
                customProviders.forEachIndexed { index, config ->
                    if (index > 0) SettingsGroupDivider()
                    ProviderRow(
                        config = config,
                        isActive = config.id == activeProviderId,
                        onEdit = { onEdit(config) },
                        onActivate = { onActivate(config.id) },
                        onDelete = { onDelete(config.id) },
                        testStatus = testStatuses[config.id] ?: ProviderTestStatus.Idle,
                    )
                }
            }
        }
    }


}

/**
 * P2-1: Provider conflicto(Configuración duplicada)tarjeta de advertencia.
 *
 * llamar [ProviderCollisionDetector.detect] Detectar configuraciones duplicadas en la lista de proveedores actual,
 * La tarjeta de advertencia solo se muestra cuando se detecta un conflicto. estilo de tarjeta:
 *  - forma:[MuseShapes.medium] esquinas redondeadas
 *  - fondo:MaterialTheme.colorScheme.errorContainer
 *  - icono:MuseIcons.alertTriangle(colorear onErrorContainer)
 *  - redacción:provider_collision_warning(Número de conflictos %1$d)
 *
 * Sólo aviso,No ampliar detalles,No bloquear las operaciones del usuario.
 */
@Composable
private fun ProviderCollisionWarning(providers: List<ProviderConfig>) {
    val collisions = remember(providers) { ProviderCollisionDetector.detect(providers) }
    if (collisions.isEmpty()) return

    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = MuseIcons.alertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = stringResource(R.string.provider_collision_warning, collisions.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ProviderCategoryHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Fila del catálogo de capacidades del modelo —— Mostrar la versión actual del directorio,Y admite actualizaciones manuales.
 *
 * Para fuentes del catálogo, consulte [ModelCatalogRepository]:caché primero,Si la extracción falla, se devolverá el activo integrado.
 */
@Composable
private fun ModelCatalogRow() {
    val repository: ModelCatalogRepository = org.koin.compose.koinInject()
    var status by remember { mutableStateOf(repository.status()) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val localizedResources = androidx.compose.ui.platform.LocalResources.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_model_catalog_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(formatCatalogTimestamp(status.publishedAt))
                    append(" · ")
                    append(
                        localizedResources.getString(
                            R.string.settings_model_catalog_counts,
                            status.providerCount,
                            status.modelCount,
                        ),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        MuseCapsuleButton(
            text = stringResource(
                if (refreshing) {
                    R.string.settings_model_catalog_refreshing
                } else {
                    R.string.settings_model_catalog_refresh
                },
            ),
            onClick = {
                if (!refreshing) {
                    refreshing = true
                    scope.launch {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repository.refresh()
                        }
                        status = repository.status()
                        refreshing = false
                        android.widget.Toast.makeText(context, result.message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
        )
    }
}

@Composable
internal fun ProviderRow(
    config: ProviderConfig,
    isActive: Boolean,
    onEdit: () -> Unit,
    // activar(Establecer como proveedor actual)y borrar —— Anteriormente, proveedorListSection aceptaba estas dos devoluciones de llamada pero nunca las usaba.,
    // Como resultado, la lista de proveedores no se puede activar ni eliminar.
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    // v1.133: Estado de verificación de estado del lote(por cabeza de lista"Todas las pruebas"disparador de botón,Mostrar en línea después de escribir)
    testStatus: ProviderTestStatus = ProviderTestStatus.Idle,
) {
    var actionsOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onEdit() }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        // lado izquierdo:ladrillo del logotipo de la marca(Icono lineal/marca denominativa,cuadrado redondeado)
        ProviderLogo(
            type = config.type,
            name = config.displayName,
            size = 44.dp,
        )

        // medio:nombre + tipo
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.displayName.ifBlank { config.id },
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = providerDisplayTypeName(config.type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = MusePaddings.tightGap),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Text(
                    text = stringResource(
                        if (config.enabled) R.string.settings_provider_status_enabled else R.string.settings_provider_status_disabled,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (config.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
                ProviderTestStatusChip(testStatus)
            }
        }
        // Haga clic en toda la fila para ingresar a la edición, dejando solo una entrada de operación independiente en el lado derecho y el estado ya no desplazará el título.
        Box {
            MuseTactileButton(
                icon = MuseIcons.moreVertical,
                onClick = { actionsOpen = true },
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconSize = MuseIconSizes.iconMedium,
            )
            io.zer0.muse.ui.common.form.MuseAnchoredMenu(
                expanded = actionsOpen,
                onDismissRequest = { actionsOpen = false },
                alignEnd = true,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!isActive) {
                        MuseListItem(
                            onClick = {
                                actionsOpen = false
                                onActivate()
                            },
                            headlineContent = {
                                Text(stringResource(R.string.settings_provider_action_activate))
                            },
                        )
                    }
                    MuseListItem(
                        onClick = {
                            actionsOpen = false
                            onDelete()
                        },
                        headlineContent = {
                            Text(stringResource(R.string.settings_provider_delete_title))
                        },
                    )
                }
            }
        }
    }
}

/**
 * v1.133: Encabezado de lista de proveedores — section Título + "Todas las pruebas"botón.
 *
 * sustituto original [SectionLabel] Texto único. Se muestra en el botón de detección en curso. CircularProgressIndicator,
 * Haga clic en el disparador [onTestAll] Pruebe todos los proveedores configurados simultáneamente.
 *
 * prensa UI:
 * ```
 * Row {
 *     Text("Provider lista")
 *     Spacer(Modifier.weight(1f))
 *     if (testing) { CircularProgressIndicator(...) }
 *     TextButton(onClick = onTestAll) { Text("Todas las pruebas") }
 * }
 * ```
 */
@Composable
private fun ProviderListHeader(totalCount: Int, isTestingAll: Boolean, onTestAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(stringResource(R.string.settings_provider_list_title))
        Spacer(Modifier.weight(1f))
        // v1.133: La detección está en curso y se muestra el círculo.(Tamaño 14dp,Coincide mucho con el texto del botón)
        if (isTestingAll) {
            MuseSpinner(
                size = 14.dp,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        MuseCapsuleButton(
            text = stringResource(R.string.settings_provider_test_all),
            onClick = onTestAll,
            enabled = !isTestingAll && totalCount > 0,
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
        )
    }
}

/**
 * v1.133: Chip de resultados de pruebas de un solo proveedor.
 *
 * - [ProviderTestStatus.Idle]: no renderizar(Mantén el lugar ordenado)
 * - [ProviderTestStatus.Testing]: pequeño círculo + "Bajo detección"
 * - [ProviderTestStatus.Success]: verde ✓ + "{latency} ms"(Retraso de visualización)
 * - [ProviderTestStatus.Failed]: rojo ✗ + "falló"(copia corta,Para más detalles, haga clic en la página de edición para ver)
 */
@Composable
private fun ProviderTestStatusChip(status: ProviderTestStatus) {
    when (status) {
        ProviderTestStatus.Idle -> Unit
        ProviderTestStatus.Testing -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MuseSpinner(
                        size = 10.dp,
                        strokeWidth = 1.5.dp,
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_row_testing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is ProviderTestStatus.Success -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_ok, status.latencyMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        is ProviderTestStatus.Failed -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MuseShapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.x,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_provider_test_result_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

/**
 * Formatee las marcas de tiempo ISO devueltas por el backend como fechas legibles localmente.
 *
 * El upstream proporciona cadenas como 2026-09-30T06:11:52.268Z. La visualización directa permitirá a los usuarios ver una cadena
 * Formato de máquina con T y Z. Cuando falla el análisis, recurra a la cadena original para evitar vaciar toda la línea.
 */
private fun formatCatalogTimestamp(raw: String): String {
    if (raw.isBlank()) return "—"
    return runCatching {
        val instant = java.time.Instant.parse(raw)
        val local = instant.atZone(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .format(local)
    }.getOrDefault(raw)
}
