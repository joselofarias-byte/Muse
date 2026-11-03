package io.zer0.muse.ui.common.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Botón de icono unificado en la barra superior de la página de chat:**icono desnudo**(Sin color de fondo del contenedor)+ 48dp Área táctil + ícono 24dp.
 *
 * ¿Por qué no convertirla en una cápsula de fondo redondo: la barra superior de otras páginas de la aplicación (`MuseTopBar`, todas las páginas de configuración) son siempre íconos desnudos,
 * Los tres círculos grises originales en la página de chat no coinciden con el idioma global y dificultan el regreso./Título/Más lucen igualmente pesados ​​y sin capas.
 * La barra superior es un lenguaje minimalista alineado con la página de configuración del sistema, sin isla flotante.
 *
 * La legibilidad de los íconos desnudos cuando se presionan contra el contenido que se desplaza está garantizada por la parte inferior degradada de la barra superior de la persona que llama (consulte `ChatTopBarScrim`),
 * No agregues sombras ni glaseado al botón.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun MuseTopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Utilice el color principal cuando sea necesario enfatizar el estado expandido; predeterminado(Color.Unspecified)Siga el color del contenido actual. */
    tint: Color = Color.Unspecified,
    /**
     * [solid] Cuando es verdadero, representa un fondo circular sólido (contenedor de color principal del tema)+ icono de inversión,
     * Haga que todos los botones en los que se puede hacer clic tengan el mismo calibre; use false para operaciones pequeñas en línea en filas de la lista para mantener los íconos desnudos y evitar dos círculos por fila.
     *
     * El contenedor toma tonal(primaryContainer) en lugar de un color principal muy saturado: la barra superior es la capa cromada de navegación,
     * El retorno puede aparecer simultáneamente en una pantalla/menú/Más tres rondas, la alta saturación abrumará el contenido de la página.
     */
    solid: Boolean = true,
    /** v2.6: patrón de isla de cristal —— true La carcasa del botón está hecha de vidrio.(backdrop/liquid Vidrio real o vidrio falso degradado),Los contenedores de colores sólidos pasan a un segundo plano frente a los colores base. */
    glassActive: Boolean = false,
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
) {
    val useGlass = glassActive && glassConfig.enabled && solid
    // v2.6: Sigue el color del contenido actual cuando el tinte no se especifica explícitamente.(LocalContentColor),
    // Haz que los íconos en la isla de cristal se adapten automáticamente al fondo claro y oscuro(GlassIsland Infundido con color contrastante).
    val resolvedTint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint
    if (useGlass) {
        // patrón de vidrio:cáscara entregada GlassIsland(isla redonda),Icono central,No dibujes contenedores de colores sólidos.
        io.zer0.muse.ui.theme.GlassIsland(
            config = glassConfig,
            shape = androidx.compose.foundation.shape.CircleShape,
            solidColor = MuseActionColors.tonalContainer,
            modifier = modifier.size(MuseIconSizes.touchTarget),
        ) {
            MuseTactileButton(
                icon = icon,
                onClick = onClick,
                contentDescription = contentDescription,
                enabled = enabled,
                container = MuseIconContainer.None,
                tint = resolvedTint,
                iconSize = MuseIconSizes.iconMedium,
                visualSize = MuseIconSizes.topBarSolid,
            )
        }
        return
    }
    MuseTactileButton(
        icon = icon,
        onClick = onClick,
        contentDescription = contentDescription,
        enabled = enabled,
        modifier = modifier,
        container = if (solid) MuseIconContainer.Tonal else MuseIconContainer.None,
        tint = if (tint == Color.Unspecified) MuseActionColors.tonalContent else tint,
        iconSize = if (solid) MuseIconSizes.iconMedium else MuseIconSizes.icon,
        visualSize = MuseIconSizes.topBarSolid,
    )
}

/**
 * La entrada unificada "Más" en el lado derecho de la barra superior: un ícono desnudo + un menú flotante en la esquina superior derecha (la posición de las tres en punto en la esquina superior derecha de la página).
 *
 * La página trata sus acciones como [items] Simplemente páselo y la apariencia del menú y la animación serán consistentes con "Contraer automáticamente después de hacer clic".
 * Cuando se expande, el botón muestra el icono de color del tema, de modo que se puede ver la relación de propiedad entre la capa flotante y el botón de origen.
 */
@Composable
internal fun MuseTopBarMenu(
    items: List<MuseFloatingActionItem>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val highlighted = expanded && enabled
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(MuseIconSizes.touchTarget)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MuseTopBarIconButton(
                icon = MuseIcons.moreVertical,
                contentDescription = contentDescription,
                onClick = { expanded = true },
                enabled = enabled,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MuseActionColors.tonalContent,
            )
        }
        if (expanded) {
            // v1.0.90: Mantenga el menú flotante en la esquina superior derecha de la barra superior (se cambió brevemente al panel inferior y se volvió a cambiar según los comentarios).
            MuseFloatingActionMenu(
                items = items.map { item ->
                    item.copy(
                        onClick = {
                            expanded = false
                            item.onClick()
                        },
                    )
                },
                onDismiss = { expanded = false },
            )
        }
    }
}

/**
 * Fondo degradado de la barra superior de la página de chat: el contenido se desplazará debajo de la barra superior y el ícono desnudo necesita un desvanecimiento muy superficial de arriba a abajo para garantizar la legibilidad.
 *
 * Utilice únicamente un degradado de tres etapas desde el color de fondo hasta el transparente, sin sombras ni vidrio esmerilado.——Cumple con el estándar de "la gradación depende de la diferencia de color del fondo".
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatTopBarScrim(
    modifier: Modifier = Modifier,
    glassActive: Boolean = false,
    glassConfig: io.zer0.muse.ui.theme.LiquidGlassConfig = io.zer0.muse.ui.theme.LiquidGlassConfig(),
) {
    val background = MaterialTheme.colorScheme.background
    // v2.6.4: La barra superior "Big Island" tiene una forma unificada. —— Independientemente del interruptor de cristal, se trata de la misma cápsula central de Oshima;
    // Sólo el material de relleno cambia con el interruptor (en=motor de vidrio, cerrado=color sólido).
    // Cuando se desactiva la lógica original, se dibuja una barra de degradado de ancho completo y la forma no coincide con el estado del vidrio (comentarios de los usuarios“No unificado”).
    // v2.6.10: Mantenga statusBarsPadding para evitar la barra de estado, pero elimine la capa de relleno vertical de 4dp.
    // La altura del cuadro principal ha sido determinada por la fila de contenido (también con statusBarsPadding) y el relleno vertical se reduce en un círculo.
    // Esto hará que el cristal sea 4dp más pequeño que la parte superior e inferior de la fila de botones, y los botones izquierdo y derecho quedarán expuestos fuera de la máscara;
    // Y eliminar completamente las inserciones hará que el vaso se trague la barra de estado y la cápsula de la barra superior se volverá anormalmente alta.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = MusePaddings.screen)
            .then(
                if (glassActive && glassConfig.enabled) {
                    io.zer0.muse.ui.theme.glassSurfaceModifier(
                        shape = CircleShape,
                        surfaceColor = background,
                        config = glassConfig,
                        backdrop = io.zer0.muse.ui.theme.LocalLayerBackdrop.current,
                        waterState = io.zer0.muse.ui.theme.LocalWaterGlassState.current,
                    )
                } else {
                    // Cierre de cristal: Isla grande de la misma forma y color sólido (un poco más profunda que la cápsula de Nakajima, lo que permite incrustar el Nakajima).
                    Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                },
            ),
    )
}
