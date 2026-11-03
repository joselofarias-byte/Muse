package io.zer0.muse.miniphone.ui.shell

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity

/**
 * Todos los estados que mostrará el shell.
 *
 * La interfaz solo lee estos datos.,No tocar la interfaz DAO/host —— ¿Quién verificará la base de datos y cómo fusionarla?,Es una cosa de ViewModel.
 */
@Immutable
data class ShellUiState(
    val threads: List<MiniPhoneThreadUi> = emptyList(),
    val contactSections: List<MiniPhoneContactSection> = emptyList(),
    val profile: MpProfile = MpProfile(nickname = ""),
    /** Interruptor \"Publicar automáticamente en Momentos\":Cuando está desactivado, el trabajador en segundo plano se salta toda la ronda. Activado de forma predeterminada. */
    val autoMomentEnabled: Boolean = true,
    /** Interruptor \"Carácter de mensaje privado activo\":Cuando está desactivado, el trabajador en segundo plano se salta toda la ronda. El valor predeterminado está desactivado. */
    val chatProactiveEnabled: Boolean = false,
    /** El nombre de la versión de la aplicación host.,por [io.zer0.muse.miniphone.api.MiniPhoneHost] proporcionar;Una cadena vacía indica que el anfitrión no ha informado nada. */
    val appVersionName: String = "",
)

/**
 * Una fila en la lista de sesiones.
 *
 * [lastMessageAt] Mantener la marca de tiempo original,La interfaz calcula el texto que se muestra en función de la hora actual.,
 * Este estado en sí no tiene nada que ver con "qué hora es ahora".,No se reconstruirá toda la tabla porque dura minutos.
 * [isGroup] Sólo se utiliza para distinguir entre mensajes grupales y mensajes privados.:El nombre se ha completado como nombre de grupo.,También se ha traído la línea de vista previa. [GROUP_PREVIEW_MARK].
 */
@Immutable
data class MiniPhoneThreadUi(
    val id: String,
    val name: String,
    val preview: String,
    val lastMessageAt: Long,
    val unreadCount: Int,
    val pinned: Boolean,
    val isGroup: Boolean = false,
)

/** Un conjunto de libretas de direcciones.:Letra inicial + rol del grupo. */
@Immutable
data class MiniPhoneContactSection(
    val key: String,
    val contacts: List<MpCharacter>,
)

/**
 * Las iniciales no latinas entran en este grupo.
 *
 * Los nombres chinos no se pueden asignar de manera confiable a letras latinas antes de obtener pinyin,caída unificada `#`,Viene después del grupo de letras.,
 * Más estable que las adivinanzas aleatorias mediante trazos o códigos.
 */
internal const val OTHER_SECTION_KEY: String = "#"

/** Clave de agrupación con nombre:letras mayúsculas latinas,El resto es igual `#`. */
internal fun sectionKeyOf(name: String): String {
    val first = name.trim().firstOrNull() ?: return OTHER_SECTION_KEY
    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper.toString() else OTHER_SECTION_KEY
}

/** Roles de grupo por primera letra:Prensa de grupo de letras A→Z fila,`#` Refinamiento. */
internal fun contactSectionsOf(characters: List<MpCharacter>): List<MiniPhoneContactSection> {
    val grouped = characters
        .filter { it.name.isNotBlank() }
        .groupBy { sectionKeyOf(it.name) }
    val byKey = compareBy<Map.Entry<String, List<MpCharacter>>> { it.key == OTHER_SECTION_KEY }.thenBy { it.key }
    return grouped.entries
        .sortedWith(byKey)
        .map { entry -> MiniPhoneContactSection(key = entry.key, contacts = entry.value.sortedBy { it.name }) }
}

/**
 * Fusionar datos de sesión, rol y perfil.
 *
 * El orden de las sesiones queda enteramente a cargo de DAO(Pon el de arriba primero y el resto en orden descendente según el último mensaje.),Aquí solo hacemos "roles" id → finalización de "nombre":
 * Las sesiones cuyas funciones se han eliminado todavía aparecen en la lista,El nombre degenera en un recordatorio.,en lugar de desaparecer de la lista.
 * Las conversaciones grupales no utilizan nombres de personajes.:es[title]Mi propio nombre de grupo,Agregar uno antes de la línea de vista previa [GROUP_PREVIEW_MARK],
 * También se pueden separar de los mensajes privados en la misma lista de un vistazo.
 */
internal fun assembleShellState(threads: List<MpThreadEntity>, characters: List<MpCharacter>, profile: MpProfile): ShellUiState {
    val namesById = characters.associateBy { it.id }
    return ShellUiState(
        threads = threads.map { thread ->
            MiniPhoneThreadUi(
                id = thread.id,
                name = if (thread.isGroup) {
                    thread.title.ifBlank { UNTITLED_GROUP_NAME }
                } else {
                    namesById[thread.characterId]?.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_CHARACTER_NAME
                },
                preview = if (thread.isGroup) groupPreviewOf(thread.lastMessagePreview) else thread.lastMessagePreview.orEmpty(),
                lastMessageAt = thread.lastMessageAt,
                unreadCount = thread.unreadCount.coerceAtLeast(0),
                pinned = thread.pinned,
                isGroup = thread.isGroup,
            )
        },
        contactSections = contactSectionsOf(characters),
        profile = profile,
    )
}

/** La marca antes de la fila de vista previa en el grupo. */
internal const val GROUP_PREVIEW_MARK: String = "[grupo]"

/** La conclusión cuando el nombre del grupo se deja vacío:Prefiero llamarlo \"chat grupal\",Tampoco dejar una fila de sesiones sin nombre. */
internal const val UNTITLED_GROUP_NAME: String = "chat grupal"

/** Fila de vista previa de la sesión grupal:sacar a relucir [GROUP_PREVIEW_MARK],Seguido del último mensaje. */
internal fun groupPreviewOf(lastMessagePreview: String?): String {
    val text = lastMessagePreview.orEmpty().trim()
    return if (text.isEmpty()) GROUP_PREVIEW_MARK else "$GROUP_PREVIEW_MARK $text"
}

/** Cuando el rol ha sido eliminado,El nombre que se muestra en la sesión. */
internal const val UNKNOWN_CHARACTER_NAME: String = "Rol desconectado"
