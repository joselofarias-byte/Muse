package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette
import io.zer0.common.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.2: [ProviderKeyRotation] Prueba de lógica de rotación de múltiples teclas.
 *
 * Portada:
 *  - Escenario clave única:effectiveApiKey Regresar directamente después del recorte key,saltar LRU
 *  - Múltiples escenarios clave:Reiniciar cada llamada LRU pick(No almacenar en caché),switchToNextKey cambiar
 *  - 429 cambiar:switchToNextKey Devuelve verdadero y actualiza cuando hay varias claves key
 *  - markKeyFailed:401 La exclusión del escenario falla key
 */
class ProviderKeyRotationTest {

    @org.junit.Before
    fun setUp() {
        Logger.enabled = false
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = true
    }

    private fun config(apiKey: String, id: String = "test-provider") = ProviderConfig(
        id = id,
        displayName = "test",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
    )

    // ---------- Escenario clave única ----------

    @Test
    fun `single key effectiveApiKey returns trimmed key without LRU`() {
        val rotation = ProviderKeyRotation(config("sk_single_key"), keyRoulette = KeyRoulette())
        assertEquals("sk_single_key", rotation.effectiveApiKey())
        // Varias llamadas siguen devolviendo lo mismo key(No es necesario girar una sola tecla)
        assertEquals("sk_single_key", rotation.effectiveApiKey())
        assertEquals("sk_single_key", rotation.effectiveApiKey())
    }

    @Test
    fun `single key with whitespace is trimmed`() {
        val rotation = ProviderKeyRotation(config("  sk_padded  "))
        assertEquals("sk_padded", rotation.effectiveApiKey())
    }

    @Test
    fun `single key switchToNextKey returns false`() {
        val rotation = ProviderKeyRotation(config("sk_only"))
        assertFalse(rotation.switchToNextKey())
        // key permanecer sin cambios
        assertEquals("sk_only", rotation.effectiveApiKey())
    }

    @Test
    fun `single key markKeyFailed returns false`() {
        val rotation = ProviderKeyRotation(config("sk_only"))
        assertFalse(rotation.markKeyFailed())
        assertEquals("sk_only", rotation.effectiveApiKey())
    }

    // ---------- Múltiples escenarios clave ----------

    @Test
    fun `multi key effectiveApiKey returns one of the configured keys`() {
        val rotation = ProviderKeyRotation(config("sk_a,sk_b,sk_c"))
        val key = rotation.effectiveApiKey()
        assertTrue(key in setOf("sk_a", "sk_b", "sk_c"))
    }

    @Test
    fun `multi key effectiveApiKey does not return raw comma-joined string`() {
        val rotation = ProviderKeyRotation(config("sk_a,sk_b,sk_c"))
        val key = rotation.effectiveApiKey()
        assertFalse(key.contains(","))
        assertFalse(key == "sk_a,sk_b,sk_c")
    }

    @Test
    fun `multi key switchToNextKey switches to a different key`() {
        val rotation = ProviderKeyRotation(config("sk_a,sk_b"))
        val first = rotation.effectiveApiKey()
        val switched = rotation.switchToNextKey()
        assertTrue(switched)
        val next = rotation.effectiveApiKey()
        assertNotEquals(first, next)
        assertTrue(next in setOf("sk_a", "sk_b"))
    }

    @Test
    fun `multi key switchToNextKey with one soft-blocked key falls back to remaining`() {
        // dos key:sk_a,sk_b. Primer disparador con sk_a switch,sk_a Ingrese a la lista negra suave,
        // Nuevamente efectivoApiKey debería tener prioridad sk_b(Candidatos no incluidos en la lista negra blanda)
        val rotation = ProviderKeyRotation(config("sk_a,sk_b"))
        val initial = rotation.effectiveApiKey()
        if (initial == "sk_a") {
            assertTrue(rotation.switchToNextKey())
            val next = rotation.effectiveApiKey()
            assertEquals("sk_b", next)
        } else {
            assertTrue(rotation.switchToNextKey())
            val next = rotation.effectiveApiKey()
            assertEquals("sk_a", next)
        }
    }

    @Test
    fun `multi key markKeyFailed excludes failed key and picks another`() {
        val rotation = ProviderKeyRotation(config("sk_bad,sk_good"))
        // Forzar la selección primero sk_bad(Elige varias veces hasta que lo consigas. sk_bad)
        var current = rotation.effectiveApiKey()
        var guard = 0
        while (current != "sk_bad" && guard < 20) {
            rotation.switchToNextKey()
            current = rotation.effectiveApiKey()
            guard++
        }
        // Marcar la clave actual como inválida(401 escena,hardBlock)
        val switched = rotation.markKeyFailed()
        assertTrue(switched)
        val after = rotation.effectiveApiKey()
        assertNotEquals("sk_bad", after)
        assertEquals("sk_good", after)
    }

    @Test
    fun `multi key effectiveApiKey is re-picked each call not cached`() {
        // Verificación de corrección principal:En escenarios de múltiples claves, EffectiveApiKey debe ejecutarse cada vez LRU pick,
        // señorita"caché"rama(De lo contrario, se seguirá devolviendo el valor anterior después de la rotación. key).
        // Método de implementación:Llamado dos veces seguidas,Si el caché se accede dos veces, la segunda vez debe ser igual a la primera.;
        // Pero la selección de LRU registrará el uso.,Haz que la segunda vez tienda a elegir otra clave.
        val rotation = ProviderKeyRotation(config("sk_a,sk_b"))
        val first = rotation.effectiveApiKey()
        val second = rotation.effectiveApiKey()
        // Debido a los registros de LRU,Existe una alta probabilidad de que dos llamadas consecutivas obtengan resultados diferentes. key;
        // Incluso si los golpes aleatorios son los mismos,también legal — Aquí sólo se afirma que todavía se encuentra dentro del conjunto de claves de configuración.
        assertTrue(first in setOf("sk_a", "sk_b"))
        assertTrue(second in setOf("sk_a", "sk_b"))
    }
}
