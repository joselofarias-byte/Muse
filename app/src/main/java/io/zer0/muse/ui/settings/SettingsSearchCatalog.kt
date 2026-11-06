package io.zer0.muse.ui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.ui.navigation.ComponentGalleryRoute
import io.zer0.muse.ui.navigation.ConnectorSettingsRoute
import io.zer0.muse.ui.navigation.PluginManageRoute
import io.zer0.muse.ui.navigation.SettingsAppearanceRoute
import io.zer0.muse.ui.navigation.SettingsAgentRoute
import io.zer0.muse.ui.navigation.SettingsChatRoute
import io.zer0.muse.ui.navigation.SettingsCloudBackupRoute
import io.zer0.muse.ui.navigation.SettingsMcpRoute
import io.zer0.muse.ui.navigation.SettingsMediaRoute
import io.zer0.muse.ui.navigation.SettingsMemoryConfigRoute
import io.zer0.muse.ui.navigation.SettingsModelRoute
import io.zer0.muse.ui.navigation.SettingsProxyRoute
import io.zer0.muse.ui.navigation.SettingsRagRoute
import io.zer0.muse.ui.navigation.SettingsTaskRoutingRoute
import io.zer0.muse.ui.navigation.SettingsWebSearchRoute
import io.zer0.muse.ui.navigation.TerminalRoute
import io.zer0.muse.ui.navigation.ToolsScreenRoute

/**
 * Busque un registro en el índice.
 *
 * El índice cubre dos tipos de entradas: entradas que se pueden ver en la página de categorías (desde [SettingsCatalog]), y solo búsqueda directa,
 * No aparezca en las páginas más profundas de la página de categorías. Establecer en [SettingsSearchExtras] entradas en.
 * [group] Siempre es una de las 14 categorías de primer nivel, que se utiliza para crear rutas de navegación en la interfaz.
 */
data class SettingsSearchRecord(
    @StringRes val titleRes: Int,
    val keywords: List<String>,
    val group: SettingsGroupKind,
    val action: SettingsEntryAction,
    /** Icono de la fila de resultados: la entrada tiene su propio icono. Sólo se utilizará el ícono de la categoría a la que se dirige la búsqueda para configurar el ícono en la página. */
    val icon: ImageVector,
)

/** Separador de migas de pan: clasificación › entrada. */
const val SETTINGS_BREADCRUMB_SEPARATOR: String = " › "

/** Rutas de navegación en los resultados de búsqueda (categorías primero, elementos al final). */
fun settingsBreadcrumb(groupTitle: String, entryTitle: String): String =
    groupTitle + SETTINGS_BREADCRUMB_SEPARATOR + entryTitle

/**
 * Filtre el índice de búsqueda por palabra clave.
 *
 * Simplemente presione cualquiera de los tres lugares: título, nombre de categoría y palabras clave;[titleOf] / [groupTitleOf] Deje la resolución de recursos a la persona que llama,
 * De esta forma, la misma lógica de filtrado se muestra en la interfaz (stringResource) y la prueba (recursos reales) se pueden ejecutar.
 */
fun filterSettingsSearch(
    records: List<SettingsSearchRecord>,
    query: String,
    titleOf: (Int) -> String,
    groupTitleOf: (SettingsGroupKind) -> String,
): List<SettingsSearchRecord> {
    if (query.isBlank()) return records
    val needle = query.trim().lowercase()
    return records.filter { record ->
        titleOf(record.titleRes).lowercase().contains(needle) ||
            groupTitleOf(record.group).lowercase().contains(needle) ||
            record.keywords.any { keyword -> keyword.lowercase().contains(needle) }
    }
}

/** Índice de búsqueda: elementos en la lista de categorías + solo configuraciones en la página para búsqueda directa. */
object SettingsSearchCatalog {

    val records: List<SettingsSearchRecord> =
        SettingsCatalog.groups.flatMap { group ->
            group.entries.map { entry ->
                SettingsSearchRecord(
                    titleRes = entry.titleRes,
                    keywords = entry.keywords,
                    group = group.kind,
                    action = entry.action,
                    icon = entry.icon,
                )
            }
        } + SettingsSearchExtras.records
}

/**
 * Configuraciones que solo aparecen en el índice de búsqueda.
 *
 * Estas son configuraciones específicas en una página determinada (tamaño de fuente, respuesta de transmisión, modelo de recuperación, etc.) y los usuarios las buscarán directamente.
 * Pero no deben estar al mismo nivel que las "páginas en las que se puede hacer clic" en la página de categorías, por lo que no se ingresan. [SettingsCatalog].
 */
internal object SettingsSearchExtras {

    val records: List<SettingsSearchRecord> = listOf(
        record(
            R.string.section_theme,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf(
                "Tema", "combinación de colores", "oscuro", "color claro", "oscuro", "AMOLED", "color", "zhuti", "peise", "shense",
                "qianse", "anhe", "yase", "zt", "ps", "ss", "qs",
            ),
        ),
        record(
            R.string.settings_screen_entry_font_size,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("Tamaño de fuente", "tamaño de fuente", "fuente", "tamaño", "ziti", "zihao", "ztdx", "zt"),
        ),
        record(
            R.string.settings_screen_entry_theme_mode,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("modo tema", "color claro", "oscuro", "Sigue el sistema", "zhutimoshi", "qianse", "shense", "genshixitong", "ztms", "qs", "ss", "gsxt"),
        ),
        record(
            R.string.settings_theme_dynamic_color,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("Selección de color dinámica", "Elige color", "fondo de pantalla", "dongtaiquse", "quse", "dtqs", "qs", "bz"),
        ),
        record(
            R.string.settings_screen_entry_scheduled_theme,
            SettingsGroupKind.APPEARANCE,
            SettingsEntryAction.Navigate(SettingsAppearanceRoute),
            listOf("Conmutación programada", "Conmutación automática", "modo oscuro", "dingshiqiehuan", "zidongqiehuan", "shensemoshi", "dsqh", "zdqh", "ssms"),
        ),
        record(
            R.string.settings_screen_entry_tts_playback,
            SettingsGroupKind.VOICE,
            SettingsEntryAction.Navigate(SettingsMediaRoute),
            listOf("TTS", "tts", "Transmisión de voz", "leer en voz alta", "texto a voz", "TextToSpeech", "yuyinbobao", "langdu", "wenzi", "yybb", "ld"),
        ),
        record(
            R.string.settings_screen_entry_tts_speed,
            SettingsGroupKind.VOICE,
            SettingsEntryAction.Navigate(SettingsMediaRoute),
            listOf("TTS", "velocidad al hablar", "tono", "yusu", "yingao", "ys", "yg"),
        ),
        record(
            R.string.settings_media_tts_voice_selector,
            SettingsGroupKind.VOICE,
            SettingsEntryAction.Navigate(SettingsMediaRoute),
            listOf("sonido", "Voz", "voice", "shengyin", "yuyin", "sy", "yy"),
        ),
        record(
            R.string.settings_screen_entry_api_key,
            SettingsGroupKind.MODELS,
            SettingsEntryAction.Navigate(SettingsModelRoute),
            listOf("API Key", "clave", "key", "token", "vale", "apiKey", "miyao", "pingzheng"),
        ),
        record(
            R.string.muse_plugins_manage,
            SettingsGroupKind.MODELS,
            SettingsEntryAction.Navigate(PluginManageRoute),
            listOf("Gestión de complementos", "complemento externo", "muse-plugin", "Paquete de complemento", "Complemento de importación", "chajian", "plugin", "daoruchajian", "cjb", "cjgl"),
        ),
        record(
            R.string.settings_memory_page_title,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsMemoryConfigRoute),
            listOf(
                "memoria", "configuración de memoria", "configuración de memoria", "sistema de memoria", "memory", "jiyi", "jiyipeizhi",
                "jiyishezhi", "jiyixitong", "jy", "jypz", "jysz",
            ),
        ),
        record(
            R.string.settings_memory_custom_compress_prompt,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsTaskRoutingRoute),
            listOf("Compresión", "Palabras rápidas comprimidas", "Compresión de contexto", "Palabras de aviso personalizadas", "yasuo", "yasuotishici", "shangwen", "ystc", "ys"),
        ),
        record(
            R.string.settings_screen_entry_memory_toggle,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsMemoryConfigRoute),
            listOf("memoria", "cambiar", "Habilitar sistema de memoria", "jiyi", "kaiguan", "qiyong", "jy", "kg", "qyjy"),
        ),
        record(
            R.string.settings_screen_entry_rag_model,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsRagRoute),
            listOf("Recuperar modelo", "RAGmodelo", "Similitud", "jiansuomoxing", "ragmoxing", "xiangsidu", "jsmx", "ragmx", "xsd"),
        ),
        record(
            R.string.settings_screen_entry_chunking,
            SettingsGroupKind.MEMORY,
            SettingsEntryAction.Navigate(SettingsRagRoute),
            listOf("segmentación", "fragmentación", "Estrategia", "fenduan", "fenkuai", "celve", "fd", "fk", "cl"),
        ),
        record(
            R.string.connector_page_title,
            SettingsGroupKind.CONNECTION,
            SettingsEntryAction.Navigate(ConnectorSettingsRoute),
            listOf("conector", "Autorizar", "OAuth", "connector", "oauth", "shouquan", "lianjieqi", "sjq", "lqq", "sq"),
        ),
        record(
            R.string.settings_mcp_title,
            SettingsGroupKind.CONNECTION,
            SettingsEntryAction.Navigate(SettingsMcpRoute),
            listOf("MCP", "servidor", "ModelContextProtocol", "fuwuqi", "fwq"),
        ),
        record(
            R.string.terminal_title,
            SettingsGroupKind.TOOLS,
            SettingsEntryAction.Navigate(TerminalRoute),
            listOf("terminales", "línea de comando", "shell", "terminal", "zhongduan", "minglinghang"),
        ),
        record(
            R.string.settings_web_search_engine,
            SettingsGroupKind.TOOLS,
            SettingsEntryAction.Navigate(SettingsWebSearchRoute),
            listOf("motor de búsqueda", "Bing", "Jina", "SearXNG", "sousuoyinqing", "ssyq"),
        ),
        record(
            R.string.settings_screen_entry_tool_approval,
            SettingsGroupKind.TOOLS,
            SettingsEntryAction.Navigate(ToolsScreenRoute),
            listOf("aprobación de herramienta", "Modo de aprobación", "Aprobación automática", "gongjupizhun", "pizhunmoshi", "zidongpizhun", "gjpz", "pzms", "zdpz"),
        ),
        record(
            R.string.settings_memory_keep_awake,
            SettingsGroupKind.ASSISTANTS,
            SettingsEntryAction.Navigate(SettingsAgentRoute),
            listOf(
                "mantente despierto", "despierta", "wakelock", "no dormir", "Siempre encendido", "keep awake", "baochihuanxing",
                "huanxing", "buxiumian", "changliang", "bchx", "hx", "bxm", "cl",
            ),
        ),
        record(
            R.string.settings_agent_boot_section,
            SettingsGroupKind.ASSISTANTS,
            SettingsEntryAction.Navigate(SettingsAgentRoute),
            listOf(
                "Iniciar automáticamente al arrancar", "auto inicio", "auto inicio", "Encendido", "boot", "auto launch", "BootReceiver",
                "kaijiziqi", "ziqi", "zidong", "kaiji", "kjzq", "zq", "zdd", "kj",
            ),
        ),
        record(
            R.string.settings_screen_entry_collab,
            SettingsGroupKind.ASSISTANTS,
            SettingsEntryAction.Navigate(SettingsAgentRoute),
            listOf("colaboración", "Múltiples asistentes", "equipo", "xiezhuo", "duozhushou", "tuandui", "xz", "dzs", "td"),
        ),
        record(
            R.string.settings_chat_streaming,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("transmisión", "Respuesta de transmisión", "salida en tiempo real", "liushi", "liushixiangying", "shishishuchu", "ls", "lsxy", "sssc"),
        ),
        record(
            R.string.settings_screen_entry_enter_send,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("Entra para enviar", "Entrar", "enviar", "huichefasong", "huiche", "fasong", "hcfs", "hc", "fs"),
        ),
        record(
            R.string.settings_screen_entry_auto_scroll,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("desplazamiento automático", "desplazarse", "zidonggundong", "gundong", "zdgd", "gd"),
        ),
        record(
            R.string.chat_deep_thinking_cd,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("pensamiento profundo", "Pensamiento profundo por defecto", "shendusikao", "morethorough", "sds", "sdsz"),
        ),
        record(
            R.string.settings_screen_entry_timestamps,
            SettingsGroupKind.CHAT,
            SettingsEntryAction.Navigate(SettingsChatRoute),
            listOf("Marca de tiempo", "24horas", "timestamp", "shijianchuo", "24xiaoshi", "sjc"),
        ),
        record(
            R.string.settings_screen_entry_proxy_switch,
            SettingsGroupKind.CONNECTION,
            SettingsEntryAction.Navigate(SettingsProxyRoute),
            listOf("agente", "cambiar", "Proxy", "daili", "kaiguan", "dl", "kg"),
        ),
        record(
            R.string.settings_backup_cloud_title,
            SettingsGroupKind.DATA_STORAGE,
            SettingsEntryAction.Navigate(SettingsCloudBackupRoute),
            listOf("Copia de seguridad en la nube", "copia de seguridad", "S3", "WebDAV", "yunbeifen", "beifen", "ybf", "bf"),
        ),
        record(
            R.string.diagnostic_export_title,
            SettingsGroupKind.DEVELOPER,
            SettingsEntryAction.Action(SettingsActionId.EXPORT_DIAGNOSTICS),
            listOf("Diagnóstico", "Exportar", "Iniciar sesión", "diagnostic", "export", "zhenduan", "daochu", "rizhi", "zd", "dc", "rz"),
        ),
        record(
            R.string.settings_component_gallery,
            SettingsGroupKind.DEVELOPER,
            SettingsEntryAction.Navigate(ComponentGalleryRoute),
            listOf(
                "componentes", "galería", "component gallery", "Demostración de componentes", "Vista previa", "sistema de diseño",
                "zujian", "hualang", "zujiandemo", "yulan", "shejixitong",
            ),
        ),
    )

    private fun record(
        @StringRes titleRes: Int,
        group: SettingsGroupKind,
        action: SettingsEntryAction,
        keywords: List<String>,
    ): SettingsSearchRecord = SettingsSearchRecord(
        titleRes = titleRes,
        keywords = keywords,
        group = group,
        action = action,
        icon = SettingsCatalog.group(group).icon,
    )
}
