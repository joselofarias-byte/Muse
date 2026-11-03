package io.zer0.muse.rag

import io.zer0.ai.core.RagCitation

/**
 * v1.133: Inyectar resultados — Contiene texto + lista de citas.
 *
 * [RagCitation] Se mudó a [io.zer0.ai.core] paquete,hacer [io.zer0.ai.core.UIMessage]
 * Se puede cotizar directamente(Evite la dependencia inversa del módulo ai del módulo de la aplicación).
 */
data class RagInjection(
    /** Texto inyectado en el mensaje del sistema.(Contiene [N] comillas). */
    val text: String,
    /** Lista de referencias(con texto en [N] corresponder). */
    val citations: List<RagCitation>,
    /** tiempo de recuperación(ms,Solo para monitoreo de desempeño). */
    val elapsedMs: Long,
)
