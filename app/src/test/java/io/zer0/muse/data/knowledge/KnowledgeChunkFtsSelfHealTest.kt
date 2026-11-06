package io.zer0.muse.data.knowledge

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-TEST-23: knowledge_chunks_fts Pruebas de regresión de autocuración.
 *
 * Simule dos tipos de fallas directas del R-DB-02:
 *  - la tabla no existe(no such table)
 *  - La tabla principal se elimina pero la tabla sombra permanece.(vtable constructor failed residuos comunes de)
 *
 * La verificación se logra a través del contenedor de recuperación automática predeterminado de la interfaz DAO real + raw falso:Activar en el primer fallo
 * [KnowledgeChunkFtsSelfHealer.repair],Los reintentos posteriores tuvieron éxito.
 * Nota:app El módulo Robolectric no es compatible con SQLite FTS4 vtable,Por lo tanto, no depende de la creación de tablas reales.
 */
class KnowledgeChunkFtsSelfHealTest {

    private lateinit var dao: FakeFtsDao

    @Before
    fun setUp() {
        dao = FakeFtsDao()
        KnowledgeChunkFtsSelfHealer.install { dao.repair() }
    }

    @After
    fun tearDown() {
        KnowledgeChunkFtsSelfHealer.install {}
    }

    @Test
    fun insert_repairsMissingTableAndRetries() = runTest {
        dao.failInsert = true

        dao.insert("chunk-1", "doc-1", "Lista completa de razas de gatos")

        assertEquals("La autocuración solo debe activarse una vez después de que desaparezca la mesa.", 1, dao.repairCount)
        assertTrue("Volver a intentarlo después de la autocuración debería tener éxito", dao.inserted)
    }

    @Test
    fun deleteByDoc_clearsShadowTablesAndRetries() = runTest {
        dao.shadowPresent = true
        dao.failDelete = true

        dao.deleteByDoc("doc-1")

        assertEquals("Los residuos de la mesa de sombra deberían desencadenar una autocuración", 1, dao.repairCount)
        assertTrue("Volver a intentarlo después de la autocuración debería tener éxito", dao.deleted)
        assertFalse("La autocuración debería eliminar los residuos de la mesa de sombra", dao.shadowPresent)
    }

    private class FakeFtsDao : KnowledgeChunkFtsDao {
        var failInsert: Boolean = false
        var failDelete: Boolean = false
        var shadowPresent: Boolean = false
        var repairCount: Int = 0
        var inserted: Boolean = false
        var deleted: Boolean = false

        override suspend fun insertRaw(chunkId: String, docId: String, content: String) {
            if (failInsert) {
                failInsert = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
            inserted = true
        }

        override suspend fun deleteByDocRaw(docId: String) {
            if (failDelete) {
                failDelete = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
            deleted = true
        }

        override suspend fun deleteByChunkIdsRaw(chunkIds: List<String>) {
            if (failDelete) {
                failDelete = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
        }

        override suspend fun deleteAllRaw() {
            if (failDelete) {
                failDelete = false
                throw RuntimeException("no such table: knowledge_chunks_fts")
            }
        }

        override suspend fun searchBm25(query: String, limit: Int): List<KnowledgeChunkFtsHit> = emptyList()

        fun repair() {
            repairCount++
            shadowPresent = false
        }
    }
}
