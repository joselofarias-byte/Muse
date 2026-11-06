package io.zer0.muse.ui.common

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * v2.6.0: Seguimiento de actividad a nivel de aplicación.
 *
 * Antecedentes (comentarios de los usuarios:OpenAI Informes de inicio de sesión con un clic "No se puede obtener el contexto de actividad"):
 * Debajo de algunos contenedores de redacción `LocalContext` No apunta a una actividad ni puede ser utilizada por varias capas.
 * ContextWrapper Paquete, es posible que aún no pueda obtenerlo simplemente rastreando el contexto baseContext —— y Auth Code
 * Flow El Intent del navegador debe iniciarse con una Actividad real (startActivity).
 *
 * Por lo tanto, registre la devolución de llamada del ciclo de vida en la Aplicación y recuerde siempre la reanudación actual.
 * Activity Como respaldo:`Context.findActivity()` Devuélvalo cuando falle el rastreo.
 */
object CurrentActivityTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumed: WeakReference<Activity>? = null

    /** La actividad reanudada más recientemente (puede ser nula, como el fondo completo). */
    fun current(): Activity? = resumed?.get()

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
