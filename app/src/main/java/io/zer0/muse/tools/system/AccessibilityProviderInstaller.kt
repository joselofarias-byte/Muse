package io.zer0.muse.tools.system

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import java.io.File

/**
 * P3-3: Instalador de servicios de accesibilidad(arranque habilitado).
 *
 * Situación actual(v2.2.1):
 *  - Los servicios dentro de la aplicación se han compilado en la página principal. APK,La esencia de la "instalación" es guiar al usuario a la configuración del sistema para habilitar;
 *  - También accesible e independiente. Provider APK(io.zer0.muse.a11y),Después de la instalación, sus elementos de autorización son independientes del ciclo de vida de la aplicación principal.
 *
 * Responsabilidades:
 *  1. [isInstalled]: Servicio en la aplicación listo(library módulo,hengwei true)
 *  2. [isProviderInstalled]: ¿Está instalado el APK del proveedor independiente?(Los servicios dentro de la aplicación todavía están disponibles cuando no están instalados)
 *  3. [isEnabled]: Si el servicio se ha habilitado en la configuración del sistema(confiar [AccessibilityClient.isEnabled],Cubre dos registros de autorización.)
 *  4. [openSettings]: Ir a la página de configuración de accesibilidad del sistema
 *  5. [ensureEnabled]: Comprobar y habilitar el arranque,Devuelve el estado actual para la visualización de la interfaz de usuario
 */
class AccessibilityProviderInstaller(private val context: Context) {
    private val client = AccessibilityClient(context)

    companion object {
        private const val TAG = "A11yInstaller"

        /** La ruta del APK del proveedor integrado en los activos(app Copiado por syncA11yProviderApk al compilar). */
        private const val PROVIDER_ASSET_PATH = "a11y/accessibility-provider.apk"
    }

    /** El módulo de servicio está compilado en APK,No se requiere instalación por separado. */
    fun isInstalled(): Boolean = true

    /** v2.2.1: Si está instalado el APK del proveedor independiente. */
    fun isProviderInstalled(): Boolean = client.isProviderInstalled()

    /** v2.2.1: ¿El APK del proveedor independiente viene con el paquete?(assets existir en). */
    fun isProviderApkBundled(): Boolean = runCatching {
        context.assets.open(PROVIDER_ASSET_PATH).close()
        true
    }.getOrDefault(false)

    /**
     * v2.2.1: Extraiga el APK del proveedor integrado y abra el instalador del sistema.
     *
     * El instalador es presentado por el sistema.,Instalación completa después de la confirmación del usuario.(Requiere declaración manifiesta REQUEST_INSTALL_PACKAGES).
     * @return true Indica que el instalador se ha iniciado correctamente;false Indica que el APK falta o es rechazado por el sistema
     */
    fun installProvider(): Boolean {
        return try {
            val apkFile = File(context.cacheDir, "a11y/accessibility-provider.apk")
            apkFile.parentFile?.mkdirs()
            context.assets.open(PROVIDER_ASSET_PATH).use { input ->
                apkFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Tolerancia a fallos necesaria:assets desaparecido/FileProvider No configurado/El sistema se niega a detenerse.,Se requiere retroalimentación para UI
            Logger.e(TAG, "No se pudo abrir el instalador del proveedor: ${e.message}", e)
            false
        }
    }

    /** Si los servicios de accesibilidad están habilitados. */
    fun isEnabled(): Boolean = client.isEnabled()

    /**
     * Guiar a los usuarios para habilitar los servicios de accesibilidad.(Abra la página de configuración del sistema).
     * @return true Indica que ha sido redirigido;false Indica que la página de configuración de accesibilidad no está disponible
     */
    fun openSettings(): Boolean {
        return try {
            client.openAccessibilitySettings()
            true
        } catch (e: Exception) {
            // Tolerancia a fallos necesaria:Es posible que algunas ROM personalizadas no tengan una página de configuración de accesibilidad estándar
            Logger.e(TAG, "No se puede abrir la configuración de accesibilidad: ${e.message}", e)
            false
        }
    }

    /**
     * Asegúrese de que los servicios de accesibilidad estén habilitados;Regrese al indicador de inicio cuando no esté habilitado.
     * @return [EnsureResult] Contiene información de estado y avisos.
     */
    fun ensureEnabled(): EnsureResult {
        return if (isEnabled()) {
            EnsureResult.Enabled
        } else {
            EnsureResult.NeedsEnable
        }
    }

    sealed class EnsureResult {
        /** El servicio está habilitado,Se puede utilizar directamente. */
        object Enabled : EnsureResult()

        /** Servicio no habilitado,Los usuarios deben ser dirigidos a la configuración del sistema. */
        object NeedsEnable : EnsureResult()
    }
}
