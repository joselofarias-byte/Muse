package io.zer0.muse.channel

import io.zer0.common.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Los medios envían comprobaciones de respuesta y cliente HTTP compartido. */
internal object MediaHttp {
    private const val TIMEOUT_SECONDS = 60L

    val client: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    val jsonMediaType: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()

    /** Realice una solicitud y verifique los campos ok/errcode devueltos por la plataforma. */
    fun postAndCheck(req: Request, platform: String) {
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) error("$platform HTTP ${resp.code}: ${text.take(300)}")
            val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            if (obj["ok"]?.jsonPrimitive?.contentOrNull == "false") {
                error("$platform rechazar: ${text.take(200)}")
            }
            val errcode = obj["errcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (errcode != null && errcode != 0) {
                error("$platform falló: errcode=$errcode ${text.take(200)}")
            }
        }
    }
}
