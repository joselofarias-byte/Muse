package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Conjunto de herramientas de autogestión del asistente —— Permitir que el asistente cree/Modificar/Quitar asistente.
 *
 * ## Diseño con permisos restringidos (esta es la más sensible de todas las herramientas)
 *
 * AI Cambiar la configuración del asistente puede crear un circuito cerrado excesivo (por ejemplo, atarse a más herramientas).
 * Por lo tanto, este conjunto de herramientas**Solo abre el subconjunto seguro**:
 *
 *  | Operación | Campos permitidos para ser modificados | Campos que no se permiten modificar |
 *  |---|---|---|
 *  | crear | name, systemPrompt, avatarEmoji | toolIdsJson, skillIdsJson, mcpServerIdsJson, modelId, providerId |
 *  | actualizar | systemPrompt(Redacción de personajes) | toolIdsJson, skillIdsJson, mcpServerIdsJson, modelId, providerId, reasoningLevel |
 *  | Eliminar | asistente completo | El asistente predeterminado no se puede eliminar; el asistente al que pertenece actualmente no se puede eliminar. |
 *
 * No se permiten cambios vinculantes (herramienta/Habilidades/MCP)——Esa es la configuración explícita del usuario en la página de configuración del asistente.
 * AI Las modificaciones conducirán a extralimitarse (darse más herramientas). modelo/Lo mismo se aplica a los proveedores (los cambios pueden generar cargos).
 *
 * denominación unificada `assistant_manage_`.
 */
class AssistantManageTools(
    private val assistantRepository: AssistantRepository,
) {
    companion object {
        private const val TAG = "AssistantManageTools"
        private const val DEFAULT_ASSISTANT_ID = "default"
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerCreate(registry)
        registerUpdatePersona(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_list",
                description = "Listar todos los asistentes (id, nombre, descripción). para modificación/Confirme la identificación del asistente antes de eliminar.",
                parameters = emptyMap(),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            runBlocking {
                val all = assistantRepository.getAll()
                if (all.isEmpty()) {
                    "No hay asistente."
                } else {
                    all.joinToString("\n") { a ->
                        val isDefault = if (a.id == DEFAULT_ASSISTANT_ID) " (Predeterminado)" else ""
                        "${a.id} | ${a.name}$isDefault"
                    }
                }
            }
        }
    }

    private fun registerCreate(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_create",
                description = "Crea un nuevo asistente (solo configura el nombre y la copia del personaje; herramientas/modelo/El enlace lo configura el usuario en la página de configuración)." +
                    "id Se puede omitir (generado automáticamente).",
                parameters = mapOf(
                    "name" to "Requerido, nombre del asistente",
                    "system_prompt" to "Opcional, copia de caracteres (palabra de aviso del sistema)",
                    "assistant_id" to "Opcional, identificación del asistente (generada automáticamente cuando se omite)",
                ),
                required = setOf("name"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                errorJson("El nombre del parámetro falta o está vacío")
            } else {
                runBlocking {
                    val id = args["assistant_id"]?.trim().takeUnless { it.isNullOrBlank() }
                        ?: "assistant-${System.currentTimeMillis()}"
                    val existing = assistantRepository.getById(id)
                    if (existing != null) {
                        errorJson("asistente id=$id Ya existe (nombre:${existing.name})")
                    } else {
                        val entity = io.zer0.muse.data.assistant.AssistantEntity(
                            id = id,
                            name = name,
                            systemPrompt = args["system_prompt"]?.trim().orEmpty(),
                        )
                        assistantRepository.upsert(entity)
                        Logger.i(TAG, "Asistente de creación de herramientas: $id ($name)")
                        okJson("Asistente creado$name\"(id=$id). Herramientas/modelo/Vaya a la página de configuración del asistente para configurar el enlace.")
                    }
                }
            }
        }
    }

    private fun registerUpdatePersona(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_update_persona",
                description = "Modificar la copia del personaje de un asistente (palabra de aviso del sistema). Sólo se permiten cambios de carácter." +
                    "No se permite cambiar las fijaciones de las herramientas./modelo/proveedor——Estos son administrados por el usuario en la página de configuración.",
                parameters = mapOf(
                    "assistant_id" to "Se necesita asistente id",
                    "system_prompt" to "Requerido, redacción de nuevos personajes.",
                ),
                required = setOf("assistant_id", "system_prompt"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["assistant_id"]?.trim().orEmpty()
            val prompt = args["system_prompt"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("El parámetro asistente_id falta o está vacío")
                prompt.isBlank() -> errorJson("El parámetro system_prompt no puede estar vacío (use un espacio para borrar el carácter)")
                else -> runBlocking {
                    val assistant = assistantRepository.getById(id)
                    if (assistant == null) {
                        errorJson("Asistente no encontrado $id")
                    } else {
                        assistantRepository.upsert(assistant.copy(systemPrompt = prompt))
                        Logger.i(TAG, "Persona asistente cambiada de herramienta: $id (${assistant.name})")
                        okJson("El asistente ha sido modificado \"${assistant.name}\"($id) redacción de personajes.")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "assistant_manage_delete",
                description = "Eliminar un asistente. Irrevocable. El asistente predeterminado no se puede eliminar." +
                    "Assistant_manage_list debe usarse para confirmar la identificación del asistente antes de eliminar.",
                parameters = mapOf(
                    "assistant_id" to "Se necesita asistente id",
                ),
                required = setOf("assistant_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["assistant_id"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("El parámetro asistente_id falta o está vacío")
                id == DEFAULT_ASSISTANT_ID -> errorJson("El asistente predeterminado no se puede eliminar (de acuerdo con la protección de la capa de la interfaz de usuario)")
                else -> runBlocking {
                    val assistant = assistantRepository.getById(id)
                    if (assistant == null) {
                        errorJson("Asistente no encontrado $id")
                    } else {
                        assistantRepository.delete(id)
                        Logger.i(TAG, "Asistente de extracción de herramientas.: $id (${assistant.name})")
                        okJson("Asistente \"eliminado\"${assistant.name}\"($id).")
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** Registrador: Inyección de Koin, guiada uniformemente por ToolRegistrarBootstrapper. */
class AssistantManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: AssistantManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
