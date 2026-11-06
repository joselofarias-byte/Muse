package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * punto de control de contexto —— compresión de sesión (context compaction) producto persistente.
 *
 * ## por qué es necesario
 *
 * Si la compresión solo cambia la lista de mensajes en la memoria, habrá tres consecuencias: al reabrir la sesión se volverá al historial completo (lo que equivale a no comprimir),
 * Cada recarga requiere otra llamada de resumen y"Compresión"A los ojos del usuario, se inserta un mensaje en lugar de un restablecimiento de contexto.
 * Coloque los resultados de la compresión en una línea.**Eventos de sesión con punteros acotados**, para que"contexto comprimido"convertirse en un hecho estable.
 *
 * ## ¿Por qué utilizar seq en lugar de la colección de ID de mensaje para el puntero de límite?
 *
 * La tabla de mensajes ya tiene un aumento monótono. `seq` columnas. a `covered_seq`("Cubierto hasta el último mensaje. seq") es el límite:
 *  - Para armar el contexto simplemente `seq > covered_seq` No es necesario que una condición lleve un conjunto de identificación;
 *  - Los mensajes antiguos se eliminan/La edición no invalidará el borde en su totalidad (id reunión);
 *  - Es estable en todos los procesos y sigue siendo válido al reiniciar la aplicación.
 *
 * [lastCoveredMessageId] con [coveredCount] Sólo para**control de coherencia**: El ID del último mensaje en el momento límite y
 * Número de tiras de cobertura. Si la identificación ya no existe al volver a leer, significa que el historial se ha modificado y el punto de control debe invalidarse y regenerarse (consulte Uso de DAO).
 *
 * ## desplazarse en lugar de agregar
 *
 * Sólo se mantiene un punto de control por sesión ([sessionId] Clave principal, sobrescritura de inserción): nueva compresión"Antiguo punto de control + nuevo intervalo"
 * Reescritos juntos en un nuevo punto de control. Prevenir estructuralmente"resumen conjunto abstracto"Capas sobre capas.
 *
 * @property coveredSeq seq del último mensaje que se ha incorporado al checkpoint; solo los mensajes con una secuencia mayor que la que se toman al ensamblar el contexto
 * @property lastCoveredMessageId La identificación del último mensaje en el momento del límite (utilizado para verificar la coherencia, puede ser una cadena vacía)
 * @property coveredCount Número de mensajes cubiertos (auditoría/para exhibición)
 * @property summary Texto del punto de control (formato estructurado, consulte ContextCheckpointFormat)
 * @property tokensBefore Estimación del token de contexto antes de la compresión.
 * @property tokensAfter Estimación del token de contexto después de la compresión.
 * @property strategy Método de salida:summary(Resumen del modelo) / local(Reconstrucción local sin solicitud) / truncate(Truncamiento duro)
 * @property reason Motivo desencadenante:auto(Tasa de ocupación) / manual(manual de usuario) / midrun(Crear costuras a mitad de camino)
 * @property updatedAt escribir tiempo (epoch millis)
 */
@Serializable
@Entity(tableName = "context_checkpoints")
data class ContextCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "covered_seq")
    val coveredSeq: Long,

    @ColumnInfo(name = "last_covered_message_id", defaultValue = "''")
    val lastCoveredMessageId: String = "",

    @ColumnInfo(name = "covered_count", defaultValue = "0")
    val coveredCount: Int = 0,

    /**
     * El número total de mensajes incorporados al resumen (acumulación continua, solo para visualización de interfaz)"¿Cuántos elementos se han comprimido?").
     *
     * con [coveredCount] La diferencia:[coveredCount] ¿Es el número de elementos recién fusionados en esta compresión (auditoría)?/para verificación),
     * Aquí está el valor acumulado histórico. La semántica aumenta de forma monótona con el tiempo, lo que es adecuado para su visualización.
     */
    @ColumnInfo(name = "total_covered_count", defaultValue = "0")
    val totalCoveredCount: Int = 0,

    @ColumnInfo(name = "summary")
    val summary: String,

    @ColumnInfo(name = "tokens_before", defaultValue = "0")
    val tokensBefore: Int = 0,

    @ColumnInfo(name = "tokens_after", defaultValue = "0")
    val tokensAfter: Int = 0,

    @ColumnInfo(name = "strategy", defaultValue = "summary")
    val strategy: String = STRATEGY_SUMMARY,

    @ColumnInfo(name = "reason", defaultValue = "auto")
    val reason: String = REASON_AUTO,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "space_id", defaultValue = "default")
    val spaceId: String = "default",
) {
    /** El token guardado por compresión (puede ser 0 o un valor negativo, el lector decide cómo mostrarlo). */
    val savedTokens: Int get() = (tokensBefore - tokensAfter).coerceAtLeast(0)

    companion object {
        /** Salida resumida del modelo. */
        const val STRATEGY_SUMMARY = "summary"

        /** Salida de reconstrucción local para solicitudes de modelo cero. */
        const val STRATEGY_LOCAL = "local"

        /** Salida truncada dura (última línea de defensa cuando los resúmenes no están disponibles). */
        const val STRATEGY_TRUNCATE = "truncate"

        /** Activador de ocupación. */
        const val REASON_AUTO = "auto"

        /** Activado manualmente por el usuario. */
        const val REASON_MANUAL = "manual"

        /** La costura se activa en cada ronda durante la generación. */
        const val REASON_MIDRUN = "midrun"
    }
}
