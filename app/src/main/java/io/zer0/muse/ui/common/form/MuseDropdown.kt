package io.zer0.muse.ui.common.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.51: selector desplegable — sustituto Material3 [androidx.compose.material3.ExposedDropdownMenuBox].
 *
 * diferencias visuales:
 *  - Haga clic en OutlinedTextField para que aparezca MuseDialog(Lista de esquinas redondeadas)En lugar del menú Bloque de material
 *  - Lista de opciones de esquinas redondeadas con Surface + MuseShapes.large
 *  - Los elementos seleccionados se resaltan con color primario.
 *
 * Uso:
 * ```
 * MuseDropdown(
 *     value = mode,
 *     onValueChange = { mode = it },
 *     label = "modo",
 *     options = PromptInjectionRepository.PRESET_MODES.entries.map { (id, label) -> id to "$label ($id)" },
 * )
 * ```
 *
 * @param value Valor seleccionado actualmente(Corresponde a las opciones en first)
 * @param onValueChange Seleccionar devolución de llamada(Opciones de devolución en first)
 * @param label Etiqueta del cuadro de entrada
 * @param options Lista de opciones,List<Pair<value, displayText>>
 * @param modifier Modifier
 */
@Composable
fun MuseDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    // L-DD3: options usar Pair<value, displayText> oso,falta de nombre,Mala legibilidad.
    // Las sugerencias de refactorización futuras deben cambiarse a data class DropdownOption(val value: String, val displayText: String),
    // El par se mantiene actualmente para compatibilidad con personas que llaman existentes(PromptInjectionRepository.PRESET_MODES espera).
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    // M-DD1: expanded Utilice RememberSaveable para persistir,Cambios de configuración(rotar/alternar oscuro)sin perder el estado expandido.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedDisplay = options.firstOrNull { it.first == value }?.second ?: value

    // L-DD5: Utilice InteractionSource propio de TextField para escuchar prensas,Evite la colcha en la que se puede hacer clic en la caja principal
    // OutlinedTextField centrarse/Intercepción del procesamiento de entrada,Como resultado, no hay respuesta al hacer clic en el cuadro desplegable.
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                expanded = true
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        // v1.0.74 fix (Auditoría inicial 3.6): Quitar cajas role/contentDescription,
        // Evite la duplicación con los nodos semánticos del campo de texto interno readOnly(TalkBack leer dos enfoques).
        // La interacción todavía se maneja mediante clics.,La semántica se deja en manos de TextField.
    ) {
        OutlinedTextField(
            value = selectedDisplay,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = MuseIcons.chevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.medium,
            singleLine = true,
            interactionSource = interactionSource,
        )

        if (expanded) {
            MuseSelectionSheet(
                onDismissRequest = { expanded = false },
                title = label,
                maxHeightFraction = 0.72f,
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // v2.0.1 fix(La máquina real falla): heightIn debe estar en verticalScroll **antes** —
                            // Cuando se invierte el orden, el nodo de desplazamiento todavía tiene la restricción de altura infinita (OPPO borde a borde), colapsado directamente.
                            .heightIn(max = DropdownListMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
                    ) {
                        options.forEach { (optionValue, optionLabel) ->
                            val isSelected = optionValue == value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(optionValue)
                                        expanded = false
                                    }
                                    // L-DD4: desnudo 12/8dp → MusePaddings Simbólico.
                                    .padding(
                                        vertical = MusePaddings.inputPadding,
                                        horizontal = MusePaddings.iconPadding,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = optionLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                    // LAYOUT-01: Componente compartido ancho de texto en línea + límite de línea (reserva hasta 2 líneas,
                                    // proveedor/No trunque agresivamente el nombre del modelo cuando sea largo), evite el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isSelected) {
                                    Spacer(Modifier.width(MusePaddings.iconPadding))
                                    Icon(
                                        imageVector = MuseIcons.check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        // L-DD4: 20.dp → MuseIconSizes.iconMedium Simbólico.
                                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** L-DD4: Altura máxima de la lista de opciones desplegable,Evite abrumar la pantalla con demasiadas opciones. */
private val DropdownListMaxHeight = 360.dp
