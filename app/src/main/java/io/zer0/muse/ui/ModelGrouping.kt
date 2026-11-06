package io.zer0.muse.ui

import java.util.Locale

/**
 * Herramientas de agrupación de modelos — Clasifique el modelo en una familia semántica según la regularidad del prefijo de identificación.(GPT / Claude / Gemini espera).
 *
 * Recopile listas de modelos largas y dispersas en un número limitado de familias.,Junto con la interfaz de usuario plegable, los usuarios pueden encontrar rápidamente el objetivo incluso cuando hay más de 50 modelos.
 *
 * Reglas de agrupación(Coincidencia regular por prefijo de identificación,No distingue entre mayúsculas y minúsculas;El prefijo debe ir seguido de un delimitador. [-_.] o terminar):
 *  - GPT:        gpt / openai / o1 / o3 / o4
 *  - Claude:     claude / opus / sonnet / haiku
 *  - Gemini:     gemini
 *  - DeepSeek:   deepseek
 *  - Kimi:       kimi / moonshot
 *  - Qwen:       qwen / qwq
 *  - Doubao:     doubao / skylark
 *  - GLM:        glm / chatglm
 *  - Yi:         yi(cero una cosa)
 *  - Baichuan:   baichuan(Baichuan)
 *  - Hunyuan:    hunyuan / yuan(Tencent Hunyuan)
 *  - Mistral:    mistral / mixtral / codestral / pixtral / magistral
 *  - MiniMax:    minimax / abab
 *  - Grok:       grok
 *  - Embeddings: embedding / embed
 *  - Other:      el resto
 *
 * Nota:Requisitos de coincidencia de prefijos"El prefijo va seguido de un separador.(-,_,.)o terminar",evitar "o1" No coincide "o123",
 * "gpt" No coincide "gptxyz" Error de cálculo de prefijos de igual longitud.
 * v1.134 P2-3: Nuevo Yi / Baichuan / Hunyuan Se agrupan tres grupos de modelos domésticos.
 */

/**
 * Reglas de agrupación:Nombre del grupo → Prefijo regular.
 * La forma regular es como `^(?:prefijo1|prefijo2|...)(?:[-._]|$)`,Coincidencia a partir del prefijo,
 * Debe ir seguido de un delimitador.(-,_,.)o final de la cuerda.
 */
private val GROUP_RULES: List<Pair<String, Regex>> = listOf(
    "GPT" to Regex("^(?:gpt|openai|o[134])(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Claude" to Regex("^(?:claude|opus|sonnet|haiku)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Gemini" to Regex("^(?:gemini)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "DeepSeek" to Regex("^(?:deepseek)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Kimi" to Regex("^(?:kimi|moonshot)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Qwen" to Regex("^(?:qwen|qwq)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Doubao" to Regex("^(?:doubao|skylark)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "GLM" to Regex("^(?:glm|chatglm)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    // v1.134 P2-3: Expansión del grupo de modelos nacionales(Cero una cosa / Baichuan / Tencent Hunyuan)
    "Yi" to Regex("^(?:yi)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Baichuan" to Regex("^(?:baichuan)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Hunyuan" to Regex("^(?:hunyuan|yuan)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Mistral" to Regex(
        "^(?:mistral|mixtral|codestral|pixtral|magistral)(?:[-._]|$)",
        RegexOption.IGNORE_CASE,
    ),
    "MiniMax" to Regex("^(?:minimax|abab)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Grok" to Regex("^(?:grok)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Embeddings" to Regex("^(?:embedding|embed)(?:[-._]|$)", RegexOption.IGNORE_CASE),
)

/**
 * Orden de visualización del grupo(Los grupos que no aparecen en la lista se ordenan alfabéticamente al final).
 * con [GROUP_RULES] El orden es consistente,Agregar al final "Other" El grupo de bolsillo.
 */
val MODEL_GROUP_ORDER: List<String> = listOf(
    "GPT", "Claude", "Gemini", "DeepSeek", "Kimi", "Qwen", "Doubao",
    "GLM", "Yi", "Baichuan", "Hunyuan",
    "Mistral", "MiniMax", "Grok", "Embeddings", "Other",
)

/**
 * Devuelve el apellido al que pertenece según la identificación del modelo.(Como por ejemplo "GPT" / "Claude" / "Other").
 *
 * lógica coincidente:Haga coincidir la identificación del modelo regularmente comenzando desde el prefijo,Requiere un prefijo seguido de un delimitador(-,_,.)o final de la cuerda,
 * evitar "o1" No coincide "o123","gpt" No coincide "gptxyz" Esperar.
 *
 * @param modelId modelo id(No distingue entre mayúsculas y minúsculas)
 * @return Nombre del grupo,Devuelto cuando no coincide ninguna regla "Other"
 */
fun groupFor(modelId: String): String {
    return GROUP_RULES.firstOrNull { (_, regex) ->
        regex.containsMatchIn(modelId)
    }?.first ?: "Other"
}

/**
 * Cambie la lista de identificación del modelo por [groupFor] grupo,Mantener el orden original dentro del grupo.,Presione entre grupos [MODEL_GROUP_ORDER] Clasificar.
 *
 * @param modelIds Lista de identificación del modelo(Ya ordenados por orden interno del proveedor)
 * @return ordenado (Nombre del grupo, Lista de identificadores de modelo para este grupo) par clave-valor
 */
fun groupModels(modelIds: List<String>): List<Pair<String, List<String>>> {
    val byGroup: Map<String, List<String>> = modelIds.groupBy { groupFor(it) }
    val known = MODEL_GROUP_ORDER.filter { it in byGroup }
    val extras = byGroup.keys
        .filter { it !in MODEL_GROUP_ORDER }
        .sortedBy { it.lowercase(Locale.US) }
    return (known + extras).map { group -> group to byGroup[group].orEmpty() }
}
