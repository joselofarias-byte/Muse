package io.zer0.muse.tools

import io.zer0.muse.data.PreferenceStore

/**
 * Herramienta de preferencias de usuario —— Pon "no lo hagas en el futuro"…”se convierte en una dura limitación.
 *
 * ## por qué es necesario
 *
 * Ítem 20 de autoevaluación del asistente: "Aprendizaje de preferencias del usuario: por ejemplo, si está acostumbrado, no utilice estímulos vacíos ni haga un resumen de tres puntos.
 * Estos pueden resolverse mediante restricciones estrictas en lugar de depender de mi juicio sobre el terreno. "
 *
 * Situación actual: El usuario dice "No uses palabras de aliento en el futuro". La modelo lo recuerda esta vez y lo olvida la próxima vez. ——
 * Porque esta frase no se ha conservado en ninguna parte. Esta herramienta completa este paso.
 *
 * ## Cuándo llamar (escribe la descripción y deja que el modelo decida por sí solo)
 *
 * solo en usuario**Expresar claramente los requisitos a largo plazo.**Al llamar: "A partir de ahora…"No más…"No me gusta…”.
 * Las solicitudes únicas ("sea breve esta vez") no deben almacenarse —— Guardarlo contaminará todas las conversaciones posteriores.
 */
object UserPreferenceTool {

    const val TOOL_NAME = "remember_preference"

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Save a lasting user preference so it applies to all future conversations. " +
            "Call ONLY when the user states a durable rule about how you should behave " +
            "(e.g. \"stop using generic encouragement\", \"don't end with a 3-point summary\", " +
            "\"always answer in Chinese\"). " +
            "Do NOT call for one-off requests like \"be brief this time\" — those apply to this turn only.",
        parameters =
        mapOf(
            "rule" to "Required. The preference as an imperative sentence in the user's own language, " +
                "e.g. \"No respondas con estímulos vacíos.\".",
            "source" to "Optional. The user's original wording, for traceability.",
        ),
        required = setOf("rule"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, store: PreferenceStore): String {
        val rule = args["rule"]?.trim().orEmpty()
        val tooLong = rule.length > MAX_RULE_LENGTH
        return when {
            rule.isEmpty() -> "Error: rule parameter is required."
            tooLong ->
                "Error: rule is too long (${rule.length} chars). Keep it under $MAX_RULE_LENGTH — " +
                    "one short imperative sentence."
            store.add(rule, args["source"]?.trim().orEmpty()) ->
                "Saved preference: \"$rule\". It will apply to all future conversations."
            else -> "Preference already saved: \"$rule\". No duplicate added."
        }
    }

    /** Longitud máxima de preferencia única —— Una \"preferencia\" demasiado larga probablemente sea una descripción de requisitos, no una restricción. */
    private const val MAX_RULE_LENGTH = 200
}
