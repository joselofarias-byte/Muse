package io.zer0.muse.data.stats

import io.zer0.common.Logger

/**
 * Escritor de flujo de uso.
 *
 * Una sola entrada garantiza que el calibre del registro de cada solicitud de modelo sea consistente; la persona que llama no necesita preocuparse por la deduplicación y las excepciones.
 * Si la escritura falla, solo se registrará y no se enviará hacia arriba.——Las estadísticas son una capacidad de omisión y nunca deben usarse para interrumpir una conversación.
 */
class UsageRecorder(private val dao: UsageRecordDao) {

    /**
     * Registre una solicitud.
     *
     * @param source fuente, ver [UsageSource]
     * @param promptTokens entrada token
     * @param completionTokens Token de salida (ya incluye razonamiento para evitar el doble conteo)
     * @param reasoningTokens Token de razonamiento (solo visualización)
     * @param cachedTokens caché**leer** token(solo visualización)
     * @param cacheWriteTokens caché**escribir** token(mostrar sólo;En comparación con las columnas de lectura, el precio unitario de las dos es diferente)
     * @param firstTokenMs Retraso de la primera palabra;0 Indica no grabado (sin transmisión)
     * @param durationMs Tiempo total empleado;0 significa no registrado
     */
    suspend fun record(
        source: String,
        modelId: String = "",
        providerId: String = "",
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        reasoningTokens: Int = 0,
        cachedTokens: Int = 0,
        cacheWriteTokens: Int = 0,
        firstTokenMs: Long = 0,
        durationMs: Long = 0,
    ) {
        // Los registros totalmente cero no tienen valor estadístico y solo acumularán basura en la tabla (como ruedas de herramientas locales y reproducción de visitas de caché).
        if (promptTokens == 0 && completionTokens == 0) return
        runCatching {
            dao.insert(
                UsageRecordEntity(
                    createdAt = System.currentTimeMillis(),
                    source = source,
                    modelId = modelId,
                    providerId = providerId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                    cacheWriteTokens = cacheWriteTokens,
                    firstTokenMs = firstTokenMs.coerceAtLeast(0),
                    durationMs = durationMs.coerceAtLeast(0),
                ),
            )
        }.onFailure {
            Logger.w("UsageRecorder", "No se pudo escribir el registro de uso(source=$source): ${it.message}")
        }
    }

    /**
     * Limpiar registros caducados.
     *
     * La escala de uso sólo aumenta, no disminuye, y seguirá creciendo con el uso a largo plazo; retener [retentionDays] Los días son suficientes para ver tendencias.
     */
    suspend fun pruneOlderThan(retentionDays: Int = 180) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        runCatching { dao.deleteBefore(cutoff) }
            .onFailure { Logger.w("UsageRecorder", "No se pudieron borrar los registros de uso: ${it.message}") }
    }
}
