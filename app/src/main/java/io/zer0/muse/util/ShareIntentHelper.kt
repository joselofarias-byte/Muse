package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger

/**
 * Compartir/Chooser Inicie herramientas de seguridad.
 *
 * fondo del accidente: Compose El LocalContext dentro del contenedor parcial(Como ventana emergente/flotante/Tareas en segundo plano)Lo que obtuve fue
 * Contexto de aplicación en lugar de actividad. Si el contexto que no es de Actividad no se lleva al iniciar la Actividad
 * FLAG_ACTIVITY_NEW_TASK,Android Lanzará AndroidRuntimeException y fallará.
 *
 * [startChooserSafely] Unificado en escenarios sin actividad NEW_TASK,y agregar automáticamente
 * EXTRA_STREAM Permiso de lectura(Para compartir archivos). Todos los portales para compartir deben seguir este método.
 */
object ShareIntentHelper {

    /**
     * Selector de inicio seguro para compartir.
     *
     * @param context arbitrario context(Activity O el contexto de la aplicación puede ser)
     * @param shareIntent configurado ACTION_SEND intent(puede contener EXTRA_STREAM)
     * @param chooserTitle chooser Título(Disponible)
     */
    fun startChooserSafely(context: Context, shareIntent: Intent, chooserTitle: String? = null) {
        runCatching {
            context.startActivity(buildChooserIntent(context, shareIntent, chooserTitle))
        }.onFailure { error ->
            // El portal de uso compartido no puede volver a bloquear la aplicación cuando no hay ninguna aplicación que pueda manejar el uso compartido o falla la autorización de URI.
            Logger.w("ShareIntentHelper", "No se puede empezar a compartir chooser: ${error.message}", error)
        }
    }

    /** Construya un selector con el indicador de inicio correcto para que lo reutilicen los llamadores y las pruebas de regresión de JVM. */
    internal fun buildChooserIntent(context: Context, shareIntent: Intent, chooserTitle: String? = null): Intent {
        // Compartir archivos requiere otorgar permiso de lectura(EXTRA_STREAM escena)
        if (shareIntent.hasExtra(Intent.EXTRA_STREAM)) {
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = if (chooserTitle != null) {
            Intent.createChooser(shareIntent, chooserTitle)
        } else {
            Intent.createChooser(shareIntent, null)
        }
        // El contexto de no actividad debe traer NEW_TASK,De lo contrario, fallará con AndroidRuntimeException
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return chooser
    }
}
