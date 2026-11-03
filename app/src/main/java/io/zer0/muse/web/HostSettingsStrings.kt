package io.zer0.muse.web

import android.content.Context
import android.content.res.Configuration
import io.zer0.common.Logger
import io.zer0.muse.util.AppLocales
import java.util.concurrent.ConcurrentHashMap

/**
 * Establecer análisis de redacción publicitaria: nombre del recurso de cadena → **localizado**texto.
 *
 * en el acuerdo `titleKey` / `descKey` / grupo `key` Es el nombre del recurso y solo se utiliza para identificación estable (navegación, afirmaciones de prueba);
 * El texto que se mostrará en la interfaz se analiza aquí de acuerdo con el idioma actual de la aplicación y luego `settings.snapshot` Envíalos juntos,
 * Por lo tanto, la página web no necesita (ni debe) mantener su propia tabla de copias.
 *
 * Si no se puede analizar, se devuelve nulo y depende de la persona que llama decidir qué hacer (déjelo en blanco en la instantánea en lugar de mostrar el nombre del recurso al usuario).
 */
fun interface HostSettingsStrings {
    fun resolve(resourceName: String, language: String): String?
}

/**
 * [HostSettingsStrings] Implementación de Android.
 *
 * El valor del idioma se toma de `SettingsRepository.languageFlow`("zh" / "en" / … / "system"),
 * con `MainActivity.attachBaseContext` Los recursos básicos de la actividad del paquete utilizan la misma asignación
 * ([AppLocales]): cree solo un nuevo ConfigurationContext de acuerdo con la configuración regional de destino,
 * Sólo entonces podrá `resources.getString` realmente ve `values-ja` ese tipo de directorio——`applicationContext`
 * Siempre es la configuración del sistema y el idioma de la aplicación no se puede leer directamente.
 */
internal class AndroidHostSettingsStrings(private val baseContext: Context) : HostSettingsStrings {

    /** language → Contexto para este idioma (solo crece con el número de idiomas). */
    private val localizedContexts = ConcurrentHashMap<String, Context>()

    /** "language|resourceName" → El texto ha sido analizado; los nombres perdidos no se ingresarán en el caché y los recursos modificados entrarán en vigencia de inmediato. */
    private val resolved = ConcurrentHashMap<String, String>()

    override fun resolve(resourceName: String, language: String): String? {
        if (resourceName.isBlank()) return null
        val cacheKey = "$language|$resourceName"
        return resolved[cacheKey] ?: lookup(cacheKey, resourceName, language)
    }

    /** Análisis verdadero: si acierta, se volverá a escribir en el caché; si falla, no se almacenará en caché y el recurso modificado entrará en vigor de inmediato. */
    private fun lookup(cacheKey: String, resourceName: String, language: String): String? {
        val context = localizedContext(language)
        val id = context.resources.getIdentifier(resourceName, RESOURCE_TYPE, context.packageName)
        if (id == 0) {
            Logger.w(TAG, "Faltan recursos de redacción de configuración: $resourceName (language=$language)")
            return null
        }
        val text = context.getString(id).takeIf { it.isNotBlank() } ?: return null
        resolved[cacheKey] = text
        return text
    }

    private fun localizedContext(language: String): Context = localizedContexts.getOrPut(language) {
        // "system" / valor desconocido → Siga el sistema: utilice directamente el contexto proporcionado por la persona que llama.
        val locale = AppLocales.parse(language) ?: return@getOrPut baseContext
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        baseContext.createConfigurationContext(configuration)
    }

    private companion object {
        const val TAG = "HostSettingsStrings"
        const val RESOURCE_TYPE = "string"
    }
}
