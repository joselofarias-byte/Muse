package io.zer0.muse.miniphone.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons

/**
 * Las cuatro interfaces principales del pequeño teléfono celular.
 *
 * id es un indicador estable:Los íconos del escritorio, las pestañas inferiores y las etiquetas de prueba se derivan de él.,Cambiar la copia no cambia la identificación.
 */
enum class MiniPhoneTab(
    val id: String,
    val title: String,
) {
    MESSAGES("messages", "noticias"),
    CONTACTS("contacts", "libreta de direcciones"),
    DISCOVER("discover", "descubrir"),
    ME("me", "yo"),
}

/**
 * Un elemento en la tabla de iconos del escritorio.
 *
 * El icono está escrito directamente en este módulo. [MiniPhoneIcons] adentro,No liberar recursos id —— El icono es un vector dibujado,
 * Confíe en el tinte al cambiar el color del tema,No es necesario poner un juego de colores claros y oscuros.
 */
data class MiniPhoneApp(
    val tab: MiniPhoneTab,
    val icon: ImageVector,
) {
    val id: String get() = tab.id

    val label: String get() = tab.title
}

/**
 * La tabla de iconos encima de la pantalla del escritorio.
 *
 * La primera versión sólo tiene cuatro entradas principales.;Cuando se agreguen álbumes de fotos, diarios y el clima posteriores, continúe agregándolos a esta tabla de acuerdo con la cuadrícula de cuatro columnas.,
 * No es necesario cambiar el diseño del escritorio.
 */
internal object MiniPhoneApps {
    val launcher: List<MiniPhoneApp> = MiniPhoneTab.entries.map { MiniPhoneApp(it, iconOf(it)) }

    /** El número de columnas en la cuadrícula de iconos del escritorio. */
    const val COLUMNS: Int = 4

    /** tab → icono. Usar cuando en lugar de tabla de búsqueda,Si falta una pestaña, se informará durante la compilación. */
    fun iconOf(tab: MiniPhoneTab): ImageVector = when (tab) {
        MiniPhoneTab.MESSAGES -> MiniPhoneIcons.Message
        MiniPhoneTab.CONTACTS -> MiniPhoneIcons.Contacts
        MiniPhoneTab.DISCOVER -> MiniPhoneIcons.Discover
        MiniPhoneTab.ME -> MiniPhoneIcons.Me
    }
}

/**
 * Etiquetas estables para elementos de interfaz.,Para posicionamiento de prueba automatizado.
 *
 * separado de la redacción:Cambiar el título o el idioma no invalidará la prueba.
 */
internal object MiniPhoneTags {
    const val PAGE_TITLE = "mp-page-title"
    const val HOME_BUTTON = "mp-home-button"
    const val STATUS_CLOCK = "mp-status-clock"
    const val EMPTY_THREADS = "mp-empty-threads"

    /** El botón \"Ir a la libreta de direcciones\" en la lista de conversaciones vacía. */
    const val EMPTY_THREADS_ACTION = "mp-empty-threads-action"
    const val EMPTY_CONTACTS = "mp-empty-contacts"

    /** La entrada \"Crear grupo\" en la barra superior de la página del mensaje,Con él, abra la página de inicio de chat grupal. */
    const val GROUP_CREATE_ENTRY = "mp-group-create-entry"
    const val GROUP_CREATE_PAGE = "mp-group-create-page"
    const val GROUP_CREATE_BACK = "mp-group-create-back"
    const val GROUP_CREATE_DONE = "mp-group-create-done"
    const val GROUP_CREATE_COUNT = "mp-group-create-count"
    const val EMPTY_GROUP_CREATE = "mp-empty-group-create"
    const val THREAD_ROW = "mp-thread-row"
    const val MOMENTS_ENTRY = "mp-moments-entry"
    const val AUTO_MOMENT_SWITCH = "mp-auto-moment-switch"
    const val CHAT_PROACTIVE_SWITCH = "mp-chat-proactive-switch"
    const val CONTACT_HEADER = "mp-contact-header"
    const val CONTACT_INDEX = "mp-contact-index"

    /** Mantenga presionada la sesión para que aparezca el menú con la confirmación de eliminación. */
    const val THREAD_MENU = "mp-thread-menu"
    const val THREAD_DELETE_DIALOG = "mp-thread-delete-dialog"
    const val THREAD_DELETE_CONFIRM = "mp-thread-delete-confirm"
    const val THREAD_DELETE_CANCEL = "mp-thread-delete-cancel"

    /** Tres entradas a mi página.:Mi círculo de amigos, borrar historial de chat, información de versión. */
    const val MY_MOMENTS_ROW = "mp-my-moments-row"
    const val CLEAR_CHATS_ROW = "mp-clear-chats-row"
    const val CLEAR_CHATS_DIALOG = "mp-clear-chats-dialog"
    const val CLEAR_CHATS_CONFIRM = "mp-clear-chats-confirm"
    const val CLEAR_CHATS_CANCEL = "mp-clear-chats-cancel"
    const val VERSION_ROW = "mp-version-row"
    const val VERSION_LABEL = "mp-version-label"

    fun tab(tab: MiniPhoneTab): String = "mp-tab-${tab.id}"

    fun launcher(app: MiniPhoneApp): String = "mp-launcher-${app.id}"

    fun thread(threadId: String): String = "mp-thread-$threadId"

    fun unread(threadId: String): String = "mp-unread-$threadId"

    fun contactHeader(key: String): String = "mp-contact-header-$key"

    fun contact(characterId: String): String = "mp-contact-$characterId"

    fun contactIndex(key: String): String = "mp-contact-index-$key"

    /** Una línea que inicia una página de chat grupal con su círculo de selección. */
    fun groupPick(characterId: String): String = "mp-group-pick-$characterId"

    fun groupPickMark(characterId: String): String = "mp-group-pick-mark-$characterId"

    fun threadMenuAction(action: ThreadMenuAction): String = "mp-thread-menu-${action.name.lowercase()}"
}
