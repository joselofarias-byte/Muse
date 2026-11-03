package io.zer0.muse.automation.vdisplay

import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import kotlinx.coroutines.delay

/** Bounded, display-scoped semantic actions shared by the visual Agent and durable workflows. */
object VirtualDisplaySemanticActions {
    data class TapTextResult(
        val success: Boolean,
        val attempts: Int,
        val matched: Boolean,
        val verified: Boolean,
        val outcomeUnknown: Boolean = false,
        val error: String? = null,
    )

    suspend fun tapText(
        manager: AutomationManager,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
        text: String,
        exact: Boolean = false,
        maxSwipes: Int = 0,
        verifyText: String? = null,
    ): TapTextResult {
        if (text.isBlank()) return TapTextResult(false, 0, false, false, error = "desaparecido text")
        val boundedSwipes = maxSwipes.coerceIn(0, MAX_SWIPES)
        repeat(boundedSwipes + 1) { attempt ->
            val screen = manager.readScreenOnDisplay(displayId)
                ?: return TapTextResult(false, attempt + 1, false, false, error = "El árbol de control de pantalla virtual no está disponible")
            val node = screen.findBestTextNode(text, exact)
            if (node != null) {
                val x = node.centerX
                val y = node.centerY
                if (
                    screen.screenWidth <= 0 ||
                    screen.screenHeight <= 0 ||
                    x !in 0 until screen.screenWidth ||
                    y !in 0 until screen.screenHeight
                ) {
                    return TapTextResult(false, attempt + 1, true, false, error = "Las coordenadas de control del objetivo no son válidas")
                }
                val tap = VirtualDisplayInputCommand.build(displayId, "tap", mapOf("x" to x.toString(), "y" to y.toString()))
                    ?: return TapTextResult(false, attempt + 1, true, false, error = "No se puede construir la pantalla virtual, haga clic")
                when (val check = DeviceCommandPolicy.validate(tap.shellCommand)) {
                    is DeviceCommandPolicy.Check.Invalid ->
                        return TapTextResult(false, attempt + 1, true, false, error = "La política deniega el comando de clic:${check.reason}")
                    DeviceCommandPolicy.Check.Valid -> Unit
                }
                val execution = displayManager.exec(tap.shellCommand)
                if (execution.exitCode != 0) {
                    return TapTextResult(
                        false,
                        attempt + 1,
                        true,
                        false,
                        outcomeUnknown = true,
                        error = "Se desconoce el resultado de hacer clic en la pantalla virtual(exit=${execution.exitCode})",
                    )
                }
                val expected = verifyText?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return TapTextResult(true, attempt + 1, true, false)
                delay(350L)
                val after = manager.readScreenOnDisplay(displayId)
                    ?: return TapTextResult(false, attempt + 1, true, false, outcomeUnknown = true, error = "Haga clic en Enviado pero no puede leer la interfaz de verificación")
                val verified = after.nodes.any { candidate ->
                    val label = candidate.text ?: candidate.contentDescription ?: return@any false
                    label.contains(expected, ignoreCase = true)
                }
                return if (verified) {
                    TapTextResult(true, attempt + 1, true, true)
                } else {
                    TapTextResult(false, attempt + 1, true, false, outcomeUnknown = true, error = "El clic fue enviado pero no se observó la interfaz esperada.")
                }
            }
            if (attempt < boundedSwipes) {
                if (screen.screenWidth <= 0 || screen.screenHeight <= 0) {
                    return TapTextResult(false, attempt + 1, false, false, error = "La resolución de la pantalla virtual no es válida y no se puede desplazar")
                }
                val swipe = VirtualDisplayInputCommand.build(
                    displayId,
                    "swipe",
                    mapOf(
                        "x1" to (screen.screenWidth / 2).toString(),
                        "y1" to (screen.screenHeight * 82 / 100).toString(),
                        "x2" to (screen.screenWidth / 2).toString(),
                        "y2" to (screen.screenHeight * 28 / 100).toString(),
                        "duration_ms" to SWIPE_DURATION_MS.toString(),
                    ),
                ) ?: return TapTextResult(false, attempt + 1, false, false, error = "No se puede construir el desplazamiento de la pantalla virtual")
                when (val check = DeviceCommandPolicy.validate(swipe.shellCommand)) {
                    is DeviceCommandPolicy.Check.Invalid ->
                        return TapTextResult(false, attempt + 1, false, false, error = "Comando de desplazamiento denegado por política:${check.reason}")
                    DeviceCommandPolicy.Check.Valid -> Unit
                }
                if (displayManager.exec(swipe.shellCommand).exitCode != 0) {
                    return TapTextResult(false, attempt + 1, false, false, error = "Error al desplazarse por la pantalla virtual")
                }
                delay(SCROLL_SETTLE_MS)
            }
        }
        return TapTextResult(false, boundedSwipes + 1, false, false, error = "El objetivo aún no se encuentra después del desplazamiento limitado")
    }

    suspend fun tapViewId(
        manager: AutomationManager,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
        viewId: String,
        maxSwipes: Int = 0,
        verifyText: String? = null,
    ): TapTextResult {
        if (viewId.isBlank()) return TapTextResult(false, 0, false, false, error = "desaparecido view_id")
        val bounded = maxSwipes.coerceIn(0, MAX_SWIPES)
        repeat(bounded + 1) { attempt ->
            val screen = manager.readScreenOnDisplay(displayId)
                ?: return TapTextResult(false, attempt + 1, false, false, error = "El árbol de control de pantalla virtual no está disponible")
            val node = screen.findBestViewIdNode(viewId)
            if (node != null) {
                val x = node.centerX
                val y = node.centerY
                if (
                    screen.screenWidth <= 0 ||
                    screen.screenHeight <= 0 ||
                    x !in 0 until screen.screenWidth ||
                    y !in 0 until screen.screenHeight
                ) {
                    return TapTextResult(false, attempt + 1, true, false, error = "Las coordenadas de control del objetivo no son válidas")
                }
                val command = VirtualDisplayInputCommand.build(displayId, "tap", mapOf("x" to x.toString(), "y" to y.toString()))
                    ?: return TapTextResult(false, attempt + 1, true, false, error = "No se puede construir la pantalla virtual, haga clic")
                when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
                    is DeviceCommandPolicy.Check.Invalid -> return TapTextResult(
                        false,
                        attempt + 1,
                        true,
                        false,
                        error = "La política deniega el comando de clic:${check.reason}",
                    )
                    DeviceCommandPolicy.Check.Valid -> Unit
                }
                if (displayManager.exec(command.shellCommand).exitCode != 0) {
                    return TapTextResult(false, attempt + 1, true, false, outcomeUnknown = true, error = "Se desconoce el resultado de hacer clic en la pantalla virtual")
                }
                val expected = verifyText?.takeIf { it.isNotBlank() } ?: return TapTextResult(true, attempt + 1, true, true)
                delay(350L)
                val after = manager.readScreenOnDisplay(displayId)
                    ?: return TapTextResult(
                        success = false,
                        attempts = attempt + 1,
                        matched = true,
                        verified = false,
                        outcomeUnknown = true,
                        error = "Haga clic en Enviado pero no se puede verificar",
                    )
                val verified = after.nodes.any { candidate ->
                    val text = candidate.text ?: candidate.contentDescription ?: return@any false
                    text.contains(expected, ignoreCase = true)
                }
                return if (verified) {
                    TapTextResult(
                        true,
                        attempt + 1,
                        true,
                        true,
                    )
                } else {
                    TapTextResult(false, attempt + 1, true, false, outcomeUnknown = true, error = "Haga clic en Enviado pero no verificado")
                }
            }
            if (attempt < bounded && screen.screenHeight > 0) {
                val swipe = VirtualDisplayInputCommand.build(
                    displayId, "swipe",
                    mapOf(
                        "x1" to (screen.screenWidth / 2).toString(), "y1" to (screen.screenHeight * 82 / 100).toString(),
                        "x2" to (screen.screenWidth / 2).toString(), "y2" to (screen.screenHeight * 28 / 100).toString(),
                        "duration_ms" to SWIPE_DURATION_MS.toString(),
                    ),
                ) ?: return TapTextResult(false, attempt + 1, false, false, error = "No se puede construir el desplazamiento")
                if (displayManager.exec(swipe.shellCommand).exitCode != 0) {
                    return TapTextResult(
                        false,
                        attempt + 1,
                        false,
                        false,
                        error = "desplazamiento fallido",
                    )
                }
                delay(SCROLL_SETTLE_MS)
            }
        }
        return TapTextResult(false, bounded + 1, false, false, error = "No encontrado después del desplazamiento limitado view_id")
    }

    private const val MAX_SWIPES = 5
    private const val SWIPE_DURATION_MS = 450L
    private const val SCROLL_SETTLE_MS = 300L
}
