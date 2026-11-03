package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.R

/**
 * P1-3b Desdominio: registrador de herramientas del portapapeles.
 *
 * clipboard_read / clipboard_write extraído de ToolRegistry.kt,
 * Solo depende del contexto y de los servicios del portapapeles del sistema.
 */
class ClipboardToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "clipboard_read",
                description = "Lea el contenido de texto del portapapeles del sistema.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) {
            readClipboard().content
        }
        // Se prefiere la versión de resultados estructurados (registerOutcome los canales tienen prioridad sobre los canales de cadena)
        toolRegistry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "clipboard_read",
                description = "Lea el contenido de texto del portapapeles del sistema.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) {
            readClipboard()
        }
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "clipboard_write",
                description = "Escriba texto en el portapapeles del sistema.",
                parameters = mapOf(
                    "text" to "Requerido,Texto que se escribirá en el portapapeles.",
                    "label" to "Opcional,etiqueta del portapapeles,Como por ejemplo muse_copy",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val text = args["text"] ?: return@register context.getString(R.string.tool_missing_param_text)
            val label = (args["label"]?.takeIf { it.isNotBlank() } ?: "muse_tool").take(100)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
            context.getString(R.string.tool_clipboard_written, text.length)
        }
    }

    private suspend fun readClipboard(): ToolOutcome {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val content = if (text.isBlank()) {
            context.getString(R.string.tool_clipboard_empty)
        } else {
            context.getString(R.string.tool_clipboard_content, text)
        }
        return ToolOutcome.ok(
            content,
            details = mapOf(
                "hasText" to text.isNotBlank(),
                "length" to text.length,
            ),
        )
    }
}
