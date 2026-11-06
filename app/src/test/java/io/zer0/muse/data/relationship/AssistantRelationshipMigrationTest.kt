package io.zer0.muse.data.relationship

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.6.7(P1-1): 113 → 114 Pruebas de contrato migratorio.
 *
 * Verificar solo"La nueva tabla se crea correctamente y las definiciones de las columnas son consistentes con la entidad"Este mínimo invariante.
 * Abrir la base de datos sin pasar por Room —— Room Se realizará una verificación de estructura completa en todas las tablas de entidades.
 * Aquí sólo se necesita una biblioteca básica mínima para ejecutar la migración SQL.
 *
 * Respecto a la cobertura (una nota anterior afirmaba"La cadena completa de actualización entre versiones está cubierta por la matriz de reflexión de MuseDbMigrationTest",
 * No coincide con la situación real y ha sido corregido):
 *  - Esta categoría cubre 113→114 Colección de columnas para DDL de una sola pieza/Valor predeterminado/Idempotencia, sin verificación de habitación;
 *  - Lo que realmente abre Room y se verifica a nivel de entidad es
 *    [io.zer0.muse.data.session.MuseDbMigrationTest.migrateV113To116Starts_validateThroughRoom]
 *    con [io.zer0.muse.data.session.MuseDbMigrationTest.migrateV112To117_backfillsUsageRecordsAndKeepsMessages];
 *  - 22..54 Punto de partida desviado debido a un artefacto de doble conexión de Robolectric
 *    [io.zer0.muse.data.session.MuseDbManualChainMigrationTest] Realizar manualmente el cubrimiento de cadena paso a paso, también sin verificación de Sala.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AssistantRelationshipMigrationTest {

    @Test
    fun migrate113To114_createsRelationshipTable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "muse_relationship_migration.db"
        val dbFile = context.getDatabasePath(dbName).apply {
            if (exists()) delete()
            parentFile?.mkdirs()
        }

        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(113) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                // Esqueleto mínimo: tanto como pueda 113→114 Simplemente ejecuta la migración
                                db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT NOT NULL PRIMARY KEY)")
                            }

                            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                        },
                    )
                    .build(),
            )

        helper.writableDatabase.use { db ->
            // Realice la migración directamente sin pasar por la verificación de entidad de Room
            MuseDb.MIGRATION_113_114.migrate(db)

            val columns = mutableMapOf<String, String?>()
            db.query("PRAGMA table_info(assistant_relationship)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(1)] = cursor.getString(4)
                }
            }

            assertTrue(
                "La tabla Assistant_Relationship debería existir después de la migración.\ncolumna real: $columns",
                columns.isNotEmpty(),
            )
            listOf(
                "assistantId",
                "relationLabel",
                "affinity",
                "lastInteractionAt",
                "proactiveLevel",
                "proactiveEnabled",
                "dailyProactiveLimit",
                "createdAt",
                "updatedAt",
            ).forEach { expected ->
                assertTrue("columna faltante $expected\ncolumna real: $columns", columns.containsKey(expected))
            }
            // El valor predeterminado de la clave debe ser coherente con la entidad; de lo contrario, la verificación de la habitación informará una discrepancia en la estructura.
            assertEquals("normal", columns["proactiveLevel"]?.trim('\'', '"'))
            assertEquals("1", columns["proactiveEnabled"])
            assertEquals("0", columns["affinity"])

            // Idempotente: la ejecución repetida no debería informar un error (la migración puede volver a ejecutarse debido a una interrupción)
            MuseDb.MIGRATION_113_114.migrate(db)
        }

        helper.close()
        if (dbFile.exists()) dbFile.delete()
        File(dbFile.absolutePath + "-wal").delete()
        File(dbFile.absolutePath + "-shm").delete()
    }
}
