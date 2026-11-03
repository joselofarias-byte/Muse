package io.zer0.muse.data.knowledge

/**
 * R-DB-02: knowledge_chunks_fts Punto de registro del fijador autorreparable.
 *
 * MuseDb Inyectar después de construir la base de datos repairer;DAO Se llama después de una única operación de escritura que no captura dicha tabla.
 * Se deben tomar medidas de reparación. raw SQL(DROP mesa de sombra + CREATE VIRTUAL TABLE),No puedo volver a llamar FTS DAO,
 * Evite activar nuevamente la autocuración durante el proceso de reparación y provocar recurrencia.
 */
object KnowledgeChunkFtsSelfHealer {

    @Volatile
    private var repairer: (suspend () -> Unit)? = null

    /** por [io.zer0.muse.data.session.MuseDb.get] Regístrese después de crear la instancia. */
    fun install(repairer: suspend () -> Unit) {
        this.repairer = repairer
    }

    suspend fun repair() {
        val r = repairer ?: error("KnowledgeChunkFts repairer is not installed")
        r()
    }
}
