package io.zer0.ai.ollama

/**
 * Ollama Inferidor de capacidad del modelo.
 *
 * P2-3: Ollama Ni las interfaces /api/tags ni /v1/models devuelven campos de capacidad del modelo.
 * (supportsVision / supportsTools),Es necesario realizar una inferencia heurística regular en función del nombre del modelo.,
 * Deje que la capa de la interfaz de usuario muestre lo visual correctamente/Identificador de capacidad de llamada de herramienta.
 *
 * principios de diseño:
 *  - Los resultados de la inferencia son sólo para"Exhibición y juicio preliminar"usar,Enviar campo de herramientas sin bloquear(¿Es realmente compatible?
 *    Depende del modelo decidir,La mayoría de los errores 400,No dañará los datos);
 *  - La concordancia de palabras clave no distingue entre mayúsculas y minúsculas,Cubrir la visión común/familia de modelos de herramientas;
 *  - Las palabras clave que faltan siempre se devolverán false(Mantenlo a salvo,Evite falsos positivos).
 *
 * Descripción de la regla:
 *  - Familia de modelos de visión: llava / llama3.2-vision / qwen2-vl / qwen2.5-vl /
 *    gemma3 / pixtral / moondream / minicpm-v / internvl / phi3-vision espera
 *  - Familia de modelos de llamada de herramientas: llama3.1 / llama3.3 / qwen2.5 / qwen3 / mistral /
 *    mixtral / command-r / phi3 / gemma3 / deepseek-r1 espera
 */
object OllamaVisionInferrer {

    // ──────────────────────────────────────────────────────────────
    // 1. Palabras clave del modelo visual(No distingue entre mayúsculas y minúsculas,Partido normal)
    // ──────────────────────────────────────────────────────────────

    /**
     * Lista de palabras clave de capacidad visual.
     * Golpear a cualquiera determinará que se admite la entrada visual.
     *
     * Nota: "vision" colocado al final como palabra clave general,Como partido de respaldo
     * (Como por ejemplo "xxx-vision" / "vision-model" variantes nombradas).
     */
    private val VISION_KEYWORDS: List<Regex> = listOf(
        "llava",
        "llama3\\.2-vision",
        "qwen2-vl",
        "qwen2\\.5-vl",
        "gemma3",
        "pixtral",
        "moondream",
        "minicpm-v",
        "internvl",
        "phi3-vision",
        "phi-3-vision",
        "vision",
    ).map { keyword ->
        // No distingue entre mayúsculas y minúsculas,coincidencia de límites flexibles(Las palabras clave se pueden utilizar como subcadenas.)
        Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE)
    }

    // ──────────────────────────────────────────────────────────────
    // 2. La herramienta llama a las palabras clave del modelo.(No distingue entre mayúsculas y minúsculas,Partido normal)
    // ──────────────────────────────────────────────────────────────

    /**
     * Lista de palabras clave de capacidad de llamada de herramientas.
     * Si alguno resulta afectado, se determina que se admite la llamada a función.
     */
    private val TOOLS_KEYWORDS: List<Regex> = listOf(
        "llama3\\.1",
        "llama3\\.3",
        "qwen2\\.5",
        "qwen3",
        "mistral",
        "mixtral",
        "command-r",
        "phi3",
        "gemma3",
        "deepseek-r1",
    ).map { keyword ->
        Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE)
    }

    // ──────────────────────────────────────────────────────────────
    // 3. Externo API
    // ──────────────────────────────────────────────────────────────

    /**
     * Infiera si un modelo de Ollama admite entrada visual según el nombre del modelo.
     *
     * Reglas coincidentes: No distingue entre mayúsculas y minúsculas,la identificación del modelo contiene [VISION_KEYWORDS] Devuelve cualquier palabra clave en true,
     * De lo contrario, devuelve falso.
     *
     * @param modelId Ollama modelo id(Como por ejemplo "llava:13b" / "gemma3:12b" / "qwen2.5:7b")
     * @return true La inferencia de representación apoya la entrada visual
     */
    fun inferSupportsVision(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return VISION_KEYWORDS.any { it.containsMatchIn(modelId) }
    }

    /**
     * Inferir si las llamadas a herramientas son compatibles según el nombre del modelo(function calling).
     *
     * Reglas coincidentes: No distingue entre mayúsculas y minúsculas,la identificación del modelo contiene [TOOLS_KEYWORDS] Devuelve cualquier palabra clave en true,
     * De lo contrario, devuelve falso.
     *
     * @param modelId Ollama modelo id(Como por ejemplo "llama3.1:8b" / "qwen3:32b" / "mistral:7b")
     * @return true Indica llamadas a herramientas de soporte de inferencia.
     */
    fun inferSupportsTools(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return TOOLS_KEYWORDS.any { it.containsMatchIn(modelId) }
    }
}
