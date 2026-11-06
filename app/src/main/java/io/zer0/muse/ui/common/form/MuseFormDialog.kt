@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Ventana emergente unificada de formato corto.
 *
 * Se utiliza para editar una pequeña cantidad de campos./Seleccionar. El contenido está alineado uniformemente hacia la izquierda y los controles mantienen un espaciado fijo.
 * El botón de confirmación admite el estado deshabilitado; los formularios más largos deben actualizarse a [MuseBottomSheet] o página independiente.
 */
@Composable
fun MuseFormDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    confirmText: String = stringResource(R.string.common_confirm),
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    dismissText: String? = stringResource(R.string.common_cancel),
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                    )
                }
                content()
            }
        },
        confirmText = confirmText,
        confirmEnabled = confirmEnabled,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismiss = onDismiss,
        destructive = destructive,
    )
}
