package io.zer0.muse.data.audit

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-4 Registrador de auditoría.
 *
 * Puntos de diseño:
 *  - [log] Es una interfaz de disparo y olvido sin suspensión.,Independiente para uso interno [scope] Escritura asincrónica,
 *    No bloquea a la persona que llama(La persona que llama es principalmente la ruta del hilo principal de UI/ViewModel).
 *  - buffer de anillo:Verifique de forma asincrónica después de cada inserción [count],exceder [MAX_LOG_COUNT] tiempo
 *    Eliminar los más antiguos de una vez [TRIM_BATCH] Artículo(Evite la sobrecarga de transacción de la eliminación artículo por artículo).
 *  - detail Map usar [AppJson] Serializar a cadena JSON,apoyo String / Number / Boolean /
 *    Tipos comunes como mapa/lista anidados,Reserva de tipo desconocido a toString().
 *  - [cleanupOldLogs] Llamado una vez cuando se inicia la aplicación.,limpiar [RETENTION_DAYS] Registro de hace días.
 *
 * @param dao Registro de auditoría DAO
 */
class AuditLogger(private val dao: AuditLogDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Límite superior del búfer circular:Superar este valor activa la limpieza por lotes. */
    private val maxLogCount = MAX_LOG_COUNT

    /** El número más antiguo de elementos eliminados a la vez cuando se activa la limpieza(Evite la sobrecarga de transacción de la eliminación artículo por artículo). */
    private val trimBatch = TRIM_BATCH

    /**
     * Registre un registro de auditoría.
     *
     * sin bloqueo:Escritura asincrónica de lanzamiento interno,La persona que llama no necesita esperar.
     *
     * @param category Clasificación:"api_call" / "user_action" / "auth" / "system"
     * @param action Nombre de la acción,Como por ejemplo "send_message" / "delete_provider" / "login"
     * @param target Objetivo de operación(chatId / providerId espera,Disponible)
     * @param detail campos extra(model / tokens / latency_ms espera),será serializado como JSON
     * @param success ¿La operación es exitosa?
     */
    fun log(category: String, action: String, target: String = "", detail: Map<String, Any> = emptyMap(), success: Boolean = true) {
        val now = System.currentTimeMillis()
        val detailJson = encodeDetail(detail)
        scope.launch {
            try {
                dao.insertWithCleanup(
                    timestamp = now,
                    category = category,
                    action = action,
                    target = target,
                    detail = detailJson,
                    success = success,
                    maxCount = maxLogCount,
                    trimBatch = trimBatch,
                )
            } catch (t: Throwable) {
                // La falla del registro de auditoría no debería afectar el negocio de la persona que llama,Sólo ha iniciado sesión en Logger
                Logger.w("AuditLogger", "No se pudo escribir el registro de auditoría: $category/$action", t)
            }
        }
    }

    /**
     * Registro de consultas de paginación(en orden cronológico inverso).
     *
     * @param limit Número de artículos por página(Predeterminado 100)
     * @param offset compensar
     */
    suspend fun query(limit: Int = 100, offset: Int = 0): List<AuditLogEntity> {
        return try {
            dao.query(limit, offset)
        } catch (t: Throwable) {
            Logger.w("AuditLogger", "No se pudo consultar el registro de auditoría", t)
            emptyList()
        }
    }

    /**
     * limpiar [RETENTION_DAYS] Registro de hace días. Llamado una vez cuando se inicia la aplicación.
     */
    suspend fun cleanupOldLogs() {
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 60L * 60L * 1000L
            dao.deleteOlderThan(cutoff)
        } catch (t: Throwable) {
            Logger.w("AuditLogger", "Error al limpiar los registros de auditoría antiguos", t)
        }
    }

    /**
     * Borrar todos los registros de auditoría(El usuario hace clic en la página de registro de auditoría."Borrar"llamado cuando).
     */
    suspend fun clearAll() {
        try {
            dao.deleteAll()
        } catch (t: Throwable) {
            Logger.w("AuditLogger", "No se pudieron borrar los registros de auditoría", t)
        }
    }

    /**
     * Serializar detalle Mapa a cadena JSON.
     *
     * apoyo String / Boolean / Number / Enum / anidado Map / List;
     * Reserva de tipo desconocido a [Any.toString].
     */
    private fun encodeDetail(detail: Map<String, Any>): String {
        if (detail.isEmpty()) return "{}"
        val jsonObject = buildJsonObject {
            detail.forEach { (k, v) -> put(k, toJsonElement(v)) }
        }
        return AppJson.encodeToString(JsonObject.serializer(), jsonObject)
    }

    /** Convertir cualquier valor a [JsonElement],Admite la conversión recursiva entre tipos básicos y tipos de contenedores. */
    private fun toJsonElement(v: Any): JsonElement = when (v) {
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toString())
        is Enum<*> -> JsonPrimitive(v.name)
        is Map<*, *> -> buildJsonObject {
            v.forEach { (nk, nv) ->
                if (nk is String && nv != null) put(nk, toJsonElement(nv))
            }
        }
        is List<*> -> JsonArray(v.map { it?.let { toJsonElement(it) } ?: JsonNull })
        is Array<*> -> JsonArray(v.map { it?.let { toJsonElement(it) } ?: JsonNull })
        else -> JsonPrimitive(v.toString())
    }

    companion object {
        /** El límite superior del número de entradas del búfer circular. */
        private const val MAX_LOG_COUNT = 10_000

        /** El número más antiguo de elementos eliminados a la vez cuando se activa la limpieza. */
        private const val TRIM_BATCH = 1_000

        /** Período de retención de registros(dia),Si excede, se limpiará al inicio. */
        private const val RETENTION_DAYS = 30
    }
}
