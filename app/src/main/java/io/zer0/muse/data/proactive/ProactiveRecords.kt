package io.zer0.muse.data.proactive

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** El destino final de un mensaje no solicitado. */
enum class OutcomeState {
    /** Enviado, aún no hay respuesta. */
    PENDING,

    /** El usuario hizo clic en la notificación. */
    OPENED,

    /** El usuario respondió. Los comentarios positivos más fuertes. */
    REPLIED,

    /** El usuario tachó la notificación. Comentarios negativos débiles. */
    DISMISSED,

    /** Ninguna acción después del tiempo de espera. */
    EXPIRED,
}

/**
 * Cuenta de resultados de mensajes activos —— Una capa que faltaba por completo en la implementación anterior.
 *
 * Un sistema que envía y finaliza no aprende nada: no sabe si el usuario es acogedor o está aburrido.
 * Sólo puedes adivinar ajustando los parámetros. Esta tabla permite "a los usuarios hacer clic en ella/responder/tacharla"
 * Se convierten en señales aprendibles que impulsan la intimidad en las relaciones y varios tipos de tendencias generativas.
 */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "proactive_outcome",
    indices = [
        Index(value = ["assistantId", "sentAt"], name = "index_proactive_outcome_assistant"),
        Index(value = ["thoughtId"], name = "index_proactive_outcome_thought"),
        Index(value = ["state"], name = "index_proactive_outcome_state"),
    ],
)
data class ProactiveOutcomeEntity(
    @PrimaryKey val id: String,
    /** A qué preocupación corresponde. Puede estar vacío (datos históricos distintos de las clases acordadas por el usuario). */
    @ColumnInfo(defaultValue = "NULL") val thoughtId: String? = null,
    /** El ID del mensaje después de descartarse se utiliza para la verificación inversa de \"detección de respuesta\". */
    @ColumnInfo(defaultValue = "NULL") val messageId: String? = null,
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    @ColumnInfo(defaultValue = "") val sessionId: String = "",
    /** Anote el tipo en ese momento para obtener comentarios estadísticos por tipo. Las estadísticas pueden continuar después de borrar las filas en cuestión. */
    @ColumnInfo(defaultValue = "FOLLOW_UP") val kind: String = ThoughtKind.FOLLOW_UP.name,
    @ColumnInfo(defaultValue = "0") val sentAt: Long = 0L,
    @ColumnInfo(defaultValue = "NULL") val openedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val repliedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val dismissedAt: Long? = null,
    /** [OutcomeState] nombre. */
    @ColumnInfo(defaultValue = "PENDING") val state: String = OutcomeState.PENDING.name,
) {
    fun stateEnum(): OutcomeState =
        OutcomeState.entries.firstOrNull { it.name == state } ?: OutcomeState.PENDING
}

@Dao
interface ProactiveOutcomeDao {

    @Upsert
    suspend fun upsert(outcome: ProactiveOutcomeEntity)

    @Query("SELECT * FROM proactive_outcome WHERE id = :id")
    suspend fun getById(id: String): ProactiveOutcomeEntity?

    /**
     * Busque el mensaje más reciente que aún está esperando respuesta.
     *
     * Cuando los usuarios respondan, utilícelo para verificar "a qué mensaje activo responde esta respuesta":
     * Obtenga el sentAt más reciente de la sesión y el estado es PENDIENTE/ABIERTO.
     */
    @Query(
        "SELECT * FROM proactive_outcome WHERE sessionId = :sessionId " +
            "AND state IN ('PENDING', 'OPENED') ORDER BY sentAt DESC LIMIT 1",
    )
    suspend fun findPendingInSession(sessionId: String): ProactiveOutcomeEntity?

    /** Se proporcionan varios resultados recientes de un asistente para visualización y estadísticas en la página de configuración. */
    @Query(
        "SELECT * FROM proactive_outcome WHERE assistantId = :assistantId " +
            "ORDER BY sentAt DESC LIMIT :limit",
    )
    suspend fun getRecent(assistantId: String, limit: Int): List<ProactiveOutcomeEntity>

    /** Cuente el número de cada estado después de un determinado momento para generar actualizaciones de intimidad. */
    @Query(
        "SELECT COUNT(*) FROM proactive_outcome WHERE assistantId = :assistantId " +
            "AND state = :state AND sentAt >= :since",
    )
    suspend fun countStateSince(assistantId: String, state: String, since: Long): Int

    /** Se utiliza un cierto tipo de distribución histórica de comentarios para determinar si \"los usuarios nunca están interesados ​​en este tipo de comentarios\". */
    @Query(
        "SELECT COUNT(*) FROM proactive_outcome WHERE assistantId = :assistantId " +
            "AND kind = :kind AND state = :state",
    )
    suspend fun countKindState(assistantId: String, kind: String, state: String): Int

    /**
     * Encuentre registros que aún no responden y que se enviaron antes de un momento determinado.
     *
     * La inspección lo utiliza para finalizar mensajes no solicitados que se "envían e ignoran durante un día" en EXPIRED.
     * No es necesario cambiar directamente el estado con una ACTUALIZACIÓN porque el nivel de intimidad debe ajustarse según el resultado final.
     * Y ese conjunto de juicios es [ProactiveOutcomeRepository.resolve] adentro.
     */
    @Query(
        "SELECT * FROM proactive_outcome WHERE state = 'PENDING' AND sentAt <= :before",
    )
    suspend fun listPendingBefore(before: Long): List<ProactiveOutcomeEntity>

    /** Finalice los registros cuyo tiempo de espera se agotó y no respondieron. */
    @Query(
        "UPDATE proactive_outcome SET state = 'EXPIRED' " +
            "WHERE state = 'PENDING' AND sentAt <= :before",
    )
    suspend fun expireStale(before: Long): Int

    @Query("DELETE FROM proactive_outcome WHERE sentAt < :before")
    suspend fun deleteBefore(before: Long): Int

    /** Volumen completo, para copia de seguridad y exportación. */
    @Query("SELECT * FROM proactive_outcome")
    suspend fun getAll(): List<ProactiveOutcomeEntity>

    /** Borre la copia de seguridad antes de restaurar. */
    @Query("DELETE FROM proactive_outcome")
    suspend fun deleteAll()
}

/**
 * relación de intimidad —— Una copia para cada asistente.
 *
 * Este es el mensaje de "cuanto más lo uses, mejor lo entenderás": el límite mínimo y el límite presupuestario están determinados por el grado seleccionado por el usuario.
 * La sección flotante en el medio está determinada por los resultados del aprendizaje aquí.
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "proactive_relation")
data class ProactiveRelationEntity(
    @PrimaryKey val assistantId: String,
    /** 0..1, el valor inicial es 0,15 (los nuevos usuarios son muy conservadores). */
    @ColumnInfo(defaultValue = "0.15") val intimacy: Float = 0.15f,
    /** Marca de tiempo de vencimiento del enfriamiento. Escrito cuando el usuario dice \"No me molestes últimamente\". */
    @ColumnInfo(defaultValue = "0") val cooldownUntil: Long = 0L,
    /** El nivel activo seleccionado por el usuario ([ProactiveIntensity] nombre). */
    @ColumnInfo(defaultValue = "STANDARD") val intensity: String = ProactiveIntensity.DEFAULT.name,
    /** Fecha de contabilidad presupuestaria (yyyy-MM-dd), borre el recuento de días actuales al cruzar días. */
    @ColumnInfo(defaultValue = "") val budgetDate: String = "",
    /** La cantidad de artículos consumidos ese día. */
    @ColumnInfo(defaultValue = "0") val spentToday: Int = 0,
    /**
     * La cantidad con la que los usuarios interactúan activamente para reponer la cantidad ese día.
     *
     * Ya no participa en el cálculo de la cuota (el número diario de barras ahora está enteramente determinado por [ProactiveIntensity.cap] decisión),
     * Las columnas están reservadas únicamente para compatibilidad con estructuras de bibliotecas más antiguas.
     */
    @ColumnInfo(defaultValue = "0") val earnedToday: Float = 0f,
) {
    fun intensityEnum(): ProactiveIntensity = ProactiveIntensity.fromName(intensity)
}

@Dao
interface ProactiveRelationDao {

    @Upsert
    suspend fun upsert(relation: ProactiveRelationEntity)

    @Query("SELECT * FROM proactive_relation WHERE assistantId = :assistantId")
    suspend fun get(assistantId: String): ProactiveRelationEntity?

    @Query("SELECT * FROM proactive_relation")
    suspend fun getAll(): List<ProactiveRelationEntity>

    @Query("UPDATE proactive_relation SET intimacy = :intimacy WHERE assistantId = :assistantId")
    suspend fun updateIntimacy(assistantId: String, intimacy: Float)

    @Query("UPDATE proactive_relation SET cooldownUntil = :until WHERE assistantId = :assistantId")
    suspend fun updateCooldown(assistantId: String, until: Long)

    @Query("UPDATE proactive_relation SET intensity = :intensity WHERE assistantId = :assistantId")
    suspend fun updateIntensity(assistantId: String, intensity: String)

    @Query(
        "UPDATE proactive_relation SET budgetDate = :date, spentToday = 0, earnedToday = 0 " +
            "WHERE assistantId = :assistantId",
    )
    suspend fun resetBudget(assistantId: String, date: String)

    @Query(
        "UPDATE proactive_relation SET spentToday = spentToday + 1 WHERE assistantId = :assistantId",
    )
    suspend fun incrementSpent(assistantId: String)

    @Query("DELETE FROM proactive_relation")
    suspend fun deleteAll()
}
