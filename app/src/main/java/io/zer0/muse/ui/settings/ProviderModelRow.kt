package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * v1.0.8 (7.6): Estado de verificación de salud del modelo individual.
 *
 * - [Idle]: No probado(Predeterminado),No mostrar estado chip
 * - [InProgress]: Prueba en progreso(mostrar CircularProgressIndicator + "Bajo prueba")
 * - [Success]: Prueba superada(mostrar ✓ + "salud",Resumen de respuesta del modelo de transporte opcional)
 * - [Failed]: prueba fallida(mostrar ✗ + "falló",llevar mensaje de error)
 */
sealed class ModelTestStatus {
    data object Idle : ModelTestStatus()
    data object InProgress : ModelTestStatus()
    data class Success(val message: String? = null) : ModelTestStatus()
    data class Failed(val message: String? = null) : ModelTestStatus()
}

/**
 * Provider Componente de fila del modelo de página de detalles.
 *
 * Pequeño icono de proveedor a la izquierda,Nombre del modelo intermedio + etiqueta de capacidad,Botón derecho de prueba + configuración/Botón Agregar.
 *
 * v1.0.8 (7.6): Nuevo [onTest] / [testStatus] Se utiliza para activar y mostrar los resultados de la verificación del estado del modelo.
 * v1.0.8 (7.7): Se agregó visualización de ventana contextual para la etiqueta de capacidad(Como por ejemplo "128K contexto").
 */
@Composable
internal fun ProviderModelRow(
    model: Model,
    providerType: ProviderType,
    providerName: String,
    isAdded: Boolean = true,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    // v1.0.8 (7.6): Devolución de llamada de verificación de estado del modelo,null Indica que el botón de prueba no se muestra(Si el nuevo modelo no se guarda,)
    onTest: (() -> Unit)? = null,
    testStatus: ModelTestStatus = ModelTestStatus.Idle,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Ladrillo con el logotipo de la marca del proveedor a la izquierda(Icono lineal/marca denominativa)
        ProviderLogo(
            type = providerType,
            name = providerName,
            size = 40.dp,
        )

        // medio:Nombre del modelo + etiqueta de habilidad
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name.takeIf { it.isNotBlank() } ?: model.id,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(4.dp))
            ModelAbilityChips(model = model, testStatus = testStatus)
        }

        // v1.0.8 (7.6): botón de prueba(Opcional,solo si onTest != null visualización de la hora)
        // F-12: Prueba de enlace completo del modelo(conversación real healthCheck),Redacción publicitaria y pruebas de endpoints(conexión de prueba)distinguir
        if (onTest != null) {
            val canTest = testStatus !is ModelTestStatus.InProgress
            Surface(
                onClick = onTest,
                enabled = canTest,
                shape = MuseShapes.pill,
                color = if (canTest) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.5f,
                    )
                },
                contentColor = if (canTest) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline.copy(
                        alpha = 0.5f,
                    )
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (testStatus is ModelTestStatus.InProgress) {
                        MuseSpinner(
                            size = 14.dp,
                        )
                    } else {
                        Icon(
                            imageVector = MuseIcons.gauge,
                            contentDescription = stringResource(R.string.settings_model_action_test_chat),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_model_action_test_chat_short),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }

        // Botón de operación derecho
        MuseTactileButton(
            icon = if (isAdded) MuseIcons.sliders else MuseIcons.plus,
            onClick = onAction,
            contentDescription = if (isAdded) {
                stringResource(
                    R.string.settings_model_action_settings,
                )
            } else {
                stringResource(R.string.settings_model_action_add)
            },
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Fila de etiqueta de capacidad del modelo.
 *
 * Mostrar en orden:Herramientas/Inferencia/Transmisión/Dibujo/Multimodalidad/Vídeo/Ventana de contexto,Se muestra de forma predeterminada cuando no hay ninguna habilidad disponible."charlar".
 *
 * v1.0.8 (7.6): Nuevo [testStatus] — Agregue el chip de estado de verificación de salud al final de la línea de capacidad.
 * v1.0.8 (7.7): Agregar ventana de contexto chip(Como por ejemplo "128K contexto"),solo si contextWindow > 0 desplegado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelAbilityChips(model: Model, testStatus: ModelTestStatus = ModelTestStatus.Idle) {
    val labelTool = stringResource(R.string.settings_model_ability_tool)
    val labelReasoning = stringResource(R.string.settings_model_ability_reasoning)
    val labelStreaming = stringResource(R.string.settings_model_ability_streaming)
    val labelImage = stringResource(R.string.settings_model_ability_image)
    val labelMultimodal = stringResource(R.string.settings_model_ability_multimodal)
    val labelVideo = stringResource(R.string.settings_model_ability_video)
    val labelChat = stringResource(R.string.settings_model_ability_chat)
    val labelContextFmt = stringResource(R.string.settings_model_context_label)
    val labelOutputFmt = stringResource(R.string.settings_model_output_label)
    val labelTestInProgress = stringResource(R.string.settings_model_test_in_progress)
    val labelTestSuccess = stringResource(R.string.settings_model_test_success)
    val labelTestFailed = stringResource(R.string.settings_model_test_failed)
    val labelTestSuccessFmt = stringResource(R.string.settings_model_test_success_with_msg)
    val labelTestFailedFmt = stringResource(R.string.settings_model_test_failed_with_msg)

    // v1.0.8 (7.7): texto del chip de ventana contextual(Formateado con el sufijo K/M,null/0 no mostrar)
    val contextChip = rememberContextChip(model, labelContextFmt)
    // v2.x: Texto del chip de límite superior de salida(Mostrar límites de metadatos del modelo uno al lado del otro con chips contextuales)
    val outputChip = rememberOutputChip(model, labelOutputFmt)

    val labels = buildList {
        if (model.supportsToolCalling()) add(labelTool)
        if (model.supportsReasoning()) add(labelReasoning)
        if (model.supportsStreaming) add(labelStreaming)
        if (model.supportsImageOutput()) add(labelImage)
        if (model.supportsVisionInput()) add(labelMultimodal)
        if (model.supportsVideoOutput()) add(labelVideo)
        contextChip?.let { add(it) }
        outputChip?.let { add(it) }
    }.ifEmpty { listOf(labelChat) }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEach { label ->
            val (containerColor, contentColor) = when (label) {
                labelTool -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                labelReasoning -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                labelStreaming -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                labelImage -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                labelMultimodal -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                labelVideo -> MaterialTheme.colorScheme.inverseSurface to MaterialTheme.colorScheme.inverseOnSurface
                labelChat -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                contextChip -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                outputChip -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }
            Surface(
                color = containerColor,
                shape = MuseShapes.small,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        // v1.0.8 (7.6): estado de control de salud chip(añadido al final de la etiqueta de habilidad)
        when (testStatus) {
            is ModelTestStatus.InProgress -> TestStatusChip(
                text = labelTestInProgress,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leading = {
                    MuseSpinner(
                        size = 10.dp,
                        strokeWidth = 1.5.dp,
                    )
                },
            )
            is ModelTestStatus.Success -> {
                val text = testStatus.message?.takeIf { it.isNotBlank() }
                    ?.let { labelTestSuccessFmt.format(it) }
                    ?: labelTestSuccess
                TestStatusChip(
                    text = text,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leading = {
                        Icon(
                            imageVector = MuseIcons.check,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                    },
                )
            }
            is ModelTestStatus.Failed -> {
                val text = testStatus.message?.takeIf { it.isNotBlank() }
                    ?.let { labelTestFailedFmt.format(it.take(40)) }
                    ?: labelTestFailed
                TestStatusChip(
                    text = text,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    leading = {
                        Icon(
                            imageVector = MuseIcons.x,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                    },
                )
            }
            ModelTestStatus.Idle -> Unit
        }
    }
}

/**
 * v1.0.8 (7.6): estado de control de salud chip(icono + texto).
 */
@Composable
private fun TestStatusChip(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    leading: @Composable () -> Unit,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            leading()
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * v1.0.8 (7.7): Calcular el texto del chip de la ventana de contexto.
 *
 * - 0 o null → null(no mostrar)
 * - >= 1M → "1M contexto"
 * - >= 1K → "128K contexto"
 * - Otros → "8192 contexto"
 *
 * usar [remember] + [derivedStateOf] Evite reformatear cada vez que reorganice.
 */
@Composable
private fun rememberContextChip(model: Model, labelFormat: String): String? {
    return androidx.compose.runtime.remember(model.id, model.contextWindow, labelFormat) {
        val ctx = model.contextWindow?.takeIf { it > 0 } ?: return@remember null
        val formatted = when {
            ctx >= 1_000_000 -> "${ctx / 1_000_000}M"
            ctx >= 1000 -> "${ctx / 1000}K"
            else -> ctx.toString()
        }
        labelFormat.format(formatted)
    }
}

/**
 * v2.x: Texto del chip de límite superior de salida(Como por ejemplo "64K salida"),Las reglas de formato son consistentes con el chip de contexto.,null/0 No mostrado.
 */
@Composable
private fun rememberOutputChip(model: Model, labelFormat: String): String? {
    return androidx.compose.runtime.remember(model.id, model.maxOutputTokens, labelFormat) {
        val out = model.maxOutputTokens?.takeIf { it > 0 } ?: return@remember null
        val formatted = when {
            out >= 1_000_000 -> "${out / 1_000_000}M"
            out >= 1000 -> "${out / 1000}K"
            else -> out.toString()
        }
        labelFormat.format(formatted)
    }
}
