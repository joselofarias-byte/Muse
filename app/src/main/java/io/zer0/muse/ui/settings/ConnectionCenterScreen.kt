package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard
import org.koin.compose.koinInject

/**
 * v2.0.1: centro de conexion — Ver todo en una sola página"MUSE que esta conectado"(Canales/MCP/Complementos/Permisos de herramientas).
 *
 * Una tarjeta de navegación para cada elemento (resumen de estado en tiempo real + salto a la página de configuración existente), alineando la experiencia de administración de conexiones de Meta Muse:
 * La conexión y la autorización son visibles en un solo lugar en lugar de estar dispersas en varias subpáginas de configuración.
 */
@Composable
fun ConnectionCenterScreen(
    onBack: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenToolPermissions: () -> Unit,
) {
    val channelManager: io.zer0.muse.channel.ChannelManager = koinInject()
    val mcpRegistry: io.zer0.muse.mcp.McpRegistry = koinInject()
    val channels by channelManager.channels.collectAsStateWithLifecycle(initialValue = emptyList())
    val mcpServers by mcpRegistry.servers.collectAsStateWithLifecycle(initialValue = emptyList())

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.connection_center_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.0.1 fix(La máquina real falla): OPPO El contenido del andamio puede recibir infinitas restricciones de altura en rutas iguales de borde a borde.
        // El sistema Direct Column+verticalScroll/Lazy arrojará IllegalStateException.
        // Herede el modo antivibración existente en el campo de configuración:BoxWithConstraints + boundedSettingsScrollHeight La altura de cierre es limitada.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val boundedHeight = boundedSettingsScrollHeight(
                parentMaxHeight = maxHeight,
                windowHeight = LocalConfiguration.current.screenHeightDp.dp,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = boundedHeight),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.chat,
                        title = stringResource(R.string.connection_channels_title),
                        subtitle = stringResource(R.string.connection_channels_subtitle, channels.size),
                        onClick = onOpenChannels,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.share,
                        title = stringResource(R.string.connection_mcp_title),
                        subtitle = stringResource(R.string.connection_mcp_subtitle, mcpServers.size),
                        onClick = onOpenMcp,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.puzzle,
                        title = stringResource(R.string.connection_plugins_title),
                        subtitle = stringResource(R.string.connection_plugins_subtitle),
                        onClick = onOpenPlugins,
                    )
                }
                item {
                    ConnectionEntryCard(
                        icon = MuseIcons.shield,
                        title = stringResource(R.string.connection_tools_title),
                        subtitle = stringResource(R.string.connection_tools_subtitle),
                        onClick = onOpenToolPermissions,
                    )
                }
            }
        }
    }
}

/** v2.0.1: Tarjeta de navegación del centro de conexión — Icono + título + resumen de estado + flecha. */
@Composable
private fun ConnectionEntryCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    MuseSurface(
        onClick = onClick,
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
