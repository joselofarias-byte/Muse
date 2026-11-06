package io.zer0.memory.deep

import io.zer0.memory.fact.FactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 Número 2: [FactAtomicityValidator] Pruebas unitarias.
 *
 * Cubriendo tres categorías:
 *  - Ejemplo positivo:Contiene conjunciones/Varias entradas de aserciones deben dividirse con punto y coma
 *  - Contraejemplo:Las afirmaciones individuales, las enumeraciones de sustantivos y las oraciones cortas no deben dividirse
 *  - frontera:Límite superior del número de segmentos, puerta de predicados, deduplicación, transmisión transparente de metadatos, conteo
 *
 * Pruebas JVM puras,no depende de Android/Room(La capa de verificación solo procesa cadenas + clase de datos FactStore.Fact).
 */
class FactAtomicityValidatorTest {

    // ── Ejemplo positivo ───────────────────────────────────────────────────────────────

    @Test
    fun `splits two assertions joined by comma connective`() {
        val parts = FactAtomicityValidator.splitAssertions(
            "El usuario trabaja en Beijing, y además le gusta escalar los fines de semana",
        )
        assertEquals(listOf("El usuario trabaja en Beijing", "le gusta escalar los fines de semana"), parts)
    }

    @Test
    fun `splits two assertions joined by bare connective`() {
        val parts = FactAtomicityValidator.splitAssertions("le gusta leer y además le gusta escribir")
        assertEquals(listOf("le gusta leer", "le gusta escribir"), parts)
    }

    @Test
    fun `splits on semicolon`() {
        val parts = FactAtomicityValidator.splitAssertions(
            "El usuario se está preparando para mudarse; al mismo tiempo busca un nuevo trabajo",
        )
        assertEquals(2, parts.size)
        assertEquals("El usuario se está preparando para mudarse", parts[0])
        assertTrue(parts[1].contains("busca un nuevo trabajo"))
    }

    @Test
    fun `splits medical multi assertion`() {
        val parts = FactAtomicityValidator.splitAssertions("El usuario es vegetariano, y además es alérgico al maní")
        assertEquals(listOf("El usuario es vegetariano", "es alérgico al maní"), parts)
    }

    @Test
    fun `splits english multi assertion on semicolon`() {
        val parts = FactAtomicityValidator.splitAssertions("The user works in Beijing; likes hiking on weekends")
        assertEquals(2, parts.size)
        assertEquals("The user works in Beijing", parts[0])
    }

    // ── Contraejemplo ───────────────────────────────────────────────────────────────

    @Test
    fun `short sentence is untouched`() {
        assertEquals(listOf("en pekín"), FactAtomicityValidator.splitAssertions("en pekín"))
    }

    @Test
    fun `single assertion with and is untouched`() {
        // “y”No dentro del punto de división,afirmación de preferencia única
        assertEquals(listOf("A los usuarios les gusta la fotografía y los viajes."), FactAtomicityValidator.splitAssertions("A los usuarios les gusta la fotografía y los viajes."))
    }

    @Test
    fun `enumeration with no comma connector is untouched`() {
        // “y”Sólo como punto de división cuando está precedido por una coma
        val text = "Los usuarios disfrutan corriendo, nadando y escalando."
        assertEquals(listOf(text), FactAtomicityValidator.splitAssertions(text))
    }

    @Test
    fun `noun phrase split is blocked by predicate gate`() {
        // Segundo párrafo“comida de shanghai”sin predicado → Se determinó que el sintagma nominal fue dividido por error,rendirse
        val text = "A los usuarios les gusta la comida de Beijing, así como Shanghai"
        assertEquals(listOf(text), FactAtomicityValidator.splitAssertions(text))
    }

    @Test
    fun `too many parts is not split`() {
        // 6 segmento > MAX_PARTS(5) → tratado como salida anormal,Déjalo como está
        val text = "Le gusta correr y además le gusta nadar y además le gusta escalar y además le gusta leer y además le gusta viajar y además le gusta entrenar"
        assertEquals(listOf(text), FactAtomicityValidator.splitAssertions(text))
    }

    @Test
    fun `duplicate parts collapse and stay unsplit`() {
        val text = "le gusta el café y además le gusta el café"
        assertEquals(listOf(text), FactAtomicityValidator.splitAssertions(text))
    }

    @Test
    fun `blank text is returned as is`() {
        val parts = FactAtomicityValidator.splitAssertions("    ")
        assertEquals(1, parts.size)
        assertTrue(parts[0].isBlank())
    }

    // ── frontera:enforce entero ─────────────────────────────────────────────────

    @Test
    fun `empty input yields empty result`() {
        val result = FactAtomicityValidator.enforce(emptyList())
        assertTrue(result.facts.isEmpty())
        assertEquals(0, result.splitCount)
        assertEquals(0, result.addedCount)
    }

    @Test
    fun `enforce counts split and added entries and keeps unsplittable`() {
        val input = listOf(
            FactStore.Fact(fact = "El usuario trabaja en Beijing, y además le gusta escalar los fines de semana"),
            FactStore.Fact(fact = "A los usuarios les gusta la fotografía y los viajes."),
        )
        val result = FactAtomicityValidator.enforce(input)

        assertEquals(3, result.facts.size)
        assertEquals(1, result.splitCount)
        assertEquals(1, result.addedCount)
        assertTrue(result.facts.any { it.fact == "A los usuarios les gusta la fotografía y los viajes." })
        assertTrue(result.facts.any { it.fact == "El usuario trabaja en Beijing" })
        assertTrue(result.facts.any { it.fact == "le gusta escalar los fines de semana" })
    }

    @Test
    fun `metadata is preserved across split parts`() {
        val fact = FactStore.Fact(
            fact = "El usuario trabaja en Beijing, y además le gusta escalar los fines de semana",
            tags = listOf("Situación actual", "trabajo"),
            time = "2026-08-12T09:00",
            sessionId = "session-1",
            importance = 2,
            category = "event",
            confidence = 0.5f,
            source = "user_explicit",
            entityKey = "Zhang San",
        )
        val result = FactAtomicityValidator.enforce(listOf(fact))

        assertEquals(2, result.facts.size)
        result.facts.forEach { part ->
            assertEquals(fact.tags, part.tags)
            assertEquals(fact.time, part.time)
            assertEquals(fact.sessionId, part.sessionId)
            assertEquals(fact.importance, part.importance)
            assertEquals(fact.category, part.category)
            assertEquals(fact.confidence, part.confidence, 0.001f)
            assertEquals(fact.source, part.source)
            assertEquals(fact.entityKey, part.entityKey)
        }
    }
}
