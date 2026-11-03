package io.zer0.muse.data.subagent

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.0.53: El subproceso del agente secundario persiste en la Entidad.
 *
 * reemplazar viejo tools/SubagentThreadStore.kt(Versión de memoria). Dos rutas de subagente compartidas:
 *  - camino A: SubagentTool + SkillExecutor.delegateAgent nonBlocking
 *  - camino B: SubagentRunSkill + SubagentRunner
 *
 * Ciclo de vida del hilo:
 *  - Primera generación de delegación threadId(El mismo requestId que la primera ejecución)
 *  - Posteriormente lleve threadId para continuar(Restaurar el historial de subsesiones)
 *  - El agente principal llama a subagent_close para cerrar el hilo.
 */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "subagent_threads",
    indices = [
        Index(value = ["parentSessionId"]),
        Index(value = ["status"]),
    ],
)
data class SubagentThreadEntity(
    @PrimaryKey val threadId: String,
    /** Crear la sesión principal del hilo. id(La ruta B utiliza marcadores de posición cuando no hay una sesión principal "subagent_run"). */
    val parentSessionId: String,
    /** subsesión id(Relleno después de la primera ejecución,En la solución B es igual threadId). */
    val childSessionId: String? = null,
    /** Ruta del archivo JSONL de subsesión(Utilizado para la recuperación del currículum). */
    val childSessionPath: String,
    /** sub-asistente id(Úselo para encontrar la configuración del modelo al continuar la conexión.). */
    val assistantId: String,
    /** nombre para mostrar,Como por ejemplo "research-1". */
    val label: String? = null,
    /** read|write(Alineación Implementación Descripción Permiso Herencia). */
    val access: String = "read",
    /** open|closed. */
    val status: String = "open",
    /** Número de veces ejecutadas. */
    val runCount: Int = 0,
    /** Estado de la última ejecución: pending|resolved|failed|aborted. */
    val lastRunStatus: String? = null,
    /** Resumen de la última ejecución(UI para exhibición). */
    val lastSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
