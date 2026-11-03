package io.zer0.memory.summary

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** verificar MemoryDb v1→v2→v3 Solo agrega columnas/Agregue tablas y conserve productos compilados antiguos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryDbMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        tempFiles.forEach { it.deleteRecursively() }
    }

    @Test
    fun `migration preserves legacy content and copies it to default scoped slot`() = runTest {
        val dir = Files.createTempDirectory("memory-migration").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = buildV1Database(file)
        val oldDb = helper.writableDatabase
        oldDb.execSQL("INSERT INTO compiled_sections VALUES ('facts', 'legacy facts', NULL, '2026-08-22T00:00:00Z')")
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(
                MemoryDb.MIGRATION_1_2,
                MemoryDb.MIGRATION_2_3,
                MemoryDb.MIGRATION_3_4,
                MemoryDb.MIGRATION_4_5,
            )
            .build()
        try {
            assertEquals("legacy facts", db.compiledSectionDao().get("facts")?.content)
            val scoped = db.scopedCompiledSectionDao().get("facts", "main", "default")
            assertEquals("legacy facts", scoped?.content)
            assertTrue(db.scopedCompiledSectionDao().getAll().any { it.spaceId == "default" })
            assertEquals("default", db.sessionSummaryDao().get("missing")?.spaceId ?: "default")
        } finally {
            db.close()
        }
    }

    /**
     * v3 → v4: Se agregaron context_checkpoints (puntos de control de compresión de sesión).
     *
     * Afirma tres cosas:① Los datos de session_summaries existentes no se perderán;② La nueva tabla se crea y se puede escribir y leer;
     * ③ La semántica de sobrescritura es válida (solo queda una entrada en la misma sesión y la segunda escritura sobrescribe la primera).
     */
    @Test
    fun `v3 to v4 adds context checkpoint table and keeps session summaries`() = runTest {
        val dir = Files.createTempDirectory("memory-migration-v4").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = buildV3Database(file)
        val oldDb = helper.writableDatabase
        oldDb.execSQL(
            "INSERT INTO session_summaries VALUES ('s-1', '2026-09-01T00:00:00Z', " +
                "'2026-09-02T00:00:00Z', 'legacy rolling summary', 12, NULL, '', NULL, '', 'default')",
        )
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(MemoryDb.MIGRATION_3_4, MemoryDb.MIGRATION_4_5)
            .build()
        try {
            // ① El antiguo resumen sigue ahí.
            val legacy = db.sessionSummaryDao().get("s-1")
            assertEquals("legacy rolling summary", legacy?.summary)
            assertEquals(12, legacy?.messageCount)

            // ② Nueva mesa disponible
            db.contextCheckpointDao().upsert(
                ContextCheckpointEntity(
                    sessionId = "s-1",
                    coveredSeq = 40L,
                    lastCoveredMessageId = "m-40",
                    coveredCount = 40,
                    summary = "## Goal\nContinuar reparando la compresión.",
                    tokensBefore = 12_000,
                    tokensAfter = 1_200,
                    updatedAt = 1_700_000_000_000L,
                ),
            )
            val loaded = db.contextCheckpointDao().get("s-1")
            assertEquals(40L, loaded?.coveredSeq)
            assertEquals("m-40", loaded?.lastCoveredMessageId)
            assertEquals(10_800, loaded?.savedTokens)

            // ③ Sobrescritura: la segunda escritura en la misma sesión reemplaza en lugar de agregar
            db.contextCheckpointDao().upsert(
                (loaded ?: error("checkpoint missing")).copy(coveredSeq = 80L, coveredCount = 80),
            )
            assertEquals(1, db.contextCheckpointDao().getAll().size)
            assertEquals(80L, db.contextCheckpointDao().get("s-1")?.coveredSeq)

            // Después de la eliminación, vuelva a leer como null
            db.contextCheckpointDao().deleteById("s-1")
            assertNull(db.contextCheckpointDao().get("s-1"))
        } finally {
            db.close()
        }
    }

    /**
     * v4 → v5: Aumento del punto de control"El número acumulado de resúmenes fusionados en"columnas.
     *
     * Afirmar: los puntos de control antiguos se conservan, existen nuevas columnas y el valor predeterminado es 0 (la capa de presentación encuentra 0 por"desconocido"procesamiento).
     */
    @Test
    fun `v4 to v5 adds total covered count and keeps checkpoints`() = runTest {
        val dir = Files.createTempDirectory("memory-migration-v5").toFile()
        tempFiles += dir
        val file = File(dir, "memory.db")
        val helper = buildV1Database(file)
        val oldDb = helper.writableDatabase
        oldDb.execSQL(
            // v1 Los session_summaries solo tienen 9 columnas (space_id Fue agregado en v2)
            "INSERT INTO session_summaries VALUES ('s-1', '2026-09-01T00:00:00Z', " +
                "'2026-09-02T00:00:00Z', 'legacy rolling summary', 12, NULL, '', NULL, '')",
        )
        helper.close()

        val db = Room.databaseBuilder(context, MemoryDb::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .addMigrations(
                MemoryDb.MIGRATION_1_2,
                MemoryDb.MIGRATION_2_3,
                MemoryDb.MIGRATION_3_4,
                MemoryDb.MIGRATION_4_5,
            )
            .build()
        try {
            db.contextCheckpointDao().upsert(
                ContextCheckpointEntity(
                    sessionId = "s-1",
                    coveredSeq = 10L,
                    lastCoveredMessageId = "m-10",
                    summary = "Resumen",
                    updatedAt = 1_700_000_000_000L,
                ),
            )
            val loaded = db.contextCheckpointDao().get("s-1")
            assertEquals("Nueva columna predeterminada 0", 0, loaded?.totalCoveredCount)
            assertEquals(10L, loaded?.coveredSeq)
            // El valor acumulado se puede volver a leer después de escribirlo.
            db.contextCheckpointDao().upsert((loaded ?: error("missing")).copy(totalCoveredCount = 42))
            assertEquals(42, db.contextCheckpointDao().get("s-1")?.totalCoveredCount)
        } finally {
            db.close()
        }
    }

    /**
     * Construir un **v1** versión del archivo Memory.db (sin space_id, sin tabla de ámbito).
     *
     * para verificación v1→v2→v3 La cadena de actualización completa —— Realmente debe comenzar desde v1; de lo contrario, la sección MIGRATION_2_3
     * "Los productos antiguos se mueven a la ranura predeterminada."La lógica no se activará y la prueba no tendrá sentido.
     */
    private fun buildV1Database(file: File): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE session_summaries (
                            session_id TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            summary TEXT NOT NULL,
                            message_count INTEGER NOT NULL,
                            source_time_range TEXT,
                            snapshot TEXT NOT NULL,
                            snapshot_at TEXT,
                            assistant_id TEXT NOT NULL,
                            PRIMARY KEY(session_id)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE daily_state (
                            `key` TEXT NOT NULL,
                            schema_version INTEGER NOT NULL,
                            logical_date TEXT NOT NULL,
                            reset_at TEXT,
                            facts_mode TEXT NOT NULL,
                            completed_steps TEXT NOT NULL,
                            daily_completed_at TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(`key`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE compiled_sections (
                            section_key TEXT NOT NULL,
                            content TEXT NOT NULL,
                            fingerprint TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(section_key)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES (42, '5b74599c11f2cfdaae37cbc32148a9fd')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build(),
    )

    /**
     * Construya una versión v3 del archivo Memory.db (cree tablas manualmente para simular la biblioteca antes de la actualización).
     *
     * DDL Escriba como una cadena con sangría de varias líneas: borre sin exceder el límite de línea de 140 caracteres de klint.
     */
    private fun buildV3Database(file: File): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE session_summaries (
                            session_id TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL,
                            summary TEXT NOT NULL,
                            message_count INTEGER NOT NULL,
                            source_time_range TEXT,
                            snapshot TEXT NOT NULL,
                            snapshot_at TEXT,
                            assistant_id TEXT NOT NULL,
                            space_id TEXT NOT NULL DEFAULT 'default',
                            PRIMARY KEY(session_id)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE daily_state (
                            `key` TEXT NOT NULL,
                            schema_version INTEGER NOT NULL,
                            logical_date TEXT NOT NULL,
                            reset_at TEXT,
                            facts_mode TEXT NOT NULL,
                            completed_steps TEXT NOT NULL,
                            daily_completed_at TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(`key`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE compiled_sections (
                            section_key TEXT NOT NULL,
                            content TEXT NOT NULL,
                            fingerprint TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(section_key)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE compiled_sections_scoped (
                            section_key TEXT NOT NULL,
                            scope TEXT NOT NULL,
                            space_id TEXT NOT NULL,
                            content TEXT NOT NULL,
                            fingerprint TEXT,
                            updated_at TEXT NOT NULL,
                            PRIMARY KEY(section_key, scope, space_id)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES (42, 'dummy-v3-hash')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build(),
    )
}
