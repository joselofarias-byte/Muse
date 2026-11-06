package io.zer0.muse.tools

import android.content.Context
import android.webkit.URLUtil
import io.zer0.common.Logger
import io.zer0.muse.ui.SsrfGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * v1.0.47 P2-2/P2-3/P2-4: Conjunto de herramientas de archivos y enlaces(AI invocable).
 *
 * Tres herramientas complementan el procesamiento de IA"Archivos proporcionados por el usuario/enlace"habilidad:
 *  1. read_file      — Leer archivos de texto en directorios accesibles a la aplicación.(Descargar/Documentación/espacio de trabajo/Salida de herramienta)
 *  2. create_download — Escribir contenido de texto en el directorio de descargas.,Generar archivos de descarga visibles para el usuario
 *  3. parse_link     — Rastrear página URL,Extraer título+Texto(Desembalaje de anuncios/Navegación),Regresar Markdown
 *
 * Diseño seguro:
 *  - read_file Lista blanca de rutas:Solo se permite aplicar filesDir / cacheDir / descarga externa / espacio de trabajo / tool_outputs
 *  - read_file prohibido ".." ultra vires,Los archivos grandes se leen mediante paginación del cursor de caracteres
 *  - create_download Solo escribe en el directorio de descarga público.(MediaStore.Downloads),nombre de archivo sanitize
 *  - parse_link solo HTTP/HTTPS,tiempo de espera 15s;Las respuestas grandes se guardan por completo y se pueden leer en segmentos.
 */
object FileTools {

    const val NAME_READ_FILE = "read_file"
    const val NAME_CREATE_DOWNLOAD = "create_download"
    const val NAME_PARSE_LINK = "parse_link"

    /** Cuando se excede este volumen, read_file devuelve la página del primer carácter de forma predeterminada.,Evite leer archivos grandes a la vez. */
    private const val READ_FILE_AUTO_PAGE_BYTES = 2 * 1024 * 1024

    /** create_download El límite de tamaño del contenido es de 10 MB. */
    private const val CREATE_DOWNLOAD_MAX_BYTES = 10 * 1024 * 1024

    /** parse_link Obtener tiempo de espera ms. */
    private const val PARSE_LINK_TIMEOUT_MS = 15_000

    /** HTML Límite superior de vista previa del análisis de sujetos;El cuerpo completo de la respuesta aún se guarda en un archivo paginable. */
    private const val PARSE_LINK_PARSE_PREVIEW_CHARS = 1_000_000

    /** B-30: parse_link Límite de salto manual(Cada salto pasa la verificación del host SSRF). */
    private const val MAX_REDIRECTS = 5

    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = NAME_READ_FILE,
            // v1.0.75 fix (Revisión de herramientas 01): description Alinearse con la implementación — La implementación rechaza caminos absolutos,
            // Reclamaciones de descripción original"Admite rutas absolutas"Como resultado, la primera llamada al modelo debe fallar.(Comentarios de los usuarios"prueba y error"causa raíz).
            description = "Leer archivos de texto en directorios accesibles a la aplicación.(UTF-8). Segmentación automática de archivos grandes.,Puedes usar offset_chars/length_chars para continuar leyendo." +
                "Solo se admiten rutas relativas,Soporte tres: Ruta relativa del espacio de trabajo(Como por ejemplo 'notes.txt')," +
                "Referencia de salida de herramienta(Como por ejemplo 'tool_outputs/xxx.json'), ruta relativa del directorio privado de la aplicación." +
                "Los caminos absolutos serán rechazados.,no enviar '/storage/emulated/0/...' forma.",
            parameters = mapOf(
                "path" to "Requerido,Ruta relativa del archivo. apoyo:Ruta relativa del espacio de trabajo(Como por ejemplo 'notes.txt')," +
                    "Referencia de salida de herramienta(Como por ejemplo 'tool_outputs/xxx.json'), ruta relativa del directorio privado de la aplicación." +
                    "Se rechazan los caminos absolutos.",
                "offset_chars" to "Opcional,cursor de inicio de carácter;Utilice los últimos offset_chars devueltos cuando continúe leyendo archivos grandes.",
                "length_chars" to "Opcional,Número de caracteres en una sola página.,máximo ${TOOL_OUTPUT_READ_PAGE_CHARS}.",
            ),
            required = setOf("path"),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
        ToolRegistry.ToolDef(
            name = NAME_CREATE_DOWNLOAD,
            description = "Escriba contenido de texto en el directorio de descargas visible para el usuario.,Generar archivo de descarga." +
                "Aplicable a:AI Texto largo generado/código/JSON/CSV Esperando a que el usuario guarde el contenido visto." +
                "Equivalente a save_to_downloads(Elige uno);Esta herramienta admite subdirectorios." +
                "Nombre del archivo automáticamente sanitize(Eliminar caracteres especiales),A los archivos con el mismo nombre se les añaden automáticamente números de serie.",
            parameters = mapOf(
                "filename" to "Requerido,nombre de archivo(Como por ejemplo 'report.md','data.json')",
                "content" to "Requerido,El contenido del texto a escribir.(UTF-8)",
                "subdir" to "Opcional,Download subdirectorios bajo(Como por ejemplo 'AIgenerar'),Directorio raíz predeterminado",
            ),
            required = setOf("filename", "content"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = NAME_PARSE_LINK,
            // v1.0.75 fix (Revisión de herramientas 01): Declaración mutuamente excluyente con web_fetch/http_get
            description = "Rastrear página URL,Extraer título y texto del cuerpo.,Formato de devolución de rebajas(con titulo)." +
                "Anuncios de bombardeo automático/Navegación/barra lateral,Bueno para leer noticias./Blog/Documentación." +
                "Tiempo de espera 15 segundos,La página grande guardará el código fuente completo y proporcionará acceso de lectura segmentado." +
                "Quiere utilizar texto sin formato web_fetch;Si desea la respuesta original utilice http_get.",
            parameters = mapOf(
                "url" to "Requerido,HTTP/HTTPS URL",
            ),
            required = setOf("url"),
            category = "built-in",
            riskLevel = ToolRiskLevel.SAFE,
        ),
    )

    /**
     * Ejecute la llamada a la herramienta.
     *
     * @param context contexto de aplicación(leer filesDir/Download)
     * @param workspaceRoot directorio raíz del espacio de trabajo(Se utiliza para resolver rutas relativas del espacio de trabajo.)
     */
    suspend fun execute(name: String, args: Map<String, String>, context: Context, workspaceRoot: File): String =
        withContext(Dispatchers.IO) {
            when (name) {
                NAME_READ_FILE -> execReadFile(args, context, workspaceRoot)
                NAME_CREATE_DOWNLOAD -> execCreateDownload(args, context)
                NAME_PARSE_LINK -> execParseLink(args, context)
                else -> "[error] herramienta desconocida: $name"
            }
        }

    // ============================ read_file ============================

    private fun execReadFile(args: Map<String, String>, context: Context, workspaceRoot: File): String {
        val path = args["path"]?.takeIf { it.isNotBlank() }
            ?: return "[error] Falta el parámetro requerido path"

        // seguro:Prohibido.. Ultra vires
        if (path.contains("..")) return "[error] El camino no debe contener '..'"

        // Reparación de auditoría (1.2): rechazar el camino absoluto — File(workspaceRoot, absolutePath) regresará directamente
        // el camino absoluto en sí(Java ignorar parent),Omita la lista blanca del directorio de candidatos a continuación,Archivos arbitrarios privados de aplicaciones legibles.
        val rawPath = path.trim()
        // El recibo de salida de la herramienta está en el modo de montaje. `tool_outputs:/x.txt`,Aquí la misma boca se procesa en forma relativa.
        val trimmedPath = if (rawPath.startsWith("$TOOL_OUTPUTS_DIR:/")) {
            TOOL_OUTPUTS_DIR + "/" + rawPath.removePrefix("$TOOL_OUTPUTS_DIR:/")
        } else {
            rawPath
        }
        val isAbsolute = trimmedPath.startsWith("/") || trimmedPath.startsWith("\\") ||
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(trimmedPath)
        if (isAbsolute) {
            return "[error] No se admiten rutas absolutas,Por favor use el espacio de trabajo/Ruta relativa al directorio privado: $path"
        }

        // Analizar rutas candidatas:Ruta relativa del espacio de trabajo → filesDir → cacheDir → externo Download
        //(El directorio de salida de la herramienta se encuentra en filesDir.`tool_outputs/x.txt` golpeado por el próximo candidato;
        //  No más esfuerzos adicionales `tool_outputs/$path`, esa capa de empalme se convertirá en un prefijo doble cuando la ruta tenga el prefijo, y nunca llegará)
        val candidates = listOf(
            File(workspaceRoot, trimmedPath),
            File(context.filesDir, trimmedPath),
            File(context.cacheDir, trimmedPath),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), trimmedPath),
        )

        val target = candidates.firstOrNull { it.exists() && it.isFile }
            ?: return "[error] El archivo no existe o no es un archivo común y corriente: $path" +
                // Utilice filesDir para insertar el directorio:tool_outputs/ Simplemente cuélgalo debajo.
                siblingHint(File(context.filesDir, trimmedPath))

        if (!target.canRead()) return "[error] El archivo no es legible: ${target.absolutePath}"

        val size = target.length()
        val requestedOffset = args["offset_chars"]?.toIntOrNull()?.coerceAtLeast(0)
        val requestedLength = args["length_chars"]?.toIntOrNull()?.coerceAtLeast(0)
        if (requestedOffset != null || requestedLength != null || size > READ_FILE_AUTO_PAGE_BYTES) {
            return ToolTextFilePager.readPage(
                file = target,
                path = trimmedPath,
                charset = Charsets.UTF_8,
                requestedOffset = requestedOffset ?: 0,
                requestedLength = requestedLength ?: TOOL_OUTPUT_READ_PAGE_CHARS,
            )
        }

        return runCatching { target.readText() }
            .onFailure { Logger.w("FileTools", "read_file Error de lectura: ${it.message}", it) }
            .getOrElse { "[error] Error de lectura: ${it.message}" }
    }

    // ============================ create_download ============================

    private fun execCreateDownload(args: Map<String, String>, context: Context): String {
        val filename = args["filename"]?.takeIf { it.isNotBlank() }
            ?: return "[error] Falta el parámetro requerido filename"
        val content = args["content"] ?: return "[error] Falta el parámetro requerido content"
        val subdir = args["subdir"]?.trim()?.trim('/') ?: ""

        if (content.toByteArray().size > CREATE_DOWNLOAD_MAX_BYTES) {
            return "[error] El contenido es demasiado grande.,límite superior ${CREATE_DOWNLOAD_MAX_BYTES / 1024 / 1024}MB"
        }

        // sanitize nombre de archivo:Mantenga solo guiones alfanuméricos de punto y guión bajo
        val safeFilename = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}.txt"

        // C-27: subdir filtrar ".."/"." segmento,Directorio de descargas de Prevent Escape
        val safeSubdir = subdir.split("/")
            .filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/")

        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // B-06: API 29+ scoped storage La escritura directa en directorios públicos fallará(EACCES),
                // Cambia tu ruta MediaStore.Downloads(Unirse automáticamente a la lista de descargas del sistema,Agregue automáticamente números de serie a nombres duplicados).
                val resolver = context.contentResolver
                val relativePath = if (safeSubdir.isNotBlank()) {
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/$safeSubdir"
                } else {
                    android.os.Environment.DIRECTORY_DOWNLOADS
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, safeFilename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "[error] No se puede crear el archivo en el directorio de descarga"
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    ?: return "[error] No se puede abrir el flujo de salida"
                Logger.i("FileTools", "create_download éxito(MediaStore): $safeFilename (${content.length} chars)")
                "[éxito] Archivo guardado en el directorio de descarga\nnombre de archivo: $safeFilename" +
                    (if (safeSubdir.isNotBlank()) "\nsubdirectorio: $safeSubdir" else "") +
                    "\ntamaño: ${content.length} personaje"
            } else {
                // Android 9 Abajo:Escribe directamente al directorio público.(necesidad WRITE_EXTERNAL_STORAGE)
                val hasWritePerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasWritePerm) return "[error] Falta permiso de almacenamiento de escritura(WRITE_EXTERNAL_STORAGE)"
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val targetDir = if (safeSubdir.isNotBlank()) File(downloadDir, safeSubdir) else downloadDir
                if (!targetDir.exists()) targetDir.mkdirs()
                // Los archivos con el mismo nombre agregan automáticamente números de serie
                var target = File(targetDir, safeFilename)
                var idx = 1
                val dotIdx = safeFilename.lastIndexOf('.')
                val baseName = if (dotIdx > 0) safeFilename.substring(0, dotIdx) else safeFilename
                val ext = if (dotIdx > 0) safeFilename.substring(dotIdx) else ""
                while (target.exists()) {
                    target = File(targetDir, "$baseName($idx)$ext")
                    idx++
                }
                target.writeText(content)
                Logger.i("FileTools", "create_download éxito: ${target.absolutePath} (${content.length} chars)")
                "[éxito] Archivo guardado en el directorio de descarga\ncamino: ${target.absolutePath}\ntamaño: ${content.length} personaje"
            }
        }.getOrElse {
            Logger.w("FileTools", "create_download falló: ${it.message}", it)
            "[error] Error de escritura: ${it.message}"
        }
    }

    // ============================ parse_link ============================

    private fun execParseLink(args: Map<String, String>, context: Context): String {
        val urlStr = args["url"]?.takeIf { it.isNotBlank() }
            ?: return "[error] Falta el parámetro requerido url"

        if (!URLUtil.isNetworkUrl(urlStr)) return "[error] Sólo admite HTTP/HTTPS URL"
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return "[error] URL Debe comenzar con http:// o https://"
        }

        // G3: SSRF La protección converge hacia SsrfGuard — Rechazar bucle invertido/red privada/enlace local/dirección reservada(Contiene
        // IPv4-mapped IPv6 con fc00::/7 ULA,Rechazo conservador cuando falla el análisis).
        // Evitar que parse_link rastree la máquina local/Servicios de intranet.
        if (SsrfGuard.isBlocked(urlStr)) {
            return "[error] La dirección de destino pertenece al loopback./Intranet/dirección reservada,Rastreo rechazado"
        }

        return runCatching {
            // B-30: Salto manual — instanceFollowRedirects=true Time 302 se puede llevar a la intranet
            // (Después de que pase la verificación inicial del host,El objetivo de redireccionamiento ya no está verificado). Desactivar el seguimiento automático,
            // Repite cada salto SsrfGuard.isBlocked,Hasta MAX_REDIRECTS veces.
            var currentUrl = urlStr
            var redirects = 0
            var conn: HttpURLConnection? = null
            while (true) {
                // G3: La URL completa para cada salto. SsrfGuard(Contiene verificación de esquema.)
                if (SsrfGuard.isBlocked(currentUrl)) {
                    return "[error] La dirección de destino pertenece al loopback./Intranet/dirección reservada,Rastreo rechazado"
                }
                conn?.disconnect()
                val u = URL(currentUrl)
                conn = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = PARSE_LINK_TIMEOUT_MS
                    readTimeout = PARSE_LINK_TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Muse/1.0")
                    setRequestProperty("Accept", "text/html,application/xhtml+xml")
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return "[error] HTTP $code: Falta el redireccionamiento Location"
                    redirects++
                    if (redirects > MAX_REDIRECTS) return "[error] El número de redirecciones supera el límite superior.($MAX_REDIRECTS),terminado"
                    // Ubicación relativa analizada por la URL actual
                    currentUrl = URL(u, location).toExternalForm()
                    continue
                }
                break
            }
            val finalConn = conn ?: return "[error] Error de recuperación: Conexión no establecida"
            try {
                val code = finalConn.responseCode
                if (code !in 200..299) return "[error] HTTP $code: ${finalConn.responseMessage}"

                val contentType = finalConn.contentType ?: ""
                if (!contentType.contains("html", ignoreCase = true)) {
                    val captured = finalConn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        captureTextStream(
                            reader = reader,
                            outputDirectory = File(context.filesDir, TOOL_OUTPUTS_DIR),
                            filePrefix = "parse_link",
                            previewLimitChars = 0,
                        )
                    }
                    return "[Contenido que no es HTML: $contentType]\n\n${captured.reference}"
                }

                // Retain the complete response while only building a bounded DOM parse preview.
                val captured = finalConn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    captureTextStream(
                        reader = reader,
                        outputDirectory = File(context.filesDir, TOOL_OUTPUTS_DIR),
                        filePrefix = "parse_link_html",
                        previewLimitChars = PARSE_LINK_PARSE_PREVIEW_CHARS,
                    )
                }
                finalConn.disconnect()

                val doc = Jsoup.parse(captured.preview, currentUrl)
                val title = doc.title().trim().ifBlank { "(Sin título)" }
                // Jsoup Eliminar automáticamente script/style,Luego seleccione el artículo o body
                val article = doc.selectFirst("article") ?: doc.body()
                val text = article.text().replace(Regex("\\s{2,}"), "\n").trim()

                Logger.i("FileTools", "parse_link éxito: $currentUrl (${text.length} chars)")
                buildString {
                    append("# ").append(title).append("\n\nFuente: ").append(currentUrl).append("\n\n")
                    append(text)
                    if (captured.savedFile != null) {
                        if (captured.totalChars > PARSE_LINK_PARSE_PREVIEW_CHARS) {
                            append("\n\n[Analizar solo antes de sobrescribir $PARSE_LINK_PARSE_PREVIEW_CHARS personajes;El código fuente completo de la página web se puede leer en secciones.]\n")
                        } else {
                            append("\n\n[El código fuente completo de la página web se puede leer en secciones.]\n")
                        }
                        append(captured.reference)
                    }
                }
            } finally {
                finalConn.disconnect()
            }
        }.getOrElse {
            Logger.w("FileTools", "parse_link falló: ${it.message}", it)
            "[error] Error de recuperación: ${it.message}"
        }
    }
}

/**
 * v1.0.47 P2: FileTools registrador — Registre read_file/create_download/parse_link en ToolRegistry.
 *
 * depender de ToolRegistry + WorkspaceManager(tomar rootDir)+ Context.
 */
class FileToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
    private val workspaceRoot: File,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        FileTools.toolDefs().forEach { def ->
            toolRegistry.register(def) { args ->
                FileTools.execute(def.name, args, context, workspaceRoot)
            }
        }
    }
}

/**
 * Cuando la lectura falla, aparecerá la frase "¿Qué hay realmente aquí?".
 *
 * Anteriormente, sólo se informaba "No existe o no es un archivo normal" y el modelo se dirigía a `workspace_list` directorio de lista ——
 * pero `tool_outputs/` Colgada en filesDir, no en la raíz del espacio de trabajo, esa acción simplemente está fuera de su alcance.
 * Enumerar directamente varios nombres de archivos reales en el mismo directorio es más útil que pedirle al modelo que intente una acción que no existe.
 */
private fun siblingHint(target: File): String {
    val dir = target.parentFile ?: return ""
    val names = runCatching {
        dir.listFiles()?.filter { it.isFile }?.take(5)?.map { it.name }
    }.getOrNull().orEmpty()
    if (names.isEmpty()) return ""
    return ". Existente en este directorio: " + names.joinToString(", ")
}
