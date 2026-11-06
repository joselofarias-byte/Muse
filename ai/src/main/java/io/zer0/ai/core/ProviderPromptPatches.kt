package io.zer0.ai.core

/**
 * v1.0.7: Provider Parche de aviso del sistema exclusivo.
 *
 * principios de diseño:
 *  - responsabilidad única:Este módulo sólo es responsable de**generar**matriz de texto de parche,No me importa cómo inyectar;
 *    Inyectado por la persona que llama(OpenAIProvider.buildRequestBody / buildResponsesRequestBody)Procesamiento unificado,
 *    Agregue el parche al final del contenido del mensaje del sistema o al campo de instrucciones de la API de Respuestas.
 *  - parche temporal:Cada parche debe indicar las condiciones de eliminación.(Se puede eliminar después de que el fabricante solucione el problema subyacente.).
 *  - Salida según la localidad/bilingue ingles,El valor predeterminado es chino(1muse idioma principal).
 *
 * Parches actualmente implementados:
 *  - [deepseekOutputContractPrompt]:DeepSeek Contrato de salida del modelo de inferencia
 *    (Evite que el modelo solo genere razonamiento_contenido/pensamiento sin escribir final assistant content)
 */
object ProviderPromptPatches {

    /**
     * Genere una lista de parches de avisos del sistema específicos del proveedor.
     *
     * @param model modelo objetivo(Se utiliza para determinar familias de modelos.)
     * @param baseUrl Provider de baseUrl(Se utiliza para determinar el fabricante.,Como por ejemplo api.deepseek.com)
     * @param thinkingFormat ProviderCompat formato de pensamiento derivado(Se utiliza para determinar la familia DeepSeek)
     * @param reasoningLevel nivel de razonamiento(OFF No se inyectan parches cuando)
     * @param locale etiqueta de idioma(null Chino predeterminado,"zh*" chino,Otro inglés)
     * @return Lista de texto de parche;Una lista vacía indica que no es necesario inyectar parches.
     */
    fun getProviderPromptPatches(
        model: Model,
        baseUrl: String,
        thinkingFormat: ThinkingFormat?,
        reasoningLevel: ReasoningLevel,
        locale: String? = null,
    ): List<String> {
        // Piensa en cerrar sin inyectar ningún parche.
        if (reasoningLevel == ReasoningLevel.OFF) return emptyList()

        val patches = mutableListOf<String>()

        // DeepSeek Contrato de salida del modelo de inferencia
        if (isDeepSeekReasoningModel(model, baseUrl, thinkingFormat)) {
            patches.add(deepseekOutputContractPrompt(locale))
        }

        return patches
    }

    /**
     * Determine si es un modelo de inferencia de DeepSeek.
     *
     * determinar el camino(Cualquier acierto se considera familia DeepSeek.,y satisfacer las condiciones del modelo de inferencia.):
     *  1. baseUrl contiene api.deepseek.com(Punto final oficial)
     *  2. modelId contiene deepseek-reasoner / deepseek-r1 / deepseek-v4(modelo de inferencia id)
     *  3. modelId Contiene deepseek-ai/ o deepseek/(Prefijo de alojamiento de terceros,Como por ejemplo OpenRouter / SiliconFlow)
     *  4. thinkingFormat == DEEPSEEK(ProviderCompat resultados derivados)
     *
     * Nota:sin limite provider — OpenRouter / SiliconFlow / ModelScope Esperando alojamiento de terceros
     *   DeepSeek modelo(Como por ejemplo deepseek-ai/DeepSeek-R1)También golpeará.
     *
     * @param model modelo objetivo
     * @param baseUrl Provider de baseUrl
     * @param thinkingFormat ProviderCompat formato de pensamiento derivado
     */
    private fun isDeepSeekReasoningModel(model: Model, baseUrl: String, thinkingFormat: ThinkingFormat?): Boolean {
        // camino rápido:thinkingFormat se ha derivado como DEEPSEEK
        if (thinkingFormat == ThinkingFormat.DEEPSEEK) return true

        val modelId = model.id.lowercase()
        val host = baseUrl.lowercase()

        // punto final no oficial + no deepseek modelId → No es la familia DeepSeek
        val isDeepSeekHost = host.contains("api.deepseek.com")
        val isDeepSeekModelId = modelId.contains("deepseek-ai/") ||
            modelId.contains("deepseek/") ||
            modelId.startsWith("deepseek-")
        if (!isDeepSeekHost && !isDeepSeekModelId) return false

        // Confirmado que será la familia DeepSeek,Determinar más a fondo si se debe inferir el modelo.:
        //  - deepseek-reasoner / deepseek-r1 / deepseek-v4 Serie(deepseek-v4 / deepseek-v4-pro espera)
        //  - deepseek-ai/DeepSeek-R1(depósito en garantía R1)
        //  - deepseek-ai/deepseek-r1(forma minúscula)
        //  - ordinario deepseek-chat / deepseek-v3 / deepseek-v3.1 No activado(modelo no inferencial)
        return modelId.contains("deepseek-reasoner") ||
            modelId.contains("deepseek-r1") ||
            modelId.contains("deepseek-v4") ||
            modelId.contains("deepseek-reasoning")
    }

    /**
     * DeepSeek El modelo de inferencia genera parches de contrato.
     *
     * eliminar condición:DeepSeek El modelo de inferencia puede mostrar de manera estable respuestas visibles para los usuarios en proveedores oficiales y externos.
     * Después de escribir el contenido final del asistente,Este parche se puede quitar.
     *
     * Antecedentes del problema:DeepSeek-R1 / DeepSeek-V4 El modelo de inferencia solo genera resultados en algunos escenarios.
     * reasoning_content / thinking(cadena de pensamiento)Este es el final de esta ronda de respuestas.,Como resultado, los usuarios no pueden ver la respuesta final.
     * Este parche requiere explícitamente que el modelo escriba la respuesta en el contenido final del asistente después de pensar.
     *
     * @param locale etiqueta de idioma(null Chino predeterminado,"zh*" chino,Otro inglés)
     */
    private fun deepseekOutputContractPrompt(locale: String?): String {
        val isZh = locale == null || locale.let { it.startsWith("es", ignoreCase = true) || it.startsWith("zh", ignoreCase = true) }
        return if (isZh) {
            """
Si está utilizando el modelo DeepSeek,Cumpla con el siguiente contrato de salida de DeepSeek:
reasoning_content / thinking Se utiliza únicamente para borradores de razonamiento interno.
Cualquier respuesta, sugerencia, código, lista, pregunta, resúmenes o conclusiones que deban mostrarse a los usuarios.,El contenido final del asistente debe escribirse una vez completado el pensamiento.
No finalice esta ronda de respuestas simplemente generando razonamiento_contenido/pensamiento.
Si usa <think> etiqueta,Primero debes cerrar la pestaña Pensar.,Luego genere la respuesta final.
            """.trimIndent()
        } else {
            """
If you are using a DeepSeek model, follow this DeepSeek output contract:
reasoning_content / thinking is only for private reasoning scratch work.
Any user-facing answer, recommendation, code, list, question, summary, or conclusion must be written into the final assistant content after thinking.
Do not end a response with only reasoning_content / thinking.
If you use <think> tags, close the thinking tag before emitting the final answer.
            """.trimIndent()
        }
    }
}
