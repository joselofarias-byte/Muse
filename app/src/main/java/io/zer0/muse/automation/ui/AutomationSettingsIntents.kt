package io.zer0.muse.automation.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger

/**
 * Inicie el sistema para establecer el límite mínimo de seguridad para la entrada.
 *
 * Algunos sistemas de proveedores no implementan Settings action,Incluso si la intención se construye con éxito,
 * startActivity Se seguirá lanzando ActivityNotFoundException. Verifique y detecte las excepciones de inicio una por una,
 * Asegúrese de que al hacer clic en la página de permisos no se bloquee toda la actividad de redacción.
 */
internal fun launchFirstResolvable(candidates: List<Intent>, canResolve: (Intent) -> Boolean, startActivity: (Intent) -> Unit): Boolean {
    candidates.forEach { candidate ->
        if (!runCatching { canResolve(candidate) }.getOrDefault(false)) return@forEach
        try {
            startActivity(candidate)
            return true
        } catch (error: ActivityNotFoundException) {
            Logger.w("AutomationSettings", "La entrada de configuración del sistema no está disponible: ${candidate.action}", error)
        } catch (error: SecurityException) {
            Logger.w("AutomationSettings", "La entrada de configuración del sistema no tiene permiso: ${candidate.action}", error)
        }
    }
    return false
}

/** Al iniciar la configuración desde un contexto que no es de actividad,Agregue NEW_TASK a la intención del candidato final. */
internal fun openAutomationSettings(context: Context, candidates: List<Intent>, onUnavailable: () -> Unit = {}): Boolean {
    val started = launchFirstResolvable(
        candidates = candidates.map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
        canResolve = { intent -> intent.resolveActivity(context.packageManager) != null },
        startActivity = context::startActivity,
    )
    if (!started) onUnavailable()
    return started
}
