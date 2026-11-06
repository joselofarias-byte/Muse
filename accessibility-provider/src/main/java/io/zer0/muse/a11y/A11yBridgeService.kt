package io.zer0.muse.a11y

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.zer0.muse.accessibility.IAccessibilityProvider
import io.zer0.muse.accessibility.MuseAccessibilityService

/**
 * v2.2.1: AIDL Servicio de puente.
 *
 * Pon el mismo proceso [MuseAccessibilityService.instance](Ejemplos de servicios de accesibilidad)Pase
 * [IAccessibilityProvider] Expuesto a la aplicación principal de Muse con la misma firma.:
 *  - manifest Con permisos a nivel de firma [io.zer0.muse.permission.A11Y_BRIDGE] barandilla;
 *  - Cuando los servicios de accesibilidad no están conectados,La clase de consulta devuelve un valor nulo/No habilitado,Devoluciones de clase de operación false(Misma semántica que la implementación integrada de la aplicación principal.).
 *
 * Descripción del mapeo del método:AIDL performGlobalAction corresponde a la clase base execGlobalAction
 * (Anotación de clase base:AccessibilityService.performGlobalAction para final,No se puede sobrescribir).
 */
class A11yBridgeService : Service() {
    private val binder =
        object : IAccessibilityProvider.Stub() {
            override fun getUiHierarchy(): String = service()?.getUiHierarchy() ?: ""

            override fun performClick(x: Int, y: Int): Boolean = service()?.performClick(x, y) ?: false

            override fun performLongPress(x: Int, y: Int): Boolean = service()?.performLongPress(x, y) ?: false

            override fun performGlobalAction(actionId: Int): Boolean = service()?.execGlobalAction(actionId) ?: false

            override fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean =
                service()?.performSwipe(startX, startY, endX, endY, duration) ?: false

            override fun findFocusedNodeId(): String = service()?.findFocusedNodeId() ?: ""

            override fun setTextOnNode(nodeId: String, text: String): Boolean = service()?.setTextOnNode(nodeId, text) ?: false

            override fun takeScreenshot(path: String, format: String): Boolean = service()?.takeScreenshot(path, format) ?: false

            override fun isAccessibilityServiceEnabled(): Boolean = service() != null

            override fun getCurrentActivityName(): String = service()?.getCurrentActivityName() ?: ""
        }

    private fun service(): MuseAccessibilityService? = MuseAccessibilityService.instance

    override fun onBind(intent: Intent?): IBinder = binder
}
