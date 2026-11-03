package io.zer0.muse.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.Logger
import java.io.File

/**
 * P2-2: Común a todos los subrepositorios de configuración `muse_settings` DataStore.
 *
 * Debe haber un solo delegado; de lo contrario, aparecerán varias instancias de DataStore en el mismo archivo.
 * Se activa una IllegalStateException al realizar pruebas o escritura simultánea.
 *
 * v2.5.3 (P1-2): Primero haga una copia de seguridad de los archivos dañados y luego reconstrúyalos.
 * El replaceFileCorruptionHandler predeterminado reemplazará directamente el archivo dañado con una preferencia vacía, lo que equivale a borrar silenciosamente todas las configuraciones del usuario.
 * Y el contenido original dañado no se puede recuperar. Aquí, guarde el archivo dañado como `*.corrupt-<Marca de tiempo>.preferences_pb`,
 * Facilita la posterior resolución de problemas y la recuperación manual.
 */
private const val SETTINGS_STORE_NAME = "muse_settings"

/**
 * Haga todo lo posible para realizar una copia de seguridad de los archivos de datos dañados; la falla no afecta la reconstrucción (las excepciones de la copia de seguridad no pueden bloquear el inicio).
 *
 * El controlador no puede obtener la ruta del archivo dañado actual, pero muse_settings solo tiene un nombre de archivo fijo.
 * Por lo tanto, el posicionamiento según la ruta determinada (datastore Tanto los subdirectorios como los directorios raíz se detectan una vez, compatibles con diferentes diseños de versiones de DataStore).
 */
private fun backupCorruptFile(context: Context) {
    val candidates =
        listOf(
            File(context.filesDir, "datastore/$SETTINGS_STORE_NAME.preferences_pb"),
            File(context.filesDir, "$SETTINGS_STORE_NAME.preferences_pb"),
        )
    val source = candidates.firstOrNull { it.exists() }
    if (source == null) {
        Logger.w("MuseSettingsDataStore", "Archivo corrupto no encontrado, copia de seguridad omitida")
        return
    }
    val backup = File(source.parentFile, "$SETTINGS_STORE_NAME.corrupt-${System.currentTimeMillis()}.preferences_pb")
    source.copyTo(backup, overwrite = true)
    Logger.w("MuseSettingsDataStore", "Se han realizado copias de seguridad de los archivos corruptos.: ${backup.absolutePath}")
}

/**
 * v2.5.3: Proporciona a la lógica de copia de seguridad dañada acceso al contexto de la aplicación.
 *
 * Inicializado por MuseApp.onCreate; la copia de seguridad se omite si no se inicializa y no afecta la reconstrucción del DataStore.
 */
internal object SettingsStoreContextHolder {
    @Volatile
    var applicationContext: Context? = null
}

internal val Context.museSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_STORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { ex ->
        Logger.w("MuseSettingsDataStore", "muse_settings Los datos están dañados, intenta hacer una copia de seguridad y reconstruirlos.: ${ex.message}", ex)
        SettingsStoreContextHolder.applicationContext?.let { ctx ->
            runCatching { backupCorruptFile(ctx) }
                .onFailure { Logger.w("MuseSettingsDataStore", "Error en la copia de seguridad de archivos dañados: ${it.message}", it) }
        }
        emptyPreferences()
    },
)
