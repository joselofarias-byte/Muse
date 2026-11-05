package io.zer0.muse.update

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Comprobador de actualizaciones de aplicaciones — Obtenga la última versión de lanzamiento.
 *
 * Puntos finales (pruebe en orden, use el siguiente solo si el anterior falla):
 *  1. `https://museai.ltd/api/latest.json` —— Imagen de construcción propia, la estructura se convierte desde GitHub Release,
 *     **Sin límite actual**;Los campos son consistentes con GitHub (faltan `body`/`digest` procesado por defecto).
 *  2. `https://api.github.com/repos/{repo}/releases/latest` —— Llega al fondo de esto.
 *
 * ¿Por qué debemos anteponer las imágenes creadas por nosotros mismos?GitHub si"No certificado"Las solicitudes están limitadas a 60 solicitudes por hora y se calculan por IP.
 * La IP de salida de la red celular suele ser compartida por una gran cantidad de dispositivos. Cuando la cuota esté llena, se verificará y actualizará toda la IP. 403
 * (Encabezado de respuesta X-RateLimit-Remaining: 0). Este fallo no tiene nada que ver con el comportamiento del propio usuario y sólo se puede solucionar cambiando el punto final.
 *
 * Instrucciones de implementación:
 *  - Las solicitudes de red se ejecutan en el hilo IO.,La corrutina se puede cancelar.(suspendCancellableCoroutine + enqueue)
 *  - No se introducen nuevas dependencias.,Reutilizar proyectos existentes OkHttp + kotlinx.serialization
 *  - GitHub Requisitos obligatorios del terminal User-Agent header,De lo contrario 403
 *
 * @param client OkHttpClient(Inyectado por la persona que llama,Generalmente reutilizado named("chat") singleton)
 * @param repo GitHub identificación del almacén,Formato "owner/name"
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val repo: String = DEFAULT_REPO,
) {

    /** Punto final espejo autoconstruido (preferido, sin límite actual). */
    private val officialApiUrl: String get() = OFFICIAL_LATEST_URL

    /** GitHub Releases API Punto final (abajo). */
    private val githubApiUrl: String
        get() = "https://api.github.com/repos/$repo/releases/latest"

    /**
     * GitHub Release Recursos(APK Archivo).
     *
     * @param name nombre de archivo(Como por ejemplo "muse-v1.133.apk")
     * @param downloadUrl Enlace directo de descarga del navegador(browser_download_url)
     * @param size bytes de archivo
     * @param sha256 GitHub Release Resumen de activos(Compatible con antiguo cuando falta Release)
     */
    @Serializable
    data class ApkAsset(
        val name: String,
        val downloadUrl: String,
        val size: Long,
        val sha256: String? = null,
    )

    /**
     * GitHub Release información.
     *
     * @param tagName etiqueta de versión(Como por ejemplo "v1.132")
     * @param name release Título
     * @param body release notes(markdown Texto)
     * @param htmlUrl release Página URL(usado para"Ver detalles")
     * @param publishedAt Marca de tiempo de publicación(milisegundos)
     * @param apkAssets Lista de recursos que terminan en .apk(Filtrado)
     */
    @Serializable
    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val publishedAt: Long,
        val apkAssets: List<ApkAsset>,
    )

    /**
     * Consulta asincrónicamente la última versión: prueba cada punto final en secuencia y se devolverá el primero que tenga éxito.
     *
     * La primera opción es crear una imagen autoconstruida (sin límite actual) y recurrir a la API de GitHub después de una falla. Se devuelve un error sólo si todos fallan.
     *
     * @return Regresar exitosamente [Result.Success] llevar [ReleaseInfo];
     *         falló(red/error de análisis)Regresar [Result.Error]
     */
    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        val attempts = listOf("github" to githubApiUrl)
        var lastError: Result.Error = Result.Error("network error")
        for ((label, url) in attempts) {
            when (val outcome = fetchRelease(label, url)) {
                is Result.Success -> return@withContext outcome
                is Result.Error -> {
                    Logger.w(TAG, "check latest release failed via $label: ${outcome.message}")
                    lastError = outcome
                }
            }
        }
        lastError
    }

    /**
     * Recuperación y análisis de un único punto final; cualquier falla converge a [Result.Error].
     *
     * `internal`(en lugar de privado) para que la prueba pueda alimentar directamente la dirección de MockWebServer y verificar"Recuperar + analizar"Esta capa.
     */
    internal suspend fun fetchRelease(label: String, url: String): Result<ReleaseInfo> {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return resultOf {
            exec(req).use { resp ->
                if (!resp.isSuccessful) {
                    // 403 Suele ser una limitación actual anónima de GitHub (X-RateLimit-Remaining: 0)
                    Logger.w(TAG, "$label API failed: HTTP ${resp.code}")
                    return@use Result.Error("HTTP ${resp.code}")
                }
                val bodyText = resp.body.string()
                if (bodyText.isBlank()) {
                    return@use Result.Error("response is empty")
                }
                parseReleaseInfo(bodyText)
            }
        }.onError { msg, t ->
            Logger.e(TAG, "Error en la búsqueda de actualizaciones($label): ${t?.message ?: msg}", t)
        }.getOrNull() ?: Result.Error("network error")
    }

    private fun parseReleaseInfo(bodyText: String): Result<ReleaseInfo> {
        val json = AppJson.parseToJsonElement(bodyText).jsonObject
        val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull
            ?: return Result.Error("failed to parse tag_name")
        val assets = json["assets"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.mapNotNull(::parseApkAsset)
            .orEmpty()
        return Result.Success(
            ReleaseInfo(
                tagName = tagName,
                name = json["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                publishedAt = parseIso8601ToMillis(
                    json["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ),
                apkAssets = assets,
            ),
        )
    }

    private fun parseApkAsset(obj: kotlinx.serialization.json.JsonObject): ApkAsset? {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
        val digest = obj["digest"]?.jsonPrimitive?.contentOrNull
        val sha256 = digest?.let { raw ->
            raw.takeIf { it.startsWith("sha256:", ignoreCase = true) || ':' !in it }
                ?.let { value -> if (':' in value) value.substringAfter(':') else value }
        }
        val validName = name?.takeIf { it.endsWith(".apk", ignoreCase = true) }
        val validUrl = url?.takeIf { isTrustedDownloadUrl(it) && isForkReleaseAssetUrl(it) }
        // El resumen que falta es compatible con versiones anteriores; Siempre que el resumen sea proporcionado por upstream, debe ser SHA-256 legal.
        val validDigest = digest == null || (sha256 != null && SHA256_REGEX.matches(sha256))
        return if (validName != null && validUrl != null && validDigest) {
            ApkAsset(
                name = validName,
                downloadUrl = validUrl,
                size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                sha256 = sha256?.lowercase(),
            )
        } else {
            null
        }
    }

    /**
     * Ejecución HTTP cancelable de rutina — Implementación de ProviderHttpSupport/ImageService por ai/módulo.
     *
     * Utilice suspendCancellableCoroutine + poner en cola en lugar de sincronización [Call.execute]:
     *  - Se llama cuando se cancela la corrutina. [Call.cancel],Interrumpir llamadas de red bloqueadas
     *  - No ocupes el hilo esperando respuesta.(enqueue Devolución de llamada por hilo del programador OkHttp)
     */
    private suspend fun exec(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { resultOf { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /**
     * Analizar cadena ISO8601(Como por ejemplo "2025-01-01T00:00:00Z")es la marca de tiempo en milisegundos.
     * Devuelve 0 si falla el análisis.
     */
    private fun parseIso8601ToMillis(value: String): Long {
        if (value.isBlank()) return 0L
        return resultOf {
            // Analizar con java.time(Proyecto minSdk=26 Ya apoyado java.time,API 26+ Incorporado)
            // Compatible con formas como "2025-01-01T00:00:00Z" con "2025-01-01T00:00:00.000Z"
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrNull() ?: 0L
    }

    companion object {
        private const val TAG = "UpdateChecker"

        /** User-Agent(GitHub API Requisitos obligatorios,De lo contrario 403). */
        private const val USER_AGENT = "muse-android"

        /**
         * Punto final espejo autoconstruido: el servidor del sitio web oficial sincroniza la instantánea de la versión de GitHub cada 30 minutos.
         *
         * Campos con GitHub `releases/latest` alinear (`tag_name` / `name` / `html_url` /
         * `published_at` / `assets[].browser_download_url`); sin aguas arriba `body` con `digest` cuando,
         * Botón Aplicar"Predeterminado"Procesamiento (la descripción está vacía, se omite la verificación resumida), lo que no afecta el juicio de versión ni la descarga.
         */
        const val OFFICIAL_LATEST_URL = "https://api.github.com/repos/joselofarias-byte/Muse/releases/latest"
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val TRUSTED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

        /** Permitir solo direcciones de activos HTTPS oficiales de GitHub para evitar el almacenamiento en caché/Los datos ascendentes convierten la entrada de descarga en un salto arbitrario. */
        fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
            val parsed = URI(url)
            parsed.scheme.equals("https", ignoreCase = true) &&
                parsed.userInfo == null &&
                parsed.port in setOf(-1, 443) &&
                parsed.host?.lowercase() in TRUSTED_DOWNLOAD_HOSTS &&
                parsed.path.endsWith(".apk", ignoreCase = true)
        }.getOrDefault(false)

        /** Only accept APK assets published under the JolufaSoft fork. */
        private fun isForkReleaseAssetUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.host.equals("github.com", ignoreCase = true) &&
                uri.path.startsWith("/joselofarias-byte/Muse/releases/download/")
        }.getOrDefault(false)

        /** Compruebe si el recurso de lanzamiento se puede utilizar como entrada de descarga de APK. La falta de sha256 simplemente significa que la versión anterior no tiene resumen. */
        fun isTrustedApkAsset(asset: ApkAsset): Boolean = asset.name.endsWith(".apk", ignoreCase = true) &&
            isTrustedDownloadUrl(asset.downloadUrl) && isForkReleaseAssetUrl(asset.downloadUrl) &&
            (asset.sha256 == null || SHA256_REGEX.matches(asset.sha256))
        /** Repositorio predeterminado de GitHub(owner/name). */
        /** v1.0.55: Corregido al almacén real. Zer0Qing/Muse(Error ortográfico original zer0/muse,404 Siempre se nota que es lo último.). */
        const val DEFAULT_REPO = "joselofarias-byte/Muse"

        /**
         * Comparación de versiones semánticas(apoyo "v1.132" / "1.132" / "1.13.2" formato etc.).
         *
         * @param current Versión actual(se puede traer "v" prefijo)
         * @param latest Versión a comparar(se puede traer "v" prefijo)
         * @return representación de números negativos current < latest;0 significa igual;representación de números positivos current > latest
         */
        fun compareVersions(current: String, latest: String): Int {
            val a = parseVersion(current)
            val b = parseVersion(latest)
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai.compareTo(bi)
            }
            return 0
        }

        /** Analizar los números de versión como una lista de números enteros(Como por ejemplo "v1.132" → [1, 132]);Los segmentos no numéricos se ignoran. */
        private fun parseVersion(version: String): List<Int> {
            val trimmed = version.trim().trimStart('v', 'V')
            return trimmed.split('.').mapNotNull { it.toIntOrNull() }
        }
    }
}
