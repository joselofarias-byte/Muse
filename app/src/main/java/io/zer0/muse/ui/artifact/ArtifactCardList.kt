package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.artifact.ArtifactEntity

/**
 * Lista horizontal de la tarjeta de producto —— Se utiliza en la parte inferior de la burbuja del mensaje para mostrar todos los productos extraídos del mensaje.
 *
 * Phase 2: exceder [MAX_VISIBLE_ARTIFACT_CARDS] Sólo se mostrarán los primeros 5,
 * para cola "+N" plegado de tarjetas,Haga clic para expandir todo(Asegúrese de que los productos colapsados aún sean accesibles).
 */
@Composable
fun ArtifactCardList(artifacts: List<ArtifactEntity>, onArtifactClick: (ArtifactEntity) -> Unit, modifier: Modifier = Modifier) {
    // Utilice la primera identificación del producto como clave para guardar:Cambio de mensajes(Listar cambios de contenido)restablecer el estado expandido
    var expanded by rememberSaveable(artifacts.firstOrNull()?.id) { mutableStateOf(false) }
    val visible = if (expanded) artifacts else artifacts.take(MAX_VISIBLE_ARTIFACT_CARDS)
    val hiddenCount = artifacts.size - visible.size

    // v2.0.1: Camión longitudinal grande — 180dp Las tarjetas horizontales pequeñas se cambian a columnas de tarjetas grandes de ancho completo (formato de tarjeta de producto).
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visible.forEach { artifact ->
            ArtifactCard(
                artifact = artifact,
                onClick = { onArtifactClick(artifact) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (hiddenCount > 0) {
            ArtifactOverflowCard(
                hiddenCount = hiddenCount,
                onClick = { expanded = true },
            )
        }
    }
}
