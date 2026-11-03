package io.zer0.muse.tools

import io.zer0.memory.pin.PinnedMemoryStore

/**
 * pin_memory Herramientas.
 *
 * Permita que la IA fije información importante en la parte superior,Guárdelo en la palabra de aviso del sistema.
 * Regístrese dinámicamente en ToolRegistry a través de AgentToolsRegistrar.
 */
object PinMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "pin_memory",
        description = "Fijar información importante en la parte superior,Mantenlo en la conversación." +
            "Cuando el usuario pide explícitamente recordar algo de forma permanente,o cuando no se debe olvidar un hecho clave.",
        parameters = mapOf(
            "content" to "Required. The content to pin (concise, one sentence).",
        ),
        required = setOf("content"),
        category = "built-in",
        // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— escritura de memoria a largo plazo,sensible a la privacidad
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, store: PinnedMemoryStore, assistantId: String? = null): String {
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (content.isEmpty()) return "Error: content cannot be empty."
        val id = store.add(content, assistantId = assistantId)
        return if (id.isEmpty()) {
            "Error: failed to pin content."
        } else {
            "Pinned successfully (id: ${id.take(8)}...). The information will remain visible in future conversations with this assistant."
        }
    }
}
