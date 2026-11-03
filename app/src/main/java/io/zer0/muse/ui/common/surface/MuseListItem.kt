@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod")

package io.zer0.muse.ui.common.surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MuseListItemSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings

/** menú/El panel de selección utiliza filas compactas y el cuerpo de la página conserva su densidad original. */
internal val LocalMuseCompactList = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Muse UI Kit — Elemento de lista primitivo [MuseListItem].
 *
 * v1.0.26: Alternativa a llamar directamente Material3 [androidx.compose.material3.ListItem],
 * Unifique los estilos de fila de todas las páginas/formularios/listas de configuración,resolver:
 *  - ListItem Color de contenedor predeterminado/La elevación de tono produce bloques de color anidados dentro de la tarjeta
 *  - El relleno/tamaño de icono/tamaño de fuente de cada página no es uniforme
 *  - Material3 ListItem Comportamiento errático del color bajo diferentes alfas
 *
 * Especificaciones(Estilo de configuración del sistema):
 *  - altura minima 60dp(Alinear Material3 ListItem predeterminado,Asegúrate de tocar el objetivo.)
 *  - nivel padding 16dp,verticales padding 12dp
 *  - leading content espaciado correcto 16dp,trailing content espaciado izquierdo 12dp
 *  - headline: titleMedium + onSurface
 *  - supporting: bodySmall + onSurfaceVariant
 *  - fondo transparente(desde la capa exterior [MuseSurface] / [CardGroup] Los contenedores proporcionan un contexto unificado)
 *
 * Uso:
 * ```
 * MuseListItem(
 *     onClick = { ... },
 *     leadingContent = { Icon(MuseIcons.palette, null) },
 *     headlineContent = { Text("Apariencia") },
 *     supportingContent = { Text("Tema, tamaño de fuente, modo oscuro") },
 *     trailingContent = { ChevronRight() },
 * )
 * ```
 *
 * @param onClick Haga clic en devolución de llamada(null Indica que no se puede hacer clic)
 * @param onLongClick Devolución de llamada con pulsación larga(Opcional,Proporcionado al mismo tiempo que onClick combinedClickable)
 * @param modifier modificador
 * @param headlineContent título principal(Requerido)
 * @param supportingContent subtítulo(Opcional,pequeñas letras grises)
 * @param leadingContent Contenido a la izquierda(normalmente un icono,Opcional)
 * @param trailingContent Contenido correcto(flecha/Switch espera,Opcional)
 * @param minHeight altura minima(Predeterminado 60dp)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MuseListItem(
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    minHeight: Dp = if (LocalMuseCompactList.current) MuseListItemSizes.compactMinHeight else MuseListItemSizes.minHeight,
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    // v2.x: Comentarios visuales para compresiones de filas — Ligero brillo del fondo(Sincronizar con hápticos existentes;Originalmente solo tacto y ninguna visión.)
    val pressed by interactionSource.collectIsPressedAsState()
    val rowBgColor by animateColorAsState(
        targetValue = if (pressed && onClick != null) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        } else {
            Color.Transparent
        },
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "listItemPress",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(rowBgColor)
            .padding(
                horizontal = MusePaddings.screen,
                vertical = if (LocalMuseCompactList.current) MusePaddings.contentGap else MusePaddings.inputPadding,
            )
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            MuseHaptics.light(haptic)
                            onClick()
                        },
                        onLongClick = {
                            MuseHaptics.medium(haptic)
                            onLongClick()
                        },
                    )
                    onClick != null -> Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = {
                            MuseHaptics.light(haptic)
                            onClick()
                        },
                    )
                    else -> Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // leading content
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(MusePaddings.screen))
        }

        // headline + supporting(disposición vertical,Ocupar el espacio restante)
        Column(
            modifier = Modifier.weight(1f),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                ProvideTextStyle(
                    (if (LocalMuseCompactList.current) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
                        .copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
                ) {
                    headlineContent()
                }
            }
            if (supportingContent != null) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                        supportingContent()
                    }
                }
            }
        }

        // trailing content
        if (trailingContent != null) {
            Spacer(Modifier.width(MusePaddings.contentGap))
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                trailingContent()
            }
        }
    }
}
