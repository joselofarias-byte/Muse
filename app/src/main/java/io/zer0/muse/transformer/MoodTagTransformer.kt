package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.util.MusePatterns

/**
 * v0.30-b: MOOD etiqueta Transformer(6 Paso 6 del flujo de trabajo — MOOD→Empaquetado de texto).
 *
 * Coloque el contenido del mensaje ASISTENTE en `<mood>...</mood>` bloque extraído a [UIMessage.mood] campo,
 * Haga que la interfaz de usuario sea plegable para mostrar IA"Manuscrito interno",content Sólo se muestra el texto de respuesta final.
 *
 * v0.32 Acceso experimental a la autorreflexión.:Despegar simultáneamente `<reflection>...</reflection>` bloquear a
 * [UIMessage.reflection] campo(Solo conecta el backend,UI Renderizar tareas posteriores pendientes).
 *
 * tiempo de procesamiento: Mensajes históricos del ASISTENTE(persistió)Preprocesamiento antes de enviar a LLM.
 * ChatViewModel elimina en tiempo real el manejo del estado de ánimo/reflexión de las respuestas de transmisión a medida que se acumula la transmisión(ver updateAssistant).
 *
 * compatibles:
 *  - `<mood>Vibe: ...\nSparks: ...\n...</mood>\nTexto` → mood="Vibe: ...\n...", content="Texto"
 *  - `<mood>...</mood>` (Sin texto) → mood="...", content=""
 *  - `<mood></mood>` (etiqueta vacía) → mood=null, content=texto restante(Las etiquetas vacías también se eliminarán)
 *  - Etiqueta de estado de ánimo no coincidente(Sin cierre) → Déjalo como está
 *  - Ya tengo un campo de humor → Saltar la extracción del estado de ánimo(Sin extracción repetida)
 *  - De la misma manera, las reglas de extracción de la etiqueta de reflexión son consistentes con el estado de ánimo.
 *
 * Nota: Este Transformer no admite etiquetas de estado de ánimo/reflexión anidadas(Como por ejemplo `<mood><mood>...</mood></mood>`),
 * La expresión regular no codiciosa solo coincidirá con la etiqueta de cierre más interna y dejará etiquetas de cierre aisladas.Muse Los productos no se anidarán,Por lo tanto, no se realiza ningún procesamiento de máquina de estados.
 *
 * Consejos de seguridad: mood/reflection como texto original,El contenido no está desinfectado.,UI La capa de renderizado debe escaparse por sí sola.(protegerse contra XSS).
 */
class MoodTagTransformer : Transformer {
    override val name: String = "MoodTag"

    /**
     * B6-02: Extracción después de que se completa la generación de flujo.
     *
     * finalizeResponse ir [Transformer.onGenerationFinish] El gancho maneja el mensaje final del asistente.
     * Si la etiqueta llega a través de trozos (<moodfx> no en el mismo delta que la etiqueta de cierre),updateAssistant de
     * Se perderá la extracción en tiempo real porque la etiqueta no está cerrada; La lógica de transformación se reutiliza aquí para la cobertura final para garantizar
     * mood / moodfx / reflection se extraen y se escriben de nuevo en los campos del mensaje. Idempotente: se omitirán los campos existentes.
     */
    override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
        transform(messages, context)

    /**
     * Partido normal `<mood>...</mood>` bloquear(no codicioso,a través de líneas).
     * v1.131: Migrado a io.zer0.muse.util.MusePatterns.MOOD_TAG_REGEX.
     * Muse Sólo uso <mood> etiqueta única,Análisis simplificado.
     */
    private val moodRegex = MusePatterns.MOOD_TAG_REGEX

    /**
     * v0.32 Autorreflexión experimental: emparejamiento regular `<reflection>...</reflection>` bloquear.
     * La denominación sigue un diseño de estado de ánimo consistente;Depósito después del decapado [UIMessage.reflection].
     */
    private val reflectionRegex = Regex("""<reflection>([\s\S]*?)</reflection>""", RegexOption.IGNORE_CASE)

    /**
     * Extracción de etiquetas universales:de [content] Descúbrelo en [regex] Todos los bloques que coinciden.
     *  - [existing] Saltar directamente si no está vacío.(Volver al original content),Mantener los valores de campo existentes
     *  - De lo contrario, findAll recopila todas las coincidencias de una sola vez.:Eliminar etiquetas del contenido independientemente de si el grupo está vacío(Las etiquetas vacías también se eliminan),
     *    Varias piezas de contenido están conectadas con saltos de línea.,Evite el bucle removeRange O(n*k) copiar
     *
     * @return Pair(extracted valor, nuevo después del desmontaje content)
     */
    private fun extractTag(content: String, regex: Regex, existing: String?): Pair<String?, String> {
        val matches = regex.findAll(content).toList()
        if (existing != null && matches.isEmpty()) return existing to content
        if (matches.isEmpty()) {
            // v1.x: Ten todo en cuenta — Etiqueta no cerrada(El modelo ocasionalmente genera <mood> o [mood] pero se me olvidó cerrar),
            // La expresión regular no puede coincidir.
            // v1.0.90: Sólo reconoce el bolsillo viejo. <mood>/[mood]/<think>,Falta la mitad de la etiqueta y etiqueta cerrada aislada
            // (</mood>,<reflection>,[mod]), si no se limpia adecuadamente, el texto original de la etiqueta se mostrará como texto principal.
            // Entréguelo a InternalMarkupSanitizer para una limpieza completa.
            val stripped = InternalMarkupSanitizer.stripForDisplay(content)
            return null to stripped
        }
        // Varias piezas de contenido están conectadas con saltos de línea.,Los bloques vacíos se filtran
        val extracted =
            matches
                .map { it.groupValues.getOrNull(1).orEmpty() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()
                .ifBlank { null }
        // Construye algo nuevo en una sola pasada content,Eliminar etiquetas independientemente de si el grupo está vacío
        val sb = StringBuilder(content.length)
        var lastEnd = 0
        for (m in matches) {
            sb.append(content, lastEnd, m.range.first)
            lastEnd = m.range.last + 1
        }
        sb.append(content, lastEnd, content.length)
        return (existing ?: extracted) to sb.toString()
    }

    override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages.map { msg ->
        if (msg.role != MessageRole.ASSISTANT) return@map msg

        // ── MOOD despegar ──
        // Si ya existe un campo de estado de ánimo, omítalo(Evite la extracción repetida)
        val (extractedMood, contentAfterMood) = extractTag(msg.content, moodRegex, msg.mood)
        // Algunos modelos escriben el manuscrito interno como <mod>...</mod>, también se procesa como un bloque adjunto de estado de ánimo y no puede ingresar el texto principal.
        val modRegex =
            Regex("""<(?:mod|mood)>([\s\S]*?)</(?:mod|mood)>|\[(?:mod|mood)\]([\s\S]*?)\[/\s*(?:mod|mood)\]""", RegexOption.IGNORE_CASE)
        val modMatches = modRegex.findAll(contentAfterMood).toList()
        val modContent =
            modMatches.mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { value -> value.isNotBlank() }?.trim()
            }.joinToString("\n").ifBlank { null }
        val contentAfterMod = modMatches.fold(contentAfterMood) { current, match -> current.replace(match.value, "") }
        val combinedMood = listOfNotNull(extractedMood, modContent).joinToString("\n").ifBlank { null }

        // ── reflection despegar(v0.32 Experimental selfReflection) ──
        // Si ya hay un campo de reflexión, omítelo.(Evite la extracción repetida)
        // ── moodfx despegar(B6-02,con <mood> Abdomen completamente aislado) ──
        val (extractedMoodSkin, contentAfterMoodSkin) = MoodSkinParser.extract(contentAfterMod, msg.moodSkin)

        // ── reflection despegar(v0.32 Experimental selfReflection) ──
        val (extractedReflection, workingContent) = extractTag(contentAfterMoodSkin, reflectionRegex, msg.reflection)

        // Alguien tiene extraccion,o el contenido está recortado → generar nuevo msg;De lo contrario, devuélvelo como está.
        if (combinedMood == msg.mood && extractedMoodSkin == msg.moodSkin &&
            extractedReflection == msg.reflection && workingContent == msg.content
        ) {
            msg
        } else {
            if (combinedMood != null && combinedMood != msg.mood && context.extra("debug_mode") == true) {
                Logger.d(
                    "MoodTag",
                    "history mood extracted | len=${combinedMood.length} | preview=${combinedMood.replace('\n', ' ').take(120)}",
                )
            }
            msg.copy(
                mood = combinedMood,
                reflection = extractedReflection,
                // B6-02: Me perdí esta línea antes — moodfx El nombre del skin no se vuelve a escribir en msg.moodSkin después de eliminarlo.
                // Esto hace que ChatScreen lea lastAssistant.moodSkin como siempre nulo y la máscara de pantalla completa nunca se mostrará.
                moodSkin = extractedMoodSkin,
                content = workingContent.trim(),
            )
        }
    }
}
