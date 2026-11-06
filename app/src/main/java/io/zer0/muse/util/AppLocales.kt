package io.zer0.muse.util

import java.util.Locale

/**
 * Cadena de preferencia de idioma → [Locale](con `values-*` directorio de recursos correspondiente).
 *
 * "system" O devolver nulo para un valor desconocido, lo que significa seguir el idioma predeterminado del sistema.
 *
 * Este mapeo era originalmente privado en MainActivity (`attachBaseContext` Envolviendo recursos base de actividades,
 * Compose de `RuntimeLocaleProvider` Utilice cada interruptor caliente una vez).Host de `settings.snapshot`
 * La copia también debe analizarse según el idioma actual (la página web ya no mantiene la tabla de copias), por lo que se recibe en util,
 * Deje que el arranque en frío, la conmutación en caliente en tiempo de ejecución y el texto localizado emitido por el protocolo lean el mismo valor de idioma.
 */
object AppLocales {

    @Suppress("DEPRECATION") // Locale(language, country) Marcar como obsoleto en el nuevo JDK (se recomienda Locale.of),
    // Pero Locale.of no está disponible en el proyecto minSdk, por lo que el constructor se utiliza intencionalmente.
    fun parse(language: String): Locale? = when (language) {
        "en" -> Locale.US
        "ja" -> Locale.JAPAN
        "ko" -> Locale.KOREA
        "ru" -> Locale("ru", "RU")
        // zh queda como migración: instalaciones que guardaron chino pasan a español.
        "es", "zh", "zh-TW" -> Locale("es", "UY")
        "pt-rBR" -> Locale("pt", "BR")
        else -> null
    }
}
