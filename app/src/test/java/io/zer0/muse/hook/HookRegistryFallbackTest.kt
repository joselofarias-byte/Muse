package io.zer0.muse.hook

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas de despacho de interfaces no registradas.
 *
 * [HookRegistry] prensa [HookRegistry.HOOK_TYPES] Al agrupar, faltar una fila en el registro hará que el punto de distribución de una determinada interfaz
 * Las tablas vacías siempre se recuperan y no se informan errores ni se dejan registros. Aquí, se utiliza una interfaz deliberadamente no registrada para verificar la ruta oculta:
 * Siempre que la instancia implemente la interfaz, el despacho debe poder obtenerla.
 */
class HookRegistryFallbackTest {

    /** Interfaz específica de prueba, no en HOOK_TYPES. */
    private interface UnregisteredHook : SkillHook {
        fun tag(): String
    }

    private class SampleHook(override val id: String = "sample") : UnregisteredHook {
        override fun tag(): String = "hit:$id"
    }

    @Test
    fun `Las interfaces no registradas en HOOK_TYPES también se pueden enviar a instancias`() = runTest {
        val registry = HookRegistry()
        registry.register(SampleHook())

        val result = registry.execute(UnregisteredHook::class, initial = "") { hook, acc -> acc + hook.tag() }

        assertEquals("hit:sample", result)
    }

    @Test
    fun `No más distribución después de la cancelación`() = runTest {
        val registry = HookRegistry()
        val hook = SampleHook()
        registry.register(hook)
        registry.unregister(hook)

        val result = registry.execute(UnregisteredHook::class, initial = "") { h, acc -> acc + h.tag() }

        assertEquals("", result)
        assertEquals(0, registry.size())
    }

    @Test
    fun `Las interfaces registradas todavía se ejecutan en orden de prioridad descendente`() = runTest {
        val registry = HookRegistry()
        registry.register(SampleHook("low"))
        registry.register(
            object : UnregisteredHook {
                override val id = "high"
                override val priority = 90
                override fun tag(): String = "hit:$id"
            },
        )

        val result = registry.execute(UnregisteredHook::class, initial = "") { hook, acc -> acc + hook.tag() }

        assertEquals("hit:highhit:low", result)
    }
}
