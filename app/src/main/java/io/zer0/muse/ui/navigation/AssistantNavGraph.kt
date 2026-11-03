package io.zer0.muse.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.zer0.muse.R
import io.zer0.muse.ui.AssistantAdvancedPage
import io.zer0.muse.ui.AssistantBasicPage
import io.zer0.muse.ui.AssistantDetailPage
import io.zer0.muse.ui.AssistantExtensionsPage
import io.zer0.muse.ui.AssistantMemoryPage
import io.zer0.muse.ui.AssistantPromptPage
import io.zer0.muse.ui.AssistantScreen
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.FavoritesScreen
import io.zer0.muse.ui.LorebookScreen
import io.zer0.muse.ui.MemoryScreen
import io.zer0.muse.ui.PromptInjectionScreen
import io.zer0.muse.ui.QuickMessageScreen
import io.zer0.muse.ui.SkillScreen
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.state.MuseEmptyState

/**
 * Campo de ayuda NavGraph — Contiene gestión de asistente, detalles del asistente y 5 subpáginas, favoritos, libro mundial,
 * mensaje rápido,Prompt inyección,Skills, un total de 13 recuerdos componibles.
 *
 * Extracto de MainActivity para resolver el último problema de sobrecarga(Línea original 1804 → objetivo ≤ 800 OK).
 * Todos los componibles se utilizan de manera uniforme. [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] Transición.
 * ASSISTANTS La página utiliza un diseño de dos columnas en modo ampliado(Lista izquierda + detalles derecha).
 */
fun NavGraphBuilder.assistantNavGraph(navController: NavHostController, sharedViewModel: ChatViewModel) {
    composable<AssistantsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        // P1-4 Adaptación de tableta:Expanded Diseño de doble columna en modo(Asistente izquierdoPantalla 40% + Derecha AssistantDetailPage 60%)
        val widthClass = rememberWindowWidthClass()
        if (widthClass == WindowWidthClass.Expanded) {
            // Realizar un seguimiento del asistente seleccionado actualmente ID(rememberSaveable Rotación garantizada/Conservar después de cambios de configuración)
            var selectedAssistantId by rememberSaveable { mutableStateOf<String?>(null) }
            Row(modifier = Modifier.fillMaxSize()) {
                // columna izquierda:lista de asistentes(onBack Aún puedes volver al nivel anterior.;onOpenDetail Cambiar columna derecha)
                Box(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                    AssistantScreen(
                        viewModel = sharedViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { id -> selectedAssistantId = id },
                    )
                }
                // columna derecha:Página de detalles del asistente(Mostrar estado vacío cuando no se selecciona ninguna selección)
                Box(modifier = Modifier.weight(0.6f).fillMaxSize()) {
                    val currentId = selectedAssistantId
                    if (currentId != null) {
                        AssistantDetailPage(
                            assistantId = currentId,
                            onBack = null,
                            onOpenBasic = { navController.navigate(AssistantBasicRoute(currentId)) },
                            onOpenPrompt = { navController.navigate(AssistantPromptRoute(currentId)) },
                            onOpenExtensions = { navController.navigate(AssistantExtensionsRoute(currentId)) },
                            onOpenMemory = { navController.navigate(AssistantMemoryRoute(currentId)) },
                            onOpenAdvanced = { navController.navigate(AssistantAdvancedRoute(currentId)) },
                        )
                    } else {
                        // P1-4: Estado vacío del marcador de posición cuando el asistente no está seleccionado(Mensaje vacío en el área de detalles a la derecha)
                        MuseEmptyState(
                            icon = MuseIcons.user,
                            title = stringResource(R.string.assistant_detail_title_default),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        } else {
            AssistantScreen(
                viewModel = sharedViewModel,
                onBack = { navController.popBackStack() },
                onOpenDetail = { id -> navController.navigate(AssistantDetailRoute(id)) },
            )
        }
    }
    // v0.37: Página de agregación de detalles del asistente(Encabezado + 5 entradas de subpágina)
    composable<AssistantDetailRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantDetailPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
            onOpenBasic = { navController.navigate(AssistantBasicRoute(assistantId)) },
            onOpenPrompt = { navController.navigate(AssistantPromptRoute(assistantId)) },
            onOpenExtensions = { navController.navigate(AssistantExtensionsRoute(assistantId)) },
            onOpenMemory = { navController.navigate(AssistantMemoryRoute(assistantId)) },
            onOpenAdvanced = { navController.navigate(AssistantAdvancedRoute(assistantId)) },
        )
    }
    // v0.37: Subpágina Conceptos básicos del Asistente
    composable<AssistantBasicRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantBasicPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: Subpágina de palabras de aviso del asistente
    composable<AssistantPromptRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantPromptPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: Subpágina de extensión auxiliar
    composable<AssistantExtensionsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantExtensionsPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: Subpágina de memoria del asistente
    composable<AssistantMemoryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantMemoryPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    // v0.37: Subpágina avanzada del asistente
    composable<AssistantAdvancedRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val assistantId = backStackEntry.arguments?.getString("assistantId").orEmpty()
        AssistantAdvancedPage(
            assistantId = assistantId,
            onBack = { navController.popBackStack() },
        )
    }
    composable<MemoryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemoryScreen(
            onBack = { navController.popBackStack() },
            onOpenSettings = { navController.navigate(SettingsMemoryConfigRoute) },
            onOpenSession = { sessionId ->
                sharedViewModel.switchSession(sessionId)
                navController.navigate(ChatDetailRoute) { launchSingleTop = true }
            },
        )
    }
    composable<FavoritesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        FavoritesScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable<LorebooksRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        LorebookScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    // P1-2: Worldbook Libro del mundo dinámico(independiente ViewModel,no depende de ChatViewModel)
    composable<WorldbookRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.worldbook.WorldBookScreen(
            onBack = { navController.popBackStack() },
        )
    }
    composable<QuickMessagesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        QuickMessageScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable<PromptInjectionsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PromptInjectionScreen(
            viewModel = sharedViewModel,
            onBack = { navController.popBackStack() },
        )
    }
    composable<SkillsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SkillScreen(
            onBack = { navController.popBackStack() },
        )
    }
}
