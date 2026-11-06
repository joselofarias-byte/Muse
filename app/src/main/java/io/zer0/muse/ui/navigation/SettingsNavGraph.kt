package io.zer0.muse.ui.navigation

import androidx.compose.runtime.remember
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.license.LicensesScreen
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.DebugScreen
import io.zer0.muse.ui.MemoryScreen
import io.zer0.muse.ui.SettingsScreen
import io.zer0.muse.ui.WorkspaceScreen
import io.zer0.muse.ui.account.AccountScreen
import io.zer0.muse.ui.dev.ComponentGalleryScreen
import io.zer0.muse.ui.knowledge.KnowledgeScreen
import io.zer0.muse.ui.settings.AgentSettingsPage
import io.zer0.muse.ui.settings.AuditLogPage
import io.zer0.muse.ui.settings.ChatSettingsPage
import io.zer0.muse.ui.settings.CloudBackupPage
import io.zer0.muse.ui.settings.ExperimentsSettingsPage
import io.zer0.muse.ui.settings.MediaSettingsPage
import io.zer0.muse.ui.settings.MemorySettingsPage
import io.zer0.muse.ui.settings.MultiAgentSettingsPage
import io.zer0.muse.ui.settings.PermissionWizardScreen
import io.zer0.muse.ui.settings.PluginManagePage
import io.zer0.muse.ui.settings.ProactiveMessageSettingsPage
import io.zer0.muse.ui.settings.ProxySettingsPage
import io.zer0.muse.ui.settings.QuickActionsSettingsPage
import io.zer0.muse.ui.settings.RagSettingsPage
import io.zer0.muse.ui.settings.SecuritySettingsPage
import io.zer0.muse.ui.settings.SettingsAboutPage
import io.zer0.muse.ui.settings.SettingsAppearancePage
import io.zer0.muse.ui.settings.SettingsAsrPage
import io.zer0.muse.ui.settings.SettingsAssistantResourcesPage
import io.zer0.muse.ui.settings.SettingsDataImportPage
import io.zer0.muse.ui.settings.SettingsDataPage
import io.zer0.muse.ui.settings.SettingsGroupPage
import io.zer0.muse.ui.settings.SettingsMcpPage
import io.zer0.muse.ui.settings.SettingsModelPage
import io.zer0.muse.ui.settings.SettingsTutorialPage
import io.zer0.muse.ui.settings.SettingsWebSearchPage
import io.zer0.muse.ui.settings.TaskRoutingSettingsPage
import io.zer0.muse.ui.settings.ToolsSettingsPage
import io.zer0.muse.ui.settings.UserPreferencePage
import io.zer0.muse.ui.settings.UserProfileEditPage
import io.zer0.muse.ui.settings.VisionSettingsPage
import io.zer0.muse.ui.settings.WebAccessPage
import io.zer0.muse.ui.terminal.TerminalScreen
import org.koin.compose.koinInject

/**
 * Establecer dominio NavGraph — Contiene la configuración de la página de inicio + 31 niveles secundarios./Página de configuración de nivel 3(cuenta/modelo/Apariencia/agente/mucho Agent/
 * Flujo de trabajo/Agent/permiso/Depuración/auditoría/espacio de trabajo/Provider Complementos, etc.)32 componibles en total.
 *
 * Extracto de MainActivity para resolver el último problema de sobrecarga(Línea original 1804 → objetivo ≤ 800 OK).
 * Todos los componibles se utilizan de manera uniforme. [MuseTransitions.horizontalPushEnter] / [horizontalPushPopExit] Transición.
 */
fun NavGraphBuilder.settingsNavGraph(navController: NavHostController, sharedViewModel: ChatViewModel) {
    // página de configuración(slide-in)
    composable<SettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAccount = { navController.navigate(AccountRoute) },
            onOpenGroup = { kind -> navController.navigate(SettingsGroupRoute(kind.name)) },
            onNavigate = { route -> navController.navigate(route) },
        )
    }
    // Configure la página de clasificación de primer nivel: la lista de inscritos después de ingresar los 14 puntos de clasificación en la página de inicio
    composable<SettingsGroupRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) { backStackEntry ->
        val route = backStackEntry.toRoute<SettingsGroupRoute>()
        // Lo que se almacena en la ruta es la cadena ID; Si no se puede mapear, regrese a la página de inicio. No utilice una categoría predeterminada para llevar a los usuarios al lugar equivocado.
        val kind = io.zer0.muse.ui.settings.SettingsGroupKind.entries
            .firstOrNull { it.name == route.kind }
        if (kind == null) {
            navController.popBackStack()
        } else {
            SettingsGroupPage(
                kind = kind,
                onBack = { navController.popBackStack() },
                onNavigate = { target -> navController.navigate(target) },
            )
        }
    }
    // v0.25: Centro de cuentas(Página de inicio de sesión de marcador de posición)
    composable<AccountRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AccountScreen(
            onBack = { navController.popBackStack() },
            onOpenUserProfile = { navController.navigate(UserProfileEditRoute) },
        )
    }
    // v0.26: Configurar página secundaria — Modelos y Servicios(v1.133: Sólo lista de proveedores,Otros están divididos en páginas secundarias independientes.)
    composable<SettingsModelRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsModelPage(
            onBack = { navController.popBackStack() },
            // v1.0.74 fix: El enrutamiento de cadenas está en desuso y no está registrado,colapsará;Utilice en su lugar enrutamiento con seguridad de tipos
            onOpenAsr = { navController.navigate(SettingsAsrRoute) },
        )
    }
    // v1.133: Configurar página secundaria — búsqueda en internet(Separarse de ConfiguraciónModelPage)
    // B0-04: Configurar página secundaria — Enrutamiento del modelo de tarea
    composable<SettingsTaskRoutingRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        TaskRoutingSettingsPage(
            onBack = { navController.popBackStack() },
            // v2.x: Entrada visual a los bloques modelo auxiliares. → Página independiente de ayuda visual
            onOpenVision = { navController.navigate(SettingsVisionRoute) },
        )
    }
    composable<SettingsWebSearchRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsWebSearchPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: Configurar página secundaria — reconocimiento de voz ASR(Separarse de ConfiguraciónModelPage)
    composable<SettingsAsrRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAsrPage(
            onBack = { navController.popBackStack() },
            onOpenProviderSettings = { navController.navigate(SettingsModelRoute) },
        )
    }
    // v1.133: Configurar página secundaria — MCP servidor(Separarse de ConfiguraciónModelPage)
    composable<SettingsMcpRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsMcpPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.133: Configurar página secundaria — Recursos asistentes(Separarse de ConfiguraciónModelPage:Favoritos/libro mundial/mensaje rapido/inyección de patrón/Skills/interruptor de memoria)
    composable<SettingsAssistantResourcesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAssistantResourcesPage(
            onBack = { navController.popBackStack() },
            onOpenAssistants = { navController.navigate(AssistantsRoute) },
            onOpenFavorites = { navController.navigate(FavoritesRoute) },
            onOpenLorebooks = { navController.navigate(LorebooksRoute) },
            onOpenWorldbook = { navController.navigate(WorldbookRoute) },
            onOpenQuickMessages = { navController.navigate(QuickMessagesRoute) },
            onOpenPromptInjections = { navController.navigate(PromptInjectionsRoute) },
            onOpenSkills = { navController.navigate(SkillsRoute) },
        )
    }
    // P3-3: Asistente de configuración de permisos(Sin barreras / Shizuku / Root Three Channel)
    composable<SettingsPermissionWizardRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PermissionWizardScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // Página de edición de retratos de usuario(edad/ciudad/MBTI espera)
    composable<UserProfileEditRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        UserProfileEditPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: Configurar página secundaria — Datos y respaldo
    composable<SettingsDataRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsDataPage(
            onBack = { navController.popBackStack() },
            onOpenCloudBackup = { navController.navigate(SettingsCloudBackupRoute) },
        )
    }
    // v1.132: Configurar página secundaria — Página de configuración independiente de la copia de seguridad en la nube(WebDAV/S3 Formulario + lista de respaldo remoto)
    composable<SettingsCloudBackupRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        CloudBackupPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: Configurar página secundaria — Apariencia
    composable<SettingsAppearanceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAppearancePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.26: Configurar página secundaria — Acerca de
    composable<SettingsAboutRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsAboutPage(
            onBack = { navController.popBackStack() },
            onOpenLicenses = { navController.navigate(LicensesRoute) },
            // v2.x: Entrada a la página de configuración de informes de fallos(La página anterior existía pero no había ninguna entrada.)
            onOpenCrashReport = { navController.navigate(SettingsCrashReportRoute) },
        )
    }
    // v2.x: Página de configuración de informes de fallos — De "Acerca de → Comentarios" entrar,Se puede acceder a los índices de búsqueda con el mismo nombre.
    composable<SettingsCrashReportRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.settings.CrashReportSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.31: Configurar página secundaria — comportamiento de chat
    composable<SettingsChatRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ChatSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenQuickActions = { navController.navigate(SettingsQuickActionsRoute) },
        )
    }
    // v2.6.0: Configurar página de tercer nivel — Personalización del botón de acceso directo a mensajes (Via fórmula)
    composable<SettingsQuickActionsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        QuickActionsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.51: centro de memoria — 4 Tab Ver+Editar(presente/corto plazo/largo plazo/hechos)
    composable<SettingsMemoryRoute>(
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
    // v1.0.51: Página de configuración de parámetros de memoria(Originales"Memorias y Notificaciones",Ingrese desde la entrada del equipo del centro de memoria.)
    composable<SettingsMemoryConfigRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MemorySettingsPage(
            onBack = { navController.popBackStack() },
            onOpenMemorySpace = { navController.navigate(SettingsMemorySpaceRoute) },
        )
    }
    // v1.0.52 P2-2: Página de gestión del espacio de memoria(Space CRUD)
    composable<SettingsMemorySpaceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.memory.MemorySpaceManageScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v2.6.10: Página independiente de la biblioteca de paquetes de expresiones
    composable<StickerSettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.settings.StickerSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.32: Configurar página secundaria — medios de comunicación
    composable<SettingsMediaRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MediaSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenVoiceCloning = { navController.navigate(VoiceCloningRoute) },
            // El modelo solo se mantiene en el proveedor: cuando el candidato está vacío, la exportación apunta de nuevo a la lista de proveedores.
            onOpenProviderSettings = { navController.navigate(SettingsModelRoute) },
        )
    }
    // v0.32: Configurar página secundaria — Experimental
    composable<SettingsExperimentsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ExperimentsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.56: Configurar página secundaria — RAG Configuración de búsqueda en la base de conocimientos
    composable<SettingsRagRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        RagSettingsPage(
            onBack = { navController.popBackStack() },
            onManageKbs = { navController.navigate(KnowledgeBaseManageRoute) },
        )
    }
    // v2.4.6: Página de nivel 3 — Gestión de la base de conocimientos.
    // Combinado con la entrada de la base de conocimientos de la página de inicio en la misma página(KnowledgeScreen):base de conocimientos=raíz, carpeta=subdirectorio,
    // Ambas entradas conducen a la misma página.,La pila de retorno, naturalmente, regresa a sus respectivas fuentes.
    composable<KnowledgeBaseManageRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.knowledge.KnowledgeScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.25: Configurar página secundaria — ayuda visual
    composable<SettingsVisionRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        VisionSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.61: Configurar página secundaria — Importación de datos
    composable<SettingsDataImportRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsDataImportPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.61: Configurar página secundaria — Tutoría(guía novato)
    composable<SettingsTutorialRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SettingsTutorialPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v0.32: Configurar página secundaria — Seguridad y uso compartido
    composable<SettingsSecurityRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        SecuritySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // Configurar página secundaria — proxy de red
    composable<SettingsProxyRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ProxySettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.25: Configurar página secundaria — Colaboración entre múltiples agentes
    composable<SettingsMultiAgentRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        MultiAgentSettingsPage(
            onBack = { navController.popBackStack() },

        )
    }

    // v1.27: Configurar página secundaria — Agent Configuración(Selección de asistente/colaboración/mensajes no solicitados)
    composable<SettingsProactiveRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ProactiveMessageSettingsPage(onBack = { navController.popBackStack() })
    }
    composable<SettingsAgentRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AgentSettingsPage(
            onBack = { navController.popBackStack() },
            onOpenMultiAgentSettings = { navController.navigate(SettingsMultiAgentRoute) },
            onOpenAgentDm = { navController.navigate(AgentDmRoute) },
            onOpenPreferences = { navController.navigate(SettingsPreferenceRoute) },
        )
    }
    // v2.6.7 fix (AUDIT F9): Página de gestión de preferencias del usuario.
    composable<SettingsPreferenceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        UserPreferencePage(onBack = { navController.popBackStack() })
    }
    // v1.25: Página de licencia de código abierto — Reparación LICENCIAS desconexión ruta
    composable<LicensesRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        LicensesScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // Página de registro de depuración — desde la configuración → Acerca de → Entrada al registro de depuración,Mostrar llamadas recientes del registrador
    composable<DebugRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        DebugScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v2.x fase terminal uno — desde la configuración → Herramientas → Entrada de terminal,Interactividad dentro del sandbox de la aplicación shell
    composable<TerminalRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        TerminalScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // Galería de componentes — desde la configuración → Acerca de → Galería de componentes Ingresar,Aceptación morfológica de todos los componentes en la biblioteca de componentes.(Versión oficial reservada)
    composable<ComponentGalleryRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ComponentGalleryScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-4: Página de registro de auditoría — desde la configuración → Datos y privacidad → Registro de auditoría Ingresar
    composable<AuditLogRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        AuditLogPage(
            onBack = { navController.popBackStack() },
        )
    }
    // P2-7: página del espacio de trabajo — desde la configuración → Datos y privacidad → Espacio de trabajo Entrar
    composable<WorkspaceRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        WorkspaceScreen(
            onBack = { navController.popBackStack() },
            onOpenArtifacts = { navController.navigate(ArtifactCenterRoute) },
        )
    }
    // v2.0.1: Centro de productos — Ingrese desde la barra superior del espacio de trabajo
    composable<ArtifactCenterRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.artifact.ArtifactCenterScreen(
            onBack = { navController.popBackStack() },
        )
    }
    // v2.0.1: centro de conexion — Ingrese desde la página de inicio de configuración (descripción general de la conexión)
    composable<ConnectionCenterRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        io.zer0.muse.ui.settings.ConnectionCenterScreen(
            onBack = { navController.popBackStack() },
            onOpenChannels = { navController.navigate(ChannelSettingsRoute) },
            onOpenMcp = { navController.navigate(SettingsMcpRoute) },
            onOpenPlugins = { navController.navigate(PluginManageRoute) },
            onOpenToolPermissions = { navController.navigate(ToolsSettingsRoute) },
        )
    }
    // Acceso a la versión web — ajustes → conectar → Acceso a la versión web (dirección / PIN / código QR)
    composable<SettingsWebAccessRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        WebAccessPage(onBack = { navController.popBackStack() })
    }
    // Página de administración de complementos unificada (complemento externo + complemento de proveedor fusionado), la entrada anterior se ha convergido en PluginManageRoute
    composable<PluginManageRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        PluginManagePage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.20: Página de gestión de aprobación de herramientas — desde la configuración → charlar → Aprobación de llamada de herramienta Ingresar
    // Mostrar todas las herramientas agrupadas por nivel de riesgo,Se pueden establecer tres estrategias para cada herramienta.(ALWAYS_ALLOW / ASK_EVERY_TIME / ALWAYS_DENY)
    composable<ToolsSettingsRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        ToolsSettingsPage(
            onBack = { navController.popBackStack() },
        )
    }
    // v1.0.80: UI Configuración de permisos automatizados(gradiente de tres capas)
    composable<SettingsAutomationRoute>(
        enterTransition = { MuseTransitions.horizontalPushEnter() },
        popExitTransition = { MuseTransitions.horizontalPushPopExit() },
    ) {
        val manager = remember { io.zer0.muse.automation.AutomationInitializer.manager }
        val settings: SettingsRepository = koinInject()
        io.zer0.muse.automation.ui.AutomationSettingsPage(
            manager = manager,
            settings = settings,
            onBack = { navController.popBackStack() },
            // v1.xxx: F-24 Vaya a la página de tareas programadas con un clic para programar acciones automatizadas(Lo mismo NavHost,Navegue directamente para llegar a través del gráfico)
            onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
            // v2.x: Asistente de configuración de permisos — La configuración de permisos de tres canales ha convergido en la página del asistente.
            onOpenPermissionWizard = { navController.navigate(SettingsPermissionWizardRoute) },
        )
    }
}
