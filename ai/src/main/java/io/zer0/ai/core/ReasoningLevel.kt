package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * nivel de razonamiento,Compatible con la serie OpenAI o1 / Anthropic thinking / Gemini thinking Fuerza de razonamiento ajustable.
 *
 * Phase 8.1 Expandir: desde el nivel 5(AUTO/NONE/LOW/MEDIUM/HIGH)Se expande al nivel 6 + BudgetTokens.
 * Escrito de forma independiente(OFF/AUTO/LOW/MEDIUM/HIGH/XHIGH + budgetTokens).
 *
 * - [OFF]: Razonamiento cercano(corresponder Anthropic thinking=disabled;OpenAI Chat Completions Punto final oficial
 *   Utilice mínimo para expresar la intensidad más baja de razonamiento.;Responses API Sin valor de apagado universal,El proveedor omite la configuración y deja que el upstream decida.)
 * - [AUTO]: automático(Deja que el servidor decida,No agregado budgetTokens)
 * - [LOW]: razonamiento ligero(~1000 tokens)
 * - [MEDIUM]: razonamiento medio(~2000 tokens)
 * - [HIGH]: razonamiento profundo(~8000 tokens)
 * - [XHIGH]: Razonamiento extremadamente profundo(~16000 tokens,Sólo compatible con los mejores modelos)
 *
 * @property budgetTokens El número correspondiente de fichas de presupuesto pensante.;
 *   null Indica no especificado(AUTO / OFF),Determinado por el servidor
 * @property effort OpenAI o1 El campo de esfuerzo de la serie.(low/medium/high);
 *   null Indica no pasar este campo
 * @property anthropicLevel Anthropic thinking de level(disabled/low/medium/high);
 *   null Indica no pasar este campo
 */
@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int?,
    val effort: String?,
    val anthropicLevel: String?,
) {
    OFF(budgetTokens = 0, effort = "minimal", anthropicLevel = "disabled"),
    AUTO(budgetTokens = null, effort = null, anthropicLevel = null),
    LOW(budgetTokens = 1000, effort = "low", anthropicLevel = "low"),
    MEDIUM(budgetTokens = 2000, effort = "medium", anthropicLevel = "medium"),
    HIGH(budgetTokens = 8000, effort = "high", anthropicLevel = "high"),

    /**
     * v1.80 (M-CORE8): XHIGH Esfuerzo completo/Nivel antrópico.
     * Originalmente nulo provocó que OpenAI/Anthropic no enviara parámetros de inferencia,Usuario seleccionado"Razonamiento extremadamente profundo"En realidad no surte efecto.
     * OpenAI Ninguno "xhigh" valor,degradado a "high";Anthropic Usado de la misma manera "high".
     */
    XHIGH(budgetTokens = 16000, effort = "high", anthropicLevel = "high"),
    ;

    companion object {
        /** Nivel de razonamiento predeterminado(Compatibilidad con versiones anteriores cuando la configuración anterior no tiene campos). */
        val DEFAULT = AUTO
    }
}
