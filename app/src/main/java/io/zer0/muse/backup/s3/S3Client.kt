package io.zer0.muse.backup.s3

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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 8.9: S3 Cliente de almacenamiento compatible(autoimplementable AWS Signature V4).
 *
 * Admite servicios compatibles con S3(MinIO / R2 / Backblaze B2 / AWS S3),Enviar solicitudes directamente a través de OkHttp,
 * no depende de AWS SDK(0 APK Volumen,conformarse a gap analysis M19 "firma auto implementada" pedido).
 *
 * Operaciones de apoyo:
 *  - PUT object(Subir archivos)
 *  - GET object(Descargar archivo)
 *  - HEAD object(Comprobar existencia)
 *
 * v1.132 Expandir:Nuevo [testConnection] / [upload] (File) / [download] (File) / [list] / [delete],
 * Para que CloudBackupPage llame directamente,Interacción completa con la operación de archivos de copia de seguridad en la nube.
 *
 * Algoritmo de firma: AWS Signature Version 4
 *  - CanonicalRequest = method + uri + query + headers + signed_headers + payload_hash
 *  - StringToSign = algorithm + timestamp + scope + SHA256(CanonicalRequest)
 *  - SigningKey = HMAC-SHA256 cadena(SecretKey → date → region → service → "aws4_request")
 *  - Signature = HMAC-SHA256(SigningKey, StringToSign)
 *  - Authorization: AWS4-HMAC-SHA256 Credential=AK/DATE/REGION/SERVICE/aws4_request, SignedHeaders=..., Signature=...
 *
 * @param endpoint S3 punto final(Como https://s3.amazonaws.com o https://minio.example.com)
 * @param region área(Como por ejemplo us-east-1)
 * @param accessKey Access Key ID
 * @param secretKey Secret Access Key
 * @param client OkHttpClient(Reutilizar named("chat"))
 */
class S3Client(
    private val endpoint: String,
    private val region: String,
    private val accessKey: String,
    private val secretKey: String,
    private val client: OkHttpClient,
) {
    /**
     * Subir objeto(PUT).
     * @param bucket bucket nombre
     * @param key objeto key(Como por ejemplo "muse/backup-2024-01-01.json")
     * @param data bytes de archivo
     * @param contentType MIME Tipo(Predeterminado application/json)
     * @return true éxito;false falló
     */
    fun putObject(bucket: String, key: String, data: ByteArray, contentType: String = "application/json"): Boolean {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex(data)
        val req = buildSignedRequest("PUT", url, bucket, key, payloadHash, data, contentType)
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * Descargar objeto(GET).
     * @param bucket bucket nombre
     * @param key objeto key
     * @return matriz de bytes de archivo;null falló o no existe
     */
    fun getObject(bucket: String, key: String): ByteArray? {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, key, payloadHash, null, null)
        return execute(req, null) { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * Comprobar si el objeto existe(HEAD).
     */
    fun headObject(bucket: String, key: String): Boolean {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("HEAD", url, bucket, key, payloadHash, null, null)
        return execute(req, false) { resp -> resp.isSuccessful }
    }

    // ── v1.132: Coroutineización + Lectura y escritura directa de archivos. + list + delete API ──────────────

    /**
     * v1.132: Pruebe si la conexión está disponible(A la raíz del cubo GET ?max-keys=1).
     * ir [Dispatchers.IO] Evite bloquear a la persona que llama.
     * @param bucket bucket nombre;Si está vacío, use el valor predeterminado de este cliente. bucket(Pasado por la persona que llama)
     * @return true Autenticación pasada y depósito accesible;false Anomalía de la red o error de autenticación
     */
    suspend fun testConnection(bucket: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket?max-keys=1"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, "", payloadHash, null, null)
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: Cargar archivos locales al objeto S3(en forma de corriente PUT,Evite leer archivos grandes en la memoria a la vez).
     * Nota:No se puede precalcular durante la carga por streaming SHA256,uso x-amz-content-sha256=UNSIGNED-PAYLOAD
     * (AWS Degradaciones de firmas admitidas oficialmente,Compatible con todos los servicios compatibles con S3).
     * @param localFile archivo local
     * @param bucket bucket nombre
     * @param key objeto key
     * @return true éxito
     */
    suspend fun upload(localFile: File, bucket: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket/$key"
        val req = buildSignedRequest(
            method = "PUT",
            url = url,
            bucket = bucket,
            key = key,
            payloadHash = UNSIGNED_PAYLOAD,
            body = null,
            contentType = "application/octet-stream",
            bodyFile = localFile,
        )
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /**
     * v1.132: Descargar objetos S3 a archivos locales(Escribir como flujo,Evite archivos grandes OOM).
     * @param bucket bucket nombre
     * @param key objeto key
     * @param localFile archivo de objeto local(será sobrescrito)
     * @return true éxito
     */
    suspend fun download(bucket: String, key: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, key, payloadHash, null, null)
        execute(req, false) { resp ->
            if (!resp.isSuccessful) return@execute false
            val body = resp.body ?: return@execute false
            runCatching {
                localFile.parentFile?.mkdirs()
                localFile.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
                true
            }.getOrElse {
                Logger.w("S3Client", "download Error al escribir en el archivo local: ${it.message}")
                false
            }
        }
    }

    /**
     * v1.132: Enumere los objetos con el prefijo especificado en el depósito(GET ?list-type=2&prefix=...).
     * analizar ListBucketResult XML,Extracto key / size / lastModified.
     * @param bucket bucket nombre
     * @param prefix Prefijo de clave de objeto(Como por ejemplo "muse/")
     * @return lista de objetos;Vacío significa que no hay ningún objeto o que la solicitud falló
     */
    suspend fun list(bucket: String, prefix: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val encodedPrefix = uriEncode(prefix, encodeSlash = true)
        val url = "$endpoint/$bucket?list-type=2&prefix=$encodedPrefix"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("GET", url, bucket, "", payloadHash, null, null)
        execute(req, emptyList()) { resp ->
            if (!resp.isSuccessful) return@execute emptyList()
            val xml = resp.body?.string() ?: return@execute emptyList()
            parseListBucketResult(xml)
        }
    }

    /**
     * v1.132: Eliminar objeto(DELETE).
     * @param bucket bucket nombre
     * @param key objeto key
     * @return true éxito
     */
    suspend fun delete(bucket: String, key: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$endpoint/$bucket/$key"
        val payloadHash = sha256Hex("".toByteArray())
        val req = buildSignedRequest("DELETE", url, bucket, key, payloadHash, null, null)
        execute(req, false) { resp -> resp.isSuccessful }
    }

    /** Cree una solicitud firmada con AWS SigV4. */
    private fun buildSignedRequest(
        method: String,
        url: String,
        bucket: String,
        key: String,
        payloadHash: String,
        body: ByteArray?,
        contentType: String?,
        bodyFile: File? = null,
    ): Request {
        val now = Date()
        val amzDate = formatAmzDate(now)
        val dateStamp = amzDate.substring(0, 8)
        val uri = java.net.URI(url)
        val host = uri.host
        // path + query dividir(Requisitos de especificación de firma canonical uri = path,canonical query = query)
        val rawPath = uri.rawPath ?: "/$bucket/$key"
        val canonicalPath = if (rawPath.isBlank()) "/" else rawPath
        val canonicalQuery = uri.rawQuery ?: ""

        val headers = mutableMapOf(
            "host" to host,
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to payloadHash,
        )
        if (contentType != null) headers["content-type"] = contentType

        val canonicalRequest = buildCanonicalRequest(method, canonicalPath, canonicalQuery, headers, payloadHash)
        val stringToSign = buildStringToSign(amzDate, dateStamp, canonicalRequest)
        val signingKey = deriveSigningKey(dateStamp)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val signedHeaders = headers.keys.sorted().joinToString(";")
        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=$accessKey/$dateStamp/$region/s3/aws4_request, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadHash)
        if (contentType != null) reqBuilder.header("content-type", contentType)
        when (method) {
            "GET" -> reqBuilder.get()
            "HEAD" -> reqBuilder.head()
            "DELETE" -> reqBuilder.delete()
            "PUT" -> {
                if (bodyFile != null) {
                    reqBuilder.put(bodyFile.asRequestBody("application/octet-stream".toMediaType()))
                } else {
                    reqBuilder.put((body ?: ByteArray(0)).toRequestBody(contentType?.toMediaType()))
                }
            }
        }
        return reqBuilder.build()
    }

    /** construir CanonicalRequest(Contiene query string,v1.132 Lista de arreglos/max-keys y otras firmas de solicitud de parámetros). */
    private fun buildCanonicalRequest(
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        payloadHash: String,
    ): String {
        val canonicalUri = uriEncode(path, encodeSlash = false)
        // canonical query: Ordenar por clave,key/value Ambos URI codificados,usar & conectar
        val canonicalQuery = if (query.isBlank()) {
            ""
        } else {
            query.split('&')
                .map { pair ->
                    val idx = pair.indexOf('=')
                    val k = if (idx >= 0) pair.substring(0, idx) else pair
                    val v = if (idx >= 0) pair.substring(idx + 1) else ""
                    uriEncode(k, encodeSlash = true) + "=" + uriEncode(v, encodeSlash = true)
                }
                .sorted()
                .joinToString("&")
        }
        val canonicalHeaders = headers.entries.sortedBy { it.key }
            .joinToString("") { "${it.key.lowercase()}:${it.value.trim()}\n" }
        val signedHeaders = headers.keys.sorted().joinToString(";")
        return "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
    }

    /** Construya StringToSign. */
    private fun buildStringToSign(amzDate: String, dateStamp: String, canonicalRequest: String): String {
        val scope = "$dateStamp/$region/s3/aws4_request"
        val canonicalHash = sha256Hex(canonicalRequest.toByteArray())
        return "AWS4-HMAC-SHA256\n$amzDate\n$scope\n$canonicalHash"
    }

    /** Derivar clave de firma: HMAC cadena(SecretKey → date → region → service → "aws4_request"). */
    private fun deriveSigningKey(dateStamp: String): ByteArray {
        val kSecret = ("AWS4$secretKey").toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp.toByteArray(Charsets.UTF_8))
        val kRegion = hmacSha256(kDate, region.toByteArray(Charsets.UTF_8))
        val kService = hmacSha256(kRegion, "s3".toByteArray(Charsets.UTF_8))
        return hmacSha256(kService, "aws4_request".toByteArray(Charsets.UTF_8))
    }

    /** Ejecute la solicitud y extraiga el resultado usando transform,Retorno del fracaso [defaultValue]. */
    private fun <T> execute(req: Request, defaultValue: T, transform: (okhttp3.Response) -> T): T {
        return resultOf {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w("S3Client", "${req.method} ${req.url} failed: HTTP ${resp.code}")
                }
                transform(resp)
            }
        }.onError { msg, t ->
            Logger.e("S3Client", "S3 request error", t)
        }.getOrNull() ?: defaultValue
    }

    /**
     * v1.132: analizar S3 ListBucketResult XML,Extracto key / size / lastModified.
     * Utilice un análisis regular simple(Evite introducir dependencias de análisis XML);si AWS S3 / MinIO / R2 Prueba superada.
     */
    private fun parseListBucketResult(xml: String): List<RemoteFile> {
        val result = mutableListOf<RemoteFile>()
        val contentsRegex = Regex("(?is)<Contents>(.*?)</Contents>")
        val keyRegex = Regex("(?is)<Key>(.*?)</Key>")
        val sizeRegex = Regex("(?is)<Size>(.*?)</Size>")
        val dateRegex = Regex("(?is)<LastModified>(.*?)</LastModified>")
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val isoFmtNoMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        for (match in contentsRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val key = keyRegex.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val name = key.substringAfterLast('/')
            val size = sizeRegex.find(block)?.groupValues?.get(1)?.trim()?.toLongOrNull() ?: 0L
            val lastModified = dateRegex.find(block)?.groupValues?.get(1)?.trim()?.let { text ->
                runCatching { isoFmt.parse(text)?.time }.recoverCatching { isoFmtNoMs.parse(text)?.time }.getOrNull() ?: 0L
            } ?: 0L
            result.add(RemoteFile(name = name, path = key, size = size, lastModified = lastModified))
        }
        return result
    }

    // ── herramientas de cifrado ──────────────────────────────────────────────────────────

    private fun sha256Hex(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** URI codificación(RFC 3986). */
    private fun uriEncode(input: String, encodeSlash: Boolean = true): String {
        val sb = StringBuilder()
        for (c in input) {
            when {
                c == '/' && !encodeSlash -> sb.append(c)
                c.isLetterOrDigit() || c in "-_.~" -> sb.append(c)
                else -> sb.append("%").append(String.format("%02X", c.code))
            }
        }
        return sb.toString()
    }

    /** Formatear al formato de fecha de AWS(yyyyMMdd'T'HHmmss'Z'). */
    private fun formatAmzDate(date: Date): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(date)
    }

    companion object {
        /** v1.132: bandera de carga útil sin firmar para transmisión PUT(AWS SigV4 Firmas de degradación admitidas oficialmente). */
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    }
}
