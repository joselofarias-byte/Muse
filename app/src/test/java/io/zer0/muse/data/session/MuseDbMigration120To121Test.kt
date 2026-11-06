package io.zer0.muse.data.session

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.16: 120 → 121 Migración de mensajes de voz.
 *
 * Llame directamente al entorno de producción. [MuseDb.MIGRATION_120_121](no una copia del DDL), afirme:
 *  - `voiceUri` / `voiceDurationMs` Se agregan ambas columnas, tipo y capacidad de nulidad./El valor predeterminado es
 *    [MessageEntity] La declaración es consistente (de lo contrario, la verificación de la sala de máquinas real arrojará "Migration didn't properly handle");
 *  - No se ha reescrito ni un solo campo en la línea anterior: texto/La columna de la imagen se deja como está y la nueva columna es NULL/0,
 *    La semántica de lectura es equivalente a"mensajes no de voz", no es necesario rellenar;
 *  - Las migraciones son repetibles (idempotentes) y no fallarán debido a columnas duplicadas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration120To121Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** La base de datos se construye de acuerdo con la columna clave de mensajes de v120 (esta vez excluyendo las dos columnas recién agregadas). */
    private fun openAt120(name: String): SupportSQLiteDatabase {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(120) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `messages` (" +
                                    "`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', " +
                                    "`role` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL DEFAULT '', " +
                                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                                    "`imageUrlsJson` TEXT NOT NULL DEFAULT '[]', " +
                                    "`cachedTokens` INTEGER DEFAULT NULL, " +
                                    "`cacheWriteTokens` INTEGER DEFAULT NULL, " +
                                    "PRIMARY KEY(`id`))",
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
            "INSERT INTO messages (id, sessionId, role, content, createdAt, imageUrlsJson, cachedTokens) " +
                "VALUES ('m1', 's1', 'ASSISTANT', 'Antigua respuesta', 1000, '[\"file://pic.jpg\"]', 700)",
        )
    }

    @Test
    fun migrationAddsVoiceColumnsWithoutTouchingLegacyRows() {
        val db = openAt120("migration_120_121.db")
        try {
            seed(db)

            MuseDb.MIGRATION_120_121.migrate(db)

            val columns = columnInfo(db, "messages")
            assertEquals(
                "messages.voiceUri Debe ser anulable y el valor predeterminado es NULL (ninguna de las filas antiguas son mensajes de voz)",
                "TEXT|notnull=0|default=NULL",
                columns["voiceUri"],
            )
            assertEquals(
                "messages.voiceDurationMs Debe ser coherente con la entidad:NOT NULL DEFAULT 0",
                "INTEGER|notnull=1|default=0",
                columns["voiceDurationMs"],
            )

            db.query("SELECT content, imageUrlsJson, cachedTokens, voiceUri, voiceDurationMs FROM messages").use { cursor ->
                assertTrue("Las viejas noticias aún deben estar ahí.", cursor.moveToFirst())
                assertEquals("El texto no debe reescribirse", "Antigua respuesta", cursor.getString(0))
                assertEquals("Las columnas de imagen no deben anularse", "[\"file://pic.jpg\"]", cursor.getString(1))
                assertEquals("La antigua columna del token no debe sobrescribirse", 700, cursor.getInt(2))
                assertNull("VoiceUri de la fila anterior debe ser NULL (no es un mensaje de voz, no hay relleno)", cursor.getString(3))
                assertEquals("La antigua línea voiceDurationMs debería ser 0", 0L, cursor.getLong(4))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migrationIsIdempotent() {
        val db = openAt120("migration_120_121_idempotent.db")
        try {
            seed(db)

            MuseDb.MIGRATION_120_121.migrate(db)
            // Ejecútalo de nuevo:ensureMessageColumns El juicio existe según PRAGMA, la ejecución repetida no debería arrojar duplicate column
            MuseDb.MIGRATION_120_121.migrate(db)

            val columns = columnInfo(db, "messages")
            assertEquals("TEXT|notnull=0|default=NULL", columns["voiceUri"])
            assertEquals("INTEGER|notnull=1|default=0", columns["voiceDurationMs"])
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Los datos no deben perderse durante migraciones repetidas", 1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
