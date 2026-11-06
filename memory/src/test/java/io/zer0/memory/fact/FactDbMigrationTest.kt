package io.zer0.memory.fact

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Phase 2.2: FactDb Ejemplo de prueba de migración.
 *
 * verificar [FactDb.MIGRATION_7_8] comportamiento:
 *  - Después de la migración de datos históricos, el campo de alcance por defecto es "main"(compatible con versiones anteriores)
 *  - scope El índice existe
 *  - El contenido de la columna existente se conserva intacto después de la migración.
 *
 * Al avanzar a v9+,Puedes agregar este archivo migrate8To9() Casos de uso.
 *
 * Modo de operación: `./gradlew :memory:testDebugUnitTest --tests "*FactDbMigrationTest*"`
 *
 * Instrucciones de implementación (2026-07-28 Reparación):
 *  MigrationTestHelper original lanzado en la habitación 2.8 + Robolectric IllegalArgumentException
 *  ("driver configured to open 'X' but 'path/X' was requested",issue 325404127).
 *  Utilice FrameworkSQLiteOpenHelper directo en su lugar para crear una base de datos de esquema v7,luego pasa
 *  Room.databaseBuilder + addMigrations Desencadene la migración y valide los datos.
 *
 *  v7 schema alineación estricta memory/schemas/io.zer0.memory.fact.FactDb/7.json:
 *  - id INTEGER PRIMARY KEY AUTOINCREMENT
 *  - tags / importance / category / confidence / source / last_hit_at cinturón uniforme DEFAULT
 *  - Nombre del índice: idx_facts_time / idx_facts_session / idx_facts_importance / idx_facts_category
 *
 * R-DOC-01: Probar la base de datos en su lugar Files.createTempDirectory,Evite filtrar Facts_test.db en el repositorio raíz cwd.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33]) // Robolectric: Implementación de SQLite usando API 33,compatibles minSdk 26
class FactDbMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun newDbFile(): File {
        val dir = Files.createTempDirectory("facts-migration").toFile()
        tempDirs += dir
        return File(dir, DB_NAME)
    }

    /**
     * v7 schema de CREATE TABLE — Alinee estrictamente createSql en 7.json.
     * Nota:id si AUTOINCREMENT,tags/importance/category/confidence/source/last_hit_at Todos tienen PREDETERMINADO.
     */
    private val V7_CREATE_FACTS = """
        CREATE TABLE IF NOT EXISTS `facts` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `fact` TEXT NOT NULL,
            `tags` TEXT NOT NULL DEFAULT '[]',
            `time` TEXT,
            `session_id` TEXT,
            `created_at` TEXT NOT NULL,
            `importance` INTEGER NOT NULL DEFAULT 0,
            `category` TEXT NOT NULL DEFAULT 'general',
            `confidence` REAL NOT NULL DEFAULT 1.0,
            `source` TEXT NOT NULL DEFAULT 'inferred',
            `expires_at` TEXT,
            `last_confirmed_at` TEXT,
            `last_hit_at` TEXT DEFAULT NULL
        )
    """.trimIndent()

    /**
     * v7 schema La mesa FTS4 — Alineación 7.json:
     *   CREATE VIRTUAL TABLE ... USING FTS4(`fact_id` INTEGER NOT NULL, `content_ngram` TEXT NOT NULL)
     */
    private val V7_CREATE_FTS = """
        CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(
            `fact_id` INTEGER NOT NULL,
            `content_ngram` TEXT NOT NULL
        )
    """.trimIndent()

    /**
     * v7 schema 4 índices de — Alinee el campo de índices en 7.json.
     */
    private val V7_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_facts_time` ON `facts` (`time`)",
        "CREATE INDEX IF NOT EXISTS `idx_facts_session` ON `facts` (`session_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_facts_importance` ON `facts` (`importance`)",
        "CREATE INDEX IF NOT EXISTS `idx_facts_category` ON `facts` (`category`)",
    )

    @Test
    fun migrate7To8_addsScopeColumnWithDefaultMain() {
        val dbFile = newDbFile()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V7_CREATE_FACTS)
                        db.execSQL(V7_CREATE_FTS)
                        V7_INDICES.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        // Asumido por la migración de habitaciones
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at
            ) VALUES (
                1, 'Modo oscuro de preferencia del usuario', '["preference"]', NULL, NULL, '2026-07-28T00:00:00Z',
                1, 'preference', 0.9, 'user_explicit',
                NULL, NULL, NULL
            )
            """.trimIndent(),
        )
        helper.close()

        val db = Room.databaseBuilder(
            context,
            FactDb::class.java,
            dbFile.absolutePath,
        )
            // v1.0.56: completo 7→10 cadena migratoria(Room ruta completa requerida;Anteriormente solo registrado 7_8,
            //   Una vez creada la base de datos de prueba, el requisito real es migrar a la versión actual. 10;v1.0.62 continuar 11)
            // v12: continuar 12(Columna de clave de normalización de entidad);v13: continuar 13(registro de revisión)
            .addMigrations(
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
                FactDb.MIGRATION_11_12,
                FactDb.MIGRATION_12_13,
                FactDb.MIGRATION_13_14,
                FactDb.MIGRATION_14_15,
                FactDb.MIGRATION_15_16,
                FactDb.MIGRATION_16_17,
                FactDb.MIGRATION_17_18,
                FactDb.MIGRATION_18_19,
            )
            .allowMainThreadQueries()
            .build()

        db.openHelper.writableDatabase.query("SELECT scope FROM facts WHERE id = 1").use { cursor ->
            assertTrue("Espere consultar 1 fila de datos históricos después de la migración", cursor.moveToFirst())
            assertEquals("El alcance de los hechos históricos debería por defecto ser main", "main", cursor.getString(0))
            assertTrue("Solo debe haber 1 fila", cursor.isLast)
        }

        db.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='index' AND name='index_facts_scope'")
            .use { cursor ->
                assertTrue("index_facts_scope El índice debería existir", cursor.moveToFirst())
            }

        db.close()
    }

    @Test
    fun migrate7To8_preservesExistingColumns() {
        val dbFile = newDbFile()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V7_CREATE_FACTS)
                        db.execSQL(V7_CREATE_FTS)
                        V7_INDICES.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at
            ) VALUES (
                42, 'cumpleaños: 1990-05-20', '["identity","event"]', '1990-05-20T00:00:00Z',
                'session-abc', '2026-07-28T10:00:00Z',
                2, 'identity', 1.0, 'user_explicit',
                '2027-07-28T00:00:00Z', '2026-07-28T11:00:00Z', '2026-07-28T12:00:00Z'
            )
            """.trimIndent(),
        )
        helper.close()

        val db = Room.databaseBuilder(
            context,
            FactDb::class.java,
            dbFile.absolutePath,
        )
            // v1.0.56: completo 7→10 cadena migratoria(Room ruta completa requerida)
            // v12: continuar 12(Columna de clave de normalización de entidad);v13: continuar 13(registro de revisión)
            .addMigrations(
                FactDb.MIGRATION_7_8,
                FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10,
                FactDb.MIGRATION_10_11,
                FactDb.MIGRATION_11_12,
                FactDb.MIGRATION_12_13,
                FactDb.MIGRATION_13_14,
                FactDb.MIGRATION_14_15,
                FactDb.MIGRATION_15_16,
                FactDb.MIGRATION_16_17,
                FactDb.MIGRATION_17_18,
                FactDb.MIGRATION_18_19,
            )
            .allowMainThreadQueries()
            .build()

        db.openHelper.writableDatabase.query(
            """
            SELECT fact, importance, category, confidence, source, expires_at, last_confirmed_at, last_hit_at, scope
            FROM facts WHERE id = 42
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("cumpleaños: 1990-05-20", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals("identity", cursor.getString(2))
            assertEquals(1.0f, cursor.getFloat(3), 0.001f)
            assertEquals("user_explicit", cursor.getString(4))
            assertEquals("2027-07-28T00:00:00Z", cursor.getString(5))
            assertEquals("2026-07-28T11:00:00Z", cursor.getString(6))
            assertEquals("2026-07-28T12:00:00Z", cursor.getString(7))
            assertEquals("main", cursor.getString(8)) // Agregar nuevo campo,Valor predeterminado
        }

        db.close()
    }

    /**
     * v12: Los usuarios antiguos actualizan desde v11 — entity_key Columna agregada,Los datos históricos se mantienen intactos.
     * Después de que el usuario instala la nueva versión, los datos de la memoria deben ser normales: Los datos existentes no se pierden, los campos permanecen sin cambios y las columnas nuevas pueden dejarse vacías.
     */
    @Test
    fun migrate11To12_addsEntityKeyColumnPreservingData() {
        val dbFile = newDbFile()
        val factory = FrameworkSQLiteOpenHelperFactory()
        // v11 schema: facts + fts + Todas las columnas antiguas(pinned_at finalmente)
        val V11_CREATE_FACTS = """
            CREATE TABLE IF NOT EXISTS `facts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fact` TEXT NOT NULL,
                `tags` TEXT NOT NULL DEFAULT '[]',
                `time` TEXT,
                `session_id` TEXT,
                `created_at` TEXT NOT NULL,
                `importance` INTEGER NOT NULL DEFAULT 0,
                `category` TEXT NOT NULL DEFAULT 'general',
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `source` TEXT NOT NULL DEFAULT 'inferred',
                `expires_at` TEXT,
                `last_confirmed_at` TEXT,
                `last_hit_at` TEXT DEFAULT NULL,
                `scope` TEXT NOT NULL DEFAULT 'main',
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `pinned_at` TEXT DEFAULT NULL
            )
        """.trimIndent()
        val V11_CREATE_FTS = """
            CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(
                `fact_id` INTEGER NOT NULL,
                `content_ngram` TEXT NOT NULL
            )
        """.trimIndent()
        val V11_CREATE_SPACES = """
            CREATE TABLE IF NOT EXISTS `memory_spaces` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `icon` TEXT,
                `description` TEXT NOT NULL DEFAULT '',
                `created_at` TEXT NOT NULL,
                `sort_index` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        val V11_CREATE_LINKS = """
            CREATE TABLE IF NOT EXISTS `memory_links` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `source_fact_id` INTEGER NOT NULL,
                `target_fact_id` INTEGER NOT NULL,
                `source_title` TEXT NOT NULL,
                `target_title` TEXT NOT NULL,
                `link_type` TEXT NOT NULL DEFAULT 'related_to',
                `weight` REAL NOT NULL DEFAULT 0.5,
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `scope` TEXT NOT NULL DEFAULT 'main',
                `created_at` TEXT NOT NULL
            )
        """.trimIndent()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V11_CREATE_FACTS)
                        db.execSQL(V11_CREATE_FTS)
                        db.execSQL(V11_CREATE_SPACES)
                        db.execSQL(V11_CREATE_LINKS)
                        // v9 índice
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_scope` ON `facts` (`scope`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_space_id` ON `facts` (`space_id`)")
                        // v7 índice — El esquema completo debe contener,De lo contrario, la verificación de la sala falla después de la migración.
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_time` ON `facts` (`time`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_session` ON `facts` (`session_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_importance` ON `facts` (`importance`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_category` ON `facts` (`category`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_spaces_sort` ON `memory_spaces` (`sort_index`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_source` ON `memory_links` (`source_fact_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_target` ON `memory_links` (`target_fact_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_space` ON `memory_links` (`space_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_scope` ON `memory_links` (`scope`)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        // El usuario antiguo simulado ya tiene 2 recuerdos.(Contiene fijado)con 1 índice FTS
        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at,
                scope, space_id, pinned_at
            ) VALUES (
                1, 'A los usuarios les encanta el modo oscuro', '["preference"]', NULL, NULL, '2026-08-01T00:00:00Z',
                1, 'preference', 0.9, 'user_explicit',
                NULL, NULL, '2026-08-10T00:00:00Z',
                'main', 'default', '2026-08-15T00:00:00Z'
            )
            """.trimIndent(),
        )
        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at,
                scope, space_id, pinned_at
            ) VALUES (
                2, 'El primer borrador del artículo deberá entregarse el próximo miércoles.', '["estudios","planear"]', '2026-08-26T09:00:00Z', 'sess-x', '2026-08-18T10:00:00Z',
                1, 'goal', 1.0, 'user_explicit',
                '2026-08-27T00:00:00Z', NULL, NULL,
                'main', 'work', NULL
            )
            """.trimIndent(),
        )
        helper.execSQL(
            "INSERT INTO facts_fts(fact_id, content_ngram) VALUES (1, 'A los usuarios les gusta el modo oscuro'), (2, 'El miércoles de la próxima semana, se entregará el primer borrador del artículo.')",
        )
        helper.close()

        // gatillo 11→12 Migrar
        val db = Room.databaseBuilder(context, FactDb::class.java, dbFile.absolutePath)
            .addMigrations(
                FactDb.MIGRATION_11_12,
                FactDb.MIGRATION_12_13,
                FactDb.MIGRATION_13_14,
                FactDb.MIGRATION_14_15,
                FactDb.MIGRATION_15_16,
                FactDb.MIGRATION_16_17,
                FactDb.MIGRATION_17_18,
                FactDb.MIGRATION_18_19,
            )
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase

        // 1. entity_key La columna existe
        db.openHelper.writableDatabase.query("PRAGMA table_info(facts)").use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(1))
            assertTrue("entity_key La columna debería existir", "entity_key" in columns)
        }

        // 2. Mantenga intactos los datos históricos(Contiene scope/space_id/pinned_at/expires_at/last_hit_at)
        db.openHelper.writableDatabase.query(
            """
            SELECT fact, importance, scope, space_id, pinned_at, expires_at, last_hit_at, entity_key
            FROM facts WHERE id = 1
            """.trimIndent(),
        ).use { cursor ->
            assertTrue("Los datos antiguos deben conservarse.", cursor.moveToFirst())
            assertEquals("A los usuarios les encanta el modo oscuro", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("main", cursor.getString(2))
            assertEquals("default", cursor.getString(3))
            assertEquals("2026-08-15T00:00:00Z", cursor.getString(4))
            assertNull("expires_at para NULL", cursor.getString(5))
            assertEquals("2026-08-10T00:00:00Z", cursor.getString(6))
            assertNull("La antigua clave_entidad de datos es NULL(Para completar con tareas de reflexión.)", cursor.getString(7))
        }

        // 3. Artículo 2(work espacio)También reservado
        db.openHelper.writableDatabase.query("SELECT space_id, expires_at FROM facts WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("work", cursor.getString(0))
            assertEquals("2026-08-27T00:00:00Z", cursor.getString(1))
        }

        // 4. Se ha creado el índice idx_facts_entity_key
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_facts_entity_key'",
        ).use { cursor ->
            assertTrue("idx_facts_entity_key El índice debería existir", cursor.moveToFirst())
        }

        // 5. v13: fact_revisions La tabla ha sido creada.(mesa vacia)
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='fact_revisions'",
        ).use { cursor ->
            assertTrue("fact_revisions la tabla debería existir", cursor.moveToFirst())
        }

        db.close()
    }

    /**
     * v13: Los usuarios antiguos actualizan desde v12 — fact_revisions mesa nueva,Los datos de los hechos originales están intactos.
     */
    @Test
    fun migrate12To13_addsRevisionsTablePreservingFacts() {
        val dbFile = newDbFile()
        val factory = FrameworkSQLiteOpenHelperFactory()
        // v12 schema: facts(Contiene entity_key) + fts + spaces + links
        val V12_CREATE_FACTS = """
            CREATE TABLE IF NOT EXISTS `facts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fact` TEXT NOT NULL,
                `tags` TEXT NOT NULL DEFAULT '[]',
                `time` TEXT,
                `session_id` TEXT,
                `created_at` TEXT NOT NULL,
                `importance` INTEGER NOT NULL DEFAULT 0,
                `category` TEXT NOT NULL DEFAULT 'general',
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `source` TEXT NOT NULL DEFAULT 'inferred',
                `expires_at` TEXT,
                `last_confirmed_at` TEXT,
                `last_hit_at` TEXT DEFAULT NULL,
                `scope` TEXT NOT NULL DEFAULT 'main',
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `pinned_at` TEXT DEFAULT NULL,
                `entity_key` TEXT
            )
        """.trimIndent()
        val V12_CREATE_FTS = """
            CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(
                `fact_id` INTEGER NOT NULL,
                `content_ngram` TEXT NOT NULL
            )
        """.trimIndent()
        val V12_CREATE_SPACES = """
            CREATE TABLE IF NOT EXISTS `memory_spaces` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `icon` TEXT,
                `description` TEXT NOT NULL DEFAULT '',
                `created_at` TEXT NOT NULL,
                `sort_index` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        val V12_CREATE_LINKS = """
            CREATE TABLE IF NOT EXISTS `memory_links` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `source_fact_id` INTEGER NOT NULL,
                `target_fact_id` INTEGER NOT NULL,
                `source_title` TEXT NOT NULL,
                `target_title` TEXT NOT NULL,
                `link_type` TEXT NOT NULL DEFAULT 'related_to',
                `weight` REAL NOT NULL DEFAULT 0.5,
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `scope` TEXT NOT NULL DEFAULT 'main',
                `created_at` TEXT NOT NULL
            )
        """.trimIndent()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V12_CREATE_FACTS)
                        db.execSQL(V12_CREATE_FTS)
                        db.execSQL(V12_CREATE_SPACES)
                        db.execSQL(V12_CREATE_LINKS)
                        // v12 schema índice completo — Índice de los 7 hechos + índice de tabla de asociaciones,De lo contrario, la verificación de la sala falla después de la migración.
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_time` ON `facts` (`time`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_session` ON `facts` (`session_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_importance` ON `facts` (`importance`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_category` ON `facts` (`category`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_scope` ON `facts` (`scope`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_space_id` ON `facts` (`space_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_entity_key` ON `facts` (`entity_key`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_spaces_sort` ON `memory_spaces` (`sort_index`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_source` ON `memory_links` (`source_fact_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_target` ON `memory_links` (`target_fact_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_space` ON `memory_links` (`space_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_memory_links_scope` ON `memory_links` (`scope`)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (
                id, fact, tags, time, session_id, created_at,
                importance, category, confidence, source,
                expires_at, last_confirmed_at, last_hit_at,
                scope, space_id, pinned_at, entity_key
            ) VALUES (
                1, 'A Zhang San le gusta la fotografía', '["preference"]', NULL, NULL, '2026-08-01T00:00:00Z',
                1, 'preference', 0.9, 'user_explicit',
                NULL, NULL, '2026-08-10T00:00:00Z',
                'main', 'default', NULL, 'Zhang San'
            )
            """.trimIndent(),
        )
        helper.close()

        val db = Room.databaseBuilder(context, FactDb::class.java, dbFile.absolutePath)
            .addMigrations(
                FactDb.MIGRATION_12_13,
                FactDb.MIGRATION_13_14,
                FactDb.MIGRATION_14_15,
                FactDb.MIGRATION_15_16,
                FactDb.MIGRATION_16_17,
                FactDb.MIGRATION_17_18,
                FactDb.MIGRATION_18_19,
            )
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase

        // fact_revisions la mesa existe
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='fact_revisions'",
        ).use { cursor ->
            assertTrue("fact_revisions la tabla debería existir", cursor.moveToFirst())
        }

        // Los datos de hechos originales no tienen pérdidas.(Contiene entity_key)
        db.openHelper.writableDatabase.query(
            "SELECT fact, importance, entity_key FROM facts WHERE id = 1",
        ).use { cursor ->
            assertTrue("facts Los datos deben conservarse", cursor.moveToFirst())
            assertEquals("A Zhang San le gusta la fotografía", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals("Zhang San", cursor.getString(2))
        }

        db.close()
    }

    /**
     * I-AUDIT: suplemento FactDb 3→7 regresión migratoria(Anteriormente solo estaba cubierto 7→8/11→12/12→13).
     * v3 solo 6 columnas(Ninguno importance/category/confidence/source/expires_at/last_hit_at/last_confirmed_at)+ Ninguno FTS,
     * Migrar completamente a la versión actual debería:Completar nuevas columnas,FTS La creación de tablas, los datos históricos no tienen pérdidas y los valores predeterminados son correctos.
     */
    @Test
    fun migrateFromV3_addsColumnsAndFtsPreservingData() {
        val dbFile = newDbFile()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val v3CreateFacts = """
            CREATE TABLE IF NOT EXISTS `facts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fact` TEXT NOT NULL,
                `tags` TEXT NOT NULL DEFAULT '[]',
                `time` TEXT,
                `session_id` TEXT,
                `created_at` TEXT NOT NULL
            )
        """.trimIndent()
        val helper = factory.create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(v3CreateFacts)
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_time` ON `facts` (`time`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_facts_session` ON `facts` (`session_id`)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            "INSERT INTO facts (id, fact, tags, time, session_id, created_at) " +
                "VALUES (1, 'Me encanta la comida picante', '[\"Preferencia\"]', NULL, NULL, '2026-01-01T00:00:00Z')",
        )
        helper.close()

        val db = Room.databaseBuilder(context, FactDb::class.java, dbFile.absolutePath)
            .addMigrations(
                FactDb.MIGRATION_3_4, FactDb.MIGRATION_4_5, FactDb.MIGRATION_5_6,
                FactDb.MIGRATION_6_7, FactDb.MIGRATION_7_8, FactDb.MIGRATION_8_9,
                FactDb.MIGRATION_9_10, FactDb.MIGRATION_10_11, FactDb.MIGRATION_11_12, FactDb.MIGRATION_12_13,
                FactDb.MIGRATION_13_14,
                FactDb.MIGRATION_14_15,
                FactDb.MIGRATION_15_16,
                FactDb.MIGRATION_16_17,
                FactDb.MIGRATION_17_18,
                FactDb.MIGRATION_18_19,
            )
            .allowMainThreadQueries()
            .build()

        // 1. 3→7 Completar nuevas columnas
        db.openHelper.writableDatabase.query("PRAGMA table_info(facts)").use { cursor ->
            val cols = mutableListOf<String>()
            while (cursor.moveToNext()) cols.add(cursor.getString(1))
            assertTrue("importance La columna debería existir", "importance" in cols)
            assertTrue("category La columna debería existir", "category" in cols)
            assertTrue("confidence La columna debería existir", "confidence" in cols)
            assertTrue("source La columna debería existir", "source" in cols)
            assertTrue("expires_at La columna debería existir", "expires_at" in cols)
            assertTrue("last_confirmed_at La columna debería existir", "last_confirmed_at" in cols)
            assertTrue("last_hit_at La columna debería existir", "last_hit_at" in cols)
            assertTrue("scope La columna debería existir", "scope" in cols)
        }

        // 2. FTS La tabla ha sido creada.
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='facts_fts'",
        ).use { cursor -> assertTrue("facts_fts la tabla debería existir", cursor.moveToFirst()) }

        // 3. Los datos históricos no tienen pérdidas + el valor predeterminado es correcto
        db.openHelper.writableDatabase.query(
            "SELECT fact, importance, category, confidence, source, scope FROM facts WHERE id = 1",
        ).use { cursor ->
            assertTrue("v3 Los datos del inventario deben conservarse.", cursor.moveToFirst())
            assertEquals("Me encanta la comida picante", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("general", cursor.getString(2))
            assertEquals(1.0f, cursor.getFloat(3), 0.001f)
            assertEquals("inferred", cursor.getString(4))
            assertEquals("main", cursor.getString(5))
        }

        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='fact_deletion_tombstones'",
        ).use { cursor ->
            assertTrue("deletion tombstone table must be created by migration 14→15", cursor.moveToFirst())
        }
        db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_fact_deletion_tombstones_scope_space_id_normalized_fact'",
        ).use { cursor ->
            assertTrue("tombstones must be unique per scope/space/fact", cursor.moveToFirst())
        }

        db.close()
    }

    /**
     * v17: Los usuarios antiguos actualizan desde v16 —— Biblioteca de caída de fracciones de desintegración,La fila del historial se inicializa para [MemoryConfig.baseImportance](10.0),
     * En lugar de un resultado de 0:0, todos los viejos recuerdos aparecerán colectivamente en la primera ronda de acuerdo.,igual a una mejora“amnesia”.
     */
    @Test
    fun migrate16To17_initializesPersistedScoreForLegacyRows() {
        val dbFile = newDbFile()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V16_FACTS)
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (id, fact, tags, time, session_id, created_at, importance, category,
                confidence, source, expires_at, last_confirmed_at, last_hit_at, hit_count,
                scope, space_id, pinned_at, entity_key)
            VALUES (7, 'A los usuarios les gusta tomar café americano.', '[]', NULL, NULL, '2026-05-01T00:00:00Z', 0, 'preference',
                1.0, 'user_explicit', NULL, NULL, NULL, 3,
                'main', 'default', NULL, 'Usuario')
            """.trimIndent(),
        )

        FactDb.MIGRATION_16_17.migrate(helper)

        helper.query("SELECT score, score_updated_at, suppressed_at FROM facts WHERE id = 7").use { cursor ->
            assertTrue("Los hechos históricos deben preservarse.", cursor.moveToFirst())
            assertEquals("La puntuación de memoria antigua debe inicializarse como baseImportance(no 0)", 10.0f, cursor.getFloat(0), 0.001f)
            assertTrue("No anote el tiempo de liquidación antes de la primera ronda de liquidación.", cursor.isNull(1))
            assertTrue("No deberías aparecer inmediatamente después de actualizar.", cursor.isNull(2))
        }
        helper.close()
    }

    /**
     * v18: Los usuarios antiguos actualizan desde v17 —— `supersedes_id` ¿Es posible agregar una nueva columna?,La historia es siempre NULL,
     * Eso es“No reemplaza ninguna memoria”: El comportamiento de selección y recuperación de las secciones compiladas es exactamente el mismo que antes de la actualización.
     */
    @Test
    fun migrate17To18_leavesLegacyRowsWithoutSupersedes() {
        val dbFile = newDbFile()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V17_FACTS)
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (id, fact, tags, time, session_id, created_at, importance, category,
                confidence, source, expires_at, last_confirmed_at, last_hit_at, hit_count,
                scope, space_id, pinned_at, entity_key, score, score_updated_at, suppressed_at)
            VALUES (9, 'A los usuarios les gusta tomar café americano.', '[]', NULL, NULL, '2026-05-01T00:00:00Z', 0, 'preference',
                1.0, 'user_explicit', NULL, NULL, NULL, 3,
                'main', 'default', NULL, 'Usuario', 7.5, NULL, NULL)
            """.trimIndent(),
        )

        FactDb.MIGRATION_17_18.migrate(helper)

        helper.query("SELECT fact, score, supersedes_id FROM facts WHERE id = 9").use { cursor ->
            assertTrue("Los hechos históricos deben preservarse.", cursor.moveToFirst())
            assertEquals("La actualización no cambia el contenido real", "A los usuarios les gusta tomar café americano.", cursor.getString(0))
            assertEquals("La actualización no cambia la puntuación de deterioro de la biblioteca eliminada", 7.5f, cursor.getFloat(1), 0.001f)
            assertTrue("La línea histórica no reemplaza ningún recuerdo.", cursor.isNull(2))
        }
        helper.close()
    }

    /**
     * v19: Los usuarios antiguos actualizan desde v18 —— `merged_into_id` ¿Es posible agregar una nueva columna?,La historia es siempre NULL,
     * Eso es“No fusionado”: El comportamiento de recuperación, proyección y lista es exactamente el mismo que antes de la actualización.
     */
    @Test
    fun migrate18To19_leavesLegacyRowsUnmerged() {
        val dbFile = newDbFile()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(V18_FACTS)
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    }
                })
                .build(),
        ).writableDatabase

        helper.execSQL(
            """
            INSERT INTO facts (id, fact, tags, time, session_id, created_at, importance, category,
                confidence, source, expires_at, last_confirmed_at, last_hit_at, hit_count,
                scope, space_id, pinned_at, entity_key, score, score_updated_at, suppressed_at, supersedes_id)
            VALUES (11, 'A los usuarios les gusta tomar café americano.', '[]', NULL, NULL, '2026-05-01T00:00:00Z', 1, 'preference',
                1.0, 'user_explicit', NULL, NULL, NULL, 2,
                'main', 'default', NULL, 'Usuario', 8.5, NULL, NULL, NULL)
            """.trimIndent(),
        )

        FactDb.MIGRATION_18_19.migrate(helper)

        helper.query("SELECT fact, importance, supersedes_id, merged_into_id FROM facts WHERE id = 11").use { cursor ->
            assertTrue("Los hechos históricos deben preservarse.", cursor.moveToFirst())
            assertEquals("La actualización no cambia el contenido real", "A los usuarios les gusta tomar café americano.", cursor.getString(0))
            assertEquals("La actualización no cambia la importancia de las bibliotecas eliminadas", 1, cursor.getInt(1))
            assertTrue("Línea histórica sin puntero de reemplazo", cursor.isNull(2))
            assertTrue("linea de historia“No fusionado”", cursor.isNull(3))
        }
        helper.close()
    }

    private companion object {
        const val DB_NAME = "facts_test.db"

        /** v16 La estructura de la tabla de hechos.(Consistente con esquemas/16.json,No incluye columnas nuevas v17). */
        val V16_FACTS = """
            CREATE TABLE IF NOT EXISTS `facts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fact` TEXT NOT NULL,
                `tags` TEXT NOT NULL DEFAULT '[]',
                `time` TEXT,
                `session_id` TEXT,
                `created_at` TEXT NOT NULL,
                `importance` INTEGER NOT NULL DEFAULT 0,
                `category` TEXT NOT NULL DEFAULT 'general',
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `source` TEXT NOT NULL DEFAULT 'inferred',
                `expires_at` TEXT,
                `last_confirmed_at` TEXT,
                `last_hit_at` TEXT DEFAULT NULL,
                `hit_count` INTEGER NOT NULL DEFAULT 0,
                `scope` TEXT NOT NULL DEFAULT 'main',
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `pinned_at` TEXT DEFAULT NULL,
                `entity_key` TEXT
            )
        """.trimIndent()

        /** v17 La estructura de la tabla de hechos.(Consistente con esquemas/17.json,Tres columnas con atenuación.,no contiene supersedes_id). */
        val V17_FACTS = """
            CREATE TABLE IF NOT EXISTS `facts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fact` TEXT NOT NULL,
                `tags` TEXT NOT NULL DEFAULT '[]',
                `time` TEXT,
                `session_id` TEXT,
                `created_at` TEXT NOT NULL,
                `importance` INTEGER NOT NULL DEFAULT 0,
                `category` TEXT NOT NULL DEFAULT 'general',
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `source` TEXT NOT NULL DEFAULT 'inferred',
                `expires_at` TEXT,
                `last_confirmed_at` TEXT,
                `last_hit_at` TEXT DEFAULT NULL,
                `hit_count` INTEGER NOT NULL DEFAULT 0,
                `scope` TEXT NOT NULL DEFAULT 'main',
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `pinned_at` TEXT DEFAULT NULL,
                `entity_key` TEXT,
                `score` REAL NOT NULL DEFAULT 10.0,
                `score_updated_at` TEXT DEFAULT NULL,
                `suppressed_at` TEXT DEFAULT NULL
            )
        """.trimIndent()

        /** v18 La estructura de la tabla de hechos.(Consistente con esquemas/18.json,Contiene supersedes_id,no contiene merged_into_id). */
        val V18_FACTS = """
            CREATE TABLE IF NOT EXISTS `facts` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `fact` TEXT NOT NULL,
                `tags` TEXT NOT NULL DEFAULT '[]',
                `time` TEXT,
                `session_id` TEXT,
                `created_at` TEXT NOT NULL,
                `importance` INTEGER NOT NULL DEFAULT 0,
                `category` TEXT NOT NULL DEFAULT 'general',
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `source` TEXT NOT NULL DEFAULT 'inferred',
                `expires_at` TEXT,
                `last_confirmed_at` TEXT,
                `last_hit_at` TEXT DEFAULT NULL,
                `hit_count` INTEGER NOT NULL DEFAULT 0,
                `scope` TEXT NOT NULL DEFAULT 'main',
                `space_id` TEXT NOT NULL DEFAULT 'default',
                `pinned_at` TEXT DEFAULT NULL,
                `entity_key` TEXT,
                `score` REAL NOT NULL DEFAULT 10.0,
                `score_updated_at` TEXT DEFAULT NULL,
                `suppressed_at` TEXT DEFAULT NULL,
                `supersedes_id` INTEGER DEFAULT NULL
            )
        """.trimIndent()
    }
}
