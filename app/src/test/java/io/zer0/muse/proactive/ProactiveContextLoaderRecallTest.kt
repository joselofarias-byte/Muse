package io.zer0.muse.proactive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactEntity
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * v17: La derivación de mensajes activos no debe utilizar memorias que hayan "salido" (la puntuación está por debajo del umbral de caída).
 *
 * El resultado de esta ruta se escribirá en el contexto del mensaje activo, por lo que la memoria de la aparición se filtrará desde aquí.
 * Equivale a que la "atenuación" no surta efecto. Aquí el verdadero FactStore (in-memory Room)+ almacén falso/entorno operativo,
 * La verificación directa de los resultados de la derivación solo deja recuerdos que todavía están dentro del rango de recuperación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProactiveContextLoaderRecallTest {

    private lateinit var db: FactDb
    private lateinit var store: FactStore
    private lateinit var thoughts: ProactiveThoughtRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = FactStore(db.factDao(), db)
        thoughts = mockk(relaxed = true)
        coEvery { thoughts.getOpenSourceRefs(any()) } returns emptySet()
        coEvery { thoughts.note(any()) } returns true
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun loader(): ProactiveContextLoader = ProactiveContextLoader(
        stores = ProactiveStores(
            thoughts = thoughts,
            relations = mockk<ProactiveRelationRepository>(relaxed = true),
            outcomes = mockk<ProactiveOutcomeRepository>(relaxed = true),
        ),
        runtime = mockk(relaxed = true),
        sources = ProactiveSources(factStore = store, milestoneDao = mockk(relaxed = true)),
    )

    @Test
    fun `deriveThoughts skips suppressed memories`() = runTest {
        val now = Instant.now().toEpochMilli()
        // recuerdos que aún se recuerdan:Pulsa la regla CUIDADO,la preocupación debe derivarse
        val kept = store.add(FactStore.Fact(fact = "Últimamente tengo insomnio"))
        // Recuerdos que han aparecido:También cumple con la regla CARE,Pero no se le permite ingresar la derivación.
        db.factDao().insert(
            FactEntity(
                fact = "Últimamente siempre tengo dolor de cabeza",
                tags = "[]",
                createdAt = Instant.ofEpochMilli(now).toString(),
                importance = 0,
                suppressedAt = Instant.ofEpochMilli(now).toString(),
            ),
        )
        store.rebuildFtsIndex()

        loader().deriveThoughts("default", now)

        val recorded = slot<PendingThoughtEntity>()
        coVerify(exactly = 1) { thoughts.note(capture(recorded)) }
        assertEquals("Sólo se deben contabilizar los recuerdos que todavía se recuerdan.", "fact_$kept", recorded.captured.sourceRef)
        assertTrue("El contenido de la derivación no debe incluir recuerdos que ya hayan aparecido.", !recorded.captured.seed.contains("dolor de cabeza"))
    }
}
