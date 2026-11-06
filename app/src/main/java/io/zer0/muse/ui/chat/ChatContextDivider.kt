// Composable Según la convención Compose, PascalCase se utiliza para nombrar, lo que entra en conflicto con las reglas de denominación de kotlin de detekt;
// Siga las prácticas establecidas del proyecto (consulte MusePopover.kt / MuseFloatingActionMenu.kt) para conocer las exenciones a nivel de archivo.
@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings

/**
 * en el flujo de conversación"El contexto está comprimido."Divisor.
 *
 * ## que expresa
 *
 * La conversación sobre esta línea se ha fusionado en el resumen:**El modelo ya no los ve artículo por artículo.**. La interfaz aún conserva el texto original (haga clic para expandir),
 * Porque los usuarios necesitan poder recordar lo que dijeron.——Pero está contraído de forma predeterminada, por lo que la sesión comprimida parece"Continuar desde el nuevo contexto",
 * en lugar de"Se inserta un resumen en el medio.".
 *
 * ## Diferencias con los mensajes de resumen
 *
 * El resumen realmente enviado al modelo es `[COMPRESSED]` Mensaje del SISTEMA al principio (ver ContextCheckpointMerge);
 * Este artículo es solo un ancla de interfaz y no participa en ningún ensamblaje de solicitudes.
 *
 * @param totalCovered El número acumulado de artículos incorporados al resumen;0 Indica desconocido (punto de control antiguo), el número de entradas no se muestra en este momento
 * @param expanded Si el mensaje anterior está actualmente expandido
 * @param onToggle Haga clic para alternar para expandir/cerrar
 */
@Composable
fun ChatContextDivider(totalCovered: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val title = if (totalCovered > 0) {
        stringResource(R.string.chat_context_divider_title_count, totalCovered)
    } else {
        stringResource(R.string.chat_context_divider_title)
    }
    val hint = stringResource(
        if (expanded) {
            R.string.chat_context_divider_hint_expanded
        } else {
            R.string.chat_context_divider_hint_collapsed
        },
    )
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.messageGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
            modifier = Modifier.padding(vertical = MusePaddings.tinyGap),
        ) {
            Icon(
                imageVector = MuseIcons.gitMerge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
            // La extensión de la copia varía según el idioma y el número de artículos (alemán/El ruso es significativamente más largo), dando suficiente ancho y limitando la línea,
            // Evite ajustar líneas palabra por palabra con tamaños de fuente ampliados en pantallas estrechas.
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
