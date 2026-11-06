package io.zer0.memory.summary

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val MEMORY_DB_VERSION = 5

/**
 * Memory La base de datos de Room del módulo.
 *
 * Resume todas las tablas de persistencia del sistema de memoria.:
 *  - [SessionSummaryEntity]: session Resumen(rolling summary)
 *  - [ContextCheckpointEntity]: punto de control de contexto(producto de compresión de sesión,puntero de límite)
 *  - [DailyStateEntity]: Progreso de compilación diario(daily-state.json Versión habitación)
 *  - [CompiledSectionEntity]: Cuatro piezas de productos compilados.(facts/today/week/longterm.md Versión habitación)
 *  - [FactEntity] por independiente [io.zer0.memory.fact.FactDb] gestión(Porque FTS5 requiere raw SQL)
 *
 * Nota: Phase 2 Simplificar,poner"sistema de archivos + facts.db"Dos juegos de almacenamiento
 * Mover todo a Room,Android Los hábitos son más consistentes.
 */
@Database(
    entities = [
        SessionSummaryEntity::class,
        ContextCheckpointEntity::class,
        DailyStateEntity::class,
        CompiledSectionEntity::class,
        ScopedCompiledSectionEntity::class,
    ],
    version = MEMORY_DB_VERSION,
    // v1.78 (H4): Habilitar exportación de esquema,Migración de escritura para proporcionar una base para futuras actualizaciones de versiones
    exportSchema = true,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun sessionSummaryDao(): SessionSummaryDao
    abstract fun contextCheckpointDao(): ContextCheckpointDao
    abstract fun dailyStateDao(): DailyStateDao
    abstract fun compiledSectionDao(): CompiledSectionDao
    abstract fun scopedCompiledSectionDao(): ScopedCompiledSectionDao

    companion object {
        /** Solo se agregan columnas, todos los resúmenes antiguos se clasifican como predeterminados y no se elimina ningún dato histórico. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE session_summaries ADD COLUMN space_id TEXT NOT NULL DEFAULT 'default'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS compiled_sections_scoped (
                        section_key TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        space_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        fingerprint TEXT,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(section_key, scope, space_id)
                    )
                    """.trimIndent(),
                )
                // Los productos antiguos de un solo espacio solo se mueven a ranuras predeterminadas compatibles y las tablas antiguas se conservan como fuentes de reversión y reversión.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO compiled_sections_scoped
                        (section_key, scope, space_id, content, fingerprint, updated_at)
                    SELECT section_key, 'main', 'default', content, fingerprint, updated_at
                    FROM compiled_sections
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2.x: Se agregó una tabla de puntos de control de contexto (producto de compresión de sesiones).
         *
         * Adición pura de mesa: no se toca ninguna mesa existente. Después de la actualización, la sesión anterior es simplemente"Aún no hay punto de control",
         * Escrito de forma natural cuando se comprime por primera vez.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS context_checkpoints (
                        session_id TEXT NOT NULL,
                        covered_seq INTEGER NOT NULL,
                        last_covered_message_id TEXT NOT NULL DEFAULT '',
                        covered_count INTEGER NOT NULL DEFAULT 0,
                        summary TEXT NOT NULL,
                        tokens_before INTEGER NOT NULL DEFAULT 0,
                        tokens_after INTEGER NOT NULL DEFAULT 0,
                        strategy TEXT NOT NULL DEFAULT 'summary',
                        reason TEXT NOT NULL DEFAULT 'auto',
                        updated_at INTEGER NOT NULL,
                        space_id TEXT NOT NULL DEFAULT 'default',
                        PRIMARY KEY(session_id)
                    )
                    """.trimIndent(),
                )
                // Nota: No cree índices adicionales aquí —— Room El esquema se verificará según la definición @Entity de la entidad.
                // La presencia de índices no declarados en entidades de la biblioteca provocará inconsistencias en el esquema al abrir la base de datos.
            }
        }

        /**
         * v2.x: Aumento del punto de control"El número acumulado de elementos fusionados en el resumen."(Para visualización de divisores de interfaz).
         *
         * Solo agregue columnas, el valor del punto de control anterior es 0 —— Capa de presentación encontrada 0 pulsación"desconocido"Proceso, sin rellenos ni conjeturas.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE context_checkpoints ADD COLUMN total_covered_count INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
