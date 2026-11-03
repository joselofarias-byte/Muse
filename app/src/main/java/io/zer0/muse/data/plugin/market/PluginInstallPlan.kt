package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginSecurityGate
import java.io.File

/**
 * A confirmation-bound install plan.
 *
 * The plan pins the exact staged artifact bytes and the reviewed package preview, so the
 * later confirm step re-reads the same file instead of re-downloading from the network.
 * A stale plan (file replaced, bytes changed, or expiry passed) must fail closed.
 */
data class PluginInstallPlan(
    val entry: PluginCatalogEntry,
    val stagedFile: File,
    val artifactSha256: String,
    val preview: PluginSecurityGate.InstallPreview,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs
}

sealed class InstallPlanResult {
    data class Ready(val plan: PluginInstallPlan) : InstallPlanResult()
    data class Rejected(val reason: String) : InstallPlanResult()
}

/**
 * Builds preview plans from signed catalog entries.
 *
 * This class deliberately performs no installation: it verifies the catalog attestation,
 * ensures the artifact exists and matches the catalog digest, and produces the host-owned
 * preview that the user must confirm before [io.zer0.muse.data.plugin.PluginManager] commits.
 */
class PluginInstallCoordinator(
    private val trustRootKeys: Map<String, String>,
    private val lastAcceptedSequence: (String) -> Long?,
    private val planTtlMs: Long = DEFAULT_PLAN_TTL_MS,
) {
    fun preparePlan(
        signedCatalog: SignedPluginCatalog,
        entryId: String,
        stagedFile: File,
        artifactSha256: String,
        reviewedPackage: PluginPackageReview,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): InstallPlanResult {
        val verification = PluginCatalogVerifier.verify(
            signed = signedCatalog,
            trustedKeys = trustRootKeys,
            nowEpochMs = nowEpochMs,
            // La secuencia aceptada se registra según la identificación del directorio. Al cambiar de directorio, cada uno de ellos no se considerará una reversión.
            lastAcceptedSequence = lastAcceptedSequence(signedCatalog.payload.catalogId),
        )
        if (!verification.valid) {
            return InstallPlanResult.Rejected(verification.reason ?: "Error en la verificación del directorio")
        }
        val entry = signedCatalog.payload.entries.firstOrNull { it.id == entryId }
            ?: return InstallPlanResult.Rejected("El complemento no existe en el directorio.: $entryId")
        if (!stagedFile.isFile) return InstallPlanResult.Rejected("El paquete de complementos no existe")
        if (!artifactSha256.equals(entry.artifactSha256, ignoreCase = true)) {
            return InstallPlanResult.Rejected("El resumen del paquete de complementos no coincide con el directorio")
        }
        if (!reviewedPackage.contentSha256.equals(entry.manifestSha256, ignoreCase = true)) {
            return InstallPlanResult.Rejected("El resumen del contenido del complemento no coincide con la tabla de contenido.")
        }
        if (reviewedPackage.preview.id != entry.id) {
            return InstallPlanResult.Rejected("La identificación del complemento no coincide con el directorio")
        }
        if (reviewedPackage.preview.version != entry.version) {
            return InstallPlanResult.Rejected("La versión del complemento no coincide con el directorio.")
        }
        // La identidad del editor declarada por el directorio debe ser coherente con la identidad resuelta por la firma en el paquete: estos dos campos en el directorio se mostrarán al usuario,
        // El emisor de la tarjeta muestra información que puede falsificarse si se permite que no coincida con la firma real.
        if (reviewedPackage.preview.publisherId != entry.publisherId) {
            return InstallPlanResult.Rejected("El editor del complemento no coincide con el directorio.")
        }
        if (!reviewedPackage.preview.publisherKeyFingerprint.equals(entry.publisherKeyFingerprint, ignoreCase = true)) {
            return InstallPlanResult.Rejected("La clave pública del editor del complemento no coincide con el directorio")
        }
        return InstallPlanResult.Ready(
            PluginInstallPlan(
                entry = entry,
                stagedFile = stagedFile,
                artifactSha256 = artifactSha256,
                preview = reviewedPackage.preview,
                createdAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + planTtlMs,
            ),
        )
    }

    /** Package-level review result that stays bound to the reviewed bytes. */
    data class PluginPackageReview(
        val preview: PluginSecurityGate.InstallPreview,
        val contentSha256: String,
    )

    companion object {
        internal const val DEFAULT_PLAN_TTL_MS = 5 * 60 * 1000L
    }
}
