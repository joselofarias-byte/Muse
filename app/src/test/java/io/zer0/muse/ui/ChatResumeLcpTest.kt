package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-11 / C-12: Pruebas unitarias de funciones puras de deduplicación de reanudación de puntos de interrupción y enrutamiento de modelos de herramientas.
 *
 * función pura anulada:
 *  - [longestCommonPrefix]: Transferencia reanudada para eliminar duplicados(Omita el prefijo común más largo que se superponga al contenido mostrado).
 *  - [canUseToolModelForRound]: ¿Está disponible la rueda de herramientas? toolModel(Cuando el historial contiene imágenes pero el modelo de herramienta no tiene visión, recurra al modelo principal.).
 *
 * Todos son pura lógica de Kotlin sin dependencias del tiempo de ejecución de Android.(JUnit4).
 */
class ChatResumeLcpTest {

    // ── longestCommonPrefix ────────────────────────────────────────────────

    @Test
    fun `identical strings share full length`() {
        assertEquals(11, longestCommonPrefix("hello world", "hello world"))
    }

    @Test
    fun `prefix differs returns common prefix length`() {
        // "hello " 6 personajes en total(h/e/l/l/o/espacio)superponerse,Después de que w vs t se bifurque
        assertEquals(6, longestCommonPrefix("hello world", "hello there"))
    }

    @Test
    fun `no overlap returns zero`() {
        assertEquals(0, longestCommonPrefix("abc", "xyz"))
    }

    @Test
    fun `empty strings return zero`() {
        assertEquals(0, longestCommonPrefix("", "hello"))
        assertEquals(0, longestCommonPrefix("hello", ""))
        assertEquals(0, longestCommonPrefix("", ""))
    }

    @Test
    fun `one is prefix of other returns shorter length`() {
        assertEquals(5, longestCommonPrefix("hello", "hello world"))
        assertEquals(5, longestCommonPrefix("hello world", "hello"))
    }

    @Test
    fun `chinese text overlapped prefix measured in code units`() {
        // "hoy dia" = 3 personajes,Seguimiento "enojado" con "lluvia" diferente → Prefijo común para obtener índice 3
        assertEquals(3, longestCommonPrefix("El clima esta muy bueno hoy", "esta lloviendo hoy"))
    }

    @Test
    fun `chinese identical shares full length`() {
        // "Hola mundo Hola mundo" = 9 caracteres chinos,El prefijo común para la misma cadena es la longitud completa
        assertEquals(9, longestCommonPrefix("Hola mundo Hola mundo", "Hola mundo Hola mundo"))
    }

    @Test
    fun `rewritten prefix after overlap returns divergence point`() {
        // "abcdefgh" fue reescrito como "abcdXefgh":Los primeros 4 caracteres todavía se superponen.,5to (e vs X) empezar a bifurcar
        assertEquals(4, longestCommonPrefix("abcdefgh", "abcdXefgh"))
    }

    @Test
    fun `mixed replay and new text never drops the new prefix`() {
        // Resta contenido antiguo "abcdef", provider Regresar "abcXYZ".
        // "abc" Es sólo una coincidencia,XYZ Podría ser la verdadera novedad en esta ronda, no puedo tragarme el abc de LCP.
        assertEquals(0, resumeOverlapToDrop("abcdef", "abcXYZ"))
    }

    @Test
    fun `partial matching chunk is retained because it may be a new continuation`() {
        assertEquals(0, resumeOverlapToDrop("abcdef", "abc"))
    }

    @Test
    fun `complete replayed partial answer can be dropped`() {
        assertEquals(6, resumeOverlapToDrop("abcdef", "abcdef"))
    }

    @Test
    fun `replayed old prefix followed by new suffix drops only old prefix`() {
        assertEquals(6, resumeOverlapToDrop("abcdef", "abcdefXYZ"))
    }

    @Test
    fun `short ambiguous replay prefix is kept to avoid swallowing a new first character`() {
        assertEquals(0, resumeOverlapToDrop("hello", "h"))
        assertEquals(0, resumeOverlapToDrop("hello", "he"))
    }

    // ── Reanudar el juicio de reescritura(v2.0 Corrección de respuestas duplicadas)──────────────────────────────────

    @Test
    fun `large overlap consumed marks a rewrite`() {
        // Texto original 33 palabras,Bifurcado después de consumir 18 palabras → Juzgado como reescritura,El contenido antiguo debe ser reemplazado.
        assertTrue(shouldReplaceOnResumeRewrite(duplicateTotal = 33, consumedChars = 18))
        assertTrue(shouldReplaceOnResumeRewrite(duplicateTotal = 20, consumedChars = 10))
        assertTrue(shouldReplaceOnResumeRewrite(duplicateTotal = 8, consumedChars = 6))
    }

    @Test
    fun `small or zero overlap keeps append semantics`() {
        assertFalse(shouldReplaceOnResumeRewrite(duplicateTotal = 0, consumedChars = 0))
        assertFalse(shouldReplaceOnResumeRewrite(duplicateTotal = 40, consumedChars = 4))
        assertFalse(shouldReplaceOnResumeRewrite(duplicateTotal = 10, consumedChars = 3))
    }

    @Test
    fun `attempt containing original head supersedes partial content`() {
        val original = "Puedo charlar contigo. ¿Cómo puedo ayudarte hoy??"
        val attempt = "¡Hola! Soy Muse y es genial charlar contigo. ¿Hay algo que pueda hacer para ayudarte??"
        val current = original + attempt
        assertTrue(shouldReplaceOnResumeSupersede(original, attempt, current))
    }

    @Test
    fun `genuine continuation does not supersede`() {
        val original = "El clima de hoy"
        val continuation = "No está mal, apto para pasear."
        val current = original + continuation
        assertFalse(shouldReplaceOnResumeSupersede(original, continuation, current))
    }

    @Test
    fun `identical attempt is already current content`() {
        val original = "¡Hola! Soy Muse y es genial charlar contigo."
        val attempt = "¡Hola! Soy Muse y es genial charlar contigo. ¿Hay algo que pueda hacer para ayudarte??"
        assertFalse(shouldReplaceOnResumeSupersede(original, attempt, attempt))
        assertFalse(shouldReplaceOnResumeSupersede(null, attempt, attempt))
        assertFalse(shouldReplaceOnResumeSupersede(original, null, original))
    }

    // ── canUseToolModelForRound ────────────────────────────────────────────

    private fun model(supportsVision: Boolean): Model =
        Model(id = "m-" + (if (supportsVision) "vision" else "text"), providerId = "p1", supportsVision = supportsVision)

    private fun userMsgWithImage(content: String = "mira la foto"): UIMessage =
        UIMessage(role = MessageRole.USER, content = content, imageBase64List = listOf("aGVsbG8="))

    @Test
    fun `vision-capable tool model is usable regardless of history images`() {
        val history = listOf(userMsgWithImage())
        assertTrue(canUseToolModelForRound(history, model(supportsVision = true)))
    }

    @Test
    fun `text-only tool model is usable when history has no images`() {
        val history = listOf(
            UIMessage(role = MessageRole.USER, content = "Ayúdame a calcularlo"),
        )
        assertTrue(canUseToolModelForRound(history, model(supportsVision = false)))
    }

    @Test
    fun `text-only tool model must fall back when history carries an image`() {
        val history = listOf(userMsgWithImage())
        assertFalse(canUseToolModelForRound(history, model(supportsVision = false)))
    }

    @Test
    fun `empty history is always usable`() {
        assertTrue(canUseToolModelForRound(emptyList(), model(supportsVision = false)))
        assertTrue(canUseToolModelForRound(emptyList(), model(supportsVision = true)))
    }
}
