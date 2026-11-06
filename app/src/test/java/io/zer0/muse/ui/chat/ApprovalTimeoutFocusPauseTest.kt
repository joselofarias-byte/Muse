package io.zer0.muse.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Criterios de congelación de cuenta regresiva de aprobación.
 *
 * Además de "El usuario colapsa y lee la lista de aprobación pendiente", la sesión de aprobación también debe congelarse cuando no está en primer plano:
 * De lo contrario, si el usuario corta otra sesión y regresa después de unos minutos, solo verá un registro de rechazo que ya expiró.
 * El modelo también tratará este tiempo de espera como rechazo del usuario y lo redireccionará.
 */
class ApprovalTimeoutFocusPauseTest {

    @Test
    fun `focused session keeps counting down`() {
        assertFalse(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s1",
            ),
        )
    }

    @Test
    fun `unfocused session freezes the countdown`() {
        assertTrue(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s2",
            ),
        )
    }

    @Test
    fun `folded reading freezes the countdown even when focused`() {
        assertTrue(
            shouldPauseApprovalTimeout(
                foldedPaused = true,
                displayedSessionId = "s1",
                sessionId = "s1",
            ),
        )
    }

    @Test
    fun `unknown focus keeps the original counting behavior`() {
        // No se puede obtener el enfoque si la sesión no se ha inicializado y no se puede considerar que esté desenfocado en este momento.——De lo contrario, la aprobación quedará congelada permanentemente.
        assertFalse(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = null,
                sessionId = "s1",
            ),
        )
    }

    @Test
    fun `unfocused pause budget is bounded`() {
        // Debe haber un límite superior para la congelación fuera de foco; de lo contrario, las rondas de rutina y sesión se bloquearán permanentemente cuando el usuario no regrese.
        assertTrue(ChatToolApprovalCoordinator.APPROVAL_UNFOCUSED_PAUSE_MAX_MS > 0L)
        assertTrue(
            ChatToolApprovalCoordinator.APPROVAL_UNFOCUSED_PAUSE_MAX_MS >
                ChatToolApprovalCoordinator.TOOL_APPROVAL_TIMEOUT_MS,
        )
    }
}
