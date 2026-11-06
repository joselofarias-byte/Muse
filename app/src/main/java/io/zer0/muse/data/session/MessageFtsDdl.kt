package io.zer0.muse.data.session

/**
 * R-DB-05: messages_fts Cree una tabla SQL y un disparador de contenido externo FTS5.
 *
 * - FTS5 Disponible:external content señalar a messages,Indexar el texto original y utilizarlo. SQLite `snippet()`
 *   Generar clips destacados;Sincronizar altas, eliminaciones y modificaciones a la tabla de mensajes mediante disparadores,Evite la deriva de sincronización manual.
 * - FTS5 No disponible:Recurrir a la solución FTS4 + content_ngram existente,continuar por SessionRepository
 *   Eliminar manualmente + insertar mantenimiento de ngram.
 */
internal object MessageFtsDdl {

    private val FTS5_SQL = """
        CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING fts5(
            content,
            content='messages',
            content_rowid='rowid',
            tokenize='unicode61'
        )
    """.trimIndent()

    private val FTS4_SQL = """
        CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(
            `message_id` TEXT,
            `content_ngram` TEXT
        )
    """.trimIndent()

    /** FTS5 external content gatillo(Sincronizar altas, eliminaciones y modificaciones en la tabla de mensajes). */
    val fts5TriggerSqls: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ai
        AFTER INSERT ON messages BEGIN
            INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_ad
        AFTER DELETE ON messages BEGIN
            INSERT INTO messages_fts(messages_fts, rowid, content)
            VALUES ('delete', old.rowid, old.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS messages_fts_au
        AFTER UPDATE OF content ON messages BEGIN
            INSERT INTO messages_fts(messages_fts, rowid, content)
            VALUES ('delete', old.rowid, old.content);
            INSERT INTO messages_fts(rowid, content) VALUES (new.rowid, new.content);
        END
        """.trimIndent(),
    )

    fun createSql(useFts5: Boolean): String = if (useFts5) FTS5_SQL else FTS4_SQL
}
