package io.zer0.muse.tools

import io.zer0.ai.core.OAuthConfig
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.connector.ConnectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: Registrador de herramientas de conector — Exponer el conector OAuth autorizado como herramienta LLM.
 *
 *  - [TOOL_LIST_CONNECTORS] Listar los conectores configurados y el estado de autorización;
 *  - [TOOL_CALL_CONNECTOR] Llame a servicios externos con credenciales de conector HTTP API(HIGH,Ir para aprobación):
 *    access_token Viene automáticamente,tiempo vencido [OAuthManager.refreshTokenIfNeeded] Actualizar automáticamente.
 *
 * restricciones de seguridad:La URL de destino debe estar en el mismo dominio registrado que el punto final del token del conector.(Determinación aproximada tomando los dos últimos nombres de dominio.),
 * Evite que access_token se dirija a hosts no relacionados.
 */
class ConnectorToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val connectorStore: ConnectorStore,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_LIST_CONNECTORS,
                description = "Listar conectores OAuth configurados(Autorización de servicio externo)Y su id/nombre/si está autorizado." +
                    "Utilice esta herramienta para obtener el conector_id antes de llamar a servicios externos.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            runBlocking {
                val connectors = connectorStore.load()
                if (connectors.isEmpty()) {
                    "Aún no se han configurado conectores."
                } else {
                    val lines = mutableListOf<String>()
                    for (c in connectors) {
                        val authorized = OAuthManager.getStoredToken("connector_${c.id}") != null
                        lines += "${c.id} | ${c.name.ifBlank { "-" }} | " +
                            if (authorized) "Autorizado" else "No autorizado"
                    }
                    lines.joinToString("\n")
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_CALL_CONNECTOR,
                description = "Llame a un servicio externo con credenciales de conector autorizado HTTP API(Viene automáticamente Bearer token,Actualización automática después del vencimiento)." +
                    "Primero use Connector_list para consultar los conectores disponibles. id;url Debe estar en el mismo dominio que el servicio del conector.",
                parameters = mapOf(
                    "connector_id" to "Requerido,conector id(ver connector_list)",
                    "url" to "Requerido,solicitud completa URL(Como por ejemplo https://api.github.com/user)",
                    "method" to "Opcional,HTTP método:GET(Predeterminado)/POST/PUT/PATCH/DELETE",
                    "body" to "Opcional,Cuerpo de la solicitud(JSON texto,POST/PUT/PATCH usado cuando)",
                ),
                required = setOf("connector_id", "url"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            runBlocking { executeCall(args) }
        }
    }

    private suspend fun executeCall(args: Map<String, String>): String = withContext(Dispatchers.IO) {
        val connectorId = args["connector_id"]?.trim().orEmpty()
        val url = args["url"]?.trim().orEmpty()
        val method = args["method"]?.trim()?.uppercase().orEmpty().ifBlank { "GET" }
        val body = args["body"]
        if (connectorId.isBlank()) return@withContext "desaparecido connector_id"
        if (url.isBlank()) return@withContext "desaparecido url"
        if (method !in ALLOWED_METHODS) return@withContext "Método no compatible: $method"
        val connector = connectorStore.load().firstOrNull { it.id == connectorId }
            ?: return@withContext "El conector no existe: $connectorId"
        // Verificación del mismo dominio:Evitar que se envíen tokens a hosts no relacionados
        if (!sameSite(url, connector.tokenUrl)) {
            return@withContext "rechazar: url Incoherente con el dominio del servicio del conector" +
                "(${hostOf(url) ?: "?"} no pertenece ${hostOf(connector.tokenUrl) ?: "?"} sitio)"
        }
        val oauthConfig = OAuthConfig(
            clientId = connector.clientId,
            clientSecret = connector.clientSecret.takeIf { it.isNotBlank() },
            authorizeUrl = connector.authorizeUrl,
            tokenUrl = connector.tokenUrl,
            redirectUri = "io.zer0.muse://oauth/callback",
            scope = connector.scope,
        )
        val token = OAuthManager.refreshTokenIfNeeded("connector_${connector.id}", oauthConfig)
            .getOrElse { e ->
                return@withContext "El conector no está autorizado o la actualización del token falló: ${e.message}" +
                    "(Por favor configúrelo primero-Autorización completa del conector)"
            }
        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.method(method, (body ?: "").toRequestBody(JSON_MEDIA))
            }
            HTTP.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                formatConnectorResponse(resp.code, text)
            }
        }.getOrElse { e -> "Solicitud fallida: ${e.message}" }
    }

    /** Determinación del mismo dominio registrado:Compara los dos últimos nombres de dominio(github.com / api.github.com → github.com). */
    private fun sameSite(a: String, b: String): Boolean {
        val hostA = hostOf(a) ?: return false
        val hostB = hostOf(b) ?: return false
        return registeredDomain(hostA) == registeredDomain(hostB)
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()
    }.getOrNull()

    private fun registeredDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    companion object {
        /** Listar conectores configurados(solo lectura). */
        const val TOOL_LIST_CONNECTORS = "connector_list"

        /** Llame a servicios externos con credenciales de conector API(Ejecutar después de la aprobación). */
        const val TOOL_CALL_CONNECTOR = "call_connector"

        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val HTTP: OkHttpClient by lazy {
            io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal fun formatConnectorResponse(statusCode: Int, body: String): String = "HTTP $statusCode\n$body"
