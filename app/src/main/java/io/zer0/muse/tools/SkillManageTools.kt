package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Conjunto de herramientas de gestión de habilidades —— Deje que el asistente realice la gestión diaria de las habilidades.
 *
 * Anteriormente, el asistente solo tenía install_skill (nuevo), en la lista/Iniciar y detener/No puedo borrarlos todos.
 * El mismo modelo que la gestión de bases de conocimientos y la gestión de sesiones.
 *
 * denominación unificada `skill_manage_`(Para evitar conflictos con los identificadores de habilidades integrados):
 *  - `skill_manage_list`     Enumere todas las habilidades y el estado de inicio y finalización (SAFE)
 *  - `skill_manage_toggle`   Iniciar y detener una habilidad (NORMAL)
 *  - `skill_manage_delete`   Eliminar una habilidad personalizada (HIGH;Las habilidades integradas no se pueden eliminar)
 *
 * Restricciones de diseño:
 *  - Habilidades incorporadas (BUILT_IN_SKILLS) no se puede eliminar, solo se puede iniciar y detener;
 *  - La eliminación es irrevocable y requiere aprobación.
 */
class SkillManageTools(
    private val skillRepository: SkillRepository,
) {
    companion object {
        private const val TAG = "SkillManageTools"

        /** Colección de ID de habilidades incorporada (no se puede eliminar, solo se puede iniciar y detener). */
        private val BUILT_IN_SKILL_IDS = setOf(
            "knowledge_search", "web_search", "web_fetch", "http_get", "http_post",
            "arxiv_search", "read_file", "write_file", "install_skill", "author_plugin",
            "delegate_agent", "task_plan", "update_plan_step", "sticker_list", "sticker_send",
        )
    }

    fun register(registry: ToolRegistry) {
        registerList(registry)
        registerToggle(registry)
        registerDelete(registry)
    }

    private fun registerList(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_list",
                description = "Enumere todas las habilidades y el estado de inicio y finalización (id, nombre, clasificación, si está habilitado, si está integrado)." +
                    "Se utiliza para iniciar y detener/Confirme la identificación de la habilidad antes de eliminarla.",
                parameters = mapOf(
                    "query" to "Opcional, filtrar por nombre o categoría",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            runBlocking {
                val query = args["query"]?.trim().orEmpty()
                val all = skillRepository.listEnabled()
                val filtered = if (query.isBlank()) {
                    all
                } else {
                    all.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.category.contains(query, ignoreCase = true)
                    }
                }
                if (filtered.isEmpty()) {
                    "No hay habilidades coincidentes."
                } else {
                    filtered.joinToString("\n") { s ->
                        "${s.id} | ${s.name} | ${s.category} | " +
                            "${if (s.enabled) "habilitar" else "desactivar"} | " +
                            "${if (s.id in BUILT_IN_SKILL_IDS) "Incorporado" else "Personalizar"}"
                    }
                }
            }
        }
    }

    private fun registerToggle(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_toggle",
                description = "Activa o desactiva una habilidad. Después de la desactivación, el asistente ya no puede activar la habilidad. Los usuarios pueden→Skill Reanudar.",
                parameters = mapOf(
                    "skill_id" to "Requerido, ID de habilidad (se puede obtener con Skill_manage_list)",
                    "enabled" to "true=habilitar,false=Desactivado; predeterminado true",
                ),
                required = setOf("skill_id"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["skill_id"]?.trim().orEmpty()
            val enabled = args["enabled"]?.trim()?.lowercase() != "false"
            if (id.isBlank()) {
                errorJson("El parámetro Skill_id falta o está vacío.")
            } else {
                runBlocking {
                    val skill = skillRepository.getById(id)
                    if (skill == null) {
                        errorJson("Habilidad no encontrada $id")
                    } else {
                        skillRepository.setEnabled(id, enabled)
                        Logger.i(TAG, "Herramientas${if (enabled) "habilitar" else "desactivar"}Habilidades: $id")
                        okJson("Ya${if (enabled) "habilitar" else "desactivar"}Habilidades${skill.name}\"($id).")
                    }
                }
            }
        }
    }

    private fun registerDelete(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "skill_manage_delete",
                description = "Eliminar una habilidad personalizada (irreversible). Las habilidades integradas no se pueden eliminar, sólo se pueden iniciar y detener." +
                    "Skill_manage_list debe usarse para confirmar la identificación de la habilidad antes de la ejecución.",
                parameters = mapOf(
                    "skill_id" to "Requerido, habilidades id",
                ),
                required = setOf("skill_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["skill_id"]?.trim().orEmpty()
            when {
                id.isBlank() -> errorJson("El parámetro Skill_id falta o está vacío.")
                id in BUILT_IN_SKILL_IDS -> errorJson("Habilidades incorporadas $id No se puede eliminar (solo se puede iniciar y detener), de acuerdo con la protección de la capa de UI")
                else -> runBlocking {
                    val skill = skillRepository.getById(id)
                    if (skill == null) {
                        errorJson("Habilidad no encontrada $id")
                    } else {
                        skillRepository.delete(id)
                        Logger.i(TAG, "Habilidad de eliminación de herramientas: $id (${skill.name})")
                        okJson("Habilidad eliminada \"${skill.name}\"($id).")
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
class SkillManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: SkillManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
