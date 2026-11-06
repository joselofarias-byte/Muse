package io.zer0.muse.automation.core

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.executors.AccessibilityExecutor
import io.zer0.muse.automation.executors.RootExecutor
import io.zer0.muse.automation.executors.RootRequestResult
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UI Gerente de Automatización —— Entrada unificada para tres niveles de actuadores.
 *
 * Responsabilidades:
 * 1. mantener [AccessibilityExecutor] / [ShellExecutor] / [RootExecutor] Ejemplo
 * 2. Detectar la disponibilidad de cada capa al inicio,expuesto [permissionState]
 * 3. distribución de acciones:Seleccione el actuador según el nivel más bajo requerido para la acción.,Bajar de categoría cuando falla el nivel superior
 * 4. Tratamiento especial para capturas de pantalla.:La capa de accesibilidad no admite capturas de pantalla,prioridad Shell/Root
 *
 * singleton(Koin inyectar),El ciclo de vida sigue la aplicación.
 */
class AutomationManager(
    private val context: Context,
    private val shizukuAuthorizer: ShizukuAuthorizer,
    /**
     * Ejecutor de raíz inyectable(Construido por contexto de forma predeterminada).
     * El punto de inyección se utiliza para pruebas únicas para simular "sin tiempo de espera/autorización" y otras rutas que no se pueden reproducir en el entorno de prueba.
     */
    private val rootExecutor: RootExecutor = RootExecutor(context),
    /**
     * Ejecutor de Shell inyectable para verificación de raíz cuando se desconecta Shizuku.
     * El valor predeterminado de producción todavía usa un ShellExecutor compartido con el autorizador.
     */
    private val shellExecutor: io.zer0.muse.automation.executors.ShellExecutor =
        io.zer0.muse.automation.executors.ShellExecutor(context, shizukuAuthorizer),
) {
    val accessibility = AccessibilityExecutor(context)

    // UI El shell automatizado y la verificación de estado comparten el mismo autorizador Shizuku para evitar“Se nota que está autorizado, pero la ejecución es normal. sh”.
    val shell = shellExecutor
    val root: RootExecutor = rootExecutor

    private val mutex = Mutex()

    private val _permissionState = MutableStateFlow(PermissionState())

    /** Estado de permiso actual de cada capa.,Observe las páginas de configuración y las herramientas a medida que se ejecutan. */
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    /**
     * v2.7: Instantánea en tiempo real (sincrónica, liviana)—— La accesibilidad se vuelve a detectar en cada lectura.
     *
     * ## por qué es necesario
     *
     * [permissionState] es un valor almacenado en caché, sólo en [refreshPermissions] Actualizado cuando se llama explícitamente
     * (App inicio, regresando de la página de configuración, después de solicitar Root). Pero los servicios de accesibilidad son**conexión asíncrona**de:
     * Después de que el usuario enciende el interruptor en la configuración del sistema,`MuseAccessibilityService.onServiceConnected`
     * Simplemente cuelgue la instancia; Si no se activa ninguna actualización en este momento, el caché siempre se detendrá en falso al inicio.
     * Conducir a un error de cálculo previo a la verificación de herramientas“No autorizado”(Prueba de usuario: El asistente muestra que está abierto, pero la herramienta informa que no se ha abierto).
     *
     * La accesibilidad es una consulta sincrónica (`service?.isAccessibilityServiceEnabled()`),
     * La sobrecarga es mínima, por lo que se requiere una verificación previa de la herramienta.“estado real actual”Úselo directamente en la escena,
     * No depende del caché; al mismo tiempo, los resultados de la detección se vuelven a escribir en la memoria caché para mantener la coherencia de la interfaz de usuario.
     *
     * Shell/Root La sonda está suspendida y puede bloquearse (su sonda), no apto para trayectorias sincronizadas,
     * Por lo tanto, este método solo actualiza la accesibilidad en tiempo real y utiliza valores almacenados en caché para el resto.
     */
    fun liveSnapshot(): PermissionState {
        val a11y = accessibility.isAvailableSync()
        val current = _permissionState.value
        return if (a11y == current.accessibilityEnabled) {
            current
        } else {
            val updated = current.copy(accessibilityEnabled = a11y)
            _permissionState.value = updated
            Logger.i(TAG, "Estado de accesibilidad actualizado en tiempo real: a11y=$a11y")
            updated
        }
    }

    /** Todos los actuadores(de menor a mayor). */
    private val executors: List<AutomationExecutor> = listOf(accessibility, shell, root)

    /**
     * Detectando disponibilidad de tres niveles. Se debe llamar cuando se inicia la aplicación y al regresar de la página de configuración.
     */
    suspend fun refreshPermissions(): PermissionState = mutex.withLock {
        val a11y = probe("accessibility") { accessibility.isAvailable() }
        // ordinario `sh` Siempre se puede iniciar en el entorno limitado de la aplicación de Android y no se puede autorizar en nombre de adb/Shizuku.
        // El estado de segundo nivel debe completar el enlace UserService y no puede simplemente mirar el bit de autorización de Shizuku.
        val shizukuStatus = try {
            shizukuAuthorizer.diagnose()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "shizuku diagnostic failed: ${e.message}", e)
            ShizukuAuthorizer.ShizukuStatus(
                ShizukuAuthorizer.ShizukuState.USER_SERVICE_UNAVAILABLE,
                "Shizuku Falló la detección de estado",
            )
        }
        val rt = probe("root") { root.isAvailable() }
        val state = PermissionState(
            accessibilityEnabled = a11y,
            shellEnabled = shizukuStatus.isReady,
            rootEnabled = rt,
            shizukuState = shizukuStatus.state,
            shizukuMessage = shizukuStatus.message,
        )
        _permissionState.value = state
        Logger.i(
            TAG,
            "permissions refreshed: a11y=$a11y shell=${shizukuStatus.state} root=$rt",
        )
        state
    }

    /** La detección de permisos pertenece a la actualización del estado de la interfaz de usuario; cuando un solo canal es anormal, se degrada a no disponible y no se debe penetrar la página de configuración. */
    private suspend fun probe(name: String, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "$name permission probe failed: ${e.message}", e)
        false
    }

    /**
     * Solicitar explícitamente autorización de Root(El comportamiento principal de la tarjeta de tercera capa en la página de configuración.).
     *
     * confiar [RootExecutor.requestRootAccess] Activar ventana emergente del administrador raíz;Independientemente del éxito o el fracaso, vuelva a detectar la capa 3
     * Estado,dejar [permissionState] Refleja resultados reales de forma inmediata. No retenido [mutex] —— su La sonda puede esperar la acción del usuario.,
     * Otras llamadas de automatización no deben bloquearse durante este período.
     */
    suspend fun requestRoot(): RootRequestResult {
        val result = root.requestRootAccess()
        refreshPermissions()
        return result
    }

    // ── interfaz de acción unificada ────────────────────────────────────────

    /**
     * v2.x Automatización Fase I:Ejecución unificada de comandos de dispositivos. —— Shizuku Prioridad,Root Llega al fondo de esto.
     *
     * @return nombre del puesto("Shizuku"/"Root")con resultados detallados;Devuelve nulo si ninguno de los equipos está disponible.
     */
    suspend fun execTiered(command: String): Pair<String, io.zer0.muse.automation.executors.ShellExecutor.ExecDetail>? {
        var state = permissionState.value
        if (!state.shellEnabled && !state.rootEnabled) {
            // La caché puede estar caducada(Recién autorizado/Cancelar),Compruébalo y decide de nuevo.
            state = refreshPermissions()
        }
        return when {
            state.shellEnabled -> {
                val shizukuResult = shell.execDetailed(command)
                if (shizukuResult.exitCode == 0 || !state.rootEnabled) {
                    "Shizuku" to shizukuResult
                } else {
                    Logger.w(TAG, "Shizuku command failed at runtime, falling back to Root")
                    "Root" to root.execDetailed(command)
                }
            }
            state.rootEnabled -> "Root" to root.execDetailed(command)
            else -> null
        }
    }

    /** captura de pantalla:Priorizar autorizado Shell/Root;API 34+ Finalmente degradado a capturas de pantalla accesibles. */
    suspend fun screenshot(): ByteArray? = mutex.withLock {
        firstNonEmptyScreenshot(
            { shell.screenshot() },
            { root.screenshot() },
            { accessibility.screenshot() },
        )
    }

    /** leer pantalla:Priorizar la accesibilidad(El árbol de control es el más completo.),Rebajar Shell(uiautomator dump). */
    suspend fun readScreen(): ScreenInfo = mutex.withLock {
        if (accessibility.isAvailable()) {
            try {
                val info = accessibility.readScreen()
                if (info.nodes.isNotEmpty()) return@withLock info
            } catch (e: Exception) {
                Logger.w(TAG, "a11y readScreen failed: ${e.message}")
            }
        }
        if (shell.isAvailable()) return@withLock shell.readScreen()
        if (root.isAvailable()) return@withLock root.readScreen()
        // No se puede falsificar sin canales automatizados.“shell Leer con éxito”;página de configuración/En consecuencia, la herramienta muestra un estado de indisponibilidad explícito.
        ScreenInfo(
            packageName = null,
            activityName = null,
            nodes = emptyList(),
            screenWidth = context.resources.displayMetrics.widthPixels,
            screenHeight = context.resources.displayMetrics.heightPixels,
            source = "unavailable",
        )
    }

    /** Lea el árbol de control de la pantalla virtual de fondo; sólo las capacidades Shell/Root pueden acceder a la pantalla especificada. */
    suspend fun readScreenOnDisplay(displayId: Int): ScreenInfo? = mutex.withLock {
        when {
            shell.isAvailable() -> shell.readScreenOnDisplay(displayId)
            root.isAvailable() -> root.readScreenOnDisplay(displayId)
            else -> null
        }
    }

    /** El nombre del paquete de interfaz actual. */
    suspend fun currentPackage(): String? = mutex.withLock {
        accessibility.currentPackage() ?: if (shell.isAvailable()) shell.currentPackage() else root.currentPackage()
    }

    /** Haga clic:Priorizar la accesibilidad,Luego presione la autorización real para bajar a Shizuku/Root. */
    suspend fun tap(x: Int, y: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.tap(x, y)
        } else if (shell.isAvailable()) shell.tap(x, y) else root.tap(x, y)
    }

    /** Pulsación larga. */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.longPress(x, y, durationMs)
        } else if (shell.isAvailable()) shell.longPress(x, y, durationMs) else root.longPress(x, y, durationMs)
    }

    /** Deslizar. */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.swipe(x1, y1, x2, y2, durationMs)
        } else if (shell.isAvailable()) {
            shell.swipe(x1, y1, x2, y2, durationMs)
        } else {
            root.swipe(x1, y1, x2, y2, durationMs)
        }
    }

    /**
     * v2.x: Pellizcar para hacer zoom — Despacho sin barrerasImplementación de gestos con dos dedos;
     * Shell/Root El comando de entrada no tiene capacidad de inyección con varios dedos.,Devuelve falso si no está disponible.
     */
    suspend fun pinch(centerX: Int, centerY: Int, startDistance: Int, endDistance: Int, durationMs: Long = 300): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.pinch(centerX, centerY, startDistance, endDistance, durationMs)
        } else {
            false
        }
    }

    /** v2.x: Deslizamiento multisegmento — Accesible y preciso punto por punto;Los niveles restantes se degradarán en el primer y último punto. */
    suspend fun swipePath(points: List<Pair<Int, Int>>, durationMs: Long = 400): Boolean = mutex.withLock {
        if (points.size < 2) return@withLock false
        if (accessibility.isAvailable()) return@withLock accessibility.swipePath(points, durationMs)
        val first = points.first()
        val last = points.last()
        if (shell.isAvailable()) {
            shell.swipe(first.first, first.second, last.first, last.second, durationMs)
        } else {
            root.swipe(first.first, first.second, last.first, last.second, durationMs)
        }
    }

    /** Introduzca texto. */
    suspend fun inputText(text: String): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.inputText(text)
        } else if (shell.isAvailable()) shell.inputText(text) else root.inputText(text)
    }

    /** botón. */
    suspend fun pressKey(keyCode: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            accessibility.pressKey(keyCode)
        } else if (shell.isAvailable()) shell.pressKey(keyCode) else root.pressKey(keyCode)
    }

    /** Inicie la aplicación. */
    suspend fun launchApp(packageName: String): Boolean = mutex.withLock {
        accessibility.launchApp(packageName) ||
            if (shell.isAvailable()) shell.launchApp(packageName) else root.launchApp(packageName)
    }

    /** Devolver. */
    suspend fun back(): Boolean = mutex.withLock {
        accessibility.back() || if (shell.isAvailable()) shell.back() else root.back()
    }

    /** Home. */
    suspend fun home(): Boolean = mutex.withLock {
        accessibility.home() || if (shell.isAvailable()) shell.home() else root.home()
    }

    /** Abra la pantalla de notificaciones. */
    suspend fun openNotifications(): Boolean = mutex.withLock {
        accessibility.openNotifications() || if (shell.isAvailable()) shell.openNotifications() else root.openNotifications()
    }

    /** Abra la configuración rápida. */
    suspend fun openQuickSettings(): Boolean = mutex.withLock {
        accessibility.openQuickSettings() || if (shell.isAvailable()) shell.openQuickSettings() else root.openQuickSettings()
    }

    /**
     * Busque el control por texto y haga clic en él.readScreen → partido text/description → tap punto central.
     * @return ¿Has encontrado y hecho clic?
     */
    suspend fun tapByText(text: String, exact: Boolean = false): Boolean {
        // readScreen/tap Cada uno es responsable del bloqueo; no puede haber otra capa de Mutex aquí; de lo contrario, se producirá un punto muerto no reentrante.
        val screen = readScreen()
        val node = screen.findBestTextNode(text, exact) ?: return false
        return tap(node.centerX, node.centerY)
    }

    /**
     * Versión mejorada del clic semántico: cuando no se pueda encontrar el objetivo, deslice hacia arriba la pantalla actual un número limitado de veces. Después de hacer clic, puede esperar a que aparezca el texto de verificación.
     *
     * Esta es la primitiva de página larga más importante para la capa de accesibilidad;Shell/Root También se puede reutilizar porque [readScreen] con [swipe]
     * Se degradará automáticamente según los canales disponibles actualmente. El valor predeterminado no es desplazamiento, manténgalo antiguo [tapByText] semántica de una sola vez.
     */
    @Suppress("ReturnCount") // Bounded retry loop has explicit success, verification, and channel-failure exits.
    suspend fun tapByTextWithRetry(text: String, exact: Boolean = false, maxSwipes: Int = 0, verifyText: String? = null): Boolean {
        return tapByTextWithRetryDetailed(text, exact, maxSwipes, verifyText).success
    }

    /** Resultados estructurados de clics semánticos para orquestación de herramientas y auditoría de UI. */
    data class TextActionResult(
        val success: Boolean,
        val attempts: Int,
        val matched: Boolean,
        val verified: Boolean,
        val matchedLabel: String? = null,
    )

    /** con [tapByTextWithRetry] Misma acción pero mantener observaciones./Verificar detalles. */
    @Suppress("ReturnCount") // Each exit represents a distinct observable action outcome.
    suspend fun tapByTextWithRetryDetailed(
        text: String,
        exact: Boolean = false,
        maxSwipes: Int = 0,
        verifyText: String? = null,
    ): TextActionResult {
        val attempts = maxSwipes.coerceIn(0, 5)
        repeat(attempts + 1) { attempt ->
            val screen = readScreen()
            val node = screen.findBestTextNode(text, exact)
            if (node != null) {
                val matchedLabel = node.text ?: node.contentDescription
                // Once a tap is attempted, a false channel result cannot prove that no input
                // reached the device. Never scroll/retry and risk replaying the side effect.
                if (!tap(node.centerX, node.centerY)) {
                    return TextActionResult(false, attempt + 1, matched = true, verified = false, matchedLabel)
                }
                if (verifyText.isNullOrBlank()) {
                    return TextActionResult(true, attempt + 1, matched = true, verified = true, matchedLabel)
                }
                kotlinx.coroutines.delay(350L)
                val verified = readScreen().nodes.any { n ->
                    val label = n.text ?: n.contentDescription ?: return@any false
                    label.contains(verifyText, ignoreCase = true)
                }
                return TextActionResult(verified, attempt + 1, matched = true, verified = verified, matchedLabel)
            }
            if (attempt < attempts && screen.screenHeight > 0) {
                val centerX = screen.screenWidth / 2
                val bottom = (screen.screenHeight * 0.82f).toInt()
                val top = (screen.screenHeight * 0.28f).toInt()
                if (!swipe(centerX, bottom, centerX, top, 450L)) {
                    return TextActionResult(false, attempt + 1, matched = false, verified = false)
                }
                kotlinx.coroutines.delay(300L)
            }
        }
        return TextActionResult(false, attempts + 1, matched = false, verified = false)
    }

    /** Stable resource-id semantic click with bounded scrolling and optional post-click verification. */
    suspend fun tapByViewIdWithRetryDetailed(viewId: String, maxSwipes: Int = 0, verifyText: String? = null): TextActionResult {
        val attempts = maxSwipes.coerceIn(0, 5)
        var terminalResult: TextActionResult? = null
        for (attempt in 0..attempts) {
            val screen = readScreen()
            val node = screen.findBestViewIdNode(viewId)
            if (node != null) {
                terminalResult = tapViewIdNode(node, attempt + 1, verifyText)
                break
            }
            if (attempt < attempts && screen.screenHeight > 0) {
                val centerX = screen.screenWidth / 2
                if (!swipe(centerX, (screen.screenHeight * 0.82f).toInt(), centerX, (screen.screenHeight * 0.28f).toInt(), 450L)) {
                    terminalResult = TextActionResult(false, attempt + 1, matched = false, verified = false)
                    break
                }
                kotlinx.coroutines.delay(300L)
            }
        }
        return terminalResult ?: TextActionResult(false, attempts + 1, false, false)
    }

    private suspend fun tapViewIdNode(node: UiNode, attempt: Int, verifyText: String?): TextActionResult {
        val label = node.viewIdResourceName ?: node.text ?: node.contentDescription
        return when {
            !tap(node.centerX, node.centerY) ->
                TextActionResult(false, attempt, matched = true, verified = false, label)
            verifyText.isNullOrBlank() -> TextActionResult(true, attempt, matched = true, verified = true, label)
            else -> {
                kotlinx.coroutines.delay(350L)
                val verified = readScreen().nodes.any { candidate ->
                    val text = candidate.text ?: candidate.contentDescription ?: return@any false
                    text.contains(verifyText, ignoreCase = true)
                }
                TextActionResult(verified, attempt, matched = true, verified = verified, label)
            }
        }
    }

    /**
     * Double-tap at (x, y) for selection or special gestures.
     * Uses accessibility dispatchGesture when available, otherwise delegates to shell/root.
     */
    suspend fun doubleTap(x: Int, y: Int): Boolean = mutex.withLock {
        if (accessibility.isAvailable()) {
            // dispatchGesture No se pueden acumular dos clics en un gesto,Utilice dos clics rápidos para aproximarse a un doble clic.
            if (accessibility.tap(x, y)) {
                kotlinx.coroutines.delay(100)
                return@withLock accessibility.tap(x, y)
            }
        }
        if (shell.isAvailable()) return@withLock shell.tap(x, y) && shell.tap(x, y)
        root.tap(x, y) && root.tap(x, y)
    }

    /**
     * Execute an action with automatic retry on failure (up to [maxRetries] additional attempts).
     * Used by tool executors to improve reliability of critical operations.
     *
     * @param action the action to execute
     * @param maxRetries number of additional attempts after first failure (default 1)
     * @return true if any attempt succeeded
     */
    suspend fun executeWithRetry(action: suspend () -> Boolean, maxRetries: Int = 1): Boolean {
        var lastSuccess = action()
        if (lastSuccess) return true
        for (i in 1..maxRetries) {
            Logger.i(TAG, "executeWithRetry attempt $i/$maxRetries")
            kotlinx.coroutines.delay(300L)
            lastSuccess = action()
            if (lastSuccess) return true
        }
        return false
    }

    /**
     * Capture a full [ScreenSnapshot] with version tracking.
     * Convenience wrapper over [screenshot] + [readScreen].
     */
    suspend fun captureSnapshot(version: Int = 0): ScreenSnapshot {
        val info = readScreen()
        val shot = screenshot()
        return ScreenSnapshot(info = info, screenshotPng = shot, version = version)
    }

    /** Nivel más alto disponible(Sin permiso para regresar NONE). */
    suspend fun highestLevel(): PermissionLevel {
        val s = _permissionState.value
        return when {
            s.rootEnabled -> PermissionLevel.ROOT
            s.shellEnabled -> PermissionLevel.SHELL
            s.accessibilityEnabled -> PermissionLevel.ACCESSIBILITY
            else -> PermissionLevel.NONE
        }
    }

    data class PermissionState(
        val accessibilityEnabled: Boolean = false,
        val shellEnabled: Boolean = false,
        val rootEnabled: Boolean = false,
        val shizukuState: ShizukuAuthorizer.ShizukuState = ShizukuAuthorizer.ShizukuState.NOT_INSTALLED,
        val shizukuMessage: String = "No detectado",
    ) {
        /** Si hay al menos una capa disponible. */
        val anyEnabled: Boolean get() = accessibilityEnabled || shellEnabled || rootEnabled

        /** v2.6.7: Nombre de jerarquía para la salida de la autoprueba (más legible que el nombre de enumeración). */
        fun highestLevelName(): String = when {
            rootEnabled -> "Root"
            shellEnabled -> "Shell(Shizuku/adb)"
            accessibilityEnabled -> "Accesibilidad"
            else -> "Ninguno (solo acciones en la aplicación)"
        }
    }

    data class ScreenCapture(
        val info: ScreenInfo,
        val screenshotPng: ByteArray?,
    )

    companion object {
        private const val TAG = "AutomationMgr"
    }
}

/** Try screenshot channels in priority order, treating empty or failed captures as unavailable. */
internal suspend fun firstNonEmptyScreenshot(vararg sources: suspend () -> ByteArray?): ByteArray? {
    for (source in sources) {
        val image = try {
            source()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (image != null && image.isNotEmpty()) return image
    }
    return null
}
