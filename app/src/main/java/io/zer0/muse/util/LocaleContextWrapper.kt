package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import io.zer0.common.Logger

/**
 * Embalaje de idiomas Context —— Conserve la capacidad de cobertura local mientras garantiza que la actividad no falle cuando se inicie.
 *
 * ## Antecedentes (fallo informado por los usuarios)
 *
 * Para realizar un cambio activo de idioma en la aplicación,MainActivity Se utiliza RuntimeLocaleProvider
 * `applicationContext.createConfigurationContext(config)` Anular Compose
 * `LocalContext`(dejar `stringResource` leer en el idioma de destino). Pero el contexto es
 * **Application context derivado**, no Actividad.
 *
 * Compose El menú de selección de texto (seleccionar texto → Sistema de barra flotante → El elemento "Procesar texto" está implementado en
 * `androidx.compose.foundation.text.contextmenu.ProcessText_androidKt`)
 * de `LocalContext` Tome el contexto directamente `startActivity`,**sin
 * FLAG_ACTIVITY_NEW_TASK** → Android tirar
 * `AndroidRuntimeException: Calling startActivity() from outside of an Activity
 * context requires the FLAG_ACTIVITY_NEW_TASK flag`(Caída medida por los usuarios).
 *
 * ## modificar la ley
 *
 * No cambie la lógica del idioma, simplemente ajuste el contexto local con una capa [ContextWrapper]:
 *  - `base` Sigue siendo el que tiene la configuración regional de destino. context → `getResources()` / `stringResource`
 *    El comportamiento permanece completamente sin cambios;
 *  - reescribir [startActivity] / [startActivity] Dos sobrecargas:**Suplemento de escena sin actividad
 *    FLAG_ACTIVITY_NEW_TASK**, con [ShareIntentHelper] Mismo conjunto de ideas.
 *
 * repararlo de esta manera**todos**"de `LocalContext` empezar Activity"Peligros ocultos (no solo ProcessText),
 * sin sacrificar ninguna funcionalidad——"Procesar texto" aún puede enviar el texto seleccionado a otras aplicaciones normalmente.
 *
 * ¿Por qué no cambiar Redactar directamente? Ese es el código de la biblioteca; y la causa raíz común de problemas similares en el lado de Muse es
 * "LocalContext fue reemplazado por no Activity context", el embalaje en origen es el que menos problemas presenta.
 */
internal class LocaleContextWrapper(
    base: Context,
) : ContextWrapper(base) {

    /**
     * Las escenas sin actividad se inician después de agregar NEW_TASK.
     *
     * Activity escena (wrapper Al envolver una actividad), manténgala como está para evitar cambiar la semántica de salto existente.
     */
    override fun startActivity(intent: Intent) {
        super.startActivity(withNewTaskIfNeeded(intent))
    }

    /** Android 16+ La sobrecarga de opciones también requiere agregar una bandera. */
    override fun startActivity(intent: Intent, options: Bundle?) {
        super.startActivity(withNewTaskIfNeeded(intent), options)
    }

    private fun withNewTaskIfNeeded(intent: Intent): Intent = when {
        // wrapper Nunca puede ser una Actividad en sí misma (hereda ContextWrapper);
        // Sólo determine si la base envuelta está activa.
        baseContext is Activity -> intent
        intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 -> intent
        else -> Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        private const val TAG = "LocaleContextWrapper"

        /**
         * Envuelva un contexto local para que sea seguro iniciar la actividad.
         *
         * Idempotente: los datos empaquetados se devuelven directamente para evitar anidamientos repetidos.
         */
        fun wrap(base: Context): Context {
            if (base is LocaleContextWrapper) return base
            Logger.d(TAG, "LocaleContextWrapper Aplicado (NEW_TASK se agrega al iniciar una actividad en un contexto que no es de actividad)")
            return LocaleContextWrapper(base)
        }
    }
}
