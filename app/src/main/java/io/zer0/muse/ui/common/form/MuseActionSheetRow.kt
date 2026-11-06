@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Fila desplegable unificada para el menú inferior/panel de acción (v2.x unificación).
 *
 * Extraído del menú de pulsación larga de la lista de chat (anteriormente ActionSheetRow privado), utilizado para MuseDialog /
 * Fila de acción en la que se puede hacer clic en el menú inferior: ancho completo + fondo redondeado poco profundo + icono opcional + texto alineado a la izquierda.
 *
 * Usuarios de la misma familia (todos deben mantener el estilo de este componente y ya no implementar su propia implementación):
 *  - Lista de chat menú de pulsación larga
 *  - MCP Menú "Más" del servidor
 *  - El menú "Más" de la fila de configuración, como Tema/Espacio de memoria/Configuración de teléfono pequeño
 *
 * @param text redacción de acciones
 * @param modifier modificador
 * @param icon Icono opcional (null solo texto)
 * @param contentColor Color de primer plano (pase colorScheme.error para acciones peligrosas)
 * @param enabled ¿Está disponible (false La transparencia cae y no responde a los clics)
 * @param onClick Haga clic en devolución de llamada (coloque al final, admite lambda final)
 */
@Composable
fun MuseActionSheetRow(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val effectiveColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuseIconSizes.touchTarget)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveColor,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = effectiveColor,
            // LAYOUT-01: Tome el ancho restante + límite de línea. La línea de acción es Row(Icono + Copiar), si la copia no es más ancha, aumenta el tamaño de la fuente
            // (sistema"tamaño de pantalla")O en una ventana estrecha, los íconos se agruparán en una columna y las palabras se agruparán palabra por palabra. —— Mismo origen que la fila del servidor MCP.
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
