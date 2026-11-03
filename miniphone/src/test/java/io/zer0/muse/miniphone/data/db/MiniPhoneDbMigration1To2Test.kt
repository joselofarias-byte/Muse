package io.zer0.muse.miniphone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1 → 2 Migrar:laoku(Sin columna de estado)Después de la actualización, los datos siguen ahí y el estado predeterminado es "Enviado".
 *
 * Primero use el 1.json exportado para crear una biblioteca v1 que sea la misma que la máquina real. —— Declaraciones de creación de tablas, índices e incluso room_master_table
 * Los hashes de identidad se escriben según el esquema.,Entonces, lo que estamos ejecutando aquí es el camino de "actualizar equipos antiguos que ya tienen datos".,No es una base de datos vacía para crear una tabla.
 * Hay dos niveles de afirmaciones.:Room Capacidad para abrir y leer líneas de historial.(Verificación del esquema de descripción aprobada),y el estado es realmente 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbMigration1To2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var databaseName: String? = null

    @After
    fun tearDown() {
        databaseName?.let { name -> context.deleteDatabase(name) }
    }

    @Test
    fun upgradeKeepsLegacyMessagesAndDefaultsTheirStatusToSent() {
        val name = LEGACY_DB_NAME
        databaseName = name
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        context.deleteDatabase(name)
        createVersion1Database(file, messageCount = 3)

        // Antes de la migración:No existe tal columna en la estructura antigua.
        assertFalse("v1 No debería haber una columna de estado en la biblioteca.", columnNames(file).contains("status"))

        val db = openMigrated(name)
        try {
            runBlocking {
                val messages = db.messageDao().observeMessages(LEGACY_THREAD_ID).first()

                assertEquals("La información histórica no se puede perder.", 3, messages.size)
                assertEquals(listOf("Oración 1", "Oración 2", "Oración 3"), messages.map { it.content })
                assertEquals("Haga clic en \"Problema\" para completar todas las filas del historial.", List(3) { MpMessageStatus.SENT }, messages.map { it.status })
                assertEquals("La línea de conversación sigue ahí.", "Oración 3", db.threadDao().getThread(LEGACY_THREAD_ID)?.lastMessagePreview)
                assertEquals("Actualice la versión de la biblioteca a la versión actual", MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
            }
        } finally {
            db.close()
        }
        // La nueva biblioteca también debe ver el valor predeterminado en SQL simple.:Cualquier escritura posterior sin estado debe ser 0
        assertEquals("0", statusColumnDefault(file))
    }

    @Test
    fun columnDefaultKeepsOldStyleWritesAndStatusUpdatesWork() {
        val db = newInMemoryMiniPhoneDb()
        try {
            // Escribiendo sin estatus(Ruta de código antigua, manual SQL)Finalización "Emitida"
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt) " +
                    "VALUES ('m1', 't1', 'c1', 'character', 'text', 'en', 1000)",
            )
            assertEquals(MpMessageStatus.SENT, runBlocking { db.messageDao().getMessage("m1") }?.status)

            // El estado se puede sobrescribir:El flujo del fracaso y el éxito de la reedición depende de esta frase.
            runBlocking { db.messageDao().updateStatus("m1", MpMessageStatus.FAILED) }
            assertEquals(MpMessageStatus.FAILED, runBlocking { db.messageDao().getMessage("m1") }?.status)
        } finally {
            db.close()
        }
    }

    /** Cree una biblioteca v1 como 1.json,Y escribir varios mensajes históricos. */
    private fun createVersion1Database(file: File, messageCount: Int) {
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
            // Consistente con la máquina real.:room_master_table Lo que se almacena en él es el hash de identidad de la v1.
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) {
                legacy.execSQL(setup.getString(index))
            }
            legacy.execSQL(
                "INSERT INTO mp_thread (id, characterId, pinned, unreadCount, lastMessagePreview, lastMessageAt, muted) " +
                    "VALUES ('$LEGACY_THREAD_ID', '$LEGACY_CHARACTER_ID', 0, 0, 'Oración 3', 3000, 0)",
            )
            repeat(messageCount) { offset ->
                val number = offset + 1
                legacy.execSQL(
                    "INSERT INTO mp_message (id, threadId, characterId, sender, type, content, createdAt) " +
                        "VALUES ('legacy-$number', '$LEGACY_THREAD_ID', '$LEGACY_CHARACTER_ID', 'character', 'text', " +
                        "'No. $number frase', ${number * 1000})",
                )
            }
            legacy.version = 1
        } finally {
            legacy.close()
        }
    }

    private fun openMigrated(name: String): MiniPhoneDb = Room.databaseBuilder(context, MiniPhoneDb::class.java, name)
        .addMigrations(MINI_PHONE_MIGRATION_1_2, MINI_PHONE_MIGRATION_2_3, MINI_PHONE_MIGRATION_3_4, MINI_PHONE_MIGRATION_4_5)
        .allowMainThreadQueries()
        .build()

    private fun columnNames(file: File): Set<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(mp_message)", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        }

    private fun statusColumnDefault(file: File): String? =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA table_info(mp_message)", null).use { cursor ->
                var found: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "status") {
                        found = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                    }
                }
                found
            }
        }

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val LEGACY_DB_NAME: String = "miniphone-migration-v1.db"
        const val LEGACY_THREAD_ID: String = "t-legacy"
        const val LEGACY_CHARACTER_ID: String = "c-legacy"
        const val SCHEMA_ASSET: String = "io.zer0.muse.miniphone.data.db.MiniPhoneDb/1.json"
        const val TABLE_PLACEHOLDER: String = "\${TABLE_NAME}"
    }
}
