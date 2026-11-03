package io.zer0.muse.miniphone.ui.chat

/**
 * Etiquetas estables para elementos de la página de chat,Para posicionamiento de prueba automatizado.
 *
 * separado de la redacción:Cambiar el nombre o el mensaje no invalidará la prueba.
 */
internal object MiniPhoneChatTags {
    const val PEER_NAME = "mp-chat-peer-name"
    const val TOP_AVATAR = "mp-chat-top-avatar"
    const val BACK_BUTTON = "mp-chat-back"
    const val MESSAGE_LIST = "mp-chat-list"
    const val INPUT_FIELD = "mp-chat-input"
    const val SEND_BUTTON = "mp-chat-send"
    const val PICK_IMAGE = "mp-chat-pick-image"

    /** Tecla de entrada de voz:Comience a grabar cuando esté inactivo,Cambia a \"Fin\" durante la grabación. */
    const val MIC_BUTTON = "mp-chat-mic"

    /** Un lugar donde se muestra en la grabación el número de segundos grabados. */
    const val RECORDING_SECONDS = "mp-chat-recording-seconds"

    /** Cancelar la entrada en la grabación. */
    const val CANCEL_RECORDING = "mp-chat-cancel-recording"

    /** Barra de aviso durante la transcripción. */
    const val VOICE_STATUS = "mp-chat-voice-status"
    const val SELF_AVATAR = "mp-chat-self-avatar"
    const val IMAGE_VIEWER = "mp-chat-image-viewer"

    /** El visor de la galería con el \"3 / 9”. */
    const val IMAGE_GALLERY = "mp-chat-image-gallery"
    const val IMAGE_GALLERY_COUNTER = "mp-chat-image-gallery-counter"
    const val TYPING = "mp-chat-typing"
    const val ACTIVITY = "mp-chat-activity"
    const val VOICE_PLAY_ICON = "mp-chat-voice-icon-play"
    const val VOICE_PAUSE_ICON = "mp-chat-voice-icon-pause"

    /** chat grupal:La cantidad de personas en la columna superior, la página de miembros del grupo y cada fila de la página. */
    const val GROUP_TOP_BAR = "mp-chat-group-top-bar"
    const val GROUP_MEMBER_COUNT = "mp-chat-group-member-count"
    const val GROUP_MEMBERS_PAGE = "mp-chat-group-members"
    const val GROUP_MEMBERS_BACK = "mp-chat-group-members-back"
    const val GROUP_MEMBERS_COUNT = "mp-chat-group-members-count"

    /** Nombre del grupo:Entrada de una línea, capa emergente renombrada y su cuadro de entrada, guardar y cancelar. */
    const val GROUP_TITLE_ROW = "mp-chat-group-title-row"
    const val GROUP_TITLE_VALUE = "mp-chat-group-title-value"
    const val GROUP_TITLE_EDIT_DIALOG = "mp-chat-group-title-dialog"
    const val GROUP_TITLE_EDIT_FIELD = "mp-chat-group-title-field"
    const val GROUP_TITLE_EDIT_SAVE = "mp-chat-group-title-save"
    const val GROUP_TITLE_EDIT_CANCEL = "mp-chat-group-title-cancel"

    /** Agregar personas:Entrada, página de selección y su regreso./Completo/El número de personas está bien. */
    const val GROUP_ADD_ENTRY = "mp-chat-group-add"
    const val GROUP_ADD_PAGE = "mp-chat-group-add-page"
    const val GROUP_ADD_BACK = "mp-group-add-member-back"
    const val GROUP_ADD_DONE = "mp-group-add-member-done"
    const val GROUP_ADD_COUNT = "mp-group-add-member-count"

    /** Eliminar miembros:Mantenga presionado el menú o el cuadro de confirmación. */
    const val MEMBER_MENU_REMOVE = "mp-chat-member-menu-remove"
    const val MEMBER_REMOVE_DIALOG = "mp-chat-member-remove-dialog"
    const val MEMBER_REMOVE_CONFIRM = "mp-chat-member-remove-confirm"
    const val MEMBER_REMOVE_CANCEL = "mp-chat-member-remove-cancel"

    /** disolver:Entrada, casilla de confirmación. */
    const val GROUP_DISBAND_ENTRY = "mp-chat-group-disband"
    const val GROUP_DISBAND_DIALOG = "mp-chat-group-disband-dialog"
    const val GROUP_DISBAND_CONFIRM = "mp-chat-group-disband-confirm"
    const val GROUP_DISBAND_CANCEL = "mp-chat-group-disband-cancel"

    /** Mantenga presionado el mensaje para que aparezca la capa flotante de operación. */
    const val MENU = "mp-chat-menu"

    /** El nombre de la persona que se encuentra encima de la línea \"escribiendo\" en el grupo. */
    const val TYPING_NAME = "mp-chat-typing-name"

    /** \"Flotando después de dejar el fondo\"N nuevos mensajes\". */
    const val NEW_MESSAGES_PILL = "mp-chat-new-messages"

    /** Confirmación antes de la eliminación:Cuadro de diálogo, tecla de confirmación, tecla de cancelación. */
    const val DELETE_DIALOG = "mp-chat-delete-dialog"
    const val DELETE_CONFIRM = "mp-chat-delete-confirm"
    const val DELETE_CANCEL = "mp-chat-delete-cancel"

    fun message(id: String): String = "mp-chat-message-$id"

    fun bubble(id: String): String = "mp-chat-bubble-$id"

    fun image(id: String): String = "mp-chat-image-$id"

    fun imageBroken(id: String): String = "mp-chat-image-broken-$id"

    fun voice(id: String): String = "mp-chat-voice-$id"

    fun voiceUnavailable(id: String): String = "mp-chat-voice-unavailable-$id"

    fun timeDivider(at: Long): String = "mp-chat-time-$at"

    /** El nombre del orador encima de la burbuja del grupo. */
    fun senderName(messageId: String): String = "mp-chat-sender-$messageId"

    /** Una fila en la página de miembros del grupo. */
    fun member(characterId: String): String = "mp-chat-member-$characterId"

    fun expand(id: String): String = "mp-chat-expand-$id"

    /** Enviar estado junto a la burbuja:Las palabras superficiales que se envían comparten esta etiqueta con la marca de fallo de envío. */
    fun status(id: String): String = "mp-chat-status-$id"

    fun menuAction(action: ChatMenuAction): String = "mp-chat-menu-${action.name.lowercase()}"
}
