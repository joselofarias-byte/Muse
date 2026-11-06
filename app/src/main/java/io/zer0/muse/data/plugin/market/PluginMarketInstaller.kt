package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginManager
import java.io.File
import java.security.MessageDigest

/**
 * Portal de envío de confirmación de planos de instalación en el mercado.
 *
 * Después de que el usuario confirma la vista previa, esta clase realiza la ronda final de verificación antes de delegar [PluginManager] Compromiso atómico:
 *  1. El plan no ha caducado (lo que impide“Verifique paquetes antiguos de hace mucho tiempo”);
 *  2. staged El paquete todavía existe y el resumen zip es consistente con el resumen del directorio que se planea vincular (para evitar el reemplazo de archivos antes de la confirmación);
 *  3. [PluginManager.installConfirmedFromFile] Vuelva a analizar el paquete, vuelva a calcular el resumen de contenido y compárelo con la vista previa,
 *     Los editores desconocidos aún deben ser explícitos `trustPublisher = true`, sin firmar/Los paquetes manipulados serán rechazados.
 *
 * Fallar en cualquier paso no escribirá en el directorio del complemento; El error solo ocurre en la preparación (caché privado de la aplicación).
 */
internal class PluginMarketInstaller(
    private val pluginManager: PluginManager,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun confirmAndInstall(plan: PluginInstallPlan, trustPublisher: Boolean): Result<PluginManager.InstalledPlugin> {
        if (plan.isExpired(nowEpochMs())) {
            return Result.failure(IllegalStateException("La confirmación de instalación ha caducado, descárguela nuevamente"))
        }
        if (!plan.stagedFile.isFile) {
            return Result.failure(IllegalStateException("El paquete del complemento no existe, descárguelo nuevamente"))
        }
        val actual = sha256Hex(plan.stagedFile)
        if (!actual.equals(plan.artifactSha256, ignoreCase = true)) {
            return Result.failure(IllegalStateException("El paquete del complemento ha cambiado después de la confirmación; descárguelo nuevamente"))
        }
        return pluginManager.installConfirmedFromFile(
            file = plan.stagedFile,
            expectedPreview = plan.preview,
            trustPublisher = trustPublisher,
        )
    }

    /** Eliminar los paquetes preparados retenidos por el plan de instalación; sólo se permiten eliminar archivos dentro de las reglas de nomenclatura de descarga. */
    fun discard(plan: PluginInstallPlan) {
        val staged = plan.stagedFile
        if (staged.name != PluginDownloadClient.stagedFileName(plan.entry)) return
        runCatching { if (staged.isFile) staged.delete() }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
