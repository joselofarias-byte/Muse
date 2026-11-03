package io.zer0.muse.miniphone.data.chat

import androidx.room.withTransaction
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao

/**
 * Escriba operaciones en la lista de sesiones.
 *
 * El propósito de extraer la interfaz es probar el ViewModel de shell sin tener que abrir una biblioteca Room para recibir una comisión.;
 * comportamiento real en [MpConversationStore] Ejecute una validación SQL real en la prueba de la biblioteca.
 */
interface MpConversationActions {
    /** Anclar/desanclar. */
    suspend fun setPinned(threadId: String, pinned: Boolean)

    /** No leído claro. */
    suspend fun markRead(threadId: String)

    /** Eliminar una conversación y todos sus mensajes. */
    suspend fun deleteThread(threadId: String)

    /** Borrar todas las conversaciones y mensajes. */
    suspend fun clearAll()
}

/**
 * Escribir operaciones en la lista de sesiones:Fijar arriba, borrar no leídos, eliminar conversación, borrar todo a la vez.
 *
 * Todas estas acciones tocan dos mesas al mismo tiempo.(Línea de conversación y línea de mensaje.),Ponlo aquí y hazlo en la transacción de la base de datos.:
 * El estado intermedio que se ve en la interfaz no debe ser "la sesión sigue ahí pero el contenido está vacío" o viceversa.
 * Las conversaciones grupales incluyen líneas de miembros adicionales:Seguir cuando se elimine el grupo,De lo contrario, quedarán un montón de registros de miembros que apuntan a sesiones inexistentes.
 * Consulta y devolución del estado de la interfaz. [io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel],
 * Sólo escribe aquí,No mantiene ningún estado de UI.
 */
class MpConversationStore(
    private val db: MiniPhoneDb,
    private val threadDao: MpThreadDao,
    private val messageDao: MpMessageDao,
    private val memberDao: MpThreadMemberDao,
) : MpConversationActions {

    /** Anclar/desanclar. La clasificación de la lista está garantizada por ORDER BY de DAO,Aquí solo se cambian las etiquetas. */
    override suspend fun setPinned(threadId: String, pinned: Boolean) {
        threadDao.setPinned(threadId, pinned)
    }

    /** No leído claro. Esta frase no cambia nada cuando ya es 0. */
    override suspend fun markRead(threadId: String) {
        threadDao.clearUnread(threadId)
    }

    /** Eliminar una conversación,Todos los mensajes a su nombre se eliminarán junto con los miembros del grupo.;Otras conversaciones y mensajes no se ven afectados. */
    override suspend fun deleteThread(threadId: String) {
        db.withTransaction {
            messageDao.deleteInThread(threadId)
            memberDao.deleteInThread(threadId)
            threadDao.delete(threadId)
        }
    }

    /**
     * Borrar historial de chat:Las tres tablas de conversaciones, mensajes y miembros del grupo se borran juntas. Momentos, información, diarios y álbumes de fotos no están aquí,No será tocado.
     */
    override suspend fun clearAll() {
        db.withTransaction {
            messageDao.deleteAll()
            memberDao.deleteAll()
            threadDao.deleteAll()
        }
    }
}
