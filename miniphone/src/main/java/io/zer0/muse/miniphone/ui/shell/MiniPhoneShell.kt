package io.zer0.muse.miniphone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatRoute
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.moments.MiniPhoneMomentsRoute
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Funda pequeña para celular.
 *
 * tercer piso:barra de estado, área de contenido(Escritorio/una determinada página/página de chat/momentos), barra de pestañas inferior;El papel tapiz es la parte inferior de todo el caparazón.
 * Cambiar escritorio y página en el mismo shell(El escritorio "no tiene ninguna pestaña seleccionada"),tab Tanto el estado como la sesión actual se guardan usando recuerdaSaveable,
 * Incluso si el proceso se rota, se recicla y luego se devuelve, se detendrá en la página original.
 *
 * La página de chat y los Momentos no entran en la navegación del anfitrión.:Este módulo solo tiene una "identificación de sesión actual" y un "círculo de momentos está abierto o no",
 * Cuando se establezca alguno, cambie el área de contenido a la página correspondiente y contraiga la barra de pestañas.,Se borra al regresar.
 * Hay dos entradas al círculo de amigos.:Descubre todas las novedades en la página.,"Mis momentos" en mi página solo ve mis publicaciones.
 *
 * Iniciar un chat grupal también es una página completa:"+"ábrelo,Seleccione suficientes personas y haga clic en "Completar" para unirse al grupo directamente. ——
 * Crea una página de grupo sin tocar la biblioteca tú mismo,Después de crear el ID de sesión, regrese aquí para abrirlo.
 *
 * @param chatContent El puerto de renderizado de la página de chat.,De forma predeterminada, este módulo [MiniPhoneChatRoute];Prueba de avatar inyectable
 * @param momentsContent Puerto de renderizado de Moments,El primer parámetro es el modo "mírate sólo a ti mismo".;El valor predeterminado es [MiniPhoneMomentsRoute]
 * @param onOpenCharacterChat Al hacer clic en una persona en la libreta de direcciones, se requiere la identificación de la sesión: si ya hay una sesión, devuélvala,No hay nadie abierto en el lugar.;
 *   Devuelve nulo para indicar que no se puede abrir.(La identificación del rol está vacía),El shell se detiene en la página original. Navegación en este shell,No se lo entregues a los superiores
 * @param actions Acciones del usuario en la página.(Cambiar, acción de conversación, borrar, abrir mi círculo de amigos, crear un grupo),Recibido desde la entrada. ViewModel
 */
@Composable
fun MiniPhoneShell(
    state: ShellUiState,
    modifier: Modifier = Modifier,
    chatContent: @Composable (threadId: String, onBack: () -> Unit) -> Unit = { threadId, onBack ->
        MiniPhoneChatRoute(threadId = threadId, onBack = onBack)
    },
    momentsContent: @Composable (onlyMine: Boolean, onBack: () -> Unit) -> Unit = { onlyMine, onBack ->
        MiniPhoneMomentsRoute(onlyMine = onlyMine, onBack = onBack)
    },
    onOpenCharacterChat: suspend (characterId: String) -> String? = { null },
    actions: MiniPhoneShellActions = MiniPhoneShellActions(),
) {
    val colors = MiniPhoneTheme.colors
    var selectedTab by rememberSaveable { mutableStateOf<MiniPhoneTab?>(null) }
    var currentThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var momentsOpen by rememberSaveable { mutableStateOf(false) }
    var momentsOnlyMine by rememberSaveable { mutableStateOf(false) }
    var groupCreateOpen by rememberSaveable { mutableStateOf(false) }
    // Las personas seleccionadas al crear un grupo se guardan en la lista:El orden de hacer clic es el orden de unirse al grupo.,Las páginas de miembros también están organizadas en este orden.
    var groupSelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    val openThread: (String) -> Unit = { currentThreadId = it }
    val closeThread: () -> Unit = { currentThreadId = null }
    val openMoments: (Boolean) -> Unit = { onlyMine ->
        momentsOnlyMine = onlyMine
        momentsOpen = true
    }
    // Para hacer clic en la libreta de direcciones es necesario consultar primero la conversación. id(Es posible que tengas que crear uno nuevo.),Entonces tienes que traer una capa de alcance de rutina.
    val scope = rememberCoroutineScope()
    val openContact: (String) -> Unit = { characterId ->
        scope.launch {
            onOpenCharacterChat(characterId)?.let { threadId -> currentThreadId = threadId }
        }
    }
    // Al crear un grupo, también debes esperar hasta que la biblioteca termine de escribir antes de poder obtener la sesión. id,No se puede abrir sincrónicamente
    val createGroup: () -> Unit = {
        scope.launch {
            actions.onCreateGroup(groupSelection)?.let { created ->
                groupCreateOpen = false
                groupSelection = emptyList()
                currentThreadId = created
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.wallpaperTop, colors.wallpaperBottom))),
    ) {
        Column(Modifier.fillMaxSize()) {
            MiniPhoneStatusBar()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val threadId = currentThreadId
                when {
                    threadId != null -> chatContent(threadId, closeThread)
                    groupCreateOpen -> GroupCreatePage(
                        sections = state.contactSections,
                        selectedIds = groupSelection.toSet(),
                        onToggle = { characterId ->
                            groupSelection = if (characterId in groupSelection) {
                                groupSelection - characterId
                            } else {
                                groupSelection + characterId
                            }
                        },
                        onBack = {
                            groupCreateOpen = false
                            groupSelection = emptyList()
                        },
                        onDone = createGroup,
                    )
                    momentsOpen -> momentsContent(momentsOnlyMine) { momentsOpen = false }
                    else -> MiniPhonePageContent(
                        selectedTab = selectedTab,
                        state = state,
                        nav = PageNav(
                            selectTab = { selectedTab = it },
                            openMoments = { openMoments(false) },
                            openMyMoments = { openMoments(true) },
                            openThread = openThread,
                            openContact = openContact,
                            openGroupCreate = { groupCreateOpen = true },
                            goHome = { selectedTab = null },
                        ),
                        actions = actions,
                    )
                }
            }
            // La página de chat, el círculo de amigos y la página de creación de grupos ocupan el área de contenido.,tab Las columnas dan paso a sus propios campos inferiores y de entrada.
            if (currentThreadId == null && !momentsOpen && !groupCreateOpen) {
                MiniPhoneTabBar(selected = selectedTab, onSelect = { selectedTab = it })
            }
        }
    }
}

/**
 * Todas las acciones del usuario en la página.,Recibido desde la entrada de ViewModel.
 *
 * Reúnalos en lugar de usarlos como parámetros uno por uno.:Ya hay cuatro puertos de renderizado y una devolución de llamada de rutina en la lista de parámetros del shell.,
 * Si agrega siete devoluciones de llamada más y las aplana, el punto se perderá por completo.;Agregar una acción elimina la necesidad de contactar a todas las personas que llaman.
 */
@Immutable
data class MiniPhoneShellActions(
    val onToggleAutoMoment: (Boolean) -> Unit = {},
    val onToggleChatProactive: (Boolean) -> Unit = {},
    val onToggleThreadPin: (threadId: String, pinned: Boolean) -> Unit = { _, _ -> },
    val onMarkThreadRead: (threadId: String) -> Unit = {},
    val onDeleteThread: (threadId: String) -> Unit = {},
    val onClearConversations: () -> Unit = {},
    /** Crear un grupo:El parámetro es el rol seleccionado. id(El orden de hacer clic es el orden de unirse al grupo.),Volver a nueva sesión id;Devuelve nulo si no se completa. */
    val onCreateGroup: suspend (characterIds: List<String>) -> String? = { null },
)

/**
 * Seis devoluciones de llamada de salto en el área de contenido:Cortar pestañas y abrir Momentos(Todo/Ver sólo a ti mismo), abra una sesión, abra una sesión por rol, cree una página de grupo y regrese al escritorio.
 *
 * describen lo mismo(¿Adónde quieren ir los usuarios?),Componer un valor es más fácil de leer que seis parámetros de mosaico.
 */
private data class PageNav(
    val selectTab: (MiniPhoneTab) -> Unit = {},
    val openMoments: () -> Unit = {},
    val openMyMoments: () -> Unit = {},
    val openThread: (String) -> Unit = {},
    val openContact: (String) -> Unit = {},
    val openGroupCreate: () -> Unit = {},
    val goHome: () -> Unit = {},
)

/** área de contenido:Escritorio, una página o una página de chat. */
@Composable
private fun MiniPhonePageContent(selectedTab: MiniPhoneTab?, state: ShellUiState, nav: PageNav, actions: MiniPhoneShellActions) {
    if (selectedTab == null) {
        MiniPhoneLauncher(onOpen = nav.selectTab)
        return
    }
    MiniPhonePageFrame(
        title = selectedTab.title,
        onGoHome = nav.goHome,
        headerAction = if (selectedTab == MiniPhoneTab.MESSAGES) {
            { GroupCreateEntry(onClick = nav.openGroupCreate) }
        } else {
            null
        },
    ) {
        when (selectedTab) {
            MiniPhoneTab.MESSAGES -> MessagesPage(
                threads = state.threads,
                onOpenThread = nav.openThread,
                // botón en estado vacío:Ir directamente a la libreta de direcciones,No es necesario que los usuarios regresen y lo encuentren ellos mismos. tab
                onGoContacts = { nav.selectTab(MiniPhoneTab.CONTACTS) },
                actions = ThreadMenuActions(
                    onTogglePin = actions.onToggleThreadPin,
                    onMarkRead = actions.onMarkThreadRead,
                    onDelete = actions.onDeleteThread,
                ),
            )
            MiniPhoneTab.CONTACTS -> ContactsPage(sections = state.contactSections, onOpenContact = nav.openContact)
            MiniPhoneTab.DISCOVER -> DiscoverPage(onOpenMoments = nav.openMoments)
            MiniPhoneTab.ME -> MePage(
                profile = state.profile,
                appVersionName = state.appVersionName,
                autoMomentEnabled = state.autoMomentEnabled,
                chatProactiveEnabled = state.chatProactiveEnabled,
                actions = MePageActions(
                    onToggleAutoMoment = actions.onToggleAutoMoment,
                    onToggleChatProactive = actions.onToggleChatProactive,
                    onOpenMyMoments = nav.openMyMoments,
                    onClearConversations = actions.onClearConversations,
                ),
            )
        }
    }
}

/**
 * barra de estado:reloj izquierdo,Señal/Wi-Fi/Batería a la derecha.
 *
 * Alinee el reloj con el siguiente minuto antes de actualizar,No hagas encuestas cada segundo —— No es necesario mostrar los segundos.,Despierta sesenta veces menos.
 */
@Composable
private fun MiniPhoneStatusBar(modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val nowMs = rememberMinuteClock()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(dimens.statusBarHeight)
            .padding(horizontal = dimens.pageMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = MiniPhoneTimeFormat.clock(nowMs),
            style = typography.caption.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium),
            modifier = Modifier.testTag(MiniPhoneTags.STATUS_CLOCK),
        )
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector = MiniPhoneIcons.Signal,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(dimens.statusIcon),
        )
        Spacer(Modifier.width(StatusIconGap))
        Icon(
            imageVector = MiniPhoneIcons.Wifi,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(dimens.statusIcon),
        )
        Spacer(Modifier.width(StatusIconGap))
        Icon(
            imageVector = MiniPhoneIcons.Battery,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(dimens.statusIcon),
        )
    }
}

/** \"Ahora\" que se alinea y actualiza cada minuto. */
@Composable
private fun rememberMinuteClock(): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            delay(MINUTE_MS - current % MINUTE_MS + TICK_SLOP_MS)
        }
    }
    return nowMs
}

/**
 * Escritorio:Cuadrícula de iconos de cuatro columnas,Deje el muelle inferior vacío.
 *
 * Tabla de iconos de [MiniPhoneApps],Cambiar un lote de entradas no requiere cambiar el diseño.
 */
@Composable
private fun MiniPhoneLauncher(onOpen: (MiniPhoneTab) -> Unit, modifier: Modifier = Modifier) {
    val dimens = MiniPhoneTheme.dimens
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LauncherRowGap)) {
            MiniPhoneApps.launcher.chunked(MiniPhoneApps.COLUMNS).forEach { rowApps ->
                Row(Modifier.fillMaxWidth()) {
                    rowApps.forEach { app ->
                        LauncherTile(
                            app = app,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(app.tab) },
                        )
                    }
                    repeat(MiniPhoneApps.COLUMNS - rowApps.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Muelle inferior: déjelo en blanco en esta etapa,Solo ocupa altura,La entrada permanente se colocará más adelante.
        Spacer(Modifier.height(dimens.dockHeight))
    }
}

/** un icono en el escritorio:Base de papel + icono de color destacado + fila de etiquetas. */
@Composable
private fun LauncherTile(app: MiniPhoneApp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(MiniPhoneTags.launcher(app))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(dimens.launcherTile)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = app.icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(dimens.launcherIcon),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            style = typography.timestamp.copy(color = colors.textPrimary),
        )
    }
}

/** marco de página:Título + Acción de encabezado + Botón Volver al escritorio + Contenido. Color del papel para el color de fondo.,Asegúrese de que el texto de la lista se pueda mantener presionado. */
@Composable
private fun MiniPhonePageFrame(
    title: String,
    onGoHome: () -> Unit,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = dimens.pageMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier.testTag(MiniPhoneTags.PAGE_TITLE),
            )
            Spacer(Modifier.weight(1f))
            if (headerAction != null) {
                headerAction()
                Spacer(Modifier.width(PageHeaderActionGap))
            }
            Box(
                modifier = Modifier
                    .size(HomeButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onGoHome)
                    .testTag(MiniPhoneTags.HOME_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Home,
                    contentDescription = "Volver al escritorio",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

/**
 * Entrada de creación de grupos en la barra superior de la página de mensajes:Un signo más que dibujé yo mismo.
 *
 * Mismo tamaño y forma que el botón de volver al escritorio(Área de toque circular + ícono personalizado),Usa colores de acento ——
 * Es la única acción activa en esta página.,No debería verse exactamente como el ícono de navegación.
 */
@Composable
private fun GroupCreateEntry(onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = Modifier
            .size(HomeButtonSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(MiniPhoneTags.GROUP_CREATE_ENTRY),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Plus,
            contentDescription = "Iniciar un chat grupal",
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** barra de pestañas inferior:Cuatro artículos,Utilice un color de acento para los elementos seleccionados. */
@Composable
private fun MiniPhoneTabBar(selected: MiniPhoneTab?, onSelect: (MiniPhoneTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.hairline)
                .background(colors.divider),
        )
        Row(Modifier.fillMaxWidth().height(dimens.tabBarHeight)) {
            MiniPhoneTab.entries.forEach { tab ->
                val active = tab == selected
                val tint = if (active) colors.accent else colors.textSecondary
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab) }
                        .testTag(MiniPhoneTags.tab(tab)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = MiniPhoneApps.iconOf(tab),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(dimens.tabIcon),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.title,
                        style = typography.timestamp.copy(color = tint, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                    )
                }
            }
        }
    }
}

private const val MINUTE_MS: Long = 60_000L
private const val TICK_SLOP_MS: Long = 40L

private val StatusIconGap = 6.dp
private val LauncherRowGap = 18.dp
private val HomeButtonSize = 32.dp
private val PageHeaderActionGap = 4.dp
