package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File

/** Un metadato que ha conservado versiones históricas; Se utiliza para volver a verificar y mostrar la interfaz de usuario antes de la reversión. */
@Serializable
data class RetainedVersionRecord(
    val version: String,
    val publisherId: String,
    val publisherKeyFingerprint: String,
    /** Resumen del contenido cuando se conserva; recalcular la comparación antes de la reversión para evitar que se reemplacen las copias históricas. */
    val contentSha256: String,
    val retainedAtEpochMs: Long,
)

/** Versiones históricas conservadas: metadatos + directorio de paquetes verificable. */
data class RetainedPluginVersion(
    val pluginId: String,
    val record: RetainedVersionRecord,
    val directory: File,
) {
    val version: String get() = record.version
}

/**
 * Biblioteca de versiones históricas del complemento.
 *
 * guardar solo**Verificación de firma y contenido aprobada.**Una copia del paquete histórico para que los usuarios puedan revertirlo activamente; no participa en la ejecución.
 * Tampoco aparece en la lista de complementos. Al retroceder, la persona que llama debe pasar nuevamente por la verificación de firma y contenido. Esta clase no hace juicios de confianza.
 *
 * Diseño del directorio (colocado deliberadamente fuera del directorio del complemento para evitar que la lógica transversal de archivos del complemento lo considere un archivo de paquete):
 * ```
 * <root>/<pluginId>/<version>/meta.json   Metadatos
 * <root>/<pluginId>/<version>/files/...   Copia de archivos en paquete.
 * ```
 */
class PluginVersionStore(
    private val root: File,
    private val maxRetained: Int = DEFAULT_MAX_RETAINED,
) {

    /** Guarde una copia de la versión instalada; conservar la misma versión nuevamente sobrescribirá la copia anterior. */
    fun retain(
        pluginId: String,
        version: String,
        sourceDirectory: File,
        record: RetainedVersionRecord,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Result<Unit> = runCatching {
        requireValidId(pluginId)
        requireValidVersion(version)
        require(sourceDirectory.isDirectory) { "El directorio del complemento fuente no existe" }
        require(record.version == version) { "La versión de metadatos no coincide con la versión reservada" }
        require(record.contentSha256.length == 64) { "El formato del resumen de contenido es ilegal." }

        root.mkdirs()
        val target = versionDirectory(pluginId, version)
        val staging = File(pluginIdDirectory(pluginId), ".staging_${version}_${System.nanoTime()}")
        staging.deleteRecursively()
        val filesTarget = File(staging, FILES_DIR)
        require(filesTarget.mkdirs()) { "No se puede crear el directorio de copia de la versión" }
        copyPackageFiles(sourceDirectory, filesTarget)
        AtomicFileStore.writeText(
            File(staging, META_FILE),
            AppJson.encodeToString(RetainedVersionRecord.serializer(), record.copy(retainedAtEpochMs = nowEpochMs)),
        )

        // Si ya existe la misma versión, elimine primero la copia anterior y luego cámbiele el nombre; si el cambio de nombre falla, no quedará la mitad de la copia.
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        prune(pluginId)
        Logger.i(TAG, "Se ha conservado la versión del historial del complemento.: $pluginId@$version")
    }

    /** Versiones retenidas, ordenadas por número de versión, de la más nueva a la más antigua. */
    fun list(pluginId: String): List<RetainedPluginVersion> {
        val dir = pluginIdDirectory(pluginId)
        val versions = dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { readRetained(pluginId, it) }
        return versions.sortedWith(
            compareByDescending<RetainedPluginVersion> { PluginVersion.parse(it.version) ?: PluginVersion(0, 0, 0) }
                .thenByDescending { it.record.retainedAtEpochMs },
        )
    }

    fun find(pluginId: String, version: String): RetainedPluginVersion? = list(pluginId).firstOrNull { it.version == version }

    fun delete(pluginId: String, version: String) {
        runCatching {
            requireValidId(pluginId)
            requireValidVersion(version)
            versionDirectory(pluginId, version).deleteRecursively()
        }.onFailure { Logger.w(TAG, "No se pudo eliminar la versión histórica: $pluginId@$version", it) }
    }

    fun deleteAll(pluginId: String) {
        runCatching { pluginIdDirectory(pluginId).deleteRecursively() }
            .onFailure { Logger.w(TAG, "No se pudo eliminar la versión histórica del complemento: $pluginId", it) }
    }

    /** Solo mantén lo último [maxRetained] versiones, las partes sobrantes se eliminarán de la más antigua a la más nueva. */
    fun prune(pluginId: String) {
        val retained = list(pluginId)
        retained.drop(maxRetained).forEach { stale ->
            Logger.i(TAG, "Limpiar versiones históricas caducadas: ${stale.pluginId}@${stale.version}")
            stale.directory.deleteRecursively()
        }
    }

    private fun readRetained(pluginId: String, directory: File): RetainedPluginVersion? = runCatching {
        val metaFile = File(directory, META_FILE)
        if (!metaFile.isFile) return@runCatching null
        val record = AppJson.decodeFromString(RetainedVersionRecord.serializer(), metaFile.readText())
        if (record.version != directory.name) return@runCatching null
        val files = File(directory, FILES_DIR)
        if (!files.isDirectory) return@runCatching null
        RetainedPluginVersion(pluginId, record, files)
    }.getOrElse { error ->
        Logger.w(TAG, "El registro de la versión histórica está dañado y omitido.: ${directory.name}", error)
        null
    }

    private fun copyPackageFiles(source: File, target: File) {
        source.listFiles().orEmpty().forEach { entry ->
            val destination = File(target, entry.name)
            if (entry.isDirectory) {
                destination.mkdirs()
                copyPackageFiles(entry, destination)
            } else {
                entry.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun pluginIdDirectory(pluginId: String): File = File(root, pluginId)

    private fun versionDirectory(pluginId: String, version: String): File = File(pluginIdDirectory(pluginId), version)

    private fun requireValidId(pluginId: String) {
        require(ID_REGEX.matches(pluginId)) { "La identificación del complemento es ilegal: $pluginId" }
    }

    private fun requireValidVersion(version: String) {
        require(PluginVersion.parse(version) != null) { "Número de versión ilegal: $version" }
    }

    companion object {
        private const val TAG = "PluginVersionStore"

        /** De forma predeterminada, se conservan las 3 versiones más recientes, lo que es suficiente para una reversión y no crecerá ilimitadamente. */
        const val DEFAULT_MAX_RETAINED = 3

        private const val META_FILE = "meta.json"
        private const val FILES_DIR = "files"
        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
    }
}
