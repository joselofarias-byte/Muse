package io.zer0.muse.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P3-1 / P2-2: v111 → v112 Verificación conductual de la migración.
 *
 * Esta migración es para `knowledge_bases` Se agregaron 5 nuevas columnas de cobertura por KB. Utilice nativo aquí SQLite
 * Ejecutar directamente con [MuseDb.MIGRATION_111_112] Exactamente el mismo DDL, afirmando:
 *  - 5 Todas las columnas existen
 *  - Las filas existentes no se pierden y el campo de cobertura está vacío (= Hereda la configuración global, el comportamiento permanece sin cambios)
 *
 * No depende de las pruebas de sala (no incluidas en las dependencias) para evitar expandir la superficie de construcción para una prueba.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration111To112Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun migration111To112_addsOverrideColumnsAndKeepsLegacyRows() {
        val dbName = "migration_111_112_test.db"
        context.deleteDatabase(dbName)

        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        try {
            // 1. Restaurar la estructura de bases de conocimiento v111 e insertar filas de stock
            db.execSQL(
                """
                CREATE TABLE knowledge_bases (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    doc_count INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO knowledge_bases (id, name, description, created_at, updated_at) " +
                    "VALUES ('kb-legacy', 'Legacy KB', 'old', 1, 2)",
            )
            db.version = 111

            // 2. Ejecutar exactamente igual que MIGRATION_111_112 DDL
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_size_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN chunk_overlap_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN top_k_override INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE knowledge_bases ADD COLUMN threshold_override REAL DEFAULT NULL")
            db.execSQL(
                "ALTER TABLE knowledge_bases ADD COLUMN embedding_model_override TEXT NOT NULL DEFAULT ''",
            )
            db.version = 112

            // 3. Afirmar:5 Todas las columnas existen
            val columns = mutableMapOf<String, String>()
            val infoCursor: android.database.Cursor = db.rawQuery("PRAGMA table_info(knowledge_bases)", null)
            infoCursor.use { c ->
                while (c.moveToNext()) {
                    columns[c.getString(1)] = c.getString(2)
                }
            }
            val expected: Map<String, String> = mapOf(
                "chunk_size_override" to "INTEGER",
                "chunk_overlap_override" to "INTEGER",
                "top_k_override" to "INTEGER",
                "threshold_override" to "REAL",
                "embedding_model_override" to "TEXT",
            )
            for ((name, type) in expected) {
                assertEquals("columna $name debe existir y ser de tipo $type", type, columns[name])
            }

            // 4. Aserción: las filas existentes se conservan y los campos sobrescritos están vacíos (heredados del global)
            val rowCursor: android.database.Cursor = db.rawQuery(
                "SELECT name, chunk_size_override, top_k_override, embedding_model_override " +
                    "FROM knowledge_bases WHERE id = 'kb-legacy'",
                null,
            )
            rowCursor.use { c ->
                assertTrue("Se deben conservar las filas de KB de stock", c.moveToFirst())
                assertEquals("Los nombres de KB en stock no deben perderse", "Legacy KB", c.getString(0))
                assertTrue("chunk_size_override Debe ser NULL (heredar global)", c.isNull(1))
                assertTrue("top_k_override Debe ser NULL (heredar global)", c.isNull(2))
                assertEquals("embedding_model_override Debería ser una cadena vacía", "", c.getString(3))
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName)
        }
    }
}
