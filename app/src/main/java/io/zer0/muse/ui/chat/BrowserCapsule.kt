package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.tools.BrowserManager
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.common.surface.MuseGlassContainer
import io.zer0.muse.ui.theme.MuseIconSizes
import kotlinx.coroutines.launch

/**
 * Cápsula de estado del navegador + visor de pantalla completa.
 *
 * Cuando la sesión actual tiene una entrada del navegador,Siempre se muestra una cápsula de estado debajo del título de la página de conversación.(No iniciado/cargando /
 * página actual). Haga clic en la cápsula para ingresar a la vista del navegador en pantalla completa,Vea la página en la que está trabajando la IA en tiempo real.
 *
 * v1.0.90: El espectador se comporta como un navegador real. —— Barra de direcciones editable(Ingrese la URL/Buscar), adelante y atrás,
 * Actualizar, borrar cookies, abrir el navegador del sistema, copiar enlace. La clave es**Navegación manual del usuario y operaciones de IA.
 * Compartir la misma instancia de WebView**:Una vez que el usuario termina de cambiar la página, la IA puede leerla y continuar la operación.
 */

internal enum class BrowserDisplayState {
    NOT_STARTED,
    BLANK_PAGE,
    LOADING,
    READY,
}

/** Mapeo de estado lógico puro,Para UI y reutilización de pruebas. */
internal fun browserDisplayState(isActive: Boolean, isLoading: Boolean, url: String): BrowserDisplayState = when {
    isLoading -> BrowserDisplayState.LOADING
    !isActive || url.isBlank() -> BrowserDisplayState.NOT_STARTED
    url.equals("about:blank", ignoreCase = true) -> BrowserDisplayState.BLANK_PAGE
    else -> BrowserDisplayState.READY
}

/** La barra de título del chat no está ocupada cuando no se inicia; el portal se muestra sólo después de que el navegador realmente comienza a funcionar. */
internal fun shouldShowBrowserCapsule(state: BrowserDisplayState): Boolean = state != BrowserDisplayState.NOT_STARTED

/** Cambiar título de página/Las direcciones están normalizadas a etiquetas cortas que la cápsula estatal puede mostrar. */
internal fun browserPageLabel(title: String, url: String): String {
    title.trim().takeIf { it.isNotBlank() }?.let { return it }
    if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return ""
    return runCatching { java.net.URI(url).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }
        ?: url.trim()
}

@Composable
fun BrowserStatusCapsule(manager: BrowserManager?, modifier: Modifier = Modifier) {
    if (manager == null) return
    val isActive by manager.isActive.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val url by manager.currentUrl.collectAsState()
    val title by manager.currentTitle.collectAsState()
    var showViewer by remember(manager) { mutableStateOf(false) }
    val displayState = browserDisplayState(isActive, isLoading, url)
    if (!shouldShowBrowserCapsule(displayState) && !showViewer) return
    val pageLabel = remember(title, url) { browserPageLabel(title, url) }
    val label = when (displayState) {
        BrowserDisplayState.NOT_STARTED -> stringResource(R.string.browser_status_not_started)
        BrowserDisplayState.BLANK_PAGE -> stringResource(R.string.browser_status_blank)
        BrowserDisplayState.LOADING -> stringResource(R.string.browser_status_loading)
        BrowserDisplayState.READY -> pageLabel.ifBlank {
            stringResource(R.string.browser_status_ready)
        }
    }.take(28)
    val capsuleDescription = stringResource(R.string.browser_status_cd, label)

    // v2.6.10: Usado originalmente onBackground.copy(alpha=0.9) Cuando el color de fondo —— Eso es "color inverso del texto",
    // El tema oscuro es como una pieza 90% blanca y, al desplazarse por el mensaje, parece una mancha de color blanco sucio. Cambiar al color de la superficie del tema + trazo fino:
    // Debe ser un control que flote sobre el contenido, no un bloque de color invertido.
    val capsuleContainer = MaterialTheme.colorScheme.surfaceContainerHigh
    val capsuleBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Surface(
        onClick = { showViewer = true },
        shape = RoundedCornerShape(50),
        color = capsuleContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, capsuleBorder),
        modifier = modifier.semantics { contentDescription = capsuleDescription },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (displayState == BrowserDisplayState.LOADING) {
                MuseSpinner(
                    size = 12.dp,
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    imageVector = MuseIcons.globe,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Icon(
                imageVector = MuseIcons.externalLink,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
        }
    }

    if (showViewer) {
        BrowserViewerDialog(
            manager = manager,
            onDismiss = { showViewer = false },
        )
    }
}

/** Visor de navegador de pantalla completa:Barra de herramientas superior del navegador(barra de direcciones/Actualizar/Más) + WebView Imágenes en vivo. */
@Composable
fun BrowserViewerDialog(manager: BrowserManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url by manager.currentUrl.collectAsState()
    val title by manager.currentTitle.collectAsState()
    val isActive by manager.isActive.collectAsState()
    val isLoading by manager.isLoading.collectAsState()
    val canGoBack by manager.canGoBack.collectAsState()
    val canGoForward by manager.canGoForward.collectAsState()
    val displayState = browserDisplayState(isActive, isLoading, url)

    // barra de direcciones:El usuario no sigue la página actual mientras edita URL;No interrumpa la entrada mientras edita.
    var addressInput by remember(manager) { mutableStateOf(url) }
    var addressFocused by remember(manager) { mutableStateOf(false) }
    var showMenu by remember(manager) { mutableStateOf(false) }
    LaunchedEffect(url, addressFocused) {
        if (!addressFocused && url.isNotBlank()) addressInput = url
    }

    val submitAddress: () -> Unit = {
        val target = addressInput.trim()
        if (target.isNotEmpty()) {
            // Enfoque claro después del envío,Devuelve la barra de direcciones a"Seguir la página actual"Estado
            addressFocused = false
            scope.launch { manager.navigate(target) }
        }
    }

    // Cree un WebView y prepárese about:blank cuando se abra por primera vez un portal explícito. Si la IA ha empezado a navegar,
    // showBlankPageIfNeeded Se conservarán las cargas existentes.,No sobrescribe sus páginas ni enlaces de protección SSRF.
    LaunchedEffect(manager) {
        manager.showBlankPageIfNeeded()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Cubre toda la pantalla(Con barra de estado): De lo contrario, el color de fondo de la capa de pantalla completa del navegador/El enmascaramiento comienza debajo de la barra de estado.
            decorFitsSystemWindows = false,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Barra de herramientas
                MuseGlassContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        // Regresar:Contraer visor,Los navegadores siguen sin cabeza y la IA sigue utilizándolos(retención de cápsulas)
                        // ST-06: 38dp → MuseIconSizes.touchTarget(48dp,MD3 Toque la línea roja del objetivo)
                        MuseTactileButton(
                            icon = MuseIcons.arrowLeft,
                            onClick = onDismiss,
                            contentDescription = stringResource(R.string.browser_viewer_collapse),
                            size = MuseIconSizes.touchTarget,
                            iconSize = 20.dp,
                        )
                        // barra de direcciones:Puede ingresar una URL o un término de búsqueda. Enviar y listo manager.navigate,
                        // Compartido con IA WebView —— El usuario ha terminado de cambiar la página.,AI Luego podrás leerlo y seguir trabajando.
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) {
                                if (isLoading) {
                                    MuseSpinner(
                                        size = 12.dp,
                                        strokeWidth = 1.5.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = MuseIcons.globe,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                BasicTextField(
                                    value = addressInput,
                                    onValueChange = { addressInput = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(onGo = { submitAddress() }),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp, vertical = 12.dp)
                                        .onFocusChanged { addressFocused = it.isFocused },
                                    decorationBox = { innerTextField ->
                                        if (addressInput.isBlank()) {
                                            Text(
                                                text = stringResource(R.string.browser_address_bar),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        innerTextField()
                                    },
                                )
                            }
                        }
                        MuseTactileButton(
                            icon = MuseIcons.refresh,
                            onClick = { runCatching { manager.reload() } },
                            contentDescription = stringResource(R.string.browser_viewer_refresh),
                            size = MuseIconSizes.touchTarget,
                            iconSize = 19.dp,
                        )
                        // Más:Adelante y atrás/Cerrar/Borrar cookies/Abrir el navegador del sistema/Copiar enlace
                        Box {
                            MuseTactileButton(
                                icon = MuseIcons.moreVertical,
                                onClick = { showMenu = true },
                                contentDescription = stringResource(R.string.chat_top_menu_cd),
                                size = MuseIconSizes.touchTarget,
                                iconSize = 20.dp,
                            )
                            if (showMenu) {
                                MuseFloatingActionMenu(
                                    items = listOf(
                                        MuseFloatingActionItem(
                                            key = "back",
                                            icon = MuseIcons.arrowLeft,
                                            label = stringResource(R.string.browser_back),
                                            enabled = canGoBack,
                                            onClick = { manager.goBack() },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "forward",
                                            icon = MuseIcons.arrowRight,
                                            label = stringResource(R.string.browser_forward),
                                            enabled = canGoForward,
                                            onClick = { manager.goForward() },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "clear_cookies",
                                            icon = MuseIcons.trash,
                                            label = stringResource(R.string.browser_menu_clear_cookies),
                                            onClick = {
                                                manager.clearCookies()
                                                MuseToast.show(context.getString(R.string.browser_cookies_cleared))
                                            },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "external",
                                            icon = MuseIcons.externalLink,
                                            label = stringResource(R.string.browser_menu_open_external),
                                            enabled = url.isNotBlank(),
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            android.net.Uri.parse(url),
                                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                }
                                            },
                                        ),
                                        MuseFloatingActionItem(
                                            key = "copy",
                                            icon = MuseIcons.copy,
                                            label = stringResource(R.string.browser_menu_copy_link),
                                            enabled = url.isNotBlank(),
                                            onClick = {
                                                runCatching {
                                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                                        ?.setPrimaryClip(
                                                            android.content.ClipData.newPlainText("muse-browser", url),
                                                        )
                                                }
                                            },
                                        ),
                                        // Cerrar el navegador:Destruir WebView + Contraer Visor,Regresar a la entrada de la página de chat"No iniciado"Estado
                                        MuseFloatingActionItem(
                                            key = "close",
                                            icon = MuseIcons.x,
                                            label = stringResource(R.string.browser_viewer_close),
                                            onClick = {
                                                manager.close()
                                                onDismiss()
                                            },
                                        ),
                                    ),
                                    onDismiss = { showMenu = false },
                                )
                            }
                        }
                    }
                }
                // WebView contenido
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.FrameLayout(ctx).also { fl ->
                                manager.attachToDisplay(fl)
                            }
                        },
                        update = { fl ->
                            // La instancia de WebView cambia después de que el proceso de renderizado falla y se reconstruye,Volver a montar
                            manager.attachToDisplay(fl)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (displayState == BrowserDisplayState.NOT_STARTED ||
                        displayState == BrowserDisplayState.BLANK_PAGE
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                            ) {
                                Icon(
                                    imageVector = MuseIcons.globe,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp),
                                )
                                Text(
                                    text = stringResource(R.string.browser_viewer_blank_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Text(
                                    text = stringResource(R.string.browser_viewer_blank_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Volver a headless: la página se conservará,AI Puede continuar la operación
            manager.detachFromDisplay()
        }
    }
}
