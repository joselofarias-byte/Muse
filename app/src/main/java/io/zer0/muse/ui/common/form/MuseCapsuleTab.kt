package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import kotlin.math.abs

/**
 * Selector de pestañas de cápsula.
 *
 * Página de inicio en borrador de diseño(Tarea/Agent/chat grupal), página de búsqueda(Sessions/Messages/Settings),
 * Detalles del asistente(Basic/Prompt/Extensions/Memory/Advanced)Todos usan este componente.
 *
 * estilo:surfaceVariant contenedor estriado(20dp esquinas redondeadas),Elemento seleccionado blanco elevado(16dp esquinas redondeadas) + sombra.
 *
 * v1.137 B3: realmente deslizarse/Haga clic para separar la animación.
 *  - Arrastre del usuario([isDragging] = true):El color del indicador se interpola continuamente con el dedo(lerp),Seguimiento del desplazamiento del buscapersonas en tiempo real,
 *    Ninguno tween,El indicador sigue dondequiera que se mueva el dedo.
 *  - Haga clic para cambiar([isDragging] = false):Transiciones suaves con interpolación de 200 ms,si"bomba"textura.
 *  - la persona que llama pasa [isDragging] distinguir:HomeScreen Establecer en onSelect clickAnimating=true,
 *    isScrollInProgress Borrar cuando termine,Esto diferencia entre arrastre del usuario y animateScrollToPage.
 *
 * @param tabs Tab Lista de texto de etiqueta
 * @param selectedIndex Índice actualmente seleccionado
 * @param onSelect Seleccionar devolución de llamada(El parámetro es el nuevo índice.)
 * @param modifier Modifier
 * @param pageOffset pager Puntuación de compensación(Seguimiento continuo de deslizamientos de dedos);null Recurrir a la animación de interpolación(Predeterminado)
 * @param isDragging Si el usuario está arrastrando pager(Utilice el seguimiento continuo solo al arrastrar;haga clic en animación/Se utiliza cuando está parado tween)
 */
@Composable
fun MuseCapsuleTab(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageOffset: Float? = null,
    isDragging: Boolean = false,
) {
    val useContinuous = pageOffset != null && isDragging
    val fractionalIndex = pageOffset?.let { selectedIndex + it } ?: selectedIndex.toFloat()

    // UI-FIX A: El estado seleccionado es sólido (texto blanco sobre fondo negro); el estado no seleccionado es opaco y neutral.
    // No más desvanecimientos con 0 alfa para evitar presionar/Al deslizarse aparece una sensación de enmascaramiento translúcido.
    val selectedBg = MuseActionColors.container
    val unselectedBg = MuseActionColors.neutralContainer
    val selectedText = MuseActionColors.content
    val unselectedText = MuseActionColors.mutedContent

    Surface(
        shape = MuseShapes.extraLarge,
        color = MuseActionColors.neutralContainer,
        modifier = modifier
            .widthIn(min = (tabs.size * 60).dp)
            // UI-FIX: 48dp La altura visual aparece demasiado alta en la barra superior del celular(Comentarios de los usuarios"Botones demasiado altos");
            // Reciba una altura visual de 40 dp,El área de toque dentro de la fila todavía está cubierta por el área de golpe más grande de la fila exterior.
            .heightIn(min = 40.dp, max = 44.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(MusePaddings.tinyGap),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = selectedIndex == index
                // B3: Calcule directamente en función del índice fraccionario al arrastrar"Selectividad"(0~1),Sin demora;
                // Haga clic/Utilice animateFloatAsState para suavizar las transiciones cuando esté parado,Tiene una textura de interpolación.
                val directFraction = (1f - abs(fractionalIndex - index)).coerceIn(0f, 1f)
                val animatedFraction by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
                    label = "capsule_tab_fraction",
                )
                val fraction = if (useContinuous) directFraction else animatedFraction

                val bgColor = lerp(unselectedBg, selectedBg, fraction)
                val textColor = lerp(unselectedText, selectedText, fraction)
                // v1.0.75 fix (CI hardcoded-cjk): Recursos de redacción seleccionados(Composable valor de contexto,semantics lambda Multiplexación interna)
                val selectedSuffix = stringResource(R.string.settings_selected_suffix)
                val interactionSource = remember(index) { MutableInteractionSource() }

                Surface(
                    shape = MuseShapes.semiLarge,
                    color = bgColor,
                    shadowElevation = if (isSelected) MuseElevation.low else MuseElevation.none,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // v1.0.74 fix (Auditoría inicial 3.7): Utilice semántica seleccionada en lugar de ortografía contentDescription,
                        // TalkBack puede leer"seleccionado"relaciones en lugar de concatenación de cadenas.
                        .semantics {
                            selected = isSelected
                            // v1.0.75 fix (CI hardcoded-cjk): Recursos de redacción seleccionados(Composable valor externo)
                            contentDescription = if (selectedIndex == index) "$label$selectedSuffix" else label
                        }
                        // La cápsula ya tiene un estado seleccionado de autodibujo y no se puede superponer con la ondulación predeterminada del Material;
                        // De forma predeterminada, las ondas delimitadas aparecen como barras horizontales blancas debajo de una máscara oscura.
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = textColor,
                            // CHAT-17: etiqueta larga(Rusia/portugués)línea única omitida,No más saltos de línea/Truncar la altura de la cápsula destruida.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
