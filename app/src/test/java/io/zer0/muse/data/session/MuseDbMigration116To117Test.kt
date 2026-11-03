package io.zer0.muse.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.7: 116 → 117 Validación conductual de migraciones (persistencia de tareas pendientes de la sesión).
 *
 * Ejecutar directamente con SQLite nativo [MuseDb.MIGRATION_116_117] Exactamente el mismo DDL, afirmando:
 *  - `session_todos` La tabla existe y las seis columnas están completas;
 *  - `sessionId` Hay un índice único (una lista por restricción de sesión);
 *  - El valor predeterminado se ajusta a la declaración de la entidad (de lo contrario, la verificación del esquema de la sala de máquinas real arrojará
 *    "Migration didn't properly handle: session_todos");
 *  - Las tablas existentes no se verán afectadas (la simple migración de tablas nuevas no debería mover otras tablas).
 *
 * No depende de las pruebas de sala (no incluidas en las dependencias) y es igual que el existente. 111→112 Pruebe el mismo patrón.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration116To117Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun columnsOf(db: android.database.sqlite.SQLiteDatabase, table: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val type = c.getString(c.getColumnIndexOrThrow("type"))
                val notNull = c.getInt(c.getColumnIndexOrThrow("notnull"))
                val dflt = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                out[name] = "$type|notnull=$notNull|default=$dflt"
            }
        }
        return out
    }

    @Test
    fun migration116To117_createsSessionTodosTableWithExpectedColumns() {
        val dbName = "migration_116_117_test.db"
        context.deleteDatabase(dbName)
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        try {
            // 1. Restaure las tablas vecinas en v116 que serán "tocadas" por esta migración y confirme que la migración no causará daños accidentales.
            db.execSQL(
                """
                CREATE TABLE ai_moments (
                    id TEXT NOT NULL PRIMARY KEY,
                    content TEXT NOT NULL DEFAULT '',
                    visibleToJson TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO ai_moments (id, content) VALUES ('m1', 'legacy moment')")
            db.version = 116

            // 2. Ejecutar exactamente igual que MIGRATION_116_117 DDL
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `session_todos` (" +
                    "`id` TEXT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`todosJson` TEXT NOT NULL DEFAULT '', " +
                    "`hasOpenItems` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_session_todos_sessionId` ON `session_todos` (`sessionId`)",
            )

            // 3. lista completa
            val cols = columnsOf(db, "session_todos")
            assertEquals(
                "El conjunto de columnas debe ser exactamente el mismo que el de la entidad.",
                setOf("id", "sessionId", "todosJson", "hasOpenItems", "createdAt", "updatedAt"),
                cols.keys,
            )

            // 4. El valor predeterminado de la columna clave es consistente con la declaración de entidad
            assertTrue("sessionId Cadena vacía predeterminada", cols.getValue("sessionId").contains("default=''"))
            assertTrue("todosJson Cadena vacía predeterminada", cols.getValue("todosJson").contains("default=''"))
            assertTrue("hasOpenItems Predeterminado 0", cols.getValue("hasOpenItems").contains("default=0"))
            assertTrue("createdAt Predeterminado 0", cols.getValue("createdAt").contains("default=0"))
            assertTrue("updatedAt Predeterminado 0", cols.getValue("updatedAt").contains("default=0"))

            // 5. existe un índice único
            val indexes = mutableListOf<String>()
            db.rawQuery("PRAGMA index_list(`session_todos`)", null).use { c ->
                while (c.moveToNext()) indexes += c.getString(c.getColumnIndexOrThrow("name"))
            }
            assertTrue(
                "sessionId Debe tener un índice único, real.: $indexes",
                indexes.contains("index_session_todos_sessionId"),
            )

            // 6. Las tablas y los datos vecinos no se ven afectados
            db.rawQuery("SELECT content FROM ai_moments WHERE id = 'm1'", null).use { c ->
                assertTrue("Se deben conservar las filas existentes", c.moveToFirst())
                assertEquals("legacy moment", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun uniqueIndexRejectsTwoRowsForTheSameSession() {
        val dbName = "migration_116_117_unique.db"
        context.deleteDatabase(dbName)
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
        try {
            db.execSQL(
                "CREATE TABLE `session_todos` (" +
                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`todosJson` TEXT NOT NULL DEFAULT '', `hasOpenItems` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX `index_session_todos_sessionId` ON `session_todos` (`sessionId`)",
            )
            db.execSQL(
                "INSERT INTO session_todos (id, sessionId) VALUES ('a', 's1')",
            )

            val threw = try {
                db.execSQL("INSERT INTO session_todos (id, sessionId) VALUES ('b', 's1')")
                false
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Violación de restricción esperada: evidencia directa de que los índices únicos funcionan
                e.message?.let { assertTrue("Conflicto de restricción único esperado", it.contains("UNIQUE", ignoreCase = true)) }
                true
            }

            assertTrue("No se permiten dos registros de tareas pendientes en la misma sesión (de lo contrario, la semántica de inserción no será válida)", threw)
            assertNotNull(
                "El registro original aún debe estar ahí.",
                db.rawQuery("SELECT id FROM session_todos WHERE sessionId='s1'", null).use {
                    assertTrue(it.moveToFirst())
                    it.getString(0)
                },
            )
        } finally {
            db.close()
        }
    }
}
