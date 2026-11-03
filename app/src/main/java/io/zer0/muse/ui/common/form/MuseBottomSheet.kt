@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.common.form

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.ui.common.surface.LocalMuseCompactList
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.fakeGlassSurface

private val BottomSheetSurfaceShape = RoundedCornerShape(
    topStart = MuseCornerRadius.SHEET.dp,
    topEnd = MuseCornerRadius.SHEET.dp,
)

/** Calcule la altura máxima de servicio del panel después de incluir el área segura de navegación inferior. */
internal fun calculateBottomSheetHeight(
    maxHeight: androidx.compose.ui.unit.Dp,
    fraction: Float,
    bottomInset: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp = ((maxHeight - bottomInset).coerceAtLeast(0.dp) * fraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp)

/** Se conserva la antigua prueba de regresión de geometría emergente; El panel actual no lee las coordenadas entre ventanas. */
internal fun calculateBottomPopupPosition(windowSize: IntSize, popupContentSize: IntSize, bottomInsetPx: Int, gapPx: Int): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val x = ((windowSize.width - popupContentSize.width) / 2).coerceIn(0, maxX)
    val y = (windowSize.height - bottomInsetPx.coerceAtLeast(0) - gapPx.coerceAtLeast(0) - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(x, y)
}

/**
 * Entrada completa al panel inferior del proyecto.
 *
 * Dialog Mida la zona segura y la posición inferior de manera uniforme para evitar hosts emergentes/Diferencias en sistemas de coordenadas de capas elásticas.
 * La máscara corta únicamente la barra de navegación; el color de fondo del panel se extiende hasta la parte inferior de la pantalla, dejando el área de gestos y el teclado para el contenido.
 * Material3 El scrim de pantalla completa incorporado no puede excluir la barra de navegación por sí sola, por lo que aquí se utiliza un shell controlado.
 */
@Composable
fun MuseBottomSheet(
    onDismissRequest: () -> Unit,
    maxHeightFraction: Float = 0.78f,
    horizontalPadding: androidx.compose.ui.unit.Dp = MusePaddings.screen,
    bottomContentSpacing: androidx.compose.ui.unit.Dp = MusePaddings.contentGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss = { visible.targetState = false }
    LaunchedEffect(visible.isIdle, visible.currentState, visible.targetState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) latestOnDismiss()
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        val density = LocalDensity.current
        val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val glassApply = io.zer0.muse.ui.theme.LocalLiquidGlass.current.enabled
        val glassBase = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(MuseMotion.tween(180)),
                exit = fadeOut(MuseMotion.tween(140)),
            ) {
                Box(
                    Modifier.fillMaxSize().museModalScrim().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = dismiss,
                    ).testTag("muse-sheet-scrim"),
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                val sheetMaxHeight = calculateBottomSheetHeight(maxHeight, maxHeightFraction, bottomInset) + bottomInset
                AnimatedVisibility(
                    visibleState = visible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(animationSpec = MuseMotion.tween(180)) { it },
                    exit = slideOutVertically(animationSpec = MuseMotion.tween(140)) { it },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                        shape = BottomSheetSurfaceShape,
                        color = if (glassApply) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface,
                        tonalElevation = MuseElevation.none,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().then(
                                if (glassApply) Modifier.fakeGlassSurface(BottomSheetSurfaceShape, glassBase) else Modifier,
                            ),
                        ) {
                            var handleDrag by remember { mutableFloatStateOf(0f) }
                            val threshold = with(density) { 32.dp.toPx() }
                            Box(
                                Modifier.pointerInput(threshold) {
                                    detectVerticalDragGestures(
                                        onDragStart = { handleDrag = 0f },
                                        onDragEnd = { if (handleDrag > threshold) dismiss() },
                                        onDragCancel = { handleDrag = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        handleDrag += amount
                                    }
                                },
                            ) { SheetHandle() }
                            CompositionLocalProvider(LocalMuseCompactList provides true) {
                                Column(
                                    Modifier.fillMaxWidth().weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState())
                                        .navigationBarsPadding()
                                        .padding(horizontal = horizontalPadding, vertical = MusePaddings.contentGap),
                                ) {
                                    content()
                                    if (bottomContentSpacing > 0.dp) Spacer(Modifier.height(bottomContentSpacing))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** El asa superior retiene la banda interactiva de 32 dp y permite deslizarla hacia abajo para cerrarla. */
@Composable
fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
        )
    }
}
