package io.zer0.muse.mcp

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Feishu tenant_access_token(internal) Cliente de intercambio.
 *
 * App Secret Ingrese solo el cuerpo de la solicitud,No se escriben registros ni mensajes de chat; la persona que llama guarda el TAT devuelto en
 * Almacenamiento de tokens MCP cifrados. Vuelve el fallo de red null,Transferencia del cliente MCP al error de conexión/Estado de reconexión.
 */
internal class McpFeishuTenantTokenClient(
    private val httpClient: OkHttpClient = defaultClient,
    private val endpoint: String = TENANT_ACCESS_TOKEN_ENDPOINT,
) {

    /**
     * Utilice la identidad de la aplicación Feishu para intercambiar por tenant_access_token.
     *
     * @param appId aplicación feishu App ID
     * @param appSecret aplicación feishu App Secret
     * @param now hora actual,Se utiliza para pruebas unitarias y calcular tiempos de vencimiento.
     */
    suspend fun fetch(appId: String, appSecret: String, now: Long = System.currentTimeMillis()): McpTokenInfo? =
        withContext(Dispatchers.IO) {
            if (appId.isBlank() || appSecret.isBlank()) return@withContext null

            val body = buildJsonObject {
                put("app_id", appId)
                put("app_secret", appSecret)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(body)
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (!response.isSuccessful) {
                        Logger.w(TAG, "La adquisición de Feishu TAT fracasó: HTTP ${response.code}")
                        return@withContext null
                    }
                    parseTokenResponse(responseBody, now)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.w(TAG, "Excepción de adquisición de Feishu TAT: ${e.message}")
                null
            }
        }

    internal fun parseTokenResponse(body: String, now: Long): McpTokenInfo? {
        val json = runCatching {
            AppJson.decodeFromString(JsonObject.serializer(), body)
        }.getOrNull() ?: return null
        val token = json["tenant_access_token"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        if (token.isBlank()) {
            val code = json["code"]?.jsonPrimitive?.contentOrNull
            val message = json["msg"]?.jsonPrimitive?.contentOrNull
            Logger.w(TAG, "Falta la respuesta Feishu TAT token: code=$code, msg=$message")
            return null
        }
        val expiresInSeconds = json["expire"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toLongOrNull()
            ?: 0L
        return McpTokenInfo(
            accessToken = token,
            expiresAt = if (expiresInSeconds > 0L) {
                now + expiresInSeconds * 1000L
            } else {
                0L
            },
        )
    }

    private companion object {
        const val TAG = "McpFeishuTenantToken"
        const val TENANT_ACCESS_TOKEN_ENDPOINT =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val defaultClient = io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
