package io.zer0.memory.fact

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R-DB-03 + v2.2.1 Version Guard ha vuelto.
 *
 * Portada:
 *  - Los primeros archivos de la biblioteca v1/v2 se reconstruyen y se marcan con indicaciones(Comportamiento existente)
 *  - Versión actual(v14)La reapertura de la biblioteca no debe archivarse(Pruebas de regresión de defectos históricos de Guards 13)
 *  - Las bibliotecas posteriores aún deberían archivarse
 *  - Recuperación única de copias de seguridad archivadas por error(Y nunca se restaurará si no hay una marca de archivo.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class FactDbLegacyResetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `legacy v1 database is archived and flagged for ui hint`() {
        val name = "facts_test_v1.db"
        createDatabase(name, version = 1)

        FactDb.create(context, name)

        assertFalse(context.getDatabasePath(name).exists())
        assertTrue(context.getDatabasePath("$name.bak").exists())
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `legacy v2 database is archived and flagged for ui hint`() {
        val name = "facts_test_v2.db"
        createDatabase(name, version = 2)

        FactDb.create(context, name)

        assertFalse(context.getDatabasePath(name).exists())
        assertTrue(context.getDatabasePath("$name.bak").exists())
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `current version database is not archived`() {
        val name = "facts_test_v11.db"
        createDatabase(name, version = 11)

        FactDb.create(context, name)

        assertTrue(context.getDatabasePath(name).exists())
        assertFalse(context.getDatabasePath("$name.bak").exists())
        assertFalse(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `current database survives reopen without archive and keeps facts`() {
        val name = "facts_test_current_reopen.db"
        // Abrir y escribir un hecho por primera vez.(Simular el uso normal)
        val db1 = FactDb.create(context, name)
        db1.openHelper.writableDatabase.execSQL(
            "INSERT INTO facts (fact, tags, created_at) VALUES ('Hechos de las pruebas de regresión', '[]', '2026-09-27T20:00:00')",
        )
        assertEquals(1, countFacts(name))
        db1.close()

        // Abrir de nuevo(Simular reinicio de la aplicación):El guardián de versiones no debe archivar ni reconstruir accidentalmente la versión actual de la biblioteca real.
        val db2 = FactDb.create(context, name)
        assertEquals("El archivo no debe borrar la biblioteca de la versión actual cuando se vuelve a abrir.", 1, countFacts(name))
        assertFalse(
            File(context.getDatabasePath(name).parentFile, "$name.pre-destructive.bak").exists(),
        )
        db2.close()
        cleanup(name)
    }

    @Test
    fun `newer version database is archived`() {
        val name = "facts_test_v${FACT_DB_VERSION + 1}.db"
        val db = FactDb.create(context, name)
        db.openHelper.writableDatabase.version = FACT_DB_VERSION + 1
        db.close()

        FactDb.create(context, name)

        assertTrue(
            File(context.getDatabasePath(name).parentFile, "$name.pre-destructive.bak").exists(),
        )
        assertTrue(MemoryLegacyReset.consume(context))
        cleanup(name)
    }

    @Test
    fun `misarchived backup is recovered once on open`() {
        val name = "facts_test_recover.db"
        val bakName = "$name.pre-destructive.bak"
        // fabricación"víctima"En el sitio: Base de datos principal vacía(Biblioteca vacía v14 realmente lanzada) + La copia de seguridad archivada accidentalmente contiene datos + etiquetas de archivo no consumidas
        createEmptyDb(name)
        val bak = FactDb.create(context, bakName)
        bak.openHelper.writableDatabase.execSQL(
            "INSERT INTO facts (fact, tags, created_at) VALUES ('Hechos que fueron mal archivados', '[]', '2026-09-27T20:00:00')",
        )
        bak.close()
        MemoryLegacyReset.mark(context, name)

        // abierto:Se debe activar una recuperación única——Solicitudes de recuperación de datos, migración de copias de seguridad y registro de números de recuperación
        val reopened = FactDb.create(context, name)
        assertEquals(1, countFacts(name))
        assertFalse(context.getDatabasePath(bakName).exists())
        assertEquals(1, MemoryLegacyReset.consumeRecovered(context))
        reopened.close()
        cleanup(name)
        runCatching { context.getDatabasePath(bakName).delete() }
    }

    @Test
    fun `recovery does not run without pending archive flag`() {
        // Cuando no hay marca de archivo:No restaurar incluso si existe una copia de seguridad que no esté vacía(protegerse contra"Después de borrar todos los recuerdos, los datos antiguos se convierten en un cadáver.")
        val name = "facts_test_no_recover.db"
        val bakName = "$name.pre-destructive.bak"
        createEmptyDb(name)
        val bak = FactDb.create(context, bakName)
        bak.openHelper.writableDatabase.execSQL(
            "INSERT INTO facts (fact, tags, created_at) VALUES ('No debería resucitar', '[]', '2026-09-27T20:00:00')",
        )
        bak.close()

        val reopened = FactDb.create(context, name)

        assertEquals(0, countFacts(name))
        assertTrue(context.getDatabasePath(bakName).exists())
        reopened.close()
        cleanup(name)
        runCatching { context.getDatabasePath(bakName).delete() }
    }

    /** Cree e implemente una biblioteca vacía v14 real(Room Creación de bases de datos perezosa,Se necesita un toque para realizar el pedido. schema). */
    private fun createEmptyDb(name: String) {
        val db = FactDb.create(context, name)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM facts").use { it.moveToFirst() }
        db.close()
    }

    private fun countFacts(name: String): Int = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM facts", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun createDatabase(name: String, version: Int) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.version = version
        } finally {
            db.close()
        }
    }

    private fun cleanup(name: String) {
        listOf(
            name,
            "$name.bak",
            "$name-wal",
            "$name-shm",
            "$name.bak-wal",
            "$name.bak-shm",
            "$name.pre-destructive.bak",
            "$name.pre-destructive.bak-wal",
            "$name.pre-destructive.bak-shm",
            "$name.replaced-by-recovery.bak",
        ).forEach {
            runCatching { context.getDatabasePath(it).delete() }
        }
    }
}
