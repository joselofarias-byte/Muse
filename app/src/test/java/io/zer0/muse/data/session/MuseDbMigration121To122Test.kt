package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * versión 121 → 122: Limpia los datos sobrantes en teléfonos pequeños y antiguos.
 *
 * Llame directamente al entorno de producción. [MuseDb.MIGRATION_121_122](No es una copia de SQL), afirma:
 *  - Momentos/Se eliminaron cuatro tablas del diario y se podían eliminar (existían en la base de datos anterior);
 *  - `sessions.isMiniPhone = 1` La conversación y los mensajes que aparecen debajo de ella se eliminan;
 *  - Hay muchas conversaciones y mensajes normales y los campos no se han modificado (esta migración solo borra pequeños datos del teléfono celular);
 *  - `isMiniPhone` columna misma**Reservar**(El antiguo SQLite no admite DROP COLUMN, la columna todavía está ahí pero no hay escritor);
 *  - La migración se puede ejecutar repetidamente (idempotente) y no se informará ningún error porque la tabla se ha eliminado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration121To122Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Construya la biblioteca de acuerdo con la estructura clave de v121:sessions / messages + Se eliminarán cuatro tablas. */
    private fun openAt121(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(121) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `sessions` (" +
                                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`lastMessagePreview` TEXT NOT NULL DEFAULT '', " +
                                    "`assistantId` TEXT NOT NULL DEFAULT 'default', " +
                                    "`archived` INTEGER NOT NULL DEFAULT 0, " +
                                    "`isAgentSession` INTEGER NOT NULL DEFAULT 0, " +
                                    "`isMiniPhone` INTEGER NOT NULL DEFAULT 0, " +
                                    "`deletedAt` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`deletedAt` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
                            )
                            db.execSQL("CREATE TABLE `ai_moments` (`id` TEXT NOT NULL PRIMARY KEY, `content` TEXT NOT NULL)")
                            db.execSQL(
                                "CREATE TABLE `ai_moment_comments` (" +
                                    "`id` TEXT NOT NULL PRIMARY KEY, `momentId` TEXT NOT NULL)",
                            )
                            db.execSQL(
                                "CREATE TABLE `ai_moment_likes` (" +
                                    "`momentId` TEXT NOT NULL, `likerType` TEXT NOT NULL, `likerId` TEXT NOT NULL, " +
                                    "PRIMARY KEY(`momentId`, `likerType`, `likerId`))",
                            )
                            db.execSQL("CREATE TABLE `ai_diaries` (`date` TEXT NOT NULL PRIMARY KEY, `content` TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        return helper.writableDatabase
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        return names
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        return names
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO sessions (id, title, assistantId, isMiniPhone) " +
                "VALUES ('s-main', 'sesión principal', 'assistant-a', 0)",
        )
        db.execSQL(
            "INSERT INTO sessions (id, title, assistantId, isMiniPhone) " +
                "VALUES ('s-mini', 'Pequeño mensaje privado de teléfono celular.', 'assistant-a', 1)",
        )
        db.execSQL("INSERT INTO messages (id, sessionId, role, content) VALUES ('m-main', 's-main', 'USER', 'Mensaje de sesión principal')")
        db.execSQL("INSERT INTO messages (id, sessionId, role, content) VALUES ('m-mini', 's-mini', 'USER', 'mensaje privado')")
        db.execSQL("INSERT INTO ai_moments (id, content) VALUES ('mo1', 'viejas noticias')")
        db.execSQL("INSERT INTO ai_moment_comments (id, momentId) VALUES ('c1', 'mo1')")
        db.execSQL(
            "INSERT INTO ai_moment_likes (momentId, likerType, likerId) VALUES ('mo1', 'user', 'user')",
        )
        db.execSQL("INSERT INTO ai_diaries (date, content) VALUES ('2026-09-20', 'viejo diario')")
    }

    @Test
    fun migrationDropsLegacyTablesAndMiniPhoneRowsOnly() {
        val db = openAt121("migration_121_122.db")
        try {
            seed(db)
            // Premisa:Estas tablas en la base de datos antigua./La fila existe; de ​​lo contrario, la aserción "eliminada" posterior es igual a una aserción vacía.
            assertTrue("Condiciones previas:Debería haber cuatro círculos de amigos en la antigua biblioteca./hoja de diario", tableNames(db).containsAll(LEGACY_TABLES))

            MuseDb.MIGRATION_121_122.migrate(db)

            val tables = tableNames(db)
            LEGACY_TABLES.forEach { table ->
                assertFalse("$table debería estar en 121→122 Eliminado durante la migración", table in tables)
            }
            assertTrue("sessions La mesa aún debe estar ahí.", "sessions" in tables)
            assertTrue("messages La mesa aún debe estar ahí.", "messages" in tables)

            db.query("SELECT id FROM sessions ORDER BY id").use { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.moveToNext()) ids += cursor.getString(0)
                assertEquals("simplemente eliminar isMiniPhone = 1 conversación", listOf("s-main"), ids)
            }
            db.query("SELECT id, content FROM messages ORDER BY id").use { cursor ->
                assertTrue("Se deben conservar los mensajes de conversaciones normales.", cursor.moveToFirst())
                assertEquals("m-main", cursor.getString(0))
                assertEquals("El texto normal del mensaje no debe reescribirse", "Mensaje de sesión principal", cursor.getString(1))
                assertFalse("Los mensajes de conversaciones en teléfonos celulares pequeños deben eliminarse juntos", cursor.moveToNext())
            }
            assertTrue(
                "isMiniPhone Se debe conservar la columna(El antiguo SQLite no admite DROP COLUMN, solo deja de escribir pero no elimina columnas.)",
                "isMiniPhone" in columnNames(db, "sessions"),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val db = openAt121("migration_121_122_idempotent.db")
        try {
            seed(db)

            MuseDb.MIGRATION_121_122.migrate(db)
            // correr de nuevo:La mesa ya no existeDROP TABLE IF EXISTS No se debe informar ningún error con la eliminación condicional.
            MuseDb.MIGRATION_121_122.migrate(db)

            db.query("SELECT COUNT(*) FROM sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Las migraciones repetidas no deberían eliminar sesiones ordinarias por error", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Las migraciones repetidas no deberían eliminar mensajes normales por error", 1, cursor.getInt(0))
            }
            assertFalse("ai_diaries todavía no debería existir", tableNames(db).contains("ai_diaries"))
        } finally {
            db.close()
        }
    }

    private companion object {
        /** Cuatro mesas dejadas por un viejo teléfono celular (los nombres de las mesas están tomados del archivo de cada Entidad) @Entity(tableName=...)). */
        val LEGACY_TABLES = setOf("ai_moments", "ai_moment_comments", "ai_moment_likes", "ai_diaries")
    }
}
