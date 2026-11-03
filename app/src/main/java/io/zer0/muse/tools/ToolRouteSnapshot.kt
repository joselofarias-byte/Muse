package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity

/** Immutable routing decision captured for one model turn. Definitions and execution share this snapshot. */
data class ToolRouteSnapshot(
    val definitions: List<ToolDefinition>,
    val routes: Map<String, Route>,
) {
    sealed interface Route {
        data object Local : Route

        data class Skill(val skill: SkillEntity) : Route
    }

    fun routeFor(name: String): Route? = routes[name]

    fun isExposed(name: String): Boolean = name in routes
}

/** Builds a deterministic per-turn table. Local tools always win over same-named skills. */
object RouteTable {
    /**
     * v2.x: Herramienta de unificación de habilidades de palabras rápidas schema — Agregue parámetros de entrada opcionales.
     * Sin un esquema de parámetros, el modelo no podrá pasar solicitudes específicas a la habilidad de palabra rápida.("Instalado pero no puedo usarlo"Una de las fuentes de experiencia.).
     */
    private const val PROMPT_SKILL_INPUT_SCHEMA =
        "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"," +
            "\"description\":\"Texto de entrada o solicitud pasado a la habilidad;será sustituido en el comando {{input}}/{{args}} marcador de posición,Si no hay ningún marcador de posición, se añade al final de la instrucción.\"}},\"required\":[]}"

    fun snapshot(localDefinitions: List<ToolDefinition>, skills: Collection<SkillEntity>): ToolRouteSnapshot {
        val localByName = localDefinitions.distinctBy { it.name }.associateBy { it.name }
        val skillByName =
            skills
                .distinctBy { it.id }
                .filterNot { it.id in localByName }
                .associateBy { it.id }
        val definitions =
            localByName.values +
                skillByName.values.map { skill ->
                    ToolDefinition(
                        name = skill.id,
                        description = skill.description,
                        // v2.x: La habilidad de palabra rápida agrega un parámetro de entrada opcional(Puerto de entrada cuando la instrucción no tiene parámetros propios)
                        parametersJsonSchema =
                        if (SkillImporter.isPromptSkill(skill.implementationKotlin)) {
                            PROMPT_SKILL_INPUT_SCHEMA
                        } else {
                            skill.parametersJson
                        },
                    )
                }
        val routes =
            localByName.keys.associateWith { ToolRouteSnapshot.Route.Local } +
                skillByName.mapValues { (_, skill) -> ToolRouteSnapshot.Route.Skill(skill) }
        return ToolRouteSnapshot(definitions = definitions, routes = routes)
    }
}
