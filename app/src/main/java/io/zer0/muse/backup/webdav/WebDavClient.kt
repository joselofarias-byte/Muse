package io.zer0.muse.backup.webdav

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.backup.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/**
 * Phase 8.9: WebDAV cliente(Basado en OkHttp).
 *
 * WebDAV es una extensión HTTP,por método específico(PUT/GET/MKCOL/PROPFIND/DELETE)Manipular archivos en la nube.
 * La mayoría de los discos de red(Nutstore / NextCloud / ownCloud / Box / koofr)Soporta protocolo WebDAV.
 *
 * Operaciones de apoyo:
 *  - PUT(Subir archivos)
 *  - GET(Descargar archivo)
 *  - MKCOL(Crear directorio)
 *  - PROPFIND(Comprobar existencia/directorio de lista)
 *  - DELETE(Eliminar archivos)
 *
 * v1.132 Expandir:Nuevo [testConnection] / [upload] (File) / [download] (File) / [list] / [mkdir],
 * Para que CloudBackupPage llame directamente,Interacción completa con la operación de archivos de copia de seguridad en la nube.
 *
 * Certificación: HTTP Basic Auth(username:password codificado como Base64).
 *
 * @param baseUrl WebDAV raíz URL(Como por ejemplo https://dav.jianguoyun.com/dav/)
 * @param username Nombre de usuario
 * @param password Contraseña(Contraseña específica de la aplicación)
 * @param client OkHttpClient(Reutilizar named("chat"))
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient,
) {
    /** Construcción completa URL(baseUrl + ruta relativa). */
    private fun buildUrl(remotePath: String): String {
        val base = baseUrl.trimEnd('/')
        val path = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        return base + path
    }

    /** Cree un encabezado de autenticación básica. */
    private fun authHeader(): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    /**
     * Subir archivos(PUT).
     * @param remotePath camino remoto(Como por ejemplo /muse/backup-2024-01-01.json)
     * @param data bytes de archivo
     * @param contentType MIME Tipo
     * @return true éxito
     */
    fun putFile(remotePath: String, data: ByteArray, contentType: String = "application/json"): Boolean {
        // Asegúrese de que el directorio principal exista(intentarlo MKCOL,Ignorar en caso de fallo — puede que ya exista)
        val parentPath = remotePath.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            mkcol(parentPath)
        }
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .put(data.toRequestBody(contentType.toMediaType()))
            .build()
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * Descargar archivo(GET).
     * @param remotePath camino remoto
     * @return bytes de archivo;null falló
     */
    fun getFile(remotePath: String): ByteArray? {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .get()
            .build()
        return execute(req, null) { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * Crear directorio(MKCOL).
     * @param remotePath ruta del directorio
     * @return true éxito(Existe devuelve falso pero no informa un error)
     */
    fun mkcol(remotePath: String): Boolean {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .method("MKCOL", null)
            .build()
        return execute(req, false) { resp -> resp.isSuccessful || resp.code == 405 }
    }

    /**
     * Comprobar si el archivo existe(PROPFIND Depth: 0).
     */
    fun exists(remotePath: String): Boolean {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody())
            .build()
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * Eliminar archivos(DELETE).
     */
    fun delete(remotePath: String): Boolean {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .delete()
            .build()
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    // ── v1.132: Coroutineización + Lectura y escritura directa de archivos. API(Para uso de CloudBackupPage) ─────

    /**
     * v1.132: Pruebe si la conexión está disponible(Enviar a la URL raíz PROPFIND Depth:0).
     * ir [Dispatchers.IO] Evite bloquear a la persona que llama.
     * @return true La autenticación pasó y el servidor respondió. 2xx;false Anomalía de la red o error de autenticación
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(buildUrl("/"))
            .header("Authorization", authHeader())
            .header("Depth", "0")
            .method("PROPFIND", "".toRequestBody())
            .build()
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: Cargar archivos locales a la ruta remota(en forma de corriente PUT,Evite leer archivos grandes en la memoria a la vez).
     * @param localFile archivo local
     * @param remotePath camino remoto
     * @return true éxito
     */
    suspend fun upload(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        // Asegúrese de que el directorio principal exista(Ignorar errores existentes)
        val parentPath = remotePath.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) mkcol(parentPath)
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .put(localFile.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: Descargar archivos remotos a la ruta local(Escribir como flujo,Evite archivos grandes OOM).
     * @param remotePath camino remoto
     * @param localFile archivo de objeto local(será sobrescrito)
     * @return true éxito
     */
    suspend fun download(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .get()
            .build()
        execute(req, false) { resp ->
            if (!resp.isSuccessful) return@execute false
            val body = resp.body ?: return@execute false
            runCatching {
                localFile.parentFile?.mkdirs()
                localFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                true
            }.getOrElse {
                Logger.w("WebDavClient", "download Error al escribir en el archivo local: ${it.message}")
                false
            }
        }
    }

    /**
     * v1.132: Listar archivos en el directorio remoto(PROPFIND Depth:1,analizar WebDAV multistatus XML).
     * Devolver solo elementos de archivo(Filtrar el directorio en sí y los subdirectorios),suministro [CloudBackupService.listBackups] Usar.
     * @param remotePath directorio remoto
     * @return lista de archivos;Vacío significa que el directorio está vacío o la solicitud falló
     */
    suspend fun list(remotePath: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(buildUrl(remotePath))
            .header("Authorization", authHeader())
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()),
            )
            .build()
        execute(req, emptyList()) { resp ->
            if (!resp.isSuccessful) return@execute emptyList()
            val xml = resp.body?.string() ?: return@execute emptyList()
            parseMultistatus(xml, buildUrl(remotePath))
        }
    }

    /**
     * v1.132: Crear directorio(MKCOL)versión de rutina,Semántica y [mkcol] coherente.
     * Ya existe(405)Considerado un éxito.
     */
    suspend fun mkdir(remotePath: String): Boolean = withContext(Dispatchers.IO) { mkcol(remotePath) }

    // ── Método de herramienta ──────────────────────────────────────────────────────

    /** Ejecute la solicitud y extraiga el resultado usando transform,Retorno del fracaso [defaultValue]. */
    private fun <T> execute(req: Request, defaultValue: T, transform: (okhttp3.Response) -> T): T {
        return resultOf {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("WebDavClient", "${req.method} ${req.url} failed: HTTP ${resp.code}")
                }
                transform(resp)
            }
        }.onError { msg, t ->
            Logger.e("WebDavClient", "WebDAV request error", t)
        }.getOrNull() ?: defaultValue
    }

    /**
     * v1.132: analizar WebDAV PROPFIND multistatus XML,Extraiga el nombre/tamaño/hora de modificación del archivo.
     * Utilice un análisis regular simple(Evite introducir dependencias de análisis XML);Se pasaron las pruebas comunes de NextCloud / Nut Cloud / koofr.
     * Devolver solo elementos de archivo(Filtrar elementos propios que coincidan con la ruta de solicitud,y subdirectorios cuyo tipo de recurso es colección).
     */
    private fun parseMultistatus(xml: String, listUrl: String): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()
        // prensa <D:href> o <d:href> fragmentación(Diferentes servicios tienen diferente capitalización.)
        val responseRegex = Regex("(?is)<(?:D|d):response>(.*?)</(?:D|d):response>")
        val hrefRegex = Regex("(?is)<(?:D|d):href>(.*?)</(?:D|d):href>")
        val collectionRegex = Regex("(?is)<(?:D|d):collection\\s*/>")
        val sizeRegex = Regex("(?is)<(?:D|d):getcontentlength>(.*?)</(?:D|d):getcontentlength>")
        val dateRegex = Regex("(?is)<(?:D|d):getlastmodified>(.*?)</(?:D|d):getlastmodified>")
        val selfPath = java.net.URI(listUrl).path?.trimEnd('/') ?: ""

        for (match in responseRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val href = hrefRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
            // Saltar el directorio en sí(self Cotización)
            val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            if (decoded.trimEnd('/') == selfPath || decoded.trimEnd('/') == "$selfPath/") continue
            // Saltar subdirectorios(collection marca)
            if (collectionRegex.containsMatchIn(block)) continue
            val name = decoded.substringAfterLast('/').ifBlank { continue }
            val size = sizeRegex.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0L
            val lastModified = dateRegex.find(block)?.groupValues?.get(1)?.trim()
                ?.let { parseHttpDate(it) } ?: 0L
            result.add(RemoteFile(name = name, path = decoded, size = size, lastModified = lastModified))
        }
        return result
    }

    /** Analizar fechas HTTP(RFC 1123 / RFC 850 / asctime),Devuelve 0 en caso de error. */
    private fun parseHttpDate(text: String): Long {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (fmt in formats) {
            runCatching {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(text)?.time ?: continue
            }
        }
        return 0L
    }

    /** v1.132: PROPFIND Cuerpo de la solicitud,Solicitar solo displayname / getcontentlength / getlastmodified / resourcetype. */
    private val PROPFIND_BODY: String = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:">
            <D:prop>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getlastmodified/>
            </D:prop>
        </D:propfind>
    """.trimIndent()

    companion object
}
