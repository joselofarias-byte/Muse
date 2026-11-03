package io.zer0.muse.automation.core

/**
 * UI Primitivas de acción de automatización.
 *
 * Los tres niveles de ejecutores implementan estas primitivas.,La diferencia radica únicamente en el alcance de las capacidades y los métodos de implementación.:
 * - Accesibilidad: Leer el árbol de control a través de AccessibilityService + dispatchGesture
 * - Shell: mediante comando de entrada + screencap + dumpsys
 * - Root: Agregar /data/acceso a datos y modificación del sistema basado en Shell
 *
 * Todas las acciones son suspend,Conveniente para la serialización en corrutinas./Programación paralela.
 */
interface AutomationExecutor {

    /** El nivel de permiso correspondiente a este ejecutor. */
    val level: PermissionLevel

    /** ¿Este ejecutor está disponible actualmente?(El servicio está conectado./Shizuku Autorizado/root si su). */
    suspend fun isAvailable(): Boolean

    /** Inicialización del ejecutor(Conectar servicios, consultar autorización, etc.). */
    suspend fun initialize() {}

    /** Actuador liberado. */
    suspend fun dispose() {}

    // ── lectura de pantalla ──────────────────────────────────────────────

    /**
     * captura de pantalla,Devuelve una matriz de bytes PNG. Devuelve nulo en caso de error.
     * Implementación de cada capa.: Accesibilidad a través de MediaProjection/Shell a través de captura de pantalla/Root Igual que Shell.
     */
    suspend fun screenshot(): ByteArray?

    /**
     * Leer el resumen del árbol de control de la pantalla actual(texto/Descripción/ubicación),Para que la IA determine el objetivo del clic.
     * La capa sin barreras más completa;Shell/Root La información obtenida a través de la actividad de dumpsys es limitada.
     */
    suspend fun readScreen(): ScreenInfo

    /** Obtenga el nombre del paquete de aplicación en primer plano actual. */
    suspend fun currentPackage(): String?

    // ── acción de entrada ──────────────────────────────────────────────

    /** Haga clic en las coordenadas de la pantalla. */
    suspend fun tap(x: Int, y: Int): Boolean

    /** Mantenga presionadas las coordenadas de la pantalla. */
    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean

    /** de (x1,y1) deslizar a (x2,y2). */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean

    /**
     * v2.x: Pellizcar para hacer zoom(pinch) — dos dedos (centerX, centerY) Abrir y cerrar simétricamente respecto al centro.
     *
     * @param startDistance Distancia inicial entre dos dedos.(Píxel)
     * @param endDistance Terminar la distancia entre dos dedos.(Píxel);grande = Acercar,pequeño = alejar
     * No compatible de forma predeterminada(Solo implementación de capa de accesibilidad;Shell/Root El comando de entrada no tiene capacidad de inyección con varios dedos.).
     */
    suspend fun pinch(centerX: Int, centerY: Int, startDistance: Int, endDistance: Int, durationMs: Long = 300): Boolean = false

    /**
     * v2.x: Deslizamiento multisegmento — Mueva un dedo a través de múltiples puntos de referencia en secuencia(patrón de desbloqueo/Arrastrar y soltar complejo).
     * La implementación predeterminada se degrada al primer punto → Deslizamiento de segmento único de punto final;La capa de accesibilidad se implementa precisamente punto por punto.
     */
    suspend fun swipePath(points: List<Pair<Int, Int>>, durationMs: Long = 400): Boolean {
        if (points.size < 2) return false
        val first = points.first()
        val last = points.last()
        return swipe(first.first, first.second, last.first, last.second, durationMs)
    }

    /** Introducir texto(Escribir en el cuadro de entrada actualmente enfocado). */
    suspend fun inputText(text: String): Boolean

    /** Enviar evento clave(KEYCODE_*). */
    suspend fun pressKey(keyCode: Int): Boolean

    // ── Acción del sistema ──────────────────────────────────────────────

    /** Inicie la aplicación con el nombre del paquete especificado. */
    suspend fun launchApp(packageName: String): Boolean

    /** Presione la tecla de retorno. */
    suspend fun back(): Boolean = pressKey(4) // KEYCODE_BACK

    /** Presione el botón Inicio. */
    suspend fun home(): Boolean = pressKey(3) // KEYCODE_HOME

    /** Abra la pantalla de notificaciones. */
    suspend fun openNotifications(): Boolean

    /** Abra la configuración rápida. */
    suspend fun openQuickSettings(): Boolean
}
