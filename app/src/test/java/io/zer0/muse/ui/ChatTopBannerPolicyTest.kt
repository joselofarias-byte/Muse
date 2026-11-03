package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prioridad superior del banner + visibilidad de errores/Estrategia de desaparición automática.
 *
 * prioridad (v2.6.8 (de):ERROR > COMPRESSION > PENDING_TOOLS > NOT_CONFIGURED > NONE.
 * Lo que está bloqueado aquí es"Los errores nunca se suprimen mediante el progreso de la compresión."Este retorno (anteriormente la COMPRESIÓN estaba por encima de ERROR,
 * El error no aparecerá ni una sola vez al comprimir más allá de la antigua ventana de desaparición automática de 5 segundos).
 */
class ChatTopBannerPolicyTest {

    @Test
    fun `error is never hidden by an active compression`() {
        assertEquals(
            ChatTopBanner.ERROR,
            resolveChatTopBanner(
                isCompressing = true,
                showPendingResume = true,
                hasErrors = true,
                isConfigured = false,
            ),
        )
    }

    @Test
    fun `compression still wins over the lower priority banners`() {
        assertEquals(
            ChatTopBanner.COMPRESSION,
            resolveChatTopBanner(
                isCompressing = true,
                showPendingResume = true,
                hasErrors = false,
                isConfigured = false,
            ),
        )
    }

    @Test
    fun `remaining banners keep their order below compression`() {
        assertEquals(
            ChatTopBanner.PENDING_TOOLS,
            resolveChatTopBanner(false, showPendingResume = true, hasErrors = false, isConfigured = false),
        )
        assertEquals(
            ChatTopBanner.NOT_CONFIGURED,
            resolveChatTopBanner(false, showPendingResume = false, hasErrors = false, isConfigured = false),
        )
        assertEquals(
            ChatTopBanner.NONE,
            resolveChatTopBanner(false, showPendingResume = false, hasErrors = false, isConfigured = true),
        )
    }

    // ── Visibilidad de errores ────────────────────────────────────────────────────────

    @Test
    fun `error counts as visible even while compressing`() {
        assertEquals(
            ErrorVisibility.VISIBLE,
            resolveErrorVisibility(
                errorStillPresent = true,
                appInForeground = true,
                isCompressing = true,
                showPendingResume = false,
                isConfigured = true,
            ),
        )
    }

    @Test
    fun `background app defers the dismiss timer`() {
        assertEquals(
            ErrorVisibility.PENDING,
            resolveErrorVisibility(
                errorStillPresent = true,
                appInForeground = false,
                isCompressing = false,
                showPendingResume = false,
                isConfigured = true,
            ),
        )
    }

    @Test
    fun `dismissed error is reported as gone`() {
        assertEquals(
            ErrorVisibility.GONE,
            resolveErrorVisibility(
                errorStillPresent = false,
                appInForeground = true,
                isCompressing = false,
                showPendingResume = false,
                isConfigured = true,
            ),
        )
    }

    @Test
    fun `banner occupied by a higher priority state defers the timer`() {
        // El error bajo la estrategia actual ya está en el nivel más alto, aquí usamos"Error no en la lista"Otras combinaciones extremas de implementaciones de bloqueo:
        // Una vez que alguien mueve ERROR bajo otro banner, la lógica de tiempo cambiará a PENDIENTE en lugar de espera ciega.
        assertEquals(
            ErrorVisibility.VISIBLE,
            resolveErrorVisibility(
                errorStillPresent = true,
                appInForeground = true,
                isCompressing = false,
                showPendingResume = true,
                isConfigured = false,
            ),
        )
    }

    // ── Descalificación automática ──────────────────────────────────────────────────────

    @Test
    fun `recoverable network error may auto dismiss`() {
        assertTrue(shouldAutoDismissError(ChatErrorType.NETWORK, isRecoverable = true))
        assertTrue(shouldAutoDismissError(ChatErrorType.RATE_LIMIT, isRecoverable = true))
    }

    @Test
    fun `errors needing a user decision never auto dismiss`() {
        assertFalse(shouldAutoDismissError(ChatErrorType.TOOL_ERROR, isRecoverable = true))
        assertFalse(shouldAutoDismissError(ChatErrorType.MODEL_ERROR, isRecoverable = true))
        assertFalse(shouldAutoDismissError(ChatErrorType.API_KEY, isRecoverable = true))
    }

    @Test
    fun `non recoverable error never auto dismiss`() {
        assertFalse(shouldAutoDismissError(ChatErrorType.UNKNOWN, isRecoverable = false))
    }

    @Test
    fun `reading window is long enough to read and bounded`() {
        assertTrue("15 segunda ventana", ERROR_AUTO_DISMISS_MS >= 10_000L)
        assertTrue("Pero no deberías ocupar la pantalla por mucho tiempo.", ERROR_AUTO_DISMISS_MS <= 30_000L)
        assertTrue("Se puede ver que el límite superior de espera debe ser mayor que la ventana de lectura.", ERROR_VISIBLE_WAIT_MS > ERROR_AUTO_DISMISS_MS)
    }
}
