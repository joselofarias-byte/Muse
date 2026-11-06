package io.zer0.memory.prompt

import io.zer0.memory.ai.ParsedAnalysis
import io.zer0.memory.ai.ParsedEntity
import io.zer0.memory.ai.ParsedLink
import io.zer0.memory.ai.ParsedMerge
import io.zer0.memory.ai.ParsedUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x: [PromptExampleGuard] Pruebas unitarias.
 *
 * Portada:Los ejemplos se copian y descartan / se conservan los recuerdos reales(Palabras clave mencionadas en la conversación.) / Limpieza de caso/resultado completo.
 */
class PromptExampleGuardTest {

    @Test
    fun `penicillin example is dropped when conversation never mentions it`() {
        assertTrue(
            PromptExampleGuard.isUngroundedExample(
                text = "alérgico a la penicilina",
                conversationText = "Los usuarios charlaron sobre el clima y la cena.,Ninguna mención de medicación.",
            ),
        )
    }

    @Test
    fun `real penicillin fact survives when conversation mentions keyword`() {
        assertFalse(
            PromptExampleGuard.isUngroundedExample(
                text = "alérgico a la penicilina",
                conversationText = "El usuario dijo que era alérgico a la penicilina.,Déjame escribirlo.",
            ),
        )
    }

    @Test
    fun `english examples are case insensitive`() {
        assertTrue(
            PromptExampleGuard.isUngroundedExample(
                text = "Allergic to penicillin",
                conversationText = "user talked about coffee yesterday",
            ),
        )
        assertFalse(
            PromptExampleGuard.isUngroundedExample(
                text = "allergic to penicillin",
                conversationText = "USER IS ALLERGIC TO PENICILLIN",
            ),
        )
    }

    @Test
    fun `unrelated text passes through`() {
        assertFalse(
            PromptExampleGuard.isUngroundedExample(
                text = "A los usuarios les gusta escalar montañas.",
                conversationText = "solo charla",
            ),
        )
        assertFalse(PromptExampleGuard.isUngroundedExample("", "cualquier conversación"))
    }

    @Test
    fun `sanitize removes leaked entities links and merges but keeps grounded ones`() {
        val conversation = "Usuario:Compré un americano hoy,Por cierto, la semana que viene voy a hacer senderismo."
        val analysis = ParsedAnalysis(
            mainProblem = ParsedEntity(title = "alérgico a la penicilina", content = "alérgico a la penicilina"),
            extractedEntities = listOf(
                // Copia de ejemplo(No se menciona la penicilina en el diálogo.)→ debe ser eliminado
                ParsedEntity(title = "alérgico a la penicilina", content = "alérgico a la penicilina", importance = 0.9f),
                // hechos verdaderos(La conversación mencionó el café americano.)→ debe ser retenido
                ParsedEntity(title = "Compré un café americano.", content = "Compré un café americano."),
            ),
            updatedEntities = listOf(
                ParsedUpdate(matchTitle = "Historia de alergia", newContent = "alérgico a la penicilina"),
                ParsedUpdate(matchTitle = "plan de ejercicios", newContent = "Ir de excursión la próxima semana"),
            ),
            mergedEntities = listOf(
                ParsedMerge(
                    sourceTitles = listOf("alérgico a la penicilina"),
                    mergedTitle = "x",
                    mergedContent = "y",
                ),
            ),
            links = listOf(
                ParsedLink(sourceTitle = "alérgico a la penicilina", targetTitle = "Tratar con amoxicilina"),
                ParsedLink(sourceTitle = "Compré un café americano.", targetTitle = "plan de escalada de montaña"),
            ),
            profileMarkdown = "## medico\n- alérgico a la penicilina\n## Interés\n- americano",
        )

        val cleaned = PromptExampleGuard.sanitize(analysis, conversation)

        assertNull(cleaned.mainProblem)
        assertEquals(1, cleaned.extractedEntities.size)
        assertEquals("Compré un café americano.", cleaned.extractedEntities[0].title)
        assertEquals(1, cleaned.updatedEntities.size)
        assertEquals("plan de ejercicios", cleaned.updatedEntities[0].matchTitle)
        assertTrue(cleaned.mergedEntities.isEmpty())
        assertEquals(1, cleaned.links.size)
        assertEquals("Compré un café americano.", cleaned.links[0].sourceTitle)
        // retrato:Se elimina la fila de penicilina,cafeteria americana(mencionado en la conversación)Reservar
        assertEquals("## medico\n## Interés\n- americano", cleaned.profileMarkdown)
    }

    @Test
    fun `grounded examples are not removed when conversation truly mentions them`() {
        val conversation = "Usuario:recuérdalo por mí,Soy alérgico a la penicilina."
        val analysis = ParsedAnalysis(
            extractedEntities = listOf(
                ParsedEntity(title = "alérgico a la penicilina", content = "alérgico a la penicilina", importance = 0.9f),
            ),
        )
        val cleaned = PromptExampleGuard.sanitize(analysis, conversation)
        assertEquals(1, cleaned.extractedEntities.size)
    }
}
