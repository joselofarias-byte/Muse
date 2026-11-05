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

/** Consulta únicamente las versiones publicadas por JolufaSoft en GitHub. */
class UpdateChecker(
    client: OkHttpClient,
    repo: String = DEFAULT_REPO,
    private val supportedAbis: () -> List<String> = { android.os.Build.SUPPORTED_ABIS.toList() },
) {

    init {
        require(repo == DEFAULT_REPO) { "Updates must come from $DEFAULT_REPO" }
    }

    private val apiClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

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

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        fetchRelease("github", OFFICIAL_LATEST_URL)
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
        if (!STABLE_VERSION_REGEX.matches(tagName) ||
            json["draft"]?.jsonPrimitive?.contentOrNull == "true" ||
            json["prerelease"]?.jsonPrimitive?.contentOrNull == "true"
        ) return Result.Error("Versión no compatible")
        val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (htmlUrl != "https://github.com/$DEFAULT_REPO/releases/tag/$tagName") {
            return Result.Error("Procedencia de versión no válida")
        }
        val assets = json["assets"]?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.mapNotNull(::parseApkAsset)
            .orEmpty()
            .filter { asset ->
                URI(asset.downloadUrl).path == "/$DEFAULT_REPO/releases/download/$tagName/${asset.name}" &&
                    APK_VERSION_REGEX.find(asset.name)?.groupValues?.get(1) == tagName.trimStart('v', 'V') &&
                    isCompatibleApkAsset(asset, supportedAbis())
            }
        if (assets.isEmpty()) return Result.Error("No hay APK compatible con este dispositivo")
        return Result.Success(
            ReleaseInfo(
                tagName = tagName,
                name = json["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                htmlUrl = htmlUrl,
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
        val validUrl = url?.takeIf { isTrustedDownloadUrl(it) }
        // El resumen que falta es compatible con versiones anteriores; Siempre que se proporcione un resumen, debe ser SHA-256 legal.
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
        val call = apiClient.newCall(request)
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

        const val DEFAULT_REPO = "joselofarias-byte/Muse"
        const val OFFICIAL_LATEST_URL = "https://api.github.com/repos/$DEFAULT_REPO/releases/latest"
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val STABLE_VERSION_REGEX = Regex("^[vV]?[0-9]+(?:\\.[0-9]+){1,3}$")
        private val ASSET_PATH_REGEX = Regex(
            "^/$DEFAULT_REPO/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.apk$",
            RegexOption.IGNORE_CASE,
        )
        private val APK_VERSION_REGEX = Regex("(?:^|[_-])[vV]?([0-9]+(?:\\.[0-9]+){1,3})(?=[_-]|\\.apk$)")
        private val APK_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /** Una URL genérica de GitHub no demuestra procedencia del fork. */
        fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
            val parsed = URI(url)
            parsed.scheme == "https" && parsed.host == "github.com" &&
                parsed.userInfo == null && parsed.port in setOf(-1, 443) &&
                parsed.rawQuery == null && parsed.rawFragment == null &&
                parsed.rawPath == parsed.path && ASSET_PATH_REGEX.matches(parsed.path) &&
                parsed.path.startsWith("/$DEFAULT_REPO/releases/download/")
        }.getOrDefault(false)

        fun isTrustedApkAsset(asset: ApkAsset): Boolean =
            isTrustedDownloadUrl(asset.downloadUrl) &&
                URI(asset.downloadUrl).path.substringAfterLast('/') == asset.name &&
                asset.size > 0 && (asset.sha256 == null || SHA256_REGEX.matches(asset.sha256))

        fun isCompatibleApkAsset(asset: ApkAsset, supportedAbis: List<String>): Boolean {
            if (!isTrustedApkAsset(asset)) return false
            val abi = APK_ABIS.firstOrNull { asset.name.endsWith("_$it.apk") } ?: return false
            return abi in supportedAbis
        }

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
