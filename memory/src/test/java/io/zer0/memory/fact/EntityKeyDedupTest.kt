package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v12: Clave de normalización de entidad(EntityKey)Eliminar pruebas duplicadas.
 *
 * escenario objetivo: Comentarios de los usuarios"Hay 3 memorias duplicadas para el mismo nombre de usuario"(Como por ejemplo"Zhang San"/"Sr. Zhang"/"Maestro Zhang San").
 * Después de la reparación, el hecho de que el texto bajo la misma clave de entidad sea esencialmente el mismo debe fusionarse en uno,En lugar de agregar tres nuevos.
 *
 * Portada:
 *  - El mismo hecho escrito de diferentes maneras bajo la misma clave de entidad → Sólo 1 artículo reservado
 *  - Diferentes hechos bajo la misma entidadClave(Como por ejemplo"como el cafe" vs "odio el cafe")→ Fusionar sin error
 *  - Misma entidad clave y texto bajo diferente espacio Id. → No fusionar espacios
 *  - update Repetir con hechos existentes. → desencadenar fusión(Acceso a la ruta de actualización y deduplicación)
 *  - normalización: ancho completo/caso/diferencia de espacio en blanco → juzgado como el mismo hecho
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EntityKeyDedupTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        store = FactStore(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `same entity key different wordings merge into one fact`() = runTest {
        // Escenarios de comentarios de los usuarios: 3 formas de escribir la misma entidad,entity_key todos son"Zhang San"
        val id1 = store.add(FactStore.Fact(fact = "A Zhang San le gusta la fotografía", entityKey = "Zhang San"))
        val id2 = store.add(FactStore.Fact(fact = "Al Sr. Zhang le gusta la fotografía.", entityKey = "Zhang San"))
        val id3 = store.add(FactStore.Fact(fact = "Al profesor Zhang San le gusta la fotografía", entityKey = "Zhang San"))

        // Los tres artículos deberían fusionarse en uno solo.(Los dos últimos se fusionan en el primero.,volver lo mismo id)
        assertEquals("La segunda escritura debe fusionarse y devolver el existente. id", id1, id2)
        assertEquals("La tercera escritura debe fusionarse y devolver el existente. id", id1, id3)

        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("Sólo debe haber 1 elemento con la misma entidad y el mismo hecho.", 1, all.size)
        assertEquals("mantener la declaración completa", "Al profesor Zhang San le gusta la fotografía", all[0].fact)
        assertEquals("Las claves de entidad deben conservarse", "Zhang San", all[0].entityKey)
    }

    @Test
    fun `same entity key different facts do not merge`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", entityKey = "Zhang San"))
        val id2 = store.add(FactStore.Fact(fact = "Zhang San odia el cilantro", entityKey = "Zhang San"))

        assertTrue("Los diferentes identificadores de hechos deberían ser diferentes", id1 != id2)
        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("Si la misma entidad tiene hechos diferentes, se deben conservar 2 elementos.", 2, all.size)
    }

    @Test
    fun `same entity key across spaces stays isolated`() = runTest {
        val work = store.add(FactStore.Fact(fact = "A Zhang San le gusta el café americano", entityKey = "Zhang San"), spaceId = "work")
        val life = store.add(FactStore.Fact(fact = "A Zhang San le gusta el café americano", entityKey = "Zhang San"), spaceId = "life")

        assertTrue("No fusionar espacios,id debería ser diferente", work != life)
        assertEquals("work Espacio 1 artículo", 1, store.getByScopeAndSpace("main", "work").size)
        assertEquals("life Espacio 1 artículo", 1, store.getByScopeAndSpace("main", "life").size)
    }

    @Test
    fun `update triggering dedup merges into existing fact`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "Li Si está aprendiendo a nadar.", entityKey = "Juan Pérez"))
        val id2 = store.add(FactStore.Fact(fact = "Li Si está aprendiendo a tocar la guitarra.", entityKey = "Juan Pérez"))

        // Cambie id2 a algo esencialmente igual que id1 → debería fusionarse(Eliminar id2,Reservar id1)
        store.update(id2, "Li Si está aprendiendo a nadar.", "main")

        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("update Después de activar la deduplicación, solo debería quedar 1 elemento.", 1, all.size)
        assertNull("El id2 fusionado debería haberse eliminado", store.getById(id2))
        assertNotNull("id1 todavía existe después de fusionarse", store.getById(id1))
    }

    @Test
    fun `normalization treats full-width case and space differences as identical`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "Zhang San likes coffee", entityKey = "zhang san"))
        // Letras de ancho completo + espacios extra + diferencias de mayúsculas,Después de la normalización, se debe juzgar como el mismo hecho que id1.
        val id2 = store.add(FactStore.Fact(fact = "zhang san  likes coffee", entityKey = "zhang san"))

        assertEquals("Después de la normalización, deben fusionarse y volver al mismo id", id1, id2)
        assertEquals(1, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `explicit entity key wins over inferred`() = runTest {
        // Cuando la clave de entidad explícita es diferente de la inferencia de texto,Sujeto a explícita
        val id = store.add(FactStore.Fact(fact = "A Wang Wu le gusta correr", entityKey = "wang-wu"))
        val retrieved = store.getById(id)
        assertNotNull(retrieved)
        assertEquals("La clave de entidad explícita tiene prioridad", "wang-wu", retrieved!!.entityKey)
    }

    @Test
    fun `inferred entity key from name placeholder`() = runTest {
        // PiiGuard El marcador de posición del nombre de la persona insensibilizada debe usarse como clave de entidad.
        val id = store.add(FactStore.Fact(fact = "{{name_0}} Me gusta la fotografía"))
        val retrieved = store.getById(id)
        assertNotNull(retrieved)
        assertEquals("Marcadores de posición insensibilizados como claves de entidad", "{{name_0}}", retrieved!!.entityKey)
    }
}
