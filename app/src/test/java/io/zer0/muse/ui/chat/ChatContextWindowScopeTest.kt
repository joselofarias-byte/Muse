package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.muse.session.WarmupHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pruebas de regresión de calibre de ventana de contexto.
 *
 * Antecedentes:UI lado `ChatUiState.contextMaxTokens` por `ChatViewModel.refreshContextInfo`
 * prensa**Modelo seleccionado globalmente**Resuelto, la solicitud real puede ir a otro modelo debido a anulaciones de sesión/vinculaciones de ayuda.
 * 90% de truncamiento agresivo en la implementación anterior,1.35 Los presupuestos de tiempos de intercepción dura, calentamiento y compresión se calculan de acuerdo con el primero.
 * Por lo tanto, se permite el modelo de ventana pequeña (inevitable 400) y el modelo de ventana grande se comprime innecesariamente (el contexto se pierde en vano).
 *
 * Aquí solo se prueba la lógica pura: cuando la ventana del modelo efectivo dada es más pequeña que la ventana global, el umbral debe calcularse de acuerdo con la ventana pequeña.
 */
class ChatContextWindowScopeTest {

    @Test
    fun `per turn model window wins over the global fallback`() {
        val smallWindowModel = Model(id = "small-model", providerId = "p", contextWindow = 8_000)

        assertEquals(8_000, effectiveContextWindowTokens(smallWindowModel, 200_000))
    }

    @Test
    fun `declared window wins over registry inference`() {
        val declared = Model(id = "gpt-4o", providerId = "p", contextWindow = 32_768)

        assertEquals(32_768, effectiveContextWindowTokens(declared, 200_000))
    }

    @Test
    fun `fallback applies only when the model window is unknown`() {
        assertEquals(200_000, effectiveContextWindowTokens(null, 200_000))
        assertEquals(
            200_000,
            effectiveContextWindowTokens(Model(id = "no-spec-model", providerId = "p"), 200_000),
        )
    }

    @Test
    fun `zero window declaration falls back instead of disabling the guard`() {
        val zeroWindow = Model(id = "no-spec-model", providerId = "p", contextWindow = 0)

        assertEquals(200_000, effectiveContextWindowTokens(zeroWindow, 200_000))
    }

    @Test
    fun `pre send truncation threshold follows the smaller per turn window`() {
        val perTurnWindow = effectiveContextWindowTokens(
            Model(id = "small-model", providerId = "p", contextWindow = 8_000),
            200_000,
        )

        // 7.5K 93% de la ventana de 8K (activada) y solo 3,75% de la ventana global de 200K (no activada).
        assertTrue(isNearContextLimit(currentTokens = 7_500, windowTokens = perTurnWindow))
        assertFalse(isNearContextLimit(currentTokens = 7_500, windowTokens = 200_000))
    }

    @Test
    fun `hard limit follows the smaller per turn window`() {
        val perTurnWindow = effectiveContextWindowTokens(
            Model(id = "small-model", providerId = "p", contextWindow = 8_000),
            200_000,
        )

        // 8K 1,35 veces la ventana es 10_800, no 270_000 para la ventana de 200K.
        assertTrue(exceedsHardContextLimit(payloadTokens = 11_000, windowTokens = perTurnWindow))
        assertFalse(exceedsHardContextLimit(payloadTokens = 11_000, windowTokens = 200_000))
        assertFalse(exceedsHardContextLimit(payloadTokens = 10_000, windowTokens = perTurnWindow))
    }

    @Test
    fun `unknown window keeps guards disabled instead of blocking every send`() {
        assertFalse(isNearContextLimit(currentTokens = 5_000, windowTokens = 0))
        assertFalse(exceedsHardContextLimit(payloadTokens = 5_000, windowTokens = 0))
        assertFalse(isNearContextLimit(currentTokens = 0, windowTokens = 8_000))
    }

    @Test
    fun `warmup and compression budgets follow the per turn window`() {
        val perTurnWindow = effectiveContextWindowTokens(
            Model(id = "small-model", providerId = "p", contextWindow = 8_000),
            200_000,
        )

        // presupuesto de calentamiento = 60% de la ventana; Al comprimir el presupuesto también se deducen la reserva de producción y el margen de seguridad.
        // La clave es que ambos deben seguir la ventana por turno: según los 200K globales, equivale a calzar toda la historia en el modelo 8K.
        assertEquals(4_800, WarmupHistory.budgetTokensFor(perTurnWindow))
        // 8000 - 4096 - 1024 = 2880
        assertEquals(2_880, WarmupHistory.compressTokenBudgetFor(perTurnWindow))
        // 200000 - 4096 - 10000(ventana 5%) = 185904
        assertEquals(185_904, WarmupHistory.compressTokenBudgetFor(200_000))
    }

    @Test
    fun `stream coordinator fixes the turn model before using the window`() {
        val source = coordinatorSource()
        val prepareBody = source.substringAfter("internal suspend fun prepareHistory")
            .substringBefore("private suspend fun applySessionCheckpoint")

        assertTrue(
            "En la etapa de preparación, la encuadernación del modelo de esta ronda debe fijarse para que la ventana pueda tener una base.",
            prepareBody.contains("resolveModelBinding(state"),
        )
        assertTrue(
            "El presupuesto del token de precalentamiento debe basarse en la ventana del modelo redondo actual.",
            prepareBody.contains("effectiveContextWindowTokens(effectiveModel, accessor.snapshot.contextMaxTokens)"),
        )
    }

    @Test
    fun `warmup and compression budgets no longer read the global window directly`() {
        val source = coordinatorSource()

        assertFalse(
            "El presupuesto de calentamiento ya no se puede calcular directamente utilizando el contexto globalMaxTokens",
            source.contains("WarmupHistory.budgetTokensFor(accessor.snapshot.contextMaxTokens)"),
        )
        assertFalse(
            "El presupuesto de compresión ya no se puede calcular directamente utilizando contextMaxTokens globales",
            source.contains("WarmupHistory.compressTokenBudgetFor(accessor.snapshot.contextMaxTokens)"),
        )
    }

    @Test
    fun `generation controller truncation and hard limit use the per turn window`() {
        val source = controllerSource()
        val promptBody = source.substringAfter("suspend fun buildSystemPromptForStream")
            .substringBefore("suspend fun recordConversationShadow")

        assertTrue(
            "El truncamiento debe realizarse de acuerdo con la ventana del modelo redondo actual antes de enviar.",
            promptBody.contains("effectiveContextWindowTokens(effectiveModel, accessor.snapshot.contextMaxTokens)"),
        )
        assertFalse(
            "La intercepción dura ya no se puede calcular directamente utilizando el contexto globalMaxTokens",
            promptBody.contains("val hardMaxTokens = accessor.snapshot.contextMaxTokens"),
        )
        assertFalse(
            "90% Las advertencias ya no se pueden calcular directamente utilizando el contexto globalMaxTokens",
            promptBody.contains("val maxTokens = accessor.snapshot.contextMaxTokens"),
        )
    }

    private fun coordinatorSource(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatStreamCoordinator.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatStreamCoordinator.kt not found")

    private fun controllerSource(): String = listOf(
        Path.of("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
        Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("ChatGenerationController.kt not found")
}
