package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Corrección de simultaneidad de escrituras en el espacio de trabajo: lecturas del mismo archivo—cambiar—Escribe en serie, diferentes archivos en paralelo.
 *
 * Fija tres cosas:
 *  1. concurrencia `workspace_edit` Cuando se trabaja con el mismo archivo, todos los cambios se conservarán en el resultado, en lugar de escribirse más tarde y sobrescribirse primero;
 *  2. Los diferentes archivos no se bloquean entre sí (dos lectores—cambiar—La escritura puede estar en la sección crítica al mismo tiempo);
 *  3. Ni las rutas de excepción ni las rutas rechazadas contienen bloqueos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceEditConcurrencyTest {

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = WorkspaceManager(context)
    }

    private fun seed(path: String, content: String) {
        val result = runBlocking { manager.writeFile(path, content) }
        assertTrue("El dispositivo de escritura falló: $result", result is WorkspaceManager.OpResult.Success)
    }

    private fun contentOf(path: String): String =
        runBlocking {
            val result = manager.readFile(path)
            assertTrue("Error de lectura: $result", result is WorkspaceManager.ReadResult.Success)
            (result as WorkspaceManager.ReadResult.Success).content
        }

    private fun edit(path: String, oldString: String, newString: String): String =
        WorkspaceTool.execute(
            WorkspaceTool.NAME_EDIT,
            mapOf("path" to path, "old_string" to oldString, "new_string" to newString),
            manager,
        )

    @Test
    fun `Cuando workspace_edits el mismo archivo simultáneamente, cada cambio se escribe en el disco.`() {
        // Número de dos dígitos: ninguna marca es un prefijo de otras marcas, lo que garantiza que old_string sea única en el archivo.
        val tokens = (1..12).map { it.toString().padStart(2, '0') }
        seed("race.txt", tokens.joinToString("\n") { "item $it" } + "\n")

        val receipts = runBlocking {
            coroutineScope {
                tokens.map { token ->
                    async(Dispatchers.IO) {
                        edit("race.txt", "item $token", "done $token")
                    }
                }.awaitAll()
            }
        }

        receipts.forEachIndexed { index, receipt ->
            assertTrue("No. $index Error al editar: $receipt", receipt.startsWith("OK:"))
        }

        val finalContent = contentOf("race.txt")
        tokens.forEach { token ->
            assertTrue("Cambiar \"$token\"Sobreescrito por otras escrituras:\n$finalContent", finalContent.contains("done $token"))
            assertTrue("Contenido antiguo \"$token\"Todavía ahí:\n$finalContent", !finalContent.contains("item $token"))
        }
    }

    @Test
    fun `Leer, modificar y escribir el mismo archivo no ingresará a la sección crítica al mismo tiempo.`() = runBlocking {
        seed("serial.txt", "start\n")
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        coroutineScope {
            (1..6).map { i ->
                async(Dispatchers.IO) {
                    manager.editFile("serial.txt") { original ->
                        val now = active.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        try {
                            Thread.sleep(5)
                            WorkspaceManager.EditDecision.Commit(original + "line$i\n")
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals("No debe haber dos lectores del mismo archivo—cambiar—escribe escalonado", 1, peak.get())
        val finalContent = contentOf("serial.txt")
        (1..6).forEach { i -> assertTrue("No. $i Tiempos adicionales perdidos:\n$finalContent", finalContent.contains("line$i\n")) }
    }

    @Test
    fun `La lectura, modificación y escritura de diferentes archivos se pueden realizar simultáneamente`() = runBlocking {
        seed("p1.txt", "a\n")
        seed("p2.txt", "b\n")
        // Sólo cuando ambas partes entren en la sección crítica serán liberadas: si dos cerraduras se combinan en una, la segunda nunca podrá entrar.
        val barrier = CyclicBarrier(2)

        val results = listOf("p1.txt", "p2.txt").map { path ->
            async(Dispatchers.IO) {
                manager.editFile(path) { original ->
                    barrier.await(5, TimeUnit.SECONDS)
                    WorkspaceManager.EditDecision.Commit(original + "x\n")
                }
            }
        }.awaitAll()

        results.forEach { assertTrue("Editar falló: $it", it is WorkspaceManager.EditResult.Applied) }
        assertTrue(contentOf("p1.txt").endsWith("x\n"))
        assertTrue(contentOf("p2.txt").endsWith("x\n"))
    }

    @Test
    fun `El bloqueo aún se liberará después de leer, modificar y escribir una excepción.`() = runBlocking {
        seed("boom.txt", "x\n")

        val boom = runCatching {
            manager.editFile("boom.txt") { throw IllegalStateException("boom") }
        }
        assertTrue("Las excepciones deben lanzarse tal cual,real=$boom", boom.exceptionOrNull() is IllegalStateException)

        val after = manager.editFile("boom.txt") { original ->
            WorkspaceManager.EditDecision.Commit(original + "y\n")
        }
        assertTrue("El bloqueo no se libera después de lanzar la excepción.: $after", after is WorkspaceManager.EditResult.Applied)
        assertTrue(contentOf("boom.txt").endsWith("y\n"))
    }

    @Test
    fun `El mismo archivo aún se puede editar después de una edición rechazada.`() {
        seed("reject.txt", "alpha\n")

        val rejected = edit("reject.txt", "fragmento no encontrado", "x")
        assertTrue(rejected, rejected.startsWith("Error:"))

        val ok = edit("reject.txt", "alpha", "beta")
        assertTrue(ok, ok.startsWith("OK:"))
        assertEquals("beta\n", contentOf("reject.txt"))
    }
}
