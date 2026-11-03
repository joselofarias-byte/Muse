package io.zer0.muse.data.stats

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * v1.107 Administrador de caché de agregación de estadísticas.
 *
 * voluntad [MessageDao] / [SessionDao] El resultado de la agregación GROUP BY de toda la tabla con una gran sobrecarga se serializa como JSON,
 * escribir [stats_cache][StatsCacheEntity] mesa,Evite activar un escaneo completo de la tabla cada vez que ingrese a la página de estadísticas.
 *
 * El tiempo de actualización lo determina la persona que llama.(envío de mensajes/Retrasar la actualización después de la eliminación,WorkManager Totalmente renovado todos los días,
 * Cuando se abra la página de estadísticas, presione actualizado en para determinar si ha caducado.). Esta clase sólo es responsable de la "agregación -> serialización -> "entrar en el almacén"
 * y "Leer/observar/Inválido".
 *
 * @param statsCacheDao Caché de estadísticas DAO
 * @param messageDao noticias DAO(Proporcionar consultas agregadas)
 * @param sessionDao sesión DAO(Proporciona el número total de sesiones.)
 */
class StatsCacheManager(
    private val statsCacheDao: StatsCacheDao,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
) {

    companion object {
        private const val TAG = "StatsCacheManager"

        /** clave de caché: Número de mensajes para cada rol.(List<RoleCountItem>). */
        const val KEY_COUNT_BY_ROLE = "countByRole"

        /** clave de caché: Número de mensajes del ASISTENTE agrupados por modelId(List<ModelCountItem>). */
        const val KEY_COUNT_BY_MODEL = "countByModel"

        /** clave de caché: Número de mensajes agrupados por ID de asistente(List<AssistantCountItem>). */
        const val KEY_COUNT_BY_ASSISTANT = "countByAssistant"

        /** clave de caché: Número de mensajes agrupados por hora(List<HourCountItem>,24 histograma raíz). */
        const val KEY_COUNT_BY_HOUR = "countByHour"

        /** clave de caché: Top N conversaciones con más mensajes(List<SessionMessageCountItem>). */
        const val KEY_TOP_SESSIONS = "topSessions"

        /** clave de caché: Número total de mensajes(TotalCount). */
        const val KEY_TOTAL_MESSAGES = "totalMessages"

        /** clave de caché: Número total de sesiones(TotalCount). */
        const val KEY_TOTAL_SESSIONS = "totalSessions"

        /** Top El número de entradas retenidas en la lista de sesiones. */
        private const val TOP_SESSIONS_LIMIT = 10

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /** Recuento de mensajes por rol que se pueden serializar. */
    @Serializable
    private data class RoleCountItem(val role: String, val cnt: Int)

    /** Recuento serializable agrupado por modelId. */
    @Serializable
    private data class ModelCountItem(val modelId: String, val cnt: Int)

    /** Recuento serializable agrupado por ID de asistente. */
    @Serializable
    private data class AssistantCountItem(val assistantId: String, val cnt: Int)

    /** Recuento agrupado por hora serializable. */
    @Serializable
    private data class HourCountItem(val hour: String, val cnt: Int)

    /** Proyección serializable del número de mensajes de sesión individuales. */
    @Serializable
    private data class SessionMessageCountItem(val sessionId: String, val cnt: Int)

    /** Recuento total serializable. */
    @Serializable
    private data class TotalCount(val total: Int)

    /**
     * Vacíe completamente todos los cachés agregados.
     *
     * Llame a cada consulta agregada en secuencia,Serializa el resultado JSON y escríbelo en la tabla stats_cache(Cubierto con la estrategia REPLACE).
     * en [AppDispatchers.io] ejecutar en. Si falla un solo cálculo, los elementos siguientes no se interrumpirán.,Sólo se registran las advertencias.
     */
    suspend fun refreshAll() = withContext(AppDispatchers.io) {
        Logger.i(TAG, "refreshAll: Comience a actualizar el caché de estadísticas")
        val now = System.currentTimeMillis()

        safePut(KEY_COUNT_BY_ROLE, now) {
            val rows = messageDao.countByRole().map { RoleCountItem(it.role, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_COUNT_BY_MODEL, now) {
            val rows = messageDao.countByModel().map { ModelCountItem(it.modelId, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_COUNT_BY_ASSISTANT, now) {
            val rows = messageDao.countByAssistant().map { AssistantCountItem(it.assistantId, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_COUNT_BY_HOUR, now) {
            val rows = messageDao.countByHour().map { HourCountItem(it.hour, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_TOP_SESSIONS, now) {
            val rows = messageDao.topSessionsByMessageCount(TOP_SESSIONS_LIMIT)
                .map { SessionMessageCountItem(it.sessionId, it.cnt) }
            json.encodeToString(rows)
        }

        safePut(KEY_TOTAL_MESSAGES, now) {
            json.encodeToString(TotalCount(messageDao.countMessages()))
        }

        safePut(KEY_TOTAL_SESSIONS, now) {
            json.encodeToString(TotalCount(sessionDao.count()))
        }

        Logger.i(TAG, "refreshAll: Actualización completada")
    }

    /**
     * Leer el valor almacenado en caché para la clave especificada(JSON cuerda),Devuelve nulo si no está presente.
     *
     * @param key clave de caché(Recomendado para usar [companion object][Companion] definido en KEY_* constante)
     * @return JSON cuerda;Volver cuando no hay caché null
     */
    suspend fun get(key: String): String? = withContext(AppDispatchers.io) {
        statsCacheDao.get(key)?.value
    }

    /**
     * Observe los cambios en el valor de la caché para una clave específica. Inserte automáticamente nuevos valores cuando el caché se actualice o invalide.
     *
     * @param key clave de caché
     * @return Insertar el valor almacenado en caché actual(Cuando no hay caché, es null)de Flow
     */
    fun observe(key: String): Flow<String?> = statsCacheDao.observe(key).map { it?.value }

    /**
     * Borre todo el caché de estadísticas.
     *
     * Enviar mensaje/Llamado después de la eliminación,Haga que la página de estadísticas obtenga un caché vacío y active una actualización retrasada la próxima vez que se lea.
     * (Actualización retrasada por parte de la persona que llama/WorkManager Programación,Evite la agregación repetida de tablas completas durante la escritura de alta frecuencia).
     */
    suspend fun invalidateAll() = withContext(AppDispatchers.io) {
        Logger.i(TAG, "invalidateAll: Borrar toda la caché de estadísticas")
        statsCacheDao.deleteAll()
    }

    /**
     * Calcular y escribir una única entrada de caché;Registrar advertencias y omitir al calcular excepciones,No afecta a otros artículos.
     *
     * @param key clave de caché
     * @param now Marca de tiempo directa
     * @param block Lógica para calcular valores almacenados en caché(Devolver cadena JSON),en [AppDispatchers.io] ejecutar en
     */
    private suspend fun safePut(key: String, now: Long, block: suspend () -> String) {
        val value = try {
            block()
        } catch (e: Exception) {
            Logger.w(TAG, "refreshAll: calcular $key falló,saltar: ${e.message}", e)
            return
        }
        statsCacheDao.upsert(StatsCacheEntity(key = key, value = value, updatedAt = now))
    }
}
