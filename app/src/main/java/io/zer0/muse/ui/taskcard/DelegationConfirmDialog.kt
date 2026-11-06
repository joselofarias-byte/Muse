package io.zer0.muse.ui.taskcard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.tools.DelegationPauseManager
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.museModalScrim
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseDialogSizes
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.semiLarge

// CMP-08: Los márgenes internos de la ventana emergente, el espaciado entre elementos y el ancho máximo están unificados con tokens de tema.(Mismo origen que MuseDialog),
// Sólo la altura máxima del área de contenido es mayor que la de MuseDialog debido a la vista previa del resultado intermedio.,Mantenga los archivos locales de este archivo.
/** Altura máxima del área de contenido de la ventana emergente(que [MuseDialogSizes.contentMaxHeight] grado superior,Acomodar vistas previas de resultados intermedios). */
private val DialogContentMaxHeight = 460.dp

/** Límite máximo de caracteres para la vista previa de resultados intermedios(Más allá del botón contraer + expandir). */
private const val INTERMEDIATE_PREVIEW_CHARS = 500

/**
 * v1.201: Ventana emergente de confirmación de suspensión de delegación.
 *
 * cuando [DelegationPauseManager.activePauses] Cuando hay solicitudes activas en,Este componente se representa al final de ChatScreen.
 * Los usuarios pueden:
 *  - Aprobado para continuar(APPROVE)
 *  - rechazar(REJECT)
 *  - Cancelar toda la delegación(CANCEL)
 *  - Continuar después de la modificación(MODIFY)— Cambiar al modo de edición,Ingrese las instrucciones modificadas y confirme nuevamente.
 *
 * La X en la esquina superior derecha se trata como CANCELAR.
 *
 * @param pauseRequest Solicitudes de pausa actualmente activas;null No renderices nada
 * @param onSubmit Devolución de llamada de decisión del usuario,llevar [DelegationPauseManager.PauseResponse]
 */
@Composable
fun DelegationConfirmDialog(pauseRequest: DelegationPauseManager.PauseRequest?, onSubmit: (DelegationPauseManager.PauseResponse) -> Unit) {
    if (pauseRequest == null) return

    Dialog(
        onDismissRequest = {
            // Haga clic en la tecla exterior/retorno para cancelar
            onSubmit(DelegationPauseManager.PauseResponse(DelegationPauseManager.PauseDecision.CANCEL))
        },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // v1.0.92: Aprende con MuseDialog — forceFullScreen Deje que la tela cubra completamente la barra de estado.
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .museModalScrim()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onSubmit(DelegationPauseManager.PauseResponse(DelegationPauseManager.PauseDecision.CANCEL))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MuseShapes.huge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = MuseElevation.none,
                shadowElevation = MuseElevation.modal,
                modifier = Modifier
                    .widthIn(max = MuseDialogSizes.maxWidth)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                DelegationConfirmDialogContent(
                    pauseRequest = pauseRequest,
                    onSubmit = onSubmit,
                    onCancel = {
                        onSubmit(DelegationPauseManager.PauseResponse(DelegationPauseManager.PauseDecision.CANCEL))
                    },
                )
            }
        }
    }
}

@Composable
private fun DelegationConfirmDialogContent(
    pauseRequest: DelegationPauseManager.PauseRequest,
    onSubmit: (DelegationPauseManager.PauseResponse) -> Unit,
    onCancel: () -> Unit,
) {
    // MODIFY Estado del modo de edición
    var modifyMode by remember(pauseRequest.requestId) { mutableStateOf(false) }
    var modifiedInput by remember(pauseRequest.requestId) { mutableStateOf("") }
    // Estado de expansión de resultados intermedios
    var intermediateExpanded by remember(pauseRequest.requestId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(MuseDialogSizes.contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // barra de título:Título centrado + botón X derecho
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // marcador de posición a la izquierda,Centrar visualmente el título
            Spacer(Modifier.size(MuseIconSizes.iconMedium))
            Text(
                text = stringResource(R.string.delegation_confirm_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onCancel,
                contentDescription = stringResource(R.string.delegation_option_cancel),
                tint = MaterialTheme.colorScheme.outline,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconMedium,
            )
        }
        Spacer(Modifier.height(MusePaddings.auxGap))

        // área de contenido(desplazable,Evite que el contenido extenso se desborde)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = DialogContentMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.auxGap),
            ) {
                // Título de la tarea
                Text(
                    text = pauseRequest.taskTitle.ifBlank { pauseRequest.taskId },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Descripción de la tarea
                if (pauseRequest.taskDescription.isNotBlank()) {
                    InfoLabelBlock(
                        label = stringResource(R.string.delegation_confirm_task),
                        value = pauseRequest.taskDescription,
                    )
                }

                // objetivo
                InfoLabelBlock(
                    label = stringResource(R.string.delegation_confirm_target),
                    value = "[${pauseRequest.targetType}] ${pauseRequest.targetName}",
                )

                // Motivo de la suspensión
                InfoLabelBlock(
                    label = stringResource(R.string.delegation_confirm_reason),
                    value = pauseRequest.reason,
                )

                // resultado intermedio(plegable)
                pauseRequest.intermediateResult?.let { intermediate ->
                    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                        Text(
                            text = stringResource(R.string.delegation_confirm_intermediate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        val displayText = if (intermediate.length > INTERMEDIATE_PREVIEW_CHARS && !intermediateExpanded) {
                            intermediate.take(INTERMEDIATE_PREVIEW_CHARS) + "..."
                        } else {
                            intermediate
                        }
                        Surface(
                            shape = MuseShapes.semiLarge,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(MusePaddings.inputPadding),
                            )
                        }
                        if (intermediate.length > INTERMEDIATE_PREVIEW_CHARS) {
                            Text(
                                text = stringResource(
                                    if (intermediateExpanded) R.string.taskcard_collapse else R.string.taskcard_expand,
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { intermediateExpanded = !intermediateExpanded },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(MuseDialogSizes.contentPadding))

        // área del botón de opción
        if (modifyMode) {
            // MODIFY modo de edición:Cuadro de entrada de varias líneas + botón de confirmación
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.auxGap)) {
                MuseTextField(
                    value = modifiedInput,
                    onValueChange = { modifiedInput = it },
                    label = { Text(stringResource(R.string.delegation_confirm_modify_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                DelegationDialogButton(
                    text = stringResource(R.string.delegation_option_approve),
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = {
                        val input = modifiedInput.trim()
                        if (input.isNotEmpty()) {
                            onSubmit(
                                DelegationPauseManager.PauseResponse(
                                    decision = DelegationPauseManager.PauseDecision.MODIFY,
                                    modifiedInput = input,
                                ),
                            )
                        }
                    },
                )
                DelegationDialogButton(
                    text = stringResource(R.string.common_cancel),
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { modifyMode = false },
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.auxGap)) {
                pauseRequest.options.forEach { option ->
                    val isDestructive = option.isDestructive
                    val bgColor = when {
                        isDestructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val contentColor = when {
                        isDestructive -> MaterialTheme.colorScheme.onError
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                    DelegationDialogButton(
                        text = option.label,
                        backgroundColor = bgColor,
                        contentColor = contentColor,
                        onClick = {
                            if (option.decision == DelegationPauseManager.PauseDecision.MODIFY) {
                                // Cambiar al modo de edición,No enviar inmediatamente
                                modifyMode = true
                            } else {
                                onSubmit(
                                    DelegationPauseManager.PauseResponse(option.decision),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Bloque de etiquetas de información:label(pequeñas letras grises) + value(onSurface Texto),Alinear a la izquierda.
 */
@Composable
private fun InfoLabelBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Botón de cápsula de ancho completo(Reutilizar el estilo visual del botón MuseDialog:48dp Alto + esquinas redondeadas de 24 dp + cambio de transparencia de prensa).
 */
@Composable
private fun DelegationDialogButton(text: String, backgroundColor: Color, contentColor: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
        label = "delegationBtnScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MuseIconSizes.touchTarget)
            .background(
                color = backgroundColor,
                shape = MuseShapes.huge,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}
