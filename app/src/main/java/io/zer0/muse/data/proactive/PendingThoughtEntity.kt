package io.zer0.muse.data.proactive

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Preocupado por el libro mayor —— "Tengo algo que decirle a esta persona".
 *
 * Esta tabla es la base del mecanismo de mensajería proactiva. La implementación anterior determina "debe enviarse ahora" desde cero cada vez.
 * El orden correcto de las preguntas es "¿Hay algo de qué hablar en la cuenta?" No existe tal mesa
 * "Nada que decir" no es un estado expresable, y el sistema sólo puede confiar en la puntuación y la probabilidad para forzar "nada".
 *
 * La contabilidad en sí no genera costos de LLM: las fuentes son parásitas en las llamadas a herramientas en conversaciones normales,
 * O es pura derivación de reglas locales.
 */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "pending_thought",
    indices = [
        Index(value = ["assistantId", "status", "expiresAt"], name = "index_pending_thought_dispatch"),
        Index(value = ["sourceRef"], name = "index_pending_thought_sourceRef"),
    ],
)
data class PendingThoughtEntity(
    @PrimaryKey val id: String,
    /** ¿A qué asistente pertenece? Cada uno de los asistentes tiene sus propias cuentas y no se visitan entre sí. */
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    /** [ThoughtKind] nombre. */
    @ColumnInfo(defaultValue = "FOLLOW_UP") val kind: String = ThoughtKind.FOLLOW_UP.name,
    /** El texto original del asunto, una frase. Escriba para el asistente, no para el usuario. */
    @ColumnInfo(defaultValue = "") val seed: String = "",
    /** [ThoughtSource] nombre. */
    @ColumnInfo(defaultValue = "CONVERSATION") val source: String = ThoughtSource.CONVERSATION.name,
    /** ID del objeto de origen (ID de memoria/ID de hito/ID de mensaje), utilizado para el seguimiento y la deduplicación. */
    @ColumnInfo(defaultValue = "NULL") val sourceRef: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    /** Marca de tiempo de vencimiento.NULL = Nunca caduca. */
    @ColumnInfo(defaultValue = "NULL") val expiresAt: Long? = null,
    /** [ThoughtUrgency] nombre. */
    @ColumnInfo(defaultValue = "WINDOW") val urgency: String = ThoughtUrgency.WINDOW.name,
    /** Cuanto me importa este asunto,0..1. */
    @ColumnInfo(defaultValue = "0.5") val weight: Float = 0.5f,
    /** La última vez que te hablé de salir,NULL = Nunca dije eso. */
    @ColumnInfo(defaultValue = "NULL") val lastSurfacedAt: Long? = null,
    /** Lo dijo varias veces. Se utiliza para evitar repetir lo mismo una y otra vez. */
    @ColumnInfo(defaultValue = "0") val surfaceCount: Int = 0,
    /** [ThoughtStatus] nombre. */
    @ColumnInfo(defaultValue = "OPEN") val status: String = ThoughtStatus.OPEN.name,
) {
    fun kindEnum(): ThoughtKind = enumOr(ThoughtKind.entries, kind, ThoughtKind.FOLLOW_UP)

    fun urgencyEnum(): ThoughtUrgency = enumOr(ThoughtUrgency.entries, urgency, ThoughtUrgency.WINDOW)

    fun statusEnum(): ThoughtStatus = enumOr(ThoughtStatus.entries, status, ThoughtStatus.OPEN)

    fun sourceEnum(): ThoughtSource = enumOr(ThoughtSource.entries, source, ThoughtSource.CONVERSATION)

    /**
     * Restaurar la enumeración por nombre, los valores ilegales vuelven a [fallback].
     *
     * No hay necesidad `enumValueOf` Análisis directo: puede haber versiones antiguas escritas en la biblioteca,
     * Ahora que se ha eliminado el nombre de la enumeración, el análisis directo generará una excepción e interrumpirá toda la programación.
     */
    private fun <T : Enum<T>> enumOr(values: List<T>, raw: String, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback
}

/**
 * El puerto de lectura del libro mayor.
 *
 * con [PendingThoughtWriteDao] Están separados porque los escenarios en los que se convocan son completamente diferentes:
 * La lectura se realiza en cada ronda de inspección (ruta caliente) y la escritura se realiza en contabilidad./Ejecutar durante el mantenimiento (vía fría).
 * Dividirlo también hace que la cantidad de funciones en ambos lados esté dentro del rango legible. —— Una persona con más de veinte métodos. DAO
 * Nadie puede decir de qué es responsable.
 *
 * Esta interfaz solo realiza acceso y no emite juicios comerciales:fitness Cómo calcular, si enviarlo, cuándo enviarlo,
 * Todos están determinados por el motor de decisiones de nivel superior. La fila original se devuelve aquí.
 */
@Dao
interface PendingThoughtDao {

    @Upsert
    suspend fun upsert(thought: PendingThoughtEntity)

    @Query("SELECT * FROM pending_thought WHERE id = :id")
    suspend fun getById(id: String): PendingThoughtEntity?

    /**
     * Obtenga todas las inquietudes no dichas sobre un asistente.
     *
     * Sin clasificación:fitness Depende del momento actual (freshness / windowFit Todo relacionado con el ahora),
     * La clasificación debe realizarse en la memoria.SQL No se puede dar de alta.
     */
    @Query("SELECT * FROM pending_thought WHERE assistantId = :assistantId AND status = 'OPEN'")
    suspend fun getOpen(assistantId: String): List<PendingThoughtEntity>

    /** Todo ABIERTO (asistente cruzado) para que la página de configuración muestre el libro mayor. */
    @Query("SELECT * FROM pending_thought WHERE status = 'OPEN' ORDER BY createdAt DESC")
    suspend fun getAllOpen(): List<PendingThoughtEntity>

    /** Varios elementos anotados recientemente se muestran en la página de configuración. */
    @Query("SELECT * FROM pending_thought ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PendingThoughtEntity>

    /**
     * Deduplicación del mismo origen: solo se registra un objeto del mismo origen.
     *
     * Sin esto, el mismo recuerdo volverá a derivarse a una nueva preocupación en cada ronda de inspección.
     * El libro mayor se expandirá a un ritmo de una entrada por hora.
     */
    @Query("SELECT COUNT(*) FROM pending_thought WHERE sourceRef = :sourceRef AND status = 'OPEN'")
    suspend fun countOpenBySourceRef(sourceRef: String): Int

    /**
     * Obtenga todos los ID de origen contabilizados para la deduplicación por lotes durante la derivación.
     *
     * Que sintonizar uno por uno [countOpenBySourceRef] Bien: puedo memorizar docenas de elementos a la vez.
     * Una consulta por entrada convierte la derivación en viajes de ida y vuelta de N DB (y eso está en la ruta de sondeo).
     */
    @Query(
        "SELECT sourceRef FROM pending_thought WHERE assistantId = :assistantId " +
            "AND status = 'OPEN' AND sourceRef IS NOT NULL",
    )
    suspend fun getOpenSourceRefs(assistantId: String): List<String>

    @Query("SELECT COUNT(*) FROM pending_thought WHERE assistantId = :assistantId AND status = 'OPEN'")
    suspend fun countOpen(assistantId: String): Int

    /** Volumen completo (incluido el finalizado), para copia de seguridad y exportación. */
    @Query("SELECT * FROM pending_thought")
    suspend fun getAll(): List<PendingThoughtEntity>
}

/**
 * Preocupado por el puerto de escritura y mantenimiento del libro mayor.
 *
 * Sólo hay una dirección del flujo de estado:OPEN → DONE / EXPIRED / DROPPED.
 * No hay camino de regreso a OPEN —— Revelando las cosas a las que te has rendido otra vez,
 * Peor que no tener ningún demérito en primer lugar.
 */
@Dao
interface PendingThoughtWriteDao {

    @Query("UPDATE pending_thought SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Marcar como hablado y hacer +1 en el número de recordatorios. */
    @Query(
        "UPDATE pending_thought SET lastSurfacedAt = :at, surfaceCount = surfaceCount + 1 " +
            "WHERE id = :id",
    )
    suspend fun markSurfaced(id: String, at: Long)

    /** Limpia las filas que han caducado pero que todavía están en estado ABIERTO. */
    @Query(
        "UPDATE pending_thought SET status = 'EXPIRED' " +
            "WHERE status = 'OPEN' AND expiresAt IS NOT NULL AND expiresAt <= :now",
    )
    suspend fun expireOverdue(now: Long): Int

    /** Si no hay respuesta después de que lo mencionen repetidamente, abandone automáticamente y evite pensar en ello todo el tiempo. */
    @Query(
        "UPDATE pending_thought SET status = 'DROPPED' " +
            "WHERE status = 'OPEN' AND surfaceCount >= :maxSurface",
    )
    suspend fun dropOverSurfaced(maxSurface: Int): Int

    @Query("DELETE FROM pending_thought WHERE status != 'OPEN' AND createdAt < :before")
    suspend fun deleteClosedBefore(before: Long): Int

    @Query("DELETE FROM pending_thought WHERE id = :id")
    suspend fun delete(id: String)

    /** Borre la copia de seguridad antes de restaurar. */
    @Query("DELETE FROM pending_thought")
    suspend fun deleteAll()
}
