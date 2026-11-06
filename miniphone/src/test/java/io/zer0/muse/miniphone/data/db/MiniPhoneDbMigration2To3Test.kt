package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2 → 3 Migrar:Las conversaciones y mensajes del viejo Curry se conservan línea por línea.,Las columnas fijas tienen el valor predeterminado de SQL 0 a partir de esta versión.
 *
 * Primero use el 2.json exportado para crear una biblioteca v2 que sea la misma que la máquina real.(Crear declaraciones de tabla, índices,room_master_table Los hashes de identidad se escriben según el esquema.),
 * Dos conversaciones están metidas en el interior.(Un elemento ha sido fijado en la parte superior.)con varios mensajes,Luego deja que Room lo abra con la cadena de migración.
 * Hay tres niveles de afirmaciones.:Room Se puede abrir y pasar la verificación del esquema.(Muestra que la tabla reconstruida y la entidad coinciden.),
 * Las filas y columnas se dejan intactas y la columna superior se restablece a 0 en nuevas escrituras sin ella.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration2To3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsThreadsAndMessagesAndGivesPinnedItsDefault() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion2Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                val threads = db.threadDao().observeThreads().first()
                assertEquals("muchas conversaciones", listOf(PINNED_THREAD_ID, PLAIN_THREAD_ID), threads.map { it.id })
                assertEquals("La etiqueta pin permanece con la línea.", true, threads.first().pinned)
                assertEquals("Las lecturas no leídas no se pueden descartar.", 3, threads.first().unreadCount)
                assertEquals("No pierdas la copia preliminar", "Oración 2", threads.first().lastMessagePreview)
                assertEquals("La fila que no se ha fijado en la parte superior todavía no está fijada en la parte superior.", false, threads.last().pinned)

                assertEquals(
                    "Los mensajes se retienen por sesión.",
                    listOf("legacy-1", "legacy-2"),
                    db.messageDao().observeMessages(PINNED_THREAD_ID).first().map { it.id },
                )
                assertEquals("Los mensajes de la otra conversación siguen ahí.", 1, db.messageDao().countInThread(PLAIN_THREAD_ID))
                assertEquals("La versión de la biblioteca continúa avanzando.,Ejecutar a la versión actual", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            }
        } finally {
            db.close()
        }
        assertEquals("Columna superior con valor predeterminado 0", "0", columnDefault(file, table = "mp_thread", column = "pinned"))
    }

    @Test
    fun writesWithoutPinnedLandOnNotPinnedAndIndexSurvivesRebuild() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion2Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                // Escribir sin fijar:Las rutas de código antiguas y el SQL manual están "no fijados" por este valor predeterminado.
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO mp_thread (id, characterId, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                        "VALUES ('fresh', 'c-fresh', 0, NULL, 7000, 0)",
                )
                assertEquals(false, db.threadDao().getThread("fresh")?.pinned)
                // El interruptor superior se puede reescribir
                db.threadDao().setPinned("fresh", true)
                assertEquals(true, db.threadDao().getThread("fresh")?.pinned)
                // Después de reconstruir la mesa,La consulta basada en el índice aún se puede ejecutar.
                assertEquals(PINNED_THREAD_ID, db.threadDao().findThreadByCharacter(CHARACTER_ID)?.id)
            }
        } finally {
            db.close()
        }
        assertEquals("El índice sigue ahí después de la reconstrucción.", true, indexNames(file).contains("index_mp_thread_lastMessageAt"))
    }

    /** Cree una biblioteca v2 como 2.json,Y escribe dos conversaciones y tres mensajes. */
    private fun createVersion2Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                legacy.execSQL(entity.getString("createSql").replace(TABLE_PLACEHOLDER, table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (indexAt in 0 until indices.length()) {
                    legacy.execSQL(indices.getJSONObject(indexAt).getString("createSql").replace(TABLE_PLACEHOLDER, table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) {
                legacy.execSQL(setup.getString(index))
            }
            // Una conversación fijada + una conversación no fijada;El mensaje que no fue fijado en la parte superior será más tarde.,El orden no se puede cambiar después de la migración.
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                    "VALUES ('$PINNED_THREAD_ID', '$CHARACTER_ID', 1, 3, 'Oración 2', 2000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                    "VALUES ('$PLAIN_THREAD_ID', 'c-other', 0, 0, 'otra palabra', 9000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-1', '$PINNED_THREAD_ID', '$CHARACTER_ID', 'character', 'text', 'Oración 1', 1000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-2', '$PINNED_THREAD_ID', '$CHARACTER_ID', 'user', 'text', 'Oración 2', 2000, 0)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('other-1', '$PLAIN_THREAD_ID', 'c-other', 'character', 'text', 'otra palabra', 9000, 0)",
            )
            legacy.version = 2
        } finally {
            legacy.close()
        }
    }

    private fun openMigrated(name: String): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
        .addMigrations(MINI_PHONE_MIGRATION_1_2, MINI_PHONE_MIGRATION_2_3, MINI_PHONE_MIGRATION_3_4, MINI_PHONE_MIGRATION_4_5)
        .allowMainThreadQueries()
        .build()

    private fun columnDefault(file: File, table: String, column: String): String? =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                        found = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                    }
                }
                found
            }
        }

    private fun indexNames(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'mp_thread'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v2.db"
        const val PINNED_THREAD_ID: String = "t-pinned"
        const val PLAIN_THREAD_ID: String = "t-plain"
        const val CHARACTER_ID: String = "c-legacy"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/2.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
