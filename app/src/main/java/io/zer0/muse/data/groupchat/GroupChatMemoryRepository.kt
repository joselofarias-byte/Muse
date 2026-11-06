package io.zer0.muse.data.groupchat

import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * v2.x: Almacén de memoria de chat grupal — Encapsulación [GroupChatMemoryDao],Exponer el método de suspensión.
 *
 * Responsabilidades:
 *  - Escribir en la memoria del chat grupal([saveSummary])——Llamado por GroupChatScheduler después de que el agente completa su respuesta,
 *    Escribe el resumen de esta conversación de chat grupal de forma independiente. fact store,**No**Escribe en el sistema de memoria principal del asistente.
 *  - Leer la memoria del chat grupal([getByAssistantAndChat])——Presionado por SystemPromptAssembler"Chat grupal actual"inyectar en
 *    system prompt,usar `<group_chat_memory>` Etiquetas y memoria principal `<long_term_memory>` Distinguir.
 *  - limpiar([cleanupOlderThan])——La limpieza periódica caduca/Viejos recuerdos.
 *
 * con sistema de memoria principal([io.zer0.memory] módulo)relación:
 *  - completamente independiente:Sin tablas compartidas, sin llamadas LLM compartidas, sin canales de compilación compartidos
 *  - Sólo en el nivel de solicitud a través de SystemPromptAssembler"Superposición",No se escriban
 *
 * Todas las operaciones de base de datos están envueltas en [Dispatchers.IO] en,Pase [resultOf] Tolerancia a fallos.
 */
class GroupChatMemoryRepository(
    private val dao: GroupChatMemoryDao,
) {

    private val TAG = "GroupChatMemRepo"

    /** B-18: Lo mismo (chat grupal, agent) Ventana de deduplicación resumida(30 minutos). */
    private companion object {
        const val DEDUP_WINDOW_MS = 30L * 60 * 1000
    }

    /**
     * Escribe un resumen de la memoria del chat grupal.
     *
     * Revisión de corrección (2.0 B-18): Deduplicación según ventana de tiempo antes de escribir — Cada agente en la rotación del chat grupal se activa cada vez que habla.
     * una vez saveSummary,Casi 10 resúmenes muy similares acumulados en un corto período de tiempo,Expansión lineal del lado de inyección;
     * mismo (chat grupal, agent) en [dedupWindowMs] Si ya existe un resumen, omítelo.(Devolver resumen existente).
     *
     * @param groupChatId chat grupal id
     * @param assistantId asistente id
     * @param summary texto resumido(LLM Generar o simplemente interceptar)
     * @param expiresAt Marca de tiempo de vencimiento opcional,null significa que nunca caduca
     * @param dedupWindowMs Ventana de deduplicación(milisegundos),Predeterminado 30 minutos
     * @return Entidad de memoria recién escrita(Devolver la entidad existente al eliminar visitas duplicadas)
     */
    suspend fun saveSummary(
        groupChatId: String,
        assistantId: String,
        summary: String,
        expiresAt: Long? = null,
        dedupWindowMs: Long = DEDUP_WINDOW_MS,
    ): GroupChatMemoryEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val latest = resultOf { dao.getByAssistantAndChat(assistantId, groupChatId, 1) }
            .onError { msg, t -> Logger.w(TAG, "Error en la consulta de deduplicación de memoria de chat grupal: $msg", t) }
            .getOrNull()?.firstOrNull()
        if (latest != null && now - latest.createdAt < dedupWindowMs) {
            // B-18: Ya hay un resumen en la ventana.,Saltar escrituras duplicadas(Los resúmenes generados al rotar el discurso de cada miembro son muy similares.)
            return@withContext latest
        }
        val memory = GroupChatMemoryEntity(
            id = UUID.randomUUID().toString(),
            groupChatId = groupChatId,
            assistantId = assistantId,
            summary = summary,
            createdAt = now,
            expiresAt = expiresAt,
        )
        resultOf { dao.insert(memory) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo escribir en la memoria del chat grupal: $msg", t) }
        memory
    }

    /**
     * Obtenga los recuerdos de chat grupal N más recientes del asistente designado(Orden descendente por creado en).
     *
     * **A-09 Reparado solo por compatibilidad**:No hay límite para el chat grupal,pondrá al asistente en**todos**Resumen de chats grupales mezclados nuevamente,
     * si"Diafonía de memoria entre grupos"Una de las causas fundamentales de la lectura.SystemPromptAssembler Se cambiarán todas las inyecciones inmediatas.
     * [getByAssistantAndChat](Limitar el chat grupal actual),Este método ya no se utiliza para inyección.
     */
    suspend fun getByAssistant(assistantId: String, limit: Int = 10): List<GroupChatMemoryEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getByAssistant(assistantId, limit) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer la memoria del chat grupal: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /**
     * Obtenga el asistente especificado en**Especificar chat grupal**Recuerdos recientes de chat grupal N(Orden descendente por creado en).
     *
     * **A-09 Reparación**:Lectura de inyección con aislamiento de chat grupal,Para que SystemPromptAssembler inyecte solo la memoria del chat grupal actual.
     */
    suspend fun getByAssistantAndChat(assistantId: String, groupChatId: String, limit: Int = 10): List<GroupChatMemoryEntity> =
        withContext(Dispatchers.IO) {
            resultOf { dao.getByAssistantAndChat(assistantId, groupChatId, limit) }
                .onError { msg, t -> Logger.w(TAG, "No se pudo leer la memoria del chat grupal: $msg", t) }
                .getOrNull() ?: emptyList()
        }

    /** Obtenga todos los recuerdos del chat grupal especificado(Para mostrar la página de detalles del chat grupal). */
    suspend fun getByGroupChat(groupChatId: String): List<GroupChatMemoryEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getByGroupChat(groupChatId) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer la memoria del chat grupal: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /**
     * limpiar [days] Recuerdos del chat grupal de hace días(Predeterminado 30 días).
     * Llamado para tareas de limpieza periódicas.,Evite la expansión ilimitada de la memoria.
     */
    suspend fun cleanupOlderThan(days: Int = 30) = withContext(Dispatchers.IO) {
        val before = System.currentTimeMillis() - days * 86_400_000L
        resultOf { dao.deleteOlderThan(before) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo borrar la memoria del chat grupal: $msg", t) }
    }

    /** Eliminar todos los recuerdos del chat grupal especificado(Llamada en cascada cuando se elimina el chat grupal). */
    suspend fun deleteByGroupChat(groupChatId: String) = withContext(Dispatchers.IO) {
        resultOf { dao.deleteByGroupChat(groupChatId) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo eliminar la memoria del chat grupal: $msg", t) }
    }

    /** v1.0.72: Consultar todos los recuerdos del chat grupal(pantalla del centro de memoria). */
    suspend fun getAll(): List<GroupChatMemoryEntity> = withContext(Dispatchers.IO) {
        resultOf { dao.getAll() }
            .onError { msg, t -> Logger.w(TAG, "No se pudieron leer todos los recuerdos del chat grupal: $msg", t) }
            .getOrNull() ?: emptyList()
    }

    /** v1.0.72: Eliminar una única memoria de chat grupal(Eliminar un solo elemento en el centro de memoria). */
    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        resultOf { dao.deleteById(id) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo eliminar la memoria del chat de un solo grupo: $msg", t) }
    }

    /** v1.0.72: Borrar todos los recuerdos del chat grupal(Limpia el centro de memoria con un clic). */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        resultOf { dao.deleteAll() }
            .onError { msg, t -> Logger.w(TAG, "No se pudieron borrar todos los recuerdos del chat grupal: $msg", t) }
    }
}
