package io.zer0.muse.ui

/**
 * v0.22: Constantes de enrutamiento de nivel superior.
 *
 * Inicio Pantalla de inicio usando Top Dual Tab(Tarea/Agent),
 * Las siguientes rutas se utilizan en NavHost"desde la configuración/DeepLink externo ingresa a la subpágina"escena.
 */
object MuseRoutes {
    /** v0.22: pagina de inicio — Navegación por pestaña superior. */
    const val HOME = "home"

    /** v0.45: Página de búsqueda global independiente(Ingrese desde el botón de búsqueda en la esquina superior derecha de la página de inicio). */
    const val SEARCH = "search"

    /** Página de edición de retratos de usuario(edad/ciudad/MBTI espera,para la personalización de la IA). */
    const val USER_PROFILE_EDIT = "user_profile_edit"

    /** Página de detalles del chat(Entrar desde la lista de conversaciones,Ninguno BottomBar). */
    const val CHAT_DETAIL = "chat_detail"

    /** Pestaña Asistente. */
    const val ASSISTANTS = "assistants"

    /** v0.37: Página de agregación de detalles del asistente(Encabezado + 5 entradas de subpágina). */
    const val ASSISTANT_DETAIL = "assistant_detail"

    /** v0.37: Subpágina Conceptos básicos del Asistente(Nombre / avatar / modelo / parámetros de muestreo). */
    const val ASSISTANT_BASIC = "assistant_basic"

    /** v0.37: Subpágina de palabras de aviso del asistente(systemPrompt / Plantilla/mensaje preestablecido). */
    const val ASSISTANT_PROMPT = "assistant_prompt"

    /** v0.37: Subpágina de extensión auxiliar(Número de recursos asociados). */
    const val ASSISTANT_EXTENSIONS = "assistant_extensions"

    /** v0.37: Subpágina de memoria del asistente(4 interruptor de memoria). */
    const val ASSISTANT_MEMORY = "assistant_memory"

    /** v0.37: Subpágina avanzada del asistente(Antecedentes / Solicitud personalizada / Etiquetas). */
    const val ASSISTANT_ADVANCED = "assistant_advanced"

    /** memoria Tab(Implementación de la etapa 6,Toma asiento primero). */
    const val MEMORY = "memory"

    /** Establecer pestaña. */
    const val SETTINGS = "settings"

    /** Favoritos(subpágina,Desde la entrada de configuración). */
    const val FAVORITES = "favorites"

    /** Lorebook(subpágina,Desde la entrada de configuración). */
    const val LOREBOOKS = "lorebooks"

    /** P1-2: Worldbook Libro del mundo dinámico(subpágina,Desde la entrada de configuración). */
    const val WORLDBOOK = "worldbook"

    /** mensaje rapido(subpágina,Desde la entrada de configuración). */
    const val QUICK_MESSAGES = "quick_messages"

    /** Prompt inyectar(subpágina,Desde la entrada de configuración). */
    const val PROMPT_INJECTIONS = "prompt_injections"

    /** Skill gestión(subpágina,Desde la entrada de configuración). */
    const val SKILLS = "skills"

    /** Phase 13: Licencia de código abierto(subpágina,desde la configuración → Acerca de entrar). */
    const val LICENSES = "licenses"

    /** Phase 15: cuenta(desde la configuración → Haga clic para ingresar a la tarjeta de cuenta). */
    const val ACCOUNT = "account"

    /** Phase 17: Tareas programadas. */
    const val SCHEDULED_TASKS = "scheduled_tasks"

    /** Phase 17: base de conocimientos. */
    const val KNOWLEDGE = "knowledge"

    /** v1.136: Nota rápida. */
    const val QUICK_NOTES = "quick_notes"

    /** v0.46: Página de estadísticas(Mapa de calor + estadísticas de uso,desde la configuración → Preferencias generales → Estadísticas Entrar). */
    const val STATS = "stats"

    /** v0.26: Configurar página secundaria — Modelos y Servicios. */
    const val SETTINGS_MODEL = "settings_model"

    /** B0-04: Configurar página secundaria — Enrutamiento del modelo de tareas. */
    const val SETTINGS_TASK_ROUTING = "settings_task_routing"

    /** B0-07: Página de administración de plantillas de Word rápida. */
    const val PROMPT_TEMPLATE_MANAGER = "prompt_template_manager"

    /** v0.26: Configurar página secundaria — Datos y respaldo. */
    const val SETTINGS_DATA = "settings_data"

    /** v0.26: Configurar página secundaria — Apariencia. */
    const val SETTINGS_APPEARANCE = "settings_appearance"

    /** v0.26: Configurar página secundaria — Acerca de. */
    const val SETTINGS_ABOUT = "settings_about"

    /** v0.31: Configurar página secundaria — comportamiento de chat. */
    const val SETTINGS_CHAT = "settings_chat"

    /** v1.0.51: centro de memoria(4 Tab Ver+Editar). */
    const val SETTINGS_MEMORY = "settings_memory"

    /** v1.0.51: Página de configuración de parámetros de memoria(Originales"Memorias y Notificaciones",Ingrese desde la entrada del equipo del centro de memoria.). */
    const val SETTINGS_MEMORY_CONFIG = "settings_memory_config"

    /** v1.0.52 P2-2: Página de gestión del espacio de memoria(Space CRUD). */
    const val SETTINGS_MEMORY_SPACE = "settings_memory_space"

    /** v0.32: Configurar página secundaria — medios de comunicación. */
    const val SETTINGS_MEDIA = "settings_media"

    /** v0.32: Configurar página secundaria — Características experimentales. */
    const val SETTINGS_EXPERIMENTS = "settings_experiments"

    /** v0.32: Configurar página secundaria — Seguridad y compartir. */
    const val SETTINGS_SECURITY = "settings_security"

    /** v0.32: Configurar página secundaria — Proxy web. */
    const val SETTINGS_PROXY = "settings_proxy"

    /** v1.25: Configurar página secundaria — Colaboración entre múltiples agentes. */
    const val SETTINGS_MULTI_AGENT = "settings_multi_agent"

    /** Multi-Agent Página de disposición visual del flujo de trabajo(traer {teamId} parámetros). */

    /** v1.27: Configurar página secundaria — Agent Configuración(Agent Selección de asistente/colaboración/mensajes no solicitados). */
    const val SETTINGS_AGENT = "settings_agent"

    /** Página de configuración de mensajería proactiva independiente. */
    const val SETTINGS_PROACTIVE = "settings_proactive"

    /** v1.56: Configurar página secundaria — RAG Configuración de búsqueda en la base de conocimiento. */
    const val SETTINGS_RAG = "settings_rag"

    /** v1.133: Página de nivel 3 — Gestión de múltiples bases de conocimientos.(Ingrese desde la página de configuración de RAG). */
    const val KB_MANAGE = "kb_manage"

    /** v1.25: Configurar página secundaria — ayuda visual(Pasar un modelo de texto plano a través de un modelo visual"ver"fotos). */
    const val SETTINGS_VISION = "settings_vision"

    /** v1.61: Configurar página secundaria — Importación de datos. */
    const val SETTINGS_DATA_IMPORT = "settings_data_import"

    /** v1.132: Configurar página secundaria — Copia de seguridad en la nube(pagina independiente,Administrar la configuración de S3/WebDAV y la lista de copias de seguridad remotas). */
    const val SETTINGS_CLOUD_BACKUP = "settings_cloud_backup"

    /** v1.61: Configurar página secundaria — Tutorial de uso. */
    const val SETTINGS_TUTORIAL = "settings_tutorial"

    /** v1.30: Página de detalles del chat grupal(Ingrese desde la lista de chat grupal,Con parámetro chatId). */
    const val GROUP_CHAT_DETAIL = "group_chat_detail"

    /** v1.97 gap8: Página de traducción independiente(desde la configuración → Herramientas Entrar). */
    const val TRANSLATE = "translate"

    /** v2.0: Página de gestión de datos(desde la configuración → Datos y Backup Ingresar). */
    const val DATA_MANAGEMENT = "data_management"

    /** v1.0.53: Portada de la página de gestión de la biblioteca(Detalles del documento de la base de conocimientos → cubrir). */
    const val COVER_MANAGER = "cover_manager"

    /** v2.0: Páginas eliminadas recientemente. */
    const val RECENTLY_DELETED = "recently_deleted"

    /** v1.126: Agent Bandeja de entrada de mensajes privados. */
    const val AGENT_DM = "agent_dm"

    /** v1.127: Página de gestión de hitos. */
    const val MILESTONES = "milestones"

    /** HTML Página de vista previa en pantalla completa(Ingrese desde la entrada del bloque de código HTML/SVG en la burbuja del mensaje). */
    const val HTML_PREVIEW = "html_preview"

    /** Página de registro de depuración(desde la configuración → Acerca de → Entrada al registro de depuración,Mostrar llamadas recientes del registrador). */
    const val DEBUG = "debug"

    /** v2.x: Configuración de informes de fallos(Anteriormente no se podía acceder a la página.,Ahora desde \"Acerca de → Comentarios\" y entrada de índice de búsqueda). */
    const val SETTINGS_CRASH_REPORT = "settings_crash_report"

    /** Galería de componentes(desde la configuración → Acerca de → Galería de componentes Ingresar,Aceptación morfológica de todos los componentes en la biblioteca de componentes.). */
    const val COMPONENT_GALLERY = "component_gallery"

    /** P2-4: Página de registro de auditoría(desde la configuración → Datos y privacidad → Registro de auditoría Ingresar). */
    const val AUDIT_LOG = "audit_log"

    /** P2-7: página del espacio de trabajo(desde la configuración → Datos y privacidad → Espacio de trabajo Entrar,administrador de archivos). */
    const val WORKSPACE = "workspace"

    /** P2-8: Página de generación de vídeo(desde la configuración → Herramientas Entrar). */
    const val VIDEO_GENERATION = "video_generation"

    /** P2-9: Página de clonación de voz(desde la configuración → medios de comunicación → Clonar voz Ingresar,ElevenLabs espera Provider). */
    const val VOICE_CLONING = "voice_cloning"

    /** P2-10: Provider Página de gestión de complementos(desde la configuración → Modelos y Servicios → Gestión de complementos entrar). */
    const val PROVIDER_PLUGINS = "provider_plugins"
    const val MUSE_PLUGINS = "muse_plugins"

    /** v1.133: Configurar página secundaria — búsqueda en internet(Separarse de ConfiguraciónModelPage). */
    const val SETTINGS_WEB_SEARCH = "settings_web_search"

    /** v1.133: Configurar página secundaria — reconocimiento de voz ASR(Separarse de ConfiguraciónModelPage). */
    const val SETTINGS_ASR = "settings_asr"

    /** v1.133: Configurar página secundaria — MCP servidor(Separarse de ConfiguraciónModelPage). */
    const val SETTINGS_MCP = "settings_mcp"

    /** v1.133: Configurar página secundaria — Recursos asistentes(Separarse de ConfiguraciónModelPage:Favoritos/libro mundial/mensaje rapido/inyección de patrón/Skills/interruptor de memoria). */
    const val SETTINGS_ASSISTANT_RESOURCES = "settings_assistant_resources"

    /** P3-3: Página de guía de permisos — tres canales(Accesibilidad/Shizuku/Root)Asistente de configuración de permisos. */
    const val SETTINGS_PERMISSION_WIZARD = "settings_permission_wizard"

    /** v1.0.4: Página de escucha de notificaciones(desde la configuración → Entrada de asistente y agente,Guíe a los usuarios para que autoricen los derechos de uso de notificaciones + vea las notificaciones recientes). */
    const val NOTIFICATION_LISTENER = "notification_listener"

    /** v1.0.92: Página del canal de mensajes(Configuración del puente IM externo). */
    const val CHANNELS = "channels"

    /** v1.0.92: OAuth Página del conector(Acceso universal OAuth2). */
    const val CONNECTORS = "connectors"

    /** v1.0.4: AI Página de gestión de herramientas(desde la configuración → Entrada de asistente y agente,Mostrar todas las herramientas en ToolRegistry). */
    const val TOOLS = "tools"

    /**
     * v1.0.20: Página de gestión de aprobación de herramientas — Enumere todas las herramientas agrupadas por nivel de riesgo,
     * Se pueden establecer tres estrategias para cada herramienta.(ALWAYS_ALLOW / ALWAYS_DENY / ASK_EVERY_TIME).
     *
     * de"ajustes → charlar → Aprobación de llamada de herramienta"o"ajustes → asistente con Agent → AI Herramientas"Ingrese por la entrada secundaria.
     */
    const val TOOLS_SETTINGS = "tools_settings"

    /** v1.0.80: UI Configuración de permisos automatizados(gradiente de tres capas: Accesibilidad/Shell/Root). */
    const val SETTINGS_AUTOMATION = "settings_automation"

    /** v2.x fase terminal uno:Página de terminal de la zona de pruebas de la aplicación(ajustes → Herramientas → Entrada terminal). */
    const val TERMINAL = "terminal"

    /**
     * v1.30: Construir enrutamiento de la página de detalles del chat grupal(Con parámetro chatId).
     *
     * @param chatId chat grupal id
     * @return en forma de "group_chat_detail/{chatId}" cadena de enrutamiento
     */
    fun groupChatDetailRoute(chatId: String) = "$GROUP_CHAT_DETAIL/$chatId"

    /**
     * Construir enrutamiento de página de vista previa HTML(Parámetros HTML con codificación URL).
     *
     * La persona que llama debe usar primero [java.net.URLEncoder.encode] Codificación del código fuente HTML,
     * evitar caracteres especiales(&,?,#,/, espacio)Rompe la coincidencia de rutas de NavHost.
     *
     * @param encodedHtml Código fuente HTML codificado por URLEncoder.encode
     * @return en forma de "html_preview/{encodedHtml}" cadena de enrutamiento
     */
    fun htmlPreviewRoute(encodedHtml: String) = "$HTML_PREVIEW/$encodedHtml"
}
