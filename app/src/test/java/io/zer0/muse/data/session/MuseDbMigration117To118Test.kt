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
 * v2.6.8: 117 → 118 Validación comportamental de migraciones (tabla de entidades de tarjetas).
 *
 * con [MuseDbMigration116To117Test] El mismo modo: use SQLite nativo para ejecutar y ejecutar directamente
 * [MuseDb.MIGRATION_117_118] Exactamente el mismo DDL, afirmando:
 *  - `card_entities` La tabla existe y las doce columnas están completas;
 *  - El valor predeterminado de la columna es el mismo que [io.zer0.muse.data.card.CardEntity] La declaración es consistente artículo por artículo (de lo contrario, la máquina real Room
 *    schema La suma de control será arrojada "Migration didn't properly handle: card_entities");
 *  - `dockSlot` Los índices únicos permiten NULL para cualquier número de filas (solo flujo de mensajes), pero rechazan espacios duplicados que no sean NULL;
 *  - `originMessageId` Existe un índice ordinario;
 *  - Los datos y las tablas vecinas existentes no se verán afectados (la migración pura de tablas nuevas no debería mover otras tablas).
 *
 * MigrationTestHelper que no depende de pruebas de sala;Room Active la verificación de rutas de un extremo a otro. Ver
 * [MuseDbMigrationTest.migrateV113To117Starts_validateThroughRoom].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MuseDbMigration117To118Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Creación de tablas + índice DDL que es textualmente consistente con MIGRATION_117_118. */
    private fun applyMigrationDdl(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `card_entities` (" +
                "`id` TEXT NOT NULL, " +
                "`legacyCardId` TEXT DEFAULT NULL, " +
                "`originSessionId` TEXT DEFAULT NULL, " +
                "`originMessageId` TEXT DEFAULT NULL, " +
                "`title` TEXT NOT NULL DEFAULT '', " +
                "`code` TEXT NOT NULL DEFAULT '', " +
                "`isSvg` INTEGER NOT NULL DEFAULT 0, " +
                "`dataJson` TEXT DEFAULT NULL, " +
                "`dockSlot` INTEGER DEFAULT NULL, " +
                "`dockedAt` INTEGER DEFAULT NULL, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_card_entities_dockSlot` ON `card_entities` (`dockSlot`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_card_entities_originMessageId` ON `card_entities` (`originMessageId`)",
        )
    }

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

    private fun openFresh(dbName: String): android.database.sqlite.SQLiteDatabase {
        context.deleteDatabase(dbName)
        return android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() },
            null,
        )
    }

    @Test
    fun migration117To118_createsCardEntitiesTableWithExpectedColumns() {
        val db = openFresh("migration_117_118_test.db")
        try {
            // 1. Restaure las tablas vecinas en v117 que serán "tocadas" por esta migración para confirmar que la migración no causará daños accidentales.
            db.execSQL(
                """
                CREATE TABLE session_todos (
                    id TEXT NOT NULL PRIMARY KEY,
                    sessionId TEXT NOT NULL DEFAULT '',
                    todosJson TEXT NOT NULL DEFAULT '',
                    hasOpenItems INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO session_todos (id, sessionId) VALUES ('t1', 's1')")
            db.version = 117

            // 2. Ejecutar exactamente igual que MIGRATION_117_118 DDL
            applyMigrationDdl(db)

            // 3. Colección de columnas/tipo/capacidad de nulidad/valor predeterminado
            assertCardEntityColumns(db)

            // 4. Existen dos índices y dockSlot es el único índice.
            assertCardEntityIndexes(db)

            // 5. Las tablas y los datos vecinos no se ven afectados
            db.rawQuery("SELECT sessionId FROM session_todos WHERE id = 't1'", null).use { c ->
                assertTrue("Se deben conservar las filas existentes", c.moveToFirst())
                assertEquals("s1", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    /** Colecciones de columnas, tipos y valores predeterminados elemento por especificación de elemento §3.1. */
    private fun assertCardEntityColumns(db: android.database.sqlite.SQLiteDatabase) {
        val cols = columnsOf(db, "card_entities")
        assertEquals(
            "El conjunto de columnas debe ser exactamente el mismo que CardEntity",
            listOf(
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
            cols.keys.toList(),
        )
        val expectedTypes = mapOf(
            "id" to "TEXT|notnull=1",
            "legacyCardId" to "TEXT|notnull=0",
            "originSessionId" to "TEXT|notnull=0",
            "originMessageId" to "TEXT|notnull=0",
            "title" to "TEXT|notnull=1",
            "code" to "TEXT|notnull=1",
            "isSvg" to "INTEGER|notnull=1",
            "dataJson" to "TEXT|notnull=0",
            "dockSlot" to "INTEGER|notnull=0",
            "dockedAt" to "INTEGER|notnull=0",
            "createdAt" to "INTEGER|notnull=1",
            "updatedAt" to "INTEGER|notnull=1",
        )
        expectedTypes.forEach { (column, prefix) ->
            assertTrue("$column Tipo/La nulidad debe ser $prefix, real ${cols.getValue(column)}", cols.getValue(column).startsWith(prefix))
        }
        assertTrue("title Cadena vacía predeterminada", cols.getValue("title").contains("default=''"))
        assertTrue("code Cadena vacía predeterminada", cols.getValue("code").contains("default=''"))
        assertTrue("isSvg Predeterminado 0", cols.getValue("isSvg").contains("default=0"))
        assertTrue("createdAt Predeterminado 0", cols.getValue("createdAt").contains("default=0"))
        assertTrue("updatedAt Predeterminado 0", cols.getValue("updatedAt").contains("default=0"))
        listOf("legacyCardId", "originSessionId", "originMessageId", "dataJson", "dockSlot", "dockedAt")
            .forEach { column ->
                // El DEFAULT NULL explícito aparece como una cadena en PRAGMA "NULL"(no NULL), coherente con el valor predeterminado de la instantánea de la habitación.
                assertEquals("$column El valor predeterminado debe ser NULL", "NULL", columnDefault(db, column))
            }
    }

    /** Ambos índices existen y tienen unicidad correcta (dockSlot solo,originMessageId Ordinario). */
    private fun assertCardEntityIndexes(db: android.database.sqlite.SQLiteDatabase) {
        val indexes = mutableMapOf<String, Boolean>()
        db.rawQuery("PRAGMA index_list(`card_entities`)", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                val unique = c.getInt(c.getColumnIndexOrThrow("unique")) == 1
                if (!name.startsWith("sqlite_autoindex")) indexes[name] = unique
            }
        }
        assertEquals(
            "La colección de índices debe ser coherente con la declaración de la entidad @Index.",
            setOf("index_card_entities_dockSlot", "index_card_entities_originMessageId"),
            indexes.keys,
        )
        assertEquals("dockSlot El índice debe ser único.", true, indexes["index_card_entities_dockSlot"])
        assertEquals("originMessageId El índice no es único (un mensaje puede contener varias tarjetas)", false, indexes["index_card_entities_originMessageId"])
    }

    private fun columnDefault(db: android.database.sqlite.SQLiteDatabase, column: String): String? {
        var dflt: String? = null
        db.rawQuery("PRAGMA table_info(`card_entities`)", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == column) {
                    dflt = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                }
            }
        }
        return dflt
    }

    @Test
    fun uniqueDockSlotIndexAllowsManyNullsButRejectsDuplicateSlots() {
        val db = openFresh("migration_117_118_unique.db")
        try {
            applyMigrationDdl(db)

            // Tarjeta de desacoplamiento (dockSlot = NULL) puedes tener tantas imágenes como quieras —— Los índices únicos no participan en comparaciones NULL
            repeat(5) { index ->
                db.execSQL("INSERT INTO card_entities (id, title) VALUES ('inline-$index', 'tarjeta $index')")
            }
            db.rawQuery("SELECT COUNT(*) FROM card_entities WHERE dockSlot IS NULL", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Deben poder coexistir varias tarjetas exclusivas de mensajería", 5, c.getInt(0))
            }

            // Las plazas idénticas deben rechazarse
            db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'inline-0'")
            val threw = try {
                db.execSQL("UPDATE card_entities SET dockSlot = 0 WHERE id = 'inline-1'")
                false
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                e.message?.let { assertTrue("Conflicto de restricción único esperado", it.contains("UNIQUE", ignoreCase = true)) }
                true
            }
            assertTrue("No se permiten dos tarjetas en el mismo dockSlot (de lo contrario la secuencia de acoplamiento será doble)", threw)

            // Normalmente se escriben diferentes espacios y el orden de lectura en orden ascendente de los espacios es estable.
            db.execSQL("UPDATE card_entities SET dockSlot = 2 WHERE id = 'inline-2'")
            db.execSQL("UPDATE card_entities SET dockSlot = 1 WHERE id = 'inline-3'")
            val order = mutableListOf<String>()
            db.rawQuery(
                "SELECT id FROM card_entities WHERE dockSlot IS NOT NULL ORDER BY dockSlot ASC",
                null,
            ).use { c ->
                while (c.moveToNext()) order += c.getString(0)
            }
            assertEquals("Las tarjetas Dock deben ordenarse en orden ascendente por ranura.", listOf("inline-0", "inline-3", "inline-2"), order)

            // Después de borrar la ranura, regrese a "Solo flujo de mensajes" y la fila seguirá
            db.execSQL("UPDATE card_entities SET dockSlot = NULL WHERE id = 'inline-0'")
            db.rawQuery("SELECT title FROM card_entities WHERE id = 'inline-0'", null).use { c ->
                assertTrue("Arrastrar hacia atrás solo borra la ranura y no elimina la fila.", c.moveToFirst())
                assertNotNull(c.getString(0))
            }
        } finally {
            db.close()
        }
    }
}
