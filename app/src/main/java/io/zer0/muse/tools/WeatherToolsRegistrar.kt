package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P1-3b Desdominio: Registrador de herramientas meteorológicas.
 *
 * get_weather() extraído de ToolRegistry.ktwttr.in consulta),
 * Solo se basa en recursos de cadenas de contexto y bibliotecas de red estándar.
 */
class WeatherToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_weather",
                description = "Obtener información meteorológica. Consulta el clima actual, temperatura, humedad, velocidad del viento, etc. por nombre de ciudad.",
                parameters = mapOf("location" to "Requerido,nombre de la ciudad,Soporte chino e inglés,Como Beijing/Shanghai/London"),
                required = setOf("location"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val location = args["location"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_missing_param_location)
            resultOf {
                val encoded = java.net.URLEncoder.encode(location, "UTF-8")
                val url = java.net.URL("https://wttr.in/$encoded?format=j1")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code != 200) return@resultOf context.getString(R.string.tool_weather_api_error, code)
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val root = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(body).jsonObject
                val current = root["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@resultOf context.getString(R.string.tool_weather_no_data, location)
                val temp = current["temp_C"]?.jsonPrimitive?.content ?: "?"
                val desc = current["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("value")?.jsonPrimitive?.content ?: "?"
                val humidity = current["humidity"]?.jsonPrimitive?.content ?: "?"
                val windSpeed = current["windspeedKmph"]?.jsonPrimitive?.content ?: "?"
                val feelsLike = current["FeelsLikeC"]?.jsonPrimitive?.content ?: "?"
                val area = root["nearest_area"]?.jsonArray?.firstOrNull()?.jsonObject
                val areaName = area?.get("areaName")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("value")?.jsonPrimitive?.content ?: location
                context.getString(
                    R.string.tool_weather_result,
                    areaName, desc, temp, feelsLike, humidity, windSpeed,
                )
            }.onError { msg, _ -> Logger.w("WeatherTools", "No se pudo verificar el clima: $msg") }
                .getOrNull() ?: context.getString(R.string.tool_weather_failed, location)
        }
    }
}
