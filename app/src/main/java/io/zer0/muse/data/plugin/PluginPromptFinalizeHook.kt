package io.zer0.muse.data.plugin

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.hook.PromptFinalizeEvent
import io.zer0.muse.hook.PromptFinalizeHook
import io.zer0.muse.hook.PromptFinalizeResult
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * v2.x(MVP del sistema Plug-in Hook): conecte la función JS del complemento a la "finalización rápida de palabras"Hook punto.
 *
 * Convención del lado del complemento (main.js):
 * ```js
 * function onPromptFinalize(event) {
 *   // event = { preparedHistory: [ {role, content}, ... ] }
 *   // Regresar { preparedHistory: [...] }(Los elementos se pueden agregar, eliminar o modificar)
 *   return event;
 * }
 * ```
 *
 * Diseño:
 *  - JS se ejecuta cada vez que se llama (la función del complemento puede ser dinámica); el valor predeterminado del motor se utiliza para el tiempo de espera (10s).
 *  - falló/Cuando regresar es ilegal**Preservar la historia original.**(fail-open), no bloquea la generación.
 *  - role Sólo se acepta usuario/asistente/sistema/herramienta; Las entradas con roles desconocidos se procesan como asistente.
 */
internal class PluginPromptFinalizeHook(
    private val context: android.content.Context,
    private val pluginId: String,
    private val manifest: PluginManifest,
    private val entryCode: String,
    private val configJson: String?,
) : PromptFinalizeHook {

    override val id: String = "plugin:$pluginId:prompt_finalize"
    override val priority: Int = 0
    override val enabled: Boolean = true

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override suspend fun beforeFinalizePrompt(event: PromptFinalizeEvent): PromptFinalizeResult {
        val result =
            runCatching { invokeHook(event) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "La ejecución del complemento Prompt_finalize falló(Preservar la historia original.): ${e.message}")
                    return fallback(event)
                }
        if (result is SkillEngineResult.Error) {
            Logger.w(TAG, "Error de finalización de solicitud del complemento(Preservar la historia original.): ${result.message}")
            return fallback(event)
        }
        val raw = (result as SkillEngineResult.Success).valueJson
        val parsed = parseHistory(raw)
            ?: run {
                Logger.d(TAG, "El complemento Prompt_finalize no tiene retorno válido(Preservar la historia original.)")
                return fallback(event)
            }
        if (parsed.isEmpty()) {
            Logger.w(TAG, "El complemento Prompt_finalize devuelve un historial vacío(Preservar la historia original.,contexto de defensa aérea)")
            return fallback(event)
        }
        Logger.d(TAG, "Complemento de la aplicación Prompt_finalize: ${event.preparedHistory.size} → ${parsed.size} Artículo")
        return PromptFinalizeResult(parsed)
    }

    /**
     * Llame al Hook una vez en tiempo de ejecución.
     *
     * JS Mantenga el método de escritura original de "resintonización de eventos en línea":`buildCallScript` Deletrea eventos en guiones,
     * Sin serialización de parámetros, se evitan los problemas de compatibilidad de parámetros de objetos complejos con motores anteriores.
     * Python No hay equipaje histórico y se pasa directamente como una matriz de parámetros.
     */
    private suspend fun invokeHook(event: PromptFinalizeEvent): SkillEngineResult =
        if (PluginFunctionInvoker.isPython(manifest)) {
            PluginFunctionInvoker(context).call(
                manifest = manifest,
                entryCode = entryCode,
                functionName = HOOK_FUNCTION_NAME,
                argsJson = buildPythonArgs(event),
                configJson = configJson,
            )
        } else {
            WebViewSkillEngine().eval(
                buildCallScript(event),
                scopeKey = pluginId,
                pluginConfigJson = configJson,
            )
        }

    /** Python Parámetros de entrada de la versión:`[{"preparedHistory": [{"role":..., "content":...}]}]`. */
    private fun buildPythonArgs(event: PromptFinalizeEvent): String {
        val history = buildJsonArray {
            event.preparedHistory.forEach { msg ->
                add(
                    buildJsonObject {
                        put("role", msg.role.wireName())
                        put("content", msg.content)
                    },
                )
            }
        }
        return buildJsonArray {
            add(buildJsonObject { put("preparedHistory", history) })
        }.toString()
    }

    /** Realizar una llamada JS: cargar el código de entrada → evento de inyección → sintonizar onPromptFinalize → Devolución serializada. */
    private fun buildCallScript(event: PromptFinalizeEvent): String {
        val historyJson = event.preparedHistory.joinToString(",") { msg ->
            val contentJson = AppJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(msg.content))
            """{"role":"${msg.role.wireName()}","content":$contentJson}"""
        }
        return buildString {
            append(entryCode)
            append("\n;(function(){ var __ev = {preparedHistory: [")
            append(historyJson)
            append("]}; var __out;")
            append("try { __out = (typeof onPromptFinalize === 'function') ? onPromptFinalize(__ev) : null; }")
            append("catch (e) { __out = { __error: String(e && e.message || e) }; }")
            append("return JSON.stringify(__out === undefined || __out === null ? null : __out); })();")
        }
    }

    /** Analizar la matriz de historial devuelta por el complemento; si la estructura es ilegal, se devuelve nulo (la persona que llama conserva el historial original). */
    private fun parseHistory(raw: String?): List<UIMessage>? {
        val out = runCatching { AppJson.parseToJsonElement(raw ?: "null") }.getOrNull()
        val arr = (out as? JsonObject)?.get("preparedHistory") as? JsonArray
        return arr
            ?.mapNotNull { it.jsonObject.toUIMessageOrNull() }
            ?.takeIf { mapped -> mapped.isNotEmpty() }
    }

    private fun fallback(event: PromptFinalizeEvent): PromptFinalizeResult = PromptFinalizeResult(event.preparedHistory)

    private fun JsonObject.toUIMessageOrNull(): UIMessage? {
        val role = this["role"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val content = this["content"]?.jsonPrimitive?.contentOrNull
        if (role == null || content == null) return null
        val messageRole = when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "tool" -> MessageRole.TOOL
            else -> MessageRole.ASSISTANT
        }
        return UIMessage(role = messageRole, content = content)
    }

    private fun MessageRole.wireName(): String = when (this) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.SYSTEM -> "system"
        MessageRole.TOOL -> "tool"
    }

    private companion object {
        const val TAG = "PluginHook"

        /** El nombre de la función de enlace debe exportarse mediante la entrada del complemento. */
        const val HOOK_FUNCTION_NAME = "onPromptFinalize"
    }
}
