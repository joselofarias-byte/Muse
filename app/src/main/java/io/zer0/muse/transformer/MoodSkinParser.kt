package io.zer0.muse.transformer

/**
 * B6-02: pelle-d-umore Protocolo de piel emocional explicado.
 *
 * Viene con aplicación `<mood>...</mood>` El manuscrito abdominal está completamente aislado:
 * - `<moodfx>rage</moodfx>` Es una máscara de pantalla completa para toda la respuesta.
 * - `[glow]...[/glow]` Espere a que aparezcan efectos de texto en línea
 * - Copiar/leer en voz alta/Utilice siempre exportar [cleanForExport] Borrar etiqueta de protocolo
 */
object MoodSkinParser {

    val SUPPORTED_SKINS = setOf("rage", "rage2", "desire", "vuoto", "moonlight", "off")

    private val MOODFX_REGEX = Regex("""<moodfx>([\s\S]*?)</moodfx>""", RegexOption.IGNORE_CASE)

    private val INLINE_EFFECTS = listOf(
        "glow",
        "big",
        "huge",
        "whisper",
        "red",
        "shake",
        "blur",
        "glitch",
    )

    /**
     * Ocho efectos especiales en línea que coinciden con fórmulas, presione [INLINE_EFFECTS] El pedido está precompilado.
     *
     * [Regex] Es inmutable y puede usarse repetidamente después de compilarlo una vez; antes de cada convocatoria se recopilaron ocho ítems.
     * Estas dos funciones están en el camino por el que pasará la burbuja en cada cuadro.
     */
    private val INLINE_EFFECT_REGEXES: Map<String, Regex> = INLINE_EFFECTS.associateWith { effect ->
        Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE)
    }

    /**
     * de [content] Extracto `<moodfx>` y retire la etiqueta.
     *
     * @return (piel id, Texto despojado)
     */
    fun extract(content: String, existing: String? = null): Pair<String?, String> {
        if (existing != null) return existing to content
        val match = MOODFX_REGEX.find(content) ?: return null to content
        val raw = match.groupValues[1].trim().lowercase()
        val skin = raw.takeIf { it in SUPPORTED_SKINS }
        val cleaned = content.removeRange(match.range)
        return skin to cleaned
    }

    /** Determinar si el texto contiene etiquetas de efectos en línea(Se utiliza para seleccionar la ruta de representación de texto enriquecido.). */
    fun containsInlineEffect(text: String): Boolean = INLINE_EFFECT_REGEXES.values.any { it.containsMatchIn(text) }

    /** Borrar todas las etiquetas de efectos en línea,Conserve el texto ajustado. */
    fun stripInlineEffects(text: String): String {
        var result = text
        for (regex in INLINE_EFFECT_REGEXES.values) {
            result = regex.replace(result) { it.groupValues[1] }
        }
        return result
    }

    /** Exportar/Copiar/Limpiar antes de leer en voz alta:Pelar primero moodfx,Borre los efectos en línea nuevamente. */
    fun cleanForExport(text: String): String = stripInlineEffects(extract(text).second)
}
