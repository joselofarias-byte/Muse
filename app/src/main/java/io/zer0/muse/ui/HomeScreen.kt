package io.zer0.muse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.horizontalGestureExclusion
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.navigation.MuseTopBarMenu
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.groupchat.GroupChatListScreen
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.update.UpdateChecker
import io.zer0.muse.update.UpdateNotifier
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// M-CS7: tabs Listar nivel de archivo val,Evite la recomposición cada vez.(Originalmente en Composable listOf)
// Ahorra recursos id(Int) y número de serie de la página,UI Las capas se analizan uniformemente utilizando stringResource.
private val HomeTabs = listOf(R.string.home_tab_tasks to 0, R.string.home_tab_agent to 1, R.string.home_tab_group_chat to 2)

/**
 * v0.22 pagina de inicio — Navegación de doble página en la parte superior.
 *
 * Arquitectura(lenguaje de diseño funcionalista minimalista):
 *  - barra fija superior: avatar izquierdo(→ajustes) | cápsula mediaTab(Tarea/Agent) | Buscar a la derecha
 *  - área de contenido: HorizontalPager(2página),Las dos pestañas son completamente independientes.,No saltéis el uno al otro
 *    - Tab 0 "Tarea": lista de tareas(Encuesta de trabajo diario del usuario)
 *    - Tab 1 "Agent": Socio de chat diario duradero(Área de chat independiente)
 *
 * Puntos de diseño(funcionalismo minimalista):
 *  - fondo gris cálido(WarmPaper LightBg #FAFAF8),No blanco puro
 *  - cápsulaTab: contenedor acanalado gris,Elemento seleccionado blanco elevado
 *  - El verde de marca es sólo para decoración.(boton enviar/FAB/Estado seleccionado)
 *  - Grandes esquinas redondeadas(16-18dp)
 *  - minimalista:Sin elementos decorativos,El contenido es el único foco visual.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    onOpenQuickNotes: () -> Unit = {},
    /** v1.0.27: Abra la página de traducción rápida. */
    onOpenQuickTranslate: () -> Unit = {},
    /** v1.0.27: Abra la página de la base de conocimientos. */
    onOpenKnowledgeBase: () -> Unit = {},
    /** v0.27: Cuando haces clic en un elemento de tarea o creas una nueva tarea, accedes a la página de detalles del chat independiente. */
    onOpenChat: () -> Unit = {},
    /** v0.45: Abra la página de búsqueda global independiente. */
    onOpenSearch: () -> Unit = {},
    /** v1.30: Haga clic en la tarjeta de chat grupal en la pestaña de chat grupal → Ir a la página de detalles del chat grupal(Los parámetros son chatId). */
    onOpenGroupChat: (String) -> Unit = {},
    /** v2.0: Abra la página eliminada recientemente. */
    onOpenRecentlyDeleted: () -> Unit = {},
    /** v0.45: Abra la página de la lista de chats archivados. */
    onOpenArchivedChats: () -> Unit = {},
    /** v1.0.72: Abra la página de inmersión del círculo de amigos de teléfono pequeño + IA. */
    onOpenMiniPhone: () -> Unit = {},
    /** HTML/SVG Devolución de llamada de vista previa de pantalla completa de bloque de código(Activado por la pestaña 1 ChatScreen). */
    onHtmlPreview: (String) -> Unit = {},
    /** Menú más → Entrada de habilidad. */
    onOpenSkills: () -> Unit = {},
    /** B0-07: Abra la página de administración de plantillas de Word. */
    onOpenPromptTemplateManager: () -> Unit = {},
    // v1.131: ChatViewModel En AppKoinModule con `single { }` Registrarse(Ver comentarios v1.92),
    // Todas las aplicaciones comparten la misma instancia.,Por lo tanto usado koinInject() Simplemente obtenga el singleton directamente.
    // koinViewModel() El registro para soltero también devolverá la misma instancia.,Pero semánticamente koinInject es más preciso.
    viewModel: ChatViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // P2-13: Interruptor maestro de tecla de acceso directo de escritorio(Expanded Ventana + teclado físico)
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    // v1.95: La página predeterminada para comenzar está determinada por la configuración.(0=Tarea, 1=Agent, 2=chat grupal),initialPage Sólo efectivo para la primera combinación.
    val settings: SettingsRepository = koinInject()
    val defaultPage by settings.defaultHomePageFlow.collectAsStateWithLifecycle(initialValue = 0)
    // v1.133: Suscríbete a caché ReleaseInfo,Renderizar si hay un valor Banner
    val releaseJson by settings.latestReleaseInfoFlow.collectAsStateWithLifecycle(initialValue = null)
    val pagerState = rememberPagerState(initialPage = defaultPage, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    // v1.41: Agent Tab Modelo de estado expandido del panel de interruptores
    var showModelSheet by remember { mutableStateOf(false) }
    // v1.133: Después de que el usuario cierra activamente el Banner,Esta conversación ya no se mostrará(Próxima recuperación de arranque en frío)
    var bannerDismissed by remember { mutableStateOf(false) }
    // v1.136 T8: Expanda la barra de herramientas de acceso directo en la parte inferior derecha de la página de inicio/Estado de colapso(Pulsación larga del interruptor Plus)
    var capsuleExpanded by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    // v1.137 B3: Distinguir entre animaciones de arrastrar y hacer clic por parte del usuario — Haga clic en configuración clickAnimating=true,
    // isScrollInProgress claro después de completar,Haga que MuseCapsuleTab utilice seguimiento continuo al arrastrar y interpolación al hacer clic.
    var clickAnimating by remember { mutableStateOf(false) }
    val reducedMotion = MuseMotion.isReducedMotion()
    // C1: Visibilidad global del panel de comando(Botón de búsqueda / Ctrl+K para invocar)
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    // C4: Doble columna adaptable de pantalla ancha — Expanded(≥840dp) Lista de sesiones de la pestaña Tareas+Mensajes en la misma pantalla,
    // La pantalla estrecha mantiene la interacción original de una sola columna + página de detalles push
    val widthClass = rememberWindowWidthClass()
    val isWideTasks = widthClass == WindowWidthClass.Expanded
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) clickAnimating = false
    }

    // v1.28: entrar/Cambiar el modo Agente al salir de la pestaña Agente(sesión independiente,No depende de tareas)
    // v1.104: Utilice la página establecida en su lugar currentPage,Solo se activa después de que ViewPager se detiene por completo,
    // evitar de page 0→2 Activado por error al pasar del estado medio de la página 1 setAgentMode(Recargará mensajes, se detendrá. TTS/ASR)
    LaunchedEffect(pagerState.settledPage) {
        viewModel.setAgentMode(pagerState.settledPage == 1)
    }

    // v1.0.16: Crear nueva tarea/Entrada unificada para nuevas sesiones. — Reutilizado por la tarjeta debajo de la pestaña y la cápsula flotante en la esquina inferior derecha.
    // C4: Solo cree sesión cuando la pantalla ancha sea de doble columna(La columna derecha ChatScreen sigue el currentSessionId y lo muestra en su lugar),No empujes la página de detalles.
    val onCreateNewTask: () -> Unit = {
        if (isWideTasks) {
            viewModel.createNewSession()
        } else {
            viewModel.createNewSession(onReady = onOpenChat)
        }
    }

    MusePageScaffold(
        floatingActionButton = {
            // v1.0.17: Las cápsulas de suspensión sólo están disponibles en"Tarea"Tab(pagina de inicio)mostrar,Agent/La página de chat grupal no se muestra
            // C4: Ocultar cuando hay doble columna en pantalla ancha(FAB Flotará sobre el área de entrada en la columna derecha y la bloqueará.;Hay una nueva entrada en la columna de la izquierda.)
            if (pagerState.currentPage == 0 && !isWideTasks) {
                HomeQuickActionCapsule(
                    onOpenScheduledTasks = onOpenScheduledTasks,
                    onOpenQuickNotes = onOpenQuickNotes,
                    onOpenQuickTranslate = onOpenQuickTranslate,
                    onCreateNewTask = onCreateNewTask,
                    expanded = capsuleExpanded,
                    onToggleExpanded = { capsuleExpanded = !capsuleExpanded },
                )
            }
        },
        topBar = {
            // v1.24: Barra superior compacta personalizada — Cancelar la altura predeterminada de TopAppBar,Haga que el selector de pestañas se ajuste al contenido siguiente
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // v1.99: grandeRángulo/Evitación de zona de seguridad horizontal con pantalla curva
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .statusBarsPadding()
                    .padding(horizontal = MusePaddings.inputPadding, vertical = MusePaddings.contentGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // lado izquierdo:avatar → ajustes(El mismo botón redondo que "Más" a la derecha,La barra superior es simétrica.)
                MuseTopBarIconButton(
                    icon = MuseIcons.user,
                    contentDescription = stringResource(R.string.home_settings_cd),
                    onClick = onOpenSettings,
                )

                // medio:Conmutador de pestañas de cápsula(MuseCapsuleTab componentes)
                val tabLabels = HomeTabs.map { (labelResId, _) ->
                    stringResource(labelResId)
                }
                io.zer0.muse.ui.common.form.MuseCapsuleTab(
                    tabs = tabLabels,
                    selectedIndex = pagerState.currentPage,
                    onSelect = { page ->
                        clickAnimating = true
                        scope.launch {
                            if (reducedMotion) {
                                pagerState.scrollToPage(page)
                            } else {
                                pagerState.animateScrollToPage(page)
                            }
                        }
                    },
                    // v1.137 B3: Siga continuamente su dedo mientras arrastra,Utilice interpolación para suavizar la transición al hacer clic
                    pageOffset = pagerState.currentPageOffsetFraction,
                    isDragging = pagerState.isScrollInProgress && !clickAnimating,
                    // Pestaña superior estrecha,Haz espacio en el lado derecho para el botón de búsqueda global
                    // CHAT-17: No más escribir hasta la muerte 172dp — Cambiar a límite adaptable,Las etiquetas largas se manejan por omisión dentro de la cápsula.
                    modifier = Modifier.widthIn(max = 172.dp),
                )

                // lado derecho:Entrada unificada "más"(Chatea con Dan/El mismo componente de la barra superior del chat grupal.).
                // Originalmente aquí había dos iconos desnudos colgados uno al lado del otro.(Búsqueda global + teléfono pequeño),El estilo del menú es diferente al de la página de chat.,
                // La barra superior parece una larga y otra corta.;Ahora en el menú desplegable del mismo botón redondo.
                // Sin teclado físico en el celular,Ctrl+K No disponible,Aún es necesario poder acceder a la entrada de búsqueda desde la primera pantalla.
                // Un pequeño teléfono celular es una entrada.(icono + nombre),Haga clic para ir directamente al teléfono pequeño.
                // Si está expuesto o no, solo lo determina el cambio en tiempo de compilación; Originalmente había un interruptor de tiempo de ejecución de "activar en la configuración".
                // Su página de configuración ha sido eliminada junto con la implementación anterior, dejarla ahí sólo hará que la entrada nunca aparezca.
                val showMiniPhone = io.zer0.muse.feature.FeatureFlags.MINI_PHONE_ENTRY
                // v1.0.90: Cuando el teléfono pequeño no está encendido, el icono de búsqueda está directamente en la esquina superior derecha. —— menú con un solo elemento
                // Equivale a una capa más. Después de encender el teléfono pequeño, se despliega en un menú de tres puntos (búsqueda + teléfono pequeño).
                if (showMiniPhone) {
                    MuseTopBarMenu(
                        contentDescription = stringResource(R.string.action_more),
                        items = listOf(
                            MuseFloatingActionItem(
                                key = "search",
                                icon = MuseIcons.search,
                                label = stringResource(R.string.home_search_cd),
                                onClick = { showCommandPalette = true },
                            ),
                            MuseFloatingActionItem(
                                key = "miniphone",
                                icon = MuseIcons.deviceMobile,
                                label = stringResource(R.string.home_miniphone_cd),
                                onClick = onOpenMiniPhone,
                            ),
                        ),
                    )
                } else {
                    MuseTopBarIconButton(
                        icon = MuseIcons.search,
                        contentDescription = stringResource(R.string.home_search_cd),
                        onClick = { showCommandPalette = true },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // interceptar retorno:Si no es la pestaña 0, vuelva primero Tab 0,Evite salir directamente App
        BackHandler(enabled = pagerState.currentPage != 0) {
            scope.launch { pagerState.animateScrollToPage(0) }
        }
        // P2-13: Bloqueo de teclas de acceso directo del escritorio — Ctrl+K abrir búsqueda,Ctrl+N Nueva conversación
        // Solo tiene efecto bajo teclado físico + ventana expandida;Ctrl+N Reutilizar la pestaña 0 existente"Nueva sesión + salto"lógica
        val newChatAction: () -> Unit = {
            viewModel.createNewSession(onReady = onOpenChat)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onKeyEvent { event ->
                    if (!desktopShortcutsEnabled) return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        // Ctrl+K: Abra el panel de comando global(Comando Buscar + barra diagonal)
                        event.key == DesktopShortcuts.FOCUS_SEARCH && event.isCtrlPressed -> {
                            showCommandPalette = true
                            true
                        }
                        // Ctrl+N: Nueva conversación(Solo responde en la lista de tareas de la pestaña 0,Consistente con onCreate existente)
                        event.key == DesktopShortcuts.NEW_CHAT && event.isCtrlPressed &&
                            pagerState.currentPage == 0 -> {
                            newChatAction()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // v1.0.16: nuevas tareas/Entrada nueva sesión — Colóquelo directamente debajo de las 3 barras de pestañas de cambio principales,
            // Mencionado aquí desde la parte superior del ChatListScreen original,Conveniente para que todas las pestañas sean visibles.
            // solo en"Tarea"Tab mostrar(Agent La página se puede ingresar por sí sola.,La página de chat grupal tiene una entrada de lista de chat grupal).
            // v1.133: arriba"Nueva versión disponible"Banner — Se muestra cuando hay ReleaseInfo en caché y no se ha cerrado
            // v1.0.72: La versión en la que el usuario hizo clic e ignoró ya no se mostrará.(persistencia,No se encenderá la próxima vez que arranque en frío.)
            // v1.0.72: Actualizar a la misma versión/Después de una versión superior, no se mostrará incluso si no se borra el caché.(24h El caché permanecerá durante el intervalo de verificación.)
            val ignoredUpdateVersion by settings.ignoredUpdateVersionFlow.collectAsStateWithLifecycle(initialValue = null)
            val currentVersionName = remember { UpdateNotifier.getCurrentVersionName(context) }
            val release = remember(releaseJson, bannerDismissed, ignoredUpdateVersion, currentVersionName) {
                if (bannerDismissed || releaseJson.isNullOrBlank()) {
                    null
                } else {
                    parseReleaseInfo(releaseJson)?.takeIf { info ->
                        info.tagName != ignoredUpdateVersion &&
                            UpdateNotifier.compareVersions(currentVersionName, info.tagName) < 0
                    }
                }
            }
            if (release != null) {
                // Descargar/Actualice las exportaciones para que apunten a la página de descarga del sitio web oficial(Versión, registro de actualizaciones y portal de descargas integrados,Conexión directa CDN nacional)
                val openDownloadPage: () -> Unit = {
                    val intent = UpdateNotifier.buildOfficialDownloadIntent()
                    runCatching {
                        ShareIntentHelper.startChooserSafely(context, intent)
                    }.onFailure { Logger.w("HomeScreen", "startActivity failed: ${it.message}") }
                }
                UpdateAvailableBanner(
                    release = release,
                    onClose = {
                        bannerDismissed = true
                        // v1.0.72: Persistencia después de ignorar,Esta versión ya no solicita(Los usuarios pueden configurar-Buscar actualizaciones manualmente)
                        scope.launch { settings.saveIgnoredUpdateVersion(release.tagName) }
                    },
                    onViewRelease = openDownloadPage,
                    onDownloadApk = openDownloadPage,
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    // P2-9(informe de auditoría): Deslizar toda la página horizontalmente competirá con el gesto de retorno del sistema para comenzar en el borde.
                    // Después de declarar los bordes izquierdo y derecho como áreas de exclusión, arrastrar desde el borde de la pantalla pasará la página en lugar de regresar.
                    .horizontalGestureExclusion(),
                userScrollEnabled = true,
            ) { page ->
                when (page) {
                    // Tab 0 "Tarea": lista de tareas(Encuesta de trabajo diario del usuario)
                    // Haga clic en solo sesión para cambiar currentSessionId,No saltes a Agent Tab
                    // C4: pantalla ancha(Expanded)doble columna — lista de sesiones izquierda(400dp)+ Mensaje correcto en la misma pantalla.;
                    //     La pantalla estrecha mantiene la interacción original(Haga clic en la página de detalles del chat independiente de inserción de tareas)
                    0 -> {
                        if (isWideTasks) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                ChatListScreen(
                                    sessions = state.sessions,
                                    folders = state.folders,
                                    currentSessionId = state.currentSessionId,
                                    onSelect = { id ->
                                        // C4: La pantalla de chat de la columna derecha de pantalla ancha sigue el ID de sesión actual para mostrarse en su lugar,No push
                                        viewModel.switchSession(id)
                                    },
                                    onCreate = {
                                        viewModel.createNewSession()
                                    },
                                    onDelete = viewModel::deleteSession,
                                    onRename = { session ->
                                        viewModel.renameSession(session.id, session.title)
                                    },
                                    onRenameTo = { session, newName ->
                                        viewModel.renameSession(session.id, newName)
                                    },
                                    onTogglePinned = viewModel::togglePinned,
                                    onReorderPinned = viewModel::reorderPinnedSessions,
                                    onMoveSessionToFolder = viewModel::moveSessionToFolder,
                                    onCreateFolder = viewModel::createFolder,
                                    onRenameFolder = viewModel::renameFolder,
                                    onDeleteFolder = viewModel::deleteFolder,
                                    onToggleFolderExpanded = viewModel::toggleFolderExpanded,
                                    assistants = state.assistants,
                                    currentAssistant = state.currentAssistant,
                                    archivedSessions = state.archivedSessions,
                                    onArchive = { id -> viewModel.setSessionArchived(id, true) },
                                    onUnarchive = { id -> viewModel.setSessionArchived(id, false) },
                                    onOpenScheduledTasks = onOpenScheduledTasks,
                                    onOpenQuickNotes = onOpenQuickNotes,
                                    onOpenQuickTranslate = onOpenQuickTranslate,
                                    onOpenKnowledgeBase = onOpenKnowledgeBase,
                                    onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                                    onOpenAssistants = onOpenAssistants,
                                    // v2.x: Socios horizontalmente — Abrir un nuevo chat con un asistente designado(Cambio in situ de doble columna en pantalla ancha a nuevas sesiones)
                                    onOpenAssistantChat = { id ->
                                        viewModel.createNewSession(assistantIdOverride = id)
                                    },
                                    onCreateWithText = { text ->
                                        viewModel.sendToNewChat(text)
                                    },
                                    isSessionsLoading = state.isSessionsLoading,
                                    sessionsError = state.sessionsError,
                                    onRetryLoadSessions = viewModel::retryLoadSessions,
                                    modifier = Modifier.fillMaxSize().width(400.dp),
                                )
                                VerticalDivider(
                                    modifier = Modifier.fillMaxHeight().width(1.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                                // C4: Mensaje de la columna derecha — onBack=null No mostrar el botón Atrás,Reutilizar el singleton ChatViewModel compartido
                                ChatScreen(
                                    onOpenAssistants = onOpenAssistants,
                                    isAgentMode = false,
                                    onHtmlPreview = onHtmlPreview,
                                    onOpenSkills = onOpenSkills,
                                    onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                )
                            }
                        } else {
                            ChatListScreen(
                                sessions = state.sessions,
                                folders = state.folders,
                                currentSessionId = state.currentSessionId,
                                onSelect = { id ->
                                    // v0.27: Haga clic en el elemento de la tarea → Cambiar conversación + pulsar para ir a la página de detalles del chat
                                    viewModel.switchSession(id)
                                    onOpenChat()
                                },
                                onCreate = {
                                    // v0.27: nuevas tareas → Crear conversación + enviar a la página de detalles del chat
                                    viewModel.createNewSession(onReady = onOpenChat)
                                },
                                onDelete = viewModel::deleteSession,
                                onRename = { session ->
                                    viewModel.renameSession(session.id, session.title)
                                },
                                // v1.48: Solucionar error de cambio de nombre de sesión bug — La implementación anterior pasó session.title(nombre original),En su lugar, pase la entrada del usuario newName
                                onRenameTo = { session, newName ->
                                    viewModel.renameSession(session.id, newName)
                                },
                                onTogglePinned = viewModel::togglePinned,
                                onReorderPinned = viewModel::reorderPinnedSessions,
                                onMoveSessionToFolder = viewModel::moveSessionToFolder,
                                onCreateFolder = viewModel::createFolder,
                                onRenameFolder = viewModel::renameFolder,
                                onDeleteFolder = viewModel::deleteFolder,
                                onToggleFolderExpanded = viewModel::toggleFolderExpanded,
                                assistants = state.assistants,
                                currentAssistant = state.currentAssistant,
                                archivedSessions = state.archivedSessions,
                                onArchive = { id -> viewModel.setSessionArchived(id, true) },
                                onUnarchive = { id -> viewModel.setSessionArchived(id, false) },
                                onOpenScheduledTasks = onOpenScheduledTasks,
                                onOpenQuickNotes = onOpenQuickNotes,
                                onOpenQuickTranslate = onOpenQuickTranslate,
                                onOpenKnowledgeBase = onOpenKnowledgeBase,
                                onOpenRecentlyDeleted = onOpenRecentlyDeleted,
                                onOpenAssistants = onOpenAssistants,
                                // v2.x: Socios horizontalmente — Abra un nuevo chat con el asistente designado e ingrese a la página de chat
                                onOpenAssistantChat = { id ->
                                    viewModel.createNewSession(onReady = onOpenChat, assistantIdOverride = id)
                                },
                                onCreateWithText = { text ->
                                    if (isWideTasks) {
                                        viewModel.sendToNewChat(text)
                                    } else {
                                        viewModel.sendToNewChat(text, onReady = onOpenChat)
                                    }
                                },
                                isSessionsLoading = state.isSessionsLoading,
                                sessionsError = state.sessionsError,
                                onRetryLoadSessions = viewModel::retryLoadSessions,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    // Tab 1 "Agent": Socio de chat diario duradero
                    // Área de chat independiente,Selección de sesión independiente de la pestaña de tareas
                    // M-CS6: Eliminar envoltorios de columnas redundantes — ChatScreen FillMaxSize de uso interno autoportante,Column Simplemente agrega una capa de anidamiento sin sentido.
                    1 -> ChatScreen(
                        onOpenAssistants = onOpenAssistants,
                        isAgentMode = true,
                        onHtmlPreview = onHtmlPreview,
                        onOpenSkills = onOpenSkills,
                        onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                    )
                    // Tab 2 "chat grupal": Lista de chat grupal de múltiples agentes
                    // Haga clic en la tarjeta de chat grupal → Ir a la página de detalles del chat grupal(Enrutamiento a través de NavHost)
                    2 -> GroupChatListScreen(
                        onOpenChat = onOpenGroupChat,
                    )
                }
            }
        }

        // v1.41: Agent Tab Panel de interruptores modelo
        if (showModelSheet) {
            ModelSwitchSheet(
                providers = state.providers,
                activeProviderId = state.activeProviderId,
                selectedModelId = state.selectedModelId,
                onPickProvider = viewModel::setActiveProvider,
                onPickModel = viewModel::setSelectedModel,
                onRefreshModels = viewModel::refreshModels,
                isFetchingModels = state.isFetchingModels,
                fetchModelsError = state.fetchModelsError,
                onDismiss = { showModelSheet = false },
            )
        }
    }
    // C1: panel de comando global(Ventana de diálogo independiente,Cubrir cualquier Tab)
    if (showCommandPalette) {
        CommandPalette(
            onDismiss = { showCommandPalette = false },
            onNewChat = {
                viewModel.createNewSession(onReady = onOpenChat)
            },
            onOpenSettings = onOpenSettings,
            onOpenSearch = onOpenSearch,
            onOpenChat = onOpenChat,
        )
    }
}

/**
 * v1.133: "Nueva versión disponible"Banner — Se muestra en la parte superior de la pantalla de inicio.
 *
 * diseño:
 *  - Surface El contenedor utiliza el color primario del contenedor.(Resaltan los tonos cálidos,consistente con la marca)
 *  - lado izquierdo:Close icono(cerrar Banner,Esta conversación no se volverá a mostrar)
 *  - medio:Título"<tagName> Publicado" + nombre de la versión
 *  - lado derecho:dos TextButton("Ver detalles" / "Descargar APK"),Abra la página de descarga del sitio web oficial.
 *
 * @param release Información de la última versión
 * @param onClose Cerrar devolución de llamada de banner
 * @param onViewRelease Abra la página de descarga del sitio web oficial.(Registro de versiones y actualizaciones)
 * @param onDownloadApk Abra la página de descarga del sitio web oficial.(Descargar entrada)
 */
@Composable
private fun UpdateAvailableBanner(
    release: UpdateChecker.ReleaseInfo,
    onClose: () -> Unit,
    onViewRelease: () -> Unit,
    onDownloadApk: () -> Unit,
) {
    val closeCd = stringResource(R.string.update_banner_close_cd)
    val viewDetailText = stringResource(R.string.update_banner_view_detail)
    val downloadApkText = stringResource(R.string.update_banner_download_apk)
    val publishedText = stringResource(R.string.update_banner_published, release.tagName)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = publishedText,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (release.name.isNotBlank()) {
                    Text(
                        text = release.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MuseCapsuleButton(
                text = viewDetailText,
                onClick = onViewRelease,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseCapsuleButton(
                text = downloadApkText,
                onClick = onDownloadApk,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
            )
            MuseTactileButton(
                icon = MuseIcons.x,
                onClick = onClose,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { contentDescription = closeCd },
            )
        }
    }
}

/**
 * Analice el JSON ReleaseInfo almacenado en caché.
 * Devolver si falla el análisis null(Banner no mostrar,Evite corromper datos y bloquear la interfaz de usuario).
 */
private fun parseReleaseInfo(json: String?): UpdateChecker.ReleaseInfo? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        AppJson.decodeFromString(UpdateChecker.ReleaseInfo.serializer(), json)
    }.onFailure {
        Logger.w("HomeScreen", "parseReleaseInfo failed: ${it.message}")
    }.getOrNull()
}

/**
 * v1.0.16: Cápsula de suspensión en la esquina inferior derecha de la página de inicio. — Tareas programadas/grabación rápida/traducción rápida/signo más(Crear nueva tarea).
 *
 * El diseño se refiere a la disposición del botón en la esquina inferior derecha proporcionada por el usuario.:
 *  - cápsula ovalada larga,Consistente con los formularios de entrada convencionales
 *  - 4 botones de iconos dispuestos igualmente,separados por una delgada línea vertical
 *  - uso de fondo surfaceContainer,flotar sobre el contenido
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeQuickActionCapsule(
    onOpenScheduledTasks: () -> Unit,
    onOpenQuickNotes: () -> Unit,
    onOpenQuickTranslate: () -> Unit,
    onCreateNewTask: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .padding(end = 8.dp, bottom = 16.dp)
            .navigationBarsPadding(),
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // v1.136 T8: Mostrar solo el botón Más cuando está contraído;Expandir para mostrar los 4 botones(Pulsación larga del interruptor Plus)
            AnimatedVisibility(
                visible = expanded,
                enter = MuseMotion.horizontalExpandEnter(durationMillis = MuseAnimation.TACTILE_MS),
                exit = MuseMotion.horizontalExpandExit(durationMillis = MuseAnimation.TACTILE_MS),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeCapsuleButton(
                        icon = MuseIcons.calendarTime,
                        contentDescription = stringResource(R.string.chat_list_scheduled_tasks),
                        onClick = onOpenScheduledTasks,
                    )
                    HomeCapsuleDivider()
                    HomeCapsuleButton(
                        icon = MuseIcons.edit,
                        contentDescription = stringResource(R.string.chat_list_quick_notes),
                        onClick = onOpenQuickNotes,
                    )
                    HomeCapsuleDivider()
                    HomeCapsuleButton(
                        icon = MuseIcons.languages,
                        contentDescription = stringResource(R.string.chat_list_quick_translate),
                        onClick = onOpenQuickTranslate,
                    )
                    HomeCapsuleDivider()
                }
            }
            // Plus Botón siempre mostrado;Mantenga presionado para cambiar y expandir/cerrar
            // CHAT-16: Debilitar el botón principal de FAB — Cambió de primaria sólida a primaryContainer,
            // Evite competir con la clave de envío de "dos grandes círculos" en el campo de entrada de la página de inicio.
            HomeCapsuleButton(
                icon = MuseIcons.plus,
                contentDescription = stringResource(R.string.chat_list_new_task),
                onClick = onCreateNewTask,
                onLongClick = onToggleExpanded,
                // UI-FIX A: La operación principal se cambia a un contenedor sólido (color claro casi negro)/Oscuro casi blanco)+ Icono de invertir
                containerColor = MuseActionColors.container,
                contentColor = MuseActionColors.content,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeCapsuleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    containerColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Icono + texto: Anteriormente solo había iconos. Aunque los lectores de pantalla pueden leer etiquetas semánticas, el aspecto visual es completamente una conjetura.
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .background(containerColor)
            // Los botones de cápsula autopintados conservan el tacto/retroalimentación de color,Deshabilite la máscara de ondulación predeterminada.
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = contentDescription,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HomeCapsuleDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}
