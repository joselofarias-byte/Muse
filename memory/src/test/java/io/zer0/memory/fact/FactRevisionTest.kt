package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v13 (T4-1): Prueba de historial de revisión de hechos.
 *  - memoria clave(importance≥1)update Revisión de registros
 *  - Fusionar revisiones de registros(Se puede rastrear el texto original de la parte fusionada.)
 *  - hechos ordinarios(importance=0)no grabar
 *  - Retroceder para restaurar valores antiguos
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactRevisionTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var revisionDao: FactRevisionDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        revisionDao = db.factRevisionDao()
        store = FactStore(dao, db, revisionDao = revisionDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `update of important fact records revision`() = runTest {
        val id = store.add(FactStore.Fact(fact = "El primer borrador del artículo deberá entregarse el próximo miércoles.", importance = 1, entityKey = "estudios"))
        store.update(id, "El primer borrador del artículo deberá entregarse el próximo viernes.")

        val revisions = store.getRevisions(id)
        assertEquals("Las actualizaciones fácticas importantes deben registrar las revisiones", 1, revisions.size)
        assertEquals("El valor anterior debe conservarse.", "El primer borrador del artículo deberá entregarse el próximo miércoles.", revisions[0].oldContent)
        assertEquals("El nuevo valor debe registrarse", "El primer borrador del artículo deberá entregarse el próximo viernes.", revisions[0].newContent)
        assertTrue("reason debería ser update", revisions[0].reason.contains("update"))
    }

    @Test
    fun `merge records revision with merged wording`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "A Zhang San le gusta la fotografía", entityKey = "Zhang San"))
        store.add(FactStore.Fact(fact = "Al profesor Zhang San le gusta la fotografía", entityKey = "Zhang San"))

        val revisions = store.getRevisions(id1)
        assertTrue("La fusión debe registrar las revisiones", revisions.isNotEmpty())
        assertTrue("reason debería ser merge", revisions[0].reason.contains("merge"))
    }

    @Test
    fun `ordinary fact update does not record revision`() = runTest {
        // Hechos sencillos sin palabras clave importantes ni claves de entidad
        val id = store.add(FactStore.Fact(fact = "El usuario tiene un gato."))
        store.update(id, "El usuario tiene un gato naranja.")

        assertEquals("Los hechos ordinarios no registran revisiones.", 0, store.getRevisions(id).size)
    }

    @Test
    fun `revert restores old content`() = runTest {
        val id = store.add(FactStore.Fact(fact = "El primer borrador del artículo deberá entregarse el próximo miércoles.", importance = 1, entityKey = "estudios"))
        store.update(id, "El primer borrador del artículo deberá entregarse el próximo viernes.")
        val revisions = store.getRevisions(id)
        assertTrue("debe ser revisado", revisions.isNotEmpty())

        val reverted = store.revertToRevision(id, revisions[0].id)
        assertTrue("La reversión debería tener éxito", reverted)
        assertEquals("El contenido restaura el valor anterior", "El primer borrador del artículo deberá entregarse el próximo miércoles.", store.getById(id)!!.fact)
    }
}
