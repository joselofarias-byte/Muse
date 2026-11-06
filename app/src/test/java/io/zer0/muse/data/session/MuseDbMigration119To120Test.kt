package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.15: 119 → 120 lectura de caché/Escribir migración de columnas.
 *
 * Llame directamente al entorno de producción. [MuseDb.MIGRATION_119_120](no una copia del DDL), afirme:
 *  - Se han agregado ambas columnas y el tipo y el valor predeterminado son consistentes con la declaración de entidad (de lo contrario, la verificación real de la sala de máquinas arrojará
 *    "Migration didn't properly handle");
 *  - No se ha reescrito ni un solo campo de la fila anterior:`cachedTokens` / `cached_tokens` Déjelo como está (sin rellenar ni volver a calcular),
 *    La nueva columna es NULL/0 en la fila anterior;
 *  - Después de seguir los pasos para revertir el documento (eliminación de dos columnas + revertir la versión a 119), los datos antiguos aún están completos y legibles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration119To120Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun openAt119(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(119) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // v119 Las columnas clave de mensajes/uso_registros (excluyendo esta nueva columna)
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`promptTokens` INTEGER DEFAULT NULL, " +
                                    "`completionTokens` INTEGER DEFAULT NULL, " +
                                    "`reasoningTokens` INTEGER DEFAULT NULL, " +
                                    "`cachedTokens` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `usage_records` (" +
                                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                                    "`source` TEXT NOT NULL DEFAULT 'chat', " +
                                    "`model_id` TEXT NOT NULL DEFAULT '', " +
                                    "`provider_id` TEXT NOT NULL DEFAULT '', " +
                                    "`prompt_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`completion_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`reasoning_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`cached_tokens` INTEGER NOT NULL DEFAULT 0, " +
                                    "`first_token_ms` INTEGER NOT NULL DEFAULT 0, " +
                                    "`duration_ms` INTEGER NOT NULL DEFAULT 0)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        return helper.writableDatabase
    }

    private fun columnInfo(db: SupportSQLiteDatabase, table: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                val dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                out[name] = "$type|notnull=$notNull|default=$dflt"
            }
        }
        return out
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO messages (id, sessionId, role, content, createdAt, promptTokens, " +
                "completionTokens, reasoningTokens, cachedTokens) " +
                "VALUES ('m1', 's1', 'ASSISTANT', 'Antigua respuesta', 1000, 300, 50, 0, 700)",
        )
        db.execSQL(
            "INSERT INTO usage_records (created_at, source, model_id, provider_id, prompt_tokens, " +
                "completion_tokens, reasoning_tokens, cached_tokens) " +
                "VALUES (1000, 'chat', 'claude-sonnet-4', 'anthropic', 300, 50, 0, 700)",
        )
    }

    @Test
    fun migrationAddsCacheWriteColumnsWithoutTouchingLegacyValues() {
        val db = openAt119("migration_119_120.db")
        try {
            seed(db)

            // Realizar migración de producción
            MuseDb.MIGRATION_119_120.migrate(db)

            val messageColumns = columnInfo(db, "messages")
            assertTrue("messages deberia estar compuesto cacheWriteTokens", messageColumns.containsKey("cacheWriteTokens"))
            assertTrue(
                "messages.cacheWriteTokens Debe ser anulable y el valor predeterminado es NULL (las filas antiguas no tienen registros escritos)",
                messageColumns.getValue("cacheWriteTokens").contains("notnull=0"),
            )

            val usageColumns = columnInfo(db, "usage_records")
            assertTrue("usage_records deberia estar compuesto cache_write_tokens", usageColumns.containsKey("cache_write_tokens"))
            assertEquals(
                "usage_records.cache_write_tokens Debe ser coherente con la entidad:NOT NULL DEFAULT 0",
                "INTEGER|notnull=1|default=0",
                usageColumns.getValue("cache_write_tokens"),
            )

            // Los valores antiguos se conservan tal cual y las nuevas columnas se NULL / 0
            db.query("SELECT cachedTokens, cacheWriteTokens FROM messages WHERE id = 'm1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Los tokens en caché de la fila anterior no se reponen", 700, cursor.getInt(0))
                assertTrue("La cantidad de escritura de la fila anterior debe ser NULL (desconocida) y no se puede compilar 0", cursor.isNull(1))
            }
            db.query("SELECT cached_tokens, cache_write_tokens FROM usage_records").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Los cached_tokens de transacciones antiguas no se reponen", 700, cursor.getInt(0))
                assertEquals("La cantidad predeterminada de escritura en el canal antiguo 0", 0, cursor.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun rollbackToV119KeepsLegacyDataIntact() {
        val db = openAt119("migration_119_120_rollback.db")
        try {
            seed(db)
            MuseDb.MIGRATION_119_120.migrate(db)

            // Siga los pasos de reversión en las notas de migración: elimine dos columnas + regrese a la versión 119.
            // Vaya aquí para "Crear una nueva tabla" → copiar datos → Ruta portátil para "cambiar nombre":DROP COLUMN Requiere SQLite 3.35+,
            // Android No hay garantía (esta prueba informará directamente un error de sintaxis cuando se ejecute en el SQLite anterior de Robolectric).
            db.execSQL(
                "CREATE TABLE `messages_rollback` (" +
                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`promptTokens` INTEGER DEFAULT NULL, " +
                    "`completionTokens` INTEGER DEFAULT NULL, " +
                    "`reasoningTokens` INTEGER DEFAULT NULL, " +
                    "`cachedTokens` INTEGER DEFAULT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "INSERT INTO `messages_rollback` (id, sessionId, role, content, createdAt, " +
                    "promptTokens, completionTokens, reasoningTokens, cachedTokens) " +
                    "SELECT id, sessionId, role, content, createdAt, " +
                    "promptTokens, completionTokens, reasoningTokens, cachedTokens FROM messages",
            )
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("ALTER TABLE `messages_rollback` RENAME TO `messages`")

            db.execSQL(
                "CREATE TABLE `usage_records_rollback` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                    "`source` TEXT NOT NULL DEFAULT 'chat', " +
                    "`model_id` TEXT NOT NULL DEFAULT '', " +
                    "`provider_id` TEXT NOT NULL DEFAULT '', " +
                    "`prompt_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`completion_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`reasoning_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`cached_tokens` INTEGER NOT NULL DEFAULT 0, " +
                    "`first_token_ms` INTEGER NOT NULL DEFAULT 0, " +
                    "`duration_ms` INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "INSERT INTO `usage_records_rollback` (created_at, source, model_id, provider_id, " +
                    "prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens, " +
                    "first_token_ms, duration_ms) " +
                    "SELECT created_at, source, model_id, provider_id, " +
                    "prompt_tokens, completion_tokens, reasoning_tokens, cached_tokens, " +
                    "first_token_ms, duration_ms FROM usage_records",
            )
            db.execSQL("DROP TABLE `usage_records`")
            db.execSQL("ALTER TABLE `usage_records_rollback` RENAME TO `usage_records`")

            db.version = 119

            assertEquals("Después de revertir, la versión debería volver a 119", 119, db.version)
            assertTrue("Se deberían haber eliminado las columnas nuevas.", !columnInfo(db, "messages").containsKey("cacheWriteTokens"))
            assertTrue("Se deberían haber eliminado las columnas nuevas.", !columnInfo(db, "usage_records").containsKey("cache_write_tokens"))

            db.query("SELECT content, promptTokens, completionTokens, cachedTokens FROM messages").use { cursor ->
                assertTrue("Las viejas noticias aún deben estar ahí.", cursor.moveToFirst())
                assertEquals("Antigua respuesta", cursor.getString(0))
                assertEquals(300, cursor.getInt(1))
                assertEquals(50, cursor.getInt(2))
                assertEquals("Retroceder sin perder los valores fusionados", 700, cursor.getInt(3))
            }
            db.query("SELECT prompt_tokens, completion_tokens, cached_tokens FROM usage_records").use { cursor ->
                assertTrue("El antiguo agua corriente todavía debe estar ahí.", cursor.moveToFirst())
                assertEquals(300, cursor.getInt(0))
                assertEquals(50, cursor.getInt(1))
                assertEquals(700, cursor.getInt(2))
            }
        } finally {
            db.close()
        }
    }
}
