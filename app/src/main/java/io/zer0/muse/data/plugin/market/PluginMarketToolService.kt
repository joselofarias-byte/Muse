package io.zer0.muse.data.plugin.market

import android.content.Context
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginSecurityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2.0.1: Servicios del lado de las herramientas en el mercado de complementos — suministro `plugin_market_search` / `plugin_market_install` Usar.
 *
 * Comparte el mismo conjunto de componentes del mercado (caché de directorio, raíz de confianza,staging directorio), asegurándose de que las herramientas en segundo plano
 * Lo que ve la interfaz de usuario es el mismo directorio firmado y la misma secuencia aceptada, y no se produce ninguna bifurcación del estado de instancia dual.
 *
 * Límite de seguridad:
 *  - La búsqueda es una operación de sólo lectura;
 *  - La instalación debe pasar por la tarjeta de aprobación de sesión (herramienta registrada como ALTO riesgo) y reutilización. [PluginManager] de completo
 *    enlace de revisión (ZIP estructura, lista blanca de capacidades, firma del emisor, resumen de contenido) y [PluginInstallCoordinator]
 *    La verificación de vinculación de directorio no proporciona ninguna ruta para omitir la confirmación del usuario;
 *  - El editor desconocido se rechaza de forma predeterminada: solo la persona que llama pasa explícitamente `trustPublisher=true`(Indica que el usuario ya está en la conversación
 *    Confirme que confía en el editor) antes de escribir a la raíz de confianza y continuar con la instalación.
 */
internal class PluginMarketToolService(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val marketSettings: PluginMarketSettings,
    private val trustRoots: PluginMarketTrustRoots,
    private val repository: PluginCatalogRepository,
) : PluginMarketToolGateway {

    private val downloadClient = PluginDownloadClient(
        stagingDir = File(context.cacheDir, "plugin_market_staging"),
    )
    private val installer = PluginMarketInstaller(pluginManager)

    /** Recupere el directorio de complementos; devuelve una lista de texto para que el modelo lo lea. */
    override suspend fun search(query: String): String {
        val catalog = ensureCatalog().getOrElse { error ->
            return "El mercado de complementos no está disponible temporalmente:${error.message ?: "error desconocido"}"
        }
        return renderSearchResult(catalog, query)
    }

    /** Descargue e instale el complemento especificado; devolver los resultados de la ejecución para la lectura del modelo. */
    override suspend fun install(entryId: String, trustPublisher: Boolean): String {
        val id = entryId.trim()
        if (id.isBlank()) return "Error de instalación: falta plugin_id."
        return runInstall(id, trustPublisher)
    }

    /** Desinstale el complemento instalado; devolver los resultados de la ejecución para la lectura del modelo. */
    override suspend fun uninstall(pluginId: String): String {
        val id = pluginId.trim()
        if (id.isBlank()) return "Error de desinstalación: falta plugin_id."
        val installed = pluginManager.list().firstOrNull { it.id == id }
            ?: return "Complemento instalado no encontrado\"$id”. Primero puede usar plugin_market_search para verificar el ID del complemento en el mercado."
        return try {
            pluginManager.uninstall(id)
            "El complemento ha sido desinstalado\"${installed.name}\"($id), cuyas habilidades se limpian junto con la copia local."
        } catch (e: Exception) {
            "La desinstalación falló:${e.message ?: "error desconocido"}"
        }
    }

    /** habilitar/Desactivar complementos instalados; devolver resultados de ejecución para la lectura del modelo. */
    override suspend fun setEnabled(pluginId: String, enabled: Boolean): String {
        val id = pluginId.trim()
        if (id.isBlank()) return "Error en la operación: falta plugin_id."
        val installed = pluginManager.list().firstOrNull { it.id == id }
            ?: return "Complemento instalado no encontrado\"$id”."
        if (enabled && !installed.installationConfirmed) {
            return "El complemento aún no ha completado la confirmación de la instalación y se ha rechazado su activación. Primero complete la confirmación de instalación en la página de administración de complementos."
        }
        return try {
            pluginManager.setEnabled(id, enabled)
            if (enabled) {
                "Complemento habilitado\"${installed.name}\"($id)."
            } else {
                "Complemento deshabilitado\"${installed.name}\"($id), la instalación se conserva y se puede volver a habilitar en cualquier momento."
            }
        } catch (e: Exception) {
            "Operación fallida:${e.message ?: "error desconocido"}"
        }
    }

    // ── Buscar ─────────────────────────────────────────────────────────────

    private fun renderSearchResult(catalog: SignedPluginCatalog, query: String): String {
        val entries = catalog.payload.entries
        val installedById = pluginManager.list().associateBy { it.id }
        val trustedPublishers = pluginManager.trustedPublishers()
        val keyword = query.trim().lowercase()
        val matched = if (keyword.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.id.lowercase().contains(keyword) ||
                    entry.name.lowercase().contains(keyword) ||
                    entry.description.lowercase().contains(keyword)
            }
        }
        if (matched.isEmpty()) {
            return if (entries.isEmpty()) {
                "Actualmente no hay complementos instalables en Plugin Market."
            } else {
                "No hay coincidencia \"$query”complemento."
            }
        }
        val builder = StringBuilder()
        builder.appendLine("mercado de complementos ${entries.size} complementos, coincidencia ${matched.size} de:")
        matched.take(MAX_SEARCH_RESULTS).forEach { entry ->
            val installedVersion = installedById[entry.id]?.version
            val publisherTrusted = trustedPublishers.any { publisher ->
                publisher.publisherId == entry.publisherId &&
                    publisher.fingerprint.equals(entry.publisherKeyFingerprint, ignoreCase = true)
            }
            builder.appendLine(
                "- ${entry.id} v${entry.version}「${entry.name}\":${entry.description.take(DESCRIPTION_LIMIT)}" +
                    "(Editor ${entry.publisherId}" +
                    (if (publisherTrusted) ", ya confiaba" else ", no es de confianza para esta máquina") +
                    ";" + (if (installedVersion != null) "Instalado v$installedVersion" else "No instalado") + ")",
            )
        }
        if (matched.size > MAX_SEARCH_RESULTS) {
            builder.appendLine("(Totales ${matched.size} , solo se enumeran los primeros $MAX_SEARCH_RESULTS )")
        }
        builder.append(
            "Instalar usando plugin_market_install(plugin_id ver arriba). La tarjeta de aprobación aparecerá durante la instalación;" +
                "Cuando no se confía en el emisor, la herramienta primero devolverá información para ser confirmada. Muestre la información del emisor al usuario." +
                "Traer después de la confirmación del usuario trust_publisher=true Recordar.",
        )
        return builder.toString()
    }

    // ── Instalación ─────────────────────────────────────────────────────────────

    /**
     * Proceso de instalación principal (excluyendo la verificación de entrada).
     *
     * Las rutas de falla se lanzan uniformemente [InstallFailed], para evitar que se dispersen múltiples salidas; generar con éxito dos formularios:
     *  - El emisor no es de confianza y la persona que llama no está confirmada → Información de devolución por confirmar (sin realizar el pedido);
     *  - Instalación completada → Devuelve los resultados de la instalación.
     */
    private suspend fun runInstall(id: String, trustPublisher: Boolean): String {
        var stagedFile: File? = null
        var planCreated = false
        return try {
            val catalog = ensureCatalog().getOrElse { error ->
                throw InstallFailed("La instalación falló:${error.message ?: "El mercado de complementos no está disponible temporalmente"}")
            }
            val staged = requireStaged(catalog, id)
            stagedFile = staged.artifact.file
            val decision = requireAllowed(reviewArtifact(staged.artifact))
            if (decision.requiresPublisherTrust && !trustPublisher) {
                publisherTrustPrompt(decision, staged.entry)
            } else {
                val plan = buildPlan(catalog, staged.entry, staged.artifact, decision)
                planCreated = true
                val result = installer.confirmAndInstall(
                    plan,
                    trustPublisher = decision.requiresPublisherTrust,
                )
                installer.discard(plan)
                val installed = result.getOrElse { error ->
                    throw InstallFailed("La instalación falló:${error.message ?: "Error al enviar la instalación"}")
                }
                "Complemento instalado y habilitado:${installed.name} v${installed.version}(${installed.id})."
            }
        } catch (failed: InstallFailed) {
            failed.message ?: "La instalación falló"
        } finally {
            val staged = stagedFile
            if (!planCreated && staged != null) runCatching { staged.delete() }
        }
    }

    /** Localice la entrada del directorio y descargue el producto en staging (tamaño/La verificación resumida se completa en el cliente de descarga); lanzamientos de fracaso [InstallFailed]. */
    private suspend fun requireStaged(catalog: SignedPluginCatalog, id: String): StagedPlugin {
        val entry = catalog.payload.entries.firstOrNull { it.id == id }
            ?: throw InstallFailed("Error de instalación: el complemento no existe en el directorio $id(Primero use plugin_market_search para recuperar la identificación de confirmación).")
        val artifact = withContext(Dispatchers.IO) { downloadClient.download(entry) }.artifact
            ?: throw InstallFailed("Error de instalación: Error de descarga")
        return StagedPlugin(entry, artifact)
    }

    /** Revisar el paquete de complementos (estructura/Habilidad/Firma del editor); lanzado cuando la excepción o la vista previa no está disponible [InstallFailed]. */
    private suspend fun reviewArtifact(artifact: DownloadedArtifact): PluginSecurityGate.Decision =
        pluginManager.reviewFromFile(artifact.file).getOrElse { error ->
            throw InstallFailed("La instalación falló:${error.message ?: "Error en la revisión del complemento"}")
        }

    /** Rechazar resultados de revisión no instalables: los paquetes de Market deben estar firmados de forma válida y no alterados. */
    private fun requireAllowed(decision: PluginSecurityGate.Decision): PluginSecurityGate.Decision {
        if (!decision.allowed) {
            throw InstallFailed("La instalación falló:${decision.reason ?: "Revisión de seguridad fallida del complemento"}")
        }
        if (decision.signature.status == PluginSecurityGate.SignatureStatus.UNSIGNED) {
            throw InstallFailed("Error de instalación: los paquetes de Market deben estar firmados por el editor.")
        }
        return decision
    }

    /** Utilice la raíz de confianza actual para elaborar un plan de instalación; la verificación del directorio falla y arroja [InstallFailed]. */
    private fun buildPlan(
        catalog: SignedPluginCatalog,
        entry: PluginCatalogEntry,
        artifact: DownloadedArtifact,
        decision: PluginSecurityGate.Decision,
    ): PluginInstallPlan {
        val coordinator = PluginInstallCoordinator(
            trustRootKeys = trustRoots.current(),
            lastAcceptedSequence = { catalogId -> repository.lastAcceptedSequence(catalogId) },
        )
        return when (
            val planResult = coordinator.preparePlan(
                signedCatalog = catalog,
                entryId = entry.id,
                stagedFile = artifact.file,
                artifactSha256 = artifact.sha256,
                reviewedPackage = PluginInstallCoordinator.PluginPackageReview(
                    preview = decision.preview,
                    contentSha256 = decision.preview.contentSha256,
                ),
            )
        ) {
            is InstallPlanResult.Ready -> planResult.plan
            is InstallPlanResult.Rejected -> throw InstallFailed("La instalación falló:${planResult.reason}")
        }
    }

    // ── Directorio y confianza ───────────────────────────────────────────────────────

    /**
     * Asegúrese de que el directorio esté disponible: dé prioridad al uso del caché firmado local; si el caché no está disponible, actualícelo según la configuración actual.
     *
     * No se realiza ninguna actualización de la red cuando el caché está disponible (consistente con el comportamiento cuando se abre la página de administración del complemento) para evitar esperar a la red para cada búsqueda;
     * pérdida de caché/Caducado/Actualizar cuando esté dañado, devolver el motivo específico si falla.
     */
    private suspend fun ensureCatalog(): Result<SignedPluginCatalog> {
        trustRoots.reload()
        val cached = repository.loadCache().signedCatalog
        if (cached != null) return Result.success(cached)
        val url = marketSettings.catalogUrl()
        return if (url.isBlank()) {
            Result.failure(IllegalStateException("El directorio de complementos no está configurado"))
        } else {
            when (val refreshed = repository.refresh(url)) {
                is CatalogRefreshResult.Updated -> Result.success(refreshed.signedCatalog)
                is CatalogRefreshResult.Rejected ->
                    Result.failure(IllegalStateException("Error al actualizar el directorio:${refreshed.reason}"))
                CatalogRefreshResult.NotConfigured ->
                    Result.failure(IllegalStateException("El directorio de complementos no está configurado"))
            }
        }
    }

    /** Pendiente de confirmación del emisor que no es de confianza; El modelo debe mostrarse al usuario antes de traerlo. `trust_publisher=true` Intentar otra vez. */
    private fun publisherTrustPrompt(decision: PluginSecurityGate.Decision, entry: PluginCatalogEntry): String {
        val capabilities = (decision.preview.capabilities + decision.preview.permissions).distinct()
        return buildString {
            appendLine("El usuario debe confirmar que confía en el editor antes de poder instalarlo (esta es la primera vez que se instala el complemento del editor en esta máquina).")
            appendLine("Primero muestre la siguiente información al usuario; después de que el usuario confirme, llame a plugin_market_install nuevamente y traiga trust_publisher=true:")
            appendLine("- Complemento:${entry.name} v${entry.version}(${entry.id})")
            appendLine("- Propósito:${entry.description}")
            appendLine("- Editor:${decision.signature.publisherId}")
            appendLine("- Huella digital de clave pública:${decision.signature.fingerprint}")
            append("- Declarar capacidades:${if (capabilities.isEmpty()) "Ninguno (solo se proporcionan herramientas)" else capabilities.joinToString()}")
        }
    }

    /** Complemento descargado y de preparación (entrada de directorio + producto local). */
    private data class StagedPlugin(
        val entry: PluginCatalogEntry,
        val artifact: DownloadedArtifact,
    )

    /** Excepción del flujo de control del proceso de instalación: solo se transmite al usuario el motivo del fallo y no se elimina [runInstall]. */
    private class InstallFailed(message: String) : Exception(message)

    private companion object {
        const val MAX_SEARCH_RESULTS = 30
        const val DESCRIPTION_LIMIT = 120
    }
}
