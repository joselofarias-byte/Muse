package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 3 → 4 Migrar:Las conversaciones y mensajes antiguos se conservan línea por línea.,Hay más etiquetas de chat grupal y nombres de grupos en la línea de conversación.,La tabla de miembros existe y se puede escribir a partir de esta versión.
 *
 * Utilice el 3.json exportado para crear una biblioteca v3 con la misma forma que la máquina real.(Crear declaraciones de tabla, índices,room_master_table Los hashes de identidad se escriben según el esquema.),
 * Dos conversaciones están metidas en el interior.(Uno clavado en la cima)con tres mensajes,Luego deja que Room lo abra con la cadena de migración.
 *
 * Hay cuatro niveles de afirmaciones.:Room Se puede abrir y pasar la verificación del esquema.(Explique que las nuevas columnas coinciden con las nuevas tablas y entidades.),
 * Las filas y columnas antiguas se conservan tal como están, los valores predeterminados de las nuevas columnas realmente caen en 0 y la cadena vacía, y la tabla de miembros se puede escribir y volver a leer en el orden en que se ingresó en el grupo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration3To4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsLegacyRowsAndDefaultsTheNewColumns() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion3Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                val threads = db.threadDao().observeThreads().first()
                assertEquals("muchas conversaciones,El orden permanece sin cambios.", listOf(PINNED_THREAD_ID, PLAIN_THREAD_ID), threads.map { it.id })
                assertEquals("La etiqueta pin permanece con la línea.", true, threads.first().pinned)
                assertEquals("Las lecturas no leídas no se pueden descartar.", 3, threads.first().unreadCount)
                assertEquals("No pierdas la copia preliminar", "Oración 2", threads.first().lastMessagePreview)

                assertTrue("Todas las conversaciones antiguas caen en el lado de los mensajes privados.", threads.none { it.isGroup })
                assertEquals("La sesión anterior no tiene nombre de grupo.", listOf("", ""), threads.map { it.title })

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
        assertEquals("Columna de etiqueta de grupo con valor predeterminado 0", "0", columnDefault(file, table = "mp_thread", column = "isGroup"))
        assertEquals("Columna de nombre de grupo con valor predeterminado cadena vacía", "''", columnDefault(file, table = "mp_thread", column = "title"))
    }

    @Test
    fun memberTableIsCreatedWritableAndOrderedByJoinTime() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion3Database(file)

        runBlocking {
            val db = openMigrated(name)
            try {
                // No existía tal tabla antes de la actualización.;Después de la actualización, debe poder usarse como una tabla de miembros de grupo.
                db.threadDao().upsert(
                    MpThreadEntity(
                        id = GROUP_THREAD_ID,
                        characterId = "c-second",
                        isGroup = true,
                        title = "Ayuan, Xiaoman, yo",
                    ),
                )
                db.threadMemberDao().upsertAll(
                    listOf(
                        MpThreadMemberEntity(threadId = GROUP_THREAD_ID, characterId = "c-second", joinedAt = 200L),
                        MpThreadMemberEntity(threadId = GROUP_THREAD_ID, characterId = "c-first", joinedAt = 100L),
                    ),
                )

                val members = db.threadMemberDao().observeMembers(GROUP_THREAD_ID).first()
                assertEquals("Los miembros presionan para unirse al grupo y leen uno tras otro.", listOf("c-first", "c-second"), members.map { it.characterId })
                assertEquals(2, db.threadMemberDao().countInThread(GROUP_THREAD_ID))
                assertEquals("La línea de grupo es una conversación de grupo.", true, db.threadDao().getThread(GROUP_THREAD_ID)?.isGroup)
                assertEquals("Sigue el nombre del grupo", "Ayuan, Xiaoman, yo", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
            } finally {
                db.close()
            }
        }
        assertEquals("Tabla de miembros indexada por threadId", true, indexNames(file, table = "mp_thread_member").contains("index_mp_thread_member_threadId"))
        assertTrue("El viejo reloj todavía está ahí.", tableNames(file).containsAll(listOf("mp_thread", "mp_message")))
    }

    @Test
    fun groupThreadsAreNotTreatedAsAPersonsDirectMessage() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion3Database(file)

        runBlocking {
            val db = openMigrated(name)
            try {
                // Grupo creado después de la actualización,characterId El primer miembro en caer.;No puedes presionarlo cuando buscas mensajes privados por carácter.
                db.threadDao().upsert(
                    MpThreadEntity(
                        id = GROUP_THREAD_ID,
                        characterId = CHARACTER_ID,
                        isGroup = true,
                        title = "Persona y otra persona en una vieja conversación.",
                    ),
                )

                assertNotNull("El viejo mensaje privado sigue ahí.", db.threadDao().findThreadByCharacter(CHARACTER_ID))
                assertEquals("No pretendas ser el mensaje privado de esta persona", PINNED_THREAD_ID, db.threadDao().findThreadByCharacter(CHARACTER_ID)?.id)
                assertFalse(db.threadDao().findThreadByCharacter(CHARACTER_ID)?.isGroup ?: true)
            } finally {
                db.close()
            }
        }
    }

    /** Cree una biblioteca v3 como 3.json,Y escribe dos conversaciones y tres mensajes. */
    private fun createVersion3Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("La línea base debe ser v3", 3, schema.getInt("version"))
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
            legacy.version = 3
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

    private fun indexNames(file: File, table: String): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun tableNames(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'mp_%'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v3.db"
        const val PINNED_THREAD_ID: String = "t-pinned"
        const val PLAIN_THREAD_ID: String = "t-plain"
        const val GROUP_THREAD_ID: String = "t-group"
        const val CHARACTER_ID: String = "c-legacy"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/3.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
