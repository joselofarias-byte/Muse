package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.groupTitleOf
import io.zer0.muse.miniphone.data.chat.normalizeGroupTitle
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.ui.shell.UNKNOWN_CHARACTER_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cuatro puntos de entrada para la gestión de miembros del grupo:Cambiar nombre, agregar personas, mudarse, disolverse.
 *
 * yuxtapuesto con la cadena que envió el mensaje,no mezclados:Aquí sólo cambiamos "¿A qué grupo pertenece y cómo se llama?",No involucrado en la generación y envío.
 * El nombre del grupo en la interfaz se cambiará después de que la base de datos se haya escrito correctamente. —— Cuando falla la escritura, la interfaz no debe cambiar primero y luego regresar.
 *
 * La determinación del "nombre predeterminado" se encuentra en este nivel.:El nombre del grupo no ha sido cambiado por el usuario.(títuloPersonalizado en la línea de sesión es 0)tiempo,
 * Agregar personas/Después de mover personas, presione "todos los miembros ahora" para volver a calcular;No se tocó ni una sola palabra del grupo modificado.
 *
 * @param characters Lista de personajes dada por el anfitrión.;Para deletrear el nombre del grupo predeterminado, debe usar el nombre
 */
internal class GroupAdminController(
    private val threadId: String,
    private val threadDao: MpThreadDao,
    private val memberDao: MpThreadMemberDao,
    private val store: MpGroupActions,
    private val characters: () -> List<MpCharacter>,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
) {

    /** Cambiar nombre de grupo:No escribas un nombre vacío,Truncamiento extralargo;Una vez que la escritura sea exitosa, el nombre del grupo en la interfaz se cambiará inmediatamente. */
    fun renameGroup(title: String) {
        val normalized = normalizeGroupTitle(title)
        if (normalized.isEmpty()) return
        scope.launch {
            if (!store.renameGroup(threadId, normalized)) return@launch
            state.update { it.copy(groupTitle = normalized) }
        }
    }

    /** Agregar personas al grupo:Las personas que ya están en el grupo serán omitidas automáticamente.;El nombre predeterminado se recalcula con nuevos miembros. */
    fun addMembers(characterIds: List<String>) {
        val ids = characterIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return
        scope.launch {
            val thread = groupThread() ?: return@launch
            val existing = memberDao.membersOf(threadId).map { member -> member.characterId }
            val fresh = ids.filterNot { characterId -> characterId in existing }
            if (fresh.isEmpty()) return@launch
            val autoTitle = autoTitleOf(thread, existing + fresh)
            if (!store.addMembers(threadId, fresh, autoTitle)) return@launch
            if (autoTitle != null) state.update { it.copy(groupTitle = autoTitle) }
        }
    }

    /** Eliminar una persona del grupo:Mantenga lo que dijo;La capa de escritura rechazará cuando solo quede una persona. */
    fun removeMember(characterId: String) {
        scope.launch {
            val thread = groupThread() ?: return@launch
            val remaining = memberDao.membersOf(threadId)
                .map { member -> member.characterId }
                .filterNot { id -> id == characterId }
            val autoTitle = autoTitleOf(thread, remaining)
            if (!store.removeMember(threadId, characterId, autoTitle)) return@launch
            if (autoTitle != null) state.update { it.copy(groupTitle = autoTitle) }
        }
    }

    /** disolver:Las sesiones, los miembros y los mensajes desaparecen juntos. La propia interfaz es responsable de salir de esta página. */
    fun disbandGroup() {
        scope.launch { store.disbandGroup(threadId) }
    }

    /** Los mensajes privados que lleguen a este punto no serán procesados.:Estas acciones sólo tienen significado para el grupo. */
    private suspend fun groupThread(): MpThreadEntity? = threadDao.getThread(threadId)?.takeIf { thread -> thread.isGroup }

    /**
     * El nombre del grupo no se ha cambiado manualmente.,Presione "Miembros actuales" para deletrear uno;Volver después de la corrección null(Di no te muevas).
     *
     * Nombre tomado de la lista de hosts:Cuando se elimina un carácter, se convierte en un nombre de marcador de posición.,No convierta un grupo en un grupo anónimo por esto.
     */
    private fun autoTitleOf(thread: MpThreadEntity, memberIds: List<String>): String? {
        if (thread.titleCustomized) return null
        val known = characters()
        val names = memberIds.map { id ->
            known.firstOrNull { character -> character.id == id }?.name?.takeIf(String::isNotBlank)
                ?: UNKNOWN_CHARACTER_NAME
        }
        return groupTitleOf(names).takeIf(String::isNotEmpty)
    }
}
