package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Flujo de uso para una solicitud de modelo.
 *
 * Por qué se necesita esta tabla: anteriormente, la página de estadísticas tenía que recorrer todos los mensajes, analizar JSON y deserializar el campo del token.
 * Si hay demasiadas sesiones, tendrás que esperar unos segundos para abrirlas; y**Solicitudes no conversacionales**(Compresión, creación de títulos, traducción, organización de memoria,OCR)
 * No hay ningún portador de mensajes y el uso es completamente invisible.
 *
 * Aquí, cada solicitud ingresa a una fila y las estadísticas se cambian para agregar directamente esta tabla: la consulta es rápida, el calibre está unificado y las fuentes se pueden dividir.
 *
 * Puntos de diseño:
 *  - Solo agrega pero no modifica, por lo que, naturalmente, es adecuado para la agregación por rango de tiempo;
 *  - [source] Utilice cadenas en lugar de números de enumeración, de modo que se agreguen nuevas fuentes en el futuro sin necesidad de migración;
 *  - Los registros de conversaciones se rellenan con el campo de uso existente en los mensajes durante la migración y los datos históricos no se pierden.
 */
@Entity(
    tableName = "usage_records",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["source", "created_at"]),
        Index(value = ["model_id", "created_at"]),
    ],
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** La hora a la que se produjo la solicitud (milisegundos). */
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    /** Para fuentes de solicitud, consulte [UsageSource]. */
    val source: String,
    /** Identificador de modelo (como deepseek-v4-flash), cadena vacía si se desconoce. */
    @ColumnInfo(name = "model_id", defaultValue = "") val modelId: String = "",
    /** ID de proveedor (para dividir por canal). */
    @ColumnInfo(name = "provider_id", defaultValue = "") val providerId: String = "",
    @ColumnInfo(name = "prompt_tokens", defaultValue = "0") val promptTokens: Int = 0,
    @ColumnInfo(name = "completion_tokens", defaultValue = "0") val completionTokens: Int = 0,
    @ColumnInfo(name = "reasoning_tokens", defaultValue = "0") val reasoningTokens: Int = 0,
    @ColumnInfo(name = "cached_tokens", defaultValue = "0") val cachedTokens: Int = 0,
    /**
     * caché**escribir** token(Anthropic cache_creation_input_tokens).
     *
     * con [cachedTokens]Desglose de la columna (lectura de caché): el precio unitario de escritura es más alto que el de la entrada normal (Anthropic es 1,25 veces),
     * Una vez combinado en un número, ya no se puede separar. Comportamiento anterior 0 (no hay volumen de escritura para recordar si la columna no existe).
     */
    @ColumnInfo(name = "cache_write_tokens", defaultValue = "0") val cacheWriteTokens: Int = 0,
    /**
     * Retraso de la primera palabra (milisegundos).0 Indica que no está registrado (solicitud sin transmisión o datos antiguos).
     * Esta es la percepción del usuario.“rápido o lento”El indicador más directo es más significativo que el tiempo total invertido.
     */
    @ColumnInfo(name = "first_token_ms", defaultValue = "0") val firstTokenMs: Long = 0,
    /** La solicitud completa tomó (milisegundos),0 Indica no registrado. */
    @ColumnInfo(name = "duration_ms", defaultValue = "0") val durationMs: Long = 0,
)

/** Fuente de dosificación. Constantes de cadena, las nuevas fuentes no requieren migración de la base de datos. */
object UsageSource {
    const val CHAT = "chat"
    const val COMPRESS = "compress"
    const val TITLE = "title"
    const val SUGGESTION = "suggestion"
    const val TRANSLATE = "translate"
    const val MEMORY = "memory"
    const val OCR = "ocr"
    const val OTHER = "other"

    val ALL = listOf(CHAT, COMPRESS, TITLE, SUGGESTION, TRANSLATE, MEMORY, OCR, OTHER)
}

/** Una fila agregada por fuente. */
data class UsageBySource(
    val source: String,
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
)

/** Una fila agregada por modelo. */
data class UsageByModel(
    val modelId: String,
    val requests: Int,
    val totalTokens: Long,
)

/** Una fila agregada por día (day para aaaa-MM-dd). */
data class UsageByDay(
    val day: String,
    val requests: Int,
    val totalTokens: Long,
)

/** Uso total. */
data class UsageTotals(
    val requests: Int,
    @ColumnInfo(name = "prompt_tokens") val promptTokens: Long,
    @ColumnInfo(name = "completion_tokens") val completionTokens: Long,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Long,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Long,
    @ColumnInfo(name = "cache_write_tokens") val cacheWriteTokens: Long,
    @ColumnInfo(name = "avg_first_token_ms") val avgFirstTokenMs: Long,
)

@Dao
interface UsageRecordDao {

    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Insert
    suspend fun insertAll(records: List<UsageRecordEntity>)

    /** Rango de tiempo total.sinceMs Un valor de 0 significa que no hay punto de partida. */
    @Query(
        """
        SELECT COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
               COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
               COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens,
               COALESCE(SUM(cached_tokens), 0) AS cached_tokens,
               COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
               COALESCE(AVG(NULLIF(first_token_ms, 0)), 0) AS avg_first_token_ms
        FROM usage_records
        WHERE created_at >= :sinceMs
        """,
    )
    suspend fun totalsSince(sinceMs: Long): UsageTotals?

    /** Dividido por fuente. */
    @Query(
        """
        SELECT source AS source,
               COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens), 0) AS promptTokens,
               COALESCE(SUM(completion_tokens), 0) AS completionTokens
        FROM usage_records
        WHERE created_at >= :sinceMs
        GROUP BY source
        ORDER BY requests DESC
        """,
    )
    suspend fun bySourceSince(sinceMs: Long): List<UsageBySource>

    /** Clasificación del modelo (por token total). */
    @Query(
        """
        SELECT model_id AS modelId,
               COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM usage_records
        WHERE created_at >= :sinceMs AND model_id != ''
        GROUP BY model_id
        ORDER BY totalTokens DESC
        LIMIT :limit
        """,
    )
    suspend fun topModelsSince(sinceMs: Long, limit: Int = 8): List<UsageByModel>

    /**
     * Agregado por día (day = yyyy-MM-dd, zona horaria local).
     *
     * Usando SQLite date() cooperar 'localtime', garantizado para ser consistente con la fecha vista por el usuario;
     * El desplazamiento de la zona horaria se maneja en SQL para evitar volver a calcularlo por parte de Kotlin.
     */
    @Query(
        """
        SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS day,
               COUNT(*) AS requests,
               COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS totalTokens
        FROM usage_records
        WHERE created_at >= :sinceMs
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    suspend fun byDaySince(sinceMs: Long): List<UsageByDay>

    /** Limpiar registros anteriores al tiempo especificado (la operación a largo plazo evita el crecimiento infinito de la tabla). */
    @Query("DELETE FROM usage_records WHERE created_at < :beforeMs")
    suspend fun deleteBefore(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM usage_records")
    suspend fun count(): Int
}
