package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.ui.shell.UNTITLED_GROUP_NAME

/**
 * Todos los estados se mostrarán en la página de chat.
 *
 * [messages] en orden cronológico(viejo → nuevo)noticias,Invertir la lista al renderizarla;
 * [items] Insertar separación de tiempo entre mensajes.,La interfaz se dibuja línea por línea según ella.,No es necesario que usted mismo calcule los intervalos adyacentes.
 *
 * Los chats grupales y los mensajes privados comparten este estado.:[isGroup] Decide cómo llamar a la columna superior y si escribirás portavoz junto a la burbuja.,
 * [members] Solo hay contenido en el grupo.(Léelo tanto en la página de miembros como en "Quién lo dijo"). en mensaje privado [members] Siempre vacío.
 */
@Immutable
data class ChatUiState(
    val threadId: String = "",
    val characterId: String = "",
    val peerName: String = "",
    val peerAvatarUri: String? = null,
    /** ¿Esta conversación es un chat grupal? grupo [characterId] solo el primer miembro,El portavoz debe leer cada mensaje por sí mismo. */
    val isGroup: Boolean = false,
    /** El nombre del grupo establecido al crear el grupo.;Los mensajes privados son siempre cadenas vacías. */
    val groupTitle: String = "",
    /** miembros del grupo(Solo roles,No incluye usuarios locales),Haga clic para unirse al grupo. */
    val members: List<ChatMemberUi> = emptyList(),
    /** Todos los roles del anfitrión.:\"A quién más puedo agregar\" en la gestión de miembros del grupo viene de aquí(Luego excluye a los que ya están en el grupo.). */
    val characters: List<MpCharacter> = emptyList(),
    /** propio apodo,La interfaz espacio-temporal utiliza sellos de marcador de posición para revelar los detalles.;Se utiliza para dibujar las iniciales del avatar de la derecha. */
    val selfName: String = "",
    val selfAvatarUri: String? = null,
    val messages: List<ChatMessageUi> = emptyList(),
    val isGenerating: Boolean = false,
    /**
     * El nombre y avatar del personaje que estoy escribiendo en este momento.;null Indica que nadie está escribiendo.
     *
     * Cuando dos personas del grupo se turnan para contestar la llamada,"Introducir" debe sustituirse por el actual.:Simplemente escriba "La otra parte está escribiendo".
     * Hará que la gente piense que todo el grupo responde al mismo tiempo. En los mensajes privados, es igual a la propia otra persona.
     */
    val typingName: String? = null,
    val typingAvatarUri: String? = null,
    /**
     * ¿Qué está haciendo el personaje en este momento?;null Indica que no está haciendo nada.
     *
     * Sólo tiene valor durante la generación.,Borra el texto después de que llegue al suelo. —— En consecuencia, la interfaz muestra una línea de copia antropomórfica encima de la burbuja.
     */
    val activity: MpActivityKind? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    /**
     * Si la configuración actual de reconocimiento de voz puede convertir grabaciones en texto.
     *
     * Pregúntale al anfitrión una vez al ingresar a la página.;false Atenúa el micrófono en el campo de entrada.,Simplemente diga por qué después de realizar el pedido.
     * El valor predeterminado verdadero es "Aún no se ha solicitado el resultado".:Presione primero para usar el dibujo.,De lo contrario, cada usuario que ingrese a la página la verá primero.
     * Un micrófono gris vuelve a brillar,Como si acabara de romperse.
     */
    val voiceInputAvailable: Boolean = true,
) {
    /** Título de la columna superior:Grupo es el nombre del grupo.,El mensaje privado es el nombre de la otra parte. */
    val title: String get() = if (isGroup) groupTitle.ifBlank { UNTITLED_GROUP_NAME } else peerName

    /** Número de personas en el grupo.:Fila de miembro más usuario local. Los mensajes privados no se cuentan. */
    val memberCount: Int get() = if (isGroup) members.size + 1 else 0

    /** Lista de nombres de miembros:Pulsa el mensaje cuando esté escrito \"Quién dijo eso\" junto a la burbuja. [ChatMessageUi.characterId] Controlar. */
    val memberNames: Map<String, String> by lazy { members.associate { it.characterId to it.name } }

    /** mensaje + separación de tiempo,Secuencia positiva. Cada mensaje solo dará como resultado una compilación.,La interfaz se reorganiza repetidamente sin volver a calcularla. */
    val items: List<ChatItem> by lazy { buildChatItems(messages) }
}

/** Un miembro del grupo. El usuario local no está en él. —— Úsalo por tu cuenta [ChatUiState.selfName] con avatar. */
@Immutable
data class ChatMemberUi(
    val characterId: String,
    val name: String,
    val avatarUri: String? = null,
)

/** La identidad mostrada de un lado determinado en la lista de chat.:Nombre + avatar. Uno para usted y para la otra parte. */
@Immutable
data class ChatPersona(
    val name: String,
    val avatarUri: String? = null,
)

/** Cómo se ve un mensaje en la interfaz. */
@Immutable
data class ChatMessageUi(
    val id: String,
    /** portavoz:El mensaje del rol es el personaje que habla.,La cadena enviada por el propio usuario está vacía en el grupo. */
    val characterId: String = "",
    val sender: MpActorType,
    val type: MpMessageType,
    val content: String,
    /** La ruta del archivo local del mensaje con imagen.;texto/El mensaje del sistema es nulo. */
    val mediaUri: String? = null,
    /** Duración del mensaje de voz(milisegundos);0 Indica desconocido,Los mensajes que no son de voz siempre son 0. */
    val voiceDurationMs: Long = 0L,
    val createdAt: Long,
    /** Enviar estado:Solo los mensajes que envíes se detendrán en \"Enviando\" o \"Envío fallido\",El resto siempre se \"emite\". */
    val status: MpMessageStatus = MpMessageStatus.SENT,
) {
    /** Aviso del sistema:Fuentes pequeñas centradas,Sin avatar no hay distinción entre izquierda y derecha. */
    val isSystem: Boolean get() = type == MpMessageType.SYSTEM

    /** fotos:Cambiar la posición de la burbuja a una imagen.,El texto principal sólo se utiliza como copia de vista previa de la lista. */
    val isImage: Boolean get() = type == MpMessageType.IMAGE

    /** Voz:Cambia la posición de la burbuja a una barra de voz.,El texto principal sólo se utiliza como copia de vista previa de la lista. */
    val isVoice: Boolean get() = type == MpMessageType.VOICE

    /** Publicado por mi mismo:Manténgase a la derecha. */
    val isMine: Boolean get() = sender == MpActorType.USER && !isSystem

    /** Envío fallido:Coloque una marca en la que se pueda hacer clic junto a la burbuja. */
    val hasFailed: Boolean get() = status == MpMessageStatus.FAILED

    /** Todavía en camino:Proporciona solo una línea de caracteres superficiales.,No cubra el texto principal. */
    val isSending: Boolean get() = status == MpMessageStatus.SENDING

    /**
     * ¿Se puede reeditar?
     *
     * Sólo reconoce mensajes de texto enviados por ti mismo:El reenvío toma el enlace "solicitar una respuesta más".,Ni la imagen ni la voz pueden producir un texto que pueda ser resentido.,
     * No hay ninguna acción de "resentimiento" al hablar con un personaje.
     */
    val canResend: Boolean get() = isMine && type == MpMessageType.TEXT
}

/** una fila en una lista:Cualquiera de las noticias,O separación del tiempo. */
sealed interface ChatItem {
    /** `LazyColumn` clave estable. */
    val key: String

    @Immutable
    data class Time(
        override val key: String,
        val at: Long,
    ) : ChatItem

    @Immutable
    data class Message(
        override val key: String,
        val message: ChatMessageUi,
    ) : ChatItem
}

/** Cuando dos mensajes adyacentes exceden este intervalo, se inserta una línea de tiempo. */
internal const val CHAT_TIME_GAP_MS: Long = 5 * 60_000L

/** El número de mensajes en una página. */
internal const val CHAT_PAGE_SIZE: Int = 30

/**
 * Distribuya mensajes en orden en líneas de renderizado:La hora debe insertarse antes del primer elemento.,La diferencia entre cada elemento posterior y el anterior es total. [CHAT_TIME_GAP_MS] Simplemente inserte.
 *
 * Coloque el subíndice del mensaje en la clave de la línea de tiempo.,Dos mensajes en el mismo milisegundo no darán con la tecla.
 */
internal fun buildChatItems(messages: List<ChatMessageUi>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val items = ArrayList<ChatItem>(messages.size + messages.size / 4)
    var previousAt: Long? = null
    messages.forEachIndexed { index, message ->
        val previous = previousAt
        if (previous == null || message.createdAt - previous >= CHAT_TIME_GAP_MS) {
            items += ChatItem.Time(key = "time-${message.createdAt}-$index", at = message.createdAt)
        }
        items += ChatItem.Message(key = "message-${message.id}", message = message)
        previousAt = message.createdAt
    }
    return items
}

/** La línea de Curry → Una línea de interfaz. */
internal fun MpMessageEntity.toChatMessageUi(): ChatMessageUi = ChatMessageUi(
    id = id,
    characterId = characterId,
    sender = sender,
    type = type,
    content = content,
    mediaUri = mediaUri,
    voiceDurationMs = voiceDurationMs ?: 0L,
    createdAt = createdAt,
    status = status,
)
