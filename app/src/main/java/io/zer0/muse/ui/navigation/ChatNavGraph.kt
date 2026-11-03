package io.zer0.muse.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.zer0.muse.ui.ArchivedChatsScreen
import io.zer0.muse.ui.ChatListScreen
import io.zer0.muse.ui.ChatScreen
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.HomeScreen
import io.zer0.muse.ui.HtmlPreviewScreen
import io.zer0.muse.ui.RecentlyDeletedScreen
import io.zer0.muse.ui.SearchScreen
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.quicknotes.QuickNotesScreen
import io.zer0.muse.ui.quicknotes.QuickNotesViewModel
import io.zer0.muse.ui.settings.PromptTemplateManagerPage
import org.koin.androidx.compose.koinViewModel

/**
 * Área de chat NavGraph — Incluye página de inicio, búsqueda, detalles del chat, detalles del chat grupal, registros rápidos eliminados recientemente,
 * tareas programadas,Agent Mensajes privados, hitos, emoticones, traducciones,HTML Vista previa de 12 elementos componibles en total.
 *
 * Extracto de MainActivity para resolver el último problema de sobrecarga(Línea original 1804 → objetivo ≤ 800 OK).
 * HOME Utilice transiciones verticales,Utilice lo mismo para todas las demás páginas. [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit].
 */
fun NavGraphBuilder.chatNavGraph(navController: NavHostController, sharedViewModel: ChatViewModel) {
    // v0.22: pagina de inicio — Navegación de la pestaña superior(transición vertical,HOME Exclusivo)
    composable<HomeRoute>(
        enterTransition = { MuseTransitions.verticalPushEnter() },
        exitTransition = { MuseTransitions.verticalPushExit() },
        popEnterTransition = { MuseTransitions.verticalPopEnter() },
        popExitTransition = { MuseTransitions.verticalPushPopExit() },
    ) {
        HomeScreen(
            onOpenSettings = { navController.navigate(SettingsRoute) },
            onOpenAssistants = { navController.navigate(AssistantsRoute) },
            onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
            onOpenQuickNotes = { navController.navigate(QuickNotesRoute) },
            onOpenQuickTranslate = { navController.navigate(TranslateRoute) },
            onOpenKnowledgeBase = { navController.navigate(KnowledgeRoute) },
            onOpenMiniPhone = { navController.navigate(MiniPhoneRoute) },
            // v0.27: Haga clic en elemento de tarea/nueva tarea → push Ir a la página de detalles del chat independiente(Desliza hacia la derecha para ingresar,transición de empuje deslizante)
            onOpenChat = { navController.navigate(ChatDetailRoute) },
            // v0.45: Busca en la esquina superior derecha → Página de búsqueda global independiente
            onOpenSearch = { navController.navigate(SearchRoute) },
            // v1.30: Clic en la tarjeta de chat grupal → Página de detalles del chat grupal(Desliza hacia la derecha para ingresar)
            onOpenGroupChat = { chatId ->
                navController.navigate(GroupChatDetailRoute(chatId))
            },
            onOpenRecentlyDeleted = { navController.navigate(RecentlyDeletedRoute) },
            onOpenArchivedChats = { navController.navigate(ArchivedChatsRoute) },
            // HTML/SVG Vista previa en pantalla completa de bloques de código:URL Saltar después de codificar HtmlPreviewScreen
            onHtmlPreview = { html ->
                navController.navigate(HtmlPreviewRoute(html))
            },
            onOpenSkills = { navController.navigate(SkillsRoute) },
            onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
        )
    }
    // v0.45: Página de búsqueda global independiente(Ingrese desde el botón de búsqueda en la esquina superior derecha de la página de inicio,Desliza hacia la derecha para ingresar)
    // v1.0.80: Deslizamiento puro sin aparición ni desaparición — Evite que la translucidez se vea a través de fondos oscuros."máscara negra"
    composable<SearchRoute>(
        enterTransition = { MuseTransitions.horizontalSlideEnter() },
        popExitTransition = { MuseTransitions.horizontalSlidePopExit() },
    ) {
        SearchScreen(
            onBack = { navController.popBackStack() },
            onOpenSession = { sessionId ->
                // Volver a la página de inicio después de cambiar de sesión(La lista de tareas resaltará la sesión.)
                sharedViewModel.switchSession(sessionId)
                navController.popBackStack(HomeRoute, inclusive = false)
            },
            // v2.x: Tab=Contenido del mensaje Haga clic en el elemento del mensaje para saltar. — Cambiar conversación + establecer mensaje objetivo,
            // Después de regresar a INICIO, ChatScreen monitorea el posicionamiento de desplazamiento de targetMessageId + resaltado breve
            onOpenMessage = { sessionId, messageId, query ->
                // v2.x: Corta la sesión primero y luego localízala.(Evitar la competencia y perder posicionamiento.),Luego ingresa a la página de conversación.
                sharedViewModel.openMessageFromSearch(sessionId, messageId, query)
                navController.popBackStack(HomeRoute, inclusive = false)
                navController.navigate(ChatDetailRoute)
            },
        )
    }
    // v2.6.9: Página de configuración de fondo permanente
    composable<KeepAliveSettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.settings.KeepAliveSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }

    // v2.6.7: Las subrutas internas de los teléfonos celulares pequeños también están controladas por la puerta principal (esta versión no se hará pública).
    if (io.zer0.muse.feature.FeatureFlags.MINI_PHONE) {
        // Entrada del miniteléfono: toda la página es autodibujada por el módulo: miniteléfono y la aplicación principal solo proporciona enrutamiento.
        composable<MiniPhoneRoute> {
            io.zer0.muse.miniphone.ui.MiniPhoneRoot()
        }

        // v2.6.7(P0-3): Página de chat en un pequeño teléfono celular — Carcasa estilo WeChat, vuelve al teléfono pequeño en lugar del teléfono principal App
        composable<MiniPhoneChatRoute>(
            enterTransition = { MuseTransitions.horizontalPushEnter() },
            popExitTransition = { MuseTransitions.horizontalPushPopExit() },
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                surface = io.zer0.muse.ui.chat.ChatSurface.MINI_PHONE,
                viewModel = sharedViewModel,
                onHtmlPreview = { html ->
                    navController.navigate(HtmlPreviewRoute(html))
                },
            )
        }
        // v2.6.7(P1-1): Página de configuración de relaciones de roles
        composable<RelationshipSettingsRoute>(
            enterTransition = { MuseTransitions.horizontalPushEnter() },
            popExitTransition = { MuseTransitions.horizontalPushPopExit() },
        ) {
            io.zer0.muse.ui.settings.RelationshipSettingsPage(
                onBack = { navController.popBackStack() },
            )
        }
    }
    // Archivar lista de chats — Ingrese desde la entrada del archivo de la página de inicio
    composable<ArchivedChatsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val state by sharedViewModel.state.collectAsStateWithLifecycle()
        ArchivedChatsScreen(
            sessions = state.archivedSessions,
            onBack = { navController.popBackStack() },
            onUnarchive = { id -> sharedViewModel.setSessionArchived(id, false) },
            onOpenSession = { id ->
                sharedViewModel.switchSession(id)
                navController.popBackStack(HomeRoute, inclusive = false)
            },
            // ST-01: Estado de error fallido al cargar la sesión de archivo + inténtelo de nuevo
            error = state.archivedSessionsError,
            onRetry = { sharedViewModel.retryLoadArchivedSessions() },
            // ST-02: Deshacer el archivo es reversible — Cancelar = Rearchivar
            onUnarchiveUndo = { id -> sharedViewModel.setSessionArchived(id, true) },
        )
    }

    // v0.27: Página de detalles del chat — Empuja desde la página de inicio para entrar,Desliza hacia la derecha para ingresar + desliza hacia la izquierda para regresar(transición de empuje deslizante)
    // P1-4 Adaptación de tableta:Expanded Diseño de doble columna en modo(Pantalla de lista de chat izquierda 40% + derecha ChatScreen 60%),
    //               Compact/Medium mantener una sola columna push/pop
    composable<ChatDetailRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val state by sharedViewModel.state.collectAsStateWithLifecycle()
        val widthClass = rememberWindowWidthClass()
        if (widthClass == WindowWidthClass.Expanded) {
            // P1-4: Expanded Diseño de doble columna — lista de tareas de la columna izquierda(40%) + Chat de la columna derecha(60%)
            Row(modifier = Modifier.fillMaxSize()) {
                // columna izquierda:lista de tareas(Mismo origen que HomeScreen Tab 0,Compartir sharedViewModel.state)
                Box(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                    ChatListScreen(
                        sessions = state.sessions,
                        folders = state.folders,
                        currentSessionId = state.currentSessionId,
                        onSelect = { id -> sharedViewModel.switchSession(id) },
                        onCreate = { sharedViewModel.createNewSession() },
                        // Falta la solución de doble columna de pantalla ancha:Estas dos devoluciones de llamada no se han pasado antes.,La lambda vacía predeterminada permite
                        // La barra de entrada en la columna de la izquierda se descarta y la clasificación de arrastre superior deja de ser válida cuando se suelta.
                        onCreateWithText = { text -> sharedViewModel.sendToNewChat(text) },
                        onDelete = sharedViewModel::deleteSession,
                        onRename = { session ->
                            sharedViewModel.renameSession(session.id, session.title)
                        },
                        onRenameTo = { session, newName ->
                            sharedViewModel.renameSession(session.id, newName)
                        },
                        onTogglePinned = sharedViewModel::togglePinned,
                        onReorderPinned = sharedViewModel::reorderPinnedSessions,
                        onMoveSessionToFolder = sharedViewModel::moveSessionToFolder,
                        onCreateFolder = sharedViewModel::createFolder,
                        onRenameFolder = sharedViewModel::renameFolder,
                        onDeleteFolder = sharedViewModel::deleteFolder,
                        onToggleFolderExpanded = sharedViewModel::toggleFolderExpanded,
                        assistants = state.assistants,
                        currentAssistant = state.currentAssistant,
                        archivedSessions = state.archivedSessions,
                        onArchive = { id -> sharedViewModel.setSessionArchived(id, true) },
                        onUnarchive = { id -> sharedViewModel.setSessionArchived(id, false) },
                        onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
                        onOpenQuickNotes = { navController.navigate(QuickNotesRoute) },
                        onOpenQuickTranslate = { navController.navigate(TranslateRoute) },
                        onOpenKnowledgeBase = { navController.navigate(KnowledgeRoute) },
                        onOpenRecentlyDeleted = { navController.navigate(RecentlyDeletedRoute) },
                        onOpenAssistants = { navController.navigate(AssistantsRoute) },
                        isSessionsLoading = state.isSessionsLoading,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // columna derecha:pagina de chat(onBack=null,La lista es permanente sin botón de retorno.)
                Box(modifier = Modifier.weight(0.6f).fillMaxSize()) {
                    ChatScreen(
                        onOpenAssistants = { navController.navigate(AssistantsRoute) },
                        onBack = null,
                        isAgentMode = state.isAgentMode,
                        viewModel = sharedViewModel,
                        onHtmlPreview = { html ->
                            navController.navigate(HtmlPreviewRoute(html))
                        },
                        onOpenSkills = { navController.navigate(SkillsRoute) },
                        onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
                    )
                }
            }
        } else {
            ChatScreen(
                onOpenAssistants = { navController.navigate(AssistantsRoute) },
                onBack = {
                    // Activar nombres de resumen de IA al salir de una conversación(Sólo si el título sigue siendo el predeterminado y hay al menos una ronda completa de diálogo.)
                    val currentSessionId = sharedViewModel.state.value.currentSessionId
                    if (!sharedViewModel.state.value.isAgentMode && currentSessionId != null) {
                        sharedViewModel.autoTitleOnExit(currentSessionId)
                    }
                    if (sharedViewModel.state.value.isAgentMode) {
                        sharedViewModel.setAgentMode(false)
                    }
                    navController.popBackStack()
                },
                isAgentMode = state.isAgentMode,
                // HTML/SVG Vista previa en pantalla completa de bloques de código:URL Saltar después de codificar HtmlPreviewScreen
                onHtmlPreview = { html ->
                    navController.navigate(HtmlPreviewRoute(html))
                },
                onOpenSkills = { navController.navigate(SkillsRoute) },
                onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
            )
        }
    }
    // v1.30: Página de detalles del chat grupal — Ingrese desde la lista de chat grupal push,Desliza hacia la derecha para ingresar + desliza hacia la izquierda para regresar
    // B0-07: Página de administración de plantillas de Word rápida(desde el chat/Entrada de ventana emergente de plantilla de chat grupal)
    composable<PromptTemplateManagerRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PromptTemplateManagerPage(
            onBack = { navController.popBackStack() },
        )
    }
    composable<GroupChatDetailRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val chatId = backStackEntry.arguments?.getString("chatId").orEmpty()
        io.zer0.muse.ui.groupchat.GroupChatDetailScreen(
            chatId = chatId,
            onBack = { navController.popBackStack() },
            // HTML/SVG Vista previa en pantalla completa de bloques de código:URL Saltar después de codificar HtmlPreviewScreen
            onHtmlPreview = { html ->
                navController.navigate(HtmlPreviewRoute(html))
            },
            onOpenPromptTemplateManager = { navController.navigate(PromptTemplateManagerRoute) },
            // v1.0.72: Proveedor de asistente de edición → Página de configuración de modelo y servicio
            onEditAssistantProvider = { navController.navigate(io.zer0.muse.ui.navigation.SettingsModelRoute) },
        )
    }
    // tareas programadas(Entrada a la casa grande y cuadrada.)
    composable<ScheduledTasksRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.schedule.ScheduledTasksScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // Clic de notificación:Abra la tarea programada especificada y expanda automáticamente los registros ejecutados recientemente.
    composable<ScheduledTaskRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<ScheduledTaskRoute>()
        io.zer0.muse.ui.schedule.ScheduledTasksScreen(
            onBack = { navController.popBackStack() },
            initialTaskId = route.taskId,
        )
    }
    // v1.136: registro rápido(La gran entrada cuadrada en la página de inicio reemplaza la base de conocimientos original.)
    // v1.0.17: Usar en su lugar QuickNotesViewModel(Room Persistencia + Papelera de reciclaje),sustituto QuickNoteStore
    composable<QuickNotesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val quickNotesViewModel: QuickNotesViewModel = koinViewModel()
        QuickNotesScreen(
            onBack = { navController.popBackStack() },
            viewModel = quickNotesViewModel,
            onSendToNewChat = { text ->
                sharedViewModel.sendToNewChat(text)
                navController.navigate(ChatDetailRoute)
            },
        )
    }
    // Clic de notificación:Abra el registro rápido especificado y expanda el texto.
    composable<QuickNoteRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<QuickNoteRoute>()
        val quickNotesViewModel: QuickNotesViewModel = koinViewModel()
        QuickNotesScreen(
            onBack = { navController.popBackStack() },
            viewModel = quickNotesViewModel,
            initialNoteId = route.noteId,
            onSendToNewChat = { text ->
                sharedViewModel.sendToNewChat(text)
                navController.navigate(ChatDetailRoute)
            },
        )
    }
    // v1.126: Agent Bandeja de entrada de mensajes privados(Ingrese desde la página de configuración del agente)
    composable<AgentDmRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.agentdm.AgentDmScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.127: Página de gestión de hitos
    composable<MilestonesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.MilestoneScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.127: Página de gestión de emoticonos
    // v1.97 gap8: Página de traducción independiente(ajustes → Herramientas → AI Traducción)
    composable<TranslateRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.translate.TranslateScreen(
            onBack = { navController.popBackStack() },
            onSendToNewChat = { text ->
                // Crea una nueva conversación y envía un mensaje de texto.,Luego salta a la página de detalles del chat.
                sharedViewModel.sendToNewChat(text)
                navController.navigate(ChatDetailRoute) {
                    popUpTo(TranslateRoute) { inclusive = true }
                }
            },
        )
    }
    // v2.0: Páginas eliminadas recientemente(Ingrese desde ChatListScreen)
    composable<RecentlyDeletedRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        RecentlyDeletedScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // HTML Página de vista previa en pantalla completa — Ingrese desde la entrada del bloque de código HTML/SVG en la burbuja del mensaje
    // El enrutamiento con seguridad de tipos pasa HTML sin procesar directamente sin codificación URL
    composable<HtmlPreviewRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val html = backStackEntry.toRoute<HtmlPreviewRoute>().html
        HtmlPreviewScreen(
            html = html,
            onBack = { navController.popBackStack() },
        )
    }
}
