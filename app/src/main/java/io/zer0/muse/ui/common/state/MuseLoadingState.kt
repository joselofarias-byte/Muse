package io.zer0.muse.ui.common.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Componentes de estado de carga unificados(v1.0.20 Nuevo).
 *
 * Sustituir artículo completo 44 desnudez dispersa [CircularProgressIndicator],Parámetros unificados:
 *  - size: [MuseIconSizes.iconLarge](32dp),Equilibrar la presencia visual y la no intrusión
 *  - strokeWidth: 2.dp,textura delgada(estilo de línea delgada)
 *  - Copia de mensaje opcional,Centrado debajo del indicador
 *
 * Uso:
 * ```
 * if (isLoading) {
 *     MuseLoadingState(message = "Cargando sesión...")
 * }
 * ```
 *
 * @param message Cargar copia(Opcional,Se muestra debajo del indicador cuando se proporciona)
 * @param modifier modificador
 */
@Composable
fun MuseLoadingState(message: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(MusePaddings.screen * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        MuseSpinner(
            size = MuseIconSizes.iconLarge,
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
