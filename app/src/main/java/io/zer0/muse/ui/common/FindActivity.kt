package io.zer0.muse.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * v2.6.0: Busca la Actividad en la cadena Contexto; vuelve a la actividad actual rastreada en el nivel de la aplicación si falla el seguimiento.
 *
 * Compose El LocalContext puede estar envuelto por ContextThemeWrapper, etc. (especialmente si la página está incrustada en
 * contenedor de tema especial), directamente `context as? Activity` fallará. aqui va primero baseContext
 * Vuelve a subir por la cadena; Úsalo cuando aún no puedas conseguirlo. [CurrentActivityTracker] La actual actividad reanudada de
 * Resuelva el problema de "no se puede obtener el contexto de actividad" en el dispositivo real, lo que provoca que falle el flujo de código de autenticación de OAuth.
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return CurrentActivityTracker.current()
}
