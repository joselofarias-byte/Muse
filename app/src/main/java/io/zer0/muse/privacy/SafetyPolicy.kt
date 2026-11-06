package io.zer0.muse.privacy

/**
 * Safety Policy — Política de seguridad de entrada del usuario,Filtrar de forma conservadora el contenido ilegal.
 *
 * principios de diseño:
 *  - Sin censura excesiva,Filtrar solo palabras sensibles que violen claramente las reglas(drogas/armas/Relacionados con la violencia).
 *  - Lista de palabras clave conservadora,Evite dañar accidentalmente conversaciones normales(Como por ejemplo"antivirus""ideas armadas"Espera palabras normales).
 *  - Dar razones amistosas de rechazo y sugerencias después del golpe.,No bloquear la entrada directamente(Depende de la persona que llama decidir cómo manejar).
 *
 * Instrucciones de implementación:Política de seguridad de contenidos basada en reglas.
 */
object SafetyPolicy {

    /**
     * Resultados del control de seguridad.
     *
     * @param safe       ¿Es seguro?(true=Se puede enviar a LLM,false=debe ser rechazado o advertido)
     * @param reason     Razones por las que no es seguro(para troncos/Depuración,null Indica seguridad)
     * @param suggestion Consejos cuando no es seguro(Consejos amigables mostrados a los usuarios,null Indica seguridad)
     */
    data class SafetyResult(
        val safe: Boolean,
        val reason: String?,
        val suggestion: String?,
    )

    /**
     * Lista conservadora de palabras clave ilegales(coincidencia de minúsculas,Partido directo chino).
     *
     * Incluya únicamente contenido duro que viole claramente las reglas.,Evite lesiones accidentales:
     *  - drogas:heroína/cocaína/metanfetamina/marihuana(Comúnmente conocido como)/Éxtasis, etc.
     *  - armas:fabricación de armas/fabricación de bombas/Municiones, etc.(traer"hacer/fabricación"contexto verbal)
     *  - Violencia:método de matar/Desmembramiento, etc.(Orientación ilegal clara)
     *
     * Nota:Palabras clave con calificación contextual(Como por ejemplo"fabricación de armas"en lugar de solo"pistola"),Reducir la tasa de detección falsa.
     */
    private val blockedKeywords: List<String> = listOf(
        // Drogas(claro contrabando)
        "heroína",
        "cocaína",
        "metanfetamina",
        "Éxtasis",
        "metanfetamina",
        "hacer una bomba",
        "hacer una bomba",
        "fabricación de bombas",
        // Fabricación de armas(con contexto de fabricación)
        "fabricación de armas",
        "haciendo armas",
        "hacer armas",
        "Fabricación de municiones",
        // Orientación sobre delitos violentos
        "método de matar",
        "como matar a alguien",
        "Método de desmembramiento",
        "Cómo desmembrar un cuerpo",
    )

    /**
     * Compruebe si la entrada del usuario es segura.
     *
     * darse cuenta:Coincidencia de subcadenas simples(No distingue entre mayúsculas y minúsculas). Pulsar cualquier palabra clave se considera inseguro.
     * Sin análisis semántico,Sin acceso a servicios de auditoría externa,Manténgalo liviano y predecible.
     *
     * @param text Texto de entrada del usuario
     * @return Resultados del control de seguridad,Predeterminado safe=true
     */
    fun checkInput(text: String): SafetyResult {
        if (text.isBlank()) return SafetyResult(safe = true, reason = null, suggestion = null)
        val lower = text.lowercase()
        for (keyword in blockedKeywords) {
            if (lower.contains(keyword)) {
                return SafetyResult(
                    safe = false,
                    reason = "Utilice palabras clave ilegales: $keyword",
                    suggestion = "Este tema puede contener contenido ilegal.,Modifíquelo y vuelva a intentarlo.",
                )
            }
        }
        return SafetyResult(safe = true, reason = null, suggestion = null)
    }
}
