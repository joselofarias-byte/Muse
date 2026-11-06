package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.plugin.AuthoredPluginRequest
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Asistente de implementación de herramienta de complemento autoescrita (author_plugin).
 *
 * Esta herramienta es el único puente entre el "código de salida del modelo" y la "decisión de confianza del usuario": texto de envío del modelo,
 * El host crea el ZIP y solo descarta la biblioteca como un borrador sin firmar (deshabilitado, no confirmado, no ejecutable); para ser verdaderamente eficaz,
 * El usuario debe configurar→Después de revisar la lista de herramientas en la administración de complementos, haga clic en "Firmar y activar" para firmarla con la clave de autor local.
 *
 * Siempre regresa en caso de fracaso `{"error": "razón china"}`, no lance excepciones a la capa de orquestación.
 */
class PluginAuthoringToolsImpl(
    private val context: Context,
    private val pluginManager: PluginManager?,
) {

    suspend fun authorPlugin(args: Map<String, String>): String {
        val manager = pluginManager
            ?: return failure(context.getString(R.string.plugin_author_not_configured))
        val tools = parseTools(args["tools"])
            ?: return failure(context.getString(R.string.plugin_author_invalid_tools))
        val capabilities = parseCapabilities(args["capabilities"])
            ?: return failure(context.getString(R.string.plugin_author_invalid_capabilities))
        val request = AuthoredPluginRequest(
            id = args["id"]?.trim().orEmpty(),
            name = args["name"]?.trim().orEmpty(),
            description = args["description"]?.trim().orEmpty(),
            version = args["version"]?.trim().orEmpty(),
            code = args["code"].orEmpty(),
            tools = tools,
            capabilities = capabilities,
        )
        return manager.createAuthoredDraft(request).fold(
            onSuccess = { draft ->
                context.getString(
                    R.string.plugin_author_draft_created,
                    draft.name,
                    draft.id,
                    draft.version,
                    draft.tools.joinToString(", ") { it.name },
                )
            },
            onFailure = { error ->
                Logger.w("PluginAuthoring", "author_plugin Error al crear el borrador: ${error.message}", error)
                failure(error.message ?: context.getString(R.string.skill_unknown_error))
            },
        )
    }

    /** Analizar la variedad de herramientas enviadas por el modelo; si la forma es ilegal, devuelva nulo (la persona que llama dará un mensaje chino unificado). */
    private fun parseTools(raw: String?): List<ToolDeclaration>? {
        if (raw.isNullOrBlank()) return null
        return resultOf {
            AppJson.decodeFromString(JsonArray.serializer(), raw).map { element ->
                val tool = element as? JsonObject ?: error("El elemento de herramienta debe ser un objeto.")
                ToolDeclaration(
                    name = tool.stringField("name"),
                    description = tool.stringField("description"),
                    parametersJson = tool.stringField("parametersJson"),
                    requiredJson = tool.stringField("requiredJson"),
                    functionName = tool.stringField("functionName"),
                )
            }
        }.getOrNull()
    }

    /** Una variedad de capacidades de análisis; De forma predeterminada, las capacidades no se declaran y los elementos que no son cadenas se consideran ilegales. */
    private fun parseCapabilities(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return emptyList()
        return resultOf {
            AppJson.decodeFromString(JsonArray.serializer(), raw).map { element ->
                element.jsonPrimitive.content
            }
        }.getOrNull()
    }

    /** Lea el campo de cadena en el objeto; sin cadena/Los valores faltantes se tratarán como cadenas vacías y se entregarán a la capa de reglas para dar una razón legible. */
    private fun JsonObject.stringField(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()

    private fun failure(reason: String): String = buildJsonObject {
        put("error", reason)
    }.toString()
}
