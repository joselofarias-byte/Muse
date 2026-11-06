package io.zer0.muse.hook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HookRegistry Pruebas de regresión del calibre del balde.
 *
 * fondo:En el lado del registro, ingrese el depósito según la clase de implementación.(`hook::class`),Presione la consulta de interfaz en el lado de despacho,
 * Los calibres inconsistentes en ambos lados hacen que todas las cadenas Hook siempre obtengan tablas vacías.,sin lanzar ninguna excepción
 * —— Inyección de libros mundiales, recorte de piso, complemento Prompt_finalize, archivo de capacidad del equipo
 * Por lo tanto, los cuatro conjuntos de funciones se han desactivado silenciosamente durante un período de tiempo. No hay ninguna excepción a detectar para este tipo de error.,Sólo puedes confiar en la afirmación y la mirada.
 */
class HookRegistryTest {

    private class ComposeHook(override val id: String, override val priority: Int = 50) :
        SystemPromptComposeHook

    /** Clase de implementación que implementa dos interfaces al mismo tiempo.,Se utiliza para verificar que \"una instancia ingresa a varios depósitos\". */
    private class DualHook(override val id: String) : SystemPromptComposeHook, PromptFinalizeHook

    private class DisabledHook(override val id: String) : PromptFinalizeHook {
        override val enabled: Boolean = false
    }

    @Test
    fun `register by implementation class is queryable through interface`() {
        val registry = HookRegistry()
        registry.register(ComposeHook("compose-1"))

        val hooks = registry.getHooks(SystemPromptComposeHook::class)

        assertEquals(1, hooks.size)
        assertEquals("compose-1", hooks.first().id)
    }

    @Test
    fun `one hook implementing two interfaces lands in both buckets`() {
        val registry = HookRegistry()
        registry.register(DualHook("dual-1"))

        assertEquals(1, registry.getHooks(SystemPromptComposeHook::class).size)
        assertEquals(1, registry.getHooks(PromptFinalizeHook::class).size)
        assertEquals(1, registry.size())
    }

    @Test
    fun `unregister removes the hook from every bucket`() {
        val registry = HookRegistry()
        val hook = DualHook("dual-2")
        registry.register(hook)

        registry.unregister(hook)

        assertTrue(registry.getHooks(SystemPromptComposeHook::class).isEmpty())
        assertTrue(registry.getHooks(PromptFinalizeHook::class).isEmpty())
        assertEquals(0, registry.size())
    }

    @Test
    fun `unregister by type and id only touches that bucket`() {
        val registry = HookRegistry()
        registry.register(DualHook("dual-3"))

        registry.unregister(PromptFinalizeHook::class, "dual-3")

        assertTrue(registry.getHooks(PromptFinalizeHook::class).isEmpty())
        assertEquals(1, registry.getHooks(SystemPromptComposeHook::class).size)
    }

    @Test
    fun `disabled hook is filtered out and priority orders descending`() {
        val registry = HookRegistry()
        registry.register(ComposeHook("low", priority = 10))
        registry.register(ComposeHook("high", priority = 90))
        registry.register(DisabledHook("off"))

        val ids = registry.getHooks(SystemPromptComposeHook::class).map { it.id }

        assertEquals(listOf("high", "low"), ids)
        assertTrue(registry.getHooks(PromptFinalizeHook::class).isEmpty())
    }

    @Test
    fun `execute walks the chain in priority order`() = runBlocking {
        val registry = HookRegistry()
        registry.register(ComposeHook("a", priority = 10))
        registry.register(ComposeHook("b", priority = 90))

        val visited = mutableListOf<String>()
        val result = registry.execute(SystemPromptComposeHook::class, initial = "") { hook, acc ->
            visited += hook.id
            acc + hook.id
        }

        assertEquals(listOf("b", "a"), visited)
        assertEquals("ba", result)
    }
}
