package io.zer0.muse.data.plugin.market

import io.zer0.common.Logger
import io.zer0.muse.tools.script.SkillBridgeHttpClient
import java.io.File
import java.security.MessageDigest

/** Result of a verified artifact download; the file is app-private staging, not the active plugin dir. */
data class DownloadedArtifact(
    val file: File,
    val sha256: String,
    val bytes: Long,
)

data class DownloadResult(
    val artifact: DownloadedArtifact? = null,
    val error: String? = null,
) {
    val success: Boolean get() = artifact != null
}

/**
 * Downloads catalog artifacts into app-private staging.
 *
 * Transport, redirect handling, DNS pinning, and route validation are delegated to
 * [SkillBridgeHttpClient], the project's controlled HTTP egress. This client adds the
 * catalog-specific policy: size limit, declared SHA-256, and never writing into the
 * active plugin directory.
 */
internal class PluginDownloadClient(
    private val httpClient: SkillBridgeHttpClient = SkillBridgeHttpClient(),
    private val stagingDir: File,
    private val maxArtifactBytes: Int = MAX_ARTIFACT_BYTES,
) {
    fun download(entry: PluginCatalogEntry): DownloadResult {
        val expectedSha = entry.artifactSha256.lowercase()
        return try {
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                return DownloadResult(error = "No se puede crear el directorio de caché del complemento")
            }
            val response = httpClient.getBytes(entry.artifactUrl, maxArtifactBytes)
            if (response.status !in 200..299) {
                return DownloadResult(error = "Falló la descarga del complemento: HTTP ${response.status}")
            }
            val bytes = response.body
            if (bytes.size.toLong() > maxArtifactBytes) {
                return DownloadResult(error = "El paquete de complementos excede el límite de tamaño")
            }
            val actual = sha256Hex(bytes)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                return DownloadResult(error = "El resumen del paquete de complementos no coincide con el directorio")
            }
            val target = File(stagingDir, stagedFileName(entry))
            target.writeBytes(bytes)
            DownloadResult(artifact = DownloadedArtifact(target, actual, bytes.size.toLong()))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.w("PluginDownloadClient", "Falló la descarga del complemento: ${entry.id}", error)
            DownloadResult(error = error.message ?: "Falló la descarga del complemento")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Matches the plugin package size limit enforced by PluginManager. */
        internal const val MAX_ARTIFACT_BYTES = 20 * 1024 * 1024

        /** staging Una convención de nomenclatura única para archivos; el plan de instalación utiliza esto para identificar archivos que se pueden eliminar. */
        internal fun stagedFileName(entry: PluginCatalogEntry): String = "${entry.id}-${entry.version}.muse-plugin"
    }
}
