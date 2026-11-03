package io.zer0.muse.data.groupchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Proyección del recuento de mensajes de chat grupal(Grupo por ID de chat,Se utiliza para los N chats grupales más activos).
 */
data class GroupChatMessageCount(
    val chatId: String,
    val cnt: Int,
)

/**
 * Objeto de acceso a datos de mensajes de chat grupal.
 *
 * Responsable de CRUD + Observación de flujo de entidades de mensajes.,Y estadísticas de grupo por chatId(Top N Chat grupal activo).
 */
@Dao
interface GroupChatMessageDao {

    /**
     * Observar los mensajes del chat grupal especificado.(Ordenar por marca de tiempo ascendente,Para interfaz de chat).
     * M4: Utilice temporalmente el límite superior LIMIT 200,PagingSource se puede cambiar más tarde.
     * M8: Usar id como clasificación secundaria key,Evite colisiones con milisegundos.
     *
     * v1.53-GC: Existe riesgo de OOM al observar completamente chats grupales largos(Almacenamiento directo de imágenes base64 DB),Utilice un nuevo código en su lugar
     * [observeRecentMessages] + [getOlderMessages] Cargando en páginas. Este método conserva la compatibilidad con versiones anteriores.,
     * La capa del Repositorio ha sido anotada con @Deprecated.
     */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp ASC, id ASC LIMIT 200")
    fun observeMessages(chatId: String): Flow<List<GroupChatMessageEntity>>

    /**
     * v1.53-GC: Observe los últimos mensajes límite en el chat grupal especificado(Ordenar por marca de tiempo ascendente),Flow se utiliza para actualizaciones en tiempo real de la pantalla de inicio de paginación.
     *
     * Subconsulta primero presione DESC para obtener la barra de límite más cercana,La capa exterior está ordenada por ASC.,Se garantiza que devolverá los "elementos límite más recientes" en orden cronológico.
     * Se utiliza para reemplazar la cantidad total. [observeMessages]:Sólo la página más reciente es visible en la primera pantalla de la interfaz de chat.,Flow se reenvía automáticamente cuando llegan nuevos mensajes,
     * ViewModel Agregue incrementalmente a la lista cargada en consecuencia.M8: Utilice id para clasificación secundaria key,Evite colisiones con milisegundos.
     */
    @Query(
        "SELECT * FROM (SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT :limit) ORDER BY timestamp ASC, id ASC",
    )
    fun observeRecentMessages(chatId: String, limit: Int): Flow<List<GroupChatMessageEntity>>

    /**
     * v1.53-GC: carga de ventana — Tomar antes del punto de anclaje(timestamp, id)mensajes de primer límite(orden descendente).
     *
     * Solía deslizarse hacia arriba para cargar más.:El primer mensaje de la lista actual (timestamp, id) para puntos de anclaje dobles,Tomemos como ejemplo la historia anterior.
     * doble punto de anclaje(timestamp + id)Evite que se pierdan o dupliquen mensajes del mismo milisegundo. El orden de devolución es descendente.(último primero),
     * llamador(Repository)Necesitas hacerlo tu mismo reversed() Convierta al orden ascendente y luego empalme hacia adelante.
     */
    @Query(
        "SELECT * FROM group_chat_messages WHERE chatId = :chatId AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun getOlderMessages(chatId: String, beforeTimestamp: Long, beforeId: String, limit: Int): List<GroupChatMessageEntity>

    /** Insertar o actualizar mensaje(Reemplazar en caso de conflicto). */
    @Upsert
    suspend fun upsert(message: GroupChatMessageEntity)

    /** Insertar mensajes en lotes. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<GroupChatMessageEntity>)

    /** Eliminar un solo mensaje por id. */
    @Query("DELETE FROM group_chat_messages WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── Reparación de auditoría (5.3): Consulta para volver a calcular campos redundantes después de la eliminación ──

    /** Recibe un solo mensaje por id. */
    @Query("SELECT * FROM group_chat_messages WHERE id = :id")
    suspend fun getById(id: String): GroupChatMessageEntity?

    /** Recibe el último mensaje de un chat grupal. */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatest(chatId: String): GroupChatMessageEntity?

    /** El número total de mensajes en un chat grupal. */
    @Query("SELECT COUNT(*) FROM group_chat_messages WHERE chatId = :chatId")
    suspend fun countByChat(chatId: String): Int

    /** Eliminar todos los mensajes en el chat grupal especificado(Llamada en cascada cuando se elimina el chat grupal). */
    @Query("DELETE FROM group_chat_messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: String)

    /** Obtenga los últimos N mensajes del chat grupal especificado(Tome la marca de tiempo en orden descendente y revierta,Para que la herramienta Agente lea el contexto).M8: Utilice id como clave de clasificación secundaria. */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: String, limit: Int): List<GroupChatMessageEntity>

    /** v1.0.72: Buscar mensajes en el chat grupal(Palabra clave COMO texto coincidente,en orden cronológico inverso). */
    @Query(
        "SELECT * FROM group_chat_messages WHERE chatId = :chatId AND body LIKE '%' || :query || '%' ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    suspend fun searchMessages(chatId: String, query: String, limit: Int = 100): List<GroupChatMessageEntity>

    /** Cuente la cantidad de mensajes agrupados por chatId,Obtenga los N mejores chats grupales activos(orden descendente). */
    @Query("SELECT chatId, COUNT(*) as cnt FROM group_chat_messages GROUP BY chatId ORDER BY cnt DESC LIMIT :limit")
    fun observeTopActiveChats(limit: Int): Flow<List<GroupChatMessageCount>>

    @Query("SELECT * FROM group_chat_messages")
    suspend fun getAll(): List<GroupChatMessageEntity>

    @Query("DELETE FROM group_chat_messages")
    suspend fun deleteAll()
}
