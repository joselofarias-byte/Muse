package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 4 → 5 Migrar:El antiguo grupo permanece como está.,El nuevo título de la columna Personalizado se incluye en 0(El usuario no le ha cambiado el nombre).
 *
 * Utilice el 4.json exportado para crear una biblioteca v4 con la misma forma que la máquina real(Crear declaraciones de tabla, índices,room_master_table Los hashes de identidad se escriben según el esquema.),
 * Ponga un nombre de grupo, miembros y mensajes en él.,Luego deja que Room lo abra con la cadena de migración.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration4To5Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsGroupRowsAndDefaultsTheCustomizedFlag() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion4Database(file)

        val db = openMigrated(name)
        try {
            runBlocking {
                val threads = db.threadDao().observeThreads().first()
                assertEquals("muchas conversaciones", listOf(GROUP_THREAD_ID, DM_THREAD_ID), threads.map { it.id })

                val group = requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID))
                assertTrue("La etiqueta de grupo se queda contigo", group.isGroup)
                assertEquals("El nombre del grupo se queda atrás.", "Ayuan, Xiaoman", group.title)
                assertFalse("No hay ningún registro de \"usuario cambiando de nombre\" en el grupo anterior,Actúa siempre como si nada hubiera cambiado.", group.titleCustomized)

                assertEquals(
                    "La lista de miembros tampoco se mueve",
                    listOf("c1", "c2"),
                    db.threadMemberDao().membersOf(GROUP_THREAD_ID).map { it.characterId },
                )
                assertEquals("La noticia sigue ahí.", 1, db.messageDao().countInThread(GROUP_THREAD_ID))
                assertEquals("La versión de la biblioteca se actualiza a 5", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
                assertEquals("Esta versión es 5", 5, MINI_PHONE_DB_VERSION)
            }
        } finally {
            db.close()
        }
        assertEquals("nueva columna con valor predeterminado 0", "0", columnDefault(file, table = "mp_thread", column = "titleCustomized"))
    }

    @Test
    fun aFreshGroupAfterUpgradeCanBeMarkedAsRenamed() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion4Database(file)

        runBlocking {
            val db = openMigrated(name)
            try {
                // Escriba según la nueva semántica después de la actualización:Cambiar nombre y lugar 1,El antiguo grupo que no ha cambiado sigue 0
                db.threadDao().updateTitle(GROUP_THREAD_ID, "equipo de fin de semana", customized = true)
                db.threadDao().upsert(
                    MpThreadEntity(id = "fresh-group", characterId = "c1", isGroup = true, title = "nuevo grupo"),
                )

                assertTrue(requireNotNull(db.threadDao().getThread(GROUP_THREAD_ID)).titleCustomized)
                assertFalse("Al grupo recién creado no se le ha cambiado el nombre de forma predeterminada.", requireNotNull(db.threadDao().getThread("fresh-group")).titleCustomized)
                assertEquals("El cambio de nombre está realmente escrito en él.", "equipo de fin de semana", db.threadDao().getThread(GROUP_THREAD_ID)?.title)
            } finally {
                db.close()
            }
        }
    }

    /** Cree una biblioteca v4 como 4.json,Y escribe un grupo, un mensaje privado, dos líneas de integrantes y un mensaje grupal. */
    private fun createVersion4Database(file: File) {
        val schema = JSONObject(readAsset(SCHEMA_ASSET)).getJSONObject("database")
        assertEquals("La línea base debe ser v4", 4, schema.getInt("version"))
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
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted, " +
                    "isGroup, title) VALUES ('$GROUP_THREAD_ID', 'c1', 0, 2, 'Nos vemos por la tarde', 3000, 0, 1, 'Ayuan, Xiaoman')",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted, " +
                    "isGroup, title) VALUES ('$DM_THREAD_ID', 'c1', 0, 0, '¿Está ahí?', 1000, 0, 0, '')",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread_member (threadId, characterId, joinedAt) VALUES ('$GROUP_THREAD_ID', 'c1', 1)",
            )
            legacy.execSQL(
                "INSERT INTO mp_thread_member (threadId, characterId, joinedAt) VALUES ('$GROUP_THREAD_ID', 'c2', 2)",
            )
            legacy.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt, status) " +
                    "VALUES ('legacy-1', '$GROUP_THREAD_ID', 'c2', 'character', 'text', 'Nos vemos por la tarde', 3000, 0)",
            )
            legacy.version = 4
        } finally {
            legacy.close()
        }
    }

    private fun openMigrated(name: String): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
        .addMigrations(
            MINI_PHONE_MIGRATION_1_2,
            MINI_PHONE_MIGRATION_2_3,
            MINI_PHONE_MIGRATION_3_4,
            MINI_PHONE_MIGRATION_4_5,
        )
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

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v4.db"
        const val GROUP_THREAD_ID: String = "t-group"
        const val DM_THREAD_ID: String = "t-dm"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/4.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
