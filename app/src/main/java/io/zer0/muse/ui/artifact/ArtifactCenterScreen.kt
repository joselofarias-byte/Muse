package io.zer0.muse.ui.artifact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import org.koin.compose.koinInject

/**
 * v2.0.1: Centro de productos — Resuma los productos de todas las sesiones (lista de tarjetas de ancho completo).
 *
 * Haga clic en la tarjeta para abrir: Contenido enriquecido (HTML/SVG/Chart/Mermaid) en modo de navegador de pantalla completa,
 * El resto (código/texto) visor de ventana emergente (compartido [ArtifactOpenHost]).
 * Entrada: barra superior de la página del espacio de trabajo.
 */
@Composable
fun ArtifactCenterScreen(onBack: () -> Unit) {
    val repository: ArtifactRepository = koinInject()
    val artifacts by repository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var opened by remember { mutableStateOf<ArtifactEntity?>(null) }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.artifact_center_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.0.1 fix(Defensa contra accidentes de máquinas reales): Lo mismo ConnectionCenter — La rejilla se cuelga después de cerrar la altura limitada.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val boundedHeight = io.zer0.muse.ui.settings.boundedSettingsScrollHeight(
                parentMaxHeight = maxHeight,
                windowHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp,
            )
            if (artifacts.isEmpty()) {
                MuseEmptyState(
                    title = stringResource(R.string.artifact_center_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // v2.0.1: Library cuadrícula — Exploración de miniaturas de dos columnas (las tarjetas grandes se reducen de forma adaptativa dentro de la cuadrícula)
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = boundedHeight),
                    contentPadding = PaddingValues(
                        start = MusePaddings.screen,
                        end = MusePaddings.screen,
                        top = 12.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = artifacts.size, key = { artifacts[it].id }) { idx ->
                        val artifact = artifacts[idx]
                        ArtifactCard(
                            artifact = artifact,
                            onClick = { opened = artifact },
                        )
                    }
                }
            }
        }
    }
    ArtifactOpenHost(artifact = opened, onDismiss = { opened = null })
}
