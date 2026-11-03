package io.zer0.muse.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.DataManagementScreen
import io.zer0.muse.ui.NotificationListenerScreen
import io.zer0.muse.ui.ToolsScreen
import io.zer0.muse.ui.VideoGenerationPage
import io.zer0.muse.ui.knowledge.KnowledgeScreen
import io.zer0.muse.ui.speech.VoiceCloningPage
import io.zer0.muse.ui.stats.StatsScreen

/**
 * dominio de herramienta NavGraph — Incluye gestión de herramientas de IA, automatización del navegador, generación de vídeo, clonación de voz,
 * Hay un total de 8 elementos componibles que incluyen estadísticas, monitoreo de notificaciones, administración de datos y base de conocimientos.
 *
 * Extracto de MainActivity para resolver el último problema de sobrecarga(Línea original 1804 → objetivo ≤ 800 OK).
 * Todos los componibles se utilizan de manera uniforme. [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] Transición.
 * STATS página requerida [ChatViewModel] Para admitir, haga clic en la sesión para volver a la página de inicio.
 */
fun NavGraphBuilder.toolsNavGraph(navController: NavHostController, sharedViewModel: ChatViewModel) {
    // v1.0.4: AI Página de gestión de herramientas(Mostrar todas las herramientas de ToolRegistry + detalles + nivel de riesgo)
    composable<ToolsScreenRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ToolsScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-6: Página de demostración de automatización del navegador — WebView de pantalla completa + barra de direcciones superior + barra de acciones inferior
    // P2-8: Página de generación de vídeo — desde la configuración → Herramientas → Entrada de generación de vídeo.
    composable<VideoGenerationRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VideoGenerationPage(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-9: Página de clonación de voz — desde la configuración → medios de comunicación → Clonar voz Ingresar
    composable<VoiceCloningRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VoiceCloningPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.46: Página de estadísticas(Mapa de calor + descripción general de uso,Desliza hacia la derecha para ingresar)
    composable<StatsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        StatsScreen(
            onBack = { navController.popBackStack() },
            onOpenSession = { sessionId ->
                // v1.66: Salto de sesión de clic en la página de estadísticas — Volver a la página de inicio después de cambiar de sesión
                sharedViewModel.switchSession(sessionId)
                navController.popBackStack(HomeRoute, inclusive = false)
            },
        )
    }
    // v1.0.4: Página de escucha de notificaciones(Guía de autorización + lista de notificaciones recientes)
    composable<NotificationListenerRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        NotificationListenerScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.92: Página de configuración del canal de mensajes(Feishu/QQ Espere al puente de mensajería instantánea externo)
    composable<ChannelSettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.settings.ChannelSettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenConversations = { channelId ->
                navController.navigate(ChannelConversationsRoute(channelId))
            },
        )
    }
    // v2.0.1: Página de conversación del canal(Ver conversaciones de contacto y contexto por canal)
    composable<ChannelConversationsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<ChannelConversationsRoute>()
        io.zer0.muse.ui.settings.ChannelConversationsScreen(
            channelId = route.channelId,
            onBack = { navController.popBackStack() },
            onOpenConversation = { from ->
                navController.navigate(ChannelChatRoute(route.channelId, from))
            },
        )
    }
    // v2.6.10: Flujo de conversación (burbujas) para un solo contacto
    composable<ChannelChatRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<ChannelChatRoute>()
        io.zer0.muse.ui.settings.ChannelChatScreen(
            channelId = route.channelId,
            from = route.from,
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.92: OAuth Página del conector(Acceso universal OAuth2)
    composable<ConnectorSettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.settings.ConnectorSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v2.0: Página de gestión de datos(Entrar desde configuración)
    composable<DataManagementRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        DataManagementScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // base de conocimientos(Entrada a la casa grande y cuadrada.)
    composable<KnowledgeRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        KnowledgeScreen(
            onBack = { navController.popBackStack() },
            onOpenCoverManager = { navController.navigate(CoverManagerRoute) },
        )
    }

    // v1.0.53: Portada de la página de gestión de la biblioteca(Detalles del documento de la base de conocimientos → Biblioteca de portadas/generar portada)
    composable<CoverManagerRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.CoverManagerScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
