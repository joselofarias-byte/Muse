package io.zer0.muse.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * v2.6.0: Grupo de acciones completo del botón de acceso directo a mensajes (Via Menú personalizado estilo navegador).
 *
 * Diseño: la fila de acceso directo debajo del mensaje ya no es un conjunto de interruptores fijos, sino un "grupo de acciones completo + lista de activación ordenada"——
 * Página de configuración (configuración de chat → Botón de acceso directo a mensajes → Personalizado) donde el usuario selecciona acciones del grupo y las clasifica,
 * La parte inferior del mensaje se muestra en este orden; el final de la línea está fijo para proporcionar "más" para ingresar al panel de pulsación larga completa.
 *
 * piscina llena = Seis interruptores originales (copiados/pensar/Traducción/Compartir/regenerar/eliminar)+ Mantenga presionado el panel "Más" para todas las acciones
 * (Cita/Comentario/confiar/rama/opción múltiple/información del mensaje/Colección/leer en voz alta/Editar/adelante).
 * Condiciones visualizables para cada acción (rol de mensaje/El estado del contenido) está determinado por el lado de renderizado,
 * Aquí sólo describimos"Qué es, cómo se ve, clasificación predeterminada".
 *
 * @param id Identificación estable, persistente a ChatPreferences.quickActionOrder
 * @param defaultInRow Habilitado de forma predeterminada (clasificación inicial cuando no está configurado)
 */
enum class QuickAction(
    val id: String,
    val labelRes: Int,
    val icon: ImageVector,
    val defaultInRow: Boolean = false,
    val defaultRank: Int = Int.MAX_VALUE,
) {
    COPY("copy", io.zer0.muse.R.string.action_copy, MuseIcons.copy, defaultInRow = true, defaultRank = 0),
    REASONING("reasoning", io.zer0.muse.R.string.msg_info_reasoning, MuseIcons.atom, defaultRank = 10),
    TRANSLATE("translate", io.zer0.muse.R.string.chat_translate_action, MuseIcons.languages, defaultInRow = true, defaultRank = 20),
    SHARE("share", io.zer0.muse.R.string.chat_share_action, MuseIcons.share, defaultInRow = true, defaultRank = 30),
    REGENERATE("regenerate", io.zer0.muse.R.string.chat_regenerate_cd, MuseIcons.refresh, defaultInRow = true, defaultRank = 40),
    DELETE("delete", io.zer0.muse.R.string.chat_delete_message, MuseIcons.trash, defaultRank = 50),
    QUOTE("quote", io.zer0.muse.R.string.message_action_quote, MuseIcons.reply),
    ANNOTATE("annotate", io.zer0.muse.R.string.message_action_annotate, MuseIcons.edit),
    DELEGATE("delegate", io.zer0.muse.R.string.chat_delegate_action, MuseIcons.users),
    FORK("fork", io.zer0.muse.R.string.chat_fork_action, MuseIcons.gitMerge),
    MULTI_SELECT("multi_select", io.zer0.muse.R.string.chat_select_messages, MuseIcons.square),
    INFO("info", io.zer0.muse.R.string.msg_info_title, MuseIcons.info),
    FAVORITE("favorite", io.zer0.muse.R.string.chat_favorite_add, MuseIcons.star),
    TTS("tts", io.zer0.muse.R.string.chat_tts_play, MuseIcons.play),
    EDIT("edit", io.zer0.muse.R.string.action_edit, MuseIcons.edit),
    FORWARD("forward", io.zer0.muse.R.string.chat_forward_action, MuseIcons.swapHorizontal),
    ;

    companion object {
        /** Disposición predeterminada: reserva cuando QuickActionOrder no está configurado (correspondiente al valor predeterminado del interruptor de seis acciones). */
        val DEFAULT_ORDER: List<QuickAction> =
            entries.filter { it.defaultInRow }.sortedBy { it.defaultRank }

        fun fromId(id: String): QuickAction? = entries.firstOrNull { it.id == id }
    }
}
