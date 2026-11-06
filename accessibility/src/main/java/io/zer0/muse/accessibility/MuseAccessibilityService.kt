package io.zer0.muse.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.zer0.common.Logger
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P3-3: Muse Servicios de accesibilidad — UI Base de capacidad de automatización.
 *
 * Responsabilidades:
 *  1. como [AccessibilityService] Recibir eventos de accesibilidad del sistema,Registrar la recepción actual Activity
 *  2. Pase [companion object] instancia estática para [io.zer0.muse.tools.system.AccessibilityClient] Exponer las capacidades de operación de la interfaz de usuario:
 *     - Leer la jerarquía de la interfaz de usuario([getUiHierarchy])
 *     - Haga clic/Pulsación larga/gesto de deslizar([performClick]/[performLongPress]/[performSwipe])
 *     - acción global([execGlobalAction]:Regresar/HOME/Tareas recientes, etc.)
 *     - Entrada de texto del nodo([setTextOnNode])
 *     - captura de pantalla([takeScreenshot],API 34+)
 *     - Consulta de actividad actual([getCurrentActivityName])
 *
 * descripción del diseño(¿Por qué no usar? AIDL bindService):
 *  - [AccessibilityService] Ciclo de vida gestionado por el sistema.,onBind Ya ocupado por el Marco para la vinculación de accesibilidad.
 *  - Kotlin No está permitido heredar tanto AccessibilityService como IAccessibilityProvider.Stub(ambas son clases)
 *  - Dentro del mismo APK(mismo proceso)Es más eficiente llamar directamente con una instancia estática,Evite el conflicto de bindService con el enlace del sistema
 *  - AIDL interfaz [IAccessibilityProvider] Se mantiene como contrato de método + base para futuras extensiones entre procesos
 *
 * Identificador de ruta de nodo:
 *  - getUiHierarchy Asignar una ruta a cada nodo "ruta principal.subíndice",como raíz="0",raíz de2nodo hijo="0.1"
 *  - findFocusedNodeId Devuelve la ruta del nodo de enfoque.
 *  - setTextOnNode Recorre el árbol para localizar nodos por ruta.
 *
 * seguro:
 *  - El manifiesto principal de la aplicación comienza con exported=true Regístrese para el servicio,Permitir que el sistema Android se vincule;
 *    BIND_ACCESSIBILITY_SERVICE Las aplicaciones normales todavía no pueden vincularse directamente.
 *  - El sistema requiere el permiso BIND_ACCESSIBILITY_SERVICE para vincularse
 *  - Todas las operaciones solo responden a llamadas activas del cliente.,Sin monitoreo de fondo(El evento solo se utiliza para actualizar el nombre de la actividad actual.)
 */
open class MuseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MuseA11yService"

        /** UI Número máximo de nodos para recorrido de nivel(Evitar que árboles muy grandes pesen en el contexto LLM). */
        private const val MAX_NODES = 500

        /** Tiempo de espera de devolución de llamada de gesto(milisegundos). */
        private const val GESTURE_TIMEOUT_MS = 2000L

        /** Tiempo de espera de devolución de llamada de captura de pantalla(milisegundos). */
        private const val SCREENSHOT_TIMEOUT_MS = 3000L

        /**
         * Instancia de servicio actual(onServiceConnected configuración de publicaciones,onDestroy Borrar).
         * AccessibilityClient Llame directamente al método de servicio a través de esta referencia estática(mismo proceso,No es necesario bindService).
         */
        @Volatile
        var instance: MuseAccessibilityService? = null

        /**
         * v2.7: conexión de servicio/Desconecte el oyente (registro de host, utilizado para actualizar el estado del permiso a tiempo).
         *
         * Los servicios de accesibilidad son**conexión asíncrona**de: Después de que el usuario enciende el interruptor en la configuración del sistema,
         * `onServiceConnected` Simplemente ejecuta. Si el host solo detecta permisos una vez cuando se inicia la aplicación,
         * El caché se detendrá en falso al inicio, lo que provocará errores de evaluación previa de la herramienta.“No autorizado”.
         * Aquí se proporcionan devoluciones de llamadas del ciclo de vida del servicio para permitir que el host (como AutomationManager)
         * en conexión/Actualice proactivamente las instantáneas de permisos cuando esté desconectado.
         *
         * Se vuelve a llamar al oyente dentro del proceso de servicio; el host debe registrarse durante el ciclo de vida de la aplicación y eliminarse al salir.
         */
        private val connectionListeners =
            java.util.concurrent.CopyOnWriteArrayList<(connected: Boolean) -> Unit>()

        /** Monitoreo del estado de la conexión de registros (idempotente: se deduplicarán los registros repetidos con la misma referencia). */
        fun addConnectionListener(listener: (connected: Boolean) -> Unit) {
            connectionListeners.addIfAbsent(listener)
        }

        /** Eliminar la supervisión del estado de la conexión. */
        fun removeConnectionListener(listener: (connected: Boolean) -> Unit) {
            connectionListeners.remove(listener)
        }

        /** Notificar a todos los oyentes (llamados por el ciclo de vida del servicio). */
        internal fun notifyConnectionChanged(connected: Boolean) {
            connectionListeners.forEach { listener ->
                runCatching { listener(connected) }
                    .onFailure { Logger.w(TAG, "Error en la devolución de llamada de escucha de conexión: ${it.message}") }
            }
        }
    }

    /** El nombre del paquete del evento de cambio de estado de ventana más reciente./nombre de clase(usado para getCurrentActivityName). */
    @Volatile private var lastPackage: String = ""

    @Volatile private var lastClassName: String = ""

    /** ¿Está conectado el servicio?(onServiceConnected trasero true). */
    @Volatile private var connected = false

    // ── AccessibilityService ciclo de vida ──────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        instance = this
        Logger.i(TAG, "Los servicios de accesibilidad están conectados")
        notifyConnectionChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Registrar únicamente la información actual de la actividad en primer plano,Ningún otro procesamiento(Sin supervisión en segundo plano del comportamiento del usuario)
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (!pkg.isNullOrBlank()) {
                lastPackage = pkg
                lastClassName = cls ?: ""
            }
        }
    }

    override fun onInterrupt() {
        Logger.w(TAG, "El servicio de accesibilidad fue interrumpido por el sistema.")
    }

    override fun onDestroy() {
        connected = false
        instance = null
        Logger.i(TAG, "Servicios de accesibilidad destruidos")
        notifyConnectionChanged(false)
        super.onDestroy()
    }

    // ── IAccessibilityProvider método de contrato(Implementación directa,No heredado Stub) ─────────────────

    fun isAccessibilityServiceEnabled(): Boolean = connected

    /**
     * Si la capacidad de captura de pantalla está disponible.
     *
     * v2.6.13: En lugar de leer el bit de capacidad realmente informado por el sistema, ya no confíe en“Si la reflexión falló”Hacer retroceder.
     *
     * La implementación anterior utiliza la reflexión para escribir el campo mCanTakeScreenshots en AccessibilityServiceInfo.
     * Pero ese campo no existe en AOSP en absoluto (registro de máquina real:`No field mCanTakeScreenshots in class
     * Landroid/accessibilityservice/AccessibilityServiceInfo;`). La capacidad de tomar capturas de pantalla en realidad la proporciona
     * `android:canTakeScreenshot` Declaración de atributo (singular), escrita en CAPABILITY_CAN_TAKE_SCREENSHOT después del análisis del sistema.
     * Entonces, aquí le pide directamente al sistema que obtenga el resultado y lo maneja como disponible si es desconocido (una llamada verdadera devolverá un código de error específico).
     */
    fun canTakeScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val info = runCatching { serviceInfo }.getOrNull() ?: return false
        val capable = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        if (!capable) {
            Logger.w(TAG, "El servicio no obtuvo la capacidad de tomar capturas de pantalla.(capabilities=${info.capabilities});Compruebe si se declara XML android:canTakeScreenshot")
        }
        return capable
    }

    fun getCurrentActivityName(): String {
        if (lastPackage.isBlank()) return ""
        return if (lastClassName.isNotBlank()) "$lastPackage/$lastClassName" else lastPackage
    }

    fun getUiHierarchy(): String {
        val root = rootInActiveWindow ?: return "[error] Ninguna ventana activa(Los servicios de accesibilidad no están autorizados o no hay ventana frontal.)"
        val sb = StringBuilder()
        try {
            sb.append("[activity] ").append(getCurrentActivityName().ifBlank { "unknown" }).append('\n')
            val counter = intArrayOf(0)
            dumpNode(root, "0", sb, counter)
            return if (counter[0] >= MAX_NODES) {
                sb.append("\n[truncated] Número máximo de nodos alcanzado $MAX_NODES")
                sb.toString()
            } else {
                sb.toString()
            }
        } finally {
            root.recycle()
        }
    }

    fun performClick(x: Int, y: Int): Boolean = dispatchClick(x, y, longPress = false)

    fun performLongPress(x: Int, y: Int): Boolean = dispatchClick(x, y, longPress = true)

    /**
     * Realizar acciones de accesibilidad global(Regresar/HOME/Tareas recientes, etc.).
     * Nota:Nombrado execGlobalAction en lugar de performGlobalAction,porque
     * AccessibilityService.performGlobalAction si final,no puedo override;
     * Este método llama directamente al método final de la clase principal.
     */
    fun execGlobalAction(actionId: Int): Boolean {
        val ok = performGlobalAction(actionId)
        Logger.d(TAG, "execGlobalAction($actionId) -> $ok")
        return ok
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration.coerceAtLeast(50L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return awaitGesture(gesture)
    }

    fun findFocusedNodeId(): String {
        val root = rootInActiveWindow ?: return ""
        try {
            val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return ""
            try {
                return findNodePath(root, focus) ?: ""
            } finally {
                focus.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun setTextOnNode(nodeId: String, text: String): Boolean {
        if (nodeId.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val target = findNodeByPath(root, nodeId) ?: return false
            try {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Logger.d(TAG, "setTextOnNode($nodeId) -> $ok")
                return ok
            } finally {
                target.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    fun takeScreenshot(path: String, format: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "takeScreenshot must not run on main thread" }
        // AccessibilityService.takeScreenshot() necesidad API 34+,La versión inferior no es compatible
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Logger.w(TAG, "takeScreenshot necesidad Android 14+(API 34),actual API ${Build.VERSION.SDK_INT}")
            return false
        }
        if (path.isBlank()) return false
        val latch = CountDownLatch(1)
        var result = false
        takeScreenshot(
            DEFAULT_DISPLAY,
            Runnable::run,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    result = saveScreenshot(screenshot, path, format)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Logger.w(TAG, "takeScreenshot falló errorCode=$errorCode")
                    latch.countDown()
                }
            },
        )
        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Logger.w(TAG, "takeScreenshot tiempo de espera")
            return false
        }
        return result
    }

    // ── implementación interna ────────────────────────────────────────────────────────────────

    /** Enviar información de nodo de forma recursiva a [sb], [path] es la ruta del nodo actual(Como por ejemplo "0.1.2"). */
    private fun dumpNode(node: AccessibilityNodeInfo, path: String, sb: StringBuilder, counter: IntArray) {
        if (counter[0] >= MAX_NODES) return
        counter[0]++
        sb.append('[').append(path).append("] ")
        sb.append("class=").append(node.className ?: "")
        val text = AccessibilityPathUtils.textForModel(node.text, node.isPassword)
        if (!text.isNullOrBlank()) sb.append(" text=").append(AccessibilityPathUtils.escapeText(text))
        val desc = AccessibilityPathUtils.textForModel(node.contentDescription, node.isPassword)
        if (!desc.isNullOrBlank()) sb.append(" desc=").append(AccessibilityPathUtils.escapeText(desc))
        val r = node.rect
        sb.append(" bounds=[").append(r.left).append(',').append(r.top)
            .append("][").append(r.right).append(',').append(r.bottom).append(']')
        if (node.isClickable) sb.append(" clickable=true")
        if (node.isEditable) sb.append(" editable=true")
        if (node.isFocused) sb.append(" focused=true")
        sb.append('\n')
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (counter[0] >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            dumpNode(child, "$path.$i", sb, counter)
            child.recycle()
        }
    }

    /** Escapar de nuevas líneas en el texto/carácter de tabulación,Salida de línea única garantizada. */

    private val AccessibilityNodeInfo.rect: android.graphics.Rect
        get() = android.graphics.Rect().also { getBoundsInScreen(it) }

    /** Haga clic/Distribuido mediante un gesto de pulsación larga. */
    private fun dispatchClick(x: Int, y: Int, longPress: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (longPress) 500L else 50L
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = awaitGesture(gesture)
        Logger.d(TAG, "dispatchClick($x,$y,longPress=$longPress) -> $ok")
        return ok
    }

    /** Esperando sincrónicamente los resultados de la distribución de gestos(CountDownLatch + tiempo de espera). */
    private fun awaitGesture(gesture: GestureDescription): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "awaitGesture must not run on main thread" }
        val latch = CountDownLatch(1)
        var result = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    result = true
                    latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        return latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result
    }

    /**
     * en [root] Buscar en subárbol [target] El identificador de ruta del nodo.
     * @return cadena de ruta(Como por ejemplo "0.1.2");Devolución no encontrada null
     */
    private fun findNodePath(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): String? {
        val targetHash = System.identityHashCode(target)
        return findNodePathRec(root, "0", targetHash)
    }

    private fun findNodePathRec(node: AccessibilityNodeInfo, path: String, targetHash: Int): String? {
        if (System.identityHashCode(node) == targetHash) return path
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                findNodePathRec(child, "$path.$i", targetHash)
            } finally {
                child.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    /** Identificar por ruta(Como por ejemplo "0.1.2")de [root] Atraviese el nodo de posicionamiento. */
    private fun findNodeByPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        val indices = AccessibilityPathUtils.parseNodePath(path)
        if (indices.isEmpty()) return null
        var current: AccessibilityNodeInfo = root
        for (i in 1 until indices.size) {
            val idx = indices[i]
            val child = current.getChild(idx) ?: return null
            if (i > 1) current.recycle()
            current = child
        }
        return current
    }

    /** voluntad [ScreenshotResult] Guarde el mapa de bits en un archivo.ScreenshotResult El hardwareBuffer es API 30+. */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.R)
    private fun saveScreenshot(screenshot: ScreenshotResult, path: String, format: String): Boolean {
        val hardwareBuffer = screenshot.hardwareBuffer ?: return false
        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null) ?: run {
            hardwareBuffer.close()
            return false
        }
        return try {
            val fmt = if (format.equals("JPEG", ignoreCase = true)) {
                Bitmap.CompressFormat.JPEG
            } else {
                Bitmap.CompressFormat.PNG
            }
            FileOutputStream(path).use { fos -> bitmap.compress(fmt, 90, fos) }
            true
        } catch (e: Exception) {
            // Tolerancia a fallos necesaria:El archivo IO puede fallar(disco lleno/Permisos),Registrar en lugar de tragar excepciones
            Logger.e(TAG, "saveScreenshot falló: ${e.message}", e)
            false
        } finally {
            hardwareBuffer.close()
        }
    }
}

/** Pantalla predeterminada ID(main display = 0). */
private const val DEFAULT_DISPLAY = 0
