package io.zer0.muse.miniphone.data.db

import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Autoprueba de la estructura de la biblioteca:Room Capaz de crear tablas basadas en definiciones de entidades y pasar su propia verificación de esquema.
 *
 * Abrirlo una vez con la biblioteca de memoria puede activar la verificación del esquema de Room(entidad/columna/Si el índice no es coherente con la declaración de creación de la tabla, se generará una excepción directamente.);
 * Aquí nuevamente, verifique los 8 nombres de tablas y números de versión uno por uno.,evitar"Se puede abrir pero a la mesa le falta una hoja.".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneDbSchemaTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createsEveryExpectedTable() {
        val tables = tableNames()

        EXPECTED_TABLES.forEach { table ->
            assertTrue("mesa faltante $table", tables.contains(table))
        }
        assertEquals(EXPECTED_TABLES.toSet(), tables)
    }

    @Test
    fun reportsDeclaredVersion() {
        assertEquals(MINI_PHONE_DB_VERSION, db.openHelper.readableDatabase.version)
    }

    @Test
    fun everyDaoIsReachable() {
        db.profileDao()
        db.threadDao()
        db.threadMemberDao()
        db.messageDao()
        db.momentDao()
        db.momentLikeDao()
        db.momentCommentDao()
        db.diaryDao()
        db.albumDao()
    }

    private fun tableNames(): Set<String> = db.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'mp_%'")
        .use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private companion object {
        val EXPECTED_TABLES: List<String> =
            listOf(
                "mp_profile",
                "mp_thread",
                "mp_thread_member",
                "mp_message",
                "mp_moment",
                "mp_moment_like",
                "mp_moment_comment",
                "mp_diary",
                "mp_album",
            )
    }
}
