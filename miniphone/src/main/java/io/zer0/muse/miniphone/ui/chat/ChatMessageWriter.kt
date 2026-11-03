package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * La única entrada al almacenamiento de mensajes.:Escriba la base de datos, actualice la vista previa de la sesión y devuelva nuevos mensajes a la interfaz.
 * Esta es la forma de escribir texto de usuario, imágenes y respuestas de personajes.,sucesivamente por [writeMutex] Esperar.
 * El cursor para pasar páginas también utiliza esta puerta.:El cursor de la ventana de orden inverso es "número de artículos cargados",
 * Insertar un nuevo mensaje entre el cursor de lectura y la consulta,Esa página tendrá la misma página que la página anterior y perderá una página anterior.
 *
 * @param now fuente de tiempo,El valor predeterminado es el reloj del sistema;prueba clavada con eso `createdAt`
 * @param newId generador de clave primaria,Predeterminado UUID;Utilice esto para realizar pruebas y hacer que los identificadores de mensajes sean predecibles.
 * @param onStored Devolución de llamada después de que llegue el mensaje perteneciente a esta sesión,El parámetro es la línea que se agregará a la interfaz.
 * @param onStatusChanged Devolución de llamada después de enviar reescritura de estado:La biblioteca y la línea en la interfaz deben cambiar al mismo tiempo.
 * @param onDeleted Devolución de llamada después de que la máquina elimine un elemento
 */
internal class ChatMessageWriter(
    private val threadId: String,
    private val messageDao: MpMessageDao,
    private val threadDao: MpThreadDao,
    private val now: () -> Long,
    private val newId: () -> String,
    private val onStored: (ChatMessageUi) -> Unit,
    private val onStatusChanged: (messageId: String, status: MpMessageStatus) -> Unit = { _, _ -> },
    private val onDeleted: (messageId: String) -> Unit = {},
) {

    /** Una puerta común para dejar caer y pasar páginas. */
    private val writeMutex = Mutex()

    /**
     * dejar un mensaje:La biblioteca, la vista previa de la sesión y la lista de interfaces se actualizan juntas.
     *
     * Sólo aquellos pertenecientes a esta sesión volverán a la interfaz —— El método de semilla de imagen permite escribir otras sesiones.,Hay otras columnas de ViewModel.
     *
     * @param message El mensaje completo se eliminará.;Si el usuario lo envía, primero se iniciará sesión como "Enviando".,Cámbialo después de obtener el resultado.
     * @return nuevas noticias id,Utilizado por personas que llaman para rastrear el estado
     */
    suspend fun append(message: ChatOutgoingMessage): String {
        val entity = message.toEntity(id = newId(), createdAt = now())
        writeMutex.withLock {
            messageDao.insert(entity)
            threadDao.updateLastMessage(message.threadId, message.content, entity.createdAt)
            if (message.threadId == this@ChatMessageWriter.threadId) {
                onStored(entity.toChatMessageUi())
            }
        }
        return entity.id
    }

    /** Cambiar el estado de envío de un mensaje:Curry y la línea en la interfaz se cambian juntos.,No hay un lado que sea nuevo y otro que sea viejo. */
    suspend fun updateStatus(messageId: String, status: MpMessageStatus) {
        writeMutex.withLock {
            messageDao.updateStatus(messageId, status)
            onStatusChanged(messageId, status)
        }
    }

    /** Eliminar uno localmente:Mueve solo la tabla de mensajes.,La vista previa y la hora de la fila de la sesión permanecen como estaban antes de la eliminación. */
    suspend fun delete(messageId: String) {
        writeMutex.withLock {
            messageDao.deleteMessage(messageId)
            onDeleted(messageId)
        }
    }

    /** Leer un mensaje en la misma puerta.:La interfaz de cursor, consulta y reescritura debe ser un paso continuo. */
    suspend fun <R> withWriteLock(block: suspend () -> R): R = writeMutex.withLock { block() }
}

/**
 * Un mensaje esperando ser dejado en la biblioteca.
 *
 * Combine "todos los campos que necesita saber para escribir un mensaje" en un solo valor:Correspondencia uno a uno entre campos y tablas.,
 * Cada persona que llama simplemente llena su parte,No es necesario recordar el orden de ocho llamadas a parámetros.
 */
@Immutable
internal data class ChatOutgoingMessage(
    val threadId: String,
    val characterId: String,
    val sender: MpActorType,
    val type: MpMessageType,
    val content: String,
    val mediaUri: String? = null,
    val voiceDurationMs: Long? = null,
    val status: MpMessageStatus = MpMessageStatus.SENT,
) {
    /** Agregar clave principal y hora,se convierte en la fila completa que se escribirá en la tabla. */
    fun toEntity(id: String, createdAt: Long): MpMessageEntity = MpMessageEntity(
        id = id,
        threadId = threadId,
        characterId = characterId,
        sender = sender,
        type = type,
        content = content,
        mediaUri = mediaUri,
        voiceDurationMs = voiceDurationMs,
        createdAt = createdAt,
        status = status,
    )
}
