package io.zer0.muse.automation.executors

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import io.zer0.common.Logger
import io.zer0.muse.accessibility.AccessibilityPathUtils
import io.zer0.muse.automation.core.AutomationExecutor
import io.zer0.muse.automation.core.PermissionLevel
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Actuador accesible —— Primera capa de automatización de la interfaz de usuario.
 *
 * Pase [MuseAccessibilityService] Lea el árbol de control + despachoGesture para inyectar gestos.
 * Este es el nivel más bajo entre los tres pisos.(Los usuarios solo necesitan activar el interruptor de accesibilidad en la configuración del sistema.).
 *
 * limitaciones:
 * - Los controles en Canvas/WebView personalizados no se pueden leer(En este momento, la capa superior se degradará a captura de pantalla.+modelo visual)
 * - Los gestos son a nivel de coordenadas.,Requiere enlace de llamada [ScreenInfo] Calcular las coordenadas
 */
class AccessibilityExecutor(
    private val context: Context,
) : AutomationExecutor {

    override val level = PermissionLevel.ACCESSIBILITY

    private val service: MuseAccessibilityService? get() = MuseAccessibilityService.instance

    override suspend fun isAvailable(): Boolean = service?.isAccessibilityServiceEnabled() == true

    /**
     * v2.7: Sondas de disponibilidad síncronas (sin colgar)—— Para inspección previa de herramientas y otras necesidades“estado real actual”uso de la escena.
     *
     * La disponibilidad accesible consiste simplemente en leer un campo @Volatile sin suspenderlo; después de separar la versión sincronizada,
     * La verificación previa de la herramienta se puede llamar directamente en rutas que no sean de rutina y no depende de instantáneas almacenadas en caché al inicio.
     */
    fun isAvailableSync(): Boolean = service?.isAccessibilityServiceEnabled() == true

    // ── lectura de pantalla ──────────────────────────────────────────────

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Screenshot is a best-effort platform boundary.
    override suspend fun screenshot(): ByteArray? {
        // Android 14+ AccessibilityService.takeScreenshot no requiere MediaProjection,Shizuku O raíz.
        // Las versiones inferiores aún regresan null,Continúe probando Shell/Root mediante AutomationManager.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val svc = service ?: return null
        return withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "a11y-shot-${System.nanoTime()}.jpg")
            try {
                if (!svc.takeScreenshot(file.absolutePath, "JPEG")) return@withContext null
                file.takeIf { it.isFile }?.readBytes()
            } catch (error: Exception) {
                Logger.w(TAG, "accessibility screenshot failed: ${error.message}")
                null
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    override suspend fun readScreen(): ScreenInfo {
        val svc = service ?: return ScreenInfo(source = "accessibility(unavailable)")
        val root = svc.rootInActiveWindow ?: return ScreenInfo(
            packageName = svc.rootInActiveWindow?.packageName?.toString(),
            source = "accessibility(no-root)",
        )
        val nodes = mutableListOf<UiNode>()
        val metrics = getScreenMetrics()
        val pkg = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.firstOrNull()?.taskInfo?.topActivity?.packageName?.toString()
                ?: svc.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            svc.rootInActiveWindow?.packageName?.toString()
        }
        try {
            // B-33: collectNodes El árbol de control puede ser reciclado por el sistema durante el recorrido.(AbandonedNodeException),
            // Después de la captura, conserve algunos nodos recopilados y registre registros.,Si no se lanza hacia arriba, toda la lectura falla y falla.
            try {
                collectNodes(root, nodes, depth = 0)
            } catch (e: Exception) {
                Logger.w(TAG, "readScreen collectNodes interrumpir(Devolver resultados parciales): ${e.message}")
            }
            return ScreenInfo(
                packageName = pkg,
                activityName = root.className?.toString(),
                nodes = nodes,
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                source = "accessibility",
            )
        } finally {
            // B-33: root Necesidad de reciclar explícitamente para liberar recursos nativos,Evite pérdidas de memoria.
            recycleNode(root, TAG)
        }
    }

    override suspend fun currentPackage(): String? {
        val svc = service ?: return null
        return try {
            svc.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ── acción de entrada ──────────────────────────────────────────────

    override suspend fun tap(x: Int, y: Int): Boolean = dispatchClick(x, y)

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean = dispatchLongPress(x, y, durationMs)

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = dispatchSwipe(x1, y1, x2, y2, durationMs)

    override suspend fun pinch(centerX: Int, centerY: Int, startDistance: Int, endDistance: Int, durationMs: Long): Boolean =
        dispatchPinch(centerX, centerY, startDistance, endDistance, durationMs)

    override suspend fun swipePath(points: List<Pair<Int, Int>>, durationMs: Long): Boolean = dispatchSwipePath(points, durationMs)

    override suspend fun inputText(text: String): Boolean {
        val svc = service ?: return false
        return try {
            // Strategy 1: directly set text via ACTION_SET_TEXT on focused editable node.
            val root = svc.rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try {
                if (focused != null) {
                    val args = android.os.Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                    val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (ok) return true
                    // Fallback 2: clipboard paste injection when ACTION_SET_TEXT fails.
                    Logger.d(TAG, "ACTION_SET_TEXT failed, falling back to clipboard paste")
                    return pasteText(text)
                } else {
                    return pasteText(text)
                }
            } finally {
                recycleNode(focused, TAG)
                recycleNode(root, TAG)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "inputText failed: ${e.message}")
            false
        }
    }

    override suspend fun pressKey(keyCode: Int): Boolean {
        // La accesibilidad no se puede inyectar directamente KeyEvent,bajar de categoría a Shell
        // Pero la clave de retorno/Home Se puede acceder a las claves a través de acciones globales.
        return when (keyCode) {
            4 -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
            3 -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
            else -> false
        }
    }

    override suspend fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Logger.w(TAG, "launchApp failed: ${e.message}")
            false
        }
    }

    override suspend fun openNotifications(): Boolean =
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true

    override suspend fun openQuickSettings(): Boolean =
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true

    // ── implementación interna ──────────────────────────────────────────────

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<UiNode>, depth: Int) {
        if (depth > MAX_TREE_DEPTH) return
        val text = AccessibilityPathUtils.textForModel(node.text, node.isPassword)
        val description = AccessibilityPathUtils.textForModel(node.contentDescription, node.isPassword)
        val hasUsefulInfo = !text.isNullOrBlank() ||
            !description.isNullOrBlank() ||
            node.isClickable || node.isEditable
        if (hasUsefulInfo) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            out.add(
                UiNode(
                    text = text,
                    contentDescription = description,
                    className = node.className?.toString(),
                    viewIdResourceName = node.viewIdResourceName,
                    boundsLeft = rect.left,
                    boundsTop = rect.top,
                    boundsRight = rect.right,
                    boundsBottom = rect.bottom,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isChecked = if (node.isCheckable) node.isChecked else null,
                    isEnabled = node.isEnabled,
                ),
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    collectNodes(child, out, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    /**
     * B-33: Recicle nodos de forma segura. Los árboles de control de accesibilidad son objetos altamente dinámicos,Los nodos pueden estar atravesando/Reciclado automáticamente por el sistema durante la consulta.
     * (La nueva versión está alojada en el sistema.),Registros de solo errores de reciclaje explícitos,No bloquee las devoluciones comerciales.
     */
    private fun recycleNode(node: AccessibilityNodeInfo?, tag: String) {
        if (node == null) return
        try {
            node.recycle()
        } catch (e: Exception) {
            Logger.d(tag, "node.recycle falló(Puede haber sido reciclado por el sistema.): ${e.message}")
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private suspend fun dispatchClick(x: Int, y: Int): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    private suspend fun dispatchLongPress(x: Int, y: Int, durationMs: Long): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    private suspend fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    /** v2.x: Pellizcar para hacer zoom — Abrir y cerrar los dos dedos simétricamente en dirección horizontal.(Dos mismos gestos stroke,El horario de inicio es el mismo.). */
    private suspend fun dispatchPinch(centerX: Int, centerY: Int, startDistance: Int, endDistance: Int, durationMs: Long): Boolean {
        val svc = service ?: return false
        val startHalf = (startDistance / 2).coerceAtLeast(1).toFloat()
        val endHalf = (endDistance / 2).coerceAtLeast(1).toFloat()
        val cy = centerY.toFloat()
        val cx = centerX.toFloat()
        val left = Path().apply {
            moveTo(cx - startHalf, cy)
            lineTo(cx - endHalf, cy)
        }
        val right = Path().apply {
            moveTo(cx + startHalf, cy)
            lineTo(cx + endHalf, cy)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(right, 0, durationMs))
            .build()
        return dispatchGesture(svc, gesture)
    }

    /** v2.x: Deslizamiento multisegmento — Punto a punto del camino con un dedo lineTo(patrón de desbloqueo/Arrastrar y soltar complejo). */
    private suspend fun dispatchSwipePath(points: List<Pair<Int, Int>>, durationMs: Long): Boolean {
        val svc = service ?: return false
        if (points.size < 2) return false
        val path = Path().apply {
            moveTo(points[0].first.toFloat(), points[0].second.toFloat())
            for (i in 1 until points.size) {
                lineTo(points[i].first.toFloat(), points[i].second.toFloat())
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(svc, gesture)
    }

    private suspend fun dispatchGesture(svc: MuseAccessibilityService, gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
        }

    private fun pasteText(text: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("auto_input", text))
            // Para pegar se requiere el evento clave de entrada de la capa Shell. KEYCODE_PASTE,
            // Aquí solo se escribe en el portapapeles.,Devuelve falso para permitir que la capa superior baje de categoría
            false
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "AccessibilityExec"
        private const val MAX_TREE_DEPTH = 30
    }
}
