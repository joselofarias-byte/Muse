@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MusePaddings

/**
 * modelo de habla (TTS / ASR) candidatos y reglas de redacción.
 *
 * Los modelos solo se mantienen en el proveedor, y la página de configuración solo es responsable de "seleccionar uno": una lista de modelos candidatos del proveedor,
 * Los campos escritos dependen de si seguir al proveedor o no. —— Sigue y escribe `modelId`, escribir manualmente `model`.
 * La lógica se extrae en funciones puras, la página solo se representa y Compose no es necesario para pruebas individuales.
 */
internal object SpeechModelPicker {

    /**
     * Proveedor candidato: Tiene prioridad el especificado en la configuración. Si no se especifica (o se ha eliminado), se utilizará el primero activado.
     *
     * El proveedor desactivado aún se devuelve: el usuario puede estar a punto de volver a cambiar su modelo y ocultarlo directamente dará la impresión de que la configuración se ha perdido.
     */
    fun candidateProvider(providers: List<ProviderConfig>, providerId: String): ProviderConfig? =
        providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull { it.enabled }

    /** Modelos candidatos: Lista de los modelos del proveedor, el orden permanece sin cambios. */
    fun options(provider: ProviderConfig?): List<SpeechModelOption> =
        provider?.models.orEmpty().map { SpeechModelOption(id = it.id, name = it.name) }

    /**
     * Seleccionado actualmente.
     *
     * Contabilizado cuando queda un valor manual en la configuración, pero no está en la lista de candidatos (configuración antigua o el proveedor no ha sincronizado este modelo)
     * [SpeechModelSelection.Custom]:UI Mostrar "Personalizar:x”, el valor se conserva tal como está y nunca se borrará.
     */
    fun selection(currentId: String, options: List<SpeechModelOption>): SpeechModelSelection {
        val current = currentId.trim()
        if (current.isBlank()) return SpeechModelSelection.Empty
        val hit = options.firstOrNull { it.id == current }
        return if (hit != null) SpeechModelSelection.FromProvider(hit) else SpeechModelSelection.Custom(current)
    }

    /** Configuración que se colocará en la biblioteca después de seleccionar el modelo TTS: siga las instrucciones del proveedor [MediaConfig.ttsModelId], en caso contrario escribe [MediaConfig.ttsModel]. */
    fun applyToTts(config: MediaConfig, modelId: String): MediaConfig =
        if (config.ttsFollowProvider) {
            config.copy(ttsModelId = modelId)
        } else {
            config.copy(ttsModel = modelId)
        }

    /**
     * ASR Si realmente se está utilizando la configuración del proveedor.
     *
     * DashScope El punto final integrado y la dirección del proveedor no participan en la identificación en tiempo real, por lo que incluso si el interruptor se enciende en este motor, aún se procesará manualmente.
     * (con [io.zer0.muse.data.SpeechProviderResolver.resolveAsr] el juicio sigue siendo consistente).
     */
    fun asrFollowActive(config: AsrConfig): Boolean =
        config.followProvider && config.provider != AsrProviderType.DASHSCOPE

    /** Configuración que se colocará en la biblioteca después de seleccionar el modelo ASR: siga las instrucciones del proveedor [AsrConfig.modelId], en caso contrario escribe [AsrConfig.model]. */
    fun applyToAsr(config: AsrConfig, modelId: String): AsrConfig =
        if (asrFollowActive(config)) {
            config.copy(modelId = modelId)
        } else {
            config.copy(model = modelId)
        }
}

/** Modelo candidato (conserve solo los dos campos utilizados por el panel de selección para evitar que todo el objeto del modelo entre en estado de interfaz de usuario). */
internal data class SpeechModelOption(val id: String, val name: String)

/** Tres estados de visualización de líneas de selección de modelo. */
internal sealed interface SpeechModelSelection {

    /** El modelo seleccionado es el que está en la lista de candidatos. */
    data class FromProvider(val model: SpeechModelOption) : SpeechModelSelection

    /** El valor en la configuración no está entre los candidatos (la configuración/proveedor anterior no está sincronizado) y se muestra como \"personalizado\" tal como está. */
    data class Custom(val value: String) : SpeechModelSelection

    /** Si no se selecciona ningún modelo, se utiliza el motor predeterminado. */
    data object Empty : SpeechModelSelection
}

/** Copia para mostrar el nombre del modelo: tome el nombre del candidato, no marque \"personalizado\" en el candidato y utilice el marcador de posición proporcionado por la persona que llama para los valores vacíos. */
@Composable
internal fun speechModelLabel(selection: SpeechModelSelection, emptyLabel: String): String = when (selection) {
    is SpeechModelSelection.FromProvider -> selection.model.name
    is SpeechModelSelection.Custom -> stringResource(R.string.settings_speech_model_custom_prefix, selection.value)
    SpeechModelSelection.Empty -> emptyLabel
}

/**
 * Bloque de configuración del modelo: fila del modelo + descripción + (bajo demanda) área de entrada personalizada + panel de selección.
 *
 * TTS La única diferencia con ASR es "dónde escribir después de la selección", por [onPick] Decidió no repetir este conjunto de controles en la página.
 *
 * @param currentModelId La identificación del modelo actualmente vigente en la configuración (modelId cuando se sigue al proveedor, modelo en modo manual)
 * @param provider Proveedores candidatos (lista de candidatos decisivos)
 * @param emptyLabel Subtítulo cuando no se selecciona ningún modelo
 * @param onPick La devolución de llamada después de seleccionar (o guardar la personalización) es responsable de eliminar la biblioteca.
 * @param onOpenProviders Salir cuando el candidato esté vacío;null Indica que no se proporciona entrada
 */
@Composable
internal fun SpeechModelSection(
    title: String,
    currentModelId: String,
    provider: ProviderConfig?,
    emptyLabel: String,
    onPick: (String) -> Unit,
    onOpenProviders: (() -> Unit)? = null,
) {
    val options = remember(provider) { SpeechModelPicker.options(provider) }
    val selection = remember(currentModelId, options) { SpeechModelPicker.selection(currentModelId, options) }
    var pickerOpen by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var customValue by remember(currentModelId) { mutableStateOf(currentModelId) }

    SpeechModelRow(
        title = title,
        selection = selection,
        emptyLabel = emptyLabel,
        onOpenPicker = { pickerOpen = true },
    )
    if (customOpen) {
        SpeechModelCustomField(
            value = customValue,
            fieldLabel = title,
            onValueChange = { customValue = it },
            onSave = {
                onPick(customValue.trim())
                customOpen = false
            },
            onCancel = { customOpen = false },
        )
    }
    if (pickerOpen) {
        SpeechModelSheet(
            title = title,
            options = options,
            selection = selection,
            onSelect = { modelId ->
                onPick(modelId)
                pickerOpen = false
            },
            onUseCustom = {
                customValue = currentModelId
                pickerOpen = false
                customOpen = true
            },
            onOpenProviders = onOpenProviders,
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * Fila de modelo: Haga clic en el panel de selección, y debajo encontrará una descripción de "El modelo lo gestiona el proveedor".
 *
 * @param title encabezado de fila (TTS y la respectiva redacción “modelo” de ASR)
 * @param selection Seleccionado actualmente
 * @param emptyLabel Subtítulo cuando no se selecciona ningún modelo
 */
@Composable
internal fun SpeechModelRow(
    title: String,
    selection: SpeechModelSelection,
    emptyLabel: String,
    onOpenPicker: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsItemRow(
            icon = MuseIcons.robot,
            title = title,
            subtitle = speechModelLabel(selection, emptyLabel),
            onClick = onOpenPicker,
        ) {
            ChevronRight()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
        ) {
            Text(
                text = stringResource(R.string.settings_speech_model_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Panel de selección de modelo.
 *
 * Los candidatos provienen de proveedores; cuando la lista está vacía, se muestran una copia de la guía y la entrada "Ir a la configuración del proveedor".
 * Deje "Cambiar a Personalizado" al final…”, todavía hay una salida para los valores completados manualmente que no han sido sincronizados por el proveedor.
 */
@Composable
internal fun SpeechModelSheet(
    title: String,
    options: List<SpeechModelOption>,
    selection: SpeechModelSelection,
    onSelect: (String) -> Unit,
    onUseCustom: () -> Unit,
    onOpenProviders: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    MuseSelectionSheet(
        title = title,
        subtitle = stringResource(R.string.settings_speech_model_hint),
        onDismissRequest = onDismiss,
    ) {
        if (options.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_speech_model_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenProviders != null) {
                MuseListItem(
                    onClick = {
                        onDismiss()
                        onOpenProviders()
                    },
                    leadingContent = { Icon(MuseIcons.server, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.settings_speech_model_open_providers)) },
                    trailingContent = { ChevronRight() },
                )
            }
        } else {
            val selectedId = (selection as? SpeechModelSelection.FromProvider)?.model?.id
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                items(options, key = { it.id }) { option ->
                    MuseListItem(
                        onClick = { onSelect(option.id) },
                        headlineContent = {
                            Text(
                                text = option.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = if (option.id == option.name) {
                            null
                        } else {
                            { Text(option.id) }
                        },
                        trailingContent = if (option.id == selectedId) {
                            {
                                Icon(
                                    imageVector = MuseIcons.check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        MuseListItem(
            onClick = { onUseCustom() },
            leadingContent = { Icon(MuseIcons.edit, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.settings_speech_model_custom_entry)) },
        )
    }
}

/**
 * Personalice el área de entrada del nombre del modelo (solo si hace clic en "Cambiar a personalizado" en el panel…”antes de desplegar).
 *
 * Ocupa el cuadro de texto que se colocaba directamente en la página en la versión anterior: la habilidad no se elimina, simplemente se oculta detrás de una entrada clara.
 */
@Composable
internal fun SpeechModelCustomField(
    value: String,
    fieldLabel: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        MuseTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(fieldLabel) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.action_save),
                onClick = onSave,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }
}
