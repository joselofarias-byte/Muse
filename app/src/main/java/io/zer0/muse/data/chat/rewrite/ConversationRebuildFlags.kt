package io.zer0.muse.data.chat.rewrite

/** Cambie entre enlaces de diálogo antiguos y nuevos. compromiso actual/evento/parts La ruta principal está habilitada de forma predeterminada.projection/UI El interruptor de retroceso aún se conserva de forma independiente. */
data class ConversationRebuildFlags(
    /** Cambio oficial: la nueva cadena de eventos se convierte en la ruta de registro de hechos predeterminada. */
    val shadowEventsEnabled: Boolean = true,
    /** Cambio formal: realice la transacción idempotente MessageCommit cuando se complete la ronda. */
    val useCommitSeq: Boolean = true,
    /** Cambio oficial: las nuevas confirmaciones también escriben message_parts estructurados. */
    val useMessageParts: Boolean = true,
    /** La nueva ruta de lectura de proyección está habilitada de forma predeterminada; Los mensajes antiguos sin partes se derivan automáticamente del legado. */
    val useMessageProjection: Boolean = true,
    /** Cambio oficial: nuevo ConversationService/MessageCommit como ruta de confirmación principal. */
    val useNewConversationService: Boolean = true,
    /** Se ha accedido directamente al diseño de la constelación del centro de memoria en la rama actual. */
    val useMemoryConstellationUi: Boolean = true,
    /** Las páginas preliminares de la sala todavía están conectadas una por una y no se abrirán globalmente por el momento. */
    val useRenovatedPageUi: Boolean = false,
)

/** Cambio de escala de grises en proceso; no persistente, no cambia el comportamiento predeterminado de los usuarios normales. */
object ConversationRebuildFlagStore {
    @Volatile
    var current: ConversationRebuildFlags = ConversationRebuildFlags()
}
