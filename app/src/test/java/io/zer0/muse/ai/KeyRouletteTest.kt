package io.zer0.muse.ai

import io.zer0.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KeyRoulette API key Rotar pruebas unitarias.
 *
 * Pruebe la estrategia de selección de claves LRU + lista negra(suave/duro)lógica:
 *  - [KeyRoulette.pick]:LRU Selección de estrategia key(Priorizar los no utilizados)
 *  - [KeyRoulette.pickNext]:429 Cambie al siguiente cuando la corriente sea limitada key(La clave actual se agrega a la lista negra suave)
 *  - [KeyRoulette.markFailed]:Error al marcar la clave(hardBlock completamente excluido)
 *  - [KeyRoulette.clearBlacklist]:Borrar la lista negra del proveedor especificado
 *  - [KeyRoulette.clear]:Borrar todo el caché y la lista negra
 *
 * Utilice una instancia de KeyRoulette separada para cada método de prueba,Evite la contaminación estatal.
 */
class KeyRouletteTest {

    private lateinit var roulette: KeyRoulette

    private val providerId = "test-provider"
    private val key1 = "sk-key1-aaaaaaaa"
    private val key2 = "sk-key2-bbbbbbbb"
    private val key3 = "sk-key3-cccccccc"
    private val multiKeys = "$key1,$key2,$key3"

    @Before
    fun setUp() {
        roulette = KeyRoulette()
    }

    // ── pick:LRU elegir key ────────────────────────────────────────────────

    @Test
    fun `Cuando hay una sola clave, pick devuelve la original key`() {
        val result = roulette.pick(providerId, key1)
        assertEquals(key1, result)
    }

    @Test
    fun `Cuando hay varias claves, pick devuelve una de las candidatas. key`() {
        val result = roulette.pick(providerId, multiKeys)
        assertTrue("pick El resultado debe ser una de las claves candidatas.: $result", result in listOf(key1, key2, key3))
    }

    @Test
    fun `pick Recoger nuevamente después de eso no devolverá lo recién usado. key(LRU)`() {
        // primera vez pick
        val first = roulette.pick(providerId, multiKeys)
        // La segunda elección: la primera se acaba de usar.,Se debe seleccionar otro key
        val second = roulette.pick(providerId, multiKeys)
        assertFalse("LRU La segunda elección según la estrategia no debería devolver lo recién usado. key: first=$first, second=$second", first == second)
    }

    @Test
    fun `La triple selección cubre los tres key`() {
        val picked = mutableSetOf<String>()
        repeat(3) {
            picked.add(roulette.pick(providerId, multiKeys))
        }
        // Tres selecciones deberían cubrir los tres key(LRU Sin duplicación)
        assertEquals("Tres selecciones deberían cubrir todo key: $picked", 3, picked.size)
        assertTrue(key1 in picked)
        assertTrue(key2 in picked)
        assertTrue(key3 in picked)
    }

    @Test
    fun `Las claves separadas por nueva línea también se pueden analizar correctamente`() {
        val newlineKeys = "$key1\n$key2\n$key3"
        val result = roulette.pick(providerId, newlineKeys)
        assertTrue("Las claves separadas por nueva línea deben analizarse correctamente: $result", result in listOf(key1, key2, key3))
    }

    @Test
    fun `Las listas de claves que contienen valores nulos se filtran automáticamente`() {
        // "key1,,key2" Hay un valor nulo en el medio.,debe ser filtrado
        val keysWithEmpty = "$key1,,$key2"
        val result = roulette.pick(providerId, keysWithEmpty)
        assertTrue("Los valores clave vacíos deben filtrarse: $result", result in listOf(key1, key2))
    }

    @Test
    fun `Aislamiento de estado LRU para diferentes ID de proveedor`() {
        // provider-A pick key1
        val a1 = roulette.pick("provider-A", multiKeys)
        // provider-B pick No afectado por la LRU del proveedor A
        val b1 = roulette.pick("provider-B", multiKeys)
        // provider-A Se debe evitar la segunda elección. a1
        val a2 = roulette.pick("provider-A", multiKeys)
        assertFalse("provider-A La LRU no debería afectar provider-B", a1 == a2)
    }

    // ── pickNext:429 Conmutación de límite de corriente ──────────────────────────────────────────

    @Test
    fun `pickNext cambiar a diferente key`() {
        val first = roulette.pick(providerId, multiKeys)
        val next = roulette.pickNext(providerId, multiKeys, first)
        assertFalse("pickNext debería cambiar a otro key: first=$first, next=$next", first == next)
        assertTrue("La clave cambiada debe estar en la lista de candidatos.: $next", next in listOf(key1, key2, key3))
    }

    @Test
    fun `Cuando hay una sola clave, pickNext devuelve la original key`() {
        val result = roulette.pickNext(providerId, key1, key1)
        assertEquals("Cuando se utiliza una sola clave, pickNext debería devolver el original key", key1, result)
    }

    @Test
    fun `pickNext Después de agregar la clave actual a la lista negra suave, ya no tendrá prioridad.`() {
        val first = roulette.pick(providerId, multiKeys)
        roulette.pickNext(providerId, multiKeys, first)
        // otra vez pick,first en lista negra suave,A otros se les debe dar prioridad key
        val third = roulette.pick(providerId, multiKeys)
        // Disminuir la prioridad de las claves en la lista negra suave,No lo selecciones cuando haya otros candidatos.
        val remaining = listOf(key1, key2, key3) - first
        assertTrue("Se debe quitar prioridad a las claves de la lista negra suave: $third", third in remaining)
    }

    @Test
    fun `PickNext continuo eventualmente rota todo key`() {
        val first = roulette.pick(providerId, multiKeys)
        val second = roulette.pickNext(providerId, multiKeys, first)
        val third = roulette.pickNext(providerId, multiKeys, second)
        // Se deben seleccionar las tres teclas.
        val all = setOf(first, second, third)
        assertEquals("PickNext consecutivo debería rotar todo key: $all", 3, all.size)
    }

    // ── markFailed:lista negra dura ────────────────────────────────────────────

    @Test
    fun `markFailed La clave queda completamente excluida después`() {
        roulette.markFailed(providerId, key1, hardBlock = true)
        // Varias selecciones no deberían regresar key1
        repeat(5) {
            val picked = roulette.pick(providerId, multiKeys)
            assertFalse("markFailed Después de la clave 1 se debe excluir: No. ${it + 1} veces pick=$picked", picked == key1)
        }
    }

    @Test
    fun `markFailed Después de que todas las claves degeneren para devolver la primera. key`() {
        // Marcar las tres claves como fallidas
        roulette.markFailed(providerId, key1, hardBlock = true)
        roulette.markFailed(providerId, key2, hardBlock = true)
        roulette.markFailed(providerId, key3, hardBlock = true)
        // Todos hard-blocked → volver a la primera key(Deje que el proveedor informe los errores por sí solo)
        val result = roulette.pick(providerId, multiKeys)
        assertEquals("Si todas las claves están bloqueadas, se debe devolver la primera. key", key1, result)
    }

    @Test
    fun `markFailed softBlock Cuando la clave no tiene prioridad pero no se excluye`() {
        // hardBlock=false:lista negra suave,No seleccionar si hay otros candidatos,Aún disponible cuando no hay otros candidatos
        roulette.markFailed(providerId, key1, hardBlock = false)
        // Hay key2/key3 disponibles,No debe ser seleccionado key1
        repeat(3) {
            val picked = roulette.pick(providerId, multiKeys)
            assertFalse("No seleccionar si hay otros candidatos en la lista negra suave key1: $picked", picked == key1)
        }
    }

    @Test
    fun `markFailed Sólo afecta a lo especificado provider`() {
        roulette.markFailed("provider-A", key1, hardBlock = true)
        // provider-B no afectado
        val result = roulette.pick("provider-B", multiKeys)
        assertTrue("markFailed no debe afectar a otros provider: $result", result in listOf(key1, key2, key3))
    }

    // ── clearBlacklist / clear ─────────────────────────────────────────

    @Test
    fun `clearBlacklist Recuperar bloqueado key`() {
        roulette.markFailed(providerId, key1, hardBlock = true)
        // Borrar lista negra
        roulette.clearBlacklist(providerId)
        // key1 candidato de recuperación
        val picked = mutableSetOf<String>()
        repeat(5) { picked.add(roulette.pick(providerId, multiKeys)) }
        assertTrue("clearBlacklist Después de la clave 1, el candidato debe ser restaurado.: $picked", key1 in picked)
    }

    @Test
    fun `clear Borrar todo el estado`() {
        roulette.pick(providerId, multiKeys)
        roulette.markFailed(providerId, key1, hardBlock = true)
        roulette.clear()
        // clear Después de la clave 1, el candidato debe ser restaurado.
        val picked = mutableSetOf<String>()
        repeat(5) { picked.add(roulette.pick(providerId, multiKeys)) }
        assertTrue("clear Después de la clave 1, el candidato debe ser restaurado.: $picked", key1 in picked)
    }

    @Test
    fun `clearBlacklist Sólo afecta a lo especificado provider`() {
        roulette.markFailed("provider-A", key1, hardBlock = true)
        roulette.markFailed("provider-B", key1, hardBlock = true)
        // Borre solo la lista negra del proveedor A
        roulette.clearBlacklist("provider-A")
        // provider-B La clave 1 sigue bloqueada
        repeat(3) {
            val picked = roulette.pick("provider-B", multiKeys)
            assertFalse("clearBlacklist no debería afectar provider-B: $picked", picked == key1)
        }
    }
}
