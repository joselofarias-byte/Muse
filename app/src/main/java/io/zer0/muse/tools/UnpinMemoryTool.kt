package io.zer0.muse.tools

import io.zer0.memory.pin.PinnedMemoryStore

/**
 * unpin_memory Herramientas.
 *
 * Permite que la IA elimine recuerdos anclados por palabra clave o identificación.
 */
object UnpinMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "unpin_memory",
        description = "Cancelar un recuerdo anclado. El usuario solicitó olvidar el contenido previamente fijado.," +
            "O se usa cuando la información fijada ya no es precisa.",
        parameters = mapOf(
            "keyword" to "Optional. Remove the first pinned memory whose content contains this keyword.",
            "id" to "Optional. Remove by exact memory id prefix.",
        ),
        required = emptySet(),
        category = "built-in",
        // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— cambios en la memoria a largo plazo,sensible a la privacidad
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, store: PinnedMemoryStore, assistantId: String? = null): String {
        val keyword = args["keyword"]?.trim()
        val id = args["id"]?.trim()
        if (keyword.isNullOrEmpty() && id.isNullOrEmpty()) {
            return "Error: provide either 'keyword' or 'id' to identify the memory to unpin."
        }
        val scopedAssistantId = assistantId?.takeIf { it.isNotBlank() }
        val includeGlobal = scopedAssistantId == "default"
        val removed = if (!id.isNullOrEmpty()) {
            // Pruebe una coincidencia exacta de prefijo
            val all = if (scopedAssistantId == null) {
                store.getAll()
            } else {
                store.getForAssistant(scopedAssistantId, includeGlobal)
            }
            val target = all.firstOrNull { it.id.startsWith(id) }
            if (target == null) {
                false
            } else if (scopedAssistantId == null) {
                store.removeById(target.id)
            } else {
                store.removeByIdForAssistant(target.id, scopedAssistantId, includeGlobal)
            }
        } else {
            // v1.131: keyword No esta vacio aqui(Ya arriba guard),Utilice requireNotNull en su lugar !! Hacer los contratos más explícitos,
            // El mal uso arrojará IllegalArgumentException en lugar de NPE,El posicionamiento es más claro.
            requireNotNull(keyword) { "keyword must be non-null when id is empty" }
            if (scopedAssistantId == null) {
                store.removeByKeyword(keyword)
            } else {
                store.removeByContentFlexibleForAssistant(keyword, scopedAssistantId, includeGlobal)
            }
        }
        return if (removed) "Unpinned successfully." else "No matching pinned memory found."
    }
}
