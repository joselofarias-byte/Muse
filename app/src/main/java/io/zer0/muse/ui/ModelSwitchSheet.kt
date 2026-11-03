package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelVerification
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseCardPress
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * Etapa 5: El modelo cambia el panel inferior.
 *
 * diseño:
 *  - primera mitad:Provider Filas de virutas horizontales(elección única,Activación de alternar Provider)
 *  - segunda mitad:Lista de modelos actualmente activos en el Proveedor,prensa [groupFor] Expositor plegable agrupado
 *  - El estado seleccionado está decorado con el icono de verificación principal de color verde laurel.(conformarse a"lámpara de mesa nocturna"<5% La ley de hierro del color de marca)
 *  - Apenas usado elevation,Agrupados por superficieBloque de color variante y divisor
 *
 * v1.x: La lista de modelos se transforma en una estructura agrupada y plegada.,
 *  - Para cada grupo de cabezas [MuseCardPress] tarjetas de estilo,Haga clic para alternar para expandir/doblar
 *  - Para estado plegado [rememberSaveable] + Persistencia del protector personalizado(Plegado predeterminado)
 *  - Agregar modelo de filtro del cuadro de búsqueda en la parte superior id / name
 *  - Se muestra cuando varios grupos"Expandir todo / Contraer todo"Botón de operación por lotes
 *
 * llamador:[ChatScreen] TopAppBar de"Cambio de modelo"El botón activa la expansión.
 *
 * @param providers Todo configurado Provider
 * @param activeProviderId Actualmente activo Provider id
 * @param selectedModelId Modelo actualmente seleccionado id(null Representa una alternativa al primer modelo del Proveedor.)
 * @param onPickProvider Activación de alternar Provider(El interior será limpiado selectedModelId)
 * @param onPickModel Seleccionar modelo(Pase nulo para volver al primer modelo del Proveedor)
 * @param onPickDefaultModel Opcional: convierta el primer modelo del proveedor en un enlace explícito; si está vacío, quédese con el anterior“encuadernación clara”Semántica
 * @param onRefreshModels Actualizar manualmente la lista de modelos del proveedor actual(pasar provider id)
 * @param isFetchingModels Si el modelo está siendo retirado(Mostrar estado de carga)
 * @param fetchModelsError Mensaje de error de extracción del modelo
 * @param onDismiss Cerrar panel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSwitchSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    selectedModelId: String?,
    onPickProvider: (String) -> Unit,
    onPickModel: (String?) -> Unit,
    onPickDefaultModel: (() -> Unit)? = null,
    onRefreshModels: (String) -> Unit,
    isFetchingModels: Boolean,
    fetchModelsError: String?,
    onDismiss: () -> Unit,
) {
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    // Modelo actualmente vigente:La selección explícita tiene prioridad,De lo contrario, recurra al primer modelo del Proveedor.
    val effectiveModelId = selectedModelId ?: activeProvider?.models?.firstOrNull()?.id

    // estado del cuadro de búsqueda(rememberSaveable persistencia,Evite girar la pantalla para borrar)
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // El estado de plegado de cada grupo.:Nombre del grupo → Ya sea para doblar(true=doblar,false=Expandir);Los grupos que no están en el mapa están contraídos de forma predeterminada(true)
    // Seleccione SnapshotStateMap principal en lugar de secundario rememberSaveable(Nombre del grupo),es porque"Expandir todo/doblar"botón
    // Necesidad de modificar el estado de todos los grupos a la vez.,La gestión de padres es más directa;Persistir a través de Saver personalizado para Bundle,El estado se conserva después de los cambios de configuración.
    val collapsedMap = rememberSaveable(saver = CollapsedMapSaver) {
        mutableStateMapOf<String, Boolean>()
    }
    val isGroupCollapsed: (String) -> Boolean = { name -> collapsedMap[name] ?: true }

    // MuseDialog sustituto original ModalBottomSheet,Evite los atascos de malla en máquinas reales
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.model_switch_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (providers.isEmpty()) {
                    // Estado vacío:Sin configuración de proveedor
                    Text(
                        text = stringResource(R.string.model_switch_no_provider),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = MusePaddings.largeGap),
                    )
                    return@Column
                }

                // v1.0.28: Provider Cambiar a disposición de columnas verticales,evitar nombres largos(Como por ejemplo OpenCode Go)En fila horizontal
                // El espacio insuficiente hace que el texto se colapse verticalmente,También es conveniente mostrar más Proveedores.
                Text(
                    text = stringResource(R.string.model_switch_provider_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = MusePaddings.tightGap),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.tightGap),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    providers.forEach { provider ->
                        val isSelected = provider.id == activeProviderId
                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = MuseShapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickProvider(provider.id) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MusePaddings.bubbleInner),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // L-MSw1: Provider Escriba identificador de una sola letra(El proyecto no tiene recursos de íconos exclusivos.,Reemplazar con iniciales Emoji)
                                Text(
                                    text = providerEmoji(provider.type),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = provider.displayName.ifBlank { provider.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                // v1.0.18: SiliconFlow proveedor gratuito(No completado key)Se agregó la insignia "Gratis"
                                if (FreeModelConfig.isFreeProvider(
                                        provider.id,
                                        provider.baseUrl,
                                        provider.apiKey,
                                        provider.hiddenFromSettings,
                                    )
                                ) {
                                    FreeBadge()
                                }
                            }
                        }
                    }
                }

                // v1.0.18 / R-SEC-07: Cuando el proveedor actualmente activado es un proveedor gratuito de SiliconFlow,No mostrar mensaje de inicio de sesión;
                // fallback key como marcador de posición/Se cambió a mensaje visible no disponible cuando está vacío.
                if (activeProvider != null &&
                    FreeModelConfig.isFreeProvider(
                        activeProvider.id,
                        activeProvider.baseUrl,
                        activeProvider.apiKey,
                        activeProvider.hiddenFromSettings,
                    )
                ) {
                    val freeHint = if (FreeModelConfig.isFallbackKeyAvailable()) {
                        stringResource(R.string.model_switch_free_provider_hint)
                    } else {
                        stringResource(R.string.free_model_service_unavailable)
                    }
                    Text(
                        text = freeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (FreeModelConfig.isFallbackKeyAvailable()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(vertical = MusePaddings.tightGap),
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = MusePaddings.itemGap),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                // segunda mitad:Lista de modelos para el Proveedor actual
                if (activeProvider == null) {
                    Text(
                        text = stringResource(R.string.model_switch_no_provider_selected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = MusePaddings.screen),
                    )
                    return@Column
                }

                // línea de título:Número de modelos + botón de actualización
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.tightGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.model_switch_models_label, activeProvider.displayName),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Surface(
                        onClick = {
                            if (!isFetchingModels) onRefreshModels(activeProvider.id)
                        },
                        enabled = !isFetchingModels,
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isFetchingModels) {
                                MuseSpinner(
                                    size = 14.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = if (isFetchingModels) {
                                    stringResource(
                                        R.string.model_switch_fetching,
                                    )
                                } else {
                                    stringResource(R.string.model_switch_refresh)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isFetchingModels) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }

                if (activeProvider.models.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.largeGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (isFetchingModels) {
                                stringResource(R.string.model_switch_fetching_upstream)
                            } else {
                                fetchModelsError ?: stringResource(R.string.model_switch_no_models)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (fetchModelsError != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                        // v1.0.4 (P2): Mostrar cerca cuando falla la extracción"Inténtalo de nuevo"botón,Evite que los usuarios tengan que buscar el botón de actualización en la parte superior
                        if (fetchModelsError != null && !isFetchingModels) {
                            MuseCapsuleButton(
                                text = stringResource(R.string.model_switch_retry),
                                onClick = { onRefreshModels(activeProvider.id) },
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                        }
                    }
                    return@Column
                }

                // === Nuevo:cuadro de búsqueda(Filtrar modelos por ID/nombre) ===
                MuseTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.model_switch_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = MuseIcons.search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = MuseIcons.x,
                                contentDescription = stringResource(R.string.common_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(MuseIconSizes.iconMedium)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.contentGap),
                )

                // === Calcular el modelo mostrado ===
                // Cuando la búsqueda está vacía,El primer modelo es como"Predeterminado"Los elementos aparecen individualmente(llamar onPickModel(null) Volver al valor predeterminado del proveedor),
                // Los modelos restantes se muestran colapsados ​​en grupos.;Mostrar todos los modelos filtrados al buscar,Los elementos predeterminados no se muestran individualmente.
                val firstModel = activeProvider.models.first()
                val showDefault = searchQuery.isBlank()
                val displayModels = if (showDefault) {
                    activeProvider.models.drop(1)
                } else {
                    activeProvider.models
                }

                val query = searchQuery.lowercase()
                val filteredModels = if (query.isBlank()) {
                    displayModels
                } else {
                    displayModels.filter {
                        it.id.lowercase().contains(query) ||
                            it.name.lowercase().contains(query)
                    }
                }

                val groups = groupModels(filteredModels.map { it.id })
                val modelById = filteredModels.associateBy { it.id }

                // === Nuevo:Expandir todo / Contraer todo(Solo se muestra cuando hay varios grupos) ===
                if (groups.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.tightGap),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseCapsuleButton(
                            text = stringResource(R.string.model_switch_expand_all),
                            onClick = {
                                groups.forEach { (name, _) -> collapsedMap[name] = false }
                            },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                        Spacer(Modifier.width(MusePaddings.contentGap))
                        MuseCapsuleButton(
                            text = stringResource(R.string.model_switch_collapse_all),
                            onClick = {
                                groups.forEach { (name, _) -> collapsedMap[name] = true }
                            },
                            variant = IosCapsuleButtonVariant.Text,
                            fillWidth = false,
                        )
                    }
                }

                // === Elemento predeterminado(Respaldo al primer modelo del proveedor) ===
                // Mostrar solo si la búsqueda está vacía,Evite la duplicación con el primer modelo de la lista agrupada.
                if (showDefault) {
                    ModelRow(
                        model = firstModel,
                        isSelected = effectiveModelId == null || effectiveModelId == firstModel.id,
                        onClick = {
                            onPickDefaultModel?.invoke() ?: onPickModel(null)
                            onDismiss()
                        },
                        isDefaultFallback = true,
                        hasNativeWebSearch = providerSupportsNativeWebSearch(activeProvider.type),
                    )
                }

                // === Lista colapsada agrupada ===
                // Nota:MuseDialog El área de contenido es Box + verticalScroll,No se puede utilizar internamente LazyColumn
                // (activará"Vertically scrollable component was measured with an infinity maximum
                //  height constraints"error),Por lo tanto, use Column + forEach para lograr el equivalente,Funcionalmente equivalente a LazyColumn.
                if (filteredModels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.model_switch_no_models),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MusePaddings.largeGap),
                    )
                } else {
                    groups.forEach { (groupName, ids) ->
                        GroupHeader(
                            groupName = groupName,
                            modelCount = ids.size,
                            isCollapsed = isGroupCollapsed(groupName),
                            onToggle = {
                                collapsedMap[groupName] = !isGroupCollapsed(groupName)
                            },
                        )
                        if (!isGroupCollapsed(groupName)) {
                            ids.forEach { modelId ->
                                val model = modelById[modelId] ?: return@forEach
                                ModelRow(
                                    model = model,
                                    isSelected = model.id == effectiveModelId,
                                    onClick = {
                                        onPickModel(model.id)
                                        onDismiss()
                                    },
                                    hasNativeWebSearch = providerSupportsNativeWebSearch(activeProvider.type),
                                )
                            }
                        }
                    }
                }
            }
        },
        onConfirm = null,
        dismissText = stringResource(R.string.common_close),
        onDismiss = onDismiss,
    )
}

/**
 * encabezado del paquete — tarjetas en las que se puede hacer clic,Mostrar nombre del grupo + número de modelos + expandir/Flecha de colapso.
 *
 * usar [MuseCardPress] alternativa desnuda clickable,Obtenga un efecto de presión de gradiente de color de 200 ms de facilidad OutCubic
 * y retroalimentación táctil.
 *
 * @param groupName Nombre del grupo(Como por ejemplo "GPT" / "Claude")
 * @param modelCount El número de modelos en este grupo.
 * @param isCollapsed Si actualmente está plegado(true=doblar,Mostrar flecha hacia abajo;false=Expandir,Mostrar flecha hacia arriba)
 * @param onToggle Haga clic para alternar el estado colapsado
 */
@Composable
private fun GroupHeader(groupName: String, modelCount: Int, isCollapsed: Boolean, onToggle: () -> Unit) {
    MuseCardPress(
        onClick = onToggle,
        shape = MuseShapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // LAYOUT-01: El nombre del grupo toma el ancho restante (solo una flecha plegable de ancho fijo a la derecha) y los nombres de grupo largos ya no se ajustan palabra por palabra.
            Text(
                text = stringResource(R.string.model_switch_group_count, groupName, modelCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (isCollapsed) {
                    MuseIcons.chevronDown
                } else {
                    MuseIcons.chevronUp
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * Provider ¿Tiene capacidades de búsqueda nativas?(Ruta de chat con principal ChatViewModel.nativeWebSearchEnabled
 * el juicio sigue siendo consistente:Sólo GEMINI/OPENAI_RESPONSES admite el protocolo de búsqueda nativo del proveedor).
 */
private fun providerSupportsNativeWebSearch(providerType: ProviderType): Boolean =
    providerType == ProviderType.GEMINI || providerType == ProviderType.OPENAI_RESPONSES

/**
 * fila de modelo único — Nombre del modelo + etiqueta de habilidad a la izquierda,lado derecho Check(Estado seleccionado).
 *
 * @param hasNativeWebSearch Si el proveedor actual admite la búsqueda nativa;true incluso si el modelo no está incorporado
 *   SEARCH También se muestran las herramientas."Redes"etiqueta.
 */
@Composable
private fun ModelRow(
    model: Model?,
    isSelected: Boolean,
    onClick: () -> Unit,
    isDefaultFallback: Boolean = false,
    hasNativeWebSearch: Boolean = false,
) {
    if (model == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isDefaultFallback) stringResource(R.string.model_switch_default, model.name) else model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // v1.0.18: SiliconFlow El modelo de lista blanca gratuita añade la insignia "gratuita"(Alineación FreeModelConfig.FREE_MODEL_IDS)
                if (FreeModelConfig.FREE_MODEL_IDS.any { it.equals(model.id, ignoreCase = true) }) {
                    Spacer(Modifier.width(6.dp))
                    FreeBadge()
                }
            }
            // Fila de etiqueta de capacidad:Visión/razonamiento/llamada de herramientas/dibujo/redes
            // stringResource Debe extraerse en la ubicación de llamada directa de @Composable,buildList no @Composable,Por lo tanto, las variables locales se obtienen previamente.
            val visionLabel = stringResource(R.string.model_switch_ability_vision)
            val reasoningLabel = stringResource(R.string.model_switch_ability_reasoning)
            val toolLabel = stringResource(R.string.model_switch_ability_tool)
            val imageLabel = stringResource(R.string.model_switch_ability_image)
            val searchLabel = stringResource(R.string.model_switch_ability_search)
            val abilities = buildList {
                if (model.supportsVisionInput()) add(visionLabel to MuseIcons.eye)
                if (model.supportsReasoning()) add(reasoningLabel to MuseIcons.brain)
                if (model.supportsToolCalling()) add(toolLabel to MuseIcons.wrench)
                // v0.47: Habilidades de dibujo suplementarias.(El modelo admite salida de imágenes.)
                if (model.supportsImageOutput()) add(imageLabel to MuseIcons.image)
                // v0.47: Complementar las capacidades de búsqueda en Internet(model.tools Contiene BÚSQUEDA o Proveedor para admitir la búsqueda nativa)
                // v2.x: Icono de languages(semántica de traducción)Corregido a globe
                if (model.tools.contains(BuiltInTool.SEARCH) || hasNativeWebSearch) add(searchLabel to MuseIcons.globe)
            }
            if (abilities.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    abilities.forEach { (label, icon) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            // v0.47/v1.0.87: Muestra la ventana de contexto y el límite de salida en el catálogo de capacidades del modelo.
            // Estos son límites de metadatos del modelo y no significan necesariamente que a cada solicitud se le asignará el mismo presupuesto.
            val ctxWindow = model.contextWindow
            val outputLimit = model.maxOutputTokens
            val hasContextLimit = ctxWindow?.let { it > 0 } == true
            val hasOutputLimit = outputLimit?.let { it > 0 } == true
            if (hasContextLimit || hasOutputLimit) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (ctxWindow != null && ctxWindow > 0) {
                        Text(
                            text = stringResource(R.string.model_switch_context_limit, formatTokenCount(ctxWindow)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (outputLimit != null && outputLimit > 0) {
                        Text(
                            text = stringResource(R.string.model_switch_output_limit, formatTokenCount(outputLimit)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            when (model.verification) {
                ModelVerification.UNVERIFIED -> Text(
                    text = stringResource(R.string.model_switch_metadata_unverified),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
                ModelVerification.SUSPICIOUS -> Text(
                    text = stringResource(R.string.model_switch_metadata_suspicious),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
                ModelVerification.VERIFIED -> Unit
            }
        }
        // Estado seleccionado:primary verde laurel Check
        if (isSelected) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = stringResource(R.string.model_switch_selected_cd),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Comprima el número de token en texto K/M que sea legible en la página de configuración. */
private fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

/**
 * Devolver ID de Emoji por tipo de proveedor(El proyecto no tiene recursos de íconos exclusivos.,Utilice el mismo esquema de Emoji que AssistantSection).
 */
private fun providerEmoji(type: ProviderType): String = when (type) {
    ProviderType.OPENAI -> "O"
    ProviderType.ANTHROPIC -> "A"
    ProviderType.GEMINI -> "G"
    ProviderType.OPENAI_RESPONSES -> "R"
}

/**
 * v1.0.18: Insignia "gratis" — Modelos gratuitos incluidos en la lista blanca para etiquetar SiliconFlow/proveedor.
 *
 * diseño:cápsulas pequeñas en forma de pastilla,primary Fondo transparente + texto principal,
 * con etiquetas de habilidad(Visión/razonamiento/Herramientas)La jerarquía visual es la misma pero los colores son más prominentes.(Color de la marca).
 */
@Composable
private fun FreeBadge() {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Text(
            text = stringResource(R.string.model_switch_free_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * Colapso estado Mapa de [listSaver] — voluntad [SnapshotStateMap] serializar a plano List,
 * Para cumplir con los requisitos de serialización del paquete(guardar directamente Map<String, Boolean> No compatible con el paquete).
 *
 * formato de serialización:[key1, value1, key2, value2, ...](Los tipos básicos String + Boolean se pueden serializar mediante Bundle).
 */
private val CollapsedMapSaver = listSaver<SnapshotStateMap<String, Boolean>, Any>(
    save = { stateMap ->
        // aplanado a [k1, v1, k2, v2, ...] lista,Todos los elementos son tipos básicos serializables en paquetes.
        buildList {
            stateMap.forEach { (k, v) ->
                add(k)
                add(v)
            }
        }
    },
    restore = { list ->
        mutableStateMapOf<String, Boolean>().apply {
            var i = 0
            while (i + 1 < list.size) {
                @Suppress("UNCHECKED_CAST")
                put(list[i] as String, list[i + 1] as Boolean)
                i += 2
            }
        }
    },
)
