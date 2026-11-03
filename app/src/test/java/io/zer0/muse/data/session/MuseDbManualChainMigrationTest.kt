package io.zer0.muse.data.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Revisión de corrección (2.0 B-27): Manual de cobertura paso a paso de la cadena de migración temprana 22..54.
 *
 * Room Ruta de verificación(RoomOpenHelper.onValidateSchema)Artefactos de doble conectividad bajo Robolectric:
 * El índice recién creado dentro de la transacción de migración no es visible para la conexión de verificación,Provocó que 22..54 puntos de partida fueran falsos positivos bajo Robolectric
 * "Migration didn't properly handle: scheduled_tasks"(55+ El índice del punto de partida está prediseñado para la instantánea.,
 * no afectado;La conexión única de la máquina real no tiene este problema). Esta prueba utiliza SupportSQLiteOpenHelper nativo para realizar manualmente paso a paso
 * Ejecute exactamente la misma cadena de migración que Room(Contiene la migración de funciones 75_76/76_77/88_89),Después de la migración de aserción:
 *  - messages Contiene todas las columnas clave actuales(parentGroupId / videoFileUri / moodSkin espera);
 *  - messages.imageUrlsJson tener DEFAULT '[]'(29_30/88_89 Objetivo de reparación);
 *  - scheduled_tasks índice index_scheduled_tasks_enabled_next_run_at existir(88_89 Objetivo de reparación);
 *  - Se conservan los datos históricos de las filas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbManualChainMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val legacyVersions: List<Int> = (22..54).filter { version ->
        File("schemas/io.zer0.muse.data.session.MuseDb/$version.json").exists()
    }

    @Test
    fun legacyChainMigration_keepsColumnsIndexesAndData() {
        for (fromVersion in legacyVersions) {
            migrateChainManually(fromVersion)
        }
    }

    /**
     * Recorra la cadena de migración completa y afirme el estado final.
     *
     * la función es más larga/La alta complejidad se debe al escenario de prueba.:Necesidad de replicar linealmente el cronograma de migración de Room
     * (Contiene migración de funciones 75_76/76_77/88_89 y paquete de transacciones),y afirmar para cada versión de punto de partida
     * columna/índice/Valor predeterminado/Retención de datos en cuatro estados finales;En su lugar, la división introduce una capa de indirección,Por tanto, queda exento en su conjunto.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun migrateChainManually(fromVersion: Int) {
        val dbFile = File(context.cacheDir, "manual_chain_$fromVersion.db").apply { if (exists()) delete() }
        val json = org.json.JSONObject(
            File("schemas/io.zer0.muse.data.session.MuseDb/$fromVersion.json").readText(),
        )
        val entities = json.getJSONObject("database").getJSONArray("entities")
        val factory = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(fromVersion) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val e = entities.getJSONObject(i)
                            val table = e.getString("tableName")
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                            if (e.has("indices")) {
                                val idx = e.getJSONArray("indices")
                                for (j in 0 until idx.length()) {
                                    val indexSql = idx.getJSONObject(j).getString("createSql")
                                        .replace("\${TABLE_NAME}", table)
                                    db.execSQL(indexSql)
                                }
                            }
                        }
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

                    override fun onDowngrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        // Insertar un mensaje histórico,Verifique que los datos se conserven en todas las migraciones.
        // instantánea temprana(22-54)La tabla de mensajes tiene una columna que NO es NULA y no tiene ningún valor predeterminado.(Como por ejemplo imageUrlsJson),
        // Finalización dinámica por PRAGMA,Evite violaciones de restricciones.
        val provided = setOf("id", "sessionId", "role", "content", "createdAt")
        val requiredNotNull = mutableListOf<Pair<String, String>>()
        db.query("PRAGMA table_info(messages)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val notNull = c.getInt(3) == 1
                val dflt = c.getString(4)
                if (notNull && dflt == null && name !in provided) {
                    requiredNotNull.add(name to if (c.getString(2).contains("INT")) "0" else "''")
                }
            }
        }
        db.execSQL(
            "INSERT INTO sessions (id, title, createdAt, updatedAt, lastMessagePreview, assistantId) " +
                "VALUES ('legacy-session', 'Pruebas de migración', 1, 1, '', 'default')",
        )
        val extraCols = requiredNotNull.joinToString("") { ", " + it.first }
        val extraVals = requiredNotNull.joinToString("") { ", " + it.second }
        db.execSQL(
            "INSERT INTO messages (id, sessionId, role, content, createdAt$extraCols) " +
                "VALUES ('legacy-msg', 'legacy-session', 'ASSISTANT', 'Mensajes históricos antes de la migración', 1$extraVals)",
        )

        // Recoge una cadena de migración que sea exactamente igual a Room(constante + función anexar)
        val migrations = MuseDb::class.java.declaredFields
            .filter { it.name.startsWith("MIGRATION_") }
            .mapNotNull { f ->
                val parts = f.name.removePrefix("MIGRATION_").split("_")
                val from = parts[0].toIntOrNull() ?: return@mapNotNull null
                val to = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (from < fromVersion) return@mapNotNull null
                f.isAccessible = true
                (from to to) to (f.get(null) as androidx.room.migration.Migration)
            }
            .sortedBy { it.first.first }
            .map { it.second }
            .let { chain ->
                val with75 = if (fromVersion <= 75) {
                    chain + MuseDb.migrate75To76(File(context.cacheDir, "muse_images"))
                } else {
                    chain
                }
                if (fromVersion <= 76) with75 + MuseDb.migrate76To77() else with75
            }

        db.execSQL("PRAGMA foreign_keys=OFF")
        db.beginTransaction()
        try {
            var current = fromVersion
            for (m in migrations) {
                if (m.startVersion < current) continue
                m.migrate(db)
                current = m.endVersion
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("PRAGMA foreign_keys=ON")

        // ── afirmar: El estado post-migración es consistente con la Entidad actual ──
        val msgColumns = mutableSetOf<String>()
        db.query("PRAGMA table_info(messages)").use { c ->
            while (c.moveToNext()) msgColumns.add(c.getString(1))
        }
        assertTrue("v$fromVersion Debería haber una columna parentGroupId después de la migración", "parentGroupId" in msgColumns)
        assertTrue("v$fromVersion Debería haber una columna videoFileUri después de la migración", "videoFileUri" in msgColumns)
        assertTrue("v$fromVersion Debería haber una columna moodSkin después de la migración.", "moodSkin" in msgColumns)
        assertTrue("v$fromVersion Debería haber una columna toolCallInfoJson después de la migración", "toolCallInfoJson" in msgColumns)
        // v92: seq La columna debe existir(Migrar AÑADIR COLUMNA + relleno)
        assertTrue("v$fromVersion Debería haber una columna secundaria después de la migración.", "seq" in msgColumns)
        // v92: seq El nombre del índice debe ser coherente con la anotación.(idx_messages_sessionId_seq)—
        // Nombre del índice de verificación del esquema de la sala de máquinas real,La inconsistencia conducirá al colapso(Migration didn't properly handle: messages)
        val messageIndexes = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'").use { c ->
            while (c.moveToNext()) messageIndexes.add(c.getString(0))
        }
        assertTrue("v$fromVersion Debería haber un índice idx_messages_sessionId_seq después de la migración", "idx_messages_sessionId_seq" in messageIndexes)

        var imageUrlsDefault = ""
        db.query("PRAGMA table_info(messages)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "imageUrlsJson") imageUrlsDefault = c.getString(4)?.trim('\'') ?: ""
            }
        }
        assertEquals("v$fromVersion Después de la migración, imageUrlsJson debería tener DEFAULT '[]'(B-27 Corrección de deriva)", "[]", imageUrlsDefault)

        val scheduledIndexes = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='scheduled_tasks'").use { c ->
            while (c.moveToNext()) scheduledIndexes.add(c.getString(0))
        }
        assertTrue(
            "v$fromVersion Después de la migración, getDueTasks debe indexar las tareas programadas.(B-27 Reparación)",
            "index_scheduled_tasks_enabled_next_run_at" in scheduledIndexes,
        )

        val msgIndexes = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'").use { c ->
            while (c.moveToNext()) msgIndexes.add(c.getString(0))
        }
        val expectedMsgIndexes = listOf(
            "index_messages_sessionId",
            "index_messages_role",
            "index_messages_sessionId_createdAt",
            "index_messages_sessionId_createdAt_role",
        )
        assertTrue(
            "v$fromVersion Después de la migración, los mensajes deben conservar 4 índices comerciales.",
            msgIndexes.containsAll(expectedMsgIndexes),
        )

        db.query("SELECT COUNT(*) FROM messages WHERE id='legacy-msg'").use { c ->
            assertTrue("v$fromVersion Los mensajes históricos deben conservarse después de la migración", c.moveToFirst() && c.getInt(0) == 1)
        }
        helper.close()
        dbFile.delete()
    }
}
