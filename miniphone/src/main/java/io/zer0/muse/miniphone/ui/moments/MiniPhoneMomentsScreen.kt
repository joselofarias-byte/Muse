package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatAvatar
import io.zer0.muse.miniphone.ui.chat.MiniPhoneGalleryViewer
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Entrada a la página de momentos:toma el tuyo ViewModel,Entregue la clave de devolución al sistema.
 *
 * Cada uno de los dos modos utiliza su propio ViewModel:[onlyMine] Verifique solo las actualizaciones de los usuarios locales.("Mis Momentos" en mi página),
 * De lo contrario ver todos. Usar el modo como clave ViewModel —— Dos entradas no pueden compartir la misma ventana de línea de tiempo.
 *
 * El estado de la página solo se recopila aquí,Los siguientes son todos componentes sin estado. —— Pruebe una porción [MomentsUiState] representará la página completa.
 */
@Composable
internal fun MiniPhoneMomentsRoute(onlyMine: Boolean = false, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: MiniPhoneMomentsViewModel = koinViewModel(key = if (onlyMine) MINE_KEY else ALL_KEY) { parametersOf(onlyMine) }
    val state by viewModel.uiState.collectAsState()
    BackHandler(onBack = onBack)
    MiniPhoneMomentsScreen(
        state = state,
        onBack = onBack,
        actions = MomentActions(
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onPublish = viewModel::publish,
            onToggleLike = viewModel::toggleLike,
            onComment = viewModel::comment,
            onDelete = viewModel::deleteMoment,
            onDeleteComment = viewModel::deleteComment,
        ),
        modifier = modifier,
        onlyMine = onlyMine,
    )
}

/**
 * Todas las devoluciones de llamada en la página.
 *
 * Reúnalos en lugar de usarlos como parámetros uno por uno.:Hay seis acciones en esta página.,La transmisión dispersa hará que la lista de parámetros sea más larga que el contenido.,
 * agregar una acción(Cambiar rango visible, adelante)Ya no tienes que tocar a todas las personas que llaman.
 */
@Immutable
internal class MomentActions(
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onPublish: (String, List<Uri>, Boolean) -> Unit = { _, _, _ -> },
    val onToggleLike: (String) -> Unit = {},
    val onComment: (String, String?, String) -> Unit = { _, _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onDeleteComment: (String, String) -> Unit = { _, _ -> },
)

/**
 * Página de momentos:Barra superior + portada + línea de tiempo,En la parte superior se encuentran la página de publicación y la página de visualización de imágenes.
 *
 * apátrida:Los estados y las devoluciones de llamadas provienen del exterior.[composing] con [viewingUri] Es estado de vista pura
 * (¿La página de publicación está abierta o no? ¿Qué foto estás mirando?),Descartar después de cerrar la página,No entrar [MomentsUiState].
 *
 * @param onlyMine Modo "Mis Momentos":Cambie el título por el suyo propio y cambie el mensaje vacío a alguien que pueda enviar uno.
 *   Las actualizaciones de otras personas han sido bloqueadas en la capa de consulta.,Aquí no se realiza más filtrado de memoria.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiniPhoneMomentsScreen(
    state: MomentsUiState,
    onBack: () -> Unit,
    actions: MomentActions,
    modifier: Modifier = Modifier,
    onlyMine: Boolean = false,
) {
    val colors = MiniPhoneTheme.colors
    var composing by rememberSaveable { mutableStateOf(false) }
    // Vista de galería:¿Qué grupo de imágenes comienza con qué imagen?(base cero). Almacene los dos valores por separado.,saveable Se puede llevar directamente contigo.
    var viewingImages by rememberSaveable { mutableStateOf(listOf<String>()) }
    var viewingIndex by rememberSaveable { mutableIntStateOf(0) }
    val viewing = viewingImages.isNotEmpty()
    // Cuando la página de la imagen está cubierta en la parte superior,Presione la tecla de retorno para cerrarlo primero.
    BackHandler(enabled = viewing) { viewingImages = emptyList() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneMomentsTags.ROOT),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            MomentsTopBar(onBack = onBack, title = if (onlyMine) MINE_PAGE_TITLE else PAGE_TITLE, onCompose = { composing = true })
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = actions.onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MomentsTimeline(
                    state = state,
                    actions = actions,
                    onlyMine = onlyMine,
                    onOpenImage = { images, index ->
                        viewingImages = images
                        viewingIndex = index
                    },
                )
            }
        }
        if (composing) {
            MomentPublishScreen(
                onClose = { composing = false },
                onPublish = { content, uris, onlySelf ->
                    actions.onPublish(content, uris, onlySelf)
                    composing = false
                },
            )
        }
        if (viewing) {
            MiniPhoneGalleryViewer(
                uris = viewingImages,
                initialIndex = viewingIndex,
                onDismiss = { viewingImages = emptyList() },
            )
        }
    }
}

/** barra superior:Retorno, título, entrada editorial en la esquina superior derecha. */
@Composable
private fun MomentsTopBar(onBack: () -> Unit, title: String, onCompose: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TopBarButtonSize)
                    .clip(RoundedCornerShape(TopBarCorner))
                    .clickable(onClick = onBack)
                    .testTag(MiniPhoneMomentsTags.BACK_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = BACK_DESCRIPTION,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(TopBarIconSize),
                )
            }
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            Box(
                modifier = Modifier
                    .size(TopBarButtonSize)
                    .clip(RoundedCornerShape(TopBarCorner))
                    .clickable(onClick = onCompose)
                    .testTag(MiniPhoneMomentsTags.COMPOSE_ENTRY),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Pen,
                    contentDescription = COMPOSE_DESCRIPTION,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(TopBarIconSize),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/**
 * línea de tiempo:Empezando por la portada,A continuación se muestran tarjetas dinámicas.
 *
 * La portada se coloca en la lista en lugar de fijarse en la parte superior.:Cuando te desplazas hacia abajo, sigue,El área de contenido no está devorada permanentemente en más de cien píxeles.
 */
@Composable
private fun MomentsTimeline(state: MomentsUiState, actions: MomentActions, onlyMine: Boolean, onOpenImage: (List<String>, Int) -> Unit) {
    val dimens = MiniPhoneTheme.dimens
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneMomentsTags.LIST),
    ) {
        item(key = COVER_KEY, contentType = COVER_KEY) {
            MomentsCover(
                coverImageUri = state.coverImageUri,
                selfName = state.selfName,
                selfAvatarUri = state.selfAvatarUri,
            )
        }
        if (state.moments.isEmpty()) {
            item(key = EMPTY_KEY, contentType = EMPTY_KEY) {
                MomentsEmptyState(isLoaded = state.isLoaded, onlyMine = onlyMine)
            }
        }
        items(items = state.moments, key = { moment -> moment.id }, contentType = { MOMENT_CONTENT_TYPE }) { moment ->
            MomentCardView(
                moment = moment,
                onToggleLike = { actions.onToggleLike(moment.id) },
                onOpenImage = onOpenImage,
                onComment = { parentId, content -> actions.onComment(moment.id, parentId, content) },
                onDelete = { actions.onDelete(moment.id) },
                modifier = Modifier.padding(horizontal = dimens.pageMargin, vertical = CardVerticalGap),
                onDeleteComment = { commentId -> actions.onDeleteComment(moment.id, commentId) },
            )
        }
        if (state.isLoadingMore) {
            item(key = LOADING_KEY, contentType = LOADING_KEY) { ListFootNote(text = LOADING_TEXT) }
        }
    }
    LoadMoreEffect(listState = listState, hasMore = state.hasMore, onLoadMore = actions.onLoadMore)
}

/** Al desplazarse cerca del final de la lista,A la página siguiente. */
@Composable
private fun LoadMoreEffect(listState: LazyListState, hasMore: Boolean, onLoadMore: () -> Unit) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { loadMore() }
    }
}

/**
 * cubrir:Usa imágenes si tienes fotos.,No hay ninguna imagen que utilice el degradado de este módulo.
 *
 * El avatar y el apodo se presionan en el lado derecho del borde inferior. —— La tarjeta de identificación de Moments no está en la parte superior de la pantalla.,Y en su propio territorio.
 * La altura se toma como altura de la cubierta en la escalera de tamaño del módulo.,No escribas un número aquí.
 */
@Composable
private fun MomentsCover(coverImageUri: String?, selfName: String, selfAvatarUri: String?) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.coverHeight)
            .background(colors.surface)
            .testTag(MiniPhoneMomentsTags.COVER),
    ) {
        if (coverImageUri != null) {
            AsyncImage(
                model = coverImageUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.accent.copy(alpha = COVER_GRADIENT_ALPHA), colors.background))),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selfName.ifBlank { DEFAULT_SELF_NAME },
                style = typography.title.copy(color = colors.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(MiniPhoneMomentsTags.SELF_NAME),
            )
            Spacer(Modifier.width(10.dp))
            MiniPhoneChatAvatar(
                name = selfName,
                avatarUri = selfAvatarUri,
                size = dimens.avatarLarge,
                modifier = Modifier.testTag(MiniPhoneMomentsTags.SELF_AVATAR),
            )
        }
    }
}

/**
 * Estado vacío:"Varias cartas apiladas" que saqué yo mismo.
 *
 * "Debería haber un montón de animaciones aquí" se crea apilando tres rectángulos redondeados con trazos.,Ven con un consejo que puedas seguir. ——
 * Una página en blanco que simplemente dice "aún no hay contenido" significa no decir nada.
 */
@Composable
private fun MomentsEmptyState(isLoaded: Boolean, onlyMine: Boolean) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EmptyVerticalPadding)
            .testTag(MiniPhoneMomentsTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!isLoaded) return@Column
        Canvas(Modifier.size(EmptyArtSize)) {
            val stroke = Stroke(width = EmptyStrokeWidth.toPx())
            val cardWidth = size.width * EMPTY_CARD_WIDTH_RATIO
            val cardHeight = size.height * EMPTY_CARD_HEIGHT_RATIO
            val corner = CornerRadius(EmptyCorner.toPx())
            repeat(EMPTY_CARD_COUNT) { index ->
                drawRoundRect(
                    color = colors.divider,
                    topLeft = Offset(
                        size.width * EMPTY_CARD_LEFT_RATIO,
                        size.height * EMPTY_CARD_TOP_RATIO + cardHeight * index * EMPTY_CARD_STEP_RATIO,
                    ),
                    size = Size(cardWidth, cardHeight),
                    cornerRadius = corner,
                    style = stroke,
                )
            }
            drawCircle(
                color = colors.accent,
                radius = EmptyDotRadius.toPx(),
                center = Offset(
                    size.width * (EMPTY_CARD_LEFT_RATIO + EMPTY_CARD_WIDTH_RATIO * EMPTY_DOT_INSET_RATIO),
                    size.height * EMPTY_CARD_TOP_RATIO + cardHeight * EMPTY_DOT_TOP_RATIO,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (onlyMine) MINE_EMPTY_TITLE else EMPTY_TITLE,
            style = typography.body.copy(color = colors.textPrimary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (onlyMine) MINE_EMPTY_HINT else EMPTY_HINT,
            style = typography.timestamp.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
        )
    }
}

/** Una nota sobre los pies de lista(Cargando). */
@Composable
private fun ListFootNote(text: String) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = text,
        style = typography.timestamp.copy(color = colors.textSecondary),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
}

private val TopBarButtonSize = 40.dp
private val TopBarIconSize = 20.dp
private val TopBarCorner = 8.dp
private val CardVerticalGap = 6.dp
private val EmptyArtSize = 96.dp
private val EmptyStrokeWidth = 1.5.dp
private val EmptyCorner = 6.dp
private val EmptyDotRadius = 3.dp
private val EmptyVerticalPadding = 40.dp

private const val COVER_GRADIENT_ALPHA: Float = 0.35f
private const val EMPTY_CARD_COUNT: Int = 3
private const val EMPTY_CARD_WIDTH_RATIO: Float = 0.62f
private const val EMPTY_CARD_HEIGHT_RATIO: Float = 0.3f
private const val EMPTY_CARD_LEFT_RATIO: Float = 0.2f
private const val EMPTY_CARD_TOP_RATIO: Float = 0.1f
private const val EMPTY_CARD_STEP_RATIO: Float = 0.62f
private const val EMPTY_DOT_INSET_RATIO: Float = 0.18f
private const val EMPTY_DOT_TOP_RATIO: Float = 0.28f

private const val PAGE_TITLE: String = "Momentos"
private const val MINE_PAGE_TITLE: String = "mi circulo de amigos"
private const val ALL_KEY: String = "mp-moments-all"
private const val MINE_KEY: String = "mp-moments-mine"
private const val BACK_DESCRIPTION: String = "Regresar"
private const val COMPOSE_DESCRIPTION: String = "escribe un mensaje"
private const val DEFAULT_SELF_NAME: String = "yo"
private const val EMPTY_TITLE: String = "Aún no hay actualizaciones"
private const val EMPTY_HINT: String = "Tira hacia abajo para actualizar y espera a que el personaje diga algo."
private const val MINE_EMPTY_TITLE: String = "Aún no hay actualizaciones propias"
private const val MINE_EMPTY_HINT: String = "Haga clic en la esquina superior derecha para escribir una nota y recuérdela ahora"
private const val LOADING_TEXT: String = "Cargando……"

/** Cuando queden algunos elementos al final de la lista, comience a preguntar por la página siguiente. */
private const val LOAD_MORE_THRESHOLD: Int = 2

private const val COVER_KEY: String = "mp-moments-cover-item"
private const val EMPTY_KEY: String = "mp-moments-empty-item"
private const val LOADING_KEY: String = "mp-moments-loading-item"
private const val MOMENT_CONTENT_TYPE: String = "mp-moment"
