package io.zer0.muse.miniphone.data.chat

import androidx.room.withTransaction
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import java.util.UUID

/** Longitud máxima del nombre del grupo:Si el riel superior es más largo, no podrá acomodarlo.,La parte sobrante se corta directamente. */
internal const val GROUP_TITLE_MAX_LENGTH: Int = 20

/** Al menos el número de miembros restantes después de que se complete la mudanza.:En un grupo sin nadie nadie puede hablar. */
internal const val MIN_GROUP_MEMBERS_AFTER_REMOVE: Int = 1

/**
 * Cómo escribir el nombre del grupo:Eliminar espacios en blanco iniciales y finales,exceder [GROUP_TITLE_MAX_LENGTH] Trunca cada carácter.
 *
 * Devuelve una cadena vacía cuando todo está vacío,La persona que llama lo trata como "no se permite guardar"—— Los grupos sin nombre no se pueden reconocer en la lista.
 */
internal fun normalizeGroupTitle(raw: String): String = raw.trim().take(GROUP_TITLE_MAX_LENGTH)

/** Un grupo debe tener al menos dos personajes.:Una persona no forma un grupo.,Entonces la carta debería haber sido pasada de contrabando. */
internal const val MIN_GROUP_MEMBERS: Int = 2

/** El separador entre nombres en un nombre de grupo. */
internal const val GROUP_TITLE_SEPARATOR: String = ","

/** El nombre del grupo puede contener como máximo algunos nombres de personas, uno por uno.;Si excede este número, obtendrá \"Ayuan, Xiaoman y otras 5 personas\". */
internal const val GROUP_TITLE_MAX_NAMES: Int = 3

/**
 * Nombre del grupo:Los nombres de los miembros se detallan en el orden de selección.
 *
 * Solo deletrea el nombre del personaje —— El usuario local no es miembro de la fila.,"( en la barra superiorN)" es el número que tiene en cuenta "número de caracteres + 1",
 * No mezcle los dos conjuntos de calibres y explíquelos juntos.
 * Cuando hay mucha gente, no hacer fila una por una.:exceder [GROUP_TITLE_MAX_NAMES] Personalmente, tomo los dos primeros y agrego "esperando a N personas".,
 * Aquí N es el número total de caracteres.(No incluye usuarios locales),Haga coincidir los seleccionados en la lista y la página de creación de grupos.
 * El nombre del grupo se determina en el momento de la creación.,No se volverá a calcular tras cambios posteriores de membresía. Los miembros sin nombre se omiten directamente.,
 * No dejes separadores adicionales;Devuelve una cadena vacía si no hay nombre,La interfaz ha degenerado en un "chat grupal".
 */
internal fun groupTitleOf(memberNames: List<String>): String {
    val names = memberNames.map(String::trim).filter(String::isNotEmpty)
    if (names.isEmpty()) return ""
    if (names.size <= GROUP_TITLE_MAX_NAMES) return names.joinToString(GROUP_TITLE_SEPARATOR)
    return names.take(GROUP_TITLE_TAIL_NAMES).joinToString(GROUP_TITLE_SEPARATOR) + groupTitleTailOf(names.size)
}

/** \"Esperando a N personas\" escrito con la boca cerrada——N es el número de todos los caracteres. */
internal fun groupTitleTailOf(personCount: Int): String = "espera $personCount gente"

/** El número de nombres enumerados uno por uno al cerrar. */
private const val GROUP_TITLE_TAIL_NAMES: Int = 2

/** ¿Se puede presionar el botón \"Finalizar\"?:Elige lo suficiente [MIN_GROUP_MEMBERS] Sólo cuenta un personaje. */
internal fun canCreateGroup(selectedCount: Int): Boolean = selectedCount >= MIN_GROUP_MEMBERS

/**
 * Construye un grupo.
 *
 * El motivo para extraer la interfaz es [MpConversationActions] Lo mismo:Las pruebas que shell ViewModel no deberían abrir una biblioteca Room para un delegado.
 */
interface MpGroupActions {
    /**
     * Crea una sesión de grupo y escribe a los miembros.,Volver a nueva sesión id;Si no hay suficientes opciones para dos personas, no se construirá.,Devuelve nulo.
     *
     * @param memberIds Rol de miembro id,El orden es el orden en el grupo.
     * @param title Nombre del grupo,Presionado por la persona que llama [groupTitleOf] Ponlo junto
     */
    suspend fun createGroup(memberIds: List<String>, title: String): String?

    /**
     * Cambie el nombre del grupo. No escribas un nombre vacío,Truncamiento extralargo(ver [normalizeGroupTitle]);Este grupo ya no volverá a calcular automáticamente el nombre del grupo después de escribir.
     *
     * @return Sólo si realmente lo escribes true
     */
    suspend fun renameGroup(threadId: String, title: String): Boolean

    /**
     * Añade personas al grupo. Las personas que ya están en el grupo serán omitidas automáticamente.
     *
     * @param autoTitle Es necesario volver a escribir el nombre del grupo cuando el usuario no lo haya cambiado.;Si se ha modificado, cárguelo. null,Sin mover una palabra
     * @return Es por eso que realmente lo agregué true
     */
    suspend fun addMembers(threadId: String, characterIds: List<String>, autoTitle: String?): Boolean

    /**
     * Eliminar a una persona del grupo. No está permitido moverse cuando solo queda una persona. —— Debe haber un personaje en el grupo que pueda hablar.
     *
     * @param autoTitle con [addMembers] sinónimo:Los grupos cuyos nombres no hayan sido cambiados se recalcularán según los integrantes.,Lo que ha cambiado permanece sin cambios
     * @return Sólo si realmente lo sacas true
     */
    suspend fun removeMember(threadId: String, characterId: String, autoTitle: String?): Boolean

    /**
     * disolver el grupo:Las filas de miembros, todos los mensajes de esta conversación y la conversación misma se eliminan juntos.,misma transacción.
     *
     * @return realmente tuve que borrarlo true
     */
    suspend fun disbandGroup(threadId: String): Boolean
}

/**
 * Escribir para crear un grupo:Una conversación grupal + varias líneas de miembros,misma transacción.
 *
 * Dos cosas deben aparecer juntas.:Solo sesión sin miembros,El nombre del grupo no coincide con la persona.;Solo miembros, sin sesiones,No puedo verlo en la lista.
 * El ID de carácter de la línea de sesión es el primer miembro. —— Esto es lo que usas cuando quieres encontrar a alguien con quien hablar en un chat grupal.
 *
 * @param now fuente de tiempo,El valor predeterminado es el reloj del sistema;Pruébelo y utilícelo para determinar el tiempo para unirse al grupo.
 * @param newId generador de identificación de sesión,Predeterminado UUID
 */
class MpGroupChatStore(
    private val db: MiniPhoneDb,
    private val threadDao: MpThreadDao,
    private val memberDao: MpThreadMemberDao,
    private val messageDao: MpMessageDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MpGroupActions {

    override suspend fun createGroup(memberIds: List<String>, title: String): String? {
        val members = memberIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (!canCreateGroup(members.size)) return null
        val threadId = newId()
        val createdAt = now()
        db.withTransaction {
            threadDao.upsert(
                MpThreadEntity(
                    id = threadId,
                    characterId = members.first(),
                    lastMessageAt = createdAt,
                    isGroup = true,
                    title = title,
                ),
            )
            // joinedAt Añade una línea por línea:Las personas que se unan al mismo grupo harán cola en el orden de selección.,Por lo tanto, el orden de las páginas de miembros es estable.
            memberDao.upsertAll(
                members.mapIndexed { index, characterId ->
                    MpThreadMemberEntity(threadId = threadId, characterId = characterId, joinedAt = createdAt + index)
                },
            )
        }
        return threadId
    }

    override suspend fun renameGroup(threadId: String, title: String): Boolean {
        val normalized = normalizeGroupTitle(title)
        if (normalized.isEmpty()) return false
        if (groupThreadOrNull(threadId) == null) return false
        threadDao.updateTitle(threadId, normalized, customized = true)
        return true
    }

    override suspend fun addMembers(threadId: String, characterIds: List<String>, autoTitle: String?): Boolean {
        if (groupThreadOrNull(threadId) == null) return false
        val existingRows = memberDao.membersOf(threadId)
        val existing = existingRows.map { member -> member.characterId }.toSet()
        val fresh = characterIds.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filterNot { characterId -> characterId in existing }
        if (fresh.isEmpty()) return false
        // Los recién llegados son los últimos:Unirse al grupo más tarde que los miembros existentes,De lo contrario, el orden de las páginas de miembros se alterará.
        val joinedAt = maxOf(now(), (existingRows.maxOfOrNull { member -> member.joinedAt } ?: 0L) + 1)
        db.withTransaction {
            memberDao.upsertAll(
                fresh.mapIndexed { index, characterId ->
                    MpThreadMemberEntity(threadId = threadId, characterId = characterId, joinedAt = joinedAt + index)
                },
            )
            if (autoTitle != null) threadDao.updateTitle(threadId, autoTitle, customized = false)
        }
        return true
    }

    override suspend fun removeMember(threadId: String, characterId: String, autoTitle: String?): Boolean {
        val thread = groupThreadOrNull(threadId) ?: return false
        val members = memberDao.membersOf(threadId)
        // Al menos una persona debe quedarse atrás una vez finalizada la mudanza.:Un grupo sin nadie,nadie puede hablar
        if (members.size <= MIN_GROUP_MEMBERS_AFTER_REMOVE) return false
        if (members.none { member -> member.characterId == characterId }) return false
        val remaining = members.filterNot { member -> member.characterId == characterId }
        db.withTransaction {
            memberDao.deleteMember(threadId, characterId)
            // El que fue removido resultó ser el que "habló con él":Reemplácelo con el primero restante.,Sólo las personas del grupo pueden responder.
            if (thread.characterId == characterId) {
                threadDao.updateCharacter(threadId, remaining.first().characterId)
            }
            if (autoTitle != null) threadDao.updateTitle(threadId, autoTitle, customized = false)
        }
        return true
    }

    override suspend fun disbandGroup(threadId: String): Boolean {
        if (groupThreadOrNull(threadId) == null) return false
        db.withTransaction {
            messageDao.deleteInThread(threadId)
            memberDao.deleteInThread(threadId)
            threadDao.delete(threadId)
        }
        return true
    }

    /** Solo se pueden modificar, agregar, expulsar y descartar conversaciones grupales;Si el mensaje privado llega hasta este punto, será ignorado. */
    private suspend fun groupThreadOrNull(threadId: String): MpThreadEntity? =
        threadDao.getThread(threadId)?.takeIf { thread -> thread.isGroup }
}
