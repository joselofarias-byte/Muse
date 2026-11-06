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
 * v12: LLM Pruebas de capa de decisión de deduplicación.
 *
 * escena central: capa de algoritmo(Similitud de personajes/Clave de entidad)Pares de candidatos difusos indeterminados,Deje que el gran modelo juzgue.
 *  - Dos elementos que son semánticamente equivalentes pero que tienen expresiones muy diferentes(capa de algoritmo miss)→ judge juicio same → fusionar
 *  - Diferentes hechos bajo una misma entidad → judge juicio not same → no fusionar
 *  - judge No disponible/baja confianza → Preferiría no fusionarse,Sin bloqueo de escrituras
 *  - El mismo par de hechos sólo se pregunta una vez judge(caché)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LlmDedupJudgeIntegrationTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** controlable fake judge — Registrar el número de llamadas,Volver por defecto. */
    private class FakeJudge(
        private val verdict: DedupVerdict,
    ) : FactDedupJudge {
        var calls = 0
        var lastA: String? = null
        var lastB: String? = null

        override suspend fun judge(a: String, b: String, entityKeyA: String?, entityKeyB: String?): DedupVerdict {
            calls++
            lastA = a
            lastB = b
            return verdict
        }
    }

    @Test
    fun `algorithm miss but judge says same merges`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = true, confidence = 0.95f, reason = "equivalencia semántica"))
        val store = FactStore(dao, db, dedupJudge = judge)

        val id1 = store.add(FactStore.Fact(fact = "El usuario tiene un corgi.", entityKey = "usuario"))
        // El algoritmo no los ve parecidos; el juez LLM sí.
        val id2 = store.add(FactStore.Fact(fact = "En casa vive un perro pequeño de orejas paradas.", entityKey = "usuario"))

        assertEquals("LLM Si se determina que son idénticos, deben fusionarse.", id1, id2)
        assertTrue("judge debería ser llamado", judge.calls >= 1)
        val all = store.getByScopeAndSpace("main", "default")
        assertEquals("Sólo debería quedar 1", 1, all.size)
    }

    @Test
    fun `judge says not same keeps both`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = false, confidence = 0.9f, reason = "diferentes hechos"))
        val store = FactStore(dao, db, dedupJudge = judge)

        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", entityKey = "Zhang San"))
        val id2 = store.add(FactStore.Fact(fact = "Zhang San odia el cilantro", entityKey = "Zhang San"))

        assertTrue("No se deben combinar hechos diferentes", id2 > 1)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `low confidence same does not merge`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = true, confidence = 0.3f, reason = "No estoy seguro"))
        val store = FactStore(dao, db, dedupJudge = judge)

        store.add(FactStore.Fact(fact = "A los usuarios les gusta madrugar", entityKey = "Usuario"))
        val id2 = store.add(FactStore.Fact(fact = "Está acostumbrado a levantarse a las cinco.", entityKey = "Usuario"))

        assertTrue("La baja confianza no debe fusionarse", id2 > 1)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `judge exception degrades to not same without blocking`() = runTest {
        val judge = object : FactDedupJudge {
            override suspend fun judge(a: String, b: String, entityKeyA: String?, entityKeyB: String?): DedupVerdict =
                throw RuntimeException("LLM timeout")
        }
        val store = FactStore(dao, db, dedupJudge = judge)

        store.add(FactStore.Fact(fact = "A los usuarios les gusta madrugar", entityKey = "Usuario"))
        val id2 = store.add(FactStore.Fact(fact = "Está acostumbrado a levantarse a las cinco.", entityKey = "Usuario"))

        assertTrue("judge Las excepciones deben degradarse y no fusionarse", id2 > 1)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `same pair judged only once via cache`() = runTest {
        val judge = FakeJudge(DedupVerdict(same = false, confidence = 0.9f, reason = "diferente"))
        val store = FactStore(dao, db, dedupJudge = judge)

        // Escriba el mismo texto dos veces bajo la misma clave de entidad,Ir al caché por segunda vez después de activar el juez
        store.add(FactStore.Fact(fact = "A Li Si le gusta correr", entityKey = "Juan Pérez"))
        store.add(FactStore.Fact(fact = "A Li Si le gusta nadar.", entityKey = "Juan Pérez"))
        store.add(FactStore.Fact(fact = "A Li Si le gusta nadar.", entityKey = "Juan Pérez"))

        assertTrue("El mismo hecho se puede preguntar como máximo una vez.", judge.calls <= 2)
    }

    @Test
    fun `noop judge never calls llm and behaves like before`() = runTest {
        val store = FactStore(dao, db) // Predeterminado NoopFactDedupJudge
        store.add(FactStore.Fact(fact = "A Zhang San le gusta la fotografía", entityKey = "Zhang San"))
        val id2 = store.add(FactStore.Fact(fact = "A Zhang San le gusta la fotografía", entityKey = "Zhang San"))
        assertEquals("Noop La capa de algoritmo aún fusiona los duplicados exactos inferiores", 1L, id2)
        assertEquals(1, store.getByScopeAndSpace("main", "default").size)
    }
}
