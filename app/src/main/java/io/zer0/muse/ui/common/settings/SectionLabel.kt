@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Section Título del grupo(Establecer título del grupo de páginas).
 *
 * Uso:`SectionLabel("Conceptos básicos")`
 *
 * estilo:labelLarge + primary Color(azul),Con SettingsScreen / AssistantScreen y 4 más
 * Las páginas de gestión están unificadas.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        // Ya no viene con espacio superior: el LazyColumn del usuario ya lo tiene spacedBy(espaciado de grupos),
        // Agregar ambos lados duplicará el espacio de agrupación, lo que provocará que "una pantalla no pueda caber en dos grupos".
        modifier = modifier.padding(bottom = MusePaddings.tightGap),
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
    )
}
