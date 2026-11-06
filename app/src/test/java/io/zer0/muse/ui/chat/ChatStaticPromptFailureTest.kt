package io.zer0.muse.ui.chat

import io.zer0.common.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Estrategia de manejo cuando el indicador estático del sistema no está disponible.
 *
 * Implementación original `resultOf { buildStaticSnapshot(...) }.getOrNull() ?: ""` "generará un error"
 * y "El resultado de la construcción está vacío" se degradan a palabras vacías y continúan generándose: el modelo pierde personalidad, descripción de la herramienta, memoria y restricciones de formato.
 * pero todavía produce uno“parece normal”Responder —— Los usuarios sólo sentirán“AI De repente ya no lo parece”, y esta respuesta será
 * Ingresa al historial de sesiones, contaminando rondas posteriores. Aquí se bloquea "determinado como no disponible" y "la persona que llama debe cancelar esta ronda".
 */
class ChatStaticPromptFailureTest {

    @Test
    fun `build error is not degraded to an empty prompt`() {
        val outcome = staticPromptBuildOutcome(Result.Error("boom", IllegalStateException("boom")))

        assertTrue("Los errores de compilación deben juzgarse como Failed", outcome is StaticPromptOutcome.Failed)
        assertEquals("boom", (outcome as StaticPromptOutcome.Failed).reason)
    }

    @Test
    fun `blank build result is treated as unavailable`() {
        assertEquals(StaticPromptOutcome.Empty, staticPromptBuildOutcome(Result.Success("")))
        assertEquals(StaticPromptOutcome.Empty, staticPromptBuildOutcome(Result.Success("   \n\t ")))
    }

    @Test
    fun `non blank build result is ready`() {
        val outcome = staticPromptBuildOutcome(Result.Success("definición de personalidad\nDescripción de la herramienta"))

        assertTrue(outcome is StaticPromptOutcome.Ready)
        assertEquals("definición de personalidad\nDescripción de la herramienta", (outcome as StaticPromptOutcome.Ready).prompt)
    }

    @Test
    fun `generation controller aborts the turn instead of continuing with an empty prompt`() {
        val source = controllerSource()
        // Sólo se comprueban los componentes básicos de las instantáneas estáticas (aún quedan "Bajar a vacío en caso de error" uso razonable).
        val staticSnapshotBody = source.substringAfter("val staticSnapshot =")
            .substringBefore("val dynamicSection")

        assertFalse(
            "Las palabras de aviso estático que no se construyen no se deben degradar silenciosamente a una cadena vacía para continuar generándose.",
            staticSnapshotBody.contains(".getOrNull() ?: \"\""),
        )
        assertTrue(
            "Debe regresar temprano cuando la palabra de aviso estático no está disponible",
            staticSnapshotBody.contains("return false"),
        )
        assertTrue(
            "La función de creación de palabras rápidas debe devolver la disponibilidad a la persona que llama",
            source.contains("suspend fun buildSystemPromptForStream(state: StreamRunState): Boolean"),
        )
        assertTrue(
            "La palabra de aviso estática no está disponible y se debe cancelar la ronda actual.",
            source.contains("if (!buildSystemPromptForStream(state))"),
        )
        assertTrue(
            "Se debe tomar la ruta de cancelación para informar errores visibles.",
            source.contains("R.string.err_chat_prompt_unavailable"),
        )
        assertTrue(
            "Las rutas de aborto deben tener trayectorias finales diagnosticables.",
            source.contains("recordDebugSummary(state, \"prompt_unavailable\")"),
        )
    }

    @Test
    fun `prompt failure is reported before the request is sent`() {
        val source = controllerSource()
        val streamBody = source.substringAfter("fun launchStream(")
            .substringBefore("suspend fun finalizeResponse")

        // La cancelación debe ocurrir antes de iniciar realmente la solicitud (runToolLoop) antes; de lo contrario, equivale a dejar que el modelo responda con una palabra inicial vacía.
        val abortIndex = streamBody.indexOf("err_chat_prompt_unavailable")
        val requestIndex = streamBody.indexOf("deps.runToolLoop(state)")
        assertTrue("La rama de aborto debe existir.", abortIndex >= 0)
        assertTrue("Esta ronda de solicitudes debe enviarse después de que la palabra clave esté lista.", requestIndex > abortIndex)
    }

    private fun controllerSource(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatGenerationController.kt not found")
}
