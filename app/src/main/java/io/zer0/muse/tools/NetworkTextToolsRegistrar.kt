package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * P1-3b Desterritorialización: Red/Registrador de herramientas de texto.
 *
 * Extraído de ToolRegistry.kt ping_host / dns_lookup / get_public_ip / json_pretty / generate_password,
 * Confíe únicamente en recursos de cadenas de contexto y redes estándar/Biblioteca de serialización.
 */
class NetworkTextToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "ping_host",
                description = "Ping nombre de dominio especificado o IP,Probar la accesibilidad de la red.",
                parameters = mapOf(
                    "host" to "Requerido,nombre de dominio de destino o IP,Como por ejemplo baidu.com",
                    "timeout_ms" to "Opcional,Tiempo de espera en milisegundos,Predeterminado 3000",
                ),
                required = setOf("host"),
                category = "built-in",
                parameterTypes = mapOf("timeout_ms" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val host = args["host"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_url_missing)
            val timeout = args["timeout_ms"]?.toIntOrNull()?.coerceAtLeast(100) ?: 3000
            resultOf {
                withContext(Dispatchers.IO) {
                    val address = java.net.InetAddress.getByName(host)
                    val reachable = address.isReachable(timeout)
                    context.getString(
                        R.string.tool_ping_host_result,
                        host,
                        address.hostAddress ?: "unknown",
                        if (reachable) context.getString(R.string.tool_yes) else context.getString(R.string.tool_no),
                        "$timeout",
                    )
                }
            }.onError { msg, _ -> Logger.w("NetworkTools", "Ping falló: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_ping_host_failed, host)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "dns_lookup",
                description = "Resolver todas las direcciones IP correspondientes al nombre de dominio.(A grabar).",
                parameters = mapOf("host" to "Requerido,nombre de dominio de destino,Como por ejemplo google.com"),
                required = setOf("host"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val host = args["host"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                withContext(Dispatchers.IO) {
                    val addresses = java.net.InetAddress.getAllByName(host)
                    val ips = addresses.mapNotNull { it.hostAddress }
                    if (ips.isEmpty()) {
                        context.getString(R.string.tool_dns_lookup_empty, host)
                    } else {
                        context.getString(R.string.tool_dns_lookup_result, host, ips.size, ips.joinToString("\n"))
                    }
                }
            }.onError { msg, _ -> Logger.w("NetworkTools", "DNS El análisis falló: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_dns_lookup_failed, host)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_public_ip",
                description = "Obtenga la dirección IP pública del dispositivo actual.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            resultOf {
                withContext(Dispatchers.IO) {
                    val endpoints = listOf(
                        "https://api.ipify.org",
                        "https://checkip.amazonaws.com",
                    )
                    for (endpoint in endpoints) {
                        try {
                            val url = java.net.URL(endpoint)
                            val ip = url.openStream().bufferedReader(Charsets.UTF_8).use { it.readLine()?.trim() }
                            if (!ip.isNullOrBlank()) {
                                return@withContext context.getString(R.string.tool_public_ip_result, ip)
                            }
                        } catch (_: Exception) { /* try next */ }
                    }
                    context.getString(R.string.tool_public_ip_failed)
                }
            }.onError { msg, _ -> Logger.w("NetworkTools", "No se pudo obtener la IP pública: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_public_ip_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "json_pretty",
                description = "Formatee la cadena JSON en un formato legible por humanos(embellecer/Compresión).",
                parameters = mapOf(
                    "json" to "Requerido,La cadena JSON a formatear",
                    "indent" to "Opcional,true/false Ya sea para expandir la sangría,Predeterminado true",
                ),
                required = setOf("json"),
                category = "built-in",
                parameterTypes = mapOf("indent" to "boolean"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val input = args["json"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_url_missing)
            val indent = args["indent"]?.equals("true", ignoreCase = true) ?: true
            resultOf {
                val prettyJson = Json {
                    prettyPrint = indent
                    ignoreUnknownKeys = true
                }
                val element = Json.parseToJsonElement(input)
                prettyJson.encodeToString(JsonElement.serializer(), element)
            }.onError { msg, _ -> Logger.w("NetworkTools", "JSON Error de formato: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_json_pretty_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_password",
                description = "Genera una contraseña aleatoria. Puede especificar la longitud y si contiene letras mayúsculas y minúsculas, números y símbolos.",
                parameters = mapOf(
                    "length" to "Opcional,Longitud de la contraseña,Predeterminado 16,alcance 4-64",
                    "uppercase" to "Opcional,true/false Contiene letras mayúsculas,Predeterminado true",
                    "lowercase" to "Opcional,true/false Contiene letras minúsculas,Predeterminado true",
                    "digits" to "Opcional,true/false Contiene números,Predeterminado true",
                    "symbols" to "Opcional,true/false Contiene símbolos especiales,Predeterminado true",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("length" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val length = args["length"]?.toIntOrNull()?.coerceIn(4, 64) ?: 16
            val useUpper = args["uppercase"]?.equals("false", ignoreCase = true) != true
            val useLower = args["lowercase"]?.equals("false", ignoreCase = true) != true
            val useDigits = args["digits"]?.equals("false", ignoreCase = true) != true
            val useSymbols = args["symbols"]?.equals("false", ignoreCase = true) != true
            val pool = buildString {
                if (useUpper) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                if (useLower) append("abcdefghijklmnopqrstuvwxyz")
                if (useDigits) append("0123456789")
                if (useSymbols) append("!@#$%^&*()-_=+[]{}|;:,.<>?")
            }
            if (pool.isEmpty()) return@register context.getString(R.string.tool_password_empty_pool)
            val random = java.security.SecureRandom()
            val password = CharArray(length) { pool[random.nextInt(pool.length)] }.concatToString()
            context.getString(R.string.tool_password_result, length, password)
        }
    }
}
