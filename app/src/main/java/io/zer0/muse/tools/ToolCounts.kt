package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.data.skill.SkillEntity

/**
 * Tres calibres para la cantidad de herramientas.
 *
 * Los tres números deberían ser diferentes originalmente. La diferencia sólo debería provenir de "qué capa se solicita", no de la implementación de escribir un conjunto para cada una:
 *  - [callableInSchema]: El número real emitido en esta ronda de esquema de funciones. —— Esto es lo que el modelo puede ajustar por el momento;
 *  - [sessionVisible]: El número que puede enumerarse mediante una consulta vacía de find_tools en esta sesión (incluidas las habilidades cargables, excluyendo el propio find_tools);
 *  - [registeredTotal]: el número total de herramientas en el registro local (este es el archivo informado en el registro de inicio).
 */
data class ToolCounts(
    val callableInSchema: Int,
    val sessionVisible: Int,
    val registeredTotal: Int,
)

/**
 * La única entrada de cálculo para el conteo de tercer nivel (función pura, no depende de Android).
 *
 * La entrega, el conteo y la recuperación están todos realizados. [ToolSchemaRules] El mismo conjunto de reglas para , por lo que el primer número aquí es el mismo que
 * ChatStreamCoordinator La cantidad de herramientas realmente enviadas al modelo es la misma y el segundo número es el mismo que find_tools
 * Las consultas vacías tienen la misma longitud de lista.
 */
object ToolCounter {

    /** Entrada de cálculo: Introduzca los datos de origen de cada uno de los tres calibres de forma centralizada. */
    data class Input(
        val localToolDefs: List<ToolDefinition>,
        val registeredTools: List<ToolRegistry.ToolDef>,
        val sessionSkillDefs: List<ToolRegistry.ToolDef>,
        val sessionVisibleNames: Set<String>?,
        val enabledSkills: List<SkillEntity> = emptyList(),
        val criteria: ToolSchemaRules.Criteria = ToolSchemaRules.Criteria(),
    )

    fun compute(input: Input): ToolCounts {
        val localNames = input.registeredTools.map { it.name }.toSet()
        val callableLocal = ToolSchemaRules.selectLocalTools(input.localToolDefs, input.criteria)
        val callableSkills = ToolSchemaRules.selectSkills(input.enabledSkills, localNames, input.criteria)
        val sessionTools = input.registeredTools + input.sessionSkillDefs
        return ToolCounts(
            callableInSchema =
            ToolSchemaRules.callableNames(callableLocal.map { it.name }, callableSkills.map { it.id }).size,
            sessionVisible =
            ToolSchemaRules.visibleSubset(sessionTools, input.sessionVisibleNames)
                .count { it.name != FindToolsTool.TOOL_NAME },
            registeredTotal = input.registeredTools.size,
        )
    }
}
