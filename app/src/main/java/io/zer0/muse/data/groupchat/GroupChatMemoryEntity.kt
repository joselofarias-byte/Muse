package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * v2.x: Aislamiento de memoria de chat grupal — Room entidad.
 *
 * fondo:Los mensajes de chat grupal contienen discursos de múltiples agentes,Escribir directamente en la memoria principal del asistente contaminará el contexto principal de la conversación.
 * El resumen de mensajes de chat grupal se escribe de forma independiente fact store,No ingresa a la memoria principal.
 * Esta mesa es"Exclusivo para chat grupal fact store",con [io.zer0.memory] El sistema de memoria principal del módulo está completamente aislado.
 *
 * Ruta de inyección:[SystemPromptAssembler] Cuando el asistente principal genera un mensaje del sistema,Asociar el asistente actual con
 * Para resumen de memoria de chat grupal `<group_chat_memory>` inyección de etiquetas,con `<long_term_memory>` Distinguir.
 *
 * @param id clave primaria(UUID)
 * @param groupChatId chat grupal id(asociación [GroupChatEntity].id)
 * @param assistantId asistente id(asociación [io.zer0.muse.data.assistant.AssistantEntity].id)
 * @param summary Resumen de mensajes de chat grupal(LLM Generar o simplemente interceptar)
 * @param createdAt Crear marca de tiempo
 * @param expiresAt Marca de tiempo de vencimiento opcional,null significa que nunca caduca
 */
@Serializable
@Entity(
    tableName = "group_chat_memories",
    indices = [
        Index(value = ["assistantId"]),
        Index(value = ["groupChatId"]),
        Index(value = ["createdAt"]),
    ],
)
data class GroupChatMemoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "groupChatId")
    val groupChatId: String,
    @ColumnInfo(name = "assistantId")
    val assistantId: String,
    @ColumnInfo(name = "summary")
    val summary: String,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "expiresAt")
    val expiresAt: Long? = null,
)

/**
 * Memoria de chat grupal DAO.
 *
 * semántica de consulta:
 *  - [getByAssistant]:Consulte los últimos recuerdos de chat grupal N por ID de asistente(Sólo por compatibilidad;A-09 Después de la reparación, ya no se utiliza para inyección rápida.)
 *  - [getByAssistantAndChat]:Presione ID de asistente + ID de chat grupal para verificar N recuerdos recientes de chat grupal(Para inyección por SystemPromptAssembler)
 *  - [getByGroupChat]:Consulta todos los recuerdos por ID de chat grupal(Para mostrar la página de detalles del chat grupal)
 *  - [insert]:Escribe un nuevo recuerdo
 *  - [deleteOlderThan]:La limpieza expiró/viejos recuerdos
 */
@Dao
interface GroupChatMemoryDao {
    /**
     * Consulte los últimos recuerdos de chat grupal N por ID de asistente(Orden descendente por creado en).
     *
     * **A-09 Reparado solo por compatibilidad**(Como se muestra en el centro de memoria.),no se volverá a utilizar [SystemPromptAssembler] inyectar:
     * Esta consulta no se limita a chats grupales.,pondrá al asistente en**todos**Resumen del chat grupal mezclado,
     * si"entre grupos/Cadena de memoria entre sesiones"una de las causas fundamentales.prompt Inyectate por favor ve [getByAssistantAndChat].
     */
    @Query("SELECT * FROM group_chat_memories WHERE assistantId = :assistantId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getByAssistant(assistantId: String, limit: Int = 10): List<GroupChatMemoryEntity>

    /**
     * Presione ID de asistente + ID de chat grupal para verificar N recuerdos recientes de chat grupal(Orden descendente por creado en).
     *
     * **A-09 Reparación**:Lectura de inyección con aislamiento de chat grupal. Devuelve sólo el ayudante en**Chat grupal actual**resúmenes pasados dentro,
     * Evite que el contenido del chat grupal A se filtre al chat grupal B o al contexto del chat individual.
     *
     * @param assistantId asistente actual id
     * @param groupChatId Chat grupal actual id(El recuerdo debe pertenecer al chat grupal.,No se permite cruzar grupos.)
     * @param limit Devuelve el límite superior del número de artículos.
     * @return Resumen de la memoria del asistente actual en el chat grupal actual(Orden descendente por creado en,la mayoría [limit] Artículo)
     */
    @Query(
        "SELECT * FROM group_chat_memories " +
            "WHERE assistantId = :assistantId AND groupChatId = :groupChatId " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun getByAssistantAndChat(assistantId: String, groupChatId: String, limit: Int = 10): List<GroupChatMemoryEntity>

    /** Consulta todos los recuerdos por ID de chat grupal(Para mostrar la página de detalles del chat grupal). */
    @Query("SELECT * FROM group_chat_memories WHERE groupChatId = :groupChatId ORDER BY createdAt DESC")
    suspend fun getByGroupChat(groupChatId: String): List<GroupChatMemoryEntity>

    /** Escribe un nuevo recuerdo(Reemplazar con identificación). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: GroupChatMemoryEntity)

    /** Limpieza creada en antes [before] memoria(Llamado para tareas de limpieza periódicas.). */
    @Query("DELETE FROM group_chat_memories WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)

    /** Eliminar todos los recuerdos del chat grupal especificado(Llamada en cascada cuando se elimina el chat grupal). */
    @Query("DELETE FROM group_chat_memories WHERE groupChatId = :groupChatId")
    suspend fun deleteByGroupChat(groupChatId: String)

    /** v1.0.72: Eliminar una única memoria de chat grupal(Para eliminación única en el centro de memoria.). */
    @Query("DELETE FROM group_chat_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    /** v1.0.72: Consultar todos los recuerdos del chat grupal(pantalla del centro de memoria,Orden descendente por tiempo). */
    @Query("SELECT * FROM group_chat_memories ORDER BY createdAt DESC")
    suspend fun getAll(): List<GroupChatMemoryEntity>

    /** v1.0.72: Borrar todos los recuerdos del chat grupal(Limpia el centro de memoria con un clic). */
    @Query("DELETE FROM group_chat_memories")
    suspend fun deleteAll()

    /** Eliminar la memoria del asistente especificado en el chat grupal especificado(Se llama cuando el robot asistente sale del chat grupal.). */
    @Query("DELETE FROM group_chat_memories WHERE groupChatId = :groupChatId AND assistantId = :assistantId")
    suspend fun deleteByGroupChatAndAssistant(groupChatId: String, assistantId: String)

    /**
     * Cuando se elimina un asistente, se borrará su memoria en todos los chats grupales.
     *
     * La memoria del chat grupal es independiente. fact store,Enlace de limpieza sin quitar el módulo de memoria.,Debe eliminarse por separado.
     *
     * @return número real de filas eliminadas
     */
    @Query("DELETE FROM group_chat_memories WHERE assistantId = :assistantId")
    suspend fun deleteByAssistant(assistantId: String): Int
}
