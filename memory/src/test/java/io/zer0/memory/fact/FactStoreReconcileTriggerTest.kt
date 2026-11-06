package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 (Fiabilidad P1): La conciliación incremental después de que el hecho esté escrito desencadena la prueba.
 *
 * fondo: FACTS Los productos compilados fueron producidos originalmente solo por el canal diario.(12–24h)reconciliarse una vez,Usuario agregado en la página de memoria./Editar después del hecho
 * system prompt Todavía inyectando viejas expresiones. Después de la reparación, si add/addBatch/update se realiza correctamente, se activará una conciliación. hook,
 * Y hay estrangulamiento/Protección de fusión y recursividad:
 *  - Una sola adición/actualización se activa una vez hook,Llevar el texto final después de escribirlo y scope/space;
 *  - varias veces dentro de la ventana(lote)Las escrituras se fusionan en una llamada de enlace;
 *  - hook Escribir internamente la biblioteca nuevamente no activará la recursividad;
 *  - No inyectado hook(prueba única/Ninguno DI)Gastos generales de programación en tiempo cero,El comportamiento es consistente con versiones anteriores.
 *
 * Descripción: Utilice runBlocking en lugar de runTest —— hook Ejecutado en corrutina IO independiente.,runTest reloj virtual
 * El presupuesto de withTimeout se agotará antes de que se complete la tarea real en segundo plano.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactStoreReconcileTriggerTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao

    /** hook registro de llamadas(scope/space/facts). */
    private data class HookCall(
        val facts: List<String>,
        val scope: String,
        val spaceId: String,
    )

    private class RecordingHook : FactStore.FactReconcileHook {
        val calls = CopyOnWriteArrayList<HookCall>()

        override suspend fun onFactsChanged(facts: List<FactStore.Fact>, scope: String, spaceId: String) {
            calls.add(HookCall(facts.map { it.fact }, scope, spaceId))
        }

        /** Encuesta esperando la primera [count] llamadas(tiempo real,la mayoría [timeoutMs]). */
        suspend fun awaitCalls(count: Int, timeoutMs: Long = 5_000) {
            withTimeout(timeoutMs) {
                while (calls.size < count) delay(20)
            }
        }
    }

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

    private fun storeWithHook(hook: FactStore.FactReconcileHook): FactStore =
        FactStore(dao, db, reconcileHook = hook).also { it.reconcileDebounceMs = 120 }

    @Test
    fun `add triggers reconcile with written fact and scope`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)

        store.add(FactStore.Fact(fact = "Le gusta tomar café americano."), scope = "main", spaceId = "default")
        hook.awaitCalls(1)
        delay(300) // Espere posibles ventanas adicionales,Verifique que no se active repetidamente

        assertEquals("add Debería iniciarse una reconciliación", 1, hook.calls.size)
        assertEquals(listOf("Le gusta tomar café americano."), hook.calls[0].facts)
        assertEquals("main", hook.calls[0].scope)
        assertEquals("default", hook.calls[0].spaceId)
    }

    @Test
    fun `addBatch coalesces into a single reconcile call`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)

        store.addBatch(
            listOf(
                FactStore.Fact(fact = "Le gusta tomar café americano."),
                FactStore.Fact(fact = "A menudo salgo a correr al parque los fines de semana."),
                FactStore.Fact(fact = "aprendiendo guitarra"),
            ),
            scope = "main",
            spaceId = "default",
        )
        hook.awaitCalls(1)
        delay(300)

        assertEquals("Las escrituras por lotes deben limitarse y consolidarse en una conciliación", 1, hook.calls.size)
        assertEquals(
            setOf("Le gusta tomar café americano.", "A menudo salgo a correr al parque los fines de semana.", "aprendiendo guitarra"),
            hook.calls[0].facts.toSet(),
        )
    }

    @Test
    fun `rapid adds within debounce window coalesce`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)

        store.add(FactStore.Fact(fact = "Le gusta tomar café americano."))
        store.add(FactStore.Fact(fact = "A menudo salgo a correr al parque los fines de semana."))

        hook.awaitCalls(1)
        delay(300)
        assertEquals("Las escrituras continuas dentro de la ventana deben combinarse en una conciliación", 1, hook.calls.size)
        assertEquals(2, hook.calls[0].facts.size)
    }

    @Test
    fun `update triggers reconcile with new content`() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)
        val id = store.add(FactStore.Fact(fact = "Le gusta tomar café americano."))
        hook.awaitCalls(1)
        delay(250)

        store.update(id, "Le gusta beber café hecho a mano.")
        hook.awaitCalls(2)
        delay(250)

        assertEquals(2, hook.calls.size)
        assertEquals(listOf("Le gusta beber café hecho a mano."), hook.calls[1].facts)
    }

    @Test
    fun deleteTriggersReconcileWithDeletedFactScope() = runBlocking {
        val hook = RecordingHook()
        val store = storeWithHook(hook)
        val id = store.add(FactStore.Fact(fact = "hechos para olvidar"), scope = "assistant-a", spaceId = "work")
        hook.awaitCalls(1)
        delay(250)

        assertTrue(store.delete(id))
        hook.awaitCalls(2)
        delay(250)

        assertEquals("assistant-a", hook.calls[1].scope)
        assertEquals("work", hook.calls[1].spaceId)
        assertEquals(listOf("hechos para olvidar"), hook.calls[1].facts)
    }

    @Test
    fun `hook writing back into store does not recurse`() = runBlocking {
        val calls = AtomicInteger(0)
        var store: FactStore? = null
        val hook = FactStore.FactReconcileHook { _, _, _ ->
            calls.incrementAndGet()
            // hook Escribe la biblioteca internamente nuevamente.:La reconciliación no debe volver a desencadenarse(Anti-recursión),Aprovecha la línea de montaje diaria
            store?.update(1L, "hook Texto reescrito internamente")
        }
        store = storeWithHook(hook)

        store.add(FactStore.Fact(fact = "Le gusta tomar café americano."))
        withTimeout(5_000) {
            while (calls.get() < 1) delay(20)
        }
        delay(400)

        assertEquals("hook Las escrituras internas no deben desencadenar la conciliación de forma recursiva", 1, calls.get())
    }

    @Test
    fun `no hook configured keeps writes working without scheduling`() = runBlocking {
        // construcción predeterminada(hook=null,Sin análisis DI)El comportamiento es consistente con la versión anterior.:La escritura es exitosa y no se lanza ninguna excepción.
        val store = FactStore(dao, db)
        val id = store.add(FactStore.Fact(fact = "Le gusta tomar café americano."))
        assertTrue(id > 0)
        assertEquals("Le gusta tomar café americano.", store.getById(id)?.fact)
        assertTrue(store.update(id, "Le gusta beber café hecho a mano."))
        assertEquals("Le gusta beber café hecho a mano.", store.getById(id)?.fact)
    }
}
