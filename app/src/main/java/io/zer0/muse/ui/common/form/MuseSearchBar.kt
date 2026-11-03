package io.zer0.muse.ui.common.form

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.pill

/**
 * v2.0.1: Establecer barra de búsqueda de página.
 *
 * - Forma de cápsula, lupa a la izquierda, sin entrada de voz; haga clic para ingresar al estado de búsqueda (procesado por la persona que llama);
 * - [glass] Modo: el fondo solo se desenfoca dentro de la forma de la cápsula (backdrop blur, ligeramente borroso, lo que permite ver el contenido siguiente),
 *   Para estado suspendido del techo——La capa de contenido está marcada por la página como `Modifier.hazeSource(state)` pasó más tarde [hazeState];
 *   API 31+ Para un desenfoque gaussiano real, las versiones inferiores automáticamente degradan a malla semitransparente (Haze comportamiento incorporado);
 * - Modo sin vidrio: cápsula de superficie ligera de color sólido (surface blanco), utilizado para el desplazamiento normal del contenido.
 *
 * Nota: el rango de desenfoque se limita al cuerpo de la cápsula.——No se realiza ningún enmascaramiento fuera de la cápsula.
 */
@Composable
fun MuseSearchBar(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glass: Boolean = false, hazeState: HazeState? = null) {
    val shape = MaterialTheme.shapes.pill
    val surfaceColor = MaterialTheme.colorScheme.surface
    val base = modifier
        .fillMaxWidth()
        .height(MusePaddings.searchBarHeight)
        .clip(shape)
    val surfaceModifier = if (glass && hazeState != null) {
        base.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                // El color de fondo debe ser cercano al color sólido: cuando el total es 0,59, el 40% del texto que se desplaza a continuación aún es visible.
                // En el estado montado en el techo, verá "una capa de palabras pegadas en el cristal". Después de mejorar a 0,86/0,12
                // La textura borrosa se conserva, pero el texto legible ya no se ve.
                backgroundColor = surfaceColor.copy(alpha = 0.86f),
                tint = HazeTint(surfaceColor.copy(alpha = 0.12f)),
                blurRadius = 16.dp,
                noiseFactor = 0f,
            ),
        )
    } else {
        base.background(surfaceColor)
    }
    Box(
        modifier = surfaceModifier.clickable(onClick = onClick),
        // v2.0.1 fix: El contenido debe estar explícitamente centrado verticalmente.——Box Inicio superior predeterminado,Row Sólo cuando fillMaxWidth
        // El ícono y el texto se pegarán en la parte superior de la cápsula (un defecto en la posición hacia arriba del texto de la barra de búsqueda).
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
