package io.zer0.muse.ui.navigation

import io.zer0.muse.ui.settings.SettingsGroupKind
import kotlinx.serialization.Serializable

/**
 * Escriba el destino de ruta seguro.
 *
 * de [MuseRoutes] El enrutamiento de cadenas se migra gradualmente; cada vez que se migra un dominio, la definición correspondiente está aquí
 * @Serializable destino y actualizar NavGraph sincrónicamente con las llamadas de navegación.
 */
@Serializable
data object ToolsScreenRoute

@Serializable
data object VideoGenerationRoute

@Serializable
data object VoiceCloningRoute

@Serializable
data object StatsRoute

@Serializable
data object NotificationListenerRoute

/** v1.0.92: Página de configuración del canal de mensajes(Puente de mensajería instantánea externa). */
@Serializable
data object ChannelSettingsRoute

/** v2.0.1: Página de conversación del canal(Ver conversaciones de contacto y contexto por canal). */
@Serializable
data class ChannelConversationsRoute(val channelId: String)

/**
 * v2.6.10: Página de flujo de conversación para un solo contacto.
 *
 * Cambie de "todos los contactos planos, cada mensaje en una línea" para hacer clic y ver la conversación completa.
 * Sólo en forma de burbuja se puede ver quién dijo qué y cómo se relacionó el contexto.
 */
@Serializable
data class ChannelChatRoute(val channelId: String, val from: String)

/** v1.0.92: OAuth Página del conector(Acceso universal OAuth2). */
@Serializable
data object ConnectorSettingsRoute

@Serializable
data object DataManagementRoute

@Serializable
data object KnowledgeRoute

@Serializable
data object CoverManagerRoute

@Serializable
data object SettingsRoute

/**
 * Configure una página de categoría de primer nivel.
 *
 * Sólo hay 14 categorías enumeradas en la página de inicio. Haga clic para ingresar las entradas de esa categoría; las categorías están enumeradas por [SettingsGroupKind] logotipo,
 * El contenido de la página todavía se toma de la lista de configuración y la ruta solo es responsable de recordar "a qué categoría ingresó".
 *
 * Para clasificación **cuerda id** En lugar de una enumeración: la navegación con seguridad de tipos buscará el serializador por nombre de clase.
 * Una vez que R8 cambia el nombre de la enumeración, la ruta construida admitirá `Cannot find class with name …`(Se bloquea al iniciar).
 * La identificación de la cadena no depende del nombre de la clase y se asigna nuevamente a la enumeración en la página de destino.
 */
@Serializable
data class SettingsGroupRoute(val kind: String)

@Serializable
data object AccountRoute

@Serializable
data object SettingsModelRoute

@Serializable
data object SettingsWebSearchRoute

@Serializable
data object SettingsAsrRoute

@Serializable
data object SettingsMcpRoute

@Serializable
data object SettingsAssistantResourcesRoute

@Serializable
data object UserProfileEditRoute

@Serializable
data object SettingsDataRoute

@Serializable
data object SettingsCloudBackupRoute

@Serializable
data object SettingsAppearanceRoute

@Serializable
data object SettingsAboutRoute

@Serializable
data object SettingsChatRoute

/** v2.6.0: Personalización del botón de acceso directo a mensajes (Via Grupo de acciones completo + Lista de habilitación ordenada), al que se accede desde la configuración del chat. */
@Serializable
data object SettingsQuickActionsRoute

@Serializable
data object ToolsSettingsRoute

@Serializable
data object SettingsAutomationRoute

@Serializable
data object SettingsMemoryRoute

@Serializable
data object SettingsMemoryConfigRoute

@Serializable
data object SettingsMemorySpaceRoute

/**
 * v2.6.10: Una página separada para la biblioteca de emoticonos.
 *
 * Originalmente solo había un párrafo en la parte inferior de la página "Medios", y el subtítulo de "Medios" solo mencionaba voz y TTS.
 * Los usuarios no pueden encontrar dónde están los emoticonos. La función no ha sido eliminada, ha sido enterrada.
 */
@Serializable
data object StickerSettingsRoute

@Serializable
data object SettingsMediaRoute

@Serializable
data object SettingsExperimentsRoute

@Serializable
data object SettingsRagRoute

@Serializable
data object SettingsVisionRoute

@Serializable
data object SettingsDataImportRoute

@Serializable
data object SettingsCrashReportRoute

@Serializable
data object SettingsTutorialRoute

/** v2.6.7 fix (AUDIT F9): Página de gestión de preferencias del usuario (requisitos a largo plazo recordados por el asistente). */
@Serializable
data object SettingsPreferenceRoute

@Serializable
data object SettingsSecurityRoute

@Serializable
data object SettingsProxyRoute

@Serializable
data object SettingsMultiAgentRoute

@Serializable
data object SettingsAgentRoute

@Serializable
data object SettingsProactiveRoute

@Serializable
data object LicensesRoute

@Serializable
data object DebugRoute

@Serializable
data object TerminalRoute

@Serializable
data object ComponentGalleryRoute

@Serializable
data object AuditLogRoute

@Serializable
data object WorkspaceRoute

@Serializable
data object ArtifactCenterRoute

@Serializable
data object ConnectionCenterRoute

/** Acceso a la versión web: la dirección/PIN/código QR se proporcionan en una pantalla, desde la configuración → Introduzca la conexión. */
@Serializable
data object SettingsWebAccessRoute

@Serializable
data object PluginManageRoute

@Serializable
data object KnowledgeBaseManageRoute

@Serializable
data object SettingsTaskRoutingRoute

@Serializable
data object SettingsPermissionWizardRoute

@Serializable
data object AssistantsRoute

@Serializable
data object MemoryRoute

@Serializable
data object FavoritesRoute

@Serializable
data object LorebooksRoute

@Serializable
data object WorldbookRoute

@Serializable
data object QuickMessagesRoute

@Serializable
data object PromptInjectionsRoute

@Serializable
data object SkillsRoute

@Serializable
data class AssistantDetailRoute(val assistantId: String)

@Serializable
data class AssistantBasicRoute(val assistantId: String)

@Serializable
data class AssistantPromptRoute(val assistantId: String)

@Serializable
data class AssistantExtensionsRoute(val assistantId: String)

@Serializable
data class AssistantMemoryRoute(val assistantId: String)

@Serializable
data class AssistantAdvancedRoute(val assistantId: String)

@Serializable
data object HomeRoute

@Serializable
data object SearchRoute

/** v2.6.7(P1-1): Página de configuración de relaciones de roles. */
@Serializable
data object RelationshipSettingsRoute

/** v2.6.9: Página de configuración de fondo permanente. */
@Serializable
data object KeepAliveSettingsRoute

/**
 * Pequeña portada de teléfono celular (sujeta a `FeatureFlags.MINI_PHONE` controlar).
 *
 * El contenido de la página es proporcionado por `:miniphone` El módulo es autodibujado y la aplicación principal solo proporciona enrutamiento y roles./información;
 * Este módulo viene con su propia base de datos y enlace de chat, y ya no reutiliza la tabla de sesiones de la aplicación principal.
 */
@Serializable
data object MiniPhoneRoute

/**
 * v2.6.7(P0-3): Página de chat en un pequeño teléfono celular.
 *
 * con [ChatDetailRoute] Separado: de "mensaje"Tab Haga clic en la conversación para ingresar aquí. El caparazón es estilo WeChat.
 * Regrese al teléfono pequeño;[ChatDetailRoute] Sigue siendo la página de conversación completa de la aplicación principal.
 * Reutilizar lo mismo [io.zer0.muse.ui.ChatScreen](pase ChatSurface.MINI_PHONE),
 * No escriba otra página de chat para evitar divergencias a largo plazo con la línea principal.
 */
@Serializable
data object MiniPhoneChatRoute

@Serializable
data object ChatDetailRoute

@Serializable
data object PromptTemplateManagerRoute

@Serializable
data class GroupChatDetailRoute(val chatId: String)

@Serializable
data object ScheduledTasksRoute

/** Después de hacer clic en la notificación, se abrirá la tarea especificada y el historial de ejecución se expandirá automáticamente. */
@Serializable
data class ScheduledTaskRoute(val taskId: String)

@Serializable
data object QuickNotesRoute

/** Después de hacer clic en la notificación, se abrirá el registro rápido especificado y el texto principal se expandirá automáticamente. */
@Serializable
data class QuickNoteRoute(val noteId: String)

@Serializable
data object AgentDmRoute

@Serializable
data object MilestonesRoute

@Serializable
data object TranslateRoute

@Serializable
data object RecentlyDeletedRoute

@Serializable
data object ArchivedChatsRoute

@Serializable
data class HtmlPreviewRoute(val html: String)
