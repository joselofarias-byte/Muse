package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryItemActionRoutingTest {

    @Test
    fun sessionSummaryIdRoutesToSummaryStorageInsteadOfFactIdParsing() {
        var factPathCalled = false
        val item = MemoryItem(
            id = "session-550e8400-e29b-41d4-a716-446655440000",
            title = "Chat title",
            content = "Conversation summary",
            source = "Summary",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ ->
                factPathCalled = true
                "fact"
            },
            onSummary = { sessionId -> sessionId },
            onExperience = { "unused" },
            onCompile = { "unused" },
            onUnsupported = { "unsupported" },
        )

        assertFalse(factPathCalled)
        assertEquals("session-550e8400-e29b-41d4-a716-446655440000", result)
    }

    @Test
    fun numericFactIdKeepsItsFactScope() {
        val item = MemoryItem(
            id = "42",
            title = "A fact",
            content = "A fact",
            source = "Fact",
            scope = "assistant-2",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { id, scope -> "$id:$scope" },
            onSummary = { "summary:$it" },
            onExperience = { "unused" },
            onCompile = { "unused" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("42:assistant-2", result)
    }

    /**
     * v2.6.7: Compilar producto (source="Compile") anteriormente recayó en onUnsupported,
     * da como resultado cero llamadas editCompile/deleteCompile —— Fallo silencioso después de la confirmación de edición del usuario,
     * No pasará nada si hago clic en el botón Eliminar. ahora identificación (section key) debe enrutarse a onCompile.
     */
    @Test
    fun compileSectionRoutesByIdToCompilePath() {
        val item = MemoryItem(
            id = "longterm",
            title = "memoria a largo plazo",
            content = "compiled text",
            source = "Compile",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ -> "fact" },
            onSummary = { "summary" },
            onExperience = { "experience:$it" },
            onCompile = { sectionKey -> "compile:$sectionKey" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("compile:longterm", result)
    }

    /**
     * v2.6.7: Las entradas de la base de experiencia anteriormente recaían en onUnsupported.
     * addExperience/editExperience/deleteExperience Con ExperienceLibraryCard,
     * ExperienceEditDialog Está todo escrito, pero nunca se ha hecho referencia a la interfaz y los usuarios no pueden verla ni cambiarla.
     */
    @Test
    fun experienceItemRoutesToExperiencePath() {
        val item = MemoryItem(
            id = "exp_1700000000_42",
            title = "El embalaje debe enviarse primero.",
            content = "Envíelo primero y luego empaquete; de lo contrario, el contenido del paquete no es consistente con HEAD",
            source = "Experience",
        )

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ -> "fact" },
            onSummary = { "summary" },
            onExperience = { id -> "experience:$id" },
            onCompile = { "compile" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("experience:exp_1700000000_42", result)
    }

    @Test
    fun unknownSourceStillFallsToUnsupported() {
        val item = MemoryItem(id = "x", title = "t", content = "c", source = "Whatever")

        val result = routeMemoryItemAction(
            item = item,
            onFact = { _, _ -> "fact" },
            onSummary = { "summary" },
            onExperience = { "experience" },
            onCompile = { "compile" },
            onUnsupported = { "unsupported" },
        )

        assertEquals("unsupported", result)
    }

    @Test
    fun allScopeMemoryMaintenanceIncludesMainAndChildAssistants() {
        val scopes = listOf(
            ScopeOption(id = null, displayName = "All", isAll = true),
            ScopeOption(id = "main", displayName = "Main", isMain = true),
            ScopeOption(id = "assistant-2", displayName = "Second"),
        )

        assertEquals(listOf("main", "assistant-2"), memoryScopesForMaintenance(null, scopes))
        assertEquals(listOf("assistant-2"), memoryScopesForMaintenance("assistant-2", scopes))
    }
}
