package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.backup.s3.S3Client
import io.zer0.muse.backup.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 8.9: Servicio de respaldo en la nube(Interfaz unificada S3/WebDAV).
 *
 * Según [CloudBackupConfig.type] Distribuido a [S3Client] o [WebDavClient],
 * proporcionar uploadBackup / downloadBackup / listBackups Tres operaciones centrales.
 *
 * Nombrar archivos de respaldo: muse-backup-yyyyMMdd-HHmmss.json
 *
 * v1.132 Expandir:Nuevo [listBackups] / [downloadBackup] / [deleteBackup] / [testConnection],
 * Permite que CloudBackupPage muestre la lista de copias de seguridad remotas, restaure por versión, elimine versiones antiguas y pruebe conexiones.
 *
 * @param client OkHttpClient(Reutilizar named("chat"))
 */
class CloudBackupService(private val client: OkHttpClient) {

    companion object {
        /** M-004: Se agotó el tiempo de espera de una única operación de red en la nube(milisegundos). */
        private const val NETWORK_TIMEOUT_MS = 30_000L

        /** latest Nombre del archivo de copia de seguridad(downloadLatestBackup / hasBackup / uploadBackupWithLatest Reutilizar). */
        private const val LATEST_FILE_NAME = "muse-backup-latest.json"

        /** v1.132: Prefijo del nombre del archivo de copia de seguridad(Se utiliza con listBackups para filtrar versiones de archivos comprimidos.). */
        private const val BACKUP_FILE_PREFIX = "muse-backup-"

        /** v1.132: listBackups Número máximo de artículos devueltos a la vez(Evitar la explosión del directorio remoto). */
        private const val LIST_MAX_ITEMS = 100
    }

    /** Genere nombres de archivos de respaldo con marca de tiempo. */
    private fun backupFileName(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "muse-backup-${fmt.format(Date())}.json"
    }

    /**
     * L-001: Abstracción unificada del cliente en la nube,Enmascare las diferencias de interfaz S3/WebDAV.
     * Pase [createClient] prensa [CloudBackupConfig.type] estructura,evitar en upload/download/exists
     * Repita el bloque cuando se envíe en cuatro métodos.path Unificado por [resolveRemotePath] analizar gramaticalmente.
     */
    private sealed interface CloudClient {
        fun upload(path: String, data: ByteArray): Boolean
        fun download(path: String): ByteArray?
        fun exists(path: String): Boolean
    }

    private class S3CloudClient(
        private val s3: S3Client,
        private val bucket: String,
    ) : CloudClient {
        override fun upload(path: String, data: ByteArray): Boolean = s3.putObject(bucket, path, data)
        override fun download(path: String): ByteArray? = s3.getObject(bucket, path)
        override fun exists(path: String): Boolean = s3.headObject(bucket, path)
    }

    private class WebDavCloudClient(
        private val dav: WebDavClient,
    ) : CloudClient {
        override fun upload(path: String, data: ByteArray): Boolean = dav.putFile(path, data)
        override fun download(path: String): ByteArray? = dav.getFile(path)
        override fun exists(path: String): Boolean = dav.exists(path)
    }

    /** L-001: Construya el cliente en la nube correspondiente según config.type;No configurado/El tipo desconocido devuelve nulo. */
    private fun createClient(config: CloudBackupConfig): CloudClient? = when (config.type) {
        "s3" -> S3CloudClient(
            S3Client(config.s3Endpoint, config.s3Region, config.s3AccessKey, config.s3SecretKey, client),
            config.s3Bucket,
        )
        "webdav" -> WebDavCloudClient(
            WebDavClient(config.webdavUrl, config.webdavUsername, config.webdavPassword, client),
        )
        else -> null
    }

    /**
     * v1.132: Construya un cliente de rutina según config.type(Llame directamente a S3Client/WebDavClient suspend API).
     * usado para [listBackups] / [downloadBackup] / [deleteBackup] / [testConnection].
     */
    private sealed interface AsyncCloudClient {
        suspend fun list(prefix: String): List<RemoteFile>
        suspend fun download(path: String, localFile: File): Boolean
        suspend fun downloadBytes(path: String): ByteArray?
        suspend fun delete(path: String): Boolean
        suspend fun test(): Boolean
    }

    private class AsyncS3Client(
        private val s3: S3Client,
        private val bucket: String,
    ) : AsyncCloudClient {
        override suspend fun list(prefix: String): List<RemoteFile> = s3.list(bucket, prefix)

        override suspend fun download(path: String, localFile: File): Boolean = s3.download(bucket, path, localFile)

        override suspend fun downloadBytes(path: String): ByteArray? = s3.getObject(bucket, path)

        override suspend fun delete(path: String): Boolean = s3.delete(bucket, path)

        override suspend fun test(): Boolean = s3.testConnection(bucket)
    }

    private class AsyncWebDavClient(
        private val dav: WebDavClient,
    ) : AsyncCloudClient {
        override suspend fun list(prefix: String): List<RemoteFile> = dav.list(prefix)

        override suspend fun download(path: String, localFile: File): Boolean = dav.download(path, localFile)

        override suspend fun downloadBytes(path: String): ByteArray? = dav.getFile(path)

        override suspend fun delete(path: String): Boolean = dav.delete(path)

        override suspend fun test(): Boolean = dav.testConnection()
    }

    /** v1.132: Construya un cliente de rutina según config.type;No configurado/El tipo desconocido devuelve nulo. */
    private fun createAsyncClient(config: CloudBackupConfig): AsyncCloudClient? = when (config.type) {
        "s3" -> AsyncS3Client(
            S3Client(config.s3Endpoint, config.s3Region, config.s3AccessKey, config.s3SecretKey, client),
            config.s3Bucket,
        )
        "webdav" -> AsyncWebDavClient(
            WebDavClient(config.webdavUrl, config.webdavUsername, config.webdavPassword, client),
        )
        else -> null
    }

    /** L-001: Empalme la ruta remota según config.type(s3 usar s3KeyPrefix,webdav usar webdavPath). */
    private fun resolveRemotePath(config: CloudBackupConfig, fileName: String): String = when (config.type) {
        "s3" -> config.s3KeyPrefix.trimEnd('/') + "/" + fileName
        "webdav" -> config.webdavPath.trimEnd('/') + "/" + fileName
        else -> fileName
    }

    /**
     * Sube la copia de seguridad a la nube.
     * @param config Configuración de copia de seguridad en la nube
     * @param data Bytes JSON de copia de seguridad
     * @return true éxito;false Falló o no está configurado
     */
    suspend fun uploadBackup(config: CloudBackupConfig, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            Logger.w("CloudBackupService", "Cloud backup not configured")
            return@withContext false
        }
        val cc = createClient(config) ?: return@withContext false
        val path = resolveRemotePath(config, backupFileName())
        // M-004: Las operaciones de red en la nube añaden un tiempo de espera de 30 segundos,Evite que la rutina se cuelgue durante mucho tiempo en una red débil
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.upload(path, data) } ?: false
    }

    /**
     * Descargar la última copia de seguridad(Ordene por nombre de archivo para obtener el más grande).
     * @param config Configuración de copia de seguridad en la nube
     * @return Bytes JSON de copia de seguridad;null Copia de seguridad fallida o sin copia
     */
    suspend fun downloadLatestBackup(config: CloudBackupConfig): ByteArray? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext null
        // Simplifique la implementación:Descargar el nombre de archivo predeterminado muse-backup-latest.json
        // La implementación completa requiere el directorio de lista PROPFIND/listObjects para obtener la última versión,dejado para la expansión
        val cc = createClient(config) ?: return@withContext null
        val path = resolveRemotePath(config, LATEST_FILE_NAME)
        // M-004: Las operaciones de red en la nube añaden un tiempo de espera de 30 segundos
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.download(path) }
    }

    /**
     * Compruebe si hay un archivo de copia de seguridad en la nube.
     */
    suspend fun hasBackup(config: CloudBackupConfig): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        val cc = createClient(config) ?: return@withContext false
        val path = resolveRemotePath(config, LATEST_FILE_NAME)
        // M-004: Las operaciones de red en la nube añaden un tiempo de espera de 30 segundos
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.exists(path) } ?: false
    }

    /**
     * Subir copia de seguridad(Cargue la versión con marca de tiempo + la última versión al mismo tiempo).
     * latest Versión utilizada para descargarLatestBackup de recuperación rápida.
     */
    suspend fun uploadBackupWithLatest(config: CloudBackupConfig, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        // Cargue primero la versión archivada con marca de tiempo
        val archived = uploadBackup(config, data)
        // Luego sube la última versión.(Portada)
        val cc = createClient(config) ?: return@withContext archived
        val path = resolveRemotePath(config, LATEST_FILE_NAME)
        // M-004: Las operaciones de red en la nube añaden un tiempo de espera de 30 segundos
        val ok2 = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { cc.upload(path, data) } ?: false
        archived || ok2
    }

    // ── v1.132: Listar / Restaurar por versión / Eliminar / Probar conexión ───────────────────────

    /**
     * v1.132: Enumerar todas las copias de seguridad de archivos remotos(en orden cronológico inverso,último primero).
     * Pase PROPFIND(WebDAV)o ListObjectsV2(S3)Extraiga todos los archivos en el directorio de prefijo,
     * filtrar [BACKUP_FILE_PREFIX] Versión archivada que comienza con(Excluir el último archivo de puntero),
     * Regresar como máximo [LIST_MAX_ITEMS] El artículo previene la explosión de directorios remotos.
     *
     * @param config Configuración de copia de seguridad en la nube
     * @return Lista de respaldo remoto;config Devuelve una lista vacía si no está configurada o la solicitud falla.
     */
    suspend fun listBackups(config: CloudBackupConfig): List<RemoteBackup> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext emptyList()
        val acc = createAsyncClient(config) ?: return@withContext emptyList()
        // list Pasar el prefijo del directorio(s3 usar s3KeyPrefix,webdav usar webdavPath)
        val prefix = when (config.type) {
            "s3" -> config.s3KeyPrefix.trimEnd('/') + "/"
            "webdav" -> config.webdavPath.trimEnd('/') + "/"
            else -> return@withContext emptyList()
        }
        val files = withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.list(prefix) } ?: emptyList()
        files
            // sólo mantener muse-backup-*.json Versión archivada(Excluir el último puntero)
            .filter { it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".json") && it.name != LATEST_FILE_NAME }
            // Ordenar por hora de modificación en orden inverso(último primero);Cuando la marca de tiempo es 0, ordene por nombre de archivo en orden descendente.
            .sortedWith(compareByDescending<RemoteFile> { it.lastModified }.thenByDescending { it.name })
            .take(LIST_MAX_ITEMS)
            .map { rf ->
                RemoteBackup(
                    fileName = rf.name,
                    remotePath = rf.path,
                    size = rf.size,
                    lastModified = rf.lastModified,
                )
            }
    }

    /**
     * v1.132: Descargar copia de seguridad del archivo del nombre de archivo especificado(usado para"Restaurar por versión").
     * @param config Configuración de copia de seguridad en la nube
     * @param fileName Nombre del archivo de copia de seguridad(Como por ejemplo muse-backup-20250719-153000.json)
     * @return Bytes JSON de copia de seguridad;null falló o no existe
     */
    suspend fun downloadBackup(config: CloudBackupConfig, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext null
        val acc = createAsyncClient(config) ?: return@withContext null
        val path = resolveRemotePath(config, fileName)
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.downloadBytes(path) }
    }

    /**
     * v1.132: Eliminar la copia de seguridad del archivo del nombre de archivo especificado(usado para"Limpiar versiones antiguas").
     * Nota:No se permite la eliminación del último archivo de puntero,Evite dañar los enlaces de recuperación rápida.
     * @param config Configuración de copia de seguridad en la nube
     * @param fileName Nombre del archivo de copia de seguridad
     * @return true éxito;false Error, no configurado o intento de eliminación latest
     */
    suspend fun deleteBackup(config: CloudBackupConfig, fileName: String): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        if (fileName == LATEST_FILE_NAME) {
            Logger.w("CloudBackupService", "Negarse a eliminar el último archivo de puntero: $fileName")
            return@withContext false
        }
        val acc = createAsyncClient(config) ?: return@withContext false
        val path = resolveRemotePath(config, fileName)
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.delete(path) } ?: false
    }

    /**
     * v1.132: Pruebe si la conexión está disponible(usado para"conexión de prueba"botón).
     * @param config Configuración de copia de seguridad en la nube
     * @return true Autenticación pasada y servidor accesible.;false Anomalía de la red o error de autenticación
     */
    suspend fun testConnection(config: CloudBackupConfig): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext false
        val acc = createAsyncClient(config) ?: return@withContext false
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) { acc.test() } ?: false
    }
}

/**
 * v1.132: Metadatos de archivos remotos(WebDAV / S3 compartir).
 * por [WebDavClient.list] / [S3Client.list] Regresar,por [CloudBackupService.listBackups] mapeado a [RemoteBackup].
 * @param name nombre de archivo(sin camino)
 * @param path camino completo(S3 para key,WebDAV es un camino relativo)
 * @param size Número de bytes;Cuando no se puede obtener, es 0
 * @param lastModified Modificar marca de tiempo(milisegundos);Cuando no se puede obtener, es 0
 */
data class RemoteFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
)

/**
 * v1.132: Metadatos de copia de seguridad remota(Para que CloudBackupPage muestre la lista).
 * @param fileName nombre de archivo(Como por ejemplo muse-backup-20250719-153000.json)
 * @param remotePath Ruta completa remota(S3 para key,WebDAV es un camino relativo)
 * @param size Número de bytes
 * @param lastModified Modificar marca de tiempo(milisegundos);Cuando el servidor no regresa, es 0
 */
data class RemoteBackup(
    val fileName: String,
    val remotePath: String,
    val size: Long,
    val lastModified: Long,
)
