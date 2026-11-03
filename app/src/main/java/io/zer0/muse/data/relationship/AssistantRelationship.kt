package io.zer0.muse.data.relationship

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * El estado de la relación entre el personaje y el propietario del pequeño teléfono celular (P1-1).
 *
 * ¿Por qué crear una tabla separada en lugar de ir a `assistants` Agregar columna:
 * `assistants` Ya hay más de 50 columnas, y esas columnas están"Configuración de roles"(Personaje, modelo, herramientas, aparejo).
 * la relación es**Estado evolucionado en tiempo de ejecución**(La intimidad cambiará, el tiempo de interacción reciente cambiará),
 * Rellenar la tabla de configuración ensuciará la copia de seguridad, la importación, la exportación y la comparación de configuraciones.
 *
 * Propósito:
 *  - La probabilidad de enviar mensajes privados de forma proactiva se ha cambiado de un 40% fijo a una "probabilidad básica × coeficiente de intimidad × "Se busca equipo"
 *  - La selección de personas para interactuar en el círculo de amigos ha cambiado de aleatoria a ponderada por la intimidad.
 *  - El sistema solicita inyectar una línea de descripción de la relación para que el personaje sepa"¿Cuál es tu relación ahora?"
 *
 * Compensaciones de diseño:
 *  - intimidad [affinity] Utilice números enteros del 0 al 100 en lugar de puntos flotantes: evite errores acumulativos y facilite la migración
 *  - [lastInteractionAt] Registre la última interacción real para"¿Hace cuánto que no charlamos?"este tipo de juicio
 *  - [proactiveLevel] Utilice cadenas en lugar de números de serie de enumeración, por lo que no será necesario migrar al agregar archivos en el futuro.
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "assistant_relationship")
data class AssistantRelationshipEntity(
    /** con `assistants.id` corresponder. */
    @PrimaryKey val assistantId: String,
    /** Etiquetas de relación, texto libre (novia/colega/desconocido)…). Una cadena vacía significa que no está configurada. */
    @ColumnInfo(defaultValue = "") val relationLabel: String = "",
    /** Intimidad 0-100.0 = Completamente desconocido,100 = Muy cerca. */
    @ColumnInfo(defaultValue = "0") val affinity: Int = 0,
    /** Tiempo de última interacción real (milisegundos).0 = Nunca interactué. */
    @ColumnInfo(defaultValue = "0") val lastInteractionAt: Long = 0,
    /** Para enviar mensajes privados de forma proactiva a su ubicación preferida, consulte [ProactiveLevel]. */
    @ColumnInfo(defaultValue = "normal") val proactiveLevel: String = ProactiveLevel.NORMAL,
    /** Si se debe permitir que este personaje envíe mensajes privados de forma proactiva. Permitido de forma predeterminada, los usuarios pueden desactivarlo individualmente. */
    @ColumnInfo(defaultValue = "1") val proactiveEnabled: Boolean = true,
    /** Límite diario de mensajes privados activos,0 = Sin límite. */
    @ColumnInfo(defaultValue = "0") val dailyProactiveLimit: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
) {
    /** Coeficiente de probabilidad de mensajes privados correspondiente a la intimidad:0 → 0.3,50 → 1.0,100 → 1.7. */
    val affinityFactor: Float
        get() = 0.3f + affinity.coerceIn(0, 100) / 100f * 1.4f

    /** El coeficiente correspondiente a la marcha deseada. */
    val proactiveFactor: Float
        get() = ProactiveLevel.factorOf(proactiveLevel)
}

/** Tome la iniciativa de enviar un mensaje privado a su ubicación deseada. Cadena constante, el nuevo equipo no requiere migración de base de datos. */
object ProactiveLevel {
    /** Rara vez proactivo: apenas te busco. */
    const val LOW = "low"

    /** Normal: archivo predeterminado. */
    const val NORMAL = "normal"

    /** Clingy: Significativamente más proactivo. */
    const val HIGH = "high"

    val ALL = listOf(LOW, NORMAL, HIGH)

    /** engranaje → Coeficiente de probabilidad. */
    fun factorOf(level: String): Float = when (level) {
        LOW -> 0.4f
        HIGH -> 1.8f
        else -> 1.0f
    }
}

@Dao
interface AssistantRelationshipDao {

    @Query("SELECT * FROM assistant_relationship WHERE assistantId = :assistantId")
    suspend fun get(assistantId: String): AssistantRelationshipEntity?

    @Query("SELECT * FROM assistant_relationship")
    suspend fun getAll(): List<AssistantRelationshipEntity>

    @Query("SELECT * FROM assistant_relationship")
    fun observeAll(): Flow<List<AssistantRelationshipEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AssistantRelationshipEntity)

    @Query("DELETE FROM assistant_relationship WHERE assistantId = :assistantId")
    suspend fun delete(assistantId: String)

    /** v2.6.7: Borrar antes de restaurar la copia de seguridad (mismo calibre que otras tablas). */
    @Query("DELETE FROM assistant_relationship")
    suspend fun deleteAll()

    /**
     * Intimidad acumulativa (con límites cada vez más bajos).
     *
     * Utilice SQL para incrementos en lugar de leer primero y luego escribir para evitar perder actualizaciones en escenarios concurrentes;
     * al mismo tiempo [lastInteractionAt] Actualizados juntos, la persona que llama solo necesita pasar una marca de tiempo.
     */
    @Query(
        """
        UPDATE assistant_relationship
        SET affinity = MIN(100, MAX(0, affinity + :delta)),
            lastInteractionAt = :now,
            updatedAt = :now
        WHERE assistantId = :assistantId
        """,
    )
    suspend fun bumpAffinity(assistantId: String, delta: Int, now: Long): Int

    /** Solo se actualiza la hora de interacción más reciente (el nivel de intimidad no cambia). */
    @Query(
        """
        UPDATE assistant_relationship
        SET lastInteractionAt = :now, updatedAt = :now
        WHERE assistantId = :assistantId
        """,
    )
    suspend fun touchLastInteraction(assistantId: String, now: Long): Int
}
