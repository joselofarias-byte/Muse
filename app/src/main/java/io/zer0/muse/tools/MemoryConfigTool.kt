package io.zer0.muse.tools

import io.zer0.memory.ticker.MemoryConfig
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Herramienta para memorizar parámetros del sistema.
 *
 * Anteriormente, el asistente podía leer y escribir recuerdos.**contenido**(save_memory / search_memory / pin_memory),
 * Pero no puedo cambiar la memoria**como funciona**——token El presupuesto, la velocidad de olvido y el umbral de compilación solo se pueden arrastrar manualmente a la página de configuración.
 *
 * Estas dos herramientas complementan la parte de configuración: una observa la situación actual y la otra cambia los parámetros. La configuración modificada aún debe pasarse.
 * [MemoryConfig.validate], las combinaciones ilegales serán rechazadas y se devolverá el motivo, y no se escribirá en el DataStore.
 */
object MemoryConfigTool {

    const val NAME_GET = "memory_config_get"
    const val NAME_SET = "memory_config_set"

    fun getDef() = ToolRegistry.ToolDef(
        name = NAME_GET,
        description =
        "Leer los parámetros actuales del sistema de memoria.(token Presupuesto/velocidad de olvido/umbral de compilación, etc.)y su significado." +
            "Antes de querer ajustar el comportamiento de la memoria, ajústelo primero y verifique la situación actual.,No haga cambios basándose en conjeturas.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun setDef() = ToolRegistry.ToolDef(
        name = NAME_SET,
        description =
        "Ajustar los parámetros del sistema de memoria. Cambie solo los campos pasados,El resto permanece sin cambios.;La legalidad se verificará una vez finalizada la modificación.,Las no reglas rechazadas en general." +
            "Escenarios comunes:La memoria siempre olvida las cosas.→Bajar o subir decay_per_day hit_bonus;" +
            "El contenido de la memoria es demasiado largo y ocupa el contexto.→bajar token_budget;" +
            "Sólo quiero recordar las cosas importantes.→Aumentar el umbral de compilación.",
        parameters =
        mapOf(
            "token_budget" to "Opcional,memory.md Inyecte el presupuesto del token del indicador del sistema,entero positivo,Predeterminado 2500.",
            "decay_per_day" to "Opcional,Coeficiente de atenuación λ(todos los dias),Cuanto más grande eres, más rápido lo olvidas.0.01≈19El cielo vuelve a cero / 0.02=Predeterminado / 0.04≈10Días.",
            "hit_bonus" to "Opcional,Puntos de bonificación por cada golpe.,Predeterminado 5. Sube el volumen para que sea más difícil que desaparezcan los recuerdos que recuerdas con frecuencia.",
            "base_importance" to "Opcional,Puntuación inicial para la nueva memoria.,debe > 0,Predeterminado 10.",
            "compile_threshold" to "Opcional,No se realizará ningún progreso si la puntuación es inferior a esta. memory.md,Predeterminado 4.5. Sube el volumen para conservar sólo los recuerdos importantes.",
            "forget_speed" to "Opcional,factor de olvido,1.0 normales,2.0 Olvídate el doble de rápido,Predeterminado 1.0.",
            "conversation_recall" to
                "Opcional,true/false. Si se debe permitir que la canalización de memoria vuelva a leer el texto completo de la sesión(en lugar de simplemente mirar la ventana actual).",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun get(settings: SettingsRepository): String {
        val c = settings.memoryConfigFlow.first()
        return buildString {
            appendLine("Parámetros de memoria actuales:")
            appendLine("token_budget = ${c.tokenBudget}  (memory.md Inyectar presupuesto,Predeterminado 2500)")
            appendLine("decay_per_day = ${c.decayPerDay}  (Coeficiente de atenuación,Predeterminado 0.02;Cuanto más grande te haces, más rápido te olvidas)")
            appendLine("hit_bonus = ${c.hitBonus}  (puntos extra por golpe,Predeterminado 5)")
            appendLine("base_importance = ${c.baseImportance}  (nueva puntuación inicial de memoria,Predeterminado 10)")
            appendLine("compile_threshold = ${c.compileThreshold}  (Ingrese la puntuación más baja en la memoria.md,Predeterminado 4.5)")
            appendLine("forget_speed = ${c.forgetSpeed}  (factor de olvido,Predeterminado 1.0)")
            append("conversation_recall = ${c.conversationRecallEnabled}  (Si se debe volver a leer el texto original de la conversación.)")
        }
    }

    /** Leer un parámetro opcional; devuelve nulo por defecto. Si el análisis falla, se registrará el motivo. [errors]. */
    private inline fun <T> pick(args: Map<String, String>, key: String, errors: MutableList<String>, parse: (String) -> T?): T? {
        val raw = args[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = parse(raw)
        if (parsed == null) errors.add("$key El valor no se puede analizar.: $raw")
        return parsed
    }

    /** apilar argumentos para [current] encendido, devolviendo nueva configuración y errores de análisis. */
    private fun merge(args: Map<String, String>, current: MemoryConfig): Pair<MemoryConfig, List<String>> {
        val errors = mutableListOf<String>()
        val next =
            MemoryConfig(
                tokenBudget = pick(args, "token_budget", errors) { it.toIntOrNull() } ?: current.tokenBudget,
                conversationRecallEnabled =
                pick(args, "conversation_recall", errors) { it.toBooleanStrictOrNull() }
                    ?: current.conversationRecallEnabled,
                decayPerDay = pick(args, "decay_per_day", errors) { it.toFloatOrNull() } ?: current.decayPerDay,
                hitBonus = pick(args, "hit_bonus", errors) { it.toFloatOrNull() } ?: current.hitBonus,
                baseImportance =
                pick(args, "base_importance", errors) { it.toFloatOrNull() } ?: current.baseImportance,
                compileThreshold =
                pick(args, "compile_threshold", errors) { it.toFloatOrNull() } ?: current.compileThreshold,
                forgetSpeed = pick(args, "forget_speed", errors) { it.toFloatOrNull() } ?: current.forgetSpeed,
            )
        return next to errors
    }

    /** Lista relativa [current] La fila cambiada. */
    private fun diff(current: MemoryConfig, next: MemoryConfig): List<String> = buildList {
        if (next.tokenBudget != current.tokenBudget) {
            add("- token_budget: ${current.tokenBudget} → ${next.tokenBudget}")
        }
        if (next.decayPerDay != current.decayPerDay) {
            add("- decay_per_day: ${current.decayPerDay} → ${next.decayPerDay}")
        }
        if (next.hitBonus != current.hitBonus) {
            add("- hit_bonus: ${current.hitBonus} → ${next.hitBonus}")
        }
        if (next.baseImportance != current.baseImportance) {
            add("- base_importance: ${current.baseImportance} → ${next.baseImportance}")
        }
        if (next.compileThreshold != current.compileThreshold) {
            add("- compile_threshold: ${current.compileThreshold} → ${next.compileThreshold}")
        }
        if (next.forgetSpeed != current.forgetSpeed) {
            add("- forget_speed: ${current.forgetSpeed} → ${next.forgetSpeed}")
        }
        if (next.conversationRecallEnabled != current.conversationRecallEnabled) {
            add(
                "- conversation_recall: ${current.conversationRecallEnabled} → " +
                    "${next.conversationRecallEnabled}",
            )
        }
    }

    suspend fun set(args: Map<String, String>, settings: SettingsRepository): String {
        val current = settings.memoryConfigFlow.first()
        val (next, errors) = merge(args, current)
        val invalid = MemoryConfig.validate(next)

        return when {
            errors.isNotEmpty() -> "Error: ${errors.joinToString("; ")}"
            invalid != null -> "Error: La combinación de parámetros es ilegal,no salvo —— $invalid"
            next == current -> "OK: Los parámetros son consistentes con la corriente.,No se requieren cambios."
            else -> {
                settings.saveMemoryConfig(next)
                val changes = diff(current, next)
                buildString {
                    appendLine("OK: Se han actualizado los parámetros de la memoria.")
                    changes.forEach { appendLine(it) }
                    append("Los parámetros entran en vigor en la siguiente ronda de compilación de la memoria.")
                }
            }
        }
    }
}
