package io.zer0.muse.doc

import android.content.Context
import android.net.Uri
import androidx.core.text.HtmlCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.R
import okhttp3.OkHttpClient
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.Writer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Phase 5-E + Phase 8.6: Analizador de documentos.
 *
 * Formatos soportados:
 *  - text subtipo(txt/md/csv/json/xml/html/log):Leer texto sin formato directamente
 *  - application/pdf: Extraer texto con PDFBox-Android(Phase 8.6 Mejora,extracción de texto real)
 *  - docx(office open xml): Analizar la extracción de word/document.xml <w:t> texto
 *  - pptx: analizar ppt/slides/slide*.xml Extracto <a:t> texto
 *  - xlsx: analizar xl/sharedStrings.xml + xl/worksheets/sheet*.xml Extraer texto de celda(v1.133 Nuevo)
 *  - epub: ZIP + OPF spine + XHTML pelado de etiquetas
 *  - fotos(image subtipo): la persona que llama va OcrManager,Esta categoría no maneja
 *
 * control de volumen: texto truncado a 1MB(Evite archivos muy grandes OOM).
 *
 * Cumplimiento: Todo el código se escribe de forma independiente.,Implementación de terceros no copiada(Para soluciones similares Apache POI,Este proyecto utiliza análisis OOXML autoimplementado).
 */
class DocumentParser(
    private val httpClient: OkHttpClient = CloudDocumentParser.defaultHttpClient(),
) {

    companion object {
        /** L-DP6: Límite de truncamiento de texto(1MB),estimación aproximada chars*2 ≈ bytes. */
        private const val MAX_TEXT_BYTES = 1 shl 20

        /** H-DP1: ZIP Límite de descompresión de entrada única(4MB),Protegido contra bombas ZIP. */
        private const val ZIP_MAX_BYTES = 4 * (1 shl 20)

        /** Límite de tamaño de carga en la nube(50MB),Evite OOM de archivos muy grandes. */
        private const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

        /** v2.2.1: PDFBox presupuesto de memoria principal(32MB),La porción excedida se derramó en un archivo temporal,Evite que el análisis de PDF de gran tamaño llene el montón. */
        private const val PDF_MAIN_MEMORY_BUDGET_BYTES = 32L * (1 shl 20)
    }

    /** PDFBox Necesito una sola vez init(Cargar recursos de fuentes). */
    private val pdfBoxInited = AtomicBoolean(false)

    /** CLOUD / MinerU Cliente de análisis de la nube. */
    private val cloudParser = CloudDocumentParser(httpClient)

    /**
     * M-DP2: Analizar documento,Regresar [Result].
     * Texto plano llevado con éxito(Puede ser una cadena vacía);Retorno del fracaso [Result.Error] Llevar mensajes legibles.
     * @param uri contenido URI(SAF Regresar)
     * @param context usado para ContentResolver + PDFBox init
     *
     * Mensaje de error extraído al recurso strings_data.xml,readXxx Utilizado dentro de los métodos. context.getString() Conseguir.
     * marca de truncamiento(Como por ejemplo"[… Archivo demasiado grande,truncado …]")es parte de la estructura del texto,No se considera redacción publicitaria de UI.
     */
    fun parseResult(uri: Uri, context: Context): Result<String> {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: guessMimeFromName(uri.toString())
        return resultOf {
            when {
                mimeType == null -> readAsText(uri, context)
                mimeType.startsWith("text/") -> readAsText(uri, context)
                mimeType == "application/pdf" -> readPdf(uri, context)
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    readDocx(uri, context)
                mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    readPptx(uri, context)
                mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    readXlsx(uri, context)
                mimeType == "application/epub+zip" -> readEpub(uri, context)
                // Ten todo en cuenta:Inténtalo de nuevo por extensión
                else -> {
                    val lower = uri.toString().lowercase()
                    when {
                        lower.endsWith(".pdf") -> readPdf(uri, context)
                        lower.endsWith(".docx") -> readDocx(uri, context)
                        lower.endsWith(".pptx") -> readPptx(uri, context)
                        lower.endsWith(".xlsx") -> readXlsx(uri, context)
                        lower.endsWith(".epub") -> readEpub(uri, context)
                        else -> readAsText(uri, context)
                    }
                }
            }
        }
    }

    /**
     * Analizar documento,Devuelve texto sin formato.
     *
     * M-DP2: Antiguo portal de compatibilidad de llamadas. Devuelve una cadena vacía en caso de error(en lugar de una copia incorrecta),Verificado por la persona que llama según isBlank;
     * Las nuevas personas que llaman deben usar [parseResult] para obtener el motivo del error.
     *
     * @param uri contenido URI(SAF Regresar)
     * @param context usado para ContentResolver + PDFBox init
     * @return Texto extraído;Devuelve una cadena vacía en caso de error o contenido vacío
     */
    fun parse(uri: Uri, context: Context): String = parseResult(uri, context).getOrNull() ?: ""

    /**
     * v1.0.47 P7-1: Según [RagConfig.documentParserType] Análisis de documentos de enrutamiento.
     *
     * - LOCAL: llamar local [parseResult](Predeterminado)
     * - CLOUD/MINERU: Subir archivos a endpoint → sondeo → Extraer texto semántico;
     *   Devuelve un error explícito cuando el punto final no está configurado,No más rebajas silenciosas LOCAL
     *
     * @param uri contenido URI
     * @param context Context
     * @param parserType tipo de analizador(de RagConfig)
     * @param cloudEndpoint Análisis de la nube endpoint(CLOUD Para tipo)
     * @param mineruEndpoint MinerU endpoint(MINERU Para tipo)
     * @param mineruToken MinerU Token(MINERU Para tipo,como Bearer Token)
     */
    fun parseResult(
        uri: Uri,
        context: Context,
        parserType: io.zer0.muse.rag.RagConfig.ParserType,
        cloudEndpoint: String = "",
        mineruEndpoint: String = "",
        mineruToken: String = "",
    ): Result<String> {
        return when (parserType) {
            io.zer0.muse.rag.RagConfig.ParserType.LOCAL ->
                parseResult(uri, context)
            io.zer0.muse.rag.RagConfig.ParserType.CLOUD -> {
                if (cloudEndpoint.isBlank()) {
                    Result.Error("El punto final de análisis de la nube no está configurado; complételo en la configuración de RAG.")
                } else {
                    parseCloud(uri, context, cloudEndpoint, token = "", mineruMode = false)
                }
            }
            io.zer0.muse.rag.RagConfig.ParserType.MINERU -> {
                if (mineruEndpoint.isBlank()) {
                    Result.Error("MinerU endpoint No configurado, complete la configuración de RAG")
                } else {
                    parseCloud(uri, context, mineruEndpoint, token = mineruToken, mineruMode = true)
                }
            }
        }
    }

    /** Leer bytes de archivo y entregarlos a [CloudDocumentParser] Subir análisis. */
    private fun parseCloud(uri: Uri, context: Context, endpoint: String, token: String, mineruMode: Boolean): Result<String> {
        val bytes = readUploadBytes(uri, context)
            ?: return Result.Error("No se puede leer el archivo que se va a analizar")
        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast('%')
            ?.ifBlank { null }
            ?: "document"
        return cloudParser.parse(bytes, fileName, endpoint, token, mineruMode)
    }

    /** La carga en la nube utiliza lectura de bytes(límite superior 50MB,protegerse contra OOM). */
    private fun readUploadBytes(uri: Uri, context: Context): ByteArray? {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var n = input.read(buf)
            while (n > 0) {
                total += n
                if (total > MAX_UPLOAD_BYTES) {
                    throw IOException("El archivo supera los 50 MB y no se puede cargar en la nube para su análisis.")
                }
                baos.write(buf, 0, n)
                n = input.read(buf)
            }
            return baos.toByteArray()
        }
        return null
    }

    /**
     * Leer archivo de texto sin formato(txt/md/csv/json/xml/html).1MB Truncar para evitar OOM.
     *
     * M-DP3: Detección de codificación——
     *  1. Leer los primeros 3 bytes olfatear BOM:UTF-8(EF BB BF)/ UTF-16 LE(FF FE)/ UTF-16 BE(FE FF)
     *  2. Si no hay ninguna lista de materiales, intente con la decodificación estricta UTF-8.;Si falla, presione GBK para guardar el dinero.(Codificaciones comunes de Windows en chino)
     *  3. GBK Si esto falla, recurra a la decodificación relajada UTF-8.(Reemplazar bytes ilegales)
     */
    private fun readAsText(uri: Uri, context: Context): String {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            // Lea todos los bytes primero(limitado MAX_TEXT_BYTES,Archivos anti-sobredimensionados OOM)
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            var n = input.read(buf)
            while (n > 0) {
                total += n
                if (total > MAX_TEXT_BYTES) {
                    baos.write(buf, 0, n - (total - MAX_TEXT_BYTES))
                    break
                }
                baos.write(buf, 0, n)
                n = input.read(buf)
            }
            val bytes = baos.toByteArray()
            val truncated = total > MAX_TEXT_BYTES
            val charset = detectCharset(bytes)
            val text = String(bytes, charset).trim()
            return if (truncated) {
                "$text\n\n[… Archivo demasiado grande,Truncado. El archivo original excede 1MB,Leer solo el primer 1MB …]"
            } else {
                text
            }
        }
        return ""
    }

    /**
     * M-DP3: Huele la codificación de caracteres de una secuencia de bytes.
     * - Hay BOM: presione BOM para regresar a la correspondiente charset
     * - Sin BOM: pruebe la decodificación estricta UTF-8,Retorno del fracaso GBK
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        // BOM oler
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE
        }
        // Sin BOM: pruebe la decodificación estricta UTF-8
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            StandardCharsets.UTF_8
        }.getOrElse {
            // UTF-8 Falló la decodificación,Presione GBK para más detalles(GBK Casi no se informará ningún error para ningún byte);
            // GBK Reserva si no está disponible UTF-8(La construcción posterior de cadenas reemplazará los bytes ilegales)
            runCatching { Charset.forName("GBK") }.getOrElse { StandardCharsets.UTF_8 }
        }
    }

    /**
     * Phase 8.6: PDF Extracción de texto(usar PDFBox-Android).
     *
     * Phase 11.2.3 mejorar:Procesamiento de diseño complejo(L7)
     *  - Metadatos del documento(Título/Autor/Tema/palabras clave/tiempo de creación)→ prefijo "[Información del documento]" bloquear
     *  - Directorio de documentos(PDOutline recursividad)→ prefijo "[Directorio]" bloquear(Hasta 2 niveles)
     *  - stripper parámetros:sortByPosition + Separación de párrafos(Las líneas en blanco fortalecen los párrafos)
     *  - encabezamiento/Filtro de pie de página:Repetir en todas las páginas(>=3 página)el primero/La última línea se considera encabezado y pie de página.,Selección posprocesamiento
     *
     * PDFBox-Android ¿Es la implementación de Android de Apache PDFBox?,apoyo:
     *  - extracción de texto real(PDFTextStripper,Basado en objetos de texto PDF)
     *  - Cifrar PDF Descifrar(Si se conoce la contraseña)
     *  - No depende del nativo PdfRenderer(Sin retransmisión de mapa de bits)
     *
     * limitaciones:Tipo de escaneo PDF(tipo de imagen)La extracción de texto vuelve vacía,Se requiere OCR.
     */
    private fun readPdf(uri: Uri, context: Context): String {
        ensurePdfBoxInited(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input, pdfMemorySetting(context)).use { doc ->
                val total = doc.numberOfPages
                val maxPages = 200
                val end = minOf(total, maxPages)

                // Step 1: Extraer texto página por página(Preservar los separadores de páginas para facilitar el filtrado de encabezados y pies de página)
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                // Separación de párrafos:PDFBox Detectar párrafos por sangría de forma predeterminada,Utilice líneas en blanco aquí para reforzar la separación de párrafos.
                stripper.paragraphStart = "\n\n"
                stripper.paragraphEnd = "\n\n"
                stripper.wordSeparator = " "

                // v2.2.1: Extraer página por página para“con límite superior Writer” —— PDFBox El getText internamente está destapado.
                // StringWriter,enfermo/Una sola página de texto en un PDF muy grande puede expandirse a cientos de MB. OOM(2026-09-27
                // Comentarios de los usuarios:La importación de PDF de la base de conocimientos falla). Calibre límite superior y existente. chars*2≈bytes coherente.
                val charBudget = MAX_TEXT_BYTES / 2
                val pageTexts = mutableListOf<String>()
                var usedChars = 0
                var truncatedByLimit = false
                for (i in 1..end) {
                    val remaining = charBudget - usedChars
                    if (remaining <= 0) {
                        truncatedByLimit = true
                        break
                    }
                    stripper.startPage = i
                    stripper.endPage = i
                    val writer = CappedStringWriter(remaining)
                    val pageResult = runCatching { stripper.writeText(doc, writer) }
                    val pageText = writer.toString()
                    if (writer.hitLimit) {
                        // golpear la tapa:Conservar la parte extraída.,Detener páginas siguientes
                        if (pageText.isNotBlank()) pageTexts.add(pageText)
                        truncatedByLimit = true
                        break
                    }
                    if (pageResult.isFailure) continue
                    usedChars += pageText.length
                    pageTexts.add(pageText)
                }

                // Step 2: encabezamiento/Filtro de pie de página(Repetir encabezado en todas las páginas/fila de cola)
                val cleanedTexts = filterHeadersFooters(pageTexts)

                // Step 3: Fusionar texto(La fase de extracción ha sido reforzada por charBudget,Sólo empalme aquí)
                val sb = StringBuilder()
                cleanedTexts.forEach { pageText -> sb.append(pageText) }
                if (truncatedByLimit) {
                    sb.appendLine()
                    sb.appendLine("[… PDF El contenido es demasiado largo.,truncado …]")
                }
                // L-DP8: Las sugerencias de números de página y las marcas de truncamiento no se afectan entre sí
                if (total > maxPages) {
                    sb.appendLine()
                    sb.appendLine("[… PDF total $total página,Sólo antes de la extracción $maxPages página …]")
                }

                // Step 4: Pre-metadatos + directorio(Si es así)
                val meta = extractPdfMetadata(doc)
                val toc = extractPdfOutline(doc)
                val prefix = buildString {
                    if (meta.isNotBlank()) {
                        appendLine("[Información del documento]")
                        appendLine(meta)
                        appendLine()
                    }
                    if (toc.isNotBlank()) {
                        appendLine("[Directorio]")
                        appendLine(toc)
                        appendLine()
                    }
                }

                val bodyText = sb.toString().trim()
                val text = if (prefix.isNotBlank()) "$prefix$bodyText" else bodyText
                return if (text.isBlank()) {
                    context.getString(R.string.doc_err_pdf_text_empty, total)
                } else {
                    text
                }
            }
        }
        return context.getString(R.string.doc_err_open_pdf)
    }

    /**
     * Phase 11.2.3: Extraer metadatos PDF(PDDocumentInformation).
     *
     * PDF campos de metadatos:Title / Author / Subject / Keywords / Creator / Producer / CreationDate.
     * Devolver solo campos que no estén vacíos,Formato "campo: valor".
     */
    private fun extractPdfMetadata(doc: PDDocument): String {
        return runCatching {
            val info: PDDocumentInformation? = doc.documentInformation
            val sb = StringBuilder()
            info ?: return@runCatching ""
            listOf(
                "Título" to info.title,
                "Autor" to info.author,
                "Tema" to info.subject,
                "palabras clave" to info.keywords,
                "Creador" to info.creator,
                "generador" to info.producer,
            ).forEach { (label, value) ->
                if (!value.isNullOrBlank()) {
                    sb.appendLine("$label: $value")
                }
            }
            // tiempo de creación(Opcional)
            runCatching {
                val cal = info.creationDate
                if (cal != null) {
                    sb.appendLine("tiempo de creación: ${cal.time}")
                }
            }
            sb.toString().trim()
        }.getOrDefault("")
    }

    /**
     * Phase 11.2.3: Extraer directorios PDF de forma recursiva(PDOutlineItem).
     *
     * Límite:
     *  - Hasta 2 niveles de profundidad(Evite el spam de directorio profundo)
     *  - Hasta 100 artículos(Truncamiento de directorios muy largos.)
     *  - tomar solo title,No analizado destination(Es necesario analizar el número de página de destino. PDPageDestination,
     *    Alta complejidad,Y tiene poco valor para la extracción pura de texto.,reservado para expansión)
     */
    private fun extractPdfOutline(doc: PDDocument): String {
        return runCatching {
            val outline: PDDocumentOutline? = doc.documentCatalog?.documentOutline
            outline ?: return@runCatching ""
            val sb = StringBuilder()
            var count = 0
            val maxItems = 100
            fun walk(item: PDOutlineItem?, depth: Int) {
                if (item == null || count >= maxItems) return
                val title = item.title
                if (!title.isNullOrBlank()) {
                    val indent = if (depth == 0) "" else "  ".repeat(depth)
                    sb.appendLine("$indent• $title")
                    count++
                }
                // nodo hijo recursivo(Hasta 2 niveles)
                if (depth < 1) {
                    var child = item.firstChild
                    while (child != null && count < maxItems) {
                        walk(child, depth + 1)
                        child = child.nextSibling
                    }
                }
            }
            var top = outline.firstChild
            while (top != null && count < maxItems) {
                walk(top, 0)
                top = top.nextSibling
            }
            if (count >= maxItems) {
                sb.appendLine("… (Directorio demasiado largo,truncado) …")
            }
            sb.toString().trim()
        }.getOrDefault("")
    }

    /**
     * Phase 11.2.3: encabezamiento/Filtrado de pie de página.
     *
     * heurístico:Obtenga las 2 primeras líneas + las 2 últimas líneas de cada página de texto,Cuente el número de repeticiones entre páginas.
     * Repetir >= 3 Las filas de la página se tratan como encabezados./Pie de página,Excluido de todas las páginas.
     *
     * limitaciones:
     *  - documento corto(< 3 página)No activa el filtrado(mantener todo el contenido)
     *  - Los números de página son diferentes para cada página.(Como por ejemplo "1/10","2/10")no será reconocido como un duplicado,Reservar
     *  - Riesgo de error de valoración de los títulos de los capítulos:Si la primera línea de un capítulo se repite en varias páginas(raro),será eliminado por error
     */
    private fun filterHeadersFooters(pageTexts: List<String>): List<String> {
        if (pageTexts.size < 3) return pageTexts

        // Estadísticas en la parte superior de cada página./El número de apariciones de la última fila.
        val lineCounts = mutableMapOf<String, Int>()
        val candidates = mutableListOf<List<String>>() // Filas candidatas por página(primero2+cola2)
        pageTexts.forEach { pageText ->
            val lines = pageText.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) {
                candidates.add(emptyList())
                return@forEach
            }
            val head = lines.take(2)
            val tail = lines.takeLast(2)
            val candidate = (head + tail).distinct()
            candidates.add(candidate)
            candidate.forEach { line ->
                lineCounts[line] = (lineCounts[line] ?: 0) + 1
            }
        }

        // Repetir >= 3 Encabezados y pies de página
        val headerFooterLines = lineCounts.filter { it.value >= 3 }.keys

        if (headerFooterLines.isEmpty()) return pageTexts

        // Elimina estas líneas de cada página de texto.(Coincidir con toda la línea,Evite eliminar accidentalmente texto dentro de un párrafo)
        return pageTexts.map { pageText ->
            pageText.lines().filter { line ->
                line.isBlank() || !headerFooterLines.contains(line)
            }.joinToString("\n")
        }
    }

    /** PDFBox-Android Requerido antes del primer uso. init(Cargar recursos de fuentes integrados). */
    private fun ensurePdfBoxInited(context: Context) {
        if (pdfBoxInited.compareAndSet(false, true)) {
            // v1.25: Revertir bandera y falla de inicio de sesión,Permitir reintentos posteriores
            runCatching { PDFBoxResourceLoader.init(context) }
                .onFailure { e ->
                    pdfBoxInited.set(false)
                    Logger.w("DocumentParser", "PDFBox La inicialización falló", e)
                }
        }
    }

    /**
     * v2.2.1: PDF Política de memoria de análisis —— presupuesto de memoria principal 32MB,La parte sobrante se desborda automáticamente a archivos temporales en el directorio de caché de la aplicación.
     *
     * grande PDF(Contiene muchas fotos/fuente)De forma predeterminada, la cantidad total reside en la memoria principal.,El montón se puede llenar directamente durante la fase de análisis.;Cambiar a"Limitado+
     * bolsillo para disco"después,Desacoplar la memoria de análisis del tamaño del archivo.
     */
    private fun pdfMemorySetting(context: Context): MemoryUsageSetting = MemoryUsageSetting.setupMixed(PDF_MAIN_MEMORY_BUDGET_BYTES)
        .setTempDir(File(context.cacheDir, "pdfbox-tmp").apply { mkdirs() })

    /**
     * v2.2.1: cuerda tapada Writer(Evite que la extracción de texto PDF crezca ilimitadamente).
     *
     * PDFBox de [PDFTextStripper.getText] Uso interno ilimitado StringWriter,enfermo/PDF extra grandes
     * Una sola página de texto puede expandirse a cientos de MB y provocar OOM;Usar en su lugar [PDFTextStripper.writeText] + ben Writer,
     * exceder [limit] Los personajes son desechados. [TextLimitExceededException],Truncado por la persona que llama,La matriz ya no se expande.
     */
    private class CappedStringWriter(private val limit: Int) : Writer() {
        private val sb = StringBuilder()
        var hitLimit = false
            private set

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (hitLimit) throw TextLimitExceededException()
            val room = limit - sb.length
            if (len >= room) {
                if (room > 0) sb.append(cbuf, off, room)
                hitLimit = true
                throw TextLimitExceededException()
            }
            sb.append(cbuf, off, len)
        }

        override fun write(c: Int) {
            if (hitLimit) throw TextLimitExceededException()
            if (sb.length >= limit) {
                hitLimit = true
                throw TextLimitExceededException()
            }
            sb.append(c.toChar())
        }

        override fun flush() = Unit

        override fun close() = Unit

        override fun toString(): String = sb.toString()
    }

    /** Extraer marcas de golpes de tapa:Solo se usa para interrumpir el ciclo de escritura de PDFBox,No expuesto hacia arriba. */
    private class TextLimitExceededException : IOException("PDF text extraction exceeded the configured limit")

    /**
     * Phase 8.6: DOCX Extracción de texto(Análisis OOXML autoimplementado).
     *
     * DOCX es un paquete ZIP,documento principal:
     *  - word/document.xml: Cuerpo del documento
     *
     * estructura del texto:
     *  - <w:p>: párrafo
     *  - <w:t>: fragmento de texto
     *  - <w:tab/>: carácter de tabulación
     *  - <w:br/>: salto de línea suave
     *  - <w:cr/>: duro retorno
     *
     * estrategia de análisis: atravesar document.xml,prensa <w:p> Cortar en secciones,fusionar <w:t> texto,
     * encuentro <w:tab>/<w:br>/<w:cr> girar \t/\n,Saltos de línea entre párrafos.
     */
    private fun readDocx(uri: Uri, context: Context): String {
        val xml = readZipEntry(context, uri, "word/document.xml")
            ?: return context.getString(R.string.doc_err_docx_no_document)
        val sb = StringBuilder()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var inParagraph = false
        val paragraphText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.substringAfterLast(':') ?: ""
                    when (name) {
                        "p" -> { // Nota:OOXML espacio de nombres predeterminado,El nombre local es p/t/tab/br/cr
                            inParagraph = true
                            paragraphText.setLength(0)
                        }
                        "t" -> {
                            val text = parser.nextText()
                            if (inParagraph) paragraphText.append(text)
                        }
                        "tab" -> if (inParagraph) paragraphText.append('\t')
                        "br", "cr" -> if (inParagraph) paragraphText.append('\n')
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name?.substringAfterLast(':') == "p" && inParagraph) {
                        inParagraph = false
                        if (paragraphText.isNotEmpty()) {
                            sb.appendLine(paragraphText.toString())
                        }
                        // M-DP4: Verifique la longitud acumulada después de cada segmento.,Si supera 1 MB, se truncará y fusionará. break
                        if (sb.length * 2 > MAX_TEXT_BYTES) {
                            sb.appendLine("[… DOCX El contenido es demasiado largo.,truncado …]")
                            break
                        }
                    }
                }
            }
            event = parser.next()
        }
        return truncate(sb.toString().trim())
    }

    /**
     * Phase 8.6: PPTX Extracción de texto(Análisis OOXML autoimplementado).
     *
     * PPTX es un paquete ZIP,documento principal:
     *  - ppt/slides/slide1.xml, slide2.xml, ... : Diapositivas para cada página.
     *
     * estructura del texto:
     *  - <a:p>: párrafo
     *  - <a:t>: fragmento de texto
     *  - <a:br>: salto de línea suave
     *
     * estrategia de análisis: Ordenar por nombre de archivo slide{N}.xml,Salida por página "— Slide N —" separar,
     * fusionar <a:t> texto.
     */
    private fun readPptx(uri: Uri, context: Context): String {
        val slideNames = listZipEntries(context, uri)
            .filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
            .sortedBy { name ->
                name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0
            }
        if (slideNames.isEmpty()) return context.getString(R.string.doc_err_pptx_no_slides)
        val sb = StringBuilder()
        for ((idx, slideName) in slideNames.withIndex()) {
            val xml = readZipEntry(context, uri, slideName) ?: continue
            sb.appendLine("— Slide ${idx + 1} —")
            val parser = newSecurePullParser()
            parser.setInput(xml.reader())
            var inParagraph = false
            val paragraphText = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name?.substringAfterLast(':')) {
                            "p" -> {
                                inParagraph = true
                                paragraphText.setLength(0)
                            }
                            "t" -> {
                                val text = parser.nextText()
                                if (inParagraph) paragraphText.append(text)
                            }
                            "br" -> if (inParagraph) paragraphText.append('\n')
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name?.substringAfterLast(':') == "p" && inParagraph) {
                            inParagraph = false
                            if (paragraphText.isNotEmpty()) {
                                sb.appendLine(paragraphText.toString())
                            }
                        }
                    }
                }
                event = parser.next()
            }
            sb.appendLine()
            if (sb.length * 2 > MAX_TEXT_BYTES) {
                sb.appendLine("[… PPTX El contenido es demasiado largo.,truncado …]")
                break
            }
        }
        return truncate(sb.toString().trim())
    }

    /**
     * v1.133: XLSX Extracción de texto(Análisis OOXML autoimplementado,Sin dependencia de puntos de interés de Apache).
     *
     * XLSX es un paquete ZIP,documento principal:
     *  - xl/sharedStrings.xml: Tabla de cadenas compartida(todas las cadenas únicas,Índice en orden de aparición.)
     *  - xl/worksheets/sheet1.xml, sheet2.xml, ... : Cada hoja de trabajo
     *
     * sharedStrings estructura:
     *  - <sst><si><t>cuerda</t></si>...</sst>
     *  - <si> Puede contener múltiples <t>(formato de texto enriquecido),debería fusionarse
     *
     * worksheet estructura:
     *  - <worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row></sheetData></worksheet>
     *  - <c> El atributo t de la etiqueta.:
     *    - no existe o "n":numeros,<v> es un numero
     *    - "s":cadena compartida,<v> es el índice de cadenas compartidas
     *    - "inlineStr":cadena en línea,<is><t>valor</t></is>
     *    - "str":cadena de fórmula,<v> es el resultado del cálculo de la fórmula
     *    - "b":Booleano,<v> si 0/1
     *    - "e":error,<v> es la cadena de error
     *
     * estrategia de análisis:
     *  1. leer sharedStrings.xml,analizar todo <si> llegar List<String>(en orden de aparición)
     *  2. Ordenar por número de nombre de archivo sheet{N}.xml,Salida por página "— Sheet N —" separar
     *  3. perseguir <row> analizar,perseguir <c> Valor de salida(separado por tabulaciones),Avance de línea al final de la línea
     *
     * Simplifica el punto:
     *  - No analice workbook.xml + rels para obtener el nombre de la hoja(Ordenar por nombre de archivo hojaN.xml,Los escenarios de extracción de texto sin formato son suficientes)
     *  - Las celdas de fórmula solo toman <v> Resultado del cálculo,no tomar <f> fórmula en sí
     *  - coordenadas de la celda(r Propiedades)Sólo se utiliza para posicionamiento.,No se utiliza para columnas de relleno(Es aceptable eliminar columnas vacías en escenarios de texto sin formato.)
     */
    private fun readXlsx(uri: Uri, context: Context): String {
        // 1. Leer tabla de cadenas compartidas(puede que no exista — medidor digital completo)
        val sharedStrings = parseSharedStrings(readZipEntry(context, uri, "xl/sharedStrings.xml"))

        // 2. Listar todos los archivos de hoja,Ordenar numéricamente
        val sheetNames = listZipEntries(context, uri)
            .filter { it.matches(Regex("""xl/worksheets/sheet\d+\.xml""")) }
            .sortedBy { name ->
                name.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 0
            }
        if (sheetNames.isEmpty()) return context.getString(R.string.doc_err_xlsx_no_sheets)

        val sb = StringBuilder()
        for ((idx, sheetName) in sheetNames.withIndex()) {
            val xml = readZipEntry(context, uri, sheetName) ?: continue
            sb.appendLine("— Sheet ${idx + 1} —")
            parseWorksheet(xml, sharedStrings, sb)
            sb.appendLine()
            if (sb.length * 2 > MAX_TEXT_BYTES) {
                sb.appendLine("[… XLSX El contenido es demasiado largo.,truncado …]")
                break
            }
        }
        return truncate(sb.toString().trim())
    }

    /**
     * v1.133: analizar xl/sharedStrings.xml — Regresar <si> Una lista de cadenas correspondientes al pedido.
     *
     * cada uno <si> Puede contener uno o más <t>(formato de texto enriquecido),fusionar todo <t> texto.
     * El error de análisis devuelve una lista vacía(Es posible que las tablas digitales puras no tengan este archivo).
     *
     * Nota:Visibilidad interna para que las pruebas unitarias llamen directamente(parseResult Es inconveniente probar usando ContentResolver).
     */
    internal fun parseSharedStrings(xml: String?): List<String> {
        if (xml.isNullOrBlank()) return emptyList()
        val list = mutableListOf<String>()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var inSi = false
        val siText = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "si" -> {
                            inSi = true
                            siText.setLength(0)
                        }
                        "t" -> if (inSi) {
                            // nextText consumirá START_TAG + TEXT + END_TAG,Orientar automáticamente después de END_TAG
                            siText.append(parser.nextText())
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si" && inSi) {
                        inSi = false
                        list.add(siText.toString())
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    /**
     * v1.133: analizar xl/worksheets/sheetN.xml — Extracto <row><c> valor de celda,añadir a [sb].
     *
     * cada uno <row> Salida de una línea,usado entre celdas \t Separado.
     * <c> Las etiquetas se analizan por atributo t:
     *  - "s":Verifique la tabla de cadenas compartidas
     *  - "inlineStr":leer <is><t>
     *  - Otros/Ninguno t:leer <v> valor original(numeros/resultado de la fórmula/Booleano/error)
     *
     * Nota:Visibilidad interna para pruebas unitarias para llamar directamente.
     */
    internal fun parseWorksheet(xml: String, sharedStrings: List<String>, sb: StringBuilder) {
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var inRow = false
        var inCell = false
        var cellType = "" // <c t="..."> El atributo t
        var inInlineStr = false // hay <is> dentro
        val rowCells = mutableListOf<String>()
        val cellBuffer = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "row" -> {
                            inRow = true
                            rowCells.clear()
                        }
                        "c" -> {
                            inCell = true
                            cellType = ""
                            cellBuffer.setLength(0)
                            // Leer atributo t
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i) == "t") {
                                    cellType = parser.getAttributeValue(i) ?: ""
                                }
                            }
                        }
                        "is" -> if (inCell) inInlineStr = true
                        "v" -> if (inCell) {
                            cellBuffer.append(parser.nextText())
                        }
                        "t" -> if (inCell && inInlineStr) {
                            cellBuffer.append(parser.nextText())
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    when (name) {
                        "is" -> inInlineStr = false
                        "c" -> {
                            if (inCell) {
                                // Convertir el contenido de cellBuffer según el tipo de celda
                                val raw = cellBuffer.toString()
                                val display = when {
                                    raw.isEmpty() -> ""
                                    cellType == "s" -> {
                                        // cadena compartida:raw es el índice
                                        val idx = raw.toIntOrNull()
                                        if (idx != null && idx in sharedStrings.indices) {
                                            sharedStrings[idx]
                                        } else {
                                            raw
                                        }
                                    }
                                    cellType == "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    // inlineStr / str / n / e / Ninguno t: Usar directamente raw
                                    else -> raw
                                }
                                rowCells.add(display)
                                inCell = false
                                cellType = ""
                                cellBuffer.setLength(0)
                            }
                        }
                        "row" -> {
                            if (inRow && rowCells.isNotEmpty()) {
                                sb.appendLine(rowCells.joinToString("\t"))
                            }
                            inRow = false
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    /**
     * Phase 8.6: EPUB Extracción de texto(autoimplementable).
     *
     * EPUB es un paquete ZIP,estructura:
     *  - META-INF/container.xml: Apuntar al archivo OPF
     *  - *.opf: lista bibliográfica(spine Las etiquetas definen el orden de lectura)
     *  - *.xhtml / *.html: archivo de contenido
     *
     * estrategia de análisis:
     *  1. Lea rootfile@full-path desde contenedor.xml para encontrar OPF
     *  2. Leer de OPF spine <itemref idref=...> Obtener orden de lectura
     *  3. de manifest <item id=... href=... media-type=...> tomar id → href mapeo
     *  4. Lea cada uno en el orden del lomo. XHTML,Quitar etiquetas HTML del texto de salida
     */
    private fun readEpub(uri: Uri, context: Context): String {
        // 1. Lea container.xml para encontrar la ruta OPF
        val containerXml = readZipEntry(context, uri, "META-INF/container.xml")
            ?: return context.getString(R.string.doc_err_epub_no_container)
        val opfPath = parseContainerForOpfPath(containerXml)
            ?: return context.getString(R.string.doc_err_epub_no_opf)
        val opfDir = opfPath.substringBeforeLast('/', "")

        // 2. leer OPF,Analizar manifiesto con spine
        val opfXml = readZipEntry(context, uri, opfPath)
            ?: return context.getString(R.string.doc_err_epub_missing_opf, opfPath)
        val manifest = parseOpfManifest(opfXml) // id → href
        val spine = parseOpfSpine(opfXml) // idref lista ordenada

        // 3. Leer XHTML en orden de lomo y quitar etiquetas
        val sb = StringBuilder()
        for (idref in spine) {
            val href = manifest[idref] ?: continue
            // M-DP5: Normalización después del empalme de caminos.,Manejar href en "../" Evite fuera de límites o fallos de coincidencia
            val path = resolveZipPath(opfDir, href)
            val xhtml = readZipEntry(context, uri, path) ?: continue
            val text = stripHtmlTags(xhtml)
            if (text.isNotBlank()) {
                sb.appendLine(text.trim())
                sb.appendLine()
            }
            if (sb.length * 2 > MAX_TEXT_BYTES) {
                sb.appendLine("[… EPUB El contenido es demasiado largo.,truncado …]")
                break
            }
        }
        return truncate(sb.toString().trim())
    }

    /** Analizar la ruta OPF desde contenedor.xml(rootfile@full-path). */
    private fun parseContainerForOpfPath(xml: String): String? {
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                // namespace puede ser "urn:oasis:names:tc:opendocument:xmlns:container"
                // Pero el nombre local es rootfile,full-path Es posible que la propiedad no tenga espacio de nombres
                for (i in 0 until parser.attributeCount) {
                    val attrName = parser.getAttributeName(i)
                    if (attrName == "full-path") {
                        return parser.getAttributeValue(i)
                    }
                }
            }
            event = parser.next()
        }
        return null
    }

    /** Analizar desde OPF manifest:<item id=... href=... media-type=.../>. */
    private fun parseOpfManifest(xml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                var id: String? = null
                var href: String? = null
                for (i in 0 until parser.attributeCount) {
                    when (parser.getAttributeName(i)) {
                        "id" -> id = parser.getAttributeValue(i)
                        "href" -> href = parser.getAttributeValue(i)
                    }
                }
                if (id != null && href != null) {
                    map[id] = href
                }
            }
            event = parser.next()
        }
        return map
    }

    /** Analizar desde OPF spine:<itemref idref=.../> Lista secuencial. */
    private fun parseOpfSpine(xml: String): List<String> {
        val list = mutableListOf<String>()
        val parser = newSecurePullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "itemref") {
                for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i) == "idref") {
                        list.add(parser.getAttributeValue(i))
                        break
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    /** Quitar etiquetas HTML/XHTML,mantener solo texto;Procesamiento de avance de línea <p>/<br>/<div>. */
    private fun stripHtmlTags(html: String): String {
        val sb = StringBuilder()
        val parser = newSecurePullParser()
        // Modo relajado:HTML Puede ser irregular
        try {
            parser.setInput(html.reader())
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> sb.append('\n')
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text
                        if (text.isNotBlank()) sb.append(text)
                    }
                }
                event = parser.next()
            }
        } catch (e: Throwable) {
            // v1.73: XML El análisis falló:Decodificación alternativa HtmlCompat(Soporte completo de entidades HTML).
            // L-DP9: La implementación anterior solo decodifica cuatro entidades nbsp/amp/lt/gt,chino/Entidad de símbolo especial(&copy; &hellip; espera)perdido.
            Logger.w("DocumentParser", "XML El análisis falló,Etiquetas de eliminación de HtmlCompat de reserva", e)
            return runCatching {
                HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            }.getOrElse {
                // HtmlCompat también falló:Retorno final a la pura regularidad
                html.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        }
        return sb.toString()
    }

    /** L-DP6: 1MB Truncamiento de texto(estimación aproximada chars*2 ≈ bytes). */
    private fun truncate(text: String): String {
        return if (text.length * 2 > MAX_TEXT_BYTES) {
            text.take(MAX_TEXT_BYTES / 2) + "\n\n[… El contenido excede 1MB,truncado …]"
        } else {
            text
        }
    }

    /**
     * L-DP7: Crear un seguro XmlPullParser,Deshabilite la protección de entidades externas contra XXE.
     *
     * Android Por defecto, KXmlParser no descarga entidades externas(seguro),Pero setFeature explícito es más seguro;
     * kxml2 No compatible external-entities feature,runCatching Ignorar casos no admitidos,
     * Confíe en el comportamiento de seguridad predeterminado.
     */
    private fun newSecurePullParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newPullParser()
    }

    /**
     * M-DP5: Normalizar la ruta de entrada ZIP,Manejar href en "../".
     * usar java.nio.file.Paths.normalize() Convertir de nuevo a barra diagonal después del análisis(ZIP entry Para uso unificado '/').
     */
    private fun resolveZipPath(baseDir: String, href: String): String {
        if (baseDir.isEmpty()) return href
        val resolved = Paths.get(baseDir, href).normalize()
        return resolved.toString().replace('\\', '/')
    }

    /** Enumere todos los nombres de las entradas en ZIP. */
    private fun listZipEntries(context: Context, uri: Uri): List<String> {
        val names = mutableListOf<String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    names.add(entry.name)
                    zis.closeEntry()
                }
            }
        }
        return names
    }

    /**
     * Leer el contenido de la entrada especificada en el ZIP(cuerda).
     *
     * H-DP1: Bomba anti-ZIP——Lea manualmente en ByteArrayOutputStream y verifique los bytes acumulados,
     * súper [ZIP_MAX_BYTES] (4MB) tirar [IOException],evitar zis.readBytes() La descompresión ilimitada provoca OOM.
     */
    private fun readZipEntry(context: Context, uri: Uri, entryName: String): String? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.name == entryName) {
                        val baos = ByteArrayOutputStream()
                        val buf = ByteArray(8 * 1024)
                        var total = 0
                        var n = zis.read(buf)
                        while (n > 0) {
                            total += n
                            if (total > ZIP_MAX_BYTES) {
                                throw IOException("ZIP entry '$entryName' La descompresión supera el límite(${ZIP_MAX_BYTES} bytes),Sospecha de bomba ZIP")
                            }
                            baos.write(buf, 0, n)
                            n = zis.read(buf)
                        }
                        return String(baos.toByteArray(), Charsets.UTF_8)
                    }
                    zis.closeEntry()
                }
            }
        }
        return null
    }

    /** Adivina el tipo MIME a partir de la ruta URI(Ten todo en cuenta,Devolución parcial del SAF null). */
    private fun guessMimeFromName(name: String): String? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") ||
                lower.endsWith(".csv") || lower.endsWith(".json") ||
                lower.endsWith(".xml") || lower.endsWith(".html") ||
                lower.endsWith(".htm") || lower.endsWith(".log") -> "text/plain"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".docx") ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".pptx") ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            lower.endsWith(".xlsx") ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".epub") -> "application/epub+zip"
            else -> null
        }
    }
}
