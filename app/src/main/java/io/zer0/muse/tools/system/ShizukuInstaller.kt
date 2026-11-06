package io.zer0.muse.tools.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.zer0.common.Logger

/**
 * P3-3: Shizuku instalador(Instalación de arranque).
 *
 * Muse No incorporado Shizuku APK(Tamaño grande + actualizaciones de versión frecuentes),En su lugar, guíe a los usuarios para que realicen la instalación desde los canales oficiales.:
 *  - GitHub Releases: https://github.com/RikkaApps/Shizuku/releases
 *  - Google Play: com.rikka.shizuku
 *
 * Responsabilidades:
 *  1. [isInstalled]: Shizuku APK ¿Está instalado?
 *  2. [openDownloadPage]: Abra la página de descarga oficial.(Prioridad Play Store,revertir GitHub)
 *  3. [ensureInstalled]: Verificar y guiar la instalación.
 */
class ShizukuInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuInstaller"

        /** Shizuku APK nombre del paquete(No del SDK Java namespace). */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** Shizuku GitHub Releases Página. */
        const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"
    }

    /** Shizuku APK ¿Se ha instalado? */
    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
    } catch (e: Exception) {
        // PackageManager.NameNotFoundException cuando getPackageInfo lanza una excepción
        false
    }

    /**
     * Abra la página de descarga de Shizuku(Prioridad Play Store,Se abre el navegador alternativo GitHub).
     * @return true Salto exitoso;false Salto fallido
     */
    fun openDownloadPage(): Boolean {
        // Pruebe primero la página de detalles de Play Store
        val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playIntent.resolveActivity(context.packageManager) != null) {
            return try {
                context.startActivity(playIntent)
                true
            } catch (e: Exception) {
                Logger.w(TAG, "No se pudo abrir Play Store: ${e.message}")
                openGitHubInBrowser()
            }
        }
        return openGitHubInBrowser()
    }

    private fun openGitHubInBrowser(): Boolean {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "No se puede abrir la página de descarga: ${e.message}", e)
            false
        }
    }

    /**
     * Asegúrate de que Shizuku esté instalado;Vuelva al indicador de inicio cuando no esté instalado.
     * @return [EnsureResult]
     */
    fun ensureInstalled(): EnsureResult {
        return if (isInstalled()) EnsureResult.Installed else EnsureResult.NeedsInstall
    }

    sealed class EnsureResult {
        /** Shizuku APK Instalado(Pero eso no significa que el servicio esté funcionando.,Requiere iniciar la aplicación Shizuku). */
        object Installed : EnsureResult()

        /** Shizuku No instalado,Necesidad de guiar la descarga. */
        object NeedsInstall : EnsureResult()
    }
}
