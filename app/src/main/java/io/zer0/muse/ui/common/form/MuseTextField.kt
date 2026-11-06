@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * Cuadro de entrada lleno — Anular el valor predeterminado de Material3 [OutlinedTextField].
 *
 * diferencias visuales:
 *  - borde transparente(Sin marco delineado),Utilice SurfaceVariant para rellenar el fondo y distinguir el área de entrada.
 *  - El color de fondo se intensifica cuando se enfoca(surfaceContainerHigh),Animación sin fronteras
 *  - esquinas redondeadas 16dp([MuseShapes.semiLarge]),Consistente con el cuadro de entrada en la página de configuración.
 *  - label Cuando se concentra se vuelve onSurfaceVariant(gris),Sin colores de marca
 *
 * descripción del diseño:Establezca el estilo del cuadro de entrada lleno de la página.
 * con [MuseDropdown],[MuseChip] utilizado junto con otros componentes.
 *
 * Uso:
 * ```
 * MuseTextField(
 *     value = text,
 *     onValueChange = { text = it },
 *     label = { Text("Nombre") },
 *     modifier = Modifier.fillMaxWidth(),
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    interactionSource: MutableInteractionSource? = null,
    /**
     * v1.0.72: Color de fondo del contenedor personalizado(Predeterminado null = Usar color de relleno predeterminado del tema).
     * Transferencia de escena de campo de entrada Color.Transparent,Evite que los bloques de colores sólidos del cuadro de entrada se superpongan en el fondo de la isla."bloque blanco".
     */
    containerColor: androidx.compose.ui.graphics.Color? = null,
    /** Solicitante de enfoque de campo de entrada interno opcional,Se utiliza para un enfoque automático seguro después de abrir la página. */
    focusRequester: FocusRequester? = null,
    /**
     * Opcional:Semáforo que fuerza el cursor al final del texto. Cada vez que el valor "se hace más grande",Simplemente coloque el cursor al final.
     *
     * Se utiliza para insertar @ miembros/Continúe escribiendo después del fragmento de plantilla.(El enlace Pure String no puede cambiar el cursor).
     * Predeterminado 0 = cerrar,Tome la ruta de enlace de cuerdas original,El comportamiento actual de las personas que llaman permanece sin cambios.
     */
    caretAtEndTick: Int = 0,
) {
    // v1.0.74 fix (Auditoría inicial 3.5): interactionSource Cambie a anulable + recuerdo interno.
    // En los parámetros predeterminados de la implementación original remember{...} Evaluar en el contexto de composición de la persona que llama,Varias llamadas dentro de un bucle comparten la misma instancia.
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme

    // camino controlado(solo caretAtEndTick > 0 habilitado cuando):Utilice TextFieldValue para mantener la selección.
    // Cuando no está habilitado, la siguiente ruta de cadena no participa en absoluto.,El comportamiento actual de las personas que llaman permanece sin cambios.
    val useControlledField = caretAtEndTick > 0
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Sincronizar texto cuando cambia el texto externo(circunscripción reservada,Consistente con la sobrecarga de cadenas)
    LaunchedEffect(value) {
        if (useControlledField && fieldValue.text != value) {
            val end = fieldValue.selection.end.coerceIn(0, value.length)
            fieldValue = fieldValue.copy(text = value, selection = TextRange(end))
        }
    }
    // Cambios de señal:El cursor cae hasta el final.
    LaunchedEffect(caretAtEndTick) {
        if (useControlledField) {
            fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
        }
    }

    Column(modifier = modifier) {
        // v1.0.29: label Se muestra uniformemente encima del cuadro de entrada,Evite el texto en el cuadro de entrada/placeholder superponerse,
        // Mejorar la legibilidad. Esta es una especificación común para los campos de entrada en todas las aplicaciones.
        if (label != null) {
            ProvideTextStyle(
                value = MaterialTheme.typography.labelMedium.copy(
                    color = scheme.onSurfaceVariant,
                ),
            ) {
                label()
            }
            Spacer(Modifier.height(MusePaddings.tinyGap))
        }

        val fieldModifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
        val fieldColors = OutlinedTextFieldDefaults.colors(
            // llenar el fondo:Se utiliza al enfocar surfaceContainerHigh(un poco más profundo),Para desenfocados surfaceVariant
            // v1.0.72: containerColor Utilice colores personalizados al pasar valores no nulos(Escena del campo de entrada transparente)
            // v2.0: Oscurecer el color de relleno en un nivel(Los comentarios de los usuarios son demasiado débiles en comparación con el fondo.)——Mezclar color en onSurface,
            // Los temas claros se oscurecen automáticamente, los temas oscuros se iluminan automáticamente,Compatible con todos los temas preestablecidos.
            focusedContainerColor = containerColor
                ?: androidx.compose.ui.graphics.lerp(scheme.surfaceContainerHigh, scheme.onSurface, 0.10f),
            unfocusedContainerColor = containerColor
                ?: androidx.compose.ui.graphics.lerp(scheme.surfaceVariant, scheme.onSurface, 0.06f),
            disabledContainerColor = containerColor
                ?: androidx.compose.ui.graphics.lerp(scheme.surfaceVariant, scheme.onSurface, 0.06f),
            errorContainerColor = containerColor
                ?: androidx.compose.ui.graphics.lerp(scheme.surfaceVariant, scheme.onSurface, 0.06f),
            // borde transparente:No utilice el esquema delineado predeterminado del Material
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
            errorBorderColor = if (isError) scheme.error else Color.Transparent,
            // color del texto
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface,
            disabledTextColor = scheme.onSurfaceVariant,
            // placeholder color
            focusedPlaceholderColor = scheme.onSurfaceVariant,
            unfocusedPlaceholderColor = scheme.onSurfaceVariant,
            // supportingText color
            focusedSupportingTextColor = scheme.onSurfaceVariant,
            unfocusedSupportingTextColor = scheme.onSurfaceVariant,
            // prefijo/Color del icono de sufijo
            focusedLeadingIconColor = scheme.onSurfaceVariant,
            unfocusedLeadingIconColor = scheme.onSurfaceVariant,
            focusedTrailingIconColor = scheme.onSurfaceVariant,
            unfocusedTrailingIconColor = scheme.onSurfaceVariant,
            // Color del cursor
            cursorColor = scheme.onSurface,
            errorCursorColor = scheme.error,
        )

        if (useControlledField) {
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { v ->
                    fieldValue = v
                    onValueChange(v.text)
                },
                modifier = fieldModifier,
                enabled = enabled,
                readOnly = readOnly,
                label = null,
                placeholder = placeholder,
                supportingText = null,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                isError = isError,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                interactionSource = resolvedInteractionSource,
                shape = MuseShapes.semiLarge,
                colors = fieldColors,
            )
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                // Reparación de auditoría (2.x corrección de regresión): modifier colgar la capa exterior Column — pasado por la persona que llama
                // RowScope.weight(1f) Debe actuar sobre los niños directos de Row.,Después de colgar en el cuerpo de la caja de entrada. weight
                // Inválido,El cuadro de entrada pierde su restricción de ancho y ocupa toda la página.(Regresión medida por el usuario).
                // focusRequester Colgar columna sin estrellarse(El enfoque automático no reproduce el teclado,Los usuarios pueden hacer clic manualmente).
                modifier = fieldModifier,
                enabled = enabled,
                readOnly = readOnly,
                label = null,
                placeholder = placeholder,
                supportingText = null,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                isError = isError,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                interactionSource = resolvedInteractionSource,
                shape = MuseShapes.semiLarge,
                colors = fieldColors,
            )
        }

        // v1.0.29: supportingText También se muestra debajo del cuadro de entrada,Simétrico para etiquetar.
        if (supportingText != null) {
            Spacer(Modifier.height(MusePaddings.tinyGap))
            ProvideTextStyle(
                value = MaterialTheme.typography.bodySmall.copy(
                    color = scheme.onSurfaceVariant,
                ),
            ) {
                supportingText()
            }
        }
    }
}
