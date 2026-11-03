package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.vision.VisionAnalysisException
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Página de configuración de asistencia visual — Pasar un modelo de texto plano a través de un modelo visual"ver"fotos.
 *
 * Elementos de configuración:
 *  - habilitar/Desactivar ayudas visuales
 *  - Elegir un modelo para el análisis visual.(Mostrar solo modelos que admitan entrada visual)
 *
 * v1.0.1: Transformación del soporte de la estación de transferencia
 *  - P0: Llamado antes de filtrar [ModelRegistry.enhanceModel] Mejorar la identificación de la capacidad del modelo,Resolver lista de modelos extraída por la estación de transferencia
 *    Sin el camposupportVision/inputModalities, la interfaz de usuario no puede ver el modelo visual.
 *  - P1: Nuevo"Mostrar todos los modelos"cambiar(Modo avanzado de estación de transferencia),Permitir a los usuarios seleccionar manualmente modelos visuales no reconocidos
 */
@Composable
fun VisionSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val visionBridge: VisionBridge = koinInject()
    val context = LocalContext.current
    val enabled by settings.visionEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val visionModelId by settings.visionModelIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val visionProviderId by settings.visionProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // v1.0.1 (P1): "Mostrar todos los modelos"cambiar — Modo avanzado de estación de transferencia
    //  Saltar admite el filtrado VisionInput después de habilitarlo,Mostrar todos los modelos,Permitir a los usuarios seleccionar manualmente modelos visuales no reconocidos
    var showAllModels by rememberSaveable { mutableStateOf(false) }

    // v1.0.5: Estado de la prueba de la sonda — Idle / Running / Success / Failed
    var probeState by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

    // v1.0.1 (P0): Llame a EnhanceModel antes de filtrar para mejorar,Identificar el modelo de estación de transferencia.
    //  La lista de modelos extraída por la estación de transferencia generalmente no tiene el campo skills/inputModalities.,directo supportsVisionInput() Regresar false
    //  enhanceModel Haga coincidir reglas preestablecidas a través del token modelId,autocompletar supportsVision=true
    val visionModels = providers.flatMap { provider ->
        provider.models.map { model ->
            // modelo mejorado(Si no coincide con las reglas preestablecidas, se devolverá tal cual.)
            val enhanced = ModelRegistry.enhanceModel(model)
            VisionModelItem(
                modelId = model.id,
                modelName = model.name,
                providerId = provider.id,
                providerName = provider.displayName,
                supportsVision = enhanced.supportsVisionInput(),
            )
        }.filter { showAllModels || it.supportsVision }
    }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_vision_title),
        onBack = onBack,
    ) {
        // Descripción de la función
        item {
            SectionLabel(stringResource(R.string.settings_vision_description))
        }

        // interruptor principal
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.eye,
                    title = stringResource(R.string.settings_vision_enabled),
                    subtitle = stringResource(R.string.settings_vision_enabled_subtitle),
                    checked = enabled,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveVisionEnabled(v) }
                    },
                )
            }
        }

        // Selección de modelo visual(Sólo se muestra cuando está habilitado)
        if (enabled) {
            item {
                SectionLabel(stringResource(R.string.settings_vision_model_section))
            }
            item {
                SettingsGroup {
                    // v2.x: "Mostrar todos los modelos"Fusionado en la primera fila del grupo de lista de modelos. — Es un control de filtro para la lista.,Ya no en grupos separados
                    SettingsSwitchRow(
                        icon = MuseIcons.eye,
                        title = stringResource(R.string.settings_vision_show_all_models),
                        subtitle = stringResource(R.string.settings_vision_show_all_models_subtitle),
                        checked = showAllModels,
                        onCheckedChange = { v -> showAllModels = v },
                    )
                    SettingsGroupDivider()
                    if (visionModels.isEmpty()) {
                        // No hay sugerencias de modelos visuales disponibles
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(MusePaddings.cardInner),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_vision_no_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // Lista de modelos visuales
                        visionModels.forEachIndexed { index, item ->
                            val isSelected = item.modelId == visionModelId && item.providerId == visionProviderId
                            VisionModelRow(
                                modelName = item.modelName,
                                providerName = item.providerName,
                                // v1.0.1 (P1): Etiqueta de artículo no reconocida como modelo visual"selección manual"Consejos
                                manualSelected = showAllModels && !item.supportsVision,
                                isSelected = isSelected,
                                onClick = {
                                    scope.launch {
                                        settings.saveVisionModelId(item.modelId)
                                        settings.saveVisionProviderId(item.providerId)
                                    }
                                },
                            )
                            if (index < visionModels.size - 1) {
                                SettingsGroupDivider()
                            }
                        }
                    }
                }
            }

            // v1.0.5: prueba de sonda — Verifique que el modelo de visión seleccionado realmente admita la entrada visual y esté disponible
            item {
                SectionLabel(stringResource(R.string.settings_vision_probe_section))
            }
            item {
                SettingsGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(MusePaddings.cardInner),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_vision_probe_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // botón de prueba(Running deshabilitado y mostrado cuando spinner)
                            val isRunning = probeState is ProbeState.Running
                            MuseCapsuleButton(
                                text = if (isRunning) {
                                    stringResource(R.string.settings_vision_probe_running)
                                } else {
                                    stringResource(R.string.settings_vision_probe_button)
                                },
                                onClick = {
                                    if (isRunning) return@MuseCapsuleButton
                                    if (visionModelId.isNullOrBlank() || visionProviderId.isNullOrBlank()) {
                                        probeState = ProbeState.Failed(
                                            context.getString(R.string.settings_vision_probe_no_model),
                                        )
                                        return@MuseCapsuleButton
                                    }
                                    if (!enabled) {
                                        probeState = ProbeState.Failed(
                                            context.getString(R.string.settings_vision_probe_disabled),
                                        )
                                        return@MuseCapsuleButton
                                    }
                                    probeState = ProbeState.Running
                                    scope.launch {
                                        val result = resultOf {
                                            visionBridge.prepare(
                                                text = "prueba de sonda",
                                                images = listOf(VISION_PROBE_IMAGE_PNG_BASE64),
                                                userRequest = "prueba de sonda:¿De qué color es esta imagen??",
                                                sessionId = null, // No utilizar la dimensión de la clave de caché session
                                            )
                                        }
                                        probeState = when {
                                            result.isError -> {
                                                val ex = (result as io.zer0.common.Result.Error).throwable
                                                val msg = (ex as? VisionAnalysisException)?.message
                                                    ?: ex?.message
                                                    ?: result.message
                                                ProbeState.Failed(msg)
                                            }
                                            result.getOrNull()?.success == true -> ProbeState.Success(
                                                result.getOrNull()!!.text.length,
                                            )
                                            else -> ProbeState.Failed(
                                                result.getOrNull()?.text
                                                    ?: "El modelo de visión devuelve resultados vacíos o se degrada",
                                            )
                                        }
                                    }
                                },
                                enabled = !isRunning,
                                loading = isRunning,
                                leadingIcon = MuseIcons.bug,
                                variant = IosCapsuleButtonVariant.Text,
                                fillWidth = false,
                            )
                        }
                        // Visualización de resultados
                        when (val s = probeState) {
                            is ProbeState.Idle -> Unit
                            is ProbeState.Running -> Unit
                            is ProbeState.Success -> {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_vision_probe_success, s.chars),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            is ProbeState.Failed -> {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_vision_probe_failed, s.message),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fila de selección de modelo visual.
 *
 * @param manualSelected v1.0.1 (P1): Si el usuario lo selecciona manualmente"Modelo visual no reconocido"
 *  (Seleccione un modelo que EnhanceModel no reconozca como visual en el modo avanzado de la estación de transferencia).true Al agregar el subtítulo"selección manual"Consejos.
 */
@Composable
private fun VisionModelRow(
    modelName: String,
    providerName: String,
    isSelected: Boolean,
    manualSelected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (isSelected) MuseIcons.circleCheck else MuseIcons.circle,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = modelName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // v1.0.1 (P1): Se muestra en modo de selección manual."selección manual"Consejos
            val subtitle = if (manualSelected) {
                "$providerName · ${stringResource(R.string.settings_vision_manual_selected)}"
            } else {
                providerName
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Clase de datos de opciones de modelo visual. */
private data class VisionModelItem(
    val modelId: String,
    val modelName: String,
    val providerId: String,
    val providerName: String,
    /** v1.0.1 (P0): Si EnhanceModel lo reconoce como un modelo visual(Se utiliza para decidir si marcar"selección manual") */
    val supportsVision: Boolean = false,
)

/**
 * v1.0.5: Máquina de estado de prueba de sonda.
 *
 *  - [Idle]: estado inicial,Ninguna prueba activada
 *  - [Running]: Prueba en progreso(botón desactivado + mostrar spinner)
 *  - [Success]: Prueba exitosa,Llevar el número de caracteres de descripción devueltos por el modelo visual.
 *  - [Failed]: prueba fallida,Llevar mensajes de error legibles por humanos(insensibilizado VisionAnalysisException.message)
 */
private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Success(val chars: Int) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/**
 * v1.0.5: Rojo 1x1 incorporado para prueba de sonda PNG(Ninguno dato: prefijo).
 *
 * Se eligió 1x1 para minimizar los costos de prueba.(Sólo se envían unas pocas docenas de bytes de imágenes en una sola solicitud),
 * Al mismo tiempo, verifique si el modelo visual realmente puede recibir y devolver descripciones de imágenes.
 */
private const val VISION_PROBE_IMAGE_PNG_BASE64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
