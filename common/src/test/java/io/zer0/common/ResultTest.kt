package io.zer0.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Result] Resultados genéricos de pruebas unitarias empaquetadas.
 *
 * Cobertura:
 *  - [resultOf] Camino al éxito y camino al fracaso.(Contiene semántica de reinicio de CancellationException)
 *  - [Result.Success] / [Result.Error] Determinación del estado([isSuccess] / [isError])
 *  - [getOrNull] éxito/Valor de falla
 *  - [getOrThrow] Adquisición de valor exitosa y fracaso al lanzar una excepción
 *  - [onSuccess] / [onError] Activación de devolución de llamada y devoluciones encadenadas
 *  - [map] Convertir Success,Error paso a través
 *  - [flatMap] Entrega encadenada de éxito/error
 */
class ResultTest {

    // ── resultOf{} camino al éxito ──────────────────────────────────────────────

    @Test
    fun `resultOf Devuelve el éxito con éxito y lleva datos`() {
        val result = resultOf { 42 }
        assertTrue(result is Result.Success)
        assertEquals(42, (result as Result.Success).data)
    }

    @Test
    fun `resultOf la ruta del éxito es el éxito es verdadero es el error es false`() {
        val result = resultOf { "ok" }
        assertTrue(result.isSuccess)
        assertFalse(result.isError)
    }

    @Test
    fun `resultOf el bloque devuelve nulo mientras todavía Success`() {
        val result = resultOf<String?> { null }
        assertTrue(result is Result.Success)
        assertNull(result.getOrNull())
    }

    // ── resultOf{} camino de falla ──────────────────────────────────────────────

    @Test
    fun `resultOf Cuando se lanza una excepción, se devuelve Error y lleva el mensaje y la excepción original`() {
        val ex = IllegalStateException("boom")
        val result = resultOf<Int> { throw ex }
        assertTrue(result is Result.Error)
        val err = result as Result.Error
        assertEquals("boom", err.message)
        assertSame(ex, err.throwable)
    }

    @Test
    fun `resultOf Cuando se produce una excepción, si el mensaje está vacío, utilice Error desconocido para averiguarlo`() {
        val ex = RuntimeException()
        val result = resultOf<Int> { throw ex }
        assertTrue(result is Result.Error)
        assertEquals("Unknown error", (result as Result.Error).message)
        assertSame(ex, err(result).throwable)
    }

    @Test
    fun `resultOf Cuando se lanza una excepción, isSuccess es falso y isError es true`() {
        val result = resultOf<Int> { throw IllegalArgumentException("bad") }
        assertFalse(result.isSuccess)
        assertTrue(result.isError)
    }

    @Test
    fun `resultOf No trague CancellationException pero vuelva a lanzarlo`() {
        val cex = kotlin.coroutines.cancellation.CancellationException("cancelled")
        assertThrows(kotlin.coroutines.cancellation.CancellationException::class.java) {
            resultOf<Int> { throw cex }
        }
    }

    // ── getOrNull() éxito/falló ────────────────────────────────────────────

    @Test
    fun `getOrNull Devolver datos sobre el éxito`() {
        val result: Result<String> = resultOf { "hello" }
        assertEquals("hello", result.getOrNull())
    }

    @Test
    fun `getOrNull Retorno del fracaso null`() {
        val result: Result<String> = resultOf { throw RuntimeException("err") }
        assertNull(result.getOrNull())
    }

    // ── getOrThrow() éxito/falló ───────────────────────────────────────────

    @Test
    fun `getOrThrow Devolver datos sobre el éxito`() {
        val result: Result<Int> = resultOf { 7 }
        assertEquals(7, result.getOrThrow())
    }

    @Test
    fun `getOrThrow Lanza la excepción original en caso de falla y lo arrojable no está vacío`() {
        val ex = IllegalStateException("original")
        val result: Result<Int> = resultOf { throw ex }
        val thrown = assertThrows(IllegalStateException::class.java) {
            result.getOrThrow()
        }
        assertSame(ex, thrown)
    }

    @Test
    fun `getOrThrow Lanza IllegalStateException en caso de falla y lo arrojable está vacío y lleva message`() {
        // Construir arrojable a nulo Error(Creación de instancias directas,falló resultOf)
        val result: Result<Int> = Result.Error(message = "no throwable", throwable = null)
        val thrown = assertThrows(IllegalStateException::class.java) {
            result.getOrThrow()
        }
        assertEquals("no throwable", thrown.message)
    }

    // ── onSuccess{} devolución de llamada ────────────────────────────────────────────────

    @Test
    fun `onSuccess Activar una devolución de llamada en caso de éxito y pasar datos`() {
        var captured: String? = null
        var callCount = 0
        val result: Result<String> = resultOf { "data" }
        result.onSuccess { value ->
            captured = value
            callCount++
        }
        assertEquals("data", captured)
        assertEquals(1, callCount)
    }

    @Test
    fun `onSuccess No activar la devolución de llamada en caso de error`() {
        var callCount = 0
        val result: Result<String> = resultOf { throw RuntimeException("err") }
        result.onSuccess { callCount++ }
        assertEquals(0, callCount)
    }

    @Test
    fun `onSuccess Devuelve la instancia de resultado original para facilitar las llamadas en cadena`() {
        val result: Result<Int> = resultOf { 1 }
        val returned = result.onSuccess { /* no-op */ }
        assertSame(result, returned)
    }

    // ── onError{} devolución de llamada ──────────────────────────────────────────────────

    @Test
    fun `onError Activar devolución de llamada en caso de falla y pasar mensaje y throwable`() {
        val ex = IllegalStateException("boom")
        var capturedMsg: String? = null
        var capturedT: Throwable? = null
        var callCount = 0
        val result: Result<Int> = resultOf { throw ex }
        result.onError { msg, t ->
            capturedMsg = msg
            capturedT = t
            callCount++
        }
        assertEquals("boom", capturedMsg)
        assertSame(ex, capturedT)
        assertEquals(1, callCount)
    }

    @Test
    fun `onError No activar la devolución de llamada en caso de éxito`() {
        var callCount = 0
        val result: Result<Int> = resultOf { 1 }
        result.onError { _, _ -> callCount++ }
        assertEquals(0, callCount)
    }

    @Test
    fun `onError La devolución de llamada recibe un arrojable que es nulo`() {
        val result: Result<Int> = Result.Error(message = "msg", throwable = null)
        var receivedT: Throwable? = "sentinel".let { null }
        result.onError { _, t -> receivedT = t }
        assertNull(receivedT)
    }

    @Test
    fun `onError Devuelve la instancia de resultado original para facilitar las llamadas en cadena`() {
        val result: Result<Int> = resultOf { throw RuntimeException("err") }
        val returned = result.onError { _, _ -> /* no-op */ }
        assertSame(result, returned)
    }

    // ── map{} Convertir ──────────────────────────────────────────────────────

    @Test
    fun `map Aplicar la transformación a los datos sobre el éxito`() {
        val result: Result<Int> = resultOf { 5 }
        val mapped = result.map { it * 2 }
        assertTrue(mapped is Result.Success)
        assertEquals(10, mapped.getOrNull())
    }

    @Test
    fun `map Convertir tipo en caso de éxito`() {
        val result: Result<Int> = resultOf { 42 }
        val mapped: Result<String> = result.map { "value=$it" }
        assertEquals("value=42", mapped.getOrNull())
    }

    @Test
    fun `map Transmitir de forma transparente la instancia de error en caso de falla`() {
        val ex = IllegalStateException("boom")
        val result: Result<Int> = resultOf { throw ex }
        val mapped = result.map { it * 2 }
        assertTrue(mapped is Result.Error)
        val err = mapped as Result.Error
        assertEquals("boom", err.message)
        assertSame(ex, err.throwable)
    }

    @Test
    fun `map No llamado ante el fracaso transform`() {
        var transformCalled = false
        val result: Result<Int> = resultOf { throw RuntimeException("err") }
        result.map {
            transformCalled = true
            it
        }
        assertFalse(transformCalled)
    }

    // ── flatMap{} cadena ──────────────────────────────────────────────────

    @Test
    fun `flatMap Cuando tenga éxito, pase los datos para transformar y generar nuevos Result`() {
        val result: Result<Int> = resultOf { 3 }
        val flat = result.flatMap<Int> { resultOf { it + 10 } }
        assertTrue(flat is Result.Success)
        assertEquals(13, flat.getOrNull())
    }

    @Test
    fun `flatMap Si tiene éxito, la transformación puede devolver un error para formar una cadena de fallas`() {
        val result: Result<Int> = resultOf { 3 }
        val flat: Result<String> = result.flatMap {
            Result.Error(message = "downstream failure", throwable = null)
        }
        assertTrue(flat is Result.Error)
        assertEquals("downstream failure", (flat as Result.Error).message)
    }

    @Test
    fun `flatMap Cuando ocurre una falla, el error original se transmite de forma transparente y no se llama transform`() {
        val ex = IllegalStateException("upstream")
        val result: Result<Int> = resultOf { throw ex }
        var transformCalled = false
        val flat: Result<String> = result.flatMap<String> {
            transformCalled = true
            resultOf { "ok" }
        }
        assertTrue(flat is Result.Error)
        assertFalse(transformCalled)
        val err = flat as Result.Error
        assertEquals("upstream", err.message)
        assertSame(ex, err.throwable)
    }

    @Test
    fun `flatMap Las combinaciones encadenadas de varios pasos siguen siendo exitosas`() {
        val final: Result<Int> = resultOf { 1 }
            .flatMap { resultOf { it + 2 } }
            .flatMap { resultOf { it * 3 } }
        assertEquals(9, final.getOrNull())
    }

    @Test
    fun `flatMap Si falla algún paso de la cadena, falla toda la cadena`() {
        val final: Result<Int> = resultOf { 1 }
            .flatMap<Int> { resultOf<Int> { throw IllegalStateException("step2") } }
            .flatMap { resultOf { it * 3 } }
        assertTrue(final is Result.Error)
        assertEquals("step2", (final as Result.Error).message)
    }

    // ── Cadena integral:onSuccess / onError / map / flatMap combinación ───────────────

    @Test
    fun `Después de integrar el mapa de la cadena, onSuccess recibe el valor convertido`() {
        var captured: String? = null
        resultOf { 10 }
            .map { it.toString() }
            .onSuccess { captured = it }
        assertEquals("10", captured)
    }

    @Test
    fun `Cuando falla el flatMap encadenado integral, onError recibe información de falla`() {
        var capturedMsg: String? = null
        var capturedT: Throwable? = null
        resultOf { 10 }
            .flatMap<Int> { resultOf<Int> { throw IllegalArgumentException("down") } }
            .onError { msg, t ->
                capturedMsg = msg
                capturedT = t
            }
        assertEquals("down", capturedMsg)
        assertNotNull(capturedT)
        assertTrue(capturedT is IllegalArgumentException)
    }

    // ── Función auxiliar ────────────────────────────────────────────────────────

    /** de [Result] Obtener la instancia de error de(Ayuda de prueba,Se supone que se sabe que la persona que llama es Error). */
    private fun <T> err(result: Result<T>): Result.Error = (result as? Result.Error) ?: error("esperado Error,real $result")
}
