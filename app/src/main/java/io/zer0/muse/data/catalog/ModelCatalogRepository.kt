package io.zer0.muse.data.catalog

import android.content.Context
import io.zer0.ai.core.ModelCatalog
import io.zer0.ai.core.ModelCatalogLoader
import io.zer0.common.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Almacén de catálogo de capacidad de modelo —— Fuente de datos de tres niveles + cadena de degradación.
 *
 * Prioridad de fuente de datos(alto → bajo):
 *  1. [refresh] Caché extraído del control remoto(filesDir/model-catalog.json)
 *  2. Ingrese al directorio integrado del APK(assets/model-catalog.json)
 *
 * El fallo de cualquier enlace no afectará el funcionamiento de la Aplicación:No se puede leer el caché, use el integrado,Error al tirar**Nunca toques el caché existente**
 * (con aguas arriba"No mantenerte viejo snapshot"misma orientación).
 *
 * La dirección remota está fijada en el servicio de directorio de creación propia de Muse.;La publicación de un nuevo directorio simplemente reemplaza el JSON,
 * Comparación silenciosa cuando se inicia el cliente [ModelCatalog.publishedAt],La nueva versión se lanzará sólo cuando esté disponible.
 */
class ModelCatalogRepository(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ModelCatalogRepo"
        const val REMOTE_URL = "https://museai.ltd/api/model-catalog.json"
        private const val CACHE_FILE = "model-catalog.json"
        private const val ASSET_FILE = "model-catalog.json"
        private const val TIMEOUT_MS = 10_000
    }

    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE)

    /**
     * Memoria caché (invalidada por PublishedAt).
     *
     * [current] Ahora ubicado en la ruta de calor proyectada del modelo (providersFlow → mergeIntoModels),
     * Leer el disco cada vez/asset Esto ralentizará la página de configuración y el inicio del chat; Los últimos resultados del análisis se almacenan en caché aquí.
     */
    @Volatile
    private var memCache: Pair<String, ModelCatalog>? = null

    /** Directorio incorporado(APK Trae el tuyo,Garantizado). Si el análisis falla, se devuelve una biblioteca vacía. */
    fun builtIn(): ModelCatalog = runCatching {
        context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
    }.map { ModelCatalogLoader.loadOrEmpty(it) }
        .getOrElse { ModelCatalog() }

    /** caché local(Directorio que se extrajo exitosamente la última vez);Devuelve nulo si no está presente. */
    fun cached(): ModelCatalog? {
        val f = cacheFile
        if (!f.isFile) return null
        return runCatching { ModelCatalogLoader.loadOrEmpty(f.readText()) }
            .getOrElse { null }
            ?.takeIf { it.providers.isNotEmpty() }
    }

    /** Directorio efectivo actual:caché primero,De lo contrario incorporado. */
    fun current(): ModelCatalog {
        memCache?.let { return it.second }
        val resolved = cached() ?: builtIn()
        memCache = resolved.publishedAt to resolved
        return resolved
    }

    /**
     * Extraiga el directorio remoto. Sólo si es remoto [ModelCatalog.publishedAt] Sólo cuando el directorio sea diferente del directorio efectivo actual se colocará el disco.
     *
     * @return Extraer descripción del resultado(Para mostrar en la página de configuración).
     */
    fun refresh(): RefreshResult {
        val remoteText = runCatching { httpGet(REMOTE_URL) }
            .onFailure { Logger.w(TAG, "No se pudo extraer el directorio: ${it.message}") }
            .getOrNull()
            ?: return RefreshResult(ok = false, message = "Error en la solicitud de red")

        val remote = ModelCatalogLoader.loadOrEmpty(remoteText)
        if (remote.providers.isEmpty()) {
            return RefreshResult(ok = false, message = "El directorio remoto está vacío o tiene un formato no válido.")
        }

        val currentPublished = current().publishedAt
        if (currentPublished.isNotBlank() && currentPublished == remote.publishedAt) {
            return RefreshResult(ok = true, updated = false, message = "Ya lo ultimo(${remote.publishedAt})")
        }

        return runCatching {
            cacheFile.writeText(remoteText)
            memCache = null
            Logger.i(TAG, "Catálogo actualizado: ${remote.publishedAt}, ${remote.providers.size} proveedores")
            RefreshResult(ok = true, updated = true, message = "ha sido actualizado a ${remote.publishedAt}")
        }.getOrElse {
            Logger.w(TAG, "Error al escribir en la caché del directorio: ${it.message}")
            RefreshResult(ok = false, message = "Falló la escritura en caché")
        }
    }

    /** Información resumida para el directorio actual(Para configurar la visualización de la página). */
    fun status(): CatalogStatus {
        val cachedCatalog = cached()
        val catalog = cachedCatalog ?: builtIn()
        val models = catalog.providers.values.sumOf { it.size }
        return CatalogStatus(
            publishedAt = catalog.publishedAt,
            providerCount = catalog.providers.size,
            modelCount = models,
            fromCache = cachedCatalog != null,
        )
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Obtener resultados. */
data class RefreshResult(
    val ok: Boolean,
    val updated: Boolean = false,
    val message: String,
)

/** Resumen del estado del directorio. */
data class CatalogStatus(
    val publishedAt: String,
    val providerCount: Int,
    val modelCount: Int,
    /** true = del caché(Sacado con éxito);false = Directorio incorporado. */
    val fromCache: Boolean,
)
