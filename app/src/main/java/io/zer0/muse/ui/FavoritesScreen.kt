@file:Suppress("FunctionNaming", "CyclomaticComplexMethod")

package io.zer0.muse.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.MarkdownPreviewTruncation
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.3: Página de favoritos entre sesiones.
 *
 * - fuente de datos: [ChatViewModel.state.favoriteMessages](observeAllFavorites Agregados todos los mensajes de colección.)
 * - elemento de la lista: Personaje + modelo + marca de tiempo + vista previa de contenido(truncamiento consciente de la estructura)+ Cancelar botón favorito
 * - Representación de contenido: Usar directamente [MarkdownText](Bloques de código de soporte/clic en el enlace/Lista, etc.),Garantizado para ser consistente con la página de chat.
 * - Cancelar favoritos: sintonizar [ChatViewModel.toggleFavorite],Actualización optimista + sincronización DB
 *
 * diseño: warm-paper estilo de tarjeta(esquinas redondeadas 12dp,surfaceVariant fondo),De acuerdo con la tarjeta SettingsScreen.
 *
 * §11 Después de la convergencia, sólo**un conjunto**grupo:Definido por el usuario tag(favoriteTag columna).
 * Se ha eliminado el cuadro de diálogo de clasificación predeterminado.(Línea de chip predeterminada después de cortar el U-20,El valor predeterminado es sólo el token interno que se ejecuta desnudo en UI).
 * Valores `__group_*__` Cuando se lee el valor, se asigna a una copia localizada.(ver [favoriteTagLabel]),No cree la migración por separado.
 */
@Composable
fun FavoritesScreen(onBack: () -> Unit, viewModel: ChatViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val staleFavoriteIds by viewModel.staleFavoriteIds.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, Locale.getDefault()) }

    // v1.104 U7: Pulsación larga/Aparece al hacer clic en el botón de grupo."Configurar grupos"El mensaje de destino del cuadro de diálogo.
    var tagEditTarget by remember { mutableStateOf<UIMessage?>(null) }

    // v2.6.2(comentarios de los usuarios): Haga clic en la tarjeta → Vista previa del texto original en pantalla completa (anteriormente, onClick estaba ocupado por el cuadro de diálogo de grupo,
    // Usuario“No puedo hacer clic para leer el texto completo.”).
    var fullTextTarget by remember { mutableStateOf<UIMessage?>(null) }

    // La lista de favoritos que se muestra bajo el filtro actual.(filtrado local,No afecta el volumen total de state.favoriteMessages)
    val visibleFavorites = remember(state.favoriteMessages, state.favoriteTagFilter) {
        when (state.favoriteTagFilter) {
            null -> state.favoriteMessages
            ChatViewModel.FAVORITE_TAG_UNGROUPED -> state.favoriteMessages.filter { it.favoriteTag == null }
            else -> state.favoriteMessages.filter { it.favoriteTag == state.favoriteTagFilter }
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.favorites_title, state.favoriteMessages.size),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // U-20: Conserve únicamente filas de etiquetas personalizadas para el filtrado jerárquico.
            // §11-1: Se ha eliminado el cuadro de diálogo de agrupación predeterminado. —— Lo que escribe es `__group_*__` interno token,
            // Y la fila de chips predeterminada no existe.,Como resultado, el token se expone directamente en la línea de etiqueta personalizada. Ahora solo hay una entrada al grupo.:
            // en la tarjeta"Configurar grupos"botón → El nombre del grupo ingresado por el usuario.
            if (state.favoriteMessages.isNotEmpty()) {
                val tagCounts = remember(state.favoriteMessages) {
                    state.favoriteMessages.groupBy { it.favoriteTag }.mapValues { it.value.size }
                }
                FavoriteTagFilterRow(
                    allCount = state.favoriteMessages.size,
                    ungroupedCount = tagCounts[null] ?: 0,
                    tags = state.favoriteTags,
                    tagCounts = tagCounts,
                    currentFilter = state.favoriteTagFilter,
                    onFilterChange = { viewModel.setFavoriteTagFilter(it) },
                )
            }
            // v1.77: Se muestra en la primera carga loading,Evite el estado flash
            if (state.isFavoritesLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseSpinner()
                }
            } else if (state.favoritesError != null) {
                // ST-01: Estado de error fallido al cargar la lista de favoritos + inténtelo de nuevo
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseErrorStateBox(
                        message = state.favoritesError.orEmpty(),
                        onRetry = { viewModel.retryLoadFavorites() },
                    )
                }
            } else if (state.favoriteMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.star,
                        title = stringResource(R.string.favorites_empty_title),
                        subtitle = stringResource(R.string.favorites_empty_subtitle),
                    )
                }
            } else if (visibleFavorites.isEmpty()) {
                // v1.104 U7: No hay favoritos en este grupo.(diferente de"No hay favoritos en absoluto")
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseEmptyState(
                        icon = MuseIcons.star,
                        title = stringResource(R.string.favorites_empty_in_filter),
                        subtitle = null,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleFavorites, key = { it.id }) { msg ->
                        Box(modifier = museAnimateItem()) {
                            FavoriteCard(
                                message = msg,
                                dateFormat = dateFormat,
                                isStaleVariant = msg.id in staleFavoriteIds,
                                onUnfavorite = { viewModel.toggleFavorite(msg.id) },
                                onLongClick = { tagEditTarget = msg },
                                // v2.6.2: Haga clic para leer el texto completo; la entrada del grupo es el botón en la tarjeta
                                onClick = { fullTextTarget = msg },
                                onGroupClick = { tagEditTarget = msg },
                            )
                        }
                    }
                }
            }
        }
    }

    // v1.104 U7: Establecer diálogo de agrupación (§11-1 El único puerto de escritura después de la convergencia: etiqueta definida por el usuario)
    tagEditTarget?.let { msg ->
        FavoriteTagEditDialog(
            currentTag = msg.favoriteTag,
            onConfirm = { newTag ->
                viewModel.setMessageFavoriteTag(msg.id, newTag)
                tagEditTarget = null
            },
            onDismiss = { tagEditTarget = null },
        )
    }

    // v2.6.2(comentarios de los usuarios): Vista previa del texto original en pantalla completa —— Haga clic en la tarjeta favorita para abrirla y desplácese para ver el contenido completo.
    fullTextTarget?.let { msg ->
        FavoriteFullTextDialog(message = msg, dateFormat = dateFormat, onDismiss = { fullTextTarget = null })
    }
}

/**
 * El estado visual seleccionado de la estrella de colección (§11-8).
 *
 * MuseIcons Sólo hay una pieza sólida en la colección. `star`, no `starOutline`, y este proyecto no permite esto
 * Recurso de icono de estado agregado. Entonces selecciona/Si no está seleccionado, se puede distinguir por la capacidad de tres marchas, sin cambiar el icono:
 *  1. Tono de icono: Favorito = primary(color de acento del tema), no recopilado = outline(color neutro)
 *  2. Contenedor de botones: para botones con color de fondo [MuseIconContainer.Tonal] expresar"abierto"(fondo redondo sólido)
 *  3. Cuando el icono se dibuja directamente (filtrado de momentos), el alfa se reduce adicionalmente a [FAVORITE_STAR_IDLE_ALPHA]
 * Una vez superpuestos los tres niveles, se puede ver de un vistazo entre los temas claros y oscuros./Cierra sin dibujar una nueva estrella de trazo.
 *
 * Lo que originalmente estaba escrito en todas partes fue `if (x) MuseIcons.star else MuseIcons.star`, dos ramas toman el mismo icono,
 * Esto significa que los bits de estado no participan en la representación y solo se distinguen por la coincidencia del color de redacción y del contenedor.
 */
internal const val FAVORITE_STAR_IDLE_ALPHA = 0.45f

/** Color de estrella favorito: seleccionado = Color de acento del tema, sin marcar = Contorno neutro. */
@Composable
internal fun favoriteStarTint(selected: Boolean): Color = if (selected) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.outline
}

/** Transparencia de estrella de colección: solo se usa en escenas donde el ícono se dibuja directamente (MuseTactileButton No hay icono de paso alfa). */
internal fun favoriteStarAlpha(selected: Boolean): Float = if (selected) 1f else FAVORITE_STAR_IDLE_ALPHA

/**
 * Mostrar copia para grupos de colección.
 *
 * §11-1: Guardado en datos históricos `__group_inspiration__` Este tipo de interna token(v2.0 Las categorías predeterminadas están incluidas en la biblioteca.).
 * De forma predeterminada, después de eliminar U-20 de la fila de chips, estas cadenas se muestran directamente en la fila de filtro y en la tarjeta. aquí en**al leer**
 * Asignar a recurso de cadena,Sin migración de datos:
 *  - El valor de las acciones aún se puede utilizar como clave de filtro.(El filtrado utiliza el valor original.,No mostrar copia)
 *  - El usuario cambia el nombre una vez,Lo nuevo es la personalización normal. tag
 *  - No escribas chino en migrar(La migración se ejecuta en la capa que no es UI,no puedo conseguirlo locale)
 */
@Composable
private fun favoriteTagLabel(tag: String): String = when (tag) {
    ChatViewModel.FAVORITE_GROUP_INSPIRATION -> stringResource(R.string.favorites_group_inspiration)
    ChatViewModel.FAVORITE_GROUP_CODE -> stringResource(R.string.favorites_group_code)
    ChatViewModel.FAVORITE_GROUP_LEARNING -> stringResource(R.string.favorites_group_learning)
    ChatViewModel.FAVORITE_GROUP_CUSTOM -> stringResource(R.string.favorites_group_custom)
    else -> tag
}

/**
 * v1.104 U7: Fila superior de FilterChip — Desplazamiento horizontal,mostrar"Todo/Desagrupado/Cada tag".
 *
 * - "Todos"mostrar siempre,Cuando se selecciona state.favoriteTagFilter = null
 * - "No agrupado"solo si existe favoriteTag=null Se muestra cuando se marca como favorito,Cuando se selecciona filter = FAVORITE_TAG_UNGROUPED
 * - Cada etiqueta es proporcionada por state.favoriteTags,DAO Se eliminó NULL / Deduplicación / Orden ascendente
 */
@Composable
private fun FavoriteTagFilterRow(
    allCount: Int,
    ungroupedCount: Int,
    tags: List<String>,
    tagCounts: Map<String?, Int>,
    currentFilter: String?,
    onFilterChange: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            MuseChip(
                selected = currentFilter == null,
                onClick = { onFilterChange(null) },
                label = stringResource(R.string.favorites_filter_all, allCount),
            )
        }
        if (ungroupedCount > 0) {
            item(key = "ungrouped") {
                MuseChip(
                    selected = currentFilter == ChatViewModel.FAVORITE_TAG_UNGROUPED,
                    onClick = { onFilterChange(ChatViewModel.FAVORITE_TAG_UNGROUPED) },
                    label = stringResource(R.string.favorites_filter_ungrouped, ungroupedCount),
                )
            }
        }
        items(tags, key = { it }) { tag ->
            MuseChip(
                selected = currentFilter == tag,
                onClick = { onFilterChange(tag) },
                label = stringResource(R.string.favorites_tag_chip, favoriteTagLabel(tag), tagCounts[tag] ?: 0),
            )
        }
    }
}

/**
 * v1.104 U7: Configure el cuadro de diálogo de agrupación.
 *
 * - Valor inicial del cuadro de entrada = currentTag(Disponible)
 * - botón Aceptar:Pase el valor del cuadro de entrada a onConfirm(trim La cadena vacía se trata como null = Mover a desagrupado)
 * - boton borrar grupo(Solo se muestra si currentTag no está vacío):Pasar nulo directamente a onConfirm
 * - Botón cancelar:Cerrar diálogo,no cambies nada
 */
@Composable
private fun FavoriteTagEditDialog(currentTag: String?, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(currentTag ?: "") }
    MuseFormDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.favorites_tag_edit_title),
        confirmText = stringResource(R.string.favorites_tag_edit_confirm),
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text) },
        dismissText = stringResource(R.string.favorites_tag_edit_cancel),
        onDismiss = onDismiss,
        content = {
            MuseTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.favorites_tag_edit_hint)) },
                singleLine = true,
            )
            if (!currentTag.isNullOrBlank()) {
                MuseCapsuleButton(
                    text = stringResource(R.string.favorites_tag_edit_clear),
                    onClick = { onConfirm(null) },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        },
    )
}

/**
 * Tarjeta de mensaje favorito único.
 *
 * v1.104 U7: Agregar devolución de llamada onLongClick,Mantenga presionado para reproducir"Configurar grupos"cuadro de diálogo;
 * Si message.favoriteTag no está vacío,Las etiquetas de agrupación aparecen en la parte inferior de la tarjeta.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun FavoriteCard(
    message: UIMessage,
    dateFormat: SimpleDateFormat,
    onUnfavorite: () -> Unit,
    onLongClick: () -> Unit,
    /** §11-4: Esta colección pertenece a una fila antigua de un grupo de variantes que ha sido reemplazada por una nueva versión. */
    isStaleVariant: Boolean = false,
    onClick: () -> Unit = {},
    /** v2.6.2: Entrada de grupo (muévete al botón de la tarjeta y dale al clic para“Leer el texto original”). */
    onGroupClick: () -> Unit = {},
) {
    val roleLabel = when (message.role) {
        MessageRole.USER -> stringResource(R.string.favorites_role_user)
        MessageRole.ASSISTANT -> "AI"
        MessageRole.SYSTEM -> stringResource(R.string.favorites_role_system)
        MessageRole.TOOL -> stringResource(R.string.favorites_role_tool)
    }
    val roleColor = when (message.role) {
        MessageRole.USER -> MaterialTheme.colorScheme.primary
        MessageRole.ASSISTANT -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    MuseSurface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // fila superior: Etiqueta de personaje + modelo + hora + botón cancelar favorito
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = roleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = roleColor,
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = roleColor,
                                shape = MuseShapes.extraSmall,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    message.modelId?.takeIf { it.isNotBlank() }?.let { model ->
                        Text(
                            text = model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // §11-4: Esta colección es una fila antigua en un grupo de variantes que ha sido reemplazada por una nueva versión. —— decir la verdad,
                    // No cambie a la última versión sin autorización(Los saltos de línea harán que los no favoritos caigan en una línea de no favoritos).
                    if (isStaleVariant) {
                        Text(
                            text = stringResource(R.string.favorites_stale_variant_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // LAYOUT-01: El espacio de metainformación final (hora + botón favorito) está organizado según el ancho del contenido, solo en filas y no en ancho.
                    // De lo contrario, la fila interior consumirá el ancho de la fila exterior y el carácter izquierdo./Las etiquetas de los modelos se comprimen verticalmente.
                    Text(
                        text = dateFormat.format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTactileButton(
                        icon = MuseIcons.star,
                        onClick = onUnfavorite,
                        contentDescription = stringResource(R.string.favorites_unfavorite_cd),
                        // §11-8: Se han recopilado todas las cartas de la página de colección y la marca de estrella está seleccionada visualmente (color principal + fondo redondo sólido).
                        // No más colores secundarios intensos —— tomar uniformemente [favoriteStarTint] Un calibre.
                        tint = favoriteStarTint(selected = true),
                        container = MuseIconContainer.Tonal,
                        size = MuseIconSizes.touchTarget,
                    )
                    // v2.6.2: Entrada de grupo (originalmente ocupaba todo el clic de la tarjeta, ahora reducido a un pequeño botón)
                    MuseTactileButton(
                        icon = MuseIcons.bookmark,
                        onClick = onGroupClick,
                        contentDescription = stringResource(R.string.favorites_group_action_cd),
                        tint = MaterialTheme.colorScheme.outline,
                        size = MuseIconSizes.touchTarget,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Vista previa del contenido: Consciente de la estructura truncado a aproximadamente 280 palabras(§11-9).
            // v2.6.8: Tome la salida de la pantalla primero y luego córtela. —— De lo contrario, cardId/JSON aparecerá en la vista previa del mensaje de la tarjeta.
            // Usar primero take(280) El corte duro corta las barreras de código/caracteres de énfasis emparejados / [N] Cotización,
            // Al abrir la valla, todo lo que está después de los puntos suspensivos se representa como un bloque de código.
            val body = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(message.content)
            val preview = remember(body) { MarkdownPreviewTruncation.preview(body) }
            MarkdownText(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                // Phase 8.4: UrlCitation — La vista previa de favoritos también es compatible [N] Haga clic en cotizar
                citationUrls = message.citationUrls,
            )
            // v1.104 U7: Mostrar etiquetas de agrupación en la parte inferior de la tarjeta(Si es así)
            message.favoriteTag?.takeIf { it.isNotBlank() }?.let { tag ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.favorites_tag_label, favoriteTagLabel(tag)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * v2.6.2(comentarios de los usuarios): Recopile la vista previa en pantalla completa del texto original.
 *
 * Haga clic en la tarjeta favorita para abrirla, desplácese para mostrar el texto completo (sin truncamiento) y reutilice la misma representación de MarkdownText que la página de chat.
 */
@Composable
private fun FavoriteFullTextDialog(message: UIMessage, dateFormat: SimpleDateFormat, onDismiss: () -> Unit) {
    val roleLabel = when (message.role) {
        MessageRole.USER -> stringResource(R.string.favorites_role_user)
        MessageRole.ASSISTANT -> "AI"
        MessageRole.SYSTEM -> stringResource(R.string.favorites_role_system)
        MessageRole.TOOL -> stringResource(R.string.favorites_role_tool)
    }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = roleLabel,
        dismissText = stringResource(R.string.action_close),
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    message.modelId?.takeIf { it.isNotBlank() }?.let { model ->
                        Text(
                            text = model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = dateFormat.format(Date(message.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Texto completo, desplazable (no truncado)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(
                        text = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(message.content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        citationUrls = message.citationUrls,
                    )
                }
            }
        },
    )
}
