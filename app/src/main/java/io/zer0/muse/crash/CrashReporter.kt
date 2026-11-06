package io.zer0.muse.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import io.zer0.common.Logger
import io.zer0.common.resultOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Salida fija para informes de fallos.
 *
 * ## ¿Por qué ya no dejar que los usuarios elijan?
 *
 * La implementación anterior convertía en "A qué dirección de correo electrónico/a qué webhook enviar" un elemento de configuración, lo que permitía a los usuarios finales completarlo ellos mismos. —— Esto en sí está mal:
 * Los usuarios no saben a quién enviar ni están obligados a configurar la recopilación de fallos para los desarrolladores. En la página de configuración, hay dos cuadros de entrada vacíos más uno
 * La mayoría de las personas se confunden cuando hacen clic en el botón "Guardar".
 *
 * El modelo actual es**un botón**: Haga clic en "Reportar ahora",App Envíe usted mismo los registros al servidor del desarrollador;
 * El correo electrónico se devolverá solo cuando no se pueda acceder al servidor (la dirección de correo electrónico también está escrita en el código y el usuario solo ve la frase "se enviará al desarrollador").
 *
 * ## Límite de privacidad (consistente con la implementación anterior, sin relajación)
 *
 * - El contenido reportado solo incluye: crash stack (insensibilizado), modelo/marca/versión de Android/ABI del dispositivo,App versión, marca de tiempo
 * - no contiene IMEI / Android ID / MAC / Cualquier identificador único, como ubicación/número de cuenta, etc.
 * - Todavía solo ocurre cuando el usuario hace clic en el botón, sin ningún informe silencioso en segundo plano.
 */
object CrashReporterFactory {

    /** La dirección de destinatario única para los informes de fallos (correo electrónico del desarrollador). Los usuarios no necesitan ni deben completar este formulario. */
    const val REPORT_EMAIL = "2119280571@qq.com"

    /**
     * Punto final de recepción del lado del servidor para informes de fallos (museai.ltd autoconstruido).
     *
     * Este es el servidor del desarrollador y no tiene nada que ver con el proxy de retransmisión AI configurado por el usuario.**Debe estar conectado directamente**.
     * Ver detalles [directClient].
     */
    const val REPORT_ENDPOINT = "https://museai.ltd/api/crash-report"

    /**
     * Prepare uno para informar fallos**Sin agente**cliente.
     *
     * Por qué debe construirse por separado: lo que transmite la persona que llama a menudo es `named("chat")` Ese cliente compartido,
     * Está equipado con un ProxySelector dinámico y reenviará todas las solicitudes de acuerdo con el proxy configurado por el usuario. Los usuarios se transfieren para conectarse a la IA
     * Es posible que el proxy configurado no pueda llegar al servidor del desarrollador (o incluso bloquearlo por completo)——
     * Entonces el informe siempre falla, y esto no es lo mismo que“El servidor está caído”Parece exactamente igual desde fuera.
     *
     * Aquí, el proxy se establece explícitamente en NO_PROXY: los enlaces de infraestructura, como los informes de fallos, no deberían verse afectados por la configuración del agente de usuario.
     */
    internal fun directClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .proxyAuthenticator { _, _ -> null }
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Tiempo de espera de envío único del lado del servidor (segundos). Los usuarios no deben quedarse atrapados aquí después de un fallo para poder informarlo. */
    internal const val CONNECT_TIMEOUT_SECONDS = 10L
    internal const val WRITE_TIMEOUT_SECONDS = 15L
    internal const val READ_TIMEOUT_SECONDS = 10L

    /**
     * Informar de un lote bloqueado.
     *
     * Secuencia: primero acceda al servidor (un clic, complete en silencio); devolver la intención del correo electrónico cuando el servidor es inaccesible,
     * Asegúrate de que los clics del usuario no se desperdicien en ambos casos: "Hay Internet pero el servicio no funciona" y "No hay Internet".
     *
     * @return Qué canal se tomó realmente, para que la interfaz muestre la copia exacta del resultado
     */
    suspend fun submit(
        context: Context,
        client: OkHttpClient,
        crashLogs: List<String>,
        metadata: Map<String, String> = buildStandardMetadata(context),
    ): SubmitOutcome {
        if (crashLogs.isEmpty()) return SubmitOutcome.NothingToSend
        val uploaded = runCatching {
            ServerCrashReporter(REPORT_ENDPOINT, directClient(client)).send(crashLogs, metadata)
        }.onFailure { Logger.w(TAG, "Excepción de informes del servidor: ${describeForLog(it)}") }.getOrDefault(false)
        return if (uploaded) {
            SubmitOutcome.Uploaded(crashLogs.size)
        } else {
            // El servidor no está disponible → Correo electrónico para decir la verdad. El destinatario está codificado y el usuario sólo necesita hacer clic en enviar en la aplicación de correo.
            val opened = runCatching {
                EmailCrashReporter(context, REPORT_EMAIL).report(crashLogs.joinToString("\n\n---\n\n"), metadata)
            }.onFailure { Logger.w(TAG, "Error de correo electrónico: ${it.message}") }.isSuccess
            if (opened) SubmitOutcome.MailFallback(crashLogs.size) else SubmitOutcome.Failed
        }
    }

    private const val TAG = "CrashReporterFactory"

    /**
     * Convierta la excepción en una línea diagnosticable.
     *
     * La antigua forma de escribir sólo recuerda `it.message`, y las excepciones de red a menudo el mensaje es nulo (como OkHttp interno
     * `IOException` subcategoría), lo único que queda en el registro es "El servidor informó una excepción.: null"—— Significa que no hay información.
     * Agregue aquí el nombre del tipo y la cadena de causa. Si falla la próxima vez, podrá saber directamente si se trata de DNS, conexión rechazada o tiempo de espera agotado.
     */
    internal fun describeForLog(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4) {
            val msg = cur.message?.takeIf { it.isNotBlank() }
            parts += if (msg != null) "${cur.javaClass.simpleName}: $msg" else cur.javaClass.simpleName
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }
}

/** Los resultados de la implementación de los informes de fallos. */
sealed interface SubmitOutcome {
    /** No hay registros que reportar. */
    data object NothingToSend : SubmitOutcome

    /** Enviado al servidor de desarrollador. */
    data class Uploaded(val count: Int) : SubmitOutcome

    /** No se puede acceder al servidor y en su lugar se ha abierto el correo electrónico (el usuario confirma el envío en la aplicación de correo electrónico). */
    data class MailFallback(val count: Int) : SubmitOutcome

    /** Ambos canales fallaron. */
    data object Failed : SubmitOutcome
}

/**
 * Reportero de fallas de correo electrónico — usar [Intent.ACTION_SEND] Inicie la aplicación de correo y complete previamente los destinatarios/Tema/Texto.
 *
 * Solo se usa cuando el servidor informa que no se puede acceder. Destinatario por [CrashReporterFactory.REPORT_EMAIL] fijo,
 * No lee ninguna configuración de usuario.
 */
class EmailCrashReporter(
    private val context: Context,
    private val recipient: String,
) {
    fun report(crashLog: String, metadata: Map<String, String>) {
        if (recipient.isBlank()) {
            Logger.w(TAG, "El destinatario del correo electrónico está vacío,Saltar informes")
            return
        }
        val body = buildString {
            appendLine("===== Muse informe de accidente =====")
            metadata.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("----- pila de choque -----")
            appendLine(crashLog)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, "Muse informe de accidente ${metadata["timestamp"] ?: ""}")
            putExtra(Intent.EXTRA_TEXT, body)
            // Se debe agregar la actividad inicial desde el contexto de la aplicación. NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // createChooser Devuelve una nueva intención, NEW_TASK en la intención original no se entrega automáticamente,
        // Utilice ShareIntentHelper de forma unificada para agregar indicadores en un contexto que no sea de actividad.
        resultOf {
            io.zer0.muse.util.ShareIntentHelper.startChooserSafely(context, intent, "Seleccione la aplicación de correo para enviar el informe de fallos")
        }.onError { msg, t ->
            Logger.w(TAG, "No se pudo iniciar la aplicación de correo: ${t?.message ?: msg}")
        }
    }

    private companion object {
        private const val TAG = "EmailCrashReporter"
    }
}

/**
 * Reportero de fallos del lado del servidor — POST al punto final de recepción de museai.ltd,JSON formato.
 *
 * Diferencias con la antigua implementación del webhook:URL Corregido (la configuración del usuario ya no se lee),**Devolver resultados sincrónicamente**
 * (La persona que llama debe decidir si desea rebotar el correo electrónico en consecuencia) y el tiempo de espera es más corto.
 *
 * Cuerpo de la solicitud:
 * ```
 * {
 *   "timestamp": "2026-07-23 12:34:56",
 *   "device": { "brand": "...", "model": "...", "android": "...", "abi": "..." },
 *   "app_version": "1.0.11",
 *   "count": 2,
 *   "crash_logs": ["...", "..."]
 * }
 * ```
 */
class ServerCrashReporter(
    private val endpoint: String,
    client: OkHttpClient,
) {
    private val httpClient = client.newBuilder()
        .connectTimeout(CrashReporterFactory.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(CrashReporterFactory.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CrashReporterFactory.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Cargue un lote de registros de fallos.
     *
     * @return true Indica que el servidor acepta (HTTP 2xx);false Indica inalcanzable o rechazado —— En consecuencia, la persona que llama retrocede.
     */
    suspend fun send(crashLogs: List<String>, metadata: Map<String, String>): Boolean {
        if (endpoint.isBlank() || !endpoint.startsWith("http")) {
            Logger.w(TAG, "El punto final reportado es ilegal: $endpoint")
            return false
        }
        return runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .post(buildJsonPayload(crashLogs, metadata).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Logger.i(TAG, "El fallo ha sido informado al servidor. (${crashLogs.size} Artículo)")
                    true
                } else {
                    Logger.w(TAG, "El servidor rechaza el informe de fallos: HTTP ${response.code}")
                    false
                }
            }
        }.onFailure { Logger.w(TAG, "Excepción de informes del servidor: ${CrashReporterFactory.describeForLog(it)}") }.getOrDefault(false)
    }

    /** Cree el cuerpo de la solicitud JSON. Varios registros en un lote utilizan matrices y el servidor los archiva por registro. */
    private fun buildJsonPayload(crashLogs: List<String>, metadata: Map<String, String>): String {
        val json = JSONObject()
        json.put("timestamp", metadata["timestamp"] ?: "")
        val device = JSONObject()
        device.put("brand", metadata["device_brand"] ?: "")
        device.put("model", metadata["device_model"] ?: "")
        device.put("android", metadata["android_version"] ?: "")
        device.put("abi", metadata["device_abi"] ?: "")
        json.put("device", device)
        json.put("app_version", metadata["app_version"] ?: "")
        json.put("count", crashLogs.size)
        json.put("crash_logs", JSONArray(crashLogs))
        return json.toString()
    }

    private companion object {
        private const val TAG = "ServerCrashReporter"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Crear metadatos de fallos estándar — Modelo/Versión de Android/Versión de la aplicación/Marca de tiempo.
 *
 * App La versión se lee a través de PackageManager (sin dependencia de BuildConfig para evitar pérdidas durante la confusión de la versión de lanzamiento).
 */
fun buildStandardMetadata(context: Context): Map<String, String> {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val appVersion = resultOf {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            ).versionName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
    }.getOrNull() ?: "unknown"
    return mapOf(
        "timestamp" to timestamp,
        "device_brand" to Build.BRAND,
        "device_model" to Build.MODEL,
        "android_version" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "device_abi" to Build.SUPPORTED_ABIS.joinToString(","),
        "app_version" to appVersion,
    )
}
