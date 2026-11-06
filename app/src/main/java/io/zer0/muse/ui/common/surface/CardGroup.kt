package io.zer0.muse.ui.common.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastForEachIndexed
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard

/**
 * v0.34 / v1.0.26: Diseño de componentes de agrupación de tarjetas.(Muse UI Kit reescribir).
 *
 * uno [CardGroup] múltiples dentro [item] Comparte el mismo contenedor de tarjetas redondeado.:
 *  - Esquinas redondeadas en la parte superior del primer elemento 20dp,Esquina inferior redondeada del último elemento 20dp,Empalme en ángulo recto del elemento medio
 *  - Título de grupo opcional(primary color + títuloFuente pequeña)
 *  - item utilizado entre [MuseDivider] separar(56dp sangría,Alinear la configuración del sistema)
 *
 * v1.0.26 Refactorizar:
 *  - contenedor en lugar [MuseSurface] Primitivo,Procesamiento unificado de clip/color/sombra
 *  - Uso de elementos de lista [MuseListItem] Primitivo,Unificar relleno/tamaño de fuente/color
 *  - Línea de separación en su lugar [MuseDivider] Primitivo,Unificar espesor/color/sangría
 *  - Eliminar la animación de esquinas redondeadas incorporada de ListItem(El elemento del medio ya no está recortado y tiene esquinas redondeadas.,Recortado uniformemente por la MuseSurface exterior)
 *
 * Uso(DSL estilo):
 * ```
 * CardGroup(
 *     title = { Text("Universales") },
 *     modifier = Modifier.padding(horizontal = 16.dp),
 * ) {
 *     item(
 *         onClick = { navController.navigate(...) },
 *         leadingContent = { Icon(MuseIcons.sun, null) },
 *         headlineContent = { Text("modo oscuro") },
 *         supportingContent = { Text("Sigue el sistema") },
 *         trailingContent = { Switch(checked=..., onCheckedChange=...) },
 *     )
 * }
 * ```
 *
 * adaptado a musa Material Icons(en vez de HugeIcons)y temas de papel caliente.
 */
private data class CardGroupItem(
    val key: Any?,
    val onClick: (() -> Unit)?,
    val onLongClick: (() -> Unit)?,
    val modifier: Modifier,
    val headlineContent: @Composable () -> Unit,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    /**
     * Un artículo dentro de un grupo de tarjetas.
     *
     * @param onClick Haga clic en devolución de llamada(null no se puede hacer clic)
     * @param onLongClick Devolución de llamada con pulsación larga(Opcional,El menú emergente de pulsación larga se pasa al mismo tiempo que onClick)
     * @param headlineContent título principal(Requerido)
     * @param supportingContent subtítulo(pequeñas letras grises,Opcional)
     * @param leadingContent Contenido a la izquierda(normalmente un icono,Opcional)
     * @param trailingContent Contenido correcto(Switch / Flecha / Seleccionar / Control deslizante, etc.,Opcional)
     * @param modifier de este artículo Modifier(Predeterminado Modifier)
     */
    fun item(
        key: Any? = null,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        headlineContent: @Composable () -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        key: Any?,
        onClick: (() -> Unit)?,
        onLongClick: (() -> Unit)?,
        modifier: Modifier,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                key = key,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
            ),
        )
    }
}

/**
 * v0.34 / v1.0.26: Contenedor de agrupación de tarjetas(Muse UI Kit reescribir).
 *
 * Consulte la documentación en la parte superior del archivo para conocer su uso. todos [content] dentro [CardGroupScope.item] se recogerán las llamadas,
 * Representado como una baraja de cartas que comparte un fondo redondeado.
 *
 * @param modifier cubierta entera(con titulo)de Modifier
 * @param title Título de grupo opcional(primary Color,Mostrar tarjeta de arriba)
 * @param content DSL bloque de contenido,Para uso interno [CardGroupScope.item] Agregar elementos
 */
@Composable
fun CardGroup(modifier: Modifier = Modifier, title: (@Composable () -> Unit)? = null, content: CardGroupScope.() -> Unit) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        // El título está fuera de la tarjeta.,No afectado por clips de tarjetas
        // v1.0.27 Reparación:Alinear el encabezado del grupo de configuración del sistema — Fuentes grises pequeñas, espacios reducidos
        if (title != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Box(
                        modifier = Modifier
                            // Alinee el título del grupo con el borde interior de la tarjeta.,No se supone ninguna sangría adicional para el ancho del icono.
                            .padding(
                                start = MusePaddings.screen,
                                end = MusePaddings.screen,
                                top = MusePaddings.cardGap,
                                bottom = MusePaddings.contentGap,
                            )
                            .fillMaxWidth(),
                    ) {
                        title()
                    }
                }
            }
        }

        // contenedor de tarjetas:Unificar con primitivas de MuseSurface clip/color/shadow
        // v1.0.27 Reparación:fondo cambiado a surface(blanco puro/blanco cálido),Cancelar sombra — curar"Empalme de bloques grises"ilusión
        // v2.0.1 biplano:El nivel consiste en"color de fondo(Profundidad) vs Cara de la tarjeta(blanco)"La diferencia de brillo lleva——
        // Elimina el trazo y reduce la sombra a micro,ya no se usa"línea"y"sombra"Capas.
        MuseSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.largeCard,
            color = MaterialTheme.colorScheme.surface,
            elevation = MuseElevation.micro,
            tonalElevation = MuseElevation.none,
            // El contenedor en sí no responde a la prensa.,Los comentarios de prensa son manejados internamente por MuseListItem
            enablePressedFeedback = false,
        ) {
            Column {
                val count = scope.items.size
                scope.items.fastForEachIndexed { index, item ->
                    // Envolver con llave,Crear elementos de lista con clave agregada y eliminada/Recuerda recordar el estado al refluir(Como fuente interactiva, estado expandido)
                    key(item.key ?: index) {
                        MuseListItem(
                            onClick = item.onClick,
                            onLongClick = item.onLongClick,
                            modifier = item.modifier,
                            headlineContent = item.headlineContent,
                            supportingContent = item.supportingContent,
                            leadingContent = item.leadingContent,
                            trailingContent = item.trailingContent,
                        )
                    }
                    // Separar con primitivas MuseDivider item,Unificar espesor/color/sangría
                    if (index != count - 1) {
                        MuseDivider()
                    }
                }
            }
        }
    }
}
