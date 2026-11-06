package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * P2-2: Hoja de diferencias del modelo upstream.
 *
 * Después de que ProviderEditPage extrae el modelo ascendente,Compare la lista de modelos locales con la lista devuelta por upstream,
 * Visualización transparente de diferencias en tres categorías.:
 *  - aguas arriba nuevo(No disponible localmente):Se puede marcar para agregar
 *  - Eliminado(Disponible localmente pero no en sentido ascendente):Marcar como "Eliminado(Reservar)",Se puede comprobar para eliminar
 *  - ser consistente:Mostrar solo el título de la cantidad,No se enumeran artículos específicos(Requisitos de la misión"consistente:no mostrar")
 *
 * Hay botones por lotes para "Agregar todo lo nuevo" y "Eliminar todo lo eliminado" en la parte superior.(usar Surface + clickable,
 * no Material3 Button);"Aplicar cambios seleccionados" en la parte inferior envía los resultados de la verificación de una sola vez.
 *
 * estilo visual:warm-paper,Usar uniformemente [MuseShapes] / [MusePaddings] /
 * [MuseIconSizes] Espere el token de diseño;No utilice el botón / AlertDialog predeterminado de Material3.
 *
 * Prensa de comparación de modelos [Model.id] campo(ignorar caso).
 *
 * @param localModels Lista de modelos guardados localmente por el proveedor actual
 * @param upstreamModels Lista de modelos devueltos por extracción ascendente
 * @param onAdd El usuario verifica el nuevo modelo que se agregará.,Devolución de llamada cuando se hace clic en "Aplicar"
 * @param onRemove El usuario comprueba el modelo eliminado para eliminarlo,Devolución de llamada cuando se hace clic en "Aplicar"
 * @param onDismiss Cerrar devolución de llamada de hoja(Se activará después de hacer clic en la máscara/tecla de retorno/finalización de la aplicación.)
 */
@Composable
fun ModelDiffSheet(
    localModels: List<Model>,
    upstreamModels: List<Model>,
    onAdd: (List<Model>) -> Unit,
    onRemove: (List<Model>) -> Unit,
    onDismiss: () -> Unit,
) {
    // prensa id(ignorar caso)establece la diferencia
    val newModels = remember(localModels, upstreamModels) {
        val localLowerIds = localModels.map { it.id.lowercase() }.toHashSet()
        // Si hay varios elementos de identificación en el sentido ascendente, solo las mayúsculas y las minúsculas son diferentes.,mantener la primera aparición(Elimine duplicados para evitar la duplicación visual)
        val seen = HashSet<String>()
        upstreamModels.filter {
            val key = it.id.lowercase()
            key !in localLowerIds && seen.add(key)
        }
    }
    val removedModels = remember(localModels, upstreamModels) {
        val upstreamLowerIds = upstreamModels.map { it.id.lowercase() }.toHashSet()
        val seen = HashSet<String>()
        localModels.filter {
            val key = it.id.lowercase()
            key !in upstreamLowerIds && seen.add(key)
        }
    }
    val unchangedCount = remember(localModels, upstreamModels) {
        val localLowerIds = localModels.map { it.id.lowercase() }.toHashSet()
        val upstreamLowerIds = upstreamModels.map { it.id.lowercase() }.toHashSet()
        localLowerIds.intersect(upstreamLowerIds).size
    }

    // Estado comprobado:addSelected marca"Se agregarán nuevos modelos",removeSelected marca"Modelos eliminados que se eliminarán"
    val addSelected = remember { mutableStateMapOf<String, Boolean>() }
    val removeSelected = remember { mutableStateMapOf<String, Boolean>() }

    // control inicial:Todos los elementos nuevos y todos los elementos eliminados están preseleccionados de forma predeterminada("Aceptar todos los cambios de forma predeterminada")
    // Inicialización única con LaunchedEffect,Evite anular la operación de cancelación del usuario con cada reorganización.
    LaunchedEffect(newModels, removedModels) {
        if (addSelected.isEmpty()) newModels.forEach { addSelected[it.id] = true }
        if (removeSelected.isEmpty()) removedModels.forEach { removeSelected[it.id] = true }
    }

    val applyEnabled by remember {
        derivedStateOf {
            addSelected.values.any { it } || removeSelected.values.any { it }
        }
    }

    MuseBottomSheet(onDismissRequest = onDismiss) {
        // Nota:MuseBottomSheet El área de contenido en sí es un contenedor de desplazamiento vertical,
        // Úsalo dentro fillMaxHeight(proporción) Obtendrá restricciones ilimitadas, lo que es proporcionalmente muy poco confiable.
        // Aquí usamos una altura fija para el LazyColumn interior. weight(1f) Una referencia definitiva,
        // De lo contrario, las dos capas de peso serán humildes entre sí y la lista colapsará a 0 (como se muestra como“Sólo botones sin lista de modelos.”).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            // área de título
            Text(
                text = stringResource(R.string.model_diff_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(MusePaddings.contentGap))

            // Fila superior de operaciones por lotes: "Agregar todo nuevo"+"Eliminar todo lo eliminado"
            // restricciones de tarea:usar Surface + clickable,No hay necesidad Material3 Button
            // v1.0.79: Semántica de cambio de botón — Haga clic en Borrar cuando todos estén seleccionados,Haga clic en Seleccionar todo si no todos están seleccionados(Un clic para revertir la selección)
            val allNewSelected = newModels.isNotEmpty() && newModels.all { addSelected[it.id] == true }
            val allRemovedSelected = removedModels.isNotEmpty() && removedModels.all { removeSelected[it.id] == true }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                DiffActionButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(
                        if (allNewSelected) R.string.model_diff_clear_add else R.string.model_diff_add_all,
                    ),
                    icon = if (allNewSelected) MuseIcons.minus else MuseIcons.plus,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    enabled = newModels.isNotEmpty(),
                    onClick = {
                        newModels.forEach { addSelected[it.id] = !allNewSelected }
                    },
                )
                DiffActionButton(
                    modifier = Modifier.weight(1f),
                    label = stringResource(
                        if (allRemovedSelected) R.string.model_diff_clear_remove else R.string.model_diff_remove_all,
                    ),
                    icon = if (allRemovedSelected) MuseIcons.plus else MuseIcons.minus,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    enabled = removedModels.isNotEmpty(),
                    onClick = {
                        removedModels.forEach { removeSelected[it.id] = !allRemovedSelected }
                    },
                )
            }
            Spacer(Modifier.size(MusePaddings.contentGap))

            // Área de lista de temas:usar weight(1f) Ocupar el espacio restante,Evitar que el contenido empuje el botón inferior fuera del área visible
            // v1.0.28 Reparación: Solía arreglar antes heightIn(max=340dp),en alta resolución/Todavía es posible en teléfonos de pantalla pequeña
            // Parte inferior truncada porque la altura total del contenido excede el límite del 85% de MuseBottomSheet"Aplicar cambios seleccionados"botón.
            // v1.0.29 Reparación: fill=false Cambiar a predeterminado fill=true,Asegúrate de que en MuseBottomSheet
            // heightIn(max=0.85*screenHeight) bajo restricciones,LazyColumn Ocupa siempre solo el espacio restante,
            // abajo"Aplicar cambios seleccionados"Los botones no saldrán del área visible.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = MusePaddings.contentGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // Grupo 1: Nueva incorporación upstream
                item {
                    DiffSectionHeader(
                        label = stringResource(R.string.model_diff_new, newModels.size),
                        chipText = stringResource(R.string.model_diff_new, newModels.size),
                        chipIcon = MuseIcons.plus,
                        chipColor = MaterialTheme.colorScheme.primaryContainer,
                        chipTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (newModels.isEmpty()) {
                    item { DiffEmptyHint(stringResource(R.string.model_diff_empty_new)) }
                } else {
                    items(newModels, key = { "new-${it.id}" }) { model ->
                        DiffModelRow(
                            model = model,
                            selected = addSelected[model.id] == true,
                            onToggle = {
                                addSelected[model.id] = !(addSelected[model.id] == true)
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                }

                // Grupo 2: Eliminado(reserva local,Sin aguas arriba)
                item {
                    DiffSectionHeader(
                        label = stringResource(R.string.model_diff_removed, removedModels.size),
                        chipText = stringResource(R.string.model_diff_removed, removedModels.size),
                        chipIcon = MuseIcons.trash,
                        chipColor = MaterialTheme.colorScheme.errorContainer,
                        chipTextColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (removedModels.isEmpty()) {
                    item { DiffEmptyHint(stringResource(R.string.model_diff_empty_removed)) }
                } else {
                    items(removedModels, key = { "rem-${it.id}" }) { model ->
                        DiffModelRow(
                            model = model,
                            selected = removeSelected[model.id] == true,
                            badgeText = stringResource(R.string.model_diff_kept_badge),
                            onToggle = {
                                removeSelected[model.id] = !(removeSelected[model.id] == true)
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp,
                        )
                    }
                }

                // Grupo 3: Manténgase constante(Mostrar solo el título de la cantidad,No se enumeran artículos específicos — Requisitos de la misión"consistente:no mostrar")
                item {
                    DiffSectionHeader(
                        label = stringResource(R.string.model_diff_unchanged, unchangedCount),
                        chipText = stringResource(R.string.model_diff_unchanged, unchangedCount),
                        chipIcon = MuseIcons.check,
                        chipColor = MaterialTheme.colorScheme.surfaceVariant,
                        chipTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))

            // Botón "Aplicar cambios seleccionados" en la parte inferior(usar Surface + clickable,no Material3 Button)
            DiffActionButton(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.model_diff_apply),
                icon = MuseIcons.check,
                containerColor = if (applyEnabled) {
                    MaterialTheme.colorScheme.inverseSurface
                } else {
                    MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.12f)
                },
                contentColor = if (applyEnabled) {
                    MaterialTheme.colorScheme.inverseOnSurface
                } else {
                    MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.5f)
                },
                enabled = applyEnabled,
                onClick = {
                    val toAdd = newModels.filter { addSelected[it.id] == true }
                    val toRemove = removedModels.filter { removeSelected[it.id] == true }
                    if (toAdd.isNotEmpty()) onAdd(toAdd)
                    if (toRemove.isNotEmpty()) onRemove(toRemove)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * Botones de cápsula de diferencia en la parte superior/inferior de la hoja(usar Surface + clickable,no Material3 Button).
 *
 * botón de diseño [ProviderEditBottomBar] estilo cápsula:warm-paper inverseSurface Color de funcionamiento principal,
 * 48dp tocar el objetivo,Translúcido y atenuado.
 */
@Composable
private fun DiffActionButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
        modifier = modifier
            .height(MuseIconSizes.touchTarget)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Spacer(Modifier.size(MusePaddings.iconPadding))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
            )
        }
    }
}

/**
 * Título del grupo:lado izquierdo [SectionLabel] Mostrar nombre del grupo(Incluyendo cantidad),lado derecho [FilterChip] Logotipo de clasificación.
 *
 * Requisitos de la misión"para cada grupo SectionLabel + FilterChip":SectionLabel como título visual,
 * FilterChip como logotipo de categoría(No se puede hacer clic,selected=true Fijo),Utilice iconos para mejorar la semántica de clasificación.
 */
@Composable
private fun DiffSectionHeader(label: String, chipText: String, chipIcon: ImageVector, chipColor: Color, chipTextColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        SectionLabel(label)
        MuseChip(
            selected = true,
            onClick = { /* Logotipo de solo lectura,No responde a los clics */ },
            label = chipText,
            leadingIcon = {
                Icon(
                    imageVector = chipIcon,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            },
        )
    }
}

/**
 * Texto de mensaje de grupo vacío.
 */
@Composable
private fun DiffEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(
            horizontal = MusePaddings.screen,
            vertical = MusePaddings.contentGap,
        ),
    )
}

/**
 * Una única línea de diferencia de modelo.
 *
 * Visión:warm-paper — Mira el círculo de la izquierda.(Rellene el estado seleccionado con el color principal + marca de verificación),
 * ID del modelo intermedio + nombre(El subtítulo solo se muestra si es diferente de la identificación.),Logotipo opcional a la derecha(Los elementos eliminados muestran "Eliminados(Reservar)").
 *
 * @param selected ¿Está actualmente comprobado?(usado para"Para ser agregado"o"Para ser eliminado"marca)
 * @param badgeText Texto del logotipo a la derecha(Opcional,Sólo se pasan los elementos eliminados)
 * @param onToggle Se activa cuando se hace clic en una fila o se marca un círculo
 */
@Composable
private fun DiffModelRow(model: Model, selected: Boolean, onToggle: () -> Unit, badgeText: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                horizontal = MusePaddings.screen,
                vertical = MusePaddings.contentGap,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        // Marque el círculo a la izquierda(22dp círculo de marca de verificación)
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = MuseIcons.check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
        }

        // medio:ID del modelo + nombre
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (model.name != model.id) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // Logotipo derecho(Los elementos eliminados muestran "Eliminados(Reservar)")
        if (badgeText != null) {
            Surface(
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.contentGap,
                        vertical = 4.dp,
                    ),
                )
            }
        }
    }
}
