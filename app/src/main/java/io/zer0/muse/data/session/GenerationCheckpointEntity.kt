package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * B5-01: Generación de puntos de control de streaming (generación de bandeja de salida).
 *
 * Se escribe un registro al inicio de cada generación de streaming y el contenido generado se coloca periódicamente;
 * Eliminar después de la finalización normal de la generación. Después de finalizar un proceso, los registros residuales se utilizan para:
 * - Restaurar contenido producido (messages Es posible que a la tabla le falten los últimos fragmentos)
 * - Marcar este mensaje del asistente como [interrumpido]
 * - La posterior "generación continua" de B7-04 encuentra el punto de partida de la continuación de este registro.
 */
@Entity(
    tableName = "generation_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("createdAt"),
    ],
)
data class GenerationCheckpointEntity(
    @PrimaryKey val assistantMessageId: String,
    val sessionId: String,
    val userMessageId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface GenerationCheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GenerationCheckpointEntity)

    @Query("DELETE FROM generation_checkpoints WHERE assistantMessageId = :assistantMessageId")
    suspend fun deleteByAssistantMessageId(assistantMessageId: String)

    /**
     * B-23: Eliminar todos los puntos de control por mensaje de usuario — Múltiples ciclos de herramientas escriben un punto de control en cada ronda,
     * Al final, limpie los mensajes de los usuarios en lotes de acuerdo con esta ronda.,Evite los residuos en los puntos de control a mitad de recorrido(Después de reiniciar
     * recoverInterruptedGenerations mal etiquetado [interrumpido]).
     */
    @Query("DELETE FROM generation_checkpoints WHERE userMessageId = :userMessageId")
    suspend fun deleteByUserMessageId(userMessageId: String)

    @Query("DELETE FROM generation_checkpoints WHERE sessionId = :sessionId AND createdAt >= :fromCreatedAt")
    suspend fun deleteBySessionAndCreatedAtFrom(sessionId: String, fromCreatedAt: Long)

    /**
     * Revisión de corrección (2.0 C-14/C-20/B-01): prensa"Conversacional + Preciso createdAt"Eliminar punto de control —
     * Todas las rondas generadas en la misma generación comparten lo mismo createdAt(launchStream de streamStartedAt),
     * La coincidencia exacta solo limpia la generación actual:
     * - C-20: El punto de control creado en la regeneración concurrente es diferente,No se eliminará por error.;
     * - C-14: No se basa en mensajes de USUARIO id,Todas las rondas se pueden borrar incluso cuando no hay ningún mensaje de USUARIO(Ya no se reduce a simplemente eliminar un solo elemento).
     */
    @Query("DELETE FROM generation_checkpoints WHERE sessionId = :sessionId AND createdAt = :createdAt")
    suspend fun deleteBySessionAndCreatedAt(sessionId: String, createdAt: Long)

    @Query("SELECT COUNT(*) FROM generation_checkpoints WHERE userMessageId = :userMessageId")
    suspend fun countByUserMessageId(userMessageId: String): Int

    @Query("SELECT * FROM generation_checkpoints ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<GenerationCheckpointEntity>

    @Query("SELECT * FROM generation_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySession(sessionId: String): List<GenerationCheckpointEntity>

    /** Limpie y genere puntos de control sincrónicamente al eliminar temporalmente una sesión para evitar que se reescriba la lógica de recuperación. */
    @Query("DELETE FROM generation_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** Limpie el estado de compilación anterior antes de reemplazar completamente la copia de seguridad de la sesión,Evite restaurar escrituras posteriores de mensajes antiguos. */
    @Query("DELETE FROM generation_checkpoints")
    suspend fun deleteAll()

    @Query(
        "SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId AND role = 'ASSISTANT' AND createdAt > :afterCreatedAt",
    )
    suspend fun countNewerAssistantMessages(sessionId: String, afterCreatedAt: Long): Int
}
