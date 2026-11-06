package io.zer0.muse.data.session

/**
 * R-DB-05: Bandera de modo FTS de mensaje en proceso.
 *
 * por [io.zer0.muse.data.session.MuseDb] Migrar/Crear tabla/onOpen Posteriormente basado en
 * `sqlite_master.sql` Escribir resultados reales de creación de tablas;SessionRepository Elija en consecuencia
 * FTS5 Índice de texto original o ruta alternativa de ngram FTS4.
 */
object MessageFtsRuntime {
    @Volatile
    var useFts5: Boolean = false
}
