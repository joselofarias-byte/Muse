package io.zer0.muse.util

import android.os.Build
import android.text.Html

/**
 * v1.131: unificar HTML → Herramienta de eliminación de texto sin formato.
 *
 * La lógica de eliminación de HTML original estaba dispersa en 3 archivos.,Las estrategias de implementación varían:
 *  - web/WebSearchService.kt#stripHtml — Pure Regex + decodificación manual de entidades
 *  - doc/DocumentParser.kt#stripHtmlTags — XmlPullParser Prioridad + reserva de HtmlCompat(Mantener una implementación independiente,Debido a la necesidad de procesamiento de saltos de línea)
 *  - tools/SkillExecutor.kt#htmlToText — Eliminar script/bloque de estilo + Regex + Html.fromHtml desbloquear entidades
 *
 * Esta herramienta proporciona dos niveles de extracción.:
 *  - [stripHtmlSimple]: Solo eliminación de etiquetas Regex + decodificación de entidades comunes,Adecuado para escenarios ligeros como resúmenes de búsqueda
 *  - [stripHtmlComprehensive]: Contiene eliminación de bloque de script/estilo + de-entidad HtmlCompat,Adecuado para escenarios de análisis de documentos.
 *
 * Nota:RichContentCard.sanitizeHtml No es una desinversión,Sí, mantenga HTML pero elimine las etiquetas peligrosas.,No dentro del ámbito unificado.
 */

private val HTML_TAG_REGEX = Regex("<[^>]+>")

/** Eliminar bloque script/estilo/noscript(Contiene contenido,a través de líneas,ignorar caso). */
private val SCRIPT_STYLE_BLOCK_REGEX = Regex(
    """(?is)<(script|style|noscript)[^>]*>.*?</\1>""",
    RegexOption.IGNORE_CASE,
)

/**
 * Simple HTML → texto plano:
 *  1. `Regex("<[^>]+>")` Retire todas las etiquetas
 *  2. Decodificación manual de 6 entidades comunes.(&amp; &lt; &gt; &quot; &#39; &nbsp;)
 *  3. trim Espacio en blanco al principio y al final
 *
 * Aplicable:resumen de búsqueda,snippet Como escenarios ligeros que no necesitan conservar la estructura de nueva línea.
 */
fun stripHtmlSimple(s: String): String {
    return s.replace(HTML_TAG_REGEX, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .trim()
}

/**
 * completo HTML → texto plano:
 *  1. Eliminar bloque script/estilo/noscript(Contiene contenido)
 *  2. `Regex("<[^>]+>")` Retire todas las etiquetas
 *  3. usar [Html.fromHtml] Desempaquetar todas las entidades HTML(incluyendo entidades digitales `&#160;` espera)
 *
 * Aplicable:Escenarios como el análisis de documentos y el contenido de texto enriquecido que requieren una decodificación completa de entidades.
 * Rendimiento:que [stripHtmlSimple] Un poco más lento(Html.fromHtml Llamada general),Pero la cobertura física es completa.
 */
fun stripHtmlComprehensive(html: String): String {
    val s = html.replace(SCRIPT_STYLE_BLOCK_REGEX, " ")
        .replace(HTML_TAG_REGEX, " ")

    @Suppress("DEPRECATION")
    val spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT)
    } else {
        Html.fromHtml(s)
    }
    return spanned.toString().trim()
}
