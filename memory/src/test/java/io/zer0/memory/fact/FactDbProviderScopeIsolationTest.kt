package io.zer0.memory.fact

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-4 Regresar:La interfaz de usuario de Memory Center comparte las mismas primitivas de enrutamiento de biblioteca que las escrituras de producción.
 *
 * lado de producción(DeepMemoryProcessor / MemoryAutoSaveScheduler / SystemPromptAssembler)
 * Escribir hechos según la base de datos del asistente.([FactDbProvider.getFactStore]),UI del Centro de memoria Ahora presione lo mismo provider
 * tienda de análisis. Esta prueba verifica las restricciones principales.:
 *  - UI Escribir en el alcance del asistente infantil → Esta subbiblioteca puede volver a leer(Anteriormente caía en la biblioteca predeterminada,No válido para IA)
 *  - Las subbibliotecas de otros ámbitos no se ven afectadas.(escribe aislamiento)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactDbProviderScopeIsolationTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `write to sub-assistant scope is read back from its own db and isolated from others`() = runTest {
        val provider = FactDbProvider(context)
        try {
            val storeA = provider.getFactStore("assistant_a")
            val storeB = provider.getFactStore("assistant_b")
            val defaultStore = provider.getFactStore("default")

            val addedId = storeA.add(
                FactStore.Fact(fact = "A los usuarios les gusta el tema azul"),
                scope = "assistant_a",
                spaceId = "default",
            )
            assertTrue("La escritura en la subbiblioteca del subasistente debe realizarse correctamente", addedId > 0)

            // El alcance objetivo puede volver a leer
            val readBack = storeA.getByScopeAndSpace("assistant_a", "default")
            assertTrue("El hecho de que el repositorio de destino debe poder leer las escrituras de la interfaz de usuario", readBack.any { it.id == addedId && it.fact.contains("tema azul") })

            // Otras bibliotecas de alcance no se ven afectadas
            assertEquals("Otros subasistentes no deben leer este hecho.", 0, storeB.getByScopeAndSpace("assistant_b", "default").size)
            assertEquals("La biblioteca principal auxiliar predeterminada no debe leer este hecho.", 0, defaultStore.getByScopeAndSpace("main", "default").size)
        } finally {
            provider.releaseAll()
        }
    }

    @Test
    fun `main scope write stays in default db`() = runTest {
        val provider = FactDbProvider(context)
        try {
            val defaultStore = provider.getFactStore("default")

            defaultStore.add(FactStore.Fact(fact = "Ejemplo de hechos del asistente maestro"), scope = "main", spaceId = "default")

            assertTrue(defaultStore.getByScopeAndSpace("main", "default").isNotEmpty())
        } finally {
            provider.releaseAll()
        }
    }

    /**
     * SEC-04 Requisito previo: autoincremento de cada biblioteca sucursal id **Habrá colisiones**.
     *
     * Esta es la razón por la que la "búsqueda entre bases de datos mediante ID simple" seguramente saldrá mal——Editor del Centro de la Memoria/Eliminar/Fijado en la parte superior debe tener margen para un posicionamiento preciso
     * (Ver `MemoryViewModel.storeForFact`), la propiedad no se puede adivinar basándose en la identificación. Si un día la subbiblioteca se cambia a una identificación global única,
     * Esto fallará y luego podrá simplificar de forma segura la lógica de enrutamiento.
     */
    @Test
    fun `fact ids collide across per-assistant databases`() = runTest {
        val provider = FactDbProvider(context)
        try {
            val storeA = provider.getFactStore("assistant_a")
            val storeB = provider.getFactStore("assistant_b")

            val idA = storeA.add(
                FactStore.Fact(fact = "A hechos del asistente"),
                scope = "assistant_a",
                spaceId = "default",
            )
            val idB = storeB.add(
                FactStore.Fact(fact = "B hechos del asistente"),
                scope = "assistant_b",
                spaceId = "default",
            )

            assertEquals("Cada una de las dos subbases de datos se incrementa y la primera identificación de hecho es la misma.", idA, idB)
            assertTrue("A La biblioteca lee los registros de A según su propia identificación.", storeA.getById(idA)?.fact?.contains("A hechos") == true)
            assertTrue("B La biblioteca lee el registro de B según la misma identificación.", storeB.getById(idB)?.fact?.contains("B hechos") == true)
        } finally {
            provider.releaseAll()
        }
    }
}
