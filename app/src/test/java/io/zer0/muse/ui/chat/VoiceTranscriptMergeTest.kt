package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [mergeTranscript] contrato de función pura:Cómo el texto acumulado de esta ronda absorbe una devolución de llamada de ASR.
 *
 * ASR La devolución de llamada puede ser "el texto completo de esta sesión hasta el momento".(será retransmitido en su totalidad),O tal vez sea solo un párrafo nuevo.;
 * Las conversaciones de voz también permiten finalizar la conversación y reiniciarla a mitad de camino.,Por tanto, la combinación debe ser idempotente y no perder ninguna palabra.
 */
class VoiceTranscriptMergeTest {

    @Test
    fun `empty incoming keeps accumulated`() {
        assertEquals("El clima de hoy", mergeTranscript("El clima de hoy", ""))
        assertEquals("El clima de hoy", mergeTranscript("El clima de hoy", "   "))
        assertEquals("", mergeTranscript("", ""))
    }

    @Test
    fun `empty accumulated takes incoming`() {
        assertEquals("El clima de hoy", mergeTranscript("", "El clima de hoy"))
        assertEquals("El clima de hoy", mergeTranscript("  ", " El clima de hoy "))
    }

    @Test
    fun `incoming starting with accumulated replaces it`() {
        // El servidor dio el texto completo con el contenido antiguo.:Reemplazar el párrafo completo,Sin empalmes repetidos
        assertEquals("El clima de hoy El clima esta agradable hoy", mergeTranscript("El clima de hoy", "El clima esta agradable hoy"))
        assertEquals("El clima de hoy", mergeTranscript("El clima de hoy", "El clima de hoy"))
    }

    @Test
    fun `accumulated starting with incoming keeps accumulated`() {
        // El servidor resiente el texto antiguo.(más corto):No acortes el contenido que ya es más largo
        assertEquals("El clima esta agradable hoy El clima de hoy", mergeTranscript("El clima esta agradable hoy", "El clima de hoy"))
    }

    @Test
    fun `unrelated texts are appended without separator`() {
        // El chino no añade separadores.:Cuando ambos párrafos sean reemplazados por contenido nuevo, conéctese directamente.
        assertEquals("El clima esta agradable hoy¿Qué pasa mañana?", mergeTranscript("El clima esta agradable hoy", "¿Qué pasa mañana?"))
        assertEquals("Compruébalo por míEl tiempo en Pekín mañana", mergeTranscript("Compruébalo por mí", "El tiempo en Pekín mañana"))
    }

    @Test
    fun `trailing overlap is collapsed once`() {
        // Una vez reabierta la sesión, ASR comienza desde el principio.:Cuando el primer párrafo de una nueva conversación se superponga con el contenido acumulado, sólo se conservará una copia.
        assertEquals("El clima de hoy buen clima", mergeTranscript("El clima de hoy", "buen clima"))
        // Los resultados intermedios crecen en la misma sesión.
        assertEquals("El clima esta agradable hoy No está mal", mergeTranscript("El clima esta agradable hoy", "No está mal"))
        // El inglés se compone de fragmentos de palabras superpuestas.
        assertEquals("hello world peace", mergeTranscript("hello world", "world peace"))
    }

    @Test
    fun `ascii word boundary gets a single space`() {
        assertEquals("hello world", mergeTranscript("hello", "world"))
        // No agregue espacios cuando el chino y el inglés sean adyacentes
        assertEquals("OK ok", mergeTranscript("OK", "ok"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("El clima de hoy No está mal", mergeTranscript("  El clima de hoy ", " No está mal  "))
        assertEquals("El clima de hoy", mergeTranscript(" El clima de hoy ", "   "))
    }

    @Test
    fun `merging the same session text twice is idempotent`() {
        // El estado de devolución de llamada y cierre de ASR son dos canales reconocidos al mismo tiempo.,No se permiten llegadas sucesivas que se repitan.
        val once = mergeTranscript("El clima de hoy", "Nada mal")
        assertEquals("El clima de hoy Nada mal", once)
        assertEquals(once, mergeTranscript(once, "Nada mal"))
        assertEquals("El clima de hoy Nada mal El clima esta agradable hoy", mergeTranscript(once, "El clima esta agradable hoy"))
    }
}
