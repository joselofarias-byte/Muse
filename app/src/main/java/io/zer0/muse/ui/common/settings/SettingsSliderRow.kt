package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Configuración de fila con control deslizante — Icono a la izquierda + título + subtítulo + valor a la derecha,Control deslizante de ancho completo a continuación.
 *
 * CMP-10: Solo se implementó la fila completa de configuración del control deslizante del proyecto. Originales MemorySettingsPage / RagSettingsPage /
 * MediaSettingsPage cada privado `SliderRow` Eliminado y movido todo aquí.,Las diferencias morfológicas se expresan únicamente mediante parámetros.
 *
 * Visión:icono(20dp,[MuseIconSizes.iconMedium],No lo pases)Alinear con la fila del título,Control deslizante en la barra inferior,
 * Valor numérico adjunto al final de la línea(primary + SemiBold,Consistente con el valor de énfasis de otras filas de configuración).
 *
 * Uso:
 * ```
 * SettingsSliderRow(
 *     icon = MuseIcons.sliders,
 *     title = stringResource(R.string.settings_rag_top_k),
 *     subtitle = stringResource(R.string.settings_rag_top_k_subtitle),
 *     value = topK.toFloat(),
 *     valueRange = 1f..10f,
 *     steps = 8,
 *     valueText = "$topK",
 *     onValueChange = { topK = it.roundToInt() },
 *     onValueChangeFinished = { save() },
 * )
 * ```
 *
 * @param icon icono izquierdo(null no se mostrará,para fila deslizante sin icono)
 * @param title título principal
 * @param value valor actual
 * @param valueRange Rango de valores
 * @param steps Número de engranaje discreto(Material Semántica:start/end El número de paradas intermedias entre)
 * @param valueText Copia numérica a la derecha(El formato lo determina la persona que llama.,Mantener la precisión de cada elemento de configuración)
 * @param onValueChange Devolución de llamada de cambio de valor durante el arrastre
 * @param modifier modificador
 * @param subtitle subtítulo(null no se mostrará)
 * @param iconContentDescription Descripción de accesibilidad del icono(null Representa iconos decorativos.)
 * @param onValueChangeFinished dejar ir la devolución de llamada(Se utiliza cuando es necesario escribir valores en la capa de persistencia.,Evite procesos de arrastre frecuentes IO)
 */
@Composable
fun SettingsSliderRow(
    icon: ImageVector? = null,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconContentDescription: String? = null,
    onValueChangeFinished: () -> Unit = {},
    /**
     * P2-1(informe de auditoría): Permite la entrada manual de valores precisos haciendo clic en el campo de valor.
     *
     * La implementación anterior solo podía arrastrar el control deslizante, y el control deslizante estaba limitado por pasos: el paso del tamaño del mosaico era 100 (380 no se puede configurar),
     * top-K El límite superior es solo 10,token El límite superior del presupuesto es 8.000, etc. No hay ninguna solución cuando las necesidades del usuario se encuentran entre las cuadrículas.
     *
     * Pasar este parámetro habilitará la entrada manual; si no se aprueba, se mantendrá el comportamiento original (algunas filas no son aplicables).
     */
    onValueInput: ((Float) -> Unit)? = null,
    /** Formato numérico utilizado al ingresar manualmente/Analizador (por ejemplo, número entero, un decimal). */
    valueInputFormatter: ((Float) -> String)? = null,
    valueInputParser: ((String) -> Float?)? = null,
) {
    // P2-1: Diálogo de entrada manual
    var showInput by remember { mutableStateOf(false) }
    if (showInput && onValueInput != null) {
        val fmt = valueInputFormatter ?: { v: Float -> v.toString() }
        val parse = valueInputParser ?: { s: String -> s.trim().toFloatOrNull() }
        var text by remember { mutableStateOf(fmt(value)) }
        MuseDialog(
            onDismissRequest = { showInput = false },
            title = title,
            content = {
                MuseTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.settings_slider_input_hint)) },
                )
            },
            confirmText = stringResource(R.string.settings_slider_input_confirm),
            onConfirm = {
                val parsed = parse(text)
                if (parsed != null) {
                    // Sujétese dentro del rango legal para evitar cruzar el límite con la entrada manual.
                    onValueInput(parsed.coerceIn(valueRange.start, valueRange.endInclusive))
                    onValueChangeFinished()
                }
                showInput = false
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showInput = false },
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    // LAYOUT-01: Restricciones de viaje(Ver la misma descripción de SettingsSwitchRow)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = MusePaddings.contentGap)
                    .then(
                        // P2-1: Cuando la capacidad de entrada manual está disponible, se puede hacer clic en el área de valores
                        if (onValueInput != null) {
                            Modifier.clickable { showInput = true }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
        MuseSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            showValueLabel = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MusePaddings.contentGap),
        )
    }
}
