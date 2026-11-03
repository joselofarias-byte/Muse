package io.zer0.memory.fact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.zer0.common.Logger
import io.zer0.memory.ai.MemoryLinkEntity
import io.zer0.memory.space.MemorySpaceEntity
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fact base de datos.
 *
 * v3 schema: tabla principal de hechos solamente(LIKE Buscar,Ninguno FTS).
 * v4 schema: Campo de importancia agregado(0=ordinario,1=importante,2=clave),Los hechos clave nunca decaen.
 * v5 schema: Nuevo category / confidence / source / expires_at / last_confirmed_at Campos estructurados.
 * v6 schema: Se agregó la mesa virtual Facts_fts FTS4.(Índice de texto completo de ngram chino),Mantenga ME GUSTA como palabra/Reversión de excepciones.
 * v7 schema: Se agregó el campo last_hit_at,Bonificación de golpe de apoyo(hitBonus)Reinicie el reloj de decadencia.
 * v8 schema: Agregar campo de alcance(alcance de la memoria,Predeterminado "main" Representa el ámbito de ayuda principal.),
 *   Se utiliza para aislar la memoria de diferentes Agentes.,Evite el mal uso por parte del subasistente de los datos del asistente principal o la confusión de la memoria de los miembros del equipo.
 * v9 schema: Campo space_id agregado(espacio de memoria,Predeterminado "default"),Utilizado para aislamiento de múltiples espacios.
 *   (trabajo/vida/Las escenas de aprendizaje no interfieren entre sí.);Agregue también la tabla Memory_spaces.
 *   Almacena metadatos del espacio.space_id ortogonal al alcance:scope Cuarentena por agente,space_id Aislar por escena.
 * v10 schema: Se agregó la tabla de enlaces de memoria.(Borde del gráfico de conocimiento de la memoria),Almacenar relaciones entre hechos.
 *   (causes/explains/part_of/related_to/contradicts),Para la gestión de memoria impulsada por IA
 *   Construir gráfico de memoria de usuario(P2-3).
 * v12 schema: Agregar columna entidad_clave(Clave de normalización de entidad),Diferentes métodos de escritura de la misma entidad comparten la misma clave,
 *   Se utiliza para comprobar con precisión la duplicación y fusionar métodos de escritura cruzada durante la escritura.(Resuelve el problema de la memoria repetida del mismo nombre.). Los datos históricos son NULL,
 *   Rellenado con tareas de reflexión durante la recopilación.
 * v13 schema: Agregar tabla fact_revisions(Registro de revisión de memoria clave),Admite auditoría y reversión.
 * v15 schema: Se agregó aislamiento de hechos de persistencia por alcance + espacio para eliminar lápidas,Confirmación atómica de transacción con eliminación de hecho.
 * v17 schema: Nuevo score / score_updated_at / suppressed_at tres columnas —— Biblioteca de caída de fracciones de desintegración,Sólo memoria de puntuación baja"aparecer"No elimine filas.
 * v18 schema: Se agregó la columna supersedes_id —— La nueva memoria apunta a la antigua memoria que reemplaza.(arbitraje de conflictos);Mantener filas antiguas,
 *   Solo sale del contexto de compilación. Los datos históricos son NULL,El comportamiento es el mismo que antes de la actualización.
 * v19 schema: Se agregó la columna fusionada_into_id —— ¿En qué memoria está fusionado este artículo?(Repetir agregación);La nueva línea apuntaba a los acarreos.
 *   Texto integrado,La parte señaladora permanece disponible para consultas pero no participa en la recuperación ni en la proyección. Los datos históricos son NULL,El comportamiento es el mismo que antes de la actualización.
 *
 * FTS4 Instrucciones de selección:
 *  - Parcialmente hecho en China ROM(Como por ejemplo OPPO Android 16)SQLite no compila el módulo FTS5,
 *    `CREATE VIRTUAL TABLE ... USING fts5(...)` puede tirar `no such module: fts5`.
 *  - FTS4 desde SQLite 3.7.4(2010)Incorporado,Android Todo el SQLite integrado es compatible,La compatibilidad es confiable.
 *  - La búsqueda en chino la realiza la capa de aplicación. [FactFtsManager.toNgram] Preprocesado como 2-gram,No depende del tokenizador incorporado.
 */

/**
 * La versión actual del esquema de la biblioteca de hechos.
 *
 * debe ser con [FactDb] @Versión de base de datos, fin de la cadena de migración(actual MIGRATION_18_19)Los tres son consistentes;
 * guardia de versión [FactDb.archiveUnknownVersionDatabase] También haga referencia a esta constante.
 * El defecto histórico es proteger los viejos valores.(13)Como resultado, la biblioteca real v14 fue juzgada erróneamente como"Versión alta desconocida"Borrado repetido de archivos.
 */
internal const val FACT_DB_VERSION = 19

@Database(
    entities = [
        FactEntity::class,
        FactFtsEntity::class,
        MemorySpaceEntity::class,
        MemoryLinkEntity::class,
        FactRevisionEntity::class,
        FactTombstoneEntity::class,
    ],
    version = FACT_DB_VERSION,
    // v1.78 (H4): Habilitar exportación de esquema,Escriba un reemplazo de migración para futuras actualizaciones de v4+ destructive
    // historia v1→v2→v3 La migración destructiva es irremediable,Mantenga una línea de base a partir de la v3
    exportSchema = true,
)
abstract class FactDb : RoomDatabase() {
    abstract fun factDao(): FactDao

    /** v13 (T4-1): Registros de revisión de hechos DAO. */
    abstract fun factRevisionDao(): FactRevisionDao

    /** Durable, scope/space-local deletion markers. */
    abstract fun factTombstoneDao(): FactTombstoneDao

    abstract fun memorySpaceDao(): io.zer0.memory.space.MemorySpaceDao

    /** v10: Borde del gráfico de conocimiento de la memoria DAO(P2-3). */
    abstract fun memoryLinkDao(): io.zer0.memory.ai.MemoryLinkDao

    companion object {
        /**
         * v3→v4 Migrar: Agregar columna de importancia(Predeterminado 0=ordinario)+ índice.
         * ALTER TABLE ADD COLUMN ¿Es SQLite nativo? DDL,Android 16 Sin restricciones de DML de execSQL.
         */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN importance INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_importance ON facts(importance)")
                }
            }

        /**
         * v4→v5 Migrar: Agregar nuevo campo de hechos estructurados(category / confidence / source / expires_at / last_confirmed_at).
         */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN category TEXT NOT NULL DEFAULT 'general'")
                    db.execSQL("ALTER TABLE facts ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                    db.execSQL("ALTER TABLE facts ADD COLUMN source TEXT NOT NULL DEFAULT 'inferred'")
                    db.execSQL("ALTER TABLE facts ADD COLUMN expires_at TEXT")
                    db.execSQL("ALTER TABLE facts ADD COLUMN last_confirmed_at TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_category ON facts(category)")
                }
            }

        /**
         * v5→v6 Migrar: Se agregó la tabla virtual FTS4 de Facts_fts + activador de eliminación en cascada.
         *
         * Nota:
         * - SQL Debe ser exactamente igual al generado por la anotación @Fts4(Con acento grave + orden de columnas),De lo contrario, la verificación del esquema de la sala falla.
         * - La migración solo crea tablas vacías,No dejes ningún dato en blanco. primer comienzo/Primera búsqueda por [FactStore.ensureFtsIndexConsistent]
         *   Compare el recuento de las dos tablas y luego la cantidad total rebuild(usar [FactFtsManager.toNgram] Convertir).
         * - No en migración INSERT SELECT: SQL no puede llamar a la función ngram de Kotlin,Insertar directamente el texto original dará lugar a
         *   índice/La consulta es inconsistente(MATCH menos que).rebuild Debe hacerse en la capa de Kotlin.
         */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS `facts_fts` USING FTS4(" +
                            "`fact_id` INTEGER, `content_ngram` TEXT" +
                            ")",
                    )
                    createFtsCleanupTrigger(db)
                }
            }

        /**
         * v6: Cree un activador para la limpieza en cascada de hechos_fts en la tabla de hechos.
         * usado para [FactStore.applyDecay] Sincronizar automáticamente índices para escenarios DELETE por lotes,Evite filas huérfanas.
         */
        private fun createFtsCleanupTrigger(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS facts_after_delete AFTER DELETE ON facts " +
                    "BEGIN DELETE FROM facts_fts WHERE fact_id = old.id; END;",
            )
        }

        /**
         * v6→v7 Migrar: Se agregó la columna last_hit_at,Se utiliza para el reloj de caída de bonificación de golpe.
         * Los datos históricos se mantienen de forma predeterminada. NULL(estado de señorita),La hora actual se escribe después de visitas posteriores.
         */
        val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN last_hit_at TEXT DEFAULT NULL")
                }
            }

        /**
         * v7→v8 Migrar: Agregar nueva columna de alcance(alcance de la memoria,Predeterminado "main")+ índice.
         *
         * scope utilizado para aislar diferentes Agent(asistente principal/sub-asistente/miembro del equipo)memoria:
         *  - Todos los datos históricos están predeterminados en "main"(alcance de ayuda principal),Comportamiento sin cambios después de la migración
         *  - Al agregar una memoria de subasistente FactStore.add(scope = assistantId) escribir
         *  - Filtrar por alcance al realizar consultas,Evite el uso indebido entre alcances
         *
         * El nombre del índice index_facts_scope es coherente con el nombre predeterminado de la anotación @Index
         * (Room Generado automáticamente `index_<table>_<column>`).
         */
        val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN scope TEXT NOT NULL DEFAULT 'main'")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_facts_scope ON facts(scope)")
                }
            }

        /**
         * v8→v9 Migrar: P2-2 Aislamiento de memoria multiespacio.
         *
         *  1. facts Agregar la columna space_id a la tabla(Predeterminado "default"),Y construir índice.
         *  2. Cree una nueva tabla Memory_spaces para almacenar metadatos de espacio.(id/name/icon/description/created_at/sort_index).
         *  3. Insertar valor predeterminado Space("default"),hechos históricos a través DEFAULT 'default' Incluido automáticamente.
         *
         * Nota:
         *  - ALTER TABLE ADD COLUMN En SQLite es O(1) Operaciones de metadatos,No se reescribirá toda la tabla.
         *  - memory_spaces La estructura de la tabla debe ser consistente con [MemorySpaceEntity] @Entity El SQL generado por la anotación es exactamente el mismo.,
         *    De lo contrario, falla la verificación del esquema de la habitación(Incluyendo orden de columnas, tipo, valor predeterminado, nombre de índice).
         *  - El nombre del índice idx_memory_spaces_sort es el mismo que @Index(name = "idx_memory_spaces_sort") Alineación.
         */
        val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. facts Agregar la columna space_id a la tabla
                    db.execSQL("ALTER TABLE facts ADD COLUMN space_id TEXT NOT NULL DEFAULT 'default'")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_facts_space_id ON facts(space_id)")

                    // 2. Crear tabla de espacios de memoria
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS memory_spaces (
                            id TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            icon TEXT,
                            description TEXT NOT NULL DEFAULT '',
                            created_at TEXT NOT NULL,
                            sort_index INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_spaces_sort ON memory_spaces(sort_index)")

                    // 3. Insertar valor predeterminado Space(Utilice INSERTAR O IGNORAR para evitar duplicaciones)
                    val defaultCreatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO memory_spaces (id, name, icon, description, created_at, sort_index)
                        VALUES ('default', 'Predeterminado', 'bookmark', '', '$defaultCreatedAt', 0)
                        """.trimIndent(),
                    )
                }
            }

        /**
         * v9→v10 Migrar: P2-3 Gráfico de conocimiento de la memoria.
         *
         *  Cree una nueva tabla Memory_links para almacenar relaciones entre hechos.(causes/explains/part_of/related_to/contradicts).
         *  - source_fact_id / target_fact_id señalar a facts.id(No agregado FOREIGN KEY,Evite pérdidas de rendimiento en cascada)
         *  - source_title / target_title título redundante(La semántica relacional aún se puede mostrar después de eliminar hechos)
         *  - space_id / scope Alinear con la tabla de hechos,Admite aislamiento multiespacio + multiagente
         *  - weight fuerza de la relación 0.0~1.0
         *
         * Nota:
         *  - La estructura de la tabla debe ser consistente con [MemoryLinkEntity] @Entity El SQL generado por la anotación es exactamente el mismo.
         *    (Lista/Tipo/Valor predeterminado/Nombre del índice),De lo contrario, la verificación del esquema de la sala falla.
         *  - Nombre del índice idx_memory_links_* con @Index(name = ...) Alineación.
         *  - No hay ningún registro de enlaces de memoria en los datos históricos.,La migración solo crea tablas vacías.
         */
        val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS memory_links (
                            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            source_fact_id INTEGER NOT NULL,
                            target_fact_id INTEGER NOT NULL,
                            source_title TEXT NOT NULL,
                            target_title TEXT NOT NULL,
                            link_type TEXT NOT NULL DEFAULT 'related_to',
                            weight REAL NOT NULL DEFAULT 0.5,
                            space_id TEXT NOT NULL DEFAULT 'default',
                            scope TEXT NOT NULL DEFAULT 'main',
                            created_at TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_source ON memory_links(source_fact_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_target ON memory_links(target_fact_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_space ON memory_links(space_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_links_scope ON memory_links(scope)")
                }
            }

        /**
         * B4-05: v10→v11 Migrar — facts Agregar la columna pinned_at a la tabla(Memoria de pines manual).
         */
        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN pinned_at TEXT DEFAULT NULL")
                }
            }

        /**
         * v11→v12 Migrar — facts Agregar la columna entidad_clave a la tabla(Clave de normalización de entidad).
         *
         * - solo ADD COLUMN,SQLite O(1) Operaciones de metadatos,No reescribir la tabla,Los datos históricos están intactos.
         * - Las nuevas columnas pueden estar vacías,Retención de datos históricos NULL,Rellenado por nombre de entidad mediante la tarea de reflexión durante la clasificación.
         * - El índice idx_facts_entity_key acelera la verificación de duplicaciones durante la escritura(Escaneo de candidato clave de la misma entidad).
         */
        val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN entity_key TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_facts_entity_key ON facts(entity_key)")
                }
            }

        /**
         * v12→v13 Migrar — Crear una nueva tabla fact_revisions(Registro de revisión de memoria clave).
         * Crear solo tablas vacías,No hay datos de revisión histórica. La estructura de la tabla debe ser consistente con [FactRevisionEntity] Alineación.
         */
        val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS fact_revisions (
                            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                            fact_id INTEGER NOT NULL,
                            old_content TEXT NOT NULL,
                            new_content TEXT NOT NULL,
                            changed_at TEXT NOT NULL,
                            reason TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_revisions_fact_id ON fact_revisions(fact_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_revisions_changed_at ON fact_revisions(changed_at)")
                }
            }

        /**
         * v13→v14 Migrar — Se agregó la columna hit_count (para recuperar el número acumulado de visitas).
         * NOT NULL + DEFAULT 0 debe ser con [FactEntity] Las declaraciones son coherentes; de lo contrario, falla la verificación del esquema de la habitación.
         */
        val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN hit_count INTEGER NOT NULL DEFAULT 0")
                }
            }

        /** v14→v15: durable tombstones; unlike the legacy shared text file these are scope/space-local. */
        val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS fact_deletion_tombstones " +
                            "(id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, normalized_fact TEXT NOT NULL, " +
                            "scope TEXT NOT NULL, space_id TEXT NOT NULL, deleted_at TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_fact_deletion_tombstones_scope_space_id_normalized_fact " +
                            "ON fact_deletion_tombstones (scope, space_id, normalized_fact)",
                    )
                }
            }

        /**
         * v15→v16(auditoría de memoria): Nuevo (scope, space_id) Índice compuesto.
         *
         * La gran mayoría de las consultas son“mismo alcance + mismo space”filtro (lista/Buscar/Eliminar duplicados/atenuación),
         * La implementación anterior solo tenía un índice de una sola columna y, cuando aumenta el volumen de datos, se requiere la combinación de índices o un escaneo completo de la tabla.
         */
        val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_facts_scope_space_id ON facts (scope, space_id)",
                    )
                }
            }

        /**
         * v16→v17(La atenuación de la memoria se puede volver a marcar): Nuevo score / score_updated_at / suppressed_at Tres columnas.
         *
         * - `score`: La fracción de descomposición de la persistencia. El valor predeterminado es 10.0, que es [io.zer0.memory.ticker.MemoryConfig.baseImportance],
         *   ALTER TABLE DEFAULT agregará directamente este valor a la fila del historial. —— no puede ser 0,
         *   De lo contrario, todos los viejos recuerdos aparecerán colectivamente en la primera ronda de liquidación después de la actualización. La verdadera edad de la antigua disputa decae en la siguiente ronda.
         *   created_at / last_hit_at Liquidación,Entonces 10.0 es solo el punto de partida y no el valor final.
         * - `suppressed_at`: tiempo de juego,NULL = Todavía bajo retirada. Una memoria de puntuación baja solo escribe la columna actual y no elimina filas.
         * - `score_updated_at`: Última hora de liquidación,Reloj que decae progresivamente.
         *
         * Plan alternativo(No ejecutado en código,Sólo para bajar de categoría/Certificado de retrabajo):
         *  1) Ambas columnas son columnas nuevas.,Cuando la versión anterior del APK abra la biblioteca v17, será [archiveUnknownVersionDatabase]
         *     Se cambió el nombre de toda la base de datos. `facts.db.pre-destructive.bak`,No se pierden datos,Se puede retroceder manualmente;
         *  2) Cuando necesitas salir de esta función,Ejecutar en copia de seguridad
         *     `ALTER TABLE facts DROP COLUMN score; ALTER TABLE facts DROP COLUMN score_updated_at;`
         *     `ALTER TABLE facts DROP COLUMN suppressed_at;`
         *     Y vuelva a establecer user_version en 16.
         */
        val MIGRATION_16_17 =
            object : Migration(16, 17) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN score REAL NOT NULL DEFAULT 10.0")
                    db.execSQL("ALTER TABLE facts ADD COLUMN score_updated_at TEXT DEFAULT NULL")
                    db.execSQL("ALTER TABLE facts ADD COLUMN suppressed_at TEXT DEFAULT NULL")
                }
            }

        /**
         * v17→v18(Arbitraje de conflictos): Se agregó la columna supersedes_id —— La nueva memoria anota qué memoria antigua reemplaza.
         *
         * - columna que acepta valores NULL,ALTER TABLE ADD COLUMN si O(1) Operaciones de metadatos,No reescribir la tabla;
         *   Todas las líneas históricas son NULL = "No reemplaza ninguna memoria",compilar/El comportamiento de recuperación es exactamente el mismo que antes de la actualización.
         * - Sin indexación: El único lector es la proyección del segmento FACTS.,Originalmente atraviesa las filas del mismo alcance + espacio en la memoria.
         * - No eliminar filas antiguas: La memoria reemplazada todavía está en la tabla de hechos.,página de memoria visible,Al eliminar la nueva memoria que apunta a ella, se vuelve automáticamente al contexto.
         *
         * Plan alternativo(Rebajar/Ejecutar en copia de seguridad durante el retrabajo,No ejecutes el código):
         *  1) Cuando la versión anterior del APK abra la biblioteca v18, será [archiveUnknownVersionDatabase] Se cambió el nombre de toda la base de datos.
         *     `facts.db.pre-destructive.bak`,No se pierden datos,Se puede retroceder manualmente;
         *  2) Cuando necesitas salir de esta función: `ALTER TABLE facts DROP COLUMN supersedes_id` Y vuelva a establecer user_version en 17.
         */
        val MIGRATION_17_18 =
            object : Migration(17, 18) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN supersedes_id INTEGER DEFAULT NULL")
                }
            }

        /**
         * v18→v19(Fusión de memoria duplicada): Se agregó la columna fusionada_into_id —— ¿En qué memoria se fusionó este elemento?
         *
         * - columna que acepta valores NULL,ALTER TABLE ADD COLUMN si O(1) Operaciones de metadatos,No reescribir la tabla;
         *   Todas las líneas históricas son NULL = "No fusionado",recordar/proyección/El comportamiento de la página de memoria es exactamente el mismo que antes de la actualización.
         * - Sin indexación: Sólo presione“¿Qué líneas están marcadas?”Lea el lote completo una vez(scoped scan),Coincidir existente
         *   (scope, space_id) El índice compuesto es suficiente,No se creará ningún índice adicional de una sola columna.
         * - Semántica diferente a la de reemplaza_id: Ese es reemplazo de contenido.(Arbitraje de conflictos),Esta columna es una agregación repetida.;
         *   Las filas fusionadas no se eliminan, se pueden verificar y se pueden revertir(La eliminación de las nuevas filas fusionadas se deshará automáticamente.).
         *
         * Plan alternativo(Rebajar/Ejecutar en copia de seguridad durante el retrabajo,No ejecutes el código):
         *  1) Cuando la versión anterior del APK abra la biblioteca v19, será [archiveUnknownVersionDatabase] Se cambió el nombre de toda la base de datos.
         *     `facts.db.pre-destructive.bak`,No se pierden datos,Se puede retroceder manualmente;
         *  2) Cuando necesitas salir de esta función: Limpia la marca primero(`UPDATE facts SET merged_into_id = NULL`,De lo contrario, no se leerá la versión anterior.
         *     esta columna,Si actualiza nuevamente en el futuro, verá números que apuntan a filas que ya no existen.),otra vez `ALTER TABLE facts DROP COLUMN merged_into_id`
         *     Y vuelva a establecer user_version en 18.
         */
        val MIGRATION_18_19 =
            object : Migration(18, 19) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE facts ADD COLUMN merged_into_id INTEGER DEFAULT NULL")
                }
            }

        /**
         * R-DB-03: Archive la versión 1/v2 anterior o la base de datos de hechos corruptos.
         * Archivado como <name>.bak Luego, Room reconstruye la biblioteca vacía.,Evite choques al abrir.
         */
        private fun archiveLegacyOrCorruptDatabase(context: Context, name: String) {
            val file = context.getDatabasePath(name)
            if (!file.exists()) return

            val legacyOrCorrupt =
                try {
                    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val version = db.version
                    db.close()
                    version < 3
                } catch (_: Exception) {
                    true
                }
            if (!legacyOrCorrupt) return

            archiveDatabaseFiles(context, file, nextBackupFile(file, "$name.bak"), name)
        }

        /**
         * Reparación de auditoría (0.2): desconocido/Las bases de datos posteriores se archivan antes de un recurso destructivo.
         * La implementación original de respaldo destructivo borrará silenciosamente la memoria de hechos del usuario sin respaldo.;
         * Aquí comprueba la versión antes de abrir Room.: Por encima de la cobertura de la cadena migratoria(Bajar de versión superior)O cuando la versión es anormal,
         * Cambie el nombre de .bak primero para conservar los datos,Room Reconstruir la base de datos vacía,Los datos son recuperables.
         */
        private fun archiveUnknownVersionDatabase(context: Context, name: String) {
            val file = context.getDatabasePath(name)
            if (!file.exists()) return
            val version =
                try {
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
                } catch (_: Exception) {
                    return // La biblioteca dañada se entrega a archiveLegacyOrCorruptDatabase para su procesamiento.
                }
            // Dentro de la cobertura de la cadena migratoria(3..FACT_DB_VERSION)Nunca archives.
            // defectos historicos: La muerte fue escrita aquí. 13,Después de que la base de datos se actualizó a 14, la versión actual de la base de datos real fue juzgada erróneamente como
            // "Versión alta desconocida",Inicie la reconstrucción del archivo cada vez → La memoria de hechos no se conserva.(83 Un .bak vacío es su rastro.).
            if (version <= FACT_DB_VERSION) return
            archiveDatabaseFiles(context, file, nextBackupFile(file, "$name.pre-destructive.bak"), name)
        }

        /**
         * Archivar archivos de base de datos(main + wal + shm).
         *
         * Haz tu mejor esfuerzo antes de presentar la solicitud `wal_checkpoint(TRUNCATE)` Fusionar datos en WAL en la base de datos principal;checkpoint falló o
         * Cuando no se puede abrir,Cambie el nombre de -wal/-shm y guárdelo con la copia de seguridad,evitar la historia"Sólo se guarda el archivo principal,WAL Los datos fueron eliminados"
         * Pérdida de datos silenciosa. Etiqueta después de archivar [MemoryLegacyReset] Proporciona indicaciones de la interfaz de usuario.
         */
        private fun archiveDatabaseFiles(context: Context, file: File, bak: File, name: String) {
            val checkpointed =
                try {
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            val renamed = runCatching { file.renameTo(bak) }.getOrDefault(false)
            check(renamed) {
                "No se puede archivar la memoria de forma segura; se ha conservado el archivo original: ${file.absolutePath}"
            }
            val parent = file.parentFile
            listOf("wal", "shm").forEach { suffix ->
                runCatching {
                    val aux = File(parent, "${file.name}-$suffix")
                    if (!aux.exists()) return@runCatching
                    if (checkpointed) {
                        aux.delete()
                    } else {
                        aux.renameTo(File(parent, "${bak.name}-$suffix"))
                    }
                }
            }
            MemoryLegacyReset.mark(context, name)
        }

        /**
         * v2.2.1 rescate de datos: Recupere la memoria factual de copias de seguridad históricas mal archivadas.
         *
         * Condición de activación(Ejecutar sólo cuando todos estén satisfechos.,Y solo inténtelo una vez por biblioteca.):
         *  1) Hay etiquetas de eventos de archivo no consumidas(Excluir dispositivos que nunca hayan sido archivados erróneamente);
         *  2) Falta la biblioteca principal o el número de filas de hechos es 0(Excluir bibliotecas que todavía están en uso normal);
         *  3) Existe y se puede abrir, y la versión no excede [FACT_DB_VERSION],facts Número de líneas > 0 de
         *     `*.pre-destructive.bak*` copia de seguridad(El que tiene más filas).
         * Antes de restaurar, guarde la biblioteca vacía actual como `*.replaced-by-recovery.bak`,No sobrescribe ningún dato existente.
         *
         * @return Número de hechos restaurados;No ejecutado/Cuando no se encuentra es null
         */
        private fun recoverFromMisarchivedBackup(context: Context, name: String): Int? {
            if (MemoryLegacyReset.isRecoveryAttempted(context, name)) return null
            if (!MemoryLegacyReset.hasPending(context)) return null

            val main = context.getDatabasePath(name)
            val mainEmpty = !main.exists() || countFacts(main) == 0
            if (!mainEmpty) {
                MemoryLegacyReset.markRecoveryAttempted(context, name)
                return null
            }

            val parent = main.parentFile ?: return null
            val candidates = parent.listFiles { f -> f.name.startsWith("$name.pre-destructive.bak") }.orEmpty()
            var best: File? = null
            var bestCount = 0
            for (candidate in candidates) {
                val version = readVersion(candidate) ?: continue
                if (version > FACT_DB_VERSION) continue
                val count = countFacts(candidate)
                if (count > bestCount) {
                    best = candidate
                    bestCount = count
                }
            }
            val source = best
            if (source == null || bestCount == 0) {
                MemoryLegacyReset.markRecoveryAttempted(context, name)
                return null
            }

            // Guarde la base de datos vacía actual y luego muévala nuevamente a la copia de seguridad(No cubierto, se puede revertir)
            if (main.exists()) {
                runCatching { main.renameTo(File(parent, "$name.replaced-by-recovery.bak")) }
                listOf("-wal", "-shm").forEach { suffix ->
                    runCatching { File(parent, "$name$suffix").takeIf { it.exists() }?.delete() }
                }
            }
            val restored = runCatching { source.renameTo(main) }.getOrDefault(false)
            if (!restored) {
                Logger.w("FactDb", "Error al restaurar desde el archivo(rename rechazado),Mantener copias de seguridad: ${source.absolutePath}")
                MemoryLegacyReset.markRecoveryAttempted(context, name)
                return null
            }
            MemoryLegacyReset.markRecoveryAttempted(context, name)
            MemoryLegacyReset.noteRecovered(context, bestCount)
            Logger.i("FactDb", "Memoria de hechos recuperada del archivo: $bestCount Artículo(Fuente ${source.name})")
            return bestCount
        }

        /** Lea el número de filas en la tabla de hechos en el archivo de base de datos;Devuelve 0 si no es legible. */
        private fun countFacts(file: File): Int = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM facts", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
        } catch (_: Exception) {
            0
        }

        /** Leer base de datos user_version;Devuelve nulo si no es legible. */
        private fun readVersion(file: File): Int? = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        } catch (_: Exception) {
            null
        }

        /** Seleccione una ruta de destino para el archivado que no sobrescriba las copias de seguridad existentes. */
        private fun nextBackupFile(file: File, preferredName: String): File {
            val parent = file.parentFile ?: error("Falta el directorio principal del archivo de base de datos: ${file.absolutePath}")
            val preferred = File(parent, preferredName)
            if (!preferred.exists()) return preferred
            var index = 1
            while (true) {
                val candidate = File(parent, "$preferredName.$index")
                if (!candidate.exists()) return candidate
                index += 1
            }
        }

        /** Instancia de base de datos singleton. Único a nivel mundial,Respaldo cuando falla la base de datos en memoria. */
        fun create(context: Context, name: String = "facts.db"): FactDb {
            archiveLegacyOrCorruptDatabase(context, name)
            archiveUnknownVersionDatabase(context, name)
            // v2.2.1: Después de que se solucione el defecto de protección de versión,Por cierto, la base de datos histórica que se archivó por error fue retirada.(solo una vez)
            recoverFromMisarchivedBackup(context, name)
            return Room.databaseBuilder(context, FactDb::class.java, name)
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                )
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            createFtsCleanupTrigger(db)
                            // v9: Insertar valor predeterminado en una instalación nueva Space(Alineado con el comportamiento MIGRATION_8_9)
                            val defaultCreatedAt =
                                LocalDateTime.now()
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                            db.execSQL(
                                """
                                INSERT OR IGNORE INTO memory_spaces
                                    (id, name, icon, description, created_at, sort_index)
                                VALUES ('default', 'Predeterminado', 'bookmark', '', '$defaultCreatedAt', 0)
                                """.trimIndent(),
                            )
                        }
                    },
                )
                // Deshabilite la migración destructiva: cuando la migración no se puede realizar de manera segura, la base de datos original se conserva y se entrega al proceso de recuperación.
                .build()
        }
    }
}
