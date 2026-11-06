@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions")

package io.zer0.muse.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * v0.29 P0-1: guía de chat vacía — Mensaje centrado ligero + cápsula de mensaje de sugerencia.
 *
 * diseño(Estado vacío):
 *  - No cubre pantalla completa(Box + CenterAlignment,Ocupa solo la zona central,no interceptar InputBar)
 *  - Ícono de marca centrado + oración principal
 *  - abajo"Sin referencia a la memoria"cambiar la cápsula
 *  - v2.2.0: Las sugerencias de eliminación indican cápsula con"Cambiar un lote"(Según los requisitos del usuario.)
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun EmptyChatGuide(
    modifier: Modifier = Modifier,
    assistant: io.zer0.muse.data.assistant.AssistantEntity? = null,
    // v1.0.72: Esta sesión no se refiere a cambios de memoria.
    ignoreMemory: Boolean = false,
    onToggleIgnoreMemory: (Boolean) -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.screen),
    ) {
        // avatar:El asistente tiene un avatar personalizado.(fotos o Emoji)Usar avatar asistente,De lo contrario, utilice el icono del proyecto.
        val currentAssistant = assistant
        val avatarCd = if (currentAssistant != null) {
            stringResource(R.string.chat_avatar_assistant_cd)
        } else {
            stringResource(R.string.chat_avatar_muse_cd)
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .clearAndSetSemantics {
                    contentDescription = avatarCd
                },
            contentAlignment = Alignment.Center,
        ) {
            if (currentAssistant != null &&
                (currentAssistant.hasImageAvatar() || currentAssistant.avatarEmoji.isNotBlank())
            ) {
                io.zer0.muse.ui.common.media.AssistantAvatar(
                    assistant = currentAssistant,
                    avatarSize = MuseAvatarSize.detail,
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_muse_logo),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
        // Palabras introductorias
        Text(
            text = stringResource(R.string.chat_empty_guide_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v2.2.0: Eliminar sugerencias mediante cápsula de solicitud de usuario con"Cambiar un lote"(manténgase abajo"Sin referencia a la memoria"cambiar)

        // v1.0.72: Esta conversación no se refiere a la memoria. — Opción de cápsula encendida/apagada
        // Después de abrir, no se inyectará ninguna memoria en esta sesión.(Retrato de usuario/fijar hacia arriba/memoria a largo plazo/Memoria de chat grupal/Biblioteca de experiencias),empezar desde cero
        Surface(
            onClick = { onToggleIgnoreMemory(!ignoreMemory) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
            color = if (ignoreMemory) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = MusePaddings.contentGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // punto de estado del interruptor(encender = color principal sólido,cerrar = hueco)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (ignoreMemory) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            },
                            shape = CircleShape,
                        ),
                )
                Text(
                    // Los puntos son solo marcadores de estado y la palabra "abierto" está escrita explícitamente en el texto./apagado,
                    // Esto evita que los usuarios piensen en los puntos grises como controles en los que se puede hacer clic y no estén seguros del estado actual.
                    text = stringResource(
                        if (ignoreMemory) {
                            R.string.chat_ignore_memory_option_on
                        } else {
                            R.string.chat_ignore_memory_option_off
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ignoreMemory) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Tarea 2B: marcador de posición de burbuja de pantalla de esqueleto brillante.
 *
 * v1.0.3 mejorar:
 *  - Agrega tres puntos saltadores en la parte superior + "Pensando"texto,Ofrezca a los usuarios comentarios semánticos claros(Al brillo original le faltan indicaciones de texto)
 *  - Tres filas de esquinas redondeadas combinadas con un amplio degradado de izquierda a derecha,crear"AI escribiendo"sentido de la respiración
 *  - "Pensando"Texto con animación de respiración por pulso.(alpha 0.5↔1.0),Más dinámico que el texto estático
 *  - diseño general: [Animación de tres puntos.] / "Pensando" / [Tres filas de tiras brillantes.]
 * v1.0.4 mejorar:
 *  - Nuevo [progressText] parámetros,Visualización de la etapa de análisis visual"Analizando imágenes 2/4…"Esperando texto de progreso(sustituto"Pensando")
 */
@Composable
internal fun ThinkingBubble(progressText: String? = null) {
    // Solo quedan dos capas en la etapa de espera: una forma de tinta que se deforma + una copia estática.
    //
    // La versión anterior es una superposición de tres capas (tres puntos de salto, respiración independiente del texto y tres líneas de barras de esqueleto amplias), y estos tres lugares ya no se conservan:
    //  - Dos ciclos no relacionados se apilan para formar un ruido visual continuo;
    //  - La barra de esqueleto implica que "pronto aparecerán tres líneas de texto de igual ancho" y se desconocen la longitud y la forma de la respuesta.
    //    El primer cuadro de texto real también saltará cuando llegue.
    val reduced = MuseMotion.isReducedMotion()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Spacer(Modifier.width(32.dp))
        io.zer0.muse.ui.common.animation.MuseInkGlyph(
            modifier = Modifier.size(io.zer0.muse.ui.common.animation.MuseThinkingGlyphSize),
            animate = !reduced,
        )
        Spacer(Modifier.width(MusePaddings.tinyGap))
        Text(
            text = progressText ?: stringResource(R.string.chat_loading_thinking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ImageGenerationPlaceholder() {
    if (MuseMotion.isReducedMotion()) {
        StaticGenerationPlaceholder(
            icon = MuseIcons.image,
            text = stringResource(R.string.chat_image_generating),
        )
        return
    }
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    )
    val transition = rememberInfiniteTransition(label = "image_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "image_shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // v1.0.4 (P2): Los tres puntos superiores saltan(Reutiliza la misma animación de ThinkingBubble,Cambiar etiqueta para evitar conflictos)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(3) { index ->
                    val dotScale by transition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = MuseMotion.tween(
                                MuseAnimation.LOOP_NORMAL_MS,
                                delayMillis = minOf(index, MuseAnimation.STAGGER_MAX_INDEX) * MuseAnimation.STAGGER_STEP_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "img_dot_scale_$index",
                    )
                    val dotAlpha by transition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = MuseMotion.tween(
                                MuseAnimation.LOOP_NORMAL_MS,
                                delayMillis = minOf(index, MuseAnimation.STAGGER_MAX_INDEX) * MuseAnimation.STAGGER_STEP_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "img_dot_alpha_$index",
                    )
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .then(
                                Modifier.graphicsLayer {
                                    scaleX = dotScale
                                    scaleY = dotScale
                                    alpha = dotAlpha
                                },
                            )
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Spacer(Modifier.height(MusePaddings.contentGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .clip(MuseShapes.semiLarge)
                    .background(brush),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_image_generating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** reduced-motion Imágenes debajo/Marcador de posición de generación de vídeo,Mantiene la semántica pero no emite brillo. */
@Composable
private fun StaticGenerationPlaceholder(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Spacer(Modifier.width(32.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 180.dp)
                .clip(MuseShapes.semiLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * v1.0.4 (P1): Tarjeta marcador de posición en generación de vídeo.
 *
 * con [ImageGenerationPlaceholder] simetria:brillo de rectángulo redondeado + icono de reproducción + "Generando vídeo,Puede tardar decenas de segundos…"redacción,
 * Permita que los usuarios perciban claramente el progreso de tareas largas cuando LLM llame a la herramienta generate_video dentro del proceso de chat principal.
 * (execGenerateVideo Internamente, también se sincronizará en la burbuja de mensajes del asistente a través de updateAssistant."Esperó N segundos…",Este marcador de posición es para comentarios complementarios.)
 */
@Composable
internal fun VideoGenerationPlaceholder() {
    if (MuseMotion.isReducedMotion()) {
        StaticGenerationPlaceholder(
            icon = MuseIcons.play,
            text = stringResource(R.string.chat_video_generating),
        )
        return
    }
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    )
    val transition = rememberInfiniteTransition(label = "video_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "video_shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
    ) {
        Spacer(Modifier.width(32.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .clip(MuseShapes.semiLarge)
                    .background(brush),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Icon(
                        imageVector = MuseIcons.play,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_video_generating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * v1.0.4 (P1): El historial carga más barras de marcador de posición superiores.
 *
 * Después de deslizarse hacia arriba para activar la cargaMásHistoria,LazyColumn Inserta este marcador de posición en la parte superior,dejar que los usuarios vean"Cargando más historia…"comentario.
 * con [ThinkingBubble] estilo consistente(Tres líneas de tiras brillantes redondeadas + redacción publicitaria),Pero quita los puntos y las burbujas.,Forme tiras finas encima.
 * marcador de posición en isLoadingMore=true visualización de la hora,Cargando completada(lastHistoryLoadCount > 0)Posteriormente eliminado por scrollToItem.
 */
@Composable
internal fun HistoryLoadMorePlaceholder() {
    if (MuseMotion.isReducedMotion()) {
        StaticHistoryLoadMorePlaceholder()
        return
    }
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    )
    val transition = rememberInfiniteTransition(label = "load_more_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "load_more_shimmer",
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim + 200f, 0f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MuseSpinner(
            size = MusePaddings.screen,
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.chat_loading_more_history),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(MuseShapes.extraSmall)
                .background(brush),
        )
    }
}

/** reduced-motion El mensaje estático de carga del historial a continuación. */
@Composable
private fun StaticHistoryLoadMorePlaceholder() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInnerSpaced),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MuseSpinner(
            size = MusePaddings.screen,
        )
        Spacer(Modifier.width(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.chat_loading_more_history),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * separador de fecha — línea delgada + texto de fecha centrado,Se utiliza para agrupar listas de mensajes por días.
 * Pantalla de noticias de hoy“hoy HH:mm”,Otras fechas mostradas“MM-dd HH:mm”,El color es más claro.
 */
@Composable
internal fun DateSeparator(timestamp: Long) {
    val now = System.currentTimeMillis()
    val isToday = remember(timestamp) { isSameDay(timestamp, now) }
    val dateText = remember(timestamp, isToday) {
        val timeSdf = java.text.SimpleDateFormat(MuseDateFormats.TIME_SHORT, java.util.Locale.getDefault())
        val timeText = timeSdf.format(java.util.Date(timestamp))
        if (isToday) {
            "hoy $timeText"
        } else {
            val dateSdf = java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_SHORT, java.util.Locale.getDefault())
            dateSdf.format(java.util.Date(timestamp))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        MuseDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )
        Text(
            text = dateText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
        MuseDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )
    }
}

/**
 * Determine si dos marcas de tiempo corresponden al mismo día.
 */
internal fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = ts1 }
    val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = ts2 }
    return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
        cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
}

/** B7-01: Barra de operación de selección múltiple de mensajes — charla privada/El chat grupal comparte componentes unificados(planear A4). */
@Composable
internal fun ChatSelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onExit: () -> Unit,
    /** v2.6.9(comentarios de los usuarios): Colección de mensajes seleccionados en lotes. */
    onFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // v2.x unificación: Las acciones en línea convergen en el menú "Más"(Evite que las columnas de texto de la pantalla estrechas sean comprimidas por varios botones)
    val moreCd = stringResource(R.string.settings_mcp_more)
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MusePaddings.contentGap,
                vertical = MusePaddings.tightGap,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.groupchat_selected_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.action_copy),
                onClick = onCopy,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = moreCd,
                onClick = { menuExpanded = true },
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
        }
    }

    if (menuExpanded) {
        MuseDialog(
            onDismissRequest = { menuExpanded = false },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MuseActionSheetRow(
                        icon = MuseIcons.check,
                        text = stringResource(R.string.groupchat_select_all),
                        onClick = {
                            menuExpanded = false
                            onSelectAll()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.star,
                        text = stringResource(R.string.chat_favorite_selected_action),
                        onClick = {
                            menuExpanded = false
                            onFavorite()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.share,
                        text = stringResource(R.string.action_share),
                        onClick = {
                            menuExpanded = false
                            onExport()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.trash,
                        text = stringResource(R.string.action_delete),
                        contentColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                    MuseActionSheetRow(
                        icon = MuseIcons.x,
                        text = stringResource(R.string.action_close),
                        onClick = {
                            menuExpanded = false
                            onExit()
                        },
                    )
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = { menuExpanded = false },
            onDismiss = { menuExpanded = false },
        )
    }
}

/**
 * v2.6.9(comentarios de los usuarios): Barra de entrada "Cargar mensajes anteriores".
 *
 * Comentarios de los usuarios“Hay algunos pequeños carteles para pasar páginas, otros no, y quiero ver los mensajes anteriores pero no puedo.”——La causa fundamental es que históricamente sólo en
 * isLoadingMore=true El marcador de posición de brillo se muestra en este momento, es decir, solo es visible en el momento en que "se ha activado la carga";
 * Por lo general, la parte superior está completamente en blanco y los usuarios no saben si pueden desplazarse hacia arriba y no hay nada en lo que hacer clic.
 *
 * Inventa uno aquí**Portal permanente en el que se puede hacer clic**: Se muestra siempre que haya un historial anterior,
 * Haz clic en él o continúa deslizándote hacia arriba para cargar. El estilo es intencionalmente más claro que el mensaje (barras delgadas + texto de bajo contraste).
 * No robes el peso visual del texto.
 */
@Composable
internal fun HistoryLoadMoreEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MuseShapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MuseIcons.arrowUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_load_earlier_messages),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
