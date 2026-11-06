package io.zer0.muse.ui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.navigation.ArchivedChatsRoute
import io.zer0.muse.ui.navigation.AssistantsRoute
import io.zer0.muse.ui.navigation.AuditLogRoute
import io.zer0.muse.ui.navigation.ChannelSettingsRoute
import io.zer0.muse.ui.navigation.ConnectionCenterRoute
import io.zer0.muse.ui.navigation.DataManagementRoute
import io.zer0.muse.ui.navigation.DebugRoute
import io.zer0.muse.ui.navigation.KeepAliveSettingsRoute
import io.zer0.muse.ui.navigation.MiniPhoneRoute
import io.zer0.muse.ui.navigation.NotificationListenerRoute
import io.zer0.muse.ui.navigation.PluginManageRoute
import io.zer0.muse.ui.navigation.QuickNotesRoute
import io.zer0.muse.ui.navigation.ScheduledTasksRoute
import io.zer0.muse.ui.navigation.SettingsAboutRoute
import io.zer0.muse.ui.navigation.SettingsAgentRoute
import io.zer0.muse.ui.navigation.SettingsAppearanceRoute
import io.zer0.muse.ui.navigation.SettingsAsrRoute
import io.zer0.muse.ui.navigation.SettingsAssistantResourcesRoute
import io.zer0.muse.ui.navigation.SettingsAutomationRoute
import io.zer0.muse.ui.navigation.SettingsChatRoute
import io.zer0.muse.ui.navigation.SettingsDataImportRoute
import io.zer0.muse.ui.navigation.SettingsDataRoute
import io.zer0.muse.ui.navigation.SettingsCrashReportRoute
import io.zer0.muse.ui.navigation.SettingsExperimentsRoute
import io.zer0.muse.ui.navigation.SettingsMcpRoute
import io.zer0.muse.ui.navigation.SettingsMediaRoute
import io.zer0.muse.ui.navigation.SettingsMemoryRoute
import io.zer0.muse.ui.navigation.SettingsModelRoute
import io.zer0.muse.ui.navigation.SettingsPermissionWizardRoute
import io.zer0.muse.ui.navigation.SettingsProactiveRoute
import io.zer0.muse.ui.navigation.SettingsProxyRoute
import io.zer0.muse.ui.navigation.SettingsRagRoute
import io.zer0.muse.ui.navigation.SettingsSecurityRoute
import io.zer0.muse.ui.navigation.SettingsTaskRoutingRoute
import io.zer0.muse.ui.navigation.SettingsTutorialRoute
import io.zer0.muse.ui.navigation.SettingsVisionRoute
import io.zer0.muse.ui.navigation.SettingsWebAccessRoute
import io.zer0.muse.ui.navigation.SettingsWebSearchRoute
import io.zer0.muse.ui.navigation.StatsRoute
import io.zer0.muse.ui.navigation.StickerSettingsRoute
import io.zer0.muse.ui.navigation.ToolsScreenRoute
import io.zer0.muse.ui.navigation.TranslateRoute
import io.zer0.muse.ui.navigation.UserProfileEditRoute
import io.zer0.muse.ui.navigation.VideoGenerationRoute
import io.zer0.muse.ui.navigation.VoiceCloningRoute
import io.zer0.muse.ui.navigation.WorkspaceRoute
import kotlinx.coroutines.flow.Flow

/**
 * Establecer una clasificación de primer nivel.
 *
 * Configure la página de inicio para que enumere solo estas 14 categorías, y las entradas de cada categoría están en la página de categorías; el orden de declaración es el orden de arriba a abajo en la página de inicio.
 */
enum class SettingsGroupKind {
    ASSISTANTS,
    CHAT,
    MODELS,
    TOOLS,
    VOICE,
    IMAGE,
    MEMORY,
    CONNECTION,
    AUTOMATION,
    APPEARANCE,
    DATA_STORAGE,
    PRIVACY_SECURITY,
    ABOUT,
    DEVELOPER,
}

/**
 * Establece la identidad estable de la entrada.
 *
 * Solo se utiliza para identificar elementos (claves de representación, afirmaciones de prueba, cobertura por elemento del estado de tiempo de ejecución), mostrar copia en
 * [SettingsEntrySpec.titleRes] adentro. El orden de declaración es el mismo que el orden de presentación en la lista.
 */
enum class SettingsEntryId {
    USER_PROFILE,
    ASSISTANTS,
    ASSISTANT_RESOURCES,
    AGENT,
    CHAT,
    STICKERS,
    QUICK_NOTES,
    SHARE,
    PROVIDER,
    TASK_ROUTING,
    PROVIDER_PLUGINS,
    TOOLS,
    PERMISSION_WIZARD,
    WEB_SEARCH,
    TTS,
    ASR,
    VOICE_CLONING,
    IMAGE_VIDEO_GEN,
    VISION,
    TRANSLATE,
    MEMORY,
    RAG,
    CONNECTION_CENTER,
    CHANNEL,
    WEB_ACCESS,
    MCP,
    PROACTIVE,
    SCHEDULED_TASKS,
    NOTIFICATION_LISTENER,
    UI_AUTOMATION,
    KEEP_ALIVE,
    APPEARANCE,
    MINIPHONE,
    DATA_MANAGEMENT,
    WORKSPACE,
    BACKUP,
    DATA_IMPORT,
    ARCHIVED_CHATS,
    PII_GUARD,
    PROXY,
    TUTORIAL,
    ABOUT,
    CHECK_UPDATE,
    STATS,
    EXPERIMENTS,
    DEBUG_LOG,
    CRASH_REPORT,
    AUDIT_LOG,
}

/** La entrada corresponde a la tecla de encendido/apagado en el almacén de configuración; la lógica de reescritura se concentra en [writeToggle]. */
enum class SettingsToggleKey {
    PII_GUARD,
}

/** Las acciones de los elementos se ejecutan directamente mediante la interfaz sin saltar. */
enum class SettingsActionId {
    CHECK_UPDATE,
    EXPORT_DIAGNOSTICS,
}

/**
 * Qué hacer cuando se hace clic en el elemento.
 *
 * Tres formularios cubren todas las entradas a la página de configuración: ingresar a una página, girar un interruptor y realizar una acción.
 */
sealed interface SettingsEntryAction {
    /** Saltar a la ruta de destino (objeto de enrutamiento con seguridad de tipos). */
    data class Navigate(val route: Any) : SettingsEntryAction

    /** Cambiar entrada: qué flujo lee [flow] declaración, respuesta escrita por [SettingsToggleKey] decisión. */
    data class Toggle(
        val key: SettingsToggleKey,
        val flow: (SettingsRepository) -> Flow<Boolean>,
    ) : SettingsEntryAction

    /** Las acciones en la página (buscar actualizaciones, exportar diagnósticos, etc.) se implementan específicamente mediante devoluciones de llamada pasadas desde la interfaz. */
    data class Action(val id: SettingsActionId) : SettingsEntryAction
}

/** Establecer declaración de artículo. */
data class SettingsEntrySpec(
    val id: SettingsEntryId,
    @StringRes val titleRes: Int,
    /** Copia de descripción; nulo para descripciones detalladas en el tiempo de ejecución (como el estado del agente). */
    @StringRes val descRes: Int? = null,
    val icon: ImageVector,
    val action: SettingsEntryAction,
    /** Palabras clave de búsqueda (sinónimos chinos + iniciales Pinyin); vacío significa visitas basadas únicamente en títulos. */
    val keywords: List<String> = emptyList(),
    /**
     * Ya sea para incluirlo en la página de categorías.
     *
     * Después de apagarlo, es sólo“sin entrada”: La página de categorías no se representa, la búsqueda aún se puede realizar y el salto directo aún está disponible.
     * Se utiliza cuando las funciones aún no han sido pulidas y aún no están promocionadas pero no se quieren desmantelar.
     */
    val browseVisible: Boolean = true,
)

/**
 * Una tarjeta en una página de categoría.
 *
 * Solo délo cuando sea necesario subdividir la clasificación. [labelRes];Las 14 categorías actuales son todas categorías de una tarjeta sin título.
 */
data class SettingsSectionSpec(
    @StringRes val labelRes: Int? = null,
    val entries: List<SettingsEntrySpec>,
)

/** Establezca una declaración de clasificación de primer nivel: título, descripción de una oración, ícono y los elementos debajo de él. */
data class SettingsGroupSpec(
    val kind: SettingsGroupKind,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
    val sections: List<SettingsSectionSpec>,
) {
    /** Todas las entradas de esta categoría se expanden en orden de partición y declaración. */
    val entries: List<SettingsEntrySpec>
        get() = sections.flatMap { it.entries }
}

/**
 * Establece la lista de declaraciones únicas de la entrada.
 *
 * Inicio (14 categorías), páginas de categorías (entradas de esta categoría) e índices de búsqueda (todas las entradas) obtienen datos de aquí.
 * Los tres ya no mantendrán cada uno una secuencia o título.
 */
object SettingsCatalog {

    val groups: List<SettingsGroupSpec> = listOf(
        group(
            SettingsGroupKind.ASSISTANTS,
            R.string.settings_group_assistants,
            R.string.settings_group_assistants_desc,
            MuseIcons.users,
            entries(
                entry(
                    SettingsEntryId.USER_PROFILE,
                    R.string.settings_user_profile_title,
                    R.string.settings_screen_desc_user_profile,
                    MuseIcons.user,
                    SettingsEntryAction.Navigate(UserProfileEditRoute),
                    keywords = listOf(
                        "Retrato de usuario", "Perfil de usuario", "mi información", "datos personales", "Apodo", "avatar", "profile",
                        "yonghuhuaxiang", "yonghuziliao", "ziliao", "nicheng", "touxiang", "yhhx", "yhzl", "zl", "nc", "tx",
                    ),
                ),
                entry(
                    SettingsEntryId.ASSISTANTS,
                    R.string.settings_screen_assistant,
                    R.string.settings_screen_assistant_desc,
                    MuseIcons.atom,
                    SettingsEntryAction.Navigate(AssistantsRoute),
                    keywords = listOf("asistente", "assistant", "papel", "Personalidad", "zhushou", "juese", "renshe", "zs", "js", "rs"),
                ),
                entry(
                    SettingsEntryId.ASSISTANT_RESOURCES,
                    R.string.settings_screen_assistant_resources,
                    R.string.settings_screen_assistant_resources_desc,
                    MuseIcons.stars,
                    SettingsEntryAction.Navigate(SettingsAssistantResourcesRoute),
                    keywords = listOf(
                        "Recursos asistentes", "Favoritos", "libro mundial", "mensaje rapido", "inyección de patrón", "Skills", "Habilidades", "zhushouziyuan",
                        "shoucangjia", "shijieshu", "kuaijiexiaoxi", "moshizhur", "jineng", "zszy",
                        "scj", "sjs", "kjxx", "mszr", "jn",
                    ),
                ),
                entry(
                    SettingsEntryId.AGENT,
                    R.string.chat_agent_tab_title,
                    R.string.settings_screen_agent_desc,
                    MuseIcons.users,
                    SettingsEntryAction.Navigate(SettingsAgentRoute),
                    keywords = listOf("Agent", "agente", "agente", "Autonomía", "daili", "zhinengti", "zizhu", "dl", "znt"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.CHAT,
            R.string.settings_group_chat,
            R.string.settings_group_chat_desc,
            MuseIcons.chat,
            entries(
                entry(
                    SettingsEntryId.CHAT,
                    R.string.settings_screen_chat,
                    R.string.settings_screen_chat_desc,
                    MuseIcons.chat,
                    SettingsEntryAction.Navigate(SettingsChatRoute),
                    keywords = listOf(
                        "charlar", "dialogo", "noticias", "entrada", "enviar", "liaotian", "duihua", "xiaoxi", "shuru",
                        "fasong", "lt", "dh", "xx", "Edición de pantalla completa", "Expandir", "Burbujas", "sombra", "modelo", "MOOD", "cadena de pensamiento",
                        "Teclas de acceso directo", "Cotización Responder",
                    ),
                ),
                entry(
                    SettingsEntryId.STICKERS,
                    R.string.settings_chat_sticker_section,
                    R.string.settings_screen_sticker_desc,
                    MuseIcons.image,
                    SettingsEntryAction.Navigate(StickerSettingsRoute),
                    keywords = listOf(
                        "Emoticones", "expresión", "pegatinas", "biblioteca de emoticonos", "sticker", "emoji", "biaoqingbao",
                        "biaoqing", "tiezhi", "biaoqingbaoku", "bq", "tq", "bqbk",
                    ),
                ),
                entry(
                    SettingsEntryId.QUICK_NOTES,
                    R.string.settings_screen_quick_notes,
                    R.string.settings_screen_quick_notes_desc,
                    MuseIcons.bulb,
                    SettingsEntryAction.Navigate(QuickNotesRoute),
                    keywords = listOf(
                        "registro rápido", "taquigrafía", "Notas", "quick note", "note", "grabar", "kuaisujilu", "suji", "biji", "jilu", "ksjl", "sj", "bj", "jl",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.MODELS,
            R.string.settings_group_models,
            R.string.settings_group_models_desc,
            MuseIcons.provider,
            entries(
                entry(
                    SettingsEntryId.PROVIDER,
                    R.string.settings_screen_provider,
                    R.string.settings_screen_provider_desc,
                    MuseIcons.provider,
                    SettingsEntryAction.Navigate(SettingsModelRoute),
                    keywords = listOf(
                        "proveedor", "modelo", "provider", "API", "clave", "gongyingshang", "moxing", "miyao",
                        "gys", "mx", "my", "Dibujo", "Agnes", "DALL-E", "proveedor de dibujo",
                    ),
                ),
                entry(
                    SettingsEntryId.TASK_ROUTING,
                    R.string.settings_task_routing_title,
                    R.string.settings_screen_task_routing_desc,
                    MuseIcons.taskRouting,
                    SettingsEntryAction.Navigate(SettingsTaskRoutingRoute),
                    keywords = listOf(
                        "Modelo auxiliar", "Enrutamiento de tareas", "enrutamiento", "artilugio", "grandes herramientas", "Visión", "modelo", "Modelo de diálogo principal", "Modelo predeterminado",
                        "fuzhumoxing", "renwuluyou", "luyou", "xiaogongju", "dagongju", "shijue",
                        "moxing", "zhuduihua", "moren", "fzmx", "rwly", "ly", "xgj", "dgj", "sj",
                        "mx", "zdh", "mr",
                    ),
                ),
                entry(
                    SettingsEntryId.PROVIDER_PLUGINS,
                    R.string.provider_plugins_title,
                    R.string.settings_screen_desc_provider_plugins,
                    MuseIcons.affiliate,
                    SettingsEntryAction.Navigate(PluginManageRoute),
                    keywords = listOf(
                        "Gestión de complementos", "Complemento de proveedor", "complemento", "Paquete de complemento", "provider plugin", "chajian",
                        "chajianbao", "gongyingshangchajian", "cj", "cjb",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.TOOLS,
            R.string.settings_group_tools,
            R.string.settings_group_tools_desc,
            MuseIcons.wrench,
            entries(
                entry(
                    SettingsEntryId.TOOLS,
                    R.string.settings_screen_tools,
                    R.string.settings_screen_tools_desc,
                    MuseIcons.wrench,
                    SettingsEntryAction.Navigate(ToolsScreenRoute),
                    keywords = listOf("Herramientas", "AIHerramientas", "ToolRegistry", "tool", "complemento", "gongju", "AIgongju", "chajian", "gj", "AIgj", "cj"),
                ),
                entry(
                    SettingsEntryId.PERMISSION_WIZARD,
                    R.string.permission_wizard_title,
                    R.string.permission_wizard_desc,
                    MuseIcons.shieldCheck,
                    SettingsEntryAction.Navigate(SettingsPermissionWizardRoute),
                    keywords = listOf("Permisos", "mago", "Accesibilidad", "Shizuku", "Root", "quanxian", "xiangdao", "wuzhangai", "qx", "xd", "wza"),
                ),
                entry(
                    SettingsEntryId.WEB_SEARCH,
                    R.string.settings_screen_web_search,
                    R.string.settings_screen_web_search_desc,
                    MuseIcons.globe,
                    SettingsEntryAction.Navigate(SettingsWebSearchRoute),
                    keywords = listOf(
                        "búsqueda en internet", "Buscar", "web search", "búsqueda web", "Buscar en línea", "lianwang", "sousuo",
                        "wangluosousuo", "zaixiansousuo", "lwss", "ss", "wlss", "zxss",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.VOICE,
            R.string.settings_group_voice,
            R.string.settings_group_voice_desc,
            MuseIcons.microphone,
            entries(
                entry(
                    SettingsEntryId.TTS,
                    R.string.settings_screen_media,
                    R.string.settings_screen_media_desc,
                    MuseIcons.microphone,
                    SettingsEntryAction.Navigate(SettingsMediaRoute),
                    keywords = listOf(
                        "Transmisión de voz", "medios de comunicación", "Grabación", "Voz", "transmitir", "yuyinbobao",
                        "meiti", "luyin", "yuyin", "bobao", "mt", "ly", "yy", "bb",
                    ),
                ),
                entry(
                    SettingsEntryId.ASR,
                    R.string.settings_screen_asr,
                    R.string.settings_screen_asr_desc,
                    MuseIcons.microphone,
                    SettingsEntryAction.Navigate(SettingsAsrRoute),
                    keywords = listOf(
                        "ASR", "asr", "reconocimiento de voz", "speech", "Convertir texto", "reconocer el habla", "yuyinshibie", "zhuanwenzi",
                        "shibieyuyin", "yysb", "zwz", "sbyy",
                    ),
                ),
                entry(
                    SettingsEntryId.VOICE_CLONING,
                    R.string.voice_cloning_title,
                    R.string.settings_screen_desc_voice_cloning,
                    MuseIcons.play,
                    SettingsEntryAction.Navigate(VoiceCloningRoute),
                    keywords = listOf(
                        "Clonación de voz", "clonar", "timbre", "reproducción de sonido", "voice clone", "yuyinkelong",
                        "kelong", "yinse", "shengyinfuke", "yykl", "kl", "ys",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.IMAGE,
            R.string.settings_group_image,
            R.string.settings_group_image_desc,
            MuseIcons.image,
            entries(
                entry(
                    SettingsEntryId.IMAGE_VIDEO_GEN,
                    R.string.video_gen_title,
                    R.string.settings_screen_desc_video_gen,
                    MuseIcons.video,
                    SettingsEntryAction.Navigate(VideoGenerationRoute),
                    keywords = listOf(
                        "generación de vídeo", "Generación de imágenes", "Dibujo", "Generar imágenes", "Cuadro vicenciano", "Generar vídeo", "video gen",
                        "image gen", "shipinshengcheng", "tupianshengcheng", "huitu", "shengchengtupian",
                        "wenshengtu", "spsc", "tpsc", "ht",
                    ),
                ),
                entry(
                    SettingsEntryId.VISION,
                    R.string.settings_vision_title,
                    R.string.settings_screen_desc_vision,
                    MuseIcons.image,
                    SettingsEntryAction.Navigate(SettingsVisionRoute),
                    keywords = listOf(
                        "ayuda visual", "Visión", "Comprensión de imágenes", "mira la foto", "OCR", "modelo visual", "shijue",
                        "tupianlijie", "kantu", "shijuefuzhu", "shijuemoxing", "sj", "tplj", "kt",
                    ),
                ),
                entry(
                    SettingsEntryId.TRANSLATE,
                    R.string.settings_screen_translate,
                    R.string.settings_screen_translate_desc,
                    MuseIcons.languages,
                    SettingsEntryAction.Navigate(TranslateRoute),
                    keywords = listOf(
                        "Traducción", "translate", "Idioma", "Traducción", "idioma de origen", "idioma de destino", "fanyi",
                        "yuyan", "huyi", "yuanyuyan", "mubiaoyuyan", "fy", "yy",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.MEMORY,
            R.string.settings_group_memory,
            R.string.settings_group_memory_desc,
            MuseIcons.brain,
            entries(
                entry(
                    SettingsEntryId.MEMORY,
                    R.string.settings_screen_memory_notification,
                    R.string.settings_screen_memory_notification_desc,
                    MuseIcons.atom,
                    SettingsEntryAction.Navigate(SettingsMemoryRoute),
                    keywords = listOf(
                        "memoria", "memoria a largo plazo", "memory", "olvidar", "recuerdos", "espacio de memoria", "jiyi", "changqijiyi", "yiwang",
                        "huiyi", "jiyikongjian", "jy", "cqjy", "yw", "hy", "kj",
                    ),
                ),
                entry(
                    SettingsEntryId.RAG,
                    R.string.settings_screen_rag,
                    R.string.settings_screen_rag_desc,
                    MuseIcons.book,
                    SettingsEntryAction.Navigate(SettingsRagRoute),
                    keywords = listOf(
                        "RAG", "base de conocimientos", "rag", "Buscar", "Vector", "Documentación", "zhishiku", "jiansuo",
                        "xiangliang", "wendang", "zsk", "js", "xl", "wd",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.CONNECTION,
            R.string.settings_group_connection,
            R.string.settings_group_connection_desc,
            MuseIcons.link,
            entries(
                entry(
                    SettingsEntryId.CONNECTION_CENTER,
                    R.string.connection_center_title,
                    R.string.connection_center_desc,
                    MuseIcons.link,
                    SettingsEntryAction.Navigate(ConnectionCenterRoute),
                    keywords = listOf(
                        "centro de conexion", "conectar", "canal", "MCP", "complemento", "Permisos",
                        "lianjie", "lianjiezhongxin", "qudao", "chajian", "quanxian", "mcp",
                    ),
                ),
                entry(
                    SettingsEntryId.CHANNEL,
                    R.string.channel_page_title,
                    R.string.settings_screen_desc_channel,
                    MuseIcons.wifi,
                    SettingsEntryAction.Navigate(ChannelSettingsRoute),
                    keywords = listOf(
                        "canal de mensajes", "canal", "Interoperabilidad de plataformas", "puente", "plataforma social", "Feishu", "QQ", "WeChat", "IM",
                        "channel", "feishu", "bridge", "xiaoxi", "qidao", "weixin", "xxqd",
                        "pingtaihutong", "qiaojie",
                    ),
                ),
                entry(
                    SettingsEntryId.WEB_ACCESS,
                    R.string.settings_screen_web_access,
                    R.string.settings_screen_web_access_desc,
                    MuseIcons.router,
                    SettingsEntryAction.Navigate(SettingsWebAccessRoute),
                    keywords = listOf(
                        "Versión web", "pagina web", "Web servicio", "Web servidor", "Navegador", "LAN", "código qr", "Escanear",
                        "código de escaneo", "Acceso celular", "PIN", "dirección de internet",
                        "wangyeban", "wangye", "liulanqi", "juyuwang", "erweima", "saoyisao", "sao",
                        "shoujifangwen", "pin", "web",
                    ),
                    // Esta versión no se recomienda por el momento: se puede usar tanto en el servidor como en la página, pero no se proporciona como una entrada en la página de categoría y la búsqueda aún puede llegar a ella.
                    browseVisible = false,
                ),
                entry(
                    SettingsEntryId.MCP,
                    R.string.settings_screen_mcp,
                    R.string.settings_screen_mcp_desc,
                    MuseIcons.plug,
                    SettingsEntryAction.Navigate(SettingsMcpRoute),
                    keywords = listOf("MCP", "mcp", "servidor", "Model Context Protocol", "protocolo de herramienta", "fuwuqi", "gongjixieyi", "fwq", "gjxy"),
                ),
                // La descripción de la entrada del proxy está escrita por la configuración actual en tiempo de ejecución (/type host:port is not enable) y no hay ninguna copia fija en el manifiesto.
                entry(
                    SettingsEntryId.PROXY,
                    R.string.proxy_title,
                    null,
                    MuseIcons.proxy,
                    SettingsEntryAction.Navigate(SettingsProxyRoute),
                    keywords = listOf("agente", "proxy", "red", "VPN", "HTTPagente", "daili", "wangluo", "dl", "wl"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.AUTOMATION,
            R.string.settings_group_automation,
            R.string.settings_group_automation_desc,
            MuseIcons.bell,
            entries(
                entry(
                    SettingsEntryId.PROACTIVE,
                    R.string.settings_agent_proactive_title,
                    R.string.settings_agent_proactive_subtitle,
                    MuseIcons.bell,
                    SettingsEntryAction.Navigate(SettingsProactiveRoute),
                    keywords = listOf(
                        "mensajes no solicitados", "Toma la iniciativa", "empujar", "Enviar regularmente", "proactive", "zhudongxiaoxi", "zhudong",
                        "tuisong", "dingshifasong", "zdxx", "zd", "ts",
                    ),
                ),
                entry(
                    SettingsEntryId.SCHEDULED_TASKS,
                    R.string.schedule_title,
                    R.string.settings_screen_desc_schedule,
                    MuseIcons.clock,
                    SettingsEntryAction.Navigate(ScheduledTasksRoute),
                    keywords = listOf(
                        "tareas programadas", "Sincronización", "Tareas programadas", "scheduled", "task", "cron", "dingshirenwu",
                        "dingshi", "jihuarenwu", "dsrw", "ds", "jhrw",
                    ),
                ),
                entry(
                    SettingsEntryId.NOTIFICATION_LISTENER,
                    R.string.settings_screen_notification_listener,
                    R.string.settings_screen_notification_listener_desc,
                    MuseIcons.bell,
                    SettingsEntryAction.Navigate(NotificationListenerRoute),
                    keywords = listOf(
                        "escucha de notificaciones", "Notificación", "NotificationListener", "Permisos de notificación", "tongzhijianting", "tongzhi",
                        "tongzhiquanxian", "tzjl", "tz", "tzqx",
                    ),
                ),
                entry(
                    SettingsEntryId.UI_AUTOMATION,
                    R.string.automation_settings_title,
                    R.string.automation_settings_subtitle,
                    MuseIcons.computer,
                    SettingsEntryAction.Navigate(SettingsAutomationRoute),
                    keywords = listOf("UIAutomatización", "operación automática", "lectura de pantalla", "gesto", "zidonghua", "zidongcaozuo", "pingmu", "shoushi"),
                ),
                entry(
                    SettingsEntryId.KEEP_ALIVE,
                    R.string.settings_keep_alive_title,
                    R.string.settings_keep_alive_subtitle,
                    MuseIcons.battery,
                    SettingsEntryAction.Navigate(KeepAliveSettingsRoute),
                    keywords = listOf(
                        "Tras bastidores permanente", "mantener vivo", "detrás del escenario", "Optimización de la batería", "auto inicio", "copia del aviso",
                        "changzhu", "baohuo", "houtai", "dianchi", "zhiqidong",
                        "czy", "bh", "ht", "dc", "zqd",
                    ),
                ),
            ),
        ),
        group(
            SettingsGroupKind.APPEARANCE,
            R.string.settings_group_appearance,
            R.string.settings_group_appearance_desc,
            MuseIcons.palette,
            entries(
                entry(
                    SettingsEntryId.APPEARANCE,
                    R.string.settings_screen_appearance_label,
                    R.string.settings_screen_appearance_desc,
                    MuseIcons.palette,
                    SettingsEntryAction.Navigate(SettingsAppearanceRoute),
                    keywords = listOf(
                        "Apariencia", "mostrar", "interfaz", "Tamaño de fuente", "fuente", "waiguan", "xianshi", "jiemian", "zihao",
                        "ziti", "wg", "xs", "jm", "zt", "Tema", "esquinas redondeadas", "pagina de inicio", "guía", "fondo de pantalla",
                    ),
                ),
                entry(
                    SettingsEntryId.MINIPHONE,
                    R.string.settings_miniphone_title,
                    R.string.settings_screen_miniphone_desc,
                    MuseIcons.deviceMobile,
                    SettingsEntryAction.Navigate(MiniPhoneRoute),
                    keywords = listOf(
                        "pequeño teléfono celular", "Teléfono celular incorporado", "teléfono celular", "miniphone", "mini phone", "xiaoshouji",
                        "neizhishouji", "shouji", "xsj", "sj",
                    ),
                    // Esta versión no se recomienda por el momento: los módulos y el enrutamiento se conservan por completo, pero la entrada no se proporciona en la página de categorías ni en la página de inicio, y la búsqueda aún puede llegar a ella.
                    browseVisible = false,
                ),
            ),
        ),
        group(
            SettingsGroupKind.DATA_STORAGE,
            R.string.settings_group_data_storage,
            R.string.settings_group_data_storage_desc,
            MuseIcons.database,
            entries(
                entry(
                    SettingsEntryId.DATA_MANAGEMENT,
                    R.string.data_management_entry,
                    R.string.data_management_entry_desc,
                    MuseIcons.database,
                    SettingsEntryAction.Navigate(DataManagementRoute),
                    keywords = listOf(
                        "Gestión de datos", "datos", "almacenamiento", "limpiar", "caché", "shujuguanli", "shuju", "cunchu", "qingli",
                        "huancun", "sjgl", "sj", "cc", "ql", "hc",
                    ),
                ),
                entry(
                    SettingsEntryId.WORKSPACE,
                    R.string.workspace_title,
                    R.string.workspace_desc,
                    MuseIcons.folder,
                    SettingsEntryAction.Navigate(WorkspaceRoute),
                    keywords = listOf(
                        "espacio de trabajo", "Gestión de archivos", "workspace", "Archivo", "Directorio", "gongzuoqu", "wenjianguanli",
                        "wenjian", "mulu", "gzq", "wjgl", "wj", "ml",
                    ),
                ),
                entry(
                    SettingsEntryId.BACKUP,
                    R.string.settings_screen_data_backup,
                    R.string.settings_screen_data_backup_desc,
                    MuseIcons.cloud,
                    SettingsEntryAction.Navigate(SettingsDataRoute),
                    keywords = listOf(
                        "copia de seguridad", "Copia de seguridad en la nube", "cloud", "backup", "S3", "WebDAV", "sincronizar",
                        "beifen", "yunbeifen", "tongbu", "bf", "ybf", "tb",
                    ),
                ),
                entry(
                    SettingsEntryId.DATA_IMPORT,
                    R.string.settings_screen_data_import,
                    R.string.settings_screen_data_import_desc,
                    MuseIcons.cloudUpload,
                    SettingsEntryAction.Navigate(SettingsDataImportRoute),
                    keywords = listOf("Importación de datos", "importar", "import", "Recuperar datos", "shujudaoru", "daoru", "huifushuju", "sjdr", "dr", "hfsj"),
                ),
                entry(
                    SettingsEntryId.ARCHIVED_CHATS,
                    R.string.settings_screen_entry_archived,
                    R.string.settings_screen_archived_desc,
                    MuseIcons.archive,
                    SettingsEntryAction.Navigate(ArchivedChatsRoute),
                    keywords = listOf("Archivo", "archive", "Archivado", "guidang", "gd"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.PRIVACY_SECURITY,
            R.string.settings_group_privacy_security,
            R.string.settings_group_privacy_security_desc,
            MuseIcons.lock,
            entries(
                entry(
                    SettingsEntryId.PII_GUARD,
                    R.string.settings_screen_pii_guard,
                    R.string.settings_screen_pii_guard_desc,
                    MuseIcons.shield,
                    SettingsEntryAction.Toggle(
                        key = SettingsToggleKey.PII_GUARD,
                        flow = { repository -> repository.piiGuardEnabledFlow },
                    ),
                    keywords = listOf(
                        "PII", "Privacidad", "Desensibilización", "cubrir", "Número de teléfono celular", "Correo electrónico", "tarjeta de identificación", "pii", "yinsi", "tuomin", "zhebi", "shenfenzheng",
                    ),
                ),
                entry(
                    SettingsEntryId.SHARE,
                    R.string.settings_screen_share,
                    R.string.settings_screen_security_desc,
                    MuseIcons.lock,
                    SettingsEntryAction.Navigate(SettingsSecurityRoute),
                    keywords = listOf("Compartir", "Exportar", "Compartir plantilla", "share", "fenxiang", "daochu", "fx", "dc"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.ABOUT,
            R.string.settings_group_about,
            R.string.settings_group_about_desc,
            MuseIcons.info,
            entries(
                entry(
                    SettingsEntryId.TUTORIAL,
                    R.string.settings_screen_tutorial,
                    R.string.settings_screen_tutorial_desc,
                    MuseIcons.school,
                    SettingsEntryAction.Navigate(SettingsTutorialRoute),
                    keywords = listOf(
                        "Tutorial", "novato", "guía", "tutorial", "ayuda", "jiaocheng", "xinshou", "yindao", "bangzhu", "jc", "xs", "yd", "bz",
                    ),
                ),
                entry(
                    SettingsEntryId.ABOUT,
                    R.string.settings_screen_about,
                    R.string.settings_screen_about_desc,
                    MuseIcons.info,
                    SettingsEntryAction.Navigate(SettingsAboutRoute),
                    keywords = listOf("Acerca de", "versión", "about", "información", "guanyu", "banben", "xinxi", "gy", "bb", "xx"),
                ),
                entry(
                    SettingsEntryId.CHECK_UPDATE,
                    R.string.settings_screen_check_update,
                    R.string.settings_screen_check_update_desc,
                    MuseIcons.refresh,
                    SettingsEntryAction.Action(SettingsActionId.CHECK_UPDATE),
                    keywords = listOf("Buscar actualizaciones", "actualizar", "update", "versión", "Mejora", "jianchagengxin", "gengxin", "shengji", "jcgc", "gx", "sj"),
                ),
            ),
        ),
        group(
            SettingsGroupKind.DEVELOPER,
            R.string.settings_group_developer,
            R.string.settings_group_developer_desc,
            MuseIcons.terminal,
            entries(
                entry(
                    SettingsEntryId.STATS,
                    R.string.settings_screen_stats,
                    R.string.settings_screen_stats_desc,
                    MuseIcons.chartBar,
                    SettingsEntryAction.Navigate(StatsRoute),
                    keywords = listOf(
                        "Estadísticas", "estadísticas de uso", "stats", "mapa de calor", "datos", "tongji",
                        "shiyongtongji", "relitu", "shuju", "tj", "sytj", "rlt", "sj",
                    ),
                ),
                entry(
                    SettingsEntryId.EXPERIMENTS,
                    R.string.settings_screen_experiments,
                    R.string.settings_screen_experiments_desc,
                    MuseIcons.flask,
                    SettingsEntryAction.Navigate(SettingsExperimentsRoute),
                    keywords = listOf("Experimental", "experimentar", "experimental", "beta", "prueba", "shiyanxing", "shiyan", "syx", "sy"),
                ),
                entry(
                    SettingsEntryId.DEBUG_LOG,
                    R.string.settings_screen_debug_log,
                    R.string.settings_screen_debug_log_desc,
                    MuseIcons.bug,
                    SettingsEntryAction.Navigate(DebugRoute),
                    keywords = listOf("Depuración", "Iniciar sesión", "debug", "log", "Logger", "tiaoshi", "rizhi", "ts", "rz"),
                ),
                entry(
                    SettingsEntryId.CRASH_REPORT,
                    R.string.settings_crash_page_title,
                    R.string.settings_screen_desc_crash_report,
                    MuseIcons.shieldCheck,
                    SettingsEntryAction.Navigate(SettingsCrashReportRoute),
                    keywords = listOf("colapsar", "Informe de fallos", "crash", "informe", "Diagnóstico", "bengkui", "baogao", "zhenduan", "bk", "bg", "zd"),
                ),
                entry(
                    SettingsEntryId.AUDIT_LOG,
                    R.string.settings_audit_log,
                    R.string.settings_screen_desc_audit_log,
                    MuseIcons.history,
                    SettingsEntryAction.Navigate(AuditLogRoute),
                    keywords = listOf(
                        "auditoría", "Iniciar sesión", "audit", "Registro de operación", "Registro de auditoría", "shenji",
                        "rizhi", "caozuojilu", "shenjirizhi", "sj", "rz", "czjl", "sjrz",
                    ),
                ),
            ),
        ),
    )

    /** Todos los artículos se expanden según el orden de clasificación en la página de inicio y el orden dentro de la categoría. */
    val entries: List<SettingsEntrySpec> = groups.flatMap { it.entries }

    /** Tome la declaración por categoría; la categoría es una enumeración y debe existir. */
    fun group(kind: SettingsGroupKind): SettingsGroupSpec = groups.first { it.kind == kind }

    private fun group(
        kind: SettingsGroupKind,
        @StringRes titleRes: Int,
        @StringRes subtitleRes: Int,
        icon: ImageVector,
        entries: List<SettingsEntrySpec>,
    ): SettingsGroupSpec = SettingsGroupSpec(
        kind = kind,
        titleRes = titleRes,
        subtitleRes = subtitleRes,
        icon = icon,
        sections = listOf(SettingsSectionSpec(entries = entries)),
    )

    private fun entries(vararg entries: SettingsEntrySpec): List<SettingsEntrySpec> = entries.toList()

    private fun entry(
        id: SettingsEntryId,
        @StringRes titleRes: Int,
        @StringRes descRes: Int?,
        icon: ImageVector,
        action: SettingsEntryAction,
        keywords: List<String> = emptyList(),
        browseVisible: Boolean = true,
    ): SettingsEntrySpec = SettingsEntrySpec(
        id = id,
        titleRes = titleRes,
        descRes = descRes,
        icon = icon,
        action = action,
        keywords = keywords,
        browseVisible = browseVisible,
    )
}
