package io.zer0.muse.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ConversationExportMessage(
    val role: String,
    val content: String,
    val mood: String? = null,
    val reasoning: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ConversationExportDocument(
    val version: Int = 1,
    val sessionId: String,
    val title: String,
    val exportedAt: String,
    val messages: List<ConversationExportMessage>,
)

/**
 * Enumeración del formato de exportación de conversación(Versión extendida,Recién agregado basado en el Markdown original HTML / PDF).
 *
 * - [MARKDOWN]:Markdown texto plano(Fácil de copiar y pegar en software para tomar notas)
 * - [HTML]:archivo único HTML,Imágenes CSS + base64 en línea,Puedes abrir y compartir directamente con tu navegador.
 * - [PDF]:PDF Documentación,Android Nativo [PdfDocument] renderizado,A4 Paginación
 */
enum class ExportFormat {
    MARKDOWN,
    HTML,
    PDF,
}

/**
 * Exportador de conversaciones:Responsable de un grupo [UIMessage] Representado como Markdown / HTML / PDF.
 *
 * Puntos de diseño:
 *  - Puramente funcional + solo recibe messages + chatTitle,No depende de ViewModel/base de datos
 *    (La persona que llama es responsable de cargar la lista completa de mensajes.,Ver métodos contenedores en ChatViewModel)
 *  - Markdown Exportar para reutilizar lo existente [ChatExportCoordinator] convención de formato(Carácter negrita + separación de línea en blanco)
 *  - HTML Generar un solo archivo,en línea CSS(estilo de burbuja de chat,Adecuado para colores oscuros y claros.),Las imágenes están insertadas usando base64.,
 *    Los bloques de código usan puro <pre><code>(C-28: Quitar highlight.js CDN,Ver detalles exportToHtml)
 *  - PDF usar [PdfDocument] + [Canvas.drawText],A4 Paginación,encabezamiento(título + fecha)+ Pie de página(Número de página)
 *
 * ── C-24 Botón exportar copia locale ──────────────────────────────────────────────
 * etiqueta de rol,"Exportar tiempo/proceso de pensamiento",lang Las propiedades y otras interfaces de usuario en chino codificadas anteriormente se enviaban en chino a todos los usuarios.
 * Ahora se agrega cada función de exportación. [java.util.Locale] parámetros(zh → chino,el resto → ingles),Pasado por la persona que llama
 * actual locale;Se utiliza cuando la persona que llama no puede entenderlo. [java.util.Locale.getDefault] Ten todo en cuenta(La exportación es el contenido del archivo en lugar de
 * UI Recursos,prensa"Bilingüe al mapear + descripción de anotaciones"Proceso,No introducir recursos de cadenas.).
 * ────────────────────────────────────────────────────────────────────────
 *
 * Nota:Esta clase no tiene estatus.,Es seguro llamar a todos los métodos desde el subproceso IO.
 */
object ConversationExporter {

    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** A4 Ancho(PostScript points,1pt = 1/72 inch). */
    private const val PAGE_WIDTH = 595

    /** A4 altura(PostScript points). */
    private const val PAGE_HEIGHT = 842

    /** Márgenes de página alrededor(pt). */
    private const val PAGE_MARGIN = 36f

    /** PDF Tamaño de fuente:Título(pt). */
    private const val SIZE_TITLE = 16f

    /** PDF Tamaño de fuente:Texto(pt). */
    private const val SIZE_BODY = 12f

    /** PDF Tamaño de fuente:tiempo/Pie de página(pt). */
    private const val SIZE_META = 10f

    /** PDF Número aproximado de filas a mantener por página(para paginar estimaciones,El juicio real se basa en la coordenada Y.). */
    private const val APPROX_LINES_PER_PAGE = 50

    /**
     * Exportar como texto Markdown.
     *
     * Reutilizar existente [io.zer0.muse.ui.chat.ChatExportCoordinator.exportSessionAsMarkdown] convención de formato:
     *  - Título superior # + línea en blanco
     *  - por mensaje:**papel**: Contenido + línea en blanco
     *  - proceso de pensamiento(Si es así)Utilice el proceso de pensamiento ### para formar párrafos separados
     *
     * @param messages Lista completa de mensajes(en orden cronológico)
     * @param chatTitle Título de la sesión
     * @param locale C-24: Exportar idioma de copia(zh → chino,el resto → ingles). Pasado por la persona que llama si no se puede obtener.
     *  [java.util.Locale.getDefault] Llega al fondo de esto.
     * @return Markdown cuerda
     */
    fun exportToMarkdown(messages: List<UIMessage>, chatTitle: String, locale: Locale = Locale.getDefault()): String {
        val sb = StringBuilder()
        sb.append("# ").append(chatTitle).append("\n\n")
        sb.append("> ").append(localized(locale, "tiempo de exportación", "Exported at")).append(": ")
            .append(SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, locale).format(Date()))
            .append("  \n\n")

        messages.forEach { msg ->
            val role = roleLabel(msg.role, locale)
            // Reparación de auditoría (Análisis de registros): Mensaje de respuesta vacío(La herramienta no pudo finalizar, etc.)Anotar al exportar,No se generan más líneas en blanco.
            // Qué ven los usuarios cuando comparten una conversación"el dialogo esta vacio"eso es todo content="" Los mensajes del asistente se acumulan.
            val content = exportBody(msg).ifBlank {
                if (!msg.reasoning.isNullOrBlank()) {
                    localized(locale, "(Sin texto,Contiene proceso de pensamiento.)", "(No body, includes thinking)")
                } else {
                    localized(locale, "(respuesta vacía)", "(Empty reply)")
                }
            }
            sb.append("**").append(role).append("**: ").append(content).append("\n\n")
            // proceso de pensamiento(Si es así)
            if (!msg.reasoning.isNullOrBlank()) {
                sb.append("### ").append(localized(locale, "proceso de pensamiento", "Thinking")).append("\n\n")
                    .append(msg.reasoning).append("\n\n")
            }
        }
        return sb.toString()
    }

    /**
     * Exporte el historial completo de mensajes de una sola conversación como JSON independiente.
     *
     * A diferencia de la copia de seguridad completa de la aplicación, este archivo solo describe una sesión y es adecuado para que los usuarios lo retengan o lo compartan de forma selectiva;
     * La persona que llama es responsable de leer la lista completa de mensajes de la base de datos que no está restringida por la paginación de la primera pantalla.
     */
    fun exportToJson(sessionId: String, messages: List<UIMessage>, chatTitle: String, locale: Locale = Locale.getDefault()): String {
        val document =
            ConversationExportDocument(
                sessionId = sessionId,
                title = chatTitle,
                exportedAt = SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, locale).format(Date()),
                messages =
                messages.map { message ->
                    ConversationExportMessage(
                        role = message.role.name,
                        content = exportBody(message),
                        mood = message.mood,
                        reasoning = message.reasoning,
                        createdAt = message.createdAt,
                    )
                },
            )
        return exportJson.encodeToString(document)
    }

    /**
     * Exportar como un solo archivo HTML(Imágenes CSS + base64 en línea,Sin enlaces externos).
     *
     * características de estilo:
     *  - estilo de burbuja de chat(La derecha del usuario alinea la burbuja azul,Asistente de alineación izquierda burbuja gris)
     *  - Pase @media (prefers-color-scheme) Adecuado para colores oscuros y claros.
     *  - Para bloques de código <pre><code> Envolver fuente monoespaciada simple
     *  - fotos:Base64 local en línea con URI de datos;URL remota degradada a enlace de texto(Ver abajo)
     *
     * C-28(Privacidad):Eliminado highlight.js CDN(<link rel=stylesheet> + <script src> +
     * script de inicialización). Referencia de implementación original cdn.jsdelivr.net,El receptor abre el HTML o el script externo.,fuga
     * Visor IP,Y la imagen remota de la firma en el mensaje. <img src> También se activará cuando se abra. Por lo tanto:
     *  ① resaltado de sintaxis:Eliminar CDN,Los bloques de código siguen siendo puros <pre><code> estilo monoespaciado(La legibilidad cumple con los estándares,Alcance cero);
     *  ② imagen remota:Este exportador es una función pura.,No puedo obtener archivos locales/cache bytes,No se puede usar base64 en línea,
     *     Por lo tanto <img src="..."> Bajar a enlace de texto(<a> + rel="noopener noreferrer"),
     *     Solo inicie solicitudes cuando el espectador haga clic activamente,Ya no está abierto, es decir, de extensión.
     *
     * @param messages Lista completa de mensajes
     * @param chatTitle Título de la sesión
     * @param locale C-24: Exportar idioma de copia(zh → chino,el resto → ingles). Usar cuando no esté disponible
     *  [java.util.Locale.getDefault] Llega al fondo de esto.
     * @return Cadena HTML completa(Contiene <!DOCTYPE html> cabeza)
     */
    fun exportToHtml(messages: List<UIMessage>, chatTitle: String, locale: Locale = Locale.getDefault()): String {
        val sb = StringBuilder()
        // C-24: lang Las propiedades se generan por configuración regional.(zh → zh-CN,el resto → en),para lectores/El traductor identifica el idioma.
        val htmlLang = if (locale.language.lowercase().let { it.startsWith("es") || it.startsWith("zh") }) "es" else "en"
        sb.append("<!DOCTYPE html><html lang=\"").append(htmlLang).append("\"><head><meta charset=\"UTF-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        sb.append("<title>").append(escapeHtml(chatTitle)).append("</title>")
        // C-28: ya no se presenta highlight.js CDN(Ver función KDoc),El bloque de código degenera en puro. <pre><code> Estilo monoancho.
        sb.append("<style>").append(CSS).append("</style></head><body>")

        // Área de título superior
        sb.append("<header class=\"chat-header\">")
        sb.append("<h1>").append(escapeHtml(chatTitle)).append("</h1>")
        sb.append("<p class=\"meta\">").append(localized(locale, "tiempo de exportación", "Exported at")).append(": ")
            .append(
                escapeHtml(
                    SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, locale).format(Date()),
                ),
            )
            .append("</p></header>")

        // Lista de mensajes
        sb.append("<main class=\"chat-list\">")
        messages.forEach { msg ->
            val isUser = msg.role == MessageRole.USER
            val bubbleClass = if (isUser) "bubble user" else "bubble assistant"
            sb.append("<div class=\"").append(bubbleClass).append("\">")
            // etiqueta de rol
            sb.append("<div class=\"role\">").append(escapeHtml(roleLabel(msg.role, locale))).append("</div>")
            // Texto del mensaje(Representar como párrafo + bloque de código)
            sb.append("<div class=\"content\">").append(renderContentHtml(exportBody(msg))).append("</div>")
            // Imágenes en línea(USER Imágenes locales cargadas/imágenes generadas por el ASISTENTE)— base64 data URI,Alcance cero
            if (msg.imageBase64List.isNotEmpty()) {
                msg.imageBase64List.forEach { b64 ->
                    sb.append("<img class=\"inline-img\" alt=\"image\" src=\"data:image/jpeg;base64,")
                        .append(b64).append("\" />")
                }
            }
            // C-28: imagen remota URL — Las funciones primitivas no pueden volverse locales./cache bytes,No se puede usar base64 en línea,
            // Bajar a enlace de texto(Solicitar solo para clics activos,No vuelvas a abrir el enlace;rel Posicionamiento inverso del antiabridor).
            if (msg.imageUrls.isNotEmpty()) {
                sb.append("<div class=\"remote-links\">")
                msg.imageUrls.forEach { url ->
                    sb.append("<a class=\"remote-link\" href=\"")
                        .append(escapeAttr(url))
                        .append("\" target=\"_blank\" rel=\"noopener noreferrer\">")
                        .append(escapeHtml(localized(locale, "Ver fotos", "View image")))
                        .append("</a>")
                }
                sb.append("</div>")
            }
            // proceso de pensamiento(doblar)
            val reasoningText = msg.reasoning
            if (!reasoningText.isNullOrBlank()) {
                sb.append("<details class=\"reasoning\"><summary>")
                    .append(escapeHtml(localized(locale, "proceso de pensamiento", "Thinking")))
                    .append("</summary><div class=\"content\">")
                    .append(renderContentHtml(reasoningText))
                    .append("</div></details>")
            }
            // Marca de tiempo
            if (msg.createdAt > 0) {
                sb.append("<div class=\"time\">")
                    .append(
                        escapeHtml(
                            SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, locale).format(Date(msg.createdAt)),
                        ),
                    )
                    .append("</div>")
            }
            sb.append("</div>")
        }
        sb.append("</main>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * Exportar a archivo PDF.
     *
     * estrategia de renderizado:
     *  - uso [PdfDocument] pagina de inicio,A4 Tamaño(595 x 842 pt)
     *  - Dibujar encabezado en cada página.(título + fecha)con pie de página(Número de página)
     *  - El mensaje comienza con"Etiqueta de rol + texto + hora"Composición tipográfica de bloques en orden unitario
     *  - Para el texto principal [StaticLayout] Ajuste automático de línea;Cree una nueva página cuando la coordenada Y exceda la parte inferior de la página
     *  - Aproximadamente 50 líneas por página.(por [APPROX_LINES_PER_PAGE] estimación,En realidad juzgue por coordenadas.)
     *
     * @param context Android Context(para leer cacheDir)
     * @param messages Lista completa de mensajes
     * @param chatTitle Título de la sesión
     * @param locale C-24: Exportar idioma de copia(zh → chino,el resto → ingles). Usar cuando no esté disponible
     *  [java.util.Locale.getDefault] Llega al fondo de esto.
     * @return Archivo PDF escrito(ubicado en cacheDir/export/)
     */
    fun exportToPdf(context: Context, messages: List<UIMessage>, chatTitle: String, locale: Locale = Locale.getDefault()): File {
        val pdf = PdfDocument()

        // Pinceles compartidos
        val bodyPaint = TextPaint().apply {
            color = 0xFF222222.toInt()
            textSize = SIZE_BODY
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        val rolePaint = TextPaint().apply {
            color = 0xFF0A6CFF.toInt()
            textSize = SIZE_BODY
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val metaPaint = TextPaint().apply {
            color = 0xFF888888.toInt()
            textSize = SIZE_META
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        // Título de la sesión en encabezado en negrita con 16 puntos(conformarse a"Título 16sp"Especificaciones)
        val titlePaint = TextPaint().apply {
            color = 0xFF1A1A1A.toInt()
            textSize = SIZE_TITLE
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        // encabezamiento/Información secundaria del pie de página(fecha, número de página)usar 10pt
        val headerPaint = TextPaint().apply {
            color = 0xFF555555.toInt()
            textSize = SIZE_META
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val contentWidth = PAGE_WIDTH - PAGE_MARGIN * 2
        val exportTime = SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, locale).format(Date())

        // Límites superior e inferior del área de texto(Dejar espacio para encabezado y pie de página)
        val topY = PAGE_MARGIN + 24f
        val bottomLimit = PAGE_HEIGHT - PAGE_MARGIN - 18f

        // Abra la primera página + dibuje el encabezado
        var pageIndex = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex).create())
        var canvas = page.canvas
        drawHeader(canvas, chatTitle, exportTime, titlePaint, headerPaint)
        drawFooter(canvas, pageIndex, metaPaint)
        var y = topY

        for (msg in messages) {
            // etiqueta de rol:Si no cabe, cambia de página.
            if (y + SIZE_BODY * 1.6f > bottomLimit) {
                val (np, nc) = turnPage(pdf, page, pageIndex, chatTitle, exportTime, titlePaint, headerPaint, metaPaint)
                pageIndex++
                page = np
                canvas = nc
                y = topY
            }
            canvas.drawText(roleLabel(msg.role, locale), PAGE_MARGIN, y + SIZE_BODY, rolePaint)
            y += SIZE_BODY * 1.6f

            // Texto:Utilice StaticLayout para contar todas las líneas después de los saltos de línea,Dibuje línea por línea y salte de página según sea necesario
            val bodyText = exportBody(msg)
            val layout = StaticLayout.Builder.obtain(
                bodyText,
                0,
                bodyText.length,
                bodyPaint,
                contentWidth.toInt(),
            ).setLineSpacing(0f, 1.2f).build()

            val lineHeight = if (layout.lineCount > 0) layout.getLineBottom(0) - layout.getLineTop(0) else SIZE_BODY * 1.2f
            for (i in 0 until layout.lineCount) {
                // Esta línea no cabe en la página actual. → cambio de pagina
                if (y + lineHeight.toFloat() > bottomLimit) {
                    val (np, nc) = turnPage(pdf, page, pageIndex, chatTitle, exportTime, titlePaint, headerPaint, metaPaint)
                    pageIndex++
                    page = np
                    canvas = nc
                    y = topY
                }
                val lineText = layout.text.subSequence(
                    layout.getLineStart(i),
                    layout.getLineEnd(i),
                ).toString()
                // drawText El parámetro y es la posición de referencia. = valor absoluto de cima + ascenso
                canvas.drawText(lineText, PAGE_MARGIN, y - bodyPaint.fontMetrics.ascent, bodyPaint)
                y += lineHeight.toFloat()
            }

            // Marca de tiempo
            if (msg.createdAt > 0) {
                if (y + SIZE_META * 1.8f > bottomLimit) {
                    val (np, nc) = turnPage(pdf, page, pageIndex, chatTitle, exportTime, titlePaint, headerPaint, metaPaint)
                    pageIndex++
                    page = np
                    canvas = nc
                    y = topY
                }
                val timeText = SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, locale)
                    .format(Date(msg.createdAt))
                canvas.drawText(timeText, PAGE_MARGIN, y + SIZE_META, metaPaint)
                y += SIZE_META * 1.8f
            }

            // intervalo de mensaje
            y += SIZE_BODY * 0.5f
        }

        pdf.finishPage(page)

        // Salida a cacheDir/export/
        val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
        val safeName = sanitizeFileName(chatTitle) + "-" +
            SimpleDateFormat(MuseDateFormats.FILE_TIMESTAMP, Locale.US).format(Date()) + ".pdf"
        val outFile = File(exportDir, safeName)
        FileOutputStream(outFile).use { fos -> pdf.writeTo(fos) }
        pdf.close()
        return outFile
    }

    /**
     * Finalizar la página actual + abrir una página nueva + dibujar el encabezado y pie de página de la página nueva,volver nuevo (Page, Canvas).
     *
     * PdfDocument reglas:Primero debe finalizar la página anterior antes de poder iniciar la página nueva.;
     * encabezamiento/El pie de página se dibuja inmediatamente después de abrir la nueva página.(Cada página tiene un encabezado y pie de página.).
     */
    private fun turnPage(
        pdf: PdfDocument,
        currentPage: PdfDocument.Page,
        currentPageIndex: Int,
        title: String,
        exportTime: String,
        titlePaint: Paint,
        timePaint: Paint,
        footerPaint: Paint,
    ): Pair<PdfDocument.Page, Canvas> {
        pdf.finishPage(currentPage)
        val newPageIndex = currentPageIndex + 1
        val newPage = pdf.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, newPageIndex).create(),
        )
        val newCanvas = newPage.canvas
        drawHeader(newCanvas, title, exportTime, titlePaint, timePaint)
        drawFooter(newCanvas, newPageIndex, footerPaint)
        return newPage to newCanvas
    }

    // ===== Método de ayuda privada =====

    /** C-24: Las etiquetas de roles se generan por configuración regional(zh → chino,el resto → ingles). */
    private fun roleLabel(role: MessageRole, locale: Locale): String = when (role) {
        MessageRole.USER -> localized(locale, "Usuario", "User")
        MessageRole.ASSISTANT -> localized(locale, "asistente", "Assistant")
        MessageRole.SYSTEM -> localized(locale, "sistema", "System")
        MessageRole.TOOL -> localized(locale, "Herramientas", "Tool")
    }

    /**
     * C-24: Exportación de copywriting mapeo bilingüe. Salida local china [zh],Resto de la salida local [en].
     *
     * El producto exportado es el contenido del archivo en lugar del recurso de cadena de la interfaz de usuario.,Simplemente procéselo en el lugar de acuerdo con el bilingüe al realizar el mapeo.(No introducido
     * strings Recursos,Evite mantener un conjunto de recursos de Android para la salida de archivos).
     */
    private fun localized(locale: Locale, zh: String, en: String): String = if (locale.language.lowercase().let { it.startsWith("es") || it.startsWith("zh") }) zh else en

    /**
     * Dibujar encabezado(título + fecha + separador).
     *
     * @param titlePaint Pincel de título de sesión(16pt Negrita)
     * @param timePaint Fecha/cepillo divisor(10pt)
     */
    private fun drawHeader(canvas: Canvas, title: String, exportTime: String, titlePaint: Paint, timePaint: Paint) {
        // título superior(16pt Negrita,Truncado más allá de 40 caracteres)
        val titleText = if (title.length > 40) title.take(40) + "…" else title
        canvas.drawText(titleText, PAGE_MARGIN, PAGE_MARGIN + SIZE_TITLE, titlePaint)
        // Fecha de exportación a la derecha(10pt)
        val timeWidth = timePaint.measureText(exportTime)
        canvas.drawText(
            exportTime,
            PAGE_WIDTH - PAGE_MARGIN - timeWidth,
            PAGE_MARGIN + SIZE_META,
            timePaint,
        )
        // Línea separadora bajo el título
        canvas.drawLine(
            PAGE_MARGIN,
            PAGE_MARGIN + SIZE_TITLE + 4f,
            PAGE_WIDTH - PAGE_MARGIN,
            PAGE_MARGIN + SIZE_TITLE + 4f,
            timePaint,
        )
    }

    /** Dibuja los números de página del pie de página. */
    private fun drawFooter(canvas: Canvas, pageIndex: Int, paint: Paint) {
        val text = "$pageIndex"
        val width = paint.measureText(text)
        canvas.drawText(
            text,
            (PAGE_WIDTH - width) / 2f,
            PAGE_HEIGHT - PAGE_MARGIN + 4f,
            paint,
        )
    }

    /**
     * v2.6.8: Exportar texto —— ir juntos [InternalMarkupSanitizer.exportContent].
     *
     * Exportar es algo que los usuarios mostrarán al mundo exterior. Filtrar el JSON del estado de la tarjeta equivale a enviar los campos de la máquina como contenido.
     * Reunidos en un solo lugar aquí,Markdown / JSON / HTML / PDF Los cuatro caminos son imperdibles.
     */
    private fun exportBody(msg: UIMessage): String = InternalMarkupSanitizer.exportContent(msg.content)

    /** Representa el cuerpo del mensaje como párrafo HTML + bloque de código. */
    private fun renderContentHtml(content: String): String {
        if (content.isBlank()) return ""
        val sb = StringBuilder()
        // Bloques de código divididos(```...```)
        val codeBlockRegex = Regex("""```(\w*)\n?([\s\S]*?)```""")
        var lastEnd = 0
        codeBlockRegex.findAll(content).forEach { match ->
            // Texto sin formato antes del bloque de código
            if (match.range.first > lastEnd) {
                val plain = content.substring(lastEnd, match.range.first)
                sb.append(plainToParagraphs(plain))
            }
            val lang = match.groupValues[1]
            val code = match.groupValues[2]
            // C-09: lang Inyectar atributos HTML,Se requiere escape de atributos(>,",' espera),Evitar que se muestren indicadores de idioma de bloque de código.
            // Utilizado para propiedades de escape./cerrado <pre> etiqueta. aunque regular `(\w*)` Caracteres de palabras limitados,Todavía escapa a la defensa.
            val langClass = if (lang.isNotBlank()) " class=\"language-${escapeHtmlAttr(lang)}\"" else ""
            sb.append("<pre><code$langClass>").append(escapeHtml(code)).append("</code></pre>")
            lastEnd = match.range.last + 1
        }
        // Texto normal restante al final
        if (lastEnd < content.length) {
            sb.append(plainToParagraphs(content.substring(lastEnd)))
        }
        return sb.toString()
    }

    /** Dividir texto normal en líneas en blanco,Empaque uno para cada segmento <p>. */
    private fun plainToParagraphs(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.split(Regex("""\n{2,}""")).joinToString("") { para ->
            val safe = escapeHtml(para).replace("\n", "<br>")
            "<p>$safe</p>"
        }
    }

    /** Escapar del contenido de texto HTML(& < >). */
    private fun escapeHtml(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * C-09: Escapar de valores de atributos de comillas dobles HTML(& < > " + ').
     * Para contenido de texto [escapeHtml] Eso es todo,pero inyectado en `class="..."` Los atributos también deben ir entre comillas.,
     * Evitar que los valores de los atributos escapen de las etiquetas de cierre(attribute-context escapar).
     */
    private fun escapeHtmlAttr(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** Escapar de los valores de los atributos HTML(Citas escapadas adicionales). */
    private fun escapeAttr(s: String): String = escapeHtml(s).replace("\"", "&quot;")

    /** Convierta títulos de sesiones en nombres de archivos seguros. */
    private fun sanitizeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_").take(40).ifBlank { "muse" }

    /** HTML en línea CSS(Estilo de burbuja de chat + adaptación de colores claros y oscuros). */
    private val CSS = """
        :root {
            color-scheme: light dark;
        }
        * { box-sizing: border-box; }
        body {
            margin: 0;
            padding: 24px 16px 60px;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Helvetica Neue", Arial, sans-serif;
            font-size: 15px;
            line-height: 1.6;
            background: #f5f5f7;
            color: #1a1a1a;
        }
        .chat-header {
            text-align: center;
            margin-bottom: 24px;
            padding-bottom: 16px;
            border-bottom: 1px solid rgba(0, 0, 0, 0.08);
        }
        .chat-header h1 {
            margin: 0 0 6px;
            font-size: 22px;
            font-weight: 600;
        }
        .chat-header .meta {
            margin: 0;
            font-size: 12px;
            color: #888;
        }
        .chat-list {
            max-width: 760px;
            margin: 0 auto;
            display: flex;
            flex-direction: column;
            gap: 14px;
        }
        .bubble {
            max-width: 80%;
            padding: 12px 16px;
            border-radius: 18px;
            box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        .bubble.user {
            align-self: flex-end;
            background: #0a6cff;
            color: #ffffff;
            border-bottom-right-radius: 4px;
        }
        .bubble.assistant {
            align-self: flex-start;
            background: #ffffff;
            color: #1a1a1a;
            border: 1px solid rgba(0, 0, 0, 0.05);
            border-bottom-left-radius: 4px;
        }
        .bubble .role {
            font-size: 12px;
            font-weight: 600;
            margin-bottom: 4px;
            opacity: 0.85;
        }
        .bubble .content p {
            margin: 0 0 8px;
        }
        .bubble .content p:last-child {
            margin-bottom: 0;
        }
        .bubble .content pre {
            margin: 8px 0;
            padding: 12px;
            background: rgba(0, 0, 0, 0.06);
            border-radius: 10px;
            overflow-x: auto;
            font-size: 13px;
            font-family: "SF Mono", Menlo, Consolas, monospace;
        }
        .bubble.user .content pre {
            background: rgba(255, 255, 255, 0.18);
        }
        .bubble .content code {
            font-family: "SF Mono", Menlo, Consolas, monospace;
        }
        .bubble .time {
            margin-top: 6px;
            font-size: 11px;
            opacity: 0.6;
            text-align: right;
        }
        .bubble.assistant .time {
            text-align: left;
        }
        .inline-img {
            display: block;
            max-width: 100%;
            border-radius: 10px;
            margin: 8px 0 0;
        }
        .remote-links {
            display: flex;
            flex-wrap: wrap;
            gap: 10px;
            margin-top: 8px;
        }
        .remote-link {
            display: inline-block;
            font-size: 13px;
            color: #0a6cff;
            text-decoration: underline;
        }
        .reasoning {
            margin-top: 8px;
            font-size: 13px;
            opacity: 0.85;
        }
        .reasoning summary {
            cursor: pointer;
            font-weight: 500;
            opacity: 0.75;
        }
        .reasoning .content {
            margin-top: 6px;
            padding: 8px 10px;
            background: rgba(0, 0, 0, 0.04);
            border-radius: 8px;
        }
        @media (prefers-color-scheme: dark) {
            body {
                background: #000000;
                color: #f2f2f2;
            }
            .chat-header {
                border-bottom-color: rgba(255, 255, 255, 0.12);
            }
            .bubble.assistant {
                background: #1c1c1e;
                color: #f2f2f2;
                border-color: rgba(255, 255, 255, 0.08);
            }
            .bubble .content pre {
                background: rgba(255, 255, 255, 0.08);
            }
            .bubble.user .content pre {
                background: rgba(255, 255, 255, 0.18);
            }
            .reasoning .content {
                background: rgba(255, 255, 255, 0.06);
            }
        }
    """.trimIndent()
}
