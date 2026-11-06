package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * B6-03: MuseDb Pruebas de la cadena de migración.
 *
 * Cree manualmente la base de datos para cada esquema JSON desde v56 a v68 e inserte un mensaje histórico.
 * Luego migre todo a v74 a través de Room y verifique:
 * - messages.mood_skin La columna existe y está predeterminada NULL
 * - sessions.is_locked La columna existe
 * - generation_checkpoints / group_chat_generation_ledger la mesa existe
 * - El contenido histórico del mensaje y las columnas clave se mantienen intactos
 *
 * Implementar memoria/FactDbMigrationTest: sin MigrationTestHelper,
 * Utilice FrameworkSQLiteOpenHelperFactory en su lugar para crear la biblioteca antigua directamente según el esquema JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migrateEveryVersionTo75_keepsDataAndAddsGenerationTables() {
        val versions = availableSchemaVersions() + (55..68) + 74
        for (fromVersion in versions) {
            val dbFile = context.getDatabasePath("muse_migration_$fromVersion.db").apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            try {
                createSchemaAtVersion(fromVersion, dbFile.absolutePath)
                insertLegacyRow(dbFile.absolutePath, fromVersion)

                val migrations = migrationsFrom(fromVersion)

                val db = Room.databaseBuilder(
                    context,
                    MuseDb::class.java,
                    dbFile.absolutePath,
                )
                    .addMigrations(
                        *migrations.toTypedArray(),
                    )
                    .allowMainThreadQueries()
                    .build()

                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    var hasParentGroup = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "parentGroupId") hasParentGroup = true
                    }
                    assertTrue("v$fromVersion Debería haber una columna parentGroupId después de la migración", hasParentGroup)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    var moodSkinDefault: String? = null
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "moodSkin") {
                            moodSkinDefault = cursor.getString(4)
                            break
                        }
                    }
                    assertTrue("v$fromVersion Debería haber una columna moodSkin después de la migración.", moodSkinDefault != null)
                    assertTrue(
                        "moodSkin El valor predeterminado debe ser NULL, got $moodSkinDefault",
                        moodSkinDefault == null || moodSkinDefault == "NULL",
                    )
                }

                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasLastRead = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "lastReadMessageId") {
                            hasLastRead = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion Debería haber una columna lastReadMessageId después de la migración", hasLastRead)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasLastReadCount = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "lastReadCount") {
                            hasLastReadCount = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion Debería haber una columna lastReadCount después de la migración", hasLastReadCount)
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasProactiveNext = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "proactiveNextTriggerAt") {
                            hasProactiveNext = true
                            break
                        }
                    }
                    assertTrue("v$fromVersion Debería haber una columna proactiveNextTriggerAt después de la migración", hasProactiveNext)
                }
                db.openHelper.writableDatabase.query(
                    "SELECT content, moodSkin FROM messages WHERE id='legacy-msg'",
                ).use { cursor ->
                    assertTrue("v$fromVersion Los mensajes históricos deben conservarse después de la migración", cursor.moveToFirst())
                    assertEquals("Mensajes históricos antes de la migración", cursor.getString(0))
                    assertNull("Mensaje histórico mood_skin debería ser NULL", cursor.getString(1))
                }

                db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                    var hasIsLocked = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == "isLocked") hasIsLocked = true
                    }
                    assertTrue("v$fromVersion Debería haber una columna isLocked después de la migración.", hasIsLocked)
                }
                db.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='generation_checkpoints'",
                ).use { cursor ->
                    assertTrue("v$fromVersion Debería haber una tabla Generation_checkpoints después de la migración.", cursor.moveToFirst())
                }
                db.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='group_chat_generation_ledger'",
                ).use { cursor ->
                    assertTrue("v$fromVersion Debería haber una tabla group_chat_generación_ledger después de la migración", cursor.moveToFirst())
                }
                listOf(
                    "conversation_turns",
                    "conversation_events",
                    "tool_rounds",
                    "session_branch_heads",
                    "message_parts",
                ).forEach { table ->
                    db.openHelper.writableDatabase.query(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(table),
                    ).use { cursor ->
                        assertTrue("v$fromVersion Después de la migración, debería haber $table mesa", cursor.moveToFirst())
                    }
                }
                db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                    val columns = buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                    assertTrue("v$fromVersion Debería haber una columna commitSeq después de la migración.", "commitSeq" in columns)
                    assertTrue("v$fromVersion Debería haber una columna parentMessageId después de la migración", "parentMessageId" in columns)
                }

                db.close()
            } finally {
                if (dbFile.exists()) dbFile.delete()
                context.deleteDatabase(dbFile.name)
            }
        }
    }

    @Test
    fun migrate105ToLatest_disablesGlobalMemoryOnlyForNonDefaultAssistants() {
        val dbFile = context.getDatabasePath("muse_migration_assistant_memory_105.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(105, dbFile.absolutePath)
            val legacyHelper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(105) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
            val legacyDb = legacyHelper.writableDatabase
            try {
                listOf(
                    Triple("default", "Default", 1),
                    Triple("assistant-shared", "Previously shared", 1),
                    Triple("assistant-private", "Already private", 0),
                ).forEach { (id, name, useGlobalMemory) ->
                    legacyDb.compileStatement(
                        "INSERT INTO assistants (id, name, useGlobalMemory) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.bindString(1, id)
                        statement.bindString(2, name)
                        statement.bindLong(3, useGlobalMemory.toLong())
                        statement.executeInsert()
                    }
                }
            } finally {
                legacyHelper.close()
            }

            val db = Room.databaseBuilder(context, MuseDb::class.java, dbFile.absolutePath)
                .addMigrations(*migrationsFrom(105).toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                val migratedSettings = mutableMapOf<String, Pair<String, Int>>()
                db.openHelper.writableDatabase.query(
                    "SELECT id, name, useGlobalMemory FROM assistants",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        migratedSettings[cursor.getString(0)] = cursor.getString(1) to cursor.getInt(2)
                    }
                }
                assertEquals("Default" to 1, migratedSettings["default"])
                assertEquals("Previously shared" to 0, migratedSettings["assistant-shared"])
                assertEquals("Already private" to 0, migratedSettings["assistant-private"])
            } finally {
                db.close()
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    @Test
    fun migrateRealV68WithoutIsLocked_addsMissingColumnsAndKeepsData() {
        val dbFile = context.getDatabasePath("muse_migration_68_real.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(68, dbFile.absolutePath, stripIsLocked = true)
            insertLegacyRow(dbFile.absolutePath, 68)
            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(68).toTypedArray())
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query("PRAGMA table_info(sessions)").use { cursor ->
                var hasIsLocked = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "isLocked") hasIsLocked = true
                }
                assertTrue("La migración real v68 debería tener la columna isLocked", hasIsLocked)
            }
            db.openHelper.writableDatabase.query("PRAGMA table_info(messages)").use { cursor ->
                var hasParentGroup = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "parentGroupId") hasParentGroup = true
                }
                assertTrue("La migración real v68 debe tener la columna parentGroupId", hasParentGroup)
            }
            db.openHelper.writableDatabase.query(
                "SELECT content FROM messages WHERE id='legacy-msg'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Mensajes históricos antes de la migración", cursor.getString(0))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    @Test
    fun migrateLegacyGroupChatMessages_addsMissingColumnsAndKeepsRows() {
        val dbFile = context.getDatabasePath("muse_migration_groupchat.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(68, dbFile.absolutePath)
            val factory = FrameworkSQLiteOpenHelperFactory()
            val helper = factory.create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(68) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build(),
            ).writableDatabase
            helper.execSQL("ALTER TABLE group_chat_messages RENAME TO group_chat_messages_full")
            helper.execSQL(
                """
                CREATE TABLE group_chat_messages (
                    id TEXT NOT NULL PRIMARY KEY,
                    chatId TEXT NOT NULL,
                    senderId TEXT NOT NULL,
                    body TEXT NOT NULL,
                    imageBase64Json TEXT NOT NULL DEFAULT '[]',
                    timestamp INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            helper.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderId, body, imageBase64Json, timestamp) " +
                    "SELECT id, chatId, senderId, body, imageBase64Json, timestamp FROM group_chat_messages_full",
            )
            helper.execSQL("DROP TABLE group_chat_messages_full")
            helper.execSQL("CREATE INDEX IF NOT EXISTS index_group_chat_messages_chatId ON group_chat_messages(chatId)")
            helper.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderId, body, timestamp) " +
                    "VALUES ('legacy-group-msg', 'legacy-group', 'u1', 'Mensajes de chat grupal antes de la migración', 1)",
            )
            helper.close()

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(68).toTypedArray())
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(1))
                for (name in listOf(
                    "senderType",
                    "senderName",
                    "mood",
                    "reasoning",
                    "whisper_target_id",
                    "reply_to_id",
                    "messageType",
                    "fileAttachmentsJson",
                )) {
                    assertTrue("Después de la migración, debería haber $name columna", name in columns)
                }
            }
            val defaults = mutableMapOf<String, String?>()
            db.openHelper.writableDatabase.query("PRAGMA table_info(group_chat_messages)").use { cursor ->
                while (cursor.moveToNext()) defaults[cursor.getString(1)] = cursor.getString(4)
            }
            assertEquals("'[]'", defaults["imageBase64Json"])
            assertEquals("0", defaults["timestamp"])
            assertEquals("NULL", defaults["whisper_target_id"])
            assertEquals("NULL", defaults["reply_to_id"])
            assertEquals("'normal'", defaults["messageType"])
            assertEquals("'[]'", defaults["fileAttachmentsJson"])
            db.openHelper.writableDatabase.query(
                "SELECT senderType, senderName, body, mood, reasoning, whisper_target_id, reply_to_id, messageType, fileAttachmentsJson " +
                    "FROM group_chat_messages WHERE id='legacy-group-msg'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("Mensajes de chat grupal antes de la migración", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertNull(cursor.getString(6))
                assertEquals("normal", cursor.getString(7))
                assertEquals("[]", cursor.getString(8))
            }
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO group_chat_messages (id, chatId, senderType, senderId, senderName, body) " +
                    "VALUES ('new-group-msg', 'legacy-group', 'assistant', 'a1', 'asistente', 'Escribir después de la migración')",
            )
            db.openHelper.writableDatabase.query(
                "SELECT body, messageType, fileAttachmentsJson, whisper_target_id, reply_to_id, reasoning, mood " +
                    "FROM group_chat_messages WHERE id='new-group-msg'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Escribir después de la migración", cursor.getString(0))
                assertEquals("normal", cursor.getString(1))
                assertEquals("[]", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertNull(cursor.getString(6))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /**
     * Regresar:de"Biblioteca antigua real producida por migración manuscrita"(scheduled_tasks Ninguno created_by)Después de la actualización,
     * v98→99 La migración debe agregar creado_por,De lo contrario, la validación del esquema de la habitación colapsará.
     * Las pruebas existentes utilizan el esquema JSON para crear la base de datos.(en sí mismo contiene created_by),Esta ruta de actualización no está cubierta.
     */
    @Test
    fun migration98To99_addsCreatedByToScheduledTasks() {
        val dbFile = java.io.File(context.cacheDir, "migration_98_99_created_by.db").apply {
            if (exists()) delete()
        }
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(98) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            // Simulando la migración de escritura a mano(9→10)tabla creada:No created_by
                            db.execSQL("CREATE TABLE scheduled_tasks (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
            val db = helper.writableDatabase
            MuseDb.MIGRATION_98_99.migrate(db)
            var hasCreatedBy = false
            db.query("PRAGMA table_info(scheduled_tasks)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "created_by") hasCreatedBy = true
                }
            }
            assertTrue("v98→99 las tareas programadas deberían haber creado la columna por después de la migración", hasCreatedBy)
            helper.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
        }
    }

    @Test
    fun migrateV99To100_addsGroupChatArchivedColumn() {
        val dbFile = java.io.File(context.cacheDir, "migration_99_100_archived.db").apply {
            if (exists()) delete()
        }
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(99) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            // Simule la biblioteca v99 donde existe la tabla group_chats pero no hay una columna isArchived
                            db.execSQL("CREATE TABLE group_chats (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                        }

                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
            val db = helper.writableDatabase
            MuseDb.MIGRATION_99_100.migrate(db)
            var hasArchived = false
            db.query("PRAGMA table_info(group_chats)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == "isArchived") hasArchived = true
                }
            }
            assertTrue("v99→100 Después de la migración, group_chats debería tener la columna isArchived", hasArchived)
            helper.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
        }
    }

    @Test
    fun migrateV75To76_externalizesLongBase64AndKeepsShortInline() {
        val dbFile = context.getDatabasePath("muse_migration_75_images.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val imageDir = File(context.cacheDir, "muse_images_migration_v75")
        imageDir.deleteRecursively()
        imageDir.mkdirs()
        try {
            createSchemaAtVersion(75, dbFile.absolutePath)
            insertLegacyRow(dbFile.absolutePath, 75)

            val longBytes = ByteArray(900) { (it % 251).toByte() }
            val longBase64 = Base64.getEncoder().encodeToString(longBytes)
            val shortBase64 = "AQIDBA=="
            val initialJson = JSONArray().put(longBase64).put(shortBase64).toString()

            val factory = FrameworkSQLiteOpenHelperFactory()
            val helper = factory.create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(75) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build(),
            ).writableDatabase
            helper.execSQL(
                "UPDATE messages SET imageBase64Json = ? WHERE id = 'legacy-msg'",
                arrayOf(initialJson),
            )
            helper.close()

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(75, imageDir).toTypedArray())
                .allowMainThreadQueries()
                .build()

            db.openHelper.writableDatabase.query(
                "SELECT imageBase64Json FROM messages WHERE id = 'legacy-msg'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val stored = JSONArray(cursor.getString(0))
                assertEquals(2, stored.length())
                val longRef = stored.getString(0)
                assertTrue("Long base64 debe externalizarse a la ruta file://", longRef.startsWith("file://"))
                assertEquals(shortBase64, stored.getString(1))
                val storedFile = File(longRef.removePrefix("file://"))
                assertTrue("El archivo de imagen externo debe existir.", storedFile.exists())
                val roundTrip = MessageImageStore(imageDir).toBase64List(
                    listOf(longRef, shortBase64),
                )
                assertEquals(longBase64, roundTrip[0])
                assertEquals(shortBase64, roundTrip[1])
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
            imageDir.deleteRecursively()
        }
    }

    @Test
    fun migrateV76To77_rebuildsMessageFtsTable() {
        val dbFile = context.getDatabasePath("muse_migration_76_fts.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(76, dbFile.absolutePath)
            insertLegacyRow(dbFile.absolutePath, 76)
            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(76).toTypedArray())
                .allowMainThreadQueries()
                .build()
            db.openHelper.writableDatabase.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='messages_fts'",
            ).use { cursor ->
                assertTrue("76→77 message_fts debería existir después de la migración", cursor.moveToFirst())
                val sql = cursor.getString(0)
                assertTrue("messages_fts Debería ser una mesa virtual FTS4/FTS5: $sql", sql.contains("FTS4") || sql.contains("fts5"))
            }
            db.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /** Simulación"Dispositivo estrellado":v80 Tabla incorrecta con índice activado → MIGRATION_80_81 Borrar índice y reconstruir tabla,Todos los datos se conservan.
     *
     * solo corre 80→81 esta sección:La reunión final de toda la cadena de actualización. 121→122 Eliminar estas dos tablas,
     * No puedes saber si sigues toda la cadena. 80→81 Se obtiene el resultado del procesamiento. */
    @Test
    fun migrate80To81_cleansLegacyIndexesFromCrashedDevice() {
        val dbName = "muse_migration_80_crash.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(80) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE ai_moments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "content TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'life', mood TEXT, " +
                                    "likes INTEGER NOT NULL DEFAULT 0, likedByUser INTEGER NOT NULL DEFAULT 0, " +
                                    "source TEXT NOT NULL DEFAULT 'scheduled', createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL(
                                "CREATE TABLE ai_moment_comments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "momentId TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, " +
                                    "createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL("CREATE INDEX idx_moments_created ON ai_moments(createdAt DESC)")
                            db.execSQL("CREATE INDEX idx_moment_comments_moment ON ai_moment_comments(momentId)")
                            db.execSQL("INSERT INTO ai_moments (id, content, createdAt) VALUES ('m1', 'Mala dinámica heredada del dispositivo', 100)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            MuseDb.MIGRATION_80_81.migrate(db)

            // Los datos todavía están ahí
            db.query("SELECT count(*) FROM ai_moments").use { c ->
                assertTrue("Se debe preservar la mala dinámica heredada del dispositivo", c.moveToFirst() && c.getInt(0) == 1)
            }
            // Índice limpio
            db.query(
                "SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'idx_moments%'",
            ).use { c ->
                assertTrue("Los índices residuales deben limpiarse", c.moveToFirst() && c.getInt(0) == 0)
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    /** Simular reloj roto versión intermedia de máquina real: mood banda de columna DEFAULT NULL(La máquina real dflt_value se lee como 'NULL'),residuo de índice,
     * MIGRATION_80_81 Datos después de reconstruir la tabla(Contiene valor del estado de ánimo)Debe conservarse. */
    @Test
    fun migrate80To81_rebuildsTableWithMoodNullDefault() {
        val dbName = "muse_migration_80_moodnull.db"
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(80) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE ai_moments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "content TEXT NOT NULL, type TEXT NOT NULL DEFAULT 'life', mood TEXT DEFAULT NULL, " +
                                    "likes INTEGER NOT NULL DEFAULT 0, likedByUser INTEGER NOT NULL DEFAULT 0, " +
                                    "source TEXT NOT NULL DEFAULT 'scheduled', createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL(
                                "CREATE TABLE ai_moment_comments (id TEXT NOT NULL PRIMARY KEY, " +
                                    "momentId TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, " +
                                    "createdAt INTEGER NOT NULL DEFAULT 0)",
                            )
                            db.execSQL("CREATE INDEX idx_moments_created ON ai_moments(createdAt DESC)")
                            db.execSQL("INSERT INTO ai_moments (id, content, mood, createdAt) VALUES ('m1', 'Dinámica versión intermedia', 'feliz', 100)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            MuseDb.MIGRATION_80_81.migrate(db)

            // datos(Contiene valor del estado de ánimo)Reservar
            db.query("SELECT content, mood FROM ai_moments WHERE id='m1'").use { c ->
                assertTrue("Se debe conservar la dinámica de la versión intermedia.", c.moveToFirst())
                assertEquals("Dinámica versión intermedia", c.getString(0))
                assertEquals("feliz", c.getString(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migrate106To107_rebuildsKnowledgeFtsWithChineseNgrams() {
        val dbFile = context.getDatabasePath("muse_knowledge_fts_106.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(106) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE knowledge_chunks (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "doc_id TEXT NOT NULL, " +
                                "content TEXT NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE VIRTUAL TABLE knowledge_chunks_fts " +
                                "USING fts4(chunkId, doc_id, text_content)",
                        )
                        db.execSQL(
                            "INSERT INTO knowledge_chunks(id, doc_id, content) " +
                                "VALUES ('chunk-1', 'doc-1', '知识库检索')",
                        )
                        db.execSQL(
                            "INSERT INTO knowledge_chunks_fts(chunkId, doc_id, text_content) " +
                                "VALUES ('chunk-1', 'doc-1', '知识库检索')",
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val db = helper.writableDatabase
            MuseDb.MIGRATION_106_107.migrate(db)

            db.query(
                """
                SELECT chunkId FROM knowledge_chunks_fts
                WHERE text_content MATCH '"知识" "识库"'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue("Después de la migración, los ngrams chinos deberían verse afectados por el FTS", cursor.moveToFirst())
                assertEquals("chunk-1", cursor.getString(0))
            }
        } finally {
            helper.close()
            dbFile.delete()
        }
    }

    /**
     * v2.6.x sección de cola (113..117) de la matriz de control de habitaciones.
     *
     * Antecedentes:113/114/115/116 Los cuatro puntos de partida anteriormente sólo contaban con pruebas especiales de "ejecución manual de DDL"
     * ([io.zer0.muse.data.relationship.AssistantRelationshipMigrationTest],
     * [MuseDbMigration116To117Test]), ese camino no se abre a través de Room en absoluto,
     * Por lo tanto, la verificación del esquema a nivel de entidad de Room nunca se ha ejecutado en estos cuatro segmentos.
     * 117 Es el precursor directo del 118 y está incluido en la matriz.
     * Usado aquí [migrateEveryVersionTo75_keepsDataAndAddsGenerationTables] El mismo conjunto de mecanismos:
     * Construya una biblioteca antigua según el esquema JSON → Room.databaseBuilder Desencadenar una migración real → Afirmar el estado final de la migración.
     *
     * Calibre de afirmación (uno es indispensable, solo afirmar "no bloqueado" significa que no hay prueba):
     *  - usage_records / assistant_relationship / session_todos / card_entities Conjuntos de columnas y valores predeterminados de las cuatro tablas;
     *  - usage_records tres índices,session_todos índice único sessionId,
     *    card_entities El índice único de dockSlot es el mismo que el índice originMessageId;
     *  - sessions.input_draft columna;
     *  - Momentos/Las cuatro tablas del diario han sido migradas de la versión anterior (121→122), el estado final ya no debería existir;
     *  - No se pierde una fila de cada una de las sesiones/mensajes insertados antes de la migración.
     */
    @Test
    fun migrateV113To117Starts_validateThroughRoom() {
        val starts = (113..117).filter { schemaExists(it) }
        assertEquals(
            "113..117 Las instantáneas del esquema deben estar completas y la biblioteca anterior no se puede crear si falta alguna (no se puede reemplazar por una afirmación vacía).",
            (113..117).toList(),
            starts,
        )
        starts.forEach { assertV113StartUpgradesToLatest(it) }
    }

    /** Single 113..117 Punto de partida: construir una biblioteca antigua → Room Activar la migración activada → Afirmar el estado final. */
    private fun assertV113StartUpgradesToLatest(fromVersion: Int) {
        val dbFile = context.getDatabasePath("muse_migration_v26x_$fromVersion.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(fromVersion, dbFile.absolutePath)
            insertLegacyRow(dbFile.absolutePath, fromVersion)

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(fromVersion).toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                assertV26xTerminalState(db.openHelper.writableDatabase, "v$fromVersion→120")
            } finally {
                db.close()
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /** v2.6.x Un conjunto completo de afirmaciones para el estado final de la migración (compartidas con la matriz y los casos de uso a lo largo de la cadena). */
    private fun assertV26xTerminalState(raw: SupportSQLiteDatabase, tag: String) {
        assertV26xSurfaces(raw, tag)
        assertRoomValidatedLatestSchema(raw, tag)
        raw.query("SELECT title FROM sessions WHERE id='legacy-session'").use { cursor ->
            assertTrue("$tag: Las sesiones insertadas antes de la migración deben conservarse", cursor.moveToFirst())
            assertEquals("$tag: El título de la sesión no debe cambiarse", "Pruebas de migración", cursor.getString(0))
        }
        raw.query("SELECT content, moodSkin FROM messages WHERE id='legacy-msg'").use { cursor ->
            assertTrue("$tag: Los mensajes históricos insertados antes de la migración deben conservarse", cursor.moveToFirst())
            assertEquals("$tag: El contenido de los mensajes históricos no debe cambiarse.", "Mensajes históricos antes de la migración", cursor.getString(0))
            assertNull("$tag: El mensaje histórico que moodSkin debería ser NULL", cursor.getString(1))
        }
    }

    /**
     * v112 → 120 Cadena completa: cubre específicamente el reabastecimiento de datos 112→113(usage_records de INSERTAR...SELECCIONAR).
     *
     * condición de relleno (MuseDb.MIGRATION_112_113):`role='ASSISTANT' AND (promptTokens + completionTokens) > 0`.
     * Aquí se cubren deliberadamente cuatro límites: cantidad útil de asistente (reposición), cantidad cero (sin reposición),
     * token Para NULL (sin relleno), mensajes de usuario con uso (role filtro, sin relleno),
     * Agregue un asistente con solo un mensaje (relleno). Afirmar el número de elementos de reabastecimiento, valores de campo,
     * Y hay muchos mensajes en una línea,token El campo no ha sido sobrescrito.
     */
    @Test
    fun migrateV112ToLatest_backfillsUsageRecordsAndKeepsMessages() {
        val dbFile = context.getDatabasePath("muse_migration_v112_full_chain.db").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        try {
            createSchemaAtVersion(112, dbFile.absolutePath)
            seedV112UsageBackfillRows(dbFile.absolutePath)

            val db = Room.databaseBuilder(
                context,
                MuseDb::class.java,
                dbFile.absolutePath,
            )
                .addMigrations(*migrationsFrom(112).toTypedArray())
                .allowMainThreadQueries()
                .build()
            try {
                val raw = db.openHelper.writableDatabase
                assertV26xSurfaces(raw, "v112→120")
                assertRoomValidatedLatestSchema(raw, "v112→120")
                assertUsageBackfill(raw)
                assertMessagesSurvivedBackfill(raw)
            } finally {
                db.close()
            }
        } finally {
            if (dbFile.exists()) dbFile.delete()
            context.deleteDatabase(dbFile.name)
        }
    }

    /** 112→113 Datos de entrada rellenados. Los nombres de las columnas utilizan los nombres originales de los casos de camellos en la instantánea v112 (messages No se ha cambiado el nombre de ninguna columna). */
    private fun seedV112UsageBackfillRows(dbPath: String) {
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(dbPath), null)
        try {
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId) " +
                    "VALUES ('legacy-session', 'prueba de relleno', 1, 1, '', 'default')",
            )
            val rows = listOf(
                arrayOf<Any?>("msg-assistant-usage", "ASSISTANT", "Respuestas útiles", 1_000L, 120, 80, 30, 10),
                arrayOf<Any?>("msg-assistant-zero", "ASSISTANT", "Respuesta de uso cero", 2_000L, 0, 0, 0, 0),
                arrayOf<Any?>("msg-assistant-null", "ASSISTANT", "Respuesta antigua sin campo de token", 3_000L, null, null, null, null),
                arrayOf<Any?>("msg-user-usage", "USER", "Los mensajes de usuario también tienen campos de uso.", 4_000L, 500, 0, 0, 0),
                arrayOf<Any?>("msg-assistant-prompt-only", "ASSISTANT", "Introduzca sólo el uso", 5_000L, 7, 0, 0, 0),
            )
            rows.forEach { row ->
                db.execSQL(
                    "INSERT INTO messages (id, sessionId, role, content, createdAt, " +
                        "promptTokens, completionTokens, reasoningTokens, cachedTokens) " +
                        "VALUES (?, 'legacy-session', ?, ?, ?, ?, ?, ?, ?)",
                    row,
                )
            }
        } finally {
            db.close()
        }
    }

    /** afirmar 112→113 INSERT...SELECT solo rellena las filas que están rellenadas y los campos se asignan correctamente. */
    private fun assertUsageBackfill(db: SupportSQLiteDatabase) {
        val byCreatedAt = mutableMapOf<Long, List<Int>>()
        db.query(
            "SELECT created_at, source, model_id, provider_id, prompt_tokens, completion_tokens, " +
                "reasoning_tokens, cached_tokens, first_token_ms, duration_ms FROM usage_records",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                assertEquals("La fuente del registro de reposición debe ser chat", "chat", cursor.getString(1))
                assertEquals("El model_id del registro de reposición debe ser una cadena vacía (los mensajes antiguos no tienen ningún modelo registrado)", "", cursor.getString(2))
                assertEquals("El id_proveedor del registro de reposición debe ser una cadena vacía.", "", cursor.getString(3))
                assertEquals("El registro de relleno first_token_ms debe ser 0 (los datos antiguos no tienen este indicador)", 0, cursor.getInt(8))
                assertEquals("Duración_ms del registro de relleno debe ser 0 (los datos antiguos no tienen este indicador)", 0, cursor.getInt(9))
                byCreatedAt[cursor.getLong(0)] = listOf(
                    cursor.getInt(4),
                    cursor.getInt(5),
                    cursor.getInt(6),
                    cursor.getInt(7),
                )
            }
        }
        assertEquals(
            "Solo se deben reponer los mensajes útiles del asistente (uso cero)./NULL token/Los mensajes de usuario no se completan)",
            listOf(1_000L, 5_000L),
            byCreatedAt.keys.sorted(),
        )
        assertEquals("created_at Debe tomarse de mensajes.createdAt,token Las cuatro columnas deben llevarse como están.", listOf(120, 80, 30, 10), byCreatedAt[1_000L])
        assertEquals("Los mensajes del Asistente que solo se usan rápidamente también se deben reponer", listOf(7, 0, 0, 0), byCreatedAt[5_000L])
    }

    /** Afirme que el relleno no ha cambiado los mensajes: el número de filas permanece sin cambios, el contenido permanece sin cambios,token El campo no ha sido sobrescrito. */
    private fun assertMessagesSurvivedBackfill(db: SupportSQLiteDatabase) {
        val contents = mutableMapOf<String, String>()
        db.query("SELECT id, content FROM messages").use { cursor ->
            while (cursor.moveToNext()) contents[cursor.getString(0)] = cursor.getString(1)
        }
        assertEquals("messages El número de filas debe ser el mismo que antes de la migración (5 artículo)", 5, contents.size)
        assertEquals("Respuestas útiles", contents["msg-assistant-usage"])
        assertEquals("Respuesta de uso cero", contents["msg-assistant-zero"])
        assertEquals("Respuesta antigua sin campo de token", contents["msg-assistant-null"])
        assertEquals("Los mensajes de usuario también tienen campos de uso.", contents["msg-user-usage"])
        assertEquals("Introduzca sólo el uso", contents["msg-assistant-prompt-only"])
        db.query(
            "SELECT promptTokens, completionTokens, reasoningTokens, cachedTokens " +
                "FROM messages WHERE id='msg-assistant-usage'",
        ).use { cursor ->
            assertTrue("El mensaje original debe conservarse.", cursor.moveToFirst())
            assertEquals("El relleno no debe sobrescribirse messages.promptTokens", 120, cursor.getInt(0))
            assertEquals("El relleno no debe sobrescribirse messages.completionTokens", 80, cursor.getInt(1))
            assertEquals("El relleno no debe sobrescribirse messages.reasoningTokens", 30, cursor.getInt(2))
            assertEquals("El relleno no debe sobrescribirse messages.cachedTokens", 10, cursor.getInt(3))
        }
    }

    /**
     * Pruebas contundentes:Room Después de pasar la verificación, su identidad_hash se escribirá en room_master_table.
     * Si la estructura del resultado de la migración no coincide con la entidad,RoomOpenHelper.onValidateSchema Lo tiraré primero
     * "Migration didn't properly handle", no hay forma de escribir un hash.
     * Entonces "hash == Último (v122) el identidadHash de la instantánea" es equivalente a "Room Realmente comparé y aprobé el estado final".
     */
    private fun assertRoomValidatedLatestSchema(db: SupportSQLiteDatabase, tag: String) {
        val expected = loadSchema(122).getJSONObject("database").getString("identityHash")
        val actual = mutableListOf<String>()
        db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
            while (cursor.moveToNext()) actual += cursor.getString(0)
        }
        assertEquals(
            "$tag: Room El Identity_hash escrito debe ser igual al último (v122) Instantánea de la entidad (demuestra que Room ha verificado y reconocido el esquema final)",
            listOf(expected),
            actual,
        )
    }

    /** v2.6.x Se agregan afirmaciones unificadas para la nueva superficie (cuatro tablas nuevas + tres columnas) y la matriz se comparte con los casos de uso de cadena completa. */
    private fun assertV26xSurfaces(db: SupportSQLiteDatabase, tag: String) {
        assertUsageRecordsSurface(db, tag)
        assertRelationshipSurface(db, tag)
        assertSessionTodosSurface(db, tag)
        assertCardEntitiesSurface(db, tag)
        assertMomentSurfaces(db, tag)
        assertEquals(
            "$tag: sessions.input_draft El valor predeterminado es una cadena vacía (las sesiones de cambio mantienen borradores respectivamente)",
            "''",
            columnDefaults(db, "sessions")["input_draft"],
        )
    }

    private fun assertUsageRecordsSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "usage_records")
        assertEquals(
            "$tag: usage_records La colección de columnas debe ser coherente con UsageRecordEntity",
            setOf(
                "id",
                "created_at",
                "source",
                "model_id",
                "provider_id",
                "prompt_tokens",
                "completion_tokens",
                "reasoning_tokens",
                "cached_tokens",
                "cache_write_tokens",
                "first_token_ms",
                "duration_ms",
            ),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "model_id" to "''",
                "provider_id" to "''",
                "prompt_tokens" to "0",
                "completion_tokens" to "0",
                "reasoning_tokens" to "0",
                "cached_tokens" to "0",
                "cache_write_tokens" to "0",
                "first_token_ms" to "0",
                "duration_ms" to "0",
            ),
            tag,
        )
        assertEquals(
            "$tag: usage_records El índice debe ser coherente con la declaración @Index de la entidad",
            setOf(
                "index_usage_records_created_at",
                "index_usage_records_source_created_at",
                "index_usage_records_model_id_created_at",
            ),
            indexNames(db, "usage_records"),
        )
    }

    private fun assertRelationshipSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "assistant_relationship")
        assertEquals(
            "$tag: assistant_relationship El conjunto de columnas debe ser coherente con AssistantRelationshipEntity",
            setOf(
                "assistantId",
                "relationLabel",
                "affinity",
                "lastInteractionAt",
                "proactiveLevel",
                "proactiveEnabled",
                "dailyProactiveLimit",
                "createdAt",
                "updatedAt",
            ),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "relationLabel" to "''",
                "affinity" to "0",
                "lastInteractionAt" to "0",
                "proactiveLevel" to "'normal'",
                "proactiveEnabled" to "1",
                "dailyProactiveLimit" to "0",
                "createdAt" to "0",
                "updatedAt" to "0",
            ),
            tag,
        )
    }

    private fun assertSessionTodosSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "session_todos")
        assertEquals(
            "$tag: session_todos La colección de columnas debe ser coherente con SessionTodoEntity",
            setOf("id", "sessionId", "todosJson", "hasOpenItems", "createdAt", "updatedAt"),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "sessionId" to "''",
                "todosJson" to "''",
                "hasOpenItems" to "0",
                "createdAt" to "0",
                "updatedAt" to "0",
            ),
            tag,
        )
        assertTrue(
            "$tag: sessionId En realidad, debería existir un índice único. ${indexNames(db, "session_todos")}",
            "index_session_todos_sessionId" in indexNames(db, "session_todos"),
        )
    }

    /**
     * 118 Nuevas facetas:card_entities El conjunto de columnas / predeterminado / dos índices y el índice único de dockSlot realmente tienen efecto.
     *
     * Simplemente afirmar que "la tabla existe" no es suficiente: los nombres de columnas a la deriva o los valores predeterminados harán que la verificación de la sala de máquinas real falle durante las actualizaciones.
     * La unicidad de dockSlot determina directamente si el orden del área de acoplamiento es estable, por lo que aquí también se prueba el comportamiento de las restricciones.
     */
    private fun assertCardEntitiesSurface(db: SupportSQLiteDatabase, tag: String) {
        val defaults = columnDefaults(db, "card_entities")
        assertEquals(
            "$tag: card_entities El conjunto de columnas debe ser coherente con CardEntity",
            setOf(
                "id",
                "legacyCardId",
                "originSessionId",
                "originMessageId",
                "title",
                "code",
                "isSvg",
                "dataJson",
                "dockSlot",
                "dockedAt",
                "createdAt",
                "updatedAt",
            ),
            defaults.keys,
        )
        assertDefaults(
            defaults,
            mapOf(
                "legacyCardId" to "NULL",
                "originSessionId" to "NULL",
                "originMessageId" to "NULL",
                "title" to "''",
                "code" to "''",
                "isSvg" to "0",
                "dataJson" to "NULL",
                "dockSlot" to "NULL",
                "dockedAt" to "NULL",
                "createdAt" to "0",
                "updatedAt" to "0",
            ),
            tag,
        )
        assertEquals(
            "$tag: card_entities El índice debe ser coherente con la declaración CardEntity @Index",
            setOf("index_card_entities_dockSlot", "index_card_entities_originMessageId"),
            indexNames(db, "card_entities"),
        )
        assertDockSlotUniquenessAllowsNull(db, tag)
    }

    /**
     * dockSlot Dos comportamientos clave de índices únicos:
     *  - Varias tarjetas son NULL (solo flujo de mensajes) y no deben entrar en conflicto. —— De lo contrario, no se pueden escribir todas las cartas excepto la primera;
     *  - Se deben rechazar dos espacios idénticos que no sean NULL —— De lo contrario, la secuencia de atraque será doble.
     *
     * Verifique con filas temporales y elimínelas inmediatamente sin contaminar los datos migrados (las afirmaciones de datos antiguas aún se mantienen en otros lugares).
     */
    private fun assertDockSlotUniquenessAllowsNull(db: SupportSQLiteDatabase, tag: String) {
        db.execSQL("INSERT INTO card_entities (id, title) VALUES ('slot-probe-a', 'a')")
        try {
            db.execSQL("INSERT INTO card_entities (id, title) VALUES ('slot-probe-b', 'b')")
            db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'slot-probe-a'")
            val rejected = try {
                db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'slot-probe-b'")
                false
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                e.message?.let { assertTrue("$tag: Se espera que sea un conflicto de restricciones único, en realidad $it", it.contains("UNIQUE", ignoreCase = true)) }
                true
            }
            assertTrue("$tag: No se permiten dos tarjetas en el mismo dockSlot (de lo contrario la secuencia de acoplamiento será doble)", rejected)
        } finally {
            db.execSQL("DELETE FROM card_entities WHERE id IN ('slot-probe-a', 'slot-probe-b')")
        }
    }

    private fun assertMomentSurfaces(db: SupportSQLiteDatabase, tag: String) {
        assertEquals(
            "$tag: Momentos/Las cuatro tablas del diario deben estar en 121→122 Eliminado durante la migración (los datos antiguos del teléfono celular se eliminaron por completo)",
            emptySet<String>(),
            setOf("ai_moments", "ai_moment_comments", "ai_moment_likes", "ai_diaries")
                .filter { tableExists(db, it) }
                .toSet(),
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun columnDefaults(db: SupportSQLiteDatabase, table: String): Map<String, String?> {
        val defaults = mutableMapOf<String, String?>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) defaults[cursor.getString(1)] = cursor.getString(4)
        }
        return defaults
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='$table'").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                // Omite el índice interno generado automáticamente por SQLite para PRIMARY KEY/UNIQUE y solo compara el índice nombrado declarado por la entidad.
                if (!name.startsWith("sqlite_autoindex")) names += name
            }
        }
        return names
    }

    private fun assertDefaults(defaults: Map<String, String?>, expected: Map<String, String>, tag: String) {
        expected.forEach { (column, value) ->
            assertTrue("$tag: columna faltante $column, la columna real ${defaults.keys}", defaults.containsKey(column))
            assertEquals("$tag: $column El valor predeterminado debe ser $value", value, defaults[column])
        }
    }

    /**
     * Revisión de corrección (2.0 B-27): Cadena de migración temprana suma cobertura Robolectric — schemas/ El directorio realmente existe
     * 22..88 Todas las instantáneas de,Las SchemaVersions disponibles anteriormente devolvieron un valor nulo, lo que dio como resultado una anulación 22..54 a partir de cero.
     * (Contiene tabla de mensajes/skills/FTS Reconstrucción de segmentos de alto riesgo). Ahora habilitado por presencia de instantáneas 22..54;
     * 1..21 Sin instantánea(1→22 para una evolución general temprana),No se puede sobrescribir automáticamente,Todavía dejando la máquina real para regresar.
     */
    /**
     * Revisión de corrección (2.0 B-27): Cobertura de la cadena de migración temprana 22..54 —
     * Room Ruta de verificación(RoomOpenHelper.onValidateSchema)Artefactos de doble conectividad bajo Robolectric:
     * El índice recién creado dentro de la transacción de migración no es visible para la conexión de verificación(55+ El punto de partida no se ve afectado porque el índice está creado previamente a partir de la instantánea.),
     * Provocó que 22..54 puntos de partida fueran falsos positivos bajo Robolectric "Migration didn't properly handle".
     * La conexión única de la máquina real no tiene este problema(Verificación manual de la cadena paso a paso y retención de índices durante todo el proceso.).
     * Por lo tanto:
     *  - Esta matriz de verificación de habitaciones reserva más de 55 puntos de partida(El comportamiento de verificación es auténtico y confiable.);
     *  - 22..54 El punto de partida es [migrateEveryLegacyVersionManually] Cobertura de prueba de cadena manual(Equivalente a una máquina real).
     */
    private fun availableSchemaVersions(): List<Int> = ((55..68) + (113..117)).filter { schemaExists(it) }

    private val imageStorageDir: File get() = File(context.cacheDir, "muse_images_migration_test")

    private fun schemaExists(version: Int): Boolean {
        val candidates = listOf(
            File("schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("../app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
        )
        return candidates.any { it.exists() }
    }

    /** Recopile las migraciones registradas de MuseDb mediante la reflexión,Ordene por versión para obtener la cadena de actualización completa. */
    private fun migrationsFrom(fromVersion: Int, imageDir: File = imageStorageDir): List<androidx.room.migration.Migration> {
        // MIGRATION_* Es el valor del objeto complementario de MuseDb. Después de la compilación, Kotlin solo genera para ellos.
        // publico getter(getMIGRATION_x_y),La reflexión no puede obtener el campo correspondiente(Los campos de entidad están ocultos/síntesis),
        // Así que aquí enumeramos desde el método público de la instancia Companion —— MuseDb::class.java.declaredFields También está vacío.
        val companion: Any = runCatching {
            MuseDb::class.java.getDeclaredField("Companion")
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull() ?: MuseDb
        val chain = companion.javaClass.methods
            .filter { it.name.startsWith("getMIGRATION_") && it.parameterCount == 0 }
            .mapNotNull { method ->
                val parts = method.name.removePrefix("getMIGRATION_").split("_")
                val from = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val to = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (from < fromVersion) return@mapNotNull null
                method.isAccessible = true
                (from to to) to (method.invoke(companion) as androidx.room.migration.Migration)
            }
            .sortedBy { it.first.first }
            .map { it.second }
        val with75 = if (fromVersion <= 75) {
            chain + MuseDb.migrate75To76(imageDir)
        } else {
            chain
        }
        val with76 = if (fromVersion <= 76) {
            with75 + MuseDb.migrate76To77()
        } else {
            with75
        }
        return with76
    }

    private fun createSchemaAtVersion(version: Int, dbPath: String, stripIsLocked: Boolean = false) {
        val schema = loadSchema(version)
        val databaseJson = schema.getJSONObject("database")
        val entities = databaseJson.getJSONArray("entities")
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val tableName = entity.getString("tableName")
                            var createSql = entity.getString("createSql")
                                .replace("\${TABLE_NAME}", tableName)
                            if (stripIsLocked && tableName == "sessions") {
                                createSql = createSql.replace("`isLocked` INTEGER NOT NULL DEFAULT 0, ", "")
                            }
                            db.execSQL(createSql)
                            if (entity.has("indices")) {
                                val indices = entity.getJSONArray("indices")
                                for (j in 0 until indices.length()) {
                                    val index = indices.getJSONObject(j)
                                    db.execSQL(
                                        index.getString("createSql")
                                            .replace("\${TABLE_NAME}", tableName),
                                    )
                                }
                            }
                        }
                        val setup = databaseJson.getJSONArray("setupQueries")
                        for (i in 0 until setup.length()) {
                            db.execSQL(setup.getString(i))
                        }
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // Room migration hacerse cargo
                    }
                })
                .build(),
        ).writableDatabase
        helper.close()
    }

    private fun defaultValueForColumn(name: String, type: String): String = when {
        name.contains("Json", ignoreCase = true) || name.contains("Urls", ignoreCase = true) -> "'[]'"
        type.uppercase().contains("INT") -> "0"
        else -> "''"
    }

    /** Columna de mensajes NOT NULL sin valor predeterminado en el esquema anterior,Al insertar datos de prueba, complete el valor predeterminado por tipo. */
    private fun requiredExtraMessageColumns(helper: androidx.sqlite.db.SupportSQLiteDatabase): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val base = setOf("id", "sessionId", "role", "content", "createdAt")
        helper.query("PRAGMA table_info(messages)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val needsValue = name !in base && cursor.getInt(3) == 1 && cursor.isNull(4)
                if (needsValue) result += name to defaultValueForColumn(name, cursor.getString(2))
            }
        }
        return result
    }

    private fun insertLegacyRow(dbPath: String, fromVersion: Int) {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(fromVersion) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId)
            VALUES ('legacy-session', 'Pruebas de migración', 1, 1, '', 'default')
            """.trimIndent(),
        )
        val requiredExtra = requiredExtraMessageColumns(helper)
        val messageColumns = listOf("id", "sessionId", "role", "content", "createdAt") + requiredExtra.map { it.first }
        val messageValues = listOf(
            "'legacy-msg'", "'legacy-session'", "'ASSISTANT'", "'Mensajes históricos antes de la migración'", "1",
        ) + requiredExtra.map { it.second }
        helper.execSQL(
            "INSERT INTO messages (${messageColumns.joinToString(", ")}) " +
                "VALUES (${messageValues.joinToString(", ")})",
        )
        helper.close()
    }

    private fun loadSchema(version: Int): JSONObject {
        val candidates = listOf(
            File("schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
            File("../app/schemas/io.zer0.muse.data.session.MuseDb/$version.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("no encontrado $version.json schema, confirme que se hayan generado la aplicación/esquemas")
        return JSONObject(file.readText())
    }

    private fun Context.deleteDatabase(name: String) {
        runCatching { deleteDatabase(name) }
    }
}
