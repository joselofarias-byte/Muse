package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Una instantánea de los directorios disponibles actualmente;[signedCatalog] cuando es nulo [entries] Debe estar vacío. */
internal data class CatalogSnapshot(
    val signedCatalog: SignedPluginCatalog?,
    val entries: List<PluginCatalogEntry>,
    /** El caché existe pero no está disponible (caducado)./Error en la verificación de firma/dañado) para la visualización de la interfaz de usuario. */
    val error: String? = null,
)

/** Secuencia grabada:[byCatalog] Para registros de subdirectorio,[legacy] Es un valor global heredado (su propiedad aún está por determinarse). */
internal data class RecordedSequences(
    val byCatalog: Map<String, Long>,
    val legacy: Long?,
)

/** actualizar resultados;[Rejected] El caché no se modificará,UI Se debe conservar la última entrada disponible y mostrar el motivo. */
internal sealed class CatalogRefreshResult {
    /** URL del directorio no configurada. */
    data object NotConfigured : CatalogRefreshResult()

    /** Después de extraer y pasar la verificación de la firma, el caché ha avanzado;sequence Ni la reversión ni el vencimiento pueden llegar aquí. */
    data class Updated(val signedCatalog: SignedPluginCatalog) : CatalogRefreshResult()

    /** Si la extracción o la verificación fallan, la caché y la secuencia aceptada permanecen sin cambios. */
    data class Rejected(val reason: String) : CatalogRefreshResult()
}

/**
 * Almacén de directorios de complementos: responsable del almacenamiento en caché del directorio, los registros de secuencia aceptados y la caducidad/Rechazo rechazado.
 *
 * Límite de seguridad:
 *  - Todas las entradas provienen únicamente de [PluginCatalogVerifier] Directorio de firmas verificadas;
 *  - La secuencia ha sido aceptada y persistida en el directorio privado de Android junto con el directorio, pero aún se niega a retroceder después de reiniciar;
 *  - Error al actualizar/El último caché verificado nunca se sobrescribirá cuando se rechace (se cerrará en caso de error, en lugar de borrarse o degradarse);
 *  - La raíz de la clave pública de confianza está dada por [trustRootKeys] Proporcionar (aplicar/Configurado explícitamente por el usuario) y nunca leído desde el directorio mismo.
 */
internal class PluginCatalogRepository(
    private val client: PluginCatalogClient,
    private val cacheDir: File,
    private val trustRootKeys: () -> Map<String, String>,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val catalogFile = File(cacheDir, CATALOG_FILE_NAME)
    private val sequenceFile = File(cacheDir, SEQUENCE_FILE_NAME)

    @Volatile
    private var cacheLoaded = false

    /** null Indica que no hay directorios autenticados (no configurados/caché no está disponible). */
    private var verifiedCatalog: SignedPluginCatalog? = null

    /** La secuencia más grande aceptada para cada ID de directorio; faltante significa que el directorio aún no ha sido aceptado. */
    private val acceptedSequences = mutableMapOf<String, Long>()

    /** Razón por la cual el último caché no estaba disponible, para explicación de la interfaz de usuario“¿Por qué el mercado está vacío?”. */
    private var cacheError: String? = null

    /** Lee el caché local (idempotente); devuelve una entrada disponible en la caché o el motivo de la indisponibilidad. */
    suspend fun loadCache(): CatalogSnapshot = withContext(Dispatchers.IO) {
        ensureCacheLoaded()
        synchronized(lock) { snapshotLocked() }
    }

    /**
     * Extraiga y verifique el directorio.
     *
     * En caso de éxito, se escribe la memoria caché y se avanza la secuencia aceptada; en caso de error, el caché y la secuencia permanecen sin cambios.
     * La persona que llama debe asegurarse de que [catalogUrl] desde el elemento de configuración.
     */
    suspend fun refresh(catalogUrl: String): CatalogRefreshResult = withContext(Dispatchers.IO) {
        val url = catalogUrl.trim()
        if (url.isEmpty()) return@withContext CatalogRefreshResult.NotConfigured
        ensureCacheLoaded()
        val keys = trustRootKeys()
        if (keys.isEmpty()) {
            return@withContext CatalogRefreshResult.Rejected(REASON_NO_TRUST_ROOT)
        }
        val fetched = client.fetch(url)
        val signed = fetched.signedCatalog
            ?: return@withContext CatalogRefreshResult.Rejected(fetched.error ?: "Error al extraer el directorio")
        // P2-25: La verificación y el compromiso deben ser atómicos.(en la misma cerradura) —
        //  La implementación original bloquea la lectura interna primero. sequence → Verificación de desbloqueo → Escribe de nuevo dentro de la cerradura otra vez.,Cuando dos actualizaciones simultáneas,
        //  La parte que presente la solicitud más tarde puede rebobinar la secuencia desde un valor alto hasta un valor bajo.(La validación se basa en caducados. lastAccepted).
        //  Aquí, "leer la última aceptación + verificación + escribir caché + secuencia avanzada" se coloca en la cerradura en su conjunto:
        //  Dos serializaciones de actualización simultáneas,sequence Estrictamente monótono sin regresión.
        val outcome = synchronized(lock) {
            val catalogId = signed.payload.catalogId
            val lastAccepted = acceptedSequences[catalogId]
            val verification = PluginCatalogVerifier.verify(
                signed = signed,
                trustedKeys = keys,
                nowEpochMs = nowEpochMs(),
                lastAcceptedSequence = lastAccepted,
            )
            if (!verification.valid) {
                val reason = translateReason(verification.reason)
                Logger.w(TAG, "Verificación del directorio rechazada: ${verification.reason}")
                return@synchronized CatalogRefreshResult.Rejected(reason)
            }
            val updated = acceptedSequences.toMutableMap().apply { this[catalogId] = signed.payload.sequence }
            try {
                persist(signed, updated)
            } catch (error: Exception) {
                Logger.e(TAG, "Error al escribir en la caché del directorio", error)
                return@synchronized CatalogRefreshResult.Rejected(
                    "Error al escribir en la caché del directorio: ${error.message ?: "unknown"}",
                )
            }
            verifiedCatalog = signed
            acceptedSequences.clear()
            acceptedSequences.putAll(updated)
            cacheError = null
            CatalogRefreshResult.Updated(signed)
        }
        return@withContext outcome
    }

    /** El directorio que pasó la verificación de firma más reciente;[loadCache]/[refresh] Anteriormente devuelto nulo. */
    fun currentCatalog(): SignedPluginCatalog? = synchronized(lock) { verifiedCatalog }

    /** Especifica la secuencia aceptada por el directorio;null Indica que el directorio aún no ha sido aceptado. */
    fun lastAcceptedSequence(catalogId: String): Long? = synchronized(lock) { acceptedSequences[catalogId] }

    /**
     * P0-9: Borre las entradas de la caché del mercado con secuencias aceptadas.
     *
     * Se llama después de revocar el directorio raíz de confianza.:El caché antiguo se verificó con una raíz de confianza que se eliminó,Continuar manteniéndolo engañará a los usuarios.
     * (La interfaz todavía muestra entradas antiguas.). La próxima vez después de limpiar [refresh] Volver a autenticarse con la raíz de confianza actual,El rechazo da como resultado la no entrada.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                catalogFile.delete()
                sequenceFile.delete()
            }.onFailure { error -> Logger.w(TAG, "Error en la limpieza de la caché del mercado(ignorar)", error) }
            verifiedCatalog = null
            acceptedSequences.clear()
            cacheError = null
            cacheLoaded = true
        }
    }

    private fun ensureCacheLoaded() {
        if (cacheLoaded) return
        synchronized(lock) {
            if (cacheLoaded) return
            loadCacheLocked()
            cacheLoaded = true
        }
    }

    private fun loadCacheLocked() {
        val recorded = readRecordedSequences()
        acceptedSequences.clear()
        acceptedSequences.putAll(recorded.byCatalog)
        cacheError = null
        verifiedCatalog = null
        if (!catalogFile.isFile) return
        val signed = try {
            AppJson.decodeFromString(SignedPluginCatalog.serializer(), catalogFile.readText())
        } catch (error: Exception) {
            Logger.w(TAG, "Error en el análisis de la caché del directorio, archivo corrupto en cuarentena", error)
            AtomicFileStore.quarantine(catalogFile, "plugin_catalog_parse")
            cacheError = "La caché del directorio está dañada. Actualice el directorio."
            return
        }
        val catalogId = signed.payload.catalogId
        // El formato antiguo solo registra una única secuencia global (en ese momento solo había un directorio posible), que pertenece a la identificación del directorio en el caché.
        recorded.legacy?.let { legacy ->
            if (!acceptedSequences.containsKey(catalogId)) acceptedSequences[catalogId] = legacy
        }
        val verification = PluginCatalogVerifier.verify(
            signed = signed,
            trustedKeys = trustRootKeys(),
            nowEpochMs = nowEpochMs(),
            lastAcceptedSequence = acceptedSequences[catalogId],
        )
        if (!verification.valid) {
            // Caducidad de la caché/La secuencia del directorio se conserva cuando se cambia la clave (continúa rechazando el respaldo), pero las entradas ya no están disponibles para el mundo exterior.
            cacheError = translateReason(verification.reason)
            return
        }
        verifiedCatalog = signed
        acceptedSequences[catalogId] = signed.payload.sequence
    }

    private fun snapshotLocked(): CatalogSnapshot {
        // Vuelva a presionar la hora actual al tomar una instantánea/La verificación de la firma de raíz de confianza evita que el directorio permanezca disponible después de la expiración mientras el proceso está activo.
        val signed = verifiedCatalog
        if (signed != null) {
            val verification = PluginCatalogVerifier.verify(
                signed = signed,
                trustedKeys = trustRootKeys(),
                nowEpochMs = nowEpochMs(),
                lastAcceptedSequence = acceptedSequences[signed.payload.catalogId],
            )
            if (!verification.valid) {
                cacheError = translateReason(verification.reason)
                verifiedCatalog = null
            }
        }
        val current = verifiedCatalog
        return if (current == null) {
            CatalogSnapshot(signedCatalog = null, entries = emptyList(), error = cacheError)
        } else {
            CatalogSnapshot(
                signedCatalog = current,
                entries = current.payload.entries,
                error = null,
            )
        }
    }

    /**
     * Leer las secuencias aceptadas de cada directorio.
     *
     * La versión anterior solo registra una única secuencia global (en ese momento solo había un directorio) y el valor no se puede autocertificar.
     * Archivado por la persona que llama según la identificación del directorio en el caché (consulte [loadCacheLocked]); Los formatos desconocidos se tratan como no grabados.
     */
    private fun readRecordedSequences(): RecordedSequences {
        val text = runCatching { sequenceFile.takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull()
            .orEmpty()
        if (text.isEmpty()) return RecordedSequences(emptyMap(), null)
        text.toLongOrNull()?.let { legacy ->
            return RecordedSequences(emptyMap(), legacy.takeIf { it >= 0 })
        }
        val byCatalog = runCatching { AppJson.decodeFromString(SEQUENCE_SERIALIZER, text) }
            .getOrElse { error ->
                Logger.w(TAG, "El análisis del registro de secuencia del directorio falló y se trata como no registrado", error)
                emptyMap()
            }
            .filterValues { it >= 0 }
        return RecordedSequences(byCatalog, null)
    }

    private fun persist(signed: SignedPluginCatalog, sequences: Map<String, Long>) {
        // Escribe primero el directorio y luego la secuencia: un bloqueo en el medio solo guardará un avance y no ocurrirá.“Aceptado arriba del caché”punto muerto.
        AtomicFileStore.writeText(
            catalogFile,
            AppJson.encodeToString(SignedPluginCatalog.serializer(), signed),
        )
        AtomicFileStore.writeText(sequenceFile, AppJson.encodeToString(SEQUENCE_SERIALIZER, sequences))
    }

    private fun translateReason(reason: String?): String = when (reason) {
        null -> "Error en la verificación del directorio"
        "catalog too large" -> "El tamaño del directorio excede el límite"
        "unknown catalog key" -> "Clave de firma de catálogo desconocida"
        "unsupported catalog metadata" -> "Formato de directorio no admitido"
        "catalog expired" -> "El directorio ha caducado, actualice"
        "catalog sequence rollback" -> "Reversión de la versión del directorio, rechazada"
        "invalid catalog entry" -> "El directorio contiene entradas ilegales."
        "catalog signature mismatch" -> "La firma del directorio no coincide"
        "catalog signature could not be verified" -> "No se puede verificar la firma del directorio"
        else -> reason
    }

    companion object {
        private const val TAG = "PluginCatalogRepository"

        internal const val CATALOG_FILE_NAME = "catalog.json"
        internal const val SEQUENCE_FILE_NAME = "catalog_sequence"

        /** Se corrigió el motivo de rechazo cuando el directorio raíz de confianza no está configurado.UI Según este mensaje“Configurar la raíz de confianza”. */
        internal const val REASON_NO_TRUST_ROOT = "La clave pública raíz de confianza del directorio no está configurada"

        private val SEQUENCE_SERIALIZER = MapSerializer(String.serializer(), Long.serializer())
    }
}
