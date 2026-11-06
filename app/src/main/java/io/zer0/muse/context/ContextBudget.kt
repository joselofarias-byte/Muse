package io.zer0.muse.context

/**
 * M4.3: Partición presupuestaria contextual.
 *
 * Una partición por fuente de inyección para solicitudes LLM entrantes,Configuración de límite superior independiente y observación independiente.,
 * evitar una sola fuente(Accesorios grandes/memoria súper larga/herramienta gigante schema)Ocupa toda la ventana de contexto.
 */
enum class ContextSection {
    /** Accesorios/Texto del documento(Empalmado en el cuerpo del mensaje a través de buildSendText). */
    ATTACHMENT_TEXT,

    /** Resumen de compilación de memoria a largo plazo(<long_term_memory>). */
    LONG_TERM_MEMORY,

    /** FTS Datos relevantes sobre el retiro(<relevant_memory>). */
    RELEVANT_MEMORY,

    /** RAG Recupera el fragmento del documento inyectado. */
    RAG_CITATION,

    /** Inyección de descripción de imágenes para generación de modelos visuales. */
    VISION_DESCRIPTION,

    /** Esquema de función total de la herramienta. */
    TOOL_SCHEMA,

    /**
     * L1-4: La cantidad total de mensajes completos del sistema (instantánea estática + segmento dinámico + memoria relacionada + revisión de sesión después del empalme).
     * Cada subsegmento tiene su propio presupuesto, pero el número total después del empalme no tiene límite superior antes; esta partición sirve como la última puerta total.
     */
    SYSTEM_PROMPT,
}

/**
 * M4.3: Presupuesto contextual unificado.
 *
 * Establecer límite de caracteres para todas las fuentes de inyección excepto los mensajes históricos;La estrategia de truncamiento es mantener el encabezado. +
 * Truncar explícitamente la anotación(El hecho del truncamiento debe ser diagnosticable aguas abajo.,No se permite el descarte silencioso).
 *
 * La cantidad de mensajes históricos aún la administra Assistant.contextMessageSize / limitContextWithContext,
 * No repetir restricciones dentro de este presupuesto.(responsabilidad única,evitar el doble truncamiento).
 *
 * @param limits Límite de partición(personaje);Construcciones de valor personalizadas disponibles para pruebas
 */
class ContextBudget(private val limits: Map<ContextSection, Int> = DEFAULT_LIMITS) {

    /** Resultados del truncamiento de una sola partición. */
    data class Clamped(val text: String, val truncated: Boolean, val originalChars: Int)

    /**
     * Truncar texto por límite de partición.
     *
     * El texto en blanco se devuelve sin cambios.;Devolver como está sin exceder el límite;Si se excede el límite, se conservará el encabezado y se agregará una nota.
     * Revisión de corrección(P2): Longitud de la nota desde [limit] reservado dentro,Longitud del resultado garantizada ≤ límite superior,
     * En lugar de interceptar el límite y luego agregar anotaciones, lo que inevitablemente excederá el presupuesto.
     */
    fun clamp(section: ContextSection, text: String): Clamped {
        val limit = limits[section]
        val originalChars = text.length
        return when {
            limit == null || originalChars <= limit -> Clamped(text, false, originalChars)
            else -> {
                val notice = "\n\n[El contenido ha sido truncado por el presupuesto contextual.: Partición=$section originales $originalChars límite superior de caracteres $limit personaje]"
                // Nota incluida en el presupuesto:Cabecera reservada = tapa - longitud de la anotación(límite inferior 0)
                val headLength = (limit - notice.length).coerceAtLeast(0)
                Clamped(text.take(headLength) + notice, true, originalChars)
            }
        }
    }

    /** Entrada conveniente:Obtenga solo el texto truncado. */
    fun clampText(section: ContextSection, text: String): String = clamp(section, text).text

    companion object {
        /**
         * Límite de partición predeterminado(personaje). Mezclado de chino e inglés. 1 token ≈ 2-3 estimación de carácter:
         * - Resumen de memoria 24k caracteres ≈ 8-12k token,No desplaza el texto de la conversación
         * - Adjunto 60k caracteres ≈ Límite superior práctico para la inyección de texto completo en un solo archivo
         * - Esquema de herramienta 400k caracteres ≈ 100+ Margen de seguridad para visualización completa de herramientas.
         */
        val DEFAULT_LIMITS: Map<ContextSection, Int> = mapOf(
            ContextSection.ATTACHMENT_TEXT to 60_000,
            ContextSection.LONG_TERM_MEMORY to 24_000,
            ContextSection.RELEVANT_MEMORY to 8_000,
            ContextSection.RAG_CITATION to 8_000,
            ContextSection.VISION_DESCRIPTION to 12_000,
            ContextSection.TOOL_SCHEMA to 400_000,
            // L1-4: system prompt Límite superior total (personalidad estática/Reglas + tiempo dinámico + memoria/Revisión de empalmes).
            // 80k Caracteres (mezcla de chino e inglés). ≈ 27-40k token) es suficiente para adaptarse a la personalidad normal+reglas+inyección de memoria,
            // Exceder el límite indica que una sección de la inyección está fuera de control (como una imagen súper larga no truncada)/memoria).
            ContextSection.SYSTEM_PROMPT to 80_000,
        )
    }
}
