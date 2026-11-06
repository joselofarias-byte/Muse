package io.zer0.muse.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puente de solicitud "Pide al usuario que seleccione un archivo".
 *
 * ## por qué es necesario
 *
 * Elemento 3 de la autoevaluación del Asistente: "Los archivos solo están en el directorio de archivos de la zona de pruebas y los archivos externos que el usuario realmente quiere que vea no se pueden leer".
 *
 * La barrera técnica no es leer el archivo;**Autorizar**:Android El SAF requiere que el usuario lo seleccione manualmente en el selector del sistema.
 * App(y mucho menos los modelos) no pueden especificar caminos arbitrarios por sí mismos. Por lo tanto, el modelo no puede "leer /sdcard/xxx directamente",
 * Solo se puede iniciar una solicitud y el contenido se devolverá una vez que el usuario haya terminado de seleccionar en la interfaz.
 *
 * ## proceso
 *
 * llamada de herramienta → [request] colgar → El flujo de estado publica solicitudes pendientes → UI Selector de sistema pop
 * → El usuario ha terminado de seleccionar (o cancelar)→ UI sintonizar [deliver] → La herramienta obtiene el resultado y continúa.
 *
 * La misma idea que la tarjeta de aprobación: el lado de la herramienta cuelga,UI Decisión lateral. La diferencia es que la aprobación sólo requiere "aceptar"/Rechazar",
 * Aquí queremos recuperar el contenido de un archivo real.
 *
 * ## tiempo de espera
 *
 * El usuario nunca podrá seleccionarlo (desactivarlo u olvidarlo). Devolver "no seleccionado" después del tiempo de espera en lugar de colgarse indefinidamente ——
 * Una llamada a una herramienta no debería ocupar una conversación para siempre.
 */
@Suppress("TooManyFunctions") // Solicitar/Entrega/Cancelar/Cada lectura tiene responsabilidades independientes y la fusión reducirá la legibilidad.
object FileAccessRequestBridge {

    /** Una petición. */
    data class Request(
        val id: String,
        /** Instrucciones para los usuarios: Por qué se necesita este archivo. */
        val reason: String,
        /** MIME Filtro (como clase de texto); vacío significa que no hay límite. */
        val mimeTypes: List<String> = emptyList(),
        /** Resultados de la selección de usuarios;null Dijo que aún no ha elegido. */
        val result: FileAccessResult? = null,
    )

    /** El resultado de la selección del usuario. */
    sealed interface FileAccessResult {
        /** El usuario seleccionó un archivo. */
        data class Picked(
            /** Mostrar el nombre del archivo. */
            val displayName: String,
            /** Contenido del archivo (truncado por límite superior). */
            val content: String,
            /** Tamaño original (antes del truncamiento). */
            val originalSize: Long,
            /** Si se truncó por exceder el límite. */
            val truncated: Boolean,
        ) : FileAccessResult

        /** El usuario cancela o agota el tiempo de espera. */
        data class Dismissed(val reason: String) : FileAccessResult
    }

    /**
     * Solicitudes pendientes actuales (UI Mira esto).
     *
     * v2.6.7 fix (AUDIT F10): La implementación anterior solo tiene una ranura —— `_pending.value = Request(...)`,
     * En caso de concurrencia, la segunda solicitud sobrescribirá directamente la primera; la ejecución que termina primero `_pending.value = null`
     * También se borrará el pendiente de este último. Entonces cuando se generan dos sesiones al mismo tiempo, la que se envía primero
     * Sólo puedes esperar 120 segundos para que se agote el tiempo de espera.UI La devolución de llamada también devolverá el resultado con una identificación incorrecta.
     *
     * Ahora es una cola por orden de llegada: solo el encabezado de la cola es visible y la siguiente se completa.
     */
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Request>()

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** Pendientes diferidos, indexados por id de solicitud. */
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<FileAccessResult>>()

    /** El límite superior de lectura de un solo archivo: si excede el límite, será truncado e informado en lugar de rechazado (es posible que el usuario solo quiera leer el comienzo). */
    const val MAX_CONTENT_CHARS = 200_000

    /**
     * El texto del resultado cuando el usuario hace clic en Volver (sin archivos seleccionados) en el selector del sistema.
     *
     * definido en el kit de herramientas: pasará [FileAccessResult.reason] El recibo que fluye hacia el modelo es el texto de la cara del modelo.
     * No debe escribirse en ui/ (el control de acceso chino codificado se escanea de acuerdo con el directorio y se informará por error como una copia de la interfaz no localizada).
     */
    const val USER_CANCELLED_PICK = "Selección cancelada por el usuario"

    /** Tiempo de espera de espera para la selección de usuario. */
    private const val PICK_TIMEOUT_MS = 120_000L

    /**
     * Haga una solicitud y espere el resultado.
     *
     * @return El resultado de la selección del usuario; regresó después del tiempo de espera [FileAccessResult.Dismissed].
     */
    suspend fun request(reason: String, mimeTypes: List<String> = emptyList()): FileAccessResult {
        val id = "fa_${System.currentTimeMillis().toString(36)}_${nextSeq.incrementAndGet()}"
        val deferred = CompletableDeferred<FileAccessResult>()
        waiters[id] = deferred
        queue.add(Request(id = id, reason = reason, mimeTypes = mimeTypes))
        publishHead()
        val result =
            withTimeoutOrNull(PICK_TIMEOUT_MS) { deferred.await() }
                ?: FileAccessResult.Dismissed("El usuario no está presente. ${PICK_TIMEOUT_MS / 1000} Seleccione archivos en segundos")
        waiters.remove(id)
        queue.removeAll { it.id == id }
        publishHead()
        return result
    }

    /** Publicar la solicitud del encabezado de la cola en la interfaz de usuario; publicar nulo si la cola está vacía. */
    private fun publishHead() {
        val head = queue.peek()
        if (_pending.value?.id != head?.id) _pending.value = head
    }

    /** UI Lado: El usuario ha terminado de seleccionar (o cancelar) y devuelve los resultados. */
    fun deliver(id: String, result: FileAccessResult) {
        waiters[id]?.complete(result)
    }

    /** UI Lado: darse por vencido activamente (como la destrucción de la página). */
    fun cancel(id: String, reason: String = "Solicitud cancelada") {
        waiters[id]?.complete(FileAccessResult.Dismissed(reason))
    }

    /** UI Lado: el usuario hizo clic en Volver (no hay ningún archivo seleccionado) en el selector del sistema. La redacción publicitaria está incluida en el kit de herramientas para evitar caracteres chinos en el modelo ui/. */
    fun deliverNoPick(id: String) {
        cancel(id, USER_CANCELLED_PICK)
    }

    /**
     * Leer el archivo seleccionado por el usuario a través de SAF y generar [FileAccessResult], utilizado por la herramienta read_user_file.
     *
     * Coloque el kit de herramientas en lugar de la capa UI: la copia producida aquí (cancelar/archivo vacío/Motivo del fallo de lectura) será
     * [FileAccessResult] El recibo que fluye hacia la herramienta y finalmente se lee en el modelo es el texto en la superficie del modelo, no la redacción publicitaria de la interfaz. ——
     * No debe localizarse mediante stringResource (lo que cambiaría el comportamiento del modelo), ni debe tenerse en cuenta mediante puertas codificadas al directorio ui/.
     *
     * Solo la interfaz de usuario obtiene el URI seleccionado por el usuario, y el lado de la herramienta no debe ni puede construirlo por sí mismo. —— Esta es exactamente la semántica de autorización de SAF,
     * Por lo tanto, la interfaz de usuario todavía llama a la entrada y, lógicamente, pertenece al kit de herramientas. Si la lectura falla, se tratará como "no exitosa" y no se lanzará ninguna excepción para interrumpir el ciclo de la herramienta.
     */
    fun readPickedFile(context: android.content.Context, uri: android.net.Uri): FileAccessResult {
        val maxChars = MAX_CONTENT_CHARS
        return runCatching {
            val resolver = context.contentResolver
            val displayName = safeDisplayName(uri.lastPathSegment)
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            // v2.6.7 fix (AUDIT F11): Veamos primero MIME. Clase sin texto (PDF / Imagen/paquete comprimido)
            // Leerlo directamente como texto solo dará como resultado caracteres confusos y el modelo lo citará como contenido real.
            val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            if (!isTextLike(mime, displayName)) {
                return FileAccessResult.Dismissed(
                    "El tipo de archivo (${mime.ifBlank { "desconocido" }}) no es texto sin formato y leerlo directamente solo generará caracteres confusos." +
                        "Solicite al usuario que cargue archivos adjuntos utilizando el campo de entrada del chat, y esa ruta utilizará el análisis de documentos.",
                )
            }
            val text =
                resolver.openInputStream(uri)?.use { input ->
                    // Lea de acuerdo con el límite de caracteres para evitar guardar todo el archivo grande en la memoria.
                    val reader = input.bufferedReader()
                    val buffer = CharArray(maxChars + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val read = reader.read(buffer, total, buffer.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    String(buffer, 0, total)
                } ?: ""
            if (text.isEmpty()) {
                FileAccessResult.Dismissed("El archivo está vacío o no se puede leer")
            } else {
                FileAccessResult.Picked(
                    displayName = displayName,
                    content = text.take(maxChars),
                    originalSize = if (size > 0) size else text.length.toLong(),
                    truncated = text.length > maxChars,
                )
            }
        }.getOrElse {
            FileAccessResult.Dismissed("Error de lectura:${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** Para realizar pruebas: borre todas las esperas. */
    internal fun resetForTest() {
        waiters.values.forEach { it.complete(FileAccessResult.Dismissed("reset")) }
        waiters.clear()
        queue.clear()
        _pending.value = null
    }

    /** Solicitar identificación para eliminar el número de serie —— Varias solicitudes dentro del mismo milisegundo no pueden chocar con los ID. */
    private val nextSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * v2.6.7 fix (AUDIT F11): Seguridad del nombre de archivo.
     *
     * estará escrito en `<user_file name="...">` Marcadores de límites. en el nombre del archivo `">` para cerrar la etiqueta,
     * y“El contenido no es una instrucción.”Nuestra protección depende de este límite. Aquí sólo se reservan juegos de caracteres conservadores.
     */
    private fun safeDisplayName(raw: String?): String {
        val base = raw?.substringAfterLast('/').orEmpty()
        if (base.isBlank()) return "archivo sin nombre"
        val cleaned = base.replace(Regex("[\\r\\n\"'<>]"), "_").trim()
        return cleaned.ifBlank { "archivo sin nombre" }.take(120)
    }

    /**
     * v2.6.7 fix (AUDIT F11): Si se puede leer como texto sin formato.
     *
     * MIME Si está vacío, vuelva al juicio de extensión; si no está seguro, intente según el texto (tolerante),
     * Pero los tipos conocidos que no son de texto (PDF / Imágenes/audio/vídeo/paquetes comprimidos/Office) se rechazan directamente.
     */
    private fun isTextLike(mime: String, displayName: String): Boolean {
        val m = mime.lowercase()
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return when {
            isTextMime(m) -> true
            isBinaryMime(m) -> false
            // MIME Desconocido: juzgado por extensión.
            else -> ext in TEXT_EXTENSIONS
        }
    }

    private fun isTextMime(m: String): Boolean = m.startsWith("text/") ||
        m == "application/json" ||
        m == "application/xml" ||
        m.endsWith("+json") ||
        m.endsWith("+xml")

    private fun isBinaryMime(m: String): Boolean = m.startsWith("image/") ||
        m.startsWith("audio/") ||
        m.startsWith("video/") ||
        m == "application/pdf" ||
        m.endsWith("/zip") ||
        m.contains("compressed") ||
        m.contains("office")

    /** Una lista blanca de extensiones que se pueden leer como texto sin formato. */
    private val TEXT_EXTENSIONS: Set<String> = setOf(
        "txt", "md", "markdown", "csv", "json", "xml", "log", "yaml", "yml",
        "ini", "kt", "java", "py", "js", "ts", "html", "htm", "css", "sql", "sh",
    )
}
