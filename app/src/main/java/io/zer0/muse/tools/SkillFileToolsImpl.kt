package io.zer0.muse.tools

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import io.zer0.muse.R
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * P1-3b Deslocalización:Skill Archivo/Implementación de herramienta de catálogo público (migrada desde SkillExecutor.kt).
 * Llamado por el delegado de SkillExecutor.
 */
class SkillFileToolsImpl(private val context: Context, private val client: OkHttpClient) {

    /** Leer archivos en el entorno limitado de la aplicación(Calificar subrutas filesDir / cacheDir). */
    fun execReadFile(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (!file.exists()) return context.getString(R.string.skill_file_not_found, path)
        // v1.52: Detección de archivos binarios — Leer los primeros 1024 bytes,Si contiene bytes NUL o una alta proporción de caracteres de reemplazo UTF-8, se considerará binario.
        // v1.52 revisión: Los archivos vacíos devuelven directamente cadenas vacías;Utilice el número real de bytes leídos para determinar,Evite errores de cálculo del NUL final causado por una lectura incompleta
        // Detectar encabezados de archivos de solo lectura;Según sea necesario, se puede realizar una lectura posterior del texto completo o una paginación de caracteres delimitados.
        if (file.length() == 0L) return ""
        val readLen = minOf(1024L, file.length()).toInt()
        val headBytes = ByteArray(readLen)
        val actualRead = file.inputStream().use { it.read(headBytes) }
        if (actualRead > 0) {
            val probe = if (actualRead < readLen) headBytes.copyOf(actualRead) else headBytes
            if (probe.any { it == 0.toByte() }) {
                return context.getString(R.string.skill_binary_file_nul, path)
            }
            val decoded = String(probe, Charsets.UTF_8)
            val replacementCount = decoded.count { it == '\uFFFD' }
            if (replacementCount.toDouble() / probe.size > 0.05) {
                return context.getString(R.string.skill_binary_file_utf8, path)
            }
        }
        // offset/length: Lectura segmentada(Número de línea inicial + número de líneas leídas,Predeterminado 0=Todos)
        val offset = args["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val length = args["length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val offsetChars = args["offset_chars"]?.toIntOrNull()?.coerceAtLeast(0)
        val requestedLengthChars = args["length_chars"]?.toIntOrNull()?.coerceAtLeast(0)
        // encoding: Predeterminado utf-8(Actualmente solo admite utf-8/utf-16,Otras alternativas utf-8)
        val encoding = args["encoding"]?.takeIf { it.isNotBlank() } ?: "utf-8"
        val charset = when (encoding.lowercase()) {
            "utf-16", "utf-16le" -> Charsets.UTF_16
            "utf-16be" -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        if (offsetChars != null || requestedLengthChars != null || file.length() > MAX_READ_FILE_CHARS) {
            return readFileCharacterPage(
                file = file,
                path = path,
                charset = charset,
                requestedOffset = offsetChars ?: 0,
                requestedLength = requestedLengthChars ?: TOOL_OUTPUT_READ_PAGE_CHARS,
            )
        }
        if (offset > 0 || length > 0) {
            val lines = file.readLines(charset)
            val from = offset.coerceAtMost(lines.size)
            val to = if (length > 0) (from + length).coerceAtMost(lines.size) else lines.size
            return lines.subList(from, to).joinToString("\n")
        }
        return file.readText(charset)
    }

    private fun readFileCharacterPage(
        file: File,
        path: String,
        charset: java.nio.charset.Charset,
        requestedOffset: Int,
        requestedLength: Int,
    ): String = ToolTextFilePager.readPage(
        file = file,
        path = path,
        charset = charset,
        requestedOffset = requestedOffset,
        requestedLength = requestedLength,
    )

    /** Escriba archivos en el entorno limitado de la aplicación. */
    fun execWriteFile(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val content = args["content"] ?: return context.getString(R.string.skill_missing_param_content)
        val append = args["append"]?.toBoolean() ?: false
        // create_dirs: Predeterminado true,Crear directorios principales automáticamente
        val createDirs = args["create_dirs"]?.toBoolean() ?: true
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (createDirs) {
            file.parentFile?.mkdirs()
        } else {
            // Cuando el directorio no se crea automáticamente,Si el directorio principal no existe, se informará un error.
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                return context.getString(R.string.skill_parent_dir_not_exist, parent.absolutePath)
            }
        }
        val isOverwrite = file.exists() && !append
        if (append) file.appendText(content) else file.writeText(content)
        // v1.28: Devolver ruta absoluta,Habilite extractFilePaths de MessageBubble para hacer coincidir y representar chips adjuntos
        val timestamp = System.currentTimeMillis()
        // v1.47: Volver a la vista previa del contenido(primeros 200 caracteres),Permitir que la persona que llama verifique los resultados de escritura,En lugar de simplemente ver la herramienta de fe ciega para contar bytes
        val previewLimit = 200
        val preview = if (content.length <= previewLimit) {
            content
        } else {
            content.take(previewLimit) + context.getString(R.string.skill_write_preview_truncated, content.length)
        }
        return context.getString(
            R.string.skill_write_result,
            file.length(),
            file.absolutePath,
            if (isOverwrite) context.getString(R.string.skill_yes) else context.getString(R.string.skill_no),
            timestamp,
            preview,
        )
    }

    /**
     * SSRF protección:Verifique si la URL apunta a la dirección de la red pública.
     *
     * Rechazar las siguientes direcciones:
     *  - nombre de host localhost
     *  - Dirección de bucle invertido 127.0.0.0/8,::1
     *  - Red privada 10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,IPv6 fc00::/7
     *  - enlace local 169.254.0.0/16
     *  - Dirección no especificada, dirección de multidifusión
     *
     * Usar al mismo tiempo [java.net.InetAddress.getAllByName] Verificación secundaria después de la resolución DNS IP,
     * Prevenir ataques de revinculación de DNS(La IP real obtenida mediante la resolución de nombres de dominio todavía apunta a la intranet).
     *
     * @return true Indica seguridad(Puedes seguir solicitando);false Indica que apunta a la intranet.,La persona que llama debe rechazar
     */
    fun validatePublicUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // Verificación secundaria después de la resolución DNS IP(protegerse contra DNS rebinding):Siempre que algún resultado del análisis apunte a la intranet, será rechazado.
        val addresses = try {
            java.net.InetAddress.getAllByName(host)
        } catch (e: Exception) {
            return false
        }
        return addresses.none { isPrivateAddress(it) }
    }

    /**
     * B-32: Análisis único + verificación de red pública.,Devolver la lista de IP congeladas(Para la reutilización de OkHttp dns, de modo que ya no sea necesario analizar la conexión dos veces.).
     *
     * con [validatePublicUrl] la diferencia:Sólo se analiza una vez y el resultado se devuelve explícitamente.,La persona que llama toma el resultado directamente como
     * objetivo de conexión(hostOverride),El nombre de dominio ya no se volverá a resolver durante la fase de conexión.,Cerrando así la ventana TOCTOU de "analizar y verificar primero y luego conectar";
     * Cualquier resultado del análisis apunta a la intranet./bucle invertido/enlace local/IPv6 ULA → tirar IOException(Semántica booleana comida).
     *
     * @param url Requerido http(s) scheme Y el anfitrión es legal.
     * @return Lista de resultados de análisis para todas las redes públicas.(La conexión y la verificación comparten lo mismo)
     */
    @Throws(java.io.IOException::class)
    private fun resolvePublicPinned(url: String): List<java.net.InetAddress> {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            throw java.io.IOException("URL ilegal: $url")
        }
        val host = uri.host?.lowercase() ?: throw java.io.IOException("Al destino de descarga le falta el nombre de host: $url")
        val addresses = try {
            java.net.InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw java.io.IOException("DNS El análisis falló: $host")
        }
        if (addresses.any { isPrivateAddress(it) }) {
            throw java.io.IOException("El destino de descarga apunta a la dirección de intranet.,Rechazado: $url")
        }
        return addresses.toList()
    }

    /**
     * Determinar si la IP está en la red interna/bucle invertido/enlace local/multidifusión/IPv6 ULA(fc00::/7).IPv4 El segmento de red privada está formado por
     * InetAddress Anulación de método incorporado,IPv6 Hay que juzgarlo por separado. ULA(InetAddress.isSiteLocalAddress Devoluciones para IPv6 false).
     */
    private fun isPrivateAddress(addr: java.net.InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        if (addr is java.net.Inet6Address) {
            val bytes = addr.address
            // fc00::/7 Los 7 primeros son 1111110,Es decir, el primer rango de bytes. 0xfc..0xfd
            if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }

    /** HTTP GET pedido. en el fracaso(404/tiempo de espera/La conexión falló)Bajar a fragmentos de búsqueda;401/403 Espere errores comerciales y no baje de categoría. */
    fun resolveSandboxFile(path: String): File? {
        val filesDir = context.filesDir.canonicalPath
        val cacheDir = context.cacheDir.canonicalPath
        val target = File(context.filesDir, path).canonicalFile
        val targetPath = target.canonicalPath
        val inside = { root: String ->
            targetPath == root || targetPath.startsWith(root + File.separator)
        }
        return if (inside(filesDir) || inside(cacheDir)) target else null
    }

    // H-SE1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
    fun execListDir(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val dir = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        if (!dir.exists()) return context.getString(R.string.skill_dir_not_exist, path)
        if (!dir.isDirectory) return context.getString(R.string.skill_not_dir, path)
        val files = dir.listFiles()
        if (files.isNullOrEmpty()) return "empty"
        return files.sortedBy { it.name }.joinToString("\n") { f ->
            val prefix = if (f.isDirectory) "[D]" else "[F]"
            val size = if (f.isFile) " (${f.length()}B)" else ""
            "$prefix ${f.name}$size"
        }
    }

    /** delete_file — Eliminar archivos o directorios vacíos(dentro de la caja de arena). Admite rutas únicas o por lotes. */
    fun execDeleteFile(args: Map<String, String>): String {
        // v1.47: Admite la eliminación por lotes de rutas(Separados por coma o nueva línea),Compatible con el antiguo parámetro de ruta única
        val paths = args["paths"]?.takeIf { it.isNotBlank() }
            ?.split(",", "\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOfNotNull(args["path"]?.takeIf { it.isNotBlank() })
        if (paths.isEmpty()) return context.getString(R.string.skill_missing_param_path_or_paths)
        val results = paths.map { p ->
            val file = resolveSandboxFile(p) ?: return@map context.getString(R.string.skill_delete_path_violation, p)
            if (!file.exists()) return@map context.getString(R.string.skill_delete_not_exist, p)
            val ok = file.delete()
            if (ok) context.getString(R.string.skill_delete_success, p) else context.getString(R.string.skill_delete_failed, p)
        }
        val okCount = results.count { it.startsWith("[éxito]") }
        return context.getString(R.string.skill_batch_delete_result, okCount, paths.size) + results.joinToString("\n")
    }

    /** file_exists — Determinar si el archivo existe(dentro de la caja de arena). */
    fun execFileExists(args: Map<String, String>): String {
        val path = args["path"] ?: return context.getString(R.string.skill_missing_param_path)
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation, path)
        return if (file.exists()) "exists" else "not_exists"
    }

    // ── Directorio público y transferencia de archivos ──────────────────────────────────────────────

    /** file_download — Descargue el archivo desde la URL a la aplicación Sandbox(Calificar en filesDir). */
    suspend fun execFileDownload(args: Map<String, String>): String {
        val url = args["url"] ?: return "error: missing url"
        // H-SE4: verificar url scheme,Evitar la lectura de archivos SSRF/locales(file:// Acuerdo)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme_err)
        }
        // A-08: Verifique el host antes de aterrizar + protección de redirección salto a salto(URL de red antipública 30x Saltar a la intranet).
        // Tanto la URL inicial como el destino de redireccionamiento se verifican paso a paso mediante downloadWithHopGuard y el destino de la conexión se congela con un único resultado de análisis.,
        // No es necesario analizarlo previamente por separado aquí.(De lo contrario, se combinará con downloadWithHopGuard para formar la ventana de análisis secundaria de TOCTOU.).
        val path = args["path"] ?: return "error: missing path"
        // H-SE3: Utilice resolveSandboxFile para verificar la ruta en su lugar.,Evitar el cruce de caminos(Como por ejemplo path="../../databases/main.db")
        val file = resolveSandboxFile(path) ?: return context.getString(R.string.skill_path_violation_err)
        // M-SE8: timeout Limitado a 1..300 segundos,Evitar el bloqueo ilimitado
        val timeoutSec = args["timeout"]?.toIntOrNull()?.coerceIn(1, 300) ?: 60

        file.parentFile?.mkdirs()

        return withTimeoutOrNull(timeoutSec * 1000L) {
            try {
                downloadWithHopGuard(url).use { response ->
                    if (!response.isSuccessful) return@withTimeoutOrNull "error: HTTP ${response.code}"

                    response.body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    context.getString(R.string.skill_download_success, file.absolutePath, file.length())
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                context.getString(R.string.skill_download_failed, e.message ?: "")
            }
        } ?: context.getString(R.string.skill_download_timeout, timeoutSec)
    }

    /**
     * A-08: Ejecución de solicitud de descarga con protección salto a salto SSRF. Desactivar redireccionamientos de seguimiento automático(client Predeterminado followRedirects=true),
     * Seguir manualmente 30x,Repite cada salto [validatePublicUrl] — Evite que la URL pública inicial sea redirigida 30 veces a la dirección de intranet
     * (Mismo esquema que http_get/web_fetch). El límite de recuento de saltos evita bucles de redireccionamiento infinitos.
     *
     * @param url inicial URL(Esquema aprobado + verificación validarPublicUrl)
     * @return respuesta de golpe final;La persona que llama es responsable. use/close
     */
    private fun downloadWithHopGuard(url: String): okhttp3.Response {
        var redirects = 0
        var currentUrl = url
        // B-32: host → Lista de IP públicas congeladas;El mismo host solo analiza la verificación una vez y la reutiliza como destino de la conexión.(hostOverride),
        // El nombre de dominio ya no se volverá a resolver durante la fase de conexión.,Eliminar las ventanas TOCTOU que "analizan y verifican primero y luego se conectan"(DNS rebinding El ataque falló).
        val pinnedResolutions = HashMap<String, List<java.net.InetAddress>>()
        while (true) {
            val host = java.net.URI(currentUrl).host
                ?: throw java.io.IOException("Al destino de descarga le falta el nombre de host: $currentUrl")
            val pinned = pinnedResolutions.getOrPut(host) { resolvePublicPinned(currentUrl) }
            // Nuevo para cada salto followRedirects=false Y el DNS se fija al resultado de una única resolución. client,Reutilizar el grupo de conexiones compartidas y la configuración del tiempo de espera
            val hopClient = client.newBuilder()
                .followRedirects(false)
                .dns(Dns { _ -> pinned })
                .build()
            val resp = hopClient.newCall(Request.Builder().url(currentUrl).get().build()).execute()
            // location para locales val,Kotlin puede convertir inteligentemente el siguiente if a no vacío después de salir temprano.
            val location = resp.header("Location")
            if (resp.code !in 300..399 || location == null) {
                return resp
            }
            resp.close()
            redirects++
            if (redirects > MAX_DOWNLOAD_REDIRECTS) {
                throw java.io.IOException("El número de redirecciones supera el límite superior.($MAX_DOWNLOAD_REDIRECTS),terminado")
            }
            // Ubicación relativa analizada por la URL actual(java.net.URL Compatible con la semántica de resolución de URL de OkHttp)
            currentUrl = try {
                java.net.URL(currentUrl).toURI().resolve(location).toString()
            } catch (e: Exception) {
                throw java.io.IOException("La ubicación de redireccionamiento no se puede resolver: $location")
            }
        }
    }

    companion object {
        private const val MAX_READ_FILE_CHARS = 1_000_000L

        /** Número máximo de saltos para seguir las redirecciones de descarga manualmente(Bucle de redireccionamiento anti-infinito). */
        private const val MAX_DOWNLOAD_REDIRECTS = 10
    }

    /** read_public_file — Leer recursos compartidos de usuarios a través de SAF/Abra el URI del archivo pasado. */
    suspend fun execReadPublicFile(args: Map<String, String>): String {
        val uriStr = args["uri"] ?: return "error: missing uri"
        val encoding = args["encoding"]?.takeIf { it.isNotBlank() } ?: "utf-8"
        // L-SE14: Verificación individual charset,Dar sugerencias claras en lugar de generales."Error de lectura"
        val cs = try {
            java.nio.charset.Charset.forName(encoding)
        } catch (e: java.nio.charset.UnsupportedCharsetException) {
            return context.getString(R.string.skill_unsupported_encoding, encoding)
        } catch (e: java.nio.charset.IllegalCharsetNameException) {
            return context.getString(R.string.skill_illegal_encoding, encoding)
        }

        return try {
            val uri = Uri.parse(uriStr)
            // Reparación de auditoría (1.3): Sólo se permite el esquema content://. La implementación original acepta file://,
            // LLM Legible cualquier archivo en el directorio privado de la aplicación.(base de datos,shared_prefs espera).
            // read_public_file La semántica son archivos compartidos SAF.,content:// Es un canal estándar.
            val scheme = uri.scheme?.lowercase()
            if (scheme != "content") {
                return "error: unsupported uri scheme '$scheme', only content:// is allowed"
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: return "error: cannot open uri"

            val capture = ToolOutputCapture(File(context.filesDir, TOOL_OUTPUTS_DIR), "read_public_file")
            input.use { stream ->
                stream.bufferedReader(cs).use { reader ->
                    val buffer = CharArray(8_192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        capture.append(String(buffer, 0, count))
                    }
                }
            }
            capture.finish(header = "", emptyMessage = "")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_read_failed, e.message ?: "")
        }
    }

    /** save_to_downloads — Guarde texto o archivos locales en el directorio de descargas(Android 10+ usar MediaStore). */
    suspend fun execSaveToDownloads(args: Map<String, String>): String {
        val filenameArg = args["filename"] ?: return "error: missing filename"
        // B-20: nombre de archivo sanitize — El nombre del archivo original puede contener "../" Escapar de los fragmentos de ruta del directorio de descarga.
        // Reutilice la misma regla de FileTools.create_download:Mantenga solo guiones alfanuméricos de punto y guión bajo,
        // Reemplace el resto con "_",Eliminar los separadores por completo/Escape de ruta causado por segmentos relativos. Los resultados vacíos vuelven al nombre predeterminado con marca de tiempo.
        val filename = filenameArg.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}.txt"
        val mimeType = args["mime_type"] ?: "text/plain"
        // v1.47: Admite el parámetro file_path — Lea archivos directamente desde la zona de pruebas y transfiéralos a Download,No es necesario leer_archivo antes de escribir texto
        val filePath = args["file_path"]?.takeIf { it.isNotBlank() }
        val content = args["content"]

        // content Alternativa a file_path
        if (content == null && filePath == null) {
            return context.getString(R.string.skill_missing_content_or_filepath)
        }

        // si pasa file_path,Leer bytes de archivos locales(Soporte binario,Sin texto obligatorio)
        val srcFile = filePath?.let { resolveSandboxFile(it) }
        if (filePath != null) {
            if (srcFile == null) return context.getString(R.string.skill_path_violation_err)
            if (!srcFile.exists()) return context.getString(R.string.skill_source_file_not_exist, filePath)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ usar MediaStore
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return "error: cannot create file in Downloads"

                resolver.openOutputStream(uri)?.use { output ->
                    if (srcFile != null) {
                        srcFile.inputStream().use { it.copyTo(output) }
                    } else {
                        // v1.74: garantía lógica filePath==null cuando el contenido no está vacío(La cima ha sido verificada. Elige uno de los dos.),usar ?: "" degradación defensiva
                        output.write((content ?: "").toByteArray())
                    }
                } ?: return "error: cannot open output stream"

                if (srcFile != null) {
                    context.getString(R.string.skill_saved_to_download_with_size, filename, srcFile.length())
                } else {
                    context.getString(R.string.skill_saved_to_download, filename)
                }
            } else {
                // Android 9 Lo siguiente escribe directamente el directorio de descarga público.
                // M-SE9: API < 29 Es necesario verificar el permiso WRITE_EXTERNAL_STORAGE
                val hasWritePerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasWritePerm) {
                    return context.getString(R.string.skill_write_perm_needed)
                }
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadDir, filename)
                if (srcFile != null) {
                    srcFile.copyTo(file, overwrite = true)
                } else {
                    // v1.74: garantía lógica filePath==null cuando el contenido no está vacío(La cima ha sido verificada. Elige uno de los dos.),usar ?: "" degradación defensiva
                    file.writeText(content ?: "")
                }
                context.getString(R.string.skill_saved_to_path, file.absolutePath)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_save_failed, e.message ?: "")
        }
    }

    /** list_public_files — Listar archivos en un directorio público específico(MediaStore Consulta). */
    suspend fun execListPublicFiles(args: Map<String, String>): String {
        val directory = args["directory"] ?: "Downloads"
        val limit = args["limit"]?.toIntOrNull() ?: 50

        return try {
            // v1.29: directory="all" mientras recorre todos los directorios públicos y fusiona los resultados
            if (directory.equals("all", ignoreCase = true)) {
                val allDirs = listOf("Downloads", "Documents", "Pictures", "Music", "Movies")
                val merged = StringBuilder()
                allDirs.forEachIndexed { idx, dir ->
                    merged.append("== $dir ==\n")
                    merged.append(queryPublicDir(dir, limit))
                    if (idx < allDirs.size - 1) merged.append("\n\n")
                }
                return merged.toString().trimEnd()
            }
            queryPublicDir(directory, limit)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            context.getString(R.string.skill_query_failed, e.message ?: "")
        }
    }

    /** Consultar la lista de archivos de un único directorio público.(MediaStore).v1.47: La salida contiene content:// URI,Se puede enviar directamente a read_public_file. */
    fun queryPublicDir(directory: String, limit: Int): String {
        val collection = when (directory.lowercase()) {
            "downloads" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri(
                    "external",
                )
            }
            "documents" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        // v1.47: Verifique varias columnas _ID,solía deletrear content:// URI,dejar list → read simetría de habilidad
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        // M-SE7: Enlace parametrizado con SelectionArgs directory,Evite la inyección SQL;
        // Escapar del carácter comodín LIKE(% _ \)y agregar ESCAPE '\' cláusula
        val escapedDir = directory.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$escapedDir%")
        } else {
            null
        }
        // v1.109 Reparación: LIMIT Agregar límite superior 200,Evite que LLM transmita valores extremadamente grandes y provoque el agotamiento de los recursos
        val safeLimit = limit.coerceIn(1, 200)
        // MediaStore providers do not consistently accept SQL LIMIT tokens in sortOrder
        // (some return "Invalid token LIMIT"). Use structured query arguments instead.
        val queryArgs =
            Bundle().apply {
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                selectionArgs?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, safeLimit)
            }
        val cursor = context.contentResolver.query(collection, projection, queryArgs, null)
            ?: return context.getString(R.string.skill_query_failed_short)
        return cursor.use {
            val results = mutableListOf<String>()
            while (it.moveToNext() && results.size < safeLimit) {
                val id = it.getLong(0)
                val name = it.getString(1) ?: "?"
                val size = it.getLong(2)
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                // Formato: nombre de archivo (tamañoB) | uri=content://...
                results.add("$name (${size}B) | uri=$uri")
            }
            if (results.isEmpty()) context.getString(R.string.skill_dir_empty) else results.joinToString("\n")
        }
    }
}
