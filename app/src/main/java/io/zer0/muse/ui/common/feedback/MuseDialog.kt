@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod")

package io.zer0.muse.ui.common.feedback

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseDialogSizes
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

internal const val MUSE_DIALOG_SCRIM_TAG = "muse-dialog-scrim"
internal const val MUSE_DIALOG_CARD_TAG = "muse-dialog-card"

/**
 * confirmación compacta/Ventana emergente de formato corto. La barra del sistema y el teclado pertenecen al shell y no están incluidos en la altura de la tarjeta.
 *
 * Las tarjetas están ligeramente más bajas que el centro y las operaciones cortas están dispuestas una al lado de la otra; aumente el tamaño de la fuente y vuelva a la disposición vertical. Botón visual 40dp, área táctil al menos 48dp.
 * Para formularios largos o listas de exploración, vaya a MuseBottomSheet / MuseSelectionSheet.
 */
@Composable
fun MuseDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
    confirmText: String = stringResource(R.string.common_confirm),
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissText: String? = stringResource(R.string.common_cancel),
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    properties: DialogProperties = DialogProperties(
        dismissOnClickOutside = true,
        dismissOnBackPress = true,
        decorFitsSystemWindows = false,
        usePlatformDefaultWidth = false,
    ),
) {
    val stackActions = LocalDensity.current.fontScale > 1.3f
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(
            modifier = Modifier.fillMaxSize().museModalScrim()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = properties.dismissOnClickOutside,
                    onClick = onDismissRequest,
                ).testTag(MUSE_DIALOG_SCRIM_TAG),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()
                    .padding(horizontal = MuseDialogSizes.edgePadding),
                contentAlignment = BiasAlignment(0f, 0.1f),
            ) {
                // La altura del área de contenido se basa en el 78% de la altura visual y el límite superior fijo, el que sea menor: ventanas emergentes con muchos campos
                // Darle solo la mitad de la pantalla hará que la mitad inferior de los campos sea completamente invisible (no solo se desplazará hasta el final).
                val contentHeight = minOf(MuseDialogSizes.contentMaxHeight, maxHeight * 0.78f)
                Surface(
                    shape = MuseShapes.huge,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = MuseElevation.none,
                    shadowElevation = MuseElevation.modal,
                    modifier = Modifier.widthIn(max = MuseDialogSizes.maxWidth)
                        .heightIn(max = maxHeight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ).testTag(MUSE_DIALOG_CARD_TAG),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(MuseDialogSizes.contentPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(MusePaddings.auxGap))
                        }
                        Box(
                            // El título fijo y las operaciones se miden primero, el texto solo ocupa el espacio restante, evitando el teclado./Presione el botón para sacarlo de la tarjeta cuando la pantalla esté horizontal.
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                                .heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center,
                        ) { Column(horizontalAlignment = Alignment.CenterHorizontally) { content() } }
                        if (onConfirm != null || dismissText != null) {
                            Spacer(Modifier.height(MusePaddings.screen))
                            val actions: @Composable (Modifier) -> Unit = { buttonModifier ->
                                if (dismissText != null) {
                                    MuseDialogButton(
                                        text = dismissText,
                                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = buttonModifier,
                                        onClick = { onDismiss?.invoke() ?: onDismissRequest() },
                                    )
                                }
                                if (onConfirm != null) {
                                    MuseDialogButton(
                                        text = confirmText,
                                        backgroundColor = if (destructive) MuseActionColors.dangerContainer else MuseActionColors.container,
                                        contentColor = if (destructive) MuseActionColors.dangerContent else MuseActionColors.content,
                                        enabled = confirmEnabled,
                                        modifier = buttonModifier,
                                        onClick = onConfirm,
                                    )
                                }
                            }
                            if (stackActions) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) { actions(Modifier.fillMaxWidth()) }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                                ) { actions(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Las dimensiones visuales y táctiles de la cápsula están separadas y el texto largo se puede aumentar en altura sin abreviar ni recortar. */
@Composable
private fun MuseDialogButton(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "dialogBtnScale",
    )
    Box(
        modifier = modifier.heightIn(min = MuseIconSizes.touchTarget).clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            enabled = enabled,
            onClick = onClick,
        ).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = MuseDialogSizes.buttonVisualHeight)
                .background(if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.4f), MuseShapes.huge)
                .padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.contentGap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Sólo el título, mensaje y una ventana emergente para confirmar la acción. */
@Composable
fun MuseAlertDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    message: String,
    confirmText: String = stringResource(R.string.common_got_it),
    onConfirm: () -> Unit = {},
) {
    MuseDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        confirmText = confirmText,
        onConfirm = {
            onConfirm()
            onDismissRequest()
        },
        dismissText = null,
    )
}
