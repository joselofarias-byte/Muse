package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R

/**
 * P1-3b Dedominio: texto/Registrador de herramientas de codificación.
 *
 * Herramientas de cálculo puras extraídas de ToolRegistry.kt (URL / Base64 / Hash / UUID / número aleatorio).
 * Solo depende del contexto (recurso de cadena) y no mantiene el estado interno de ToolRegistry.
 */
class EncodingToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "url_encode",
                description = "Codifica el texto mediante URL.",
                parameters = mapOf("text" to "Requerido,texto para codificar"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(R.string.tool_url_encoded, java.net.URLEncoder.encode(text, "UTF-8"))
            }.onError { msg, _ -> Logger.w("EncodingTools", "URL Error de codificación: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "url_decode",
                description = "Decodificar texto codificado en URL.",
                parameters = mapOf("text" to "Requerido,texto para decodificar"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(R.string.tool_url_decoded, java.net.URLDecoder.decode(text, "UTF-8"))
            }.onError { msg, _ -> Logger.w("EncodingTools", "URL Falló la decodificación: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "base64_encode",
                description = "Base64 codifica el texto.",
                parameters = mapOf("text" to "Requerido,texto para codificar"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                context.getString(
                    R.string.tool_base64_encoded,
                    android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP),
                )
            }.onError { msg, _ -> Logger.w("EncodingTools", "Base64 Error de codificación: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_url_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "base64_decode",
                description = "Decodificar texto Base64.",
                parameters = mapOf("text" to "Requerido,Texto base64 para decodificar"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_url_missing)
            resultOf {
                val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                context.getString(R.string.tool_base64_decoded, String(bytes, Charsets.UTF_8))
            }.onError { msg, _ -> Logger.w("EncodingTools", "Base64 Falló la decodificación: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_base64_failed)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "hash_text",
                description = "Calcular el valor hash del texto. Compatible con MD5,SHA-1,SHA-256,El valor predeterminado es SHA-256.",
                parameters = mapOf(
                    "text" to "Requerido,texto a hash",
                    "algorithm" to "Opcional,MD5/SHA-1/SHA-256,Predeterminado SHA-256",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_hash_missing)
            val algo = args["algorithm"]?.uppercase() ?: "SHA-256"
            if (algo !in setOf("MD5", "SHA-1", "SHA-256")) {
                return@register context.getString(R.string.tool_hash_unsupported, algo)
            }
            resultOf {
                val digest = java.security.MessageDigest.getInstance(algo)
                val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                context.getString(R.string.tool_hash_result, algo, hash)
            }.onError { msg, _ -> Logger.w("EncodingTools", "Error en el cálculo de hash: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_hash_missing)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_uuid",
                description = "generar un azar UUID(identificador único universal).",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            context.getString(R.string.tool_uuid_result, java.util.UUID.randomUUID().toString())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "random_number",
                description = "Generar un número entero aleatorio dentro de un rango específico(Contiene límites).",
                parameters = mapOf(
                    "min" to "Opcional,valor mínimo,Predeterminado 0",
                    "max" to "Opcional,valor máximo,Predeterminado 100",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("min" to "integer", "max" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            var min = args["min"]?.toIntOrNull() ?: 0
            var max = args["max"]?.toIntOrNull() ?: 100
            if (min > max) {
                min = max.also { max = min }
            }
            context.getString(R.string.tool_random_number_result, kotlin.random.Random.nextInt(min, max + 1))
        }
    }
}
