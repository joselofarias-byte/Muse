package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P1-3b Deslocalización:Skill Implementación de herramientas de gestión (install/list/uninstall/disable/enable/update).
 * Llamado por el delegado de SkillExecutor.
 *
 * También responsable de las habilidades verbales rápidas.(prompt skill)entrada de ejecución [execPromptSkill]:
 * Este tipo no ejecuta la implementación de Kotlin, solo `implementationKotlin = "prompt:<texto>"` codificado
 * Las instrucciones de uso devuelven el modelo sin cambios (ver [SkillImporter.PROMPT_PREFIX]).
 */
class SkillManagementToolsImpl(
    private val context: Context,
    private val skillRepository: SkillRepository?,
) {
    suspend fun installSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_install_not_configured)
        val skillJson = args["skill_json"] ?: return context.getString(R.string.skill_missing_param_skill_json)
        precheckSkillJson(skillJson)?.let { return it }
        when (val result = SkillImporter.parse(skillJson)) {
            is SkillImporter.Result.Ok -> {
                if (result.skill.id in SkillImporter.RESERVED_IDS) {
                    return context.getString(R.string.skill_install_conflict, result.skill.id)
                }
                resultOf { repo.upsert(result.skill) }
                    .onError { msg, _ -> return context.getString(R.string.skill_install_db_failed, msg) }
                return context.getString(R.string.skill_installed, result.skill.name, result.skill.id, result.skill.implementationKotlin)
            }
            is SkillImporter.Result.Err -> return context.getString(R.string.skill_install_validate_failed, result.reason)
        }
        return context.getString(R.string.skill_unknown_error)
    }

    suspend fun listSkills(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_list_not_configured)
        val category = args["category"]?.takeIf { it.isNotBlank() }
        val all = repo.observeAll.first()
        val builtInIds = SkillImporter.RESERVED_IDS
        val builtInCount = all.count { it.id in builtInIds }
        val userCount = all.size - builtInCount
        if (all.isEmpty()) {
            return if (category != null) {
                context.getString(R.string.skill_no_category_skill, category)
            } else {
                context.getString(R.string.skill_no_skill_installed)
            }
        }
        val filtered =
            when (category?.lowercase()) {
                "user", "custom" -> all.filter { it.id !in builtInIds }
                "skill" -> all
                null -> all
                else -> all.filter { it.category == category }
            }
        if (filtered.isEmpty()) {
            return context.getString(R.string.skill_no_category_skill, category)
        }
        val header = "total ${all.size} un skill(Incorporado $builtInCount un,Instalación de usuario $userCount un)"
        val body =
            filtered.joinToString("\n") { s ->
                val source = if (s.id in builtInIds) "[Incorporado]" else "[Usuario]"
                "${s.id} | ${s.name} | ${s.category} | ${if (s.enabled) "enabled" else "disabled"} $source"
            }
        return "$header\n$body"
    }

    /**
     * uninstall_skill — Desinstale la habilidad instalada por el usuario.
     *
     * cheque de pertenencia(con [updateSkill]/[enableSkill] Alineación):
     *  - [SkillImporter.RESERVED_IDS] Los ID reservados integrados siempre se rechazan —— Las habilidades integradas solo se pueden desactivar,No se puede desinstalar;
     *  - `implementationKotlin` a `plugin:` comienzo o `category == "plugin"` Habilidades de complemento rechazadas ——
     *    Las habilidades del complemento se desinstalan con el complemento,Eliminarlo solo causará"Complemento habilitado pero la herramienta desaparece".
     */
    suspend fun uninstallSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_uninstall_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
        val name = args["name"]?.takeIf { it.isNotBlank() }
        if (id == null && name == null) return context.getString(R.string.skill_missing_param_id_or_name)
        val targetId =
            id ?: run {
                val matched = repo.observeAll.first().find { it.name == name }
                matched?.id ?: return context.getString(R.string.skill_skill_not_found_by_name, name)
            }
        if (targetId in SkillImporter.RESERVED_IDS) {
            return context.getString(R.string.skill_uninstall_reserved_rejected, targetId)
        }
        val existing = repo.getById(targetId) ?: return context.getString(R.string.skill_skill_not_found, targetId)
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_uninstall_plugin_rejected, existing.name, targetId)
        }
        repo.delete(targetId)
        return context.getString(R.string.skill_uninstalled, existing.name, targetId)
    }

    /**
     * disable_skill — Deshabilite las habilidades instaladas por el usuario.
     *
     * cheque de pertenencia:
     *  - Las habilidades reservadas integradas permiten deshabilitar(Están incorporados,El usuario puede cerrar manualmente,ver [enableSkill]);
     *  - Se rechazan las habilidades propiedad de los complementos —— Las habilidades del complemento comienzan y terminan con el complemento,Deshabilitarlo por sí solo omitirá la verificación del ciclo de vida de la página de administración de complementos.
     */
    suspend fun disableSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_disable_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_disable_plugin_rejected, existing.name, id)
        }
        repo.setEnabled(id, enabled = false)
        return context.getString(R.string.skill_disabled_result, existing.name, id)
    }

    /**
     * enable_skill — Habilitar especificado skill(con [disableSkill] simetria).
     *
     * Restricciones de pertenencia:
     *  - Las habilidades de retención incorporadas permiten habilitar(Están incorporados,Sólo el usuario puede cerrarlo manualmente.);
     *  - Habilidades que poseen los complementos(implementationKotlin a `plugin:` comienzo o category == "plugin")rechazar ——
     *    Las habilidades del complemento comienzan y terminan con el complemento,Habilitarlo por sí solo omitirá la verificación del ciclo de vida de la página de administración del complemento.;
     *  - Otros identificadores desconocidos devolverán directamente no encontrado.
     */
    suspend fun enableSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_enable_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_enable_plugin_rejected, existing.name, id)
        }
        repo.setEnabled(id, enabled = true)
        return context.getString(R.string.skill_enabled_result, existing.name, id)
    }

    /**
     * update_skill — Iterar sobre las habilidades del usuario creadas por el propio asistente.
     *
     * Permitir actualizaciones name / description / parametersJson;También se permite el uso de habilidades con palabras rápidas. `prompt`
     * Texto del comando de reemplazo de parámetros.
     *
     * Restricciones de pertenencia(Aquellos que no pertenezcan al usuario serán rechazados.,El motivo se devuelve al modelo en chino.):
     *  - [SkillImporter.RESERVED_IDS] retención incorporada id;
     *  - `implementationKotlin` a `plugin:` comienzo o `category == "plugin"` Habilidades de complemento.
     *
     * restricciones de seguridad:El contenido actualizado no se escribe directamente en la biblioteca.,Xi'an Jing [SkillImporter.validateForUpdate] Ve e instala
     * exactamente el mismo cheque(Reservar ID/categoría lista blanca/implementar lista blanca/inyectar lista negra/indicar límite superior).
     * Una vez que la actualización se realiza correctamente, la identificación permanece sin cambios y se ve obligada a permanecer habilitada.
     */
    suspend fun updateSkill(args: Map<String, String>): String {
        val repo = skillRepository ?: return context.getString(R.string.skill_update_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() } ?: return context.getString(R.string.skill_missing_param_id)
        val existing = repo.getById(id) ?: return context.getString(R.string.skill_skill_not_found, id)
        if (id in SkillImporter.RESERVED_IDS) {
            return context.getString(R.string.skill_update_reserved_rejected, id)
        }
        if (existing.implementationKotlin.startsWith(PLUGIN_IMPLEMENTATION_PREFIX) || existing.category == "plugin") {
            return context.getString(R.string.skill_update_plugin_rejected, existing.name, id)
        }

        val isPrompt = SkillImporter.isPromptSkill(existing.implementationKotlin)
        val promptArg = args["prompt"]
        if (promptArg != null && !isPrompt) {
            return context.getString(R.string.skill_update_prompt_not_supported, existing.name, id)
        }
        val newPrompt = promptArg?.trim()
        if (promptArg != null && newPrompt.isNullOrEmpty()) {
            return context.getString(R.string.skill_prompt_blank)
        }
        val newName = args["name"]?.trim()?.takeIf { it.isNotBlank() }
        val newDescription = args["description"]?.trim()?.takeIf { it.isNotBlank() }
        val newParameters = args["parametersJson"]?.takeIf { it.isNotBlank() }
        if (newName == null && newDescription == null && newParameters == null && newPrompt == null) {
            return context.getString(R.string.skill_update_no_fields)
        }

        // La habilidad de palabra rápida no tiene parámetros:parametersJson / requiredJson Fijado al vacio,Ignorar la llamada entrante parametersJson
        val validated =
            SkillImporter.validateForUpdate(
                id = existing.id,
                name = newName ?: existing.name,
                description = newDescription ?: existing.description,
                parametersJson = if (isPrompt) SkillImporter.EMPTY_PARAMETERS_JSON else newParameters ?: existing.parametersJson,
                requiredJson = if (isPrompt) "[]" else existing.requiredJson,
                implementationKotlin = newPrompt?.let { SkillImporter.encodePromptText(it) } ?: existing.implementationKotlin,
                category = existing.category,
            )
        val parsed =
            when (validated) {
                is SkillImporter.Result.Ok -> validated.skill
                is SkillImporter.Result.Err -> return context.getString(R.string.skill_update_validate_failed, validated.reason)
            }
        val updated =
            parsed.copy(
                createdAt = existing.createdAt,
                updatedAt = System.currentTimeMillis(),
                enabled = true,
            )
        resultOf { repo.update(updated) }
            .onError { msg, _ -> return context.getString(R.string.skill_update_db_failed, msg) }
        return context.getString(R.string.skill_updated, updated.name, updated.id)
    }

    /**
     * Ejecutar habilidad de palabra rápida:Devolver el texto de instrucciones al modelo.,No se implementa ninguna implementación de Kotlin.
     *
     * tira de texto `[comando de habilidad]` marca,Claramente distinguido de los comandos del sistema.
     * v2.x: Soporte opcional `input` parámetros — El texto del comando contiene [PROMPT_INPUT_PLACEHOLDER](o [PROMPT_ARGS_PLACEHOLDER])tiempo
     * Reemplazar con entrada;Si no hay ningún marcador de posición, agregue la entrada al final de la instrucción.;Los parámetros restantes se ignoran.
     */
    fun execPromptSkill(skill: SkillEntity, args: Map<String, String> = emptyMap()): String {
        val text = SkillImporter.decodePromptText(skill.implementationKotlin)?.trim()
        if (text.isNullOrEmpty()) return context.getString(R.string.skill_prompt_empty)
        val input = args["input"]?.trim().orEmpty()
        val hasPlaceholder = text.contains(PROMPT_INPUT_PLACEHOLDER) || text.contains(PROMPT_ARGS_PLACEHOLDER)
        val rendered =
            when {
                input.isEmpty() -> text
                hasPlaceholder ->
                    text.replace(PROMPT_INPUT_PLACEHOLDER, input).replace(PROMPT_ARGS_PLACEHOLDER, input)
                else -> text + "\n\nentrada:\n" + input
            }
        return context.getString(R.string.skill_prompt_instruction_result, skill.name, skill.id, rendered)
    }

    private companion object {
        /** implementaciónPrefijo de Kotlin para habilidades de complemento(con Administrador de complementos "plugin:<pluginId>:<fn>" Acuerdo consistente). */
        const val PLUGIN_IMPLEMENTATION_PREFIX = "plugin:"

        /** v2.x: Marcador de posición de entrada de habilidad de palabra rápida. */
        const val PROMPT_INPUT_PLACEHOLDER = "{{input}}"

        /** v2.x: Marcador de posición de entrada de habilidad de palabra rápida(Alias,escritura habitual). */
        const val PROMPT_ARGS_PLACEHOLDER = "{{args}}"
    }

    private fun precheckSkillJson(jsonText: String): String? {
        val requiredFields = listOf("name", "description", "category", "implementationKotlin", "parametersJson")
        var jsonErrorMsg: String? = null
        val raw: JsonObject? =
            resultOf {
                AppJson.decodeFromString(JsonObject.serializer(), jsonText)
            }.onError { msg, throwable ->
                val errorKind = if (throwable is SerializationException) "Error de formato" else "excepción inesperada"
                jsonErrorMsg = msg
                Logger.w("SkillManagement", "install_skill JSON El análisis falló($errorKind, ${throwable?.javaClass?.simpleName}): $msg")
            }.getOrNull()
        if (raw == null) {
            return "JSON Error de formato: ${jsonErrorMsg ?: "No se puede analizar"}. Compruebe si las comillas, los dos puntos y los corchetes coinciden."
        }
        for (field in requiredFields) {
            if (field !in raw) {
                return "Falta campo obligatorio: $field. Campos obligatorios: name, description, category, implementationKotlin, parametersJson"
            }
        }
        for (field in requiredFields) {
            val element = raw[field]
            if (element !is JsonPrimitive || !element.isString) {
                val actualType =
                    when (element) {
                        is JsonPrimitive -> if (element.isString) "cuerda" else "numeros/Booleano"
                        is JsonObject -> "objeto"
                        is JsonArray -> "matriz"
                        is JsonNull -> "null"
                        else -> "desconocido"
                    }
                return "campo $field error de tipo: esperar cadena,En realidad $actualType"
            }
        }
        return null
    }
}
