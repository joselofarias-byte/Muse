package io.zer0.muse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/** B7-07: Página de edición de pantalla completa del campo de entrada,de [InputBar] Separe para reducir el número de líneas del archivo principal. */
@Composable
internal fun MuseExpandedInputEditor(
    text: String,
    assistantName: String = "Muse",
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            // v2.6.0 (AUD-UI-F14): suplemento imePadding —— Esta página está compuesta directamente desde la barra de entrada.
            // (Ventana independiente sin diálogo),edge-to-edge El teclado en pantalla insertado no subirá automáticamente el contenido,
            // Cuadro de entrada cuando aparece el teclado/El botón de enviar quedará oculto.
            .systemBarsPadding()
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(MusePaddings.screen)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MuseTactileButton(
                    icon = MuseIcons.arrowLeft,
                    onClick = onClose,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.chat_expand_input_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(MuseIconSizes.touchTarget))
            }
            Spacer(Modifier.height(MusePaddings.itemGap))
            MuseTextField(
                value = text,
                onValueChange = { if (it.length <= INPUT_TEXT_MAX_LENGTH) onTextChanged(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                minLines = 10,
                maxLines = 100,
                placeholder = {
                    Text(stringResource(R.string.chat_placeholder_send, assistantName.ifBlank { "Muse" }))
                },
            )
            Spacer(Modifier.height(MusePaddings.itemGap))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSend()
                            onClose()
                        }
                    },
                    enabled = text.isNotBlank(),
                    shape = MuseShapes.pill,
                    color = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = MusePaddings.inputHorizontal, vertical = MusePaddings.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MuseIcons.send,
                            contentDescription = null,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            tint = if (text.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                        Spacer(Modifier.width(MusePaddings.labelVerticalGap))
                        Text(
                            text = stringResource(R.string.action_send),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (text.isNotBlank()) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
            }
        }
    }
}
