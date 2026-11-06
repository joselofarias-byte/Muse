package io.zer0.muse.web

import java.io.File
import java.util.Locale

/**
 * Determinación del tipo de medio: primero mire el encabezado del archivo y luego vuelva a la extensión.
 *
 * Cuando la imagen del mensaje se comercializa, se utiliza `.bin` sufijo (`{messageId}_{idx}_{hash}.bin`), la extensión no proporciona ninguna información;
 * El sufijo del archivo de voz proviene del contenedor devuelto por TTS (wav/m4a/mp3 son posibles). Cuando ambas partes no son confiables,
 * El encabezado del archivo es el único criterio, así que mire primero el encabezado y luego el nombre.
 *
 * Regresar `application/octet-stream` Dijo que no podía decir —— El enrutador seguirá enviando el archivo,
 * Es sólo que el navegador lo tratará como un archivo adjunto y no generará un error 404.
 */
internal object HostMediaContentType {

    /** El número de bytes utilizados para leer el encabezado del archivo: el número mágico que es suficiente para cubrir cada formato (el más largo es RIFF/WEBP de 12 bytes). */
    private const val HEADER_BYTES = 16

    const val OCTET_STREAM = "application/octet-stream"

    private const val PNG = "image/png"
    private const val JPEG = "image/jpeg"
    private const val GIF = "image/gif"
    private const val WEBP = "image/webp"
    private const val MP3 = "audio/mpeg"
    private const val MP4 = "audio/mp4"
    private const val AAC = "audio/aac"
    private const val WAV = "audio/wav"
    private const val OGG = "audio/ogg"

    /** extensión → MIME. con [MIME_TO_EXTENSION] Son inversos entre sí y solo incluyen tipos que realmente aparecen en el directorio de medios. */
    private val EXTENSION_TO_MIME = mapOf(
        "png" to PNG,
        "jpg" to JPEG,
        "jpeg" to JPEG,
        "gif" to GIF,
        "webp" to WEBP,
        "mp3" to MP3,
        "m4a" to MP4,
        "mp4" to MP4,
        "aac" to AAC,
        "wav" to WAV,
        "ogg" to OGG,
        "opus" to OGG,
    )

    private val MIME_TO_EXTENSION = mapOf(
        PNG to "png",
        JPEG to "jpg",
        GIF to "gif",
        WEBP to "webp",
        MP3 to "mp3",
        MP4 to "m4a",
        AAC to "aac",
        WAV to "wav",
        OGG to "ogg",
    )

    /** MIME → La extensión utilizada para la colocación del disco; para tipos desconocidos, se devuelve nulo (sin ubicación en disco, sin envío de enlace). */
    fun extensionFor(mime: String): String? = MIME_TO_EXTENSION[mime.lowercase(Locale.ROOT).substringBefore(';')]

    /** Determinar según el encabezado del archivo; devuelve nulo si no se determina. Las imágenes y el audio recibirán la mitad del premio cada uno. */
    fun detect(head: ByteArray): String? = detectImage(head) ?: detectAudio(head)

    private fun detectImage(head: ByteArray): String? = when {
        head.size < 4 -> null
        head[0] == 0x89.toByte() && ascii(head, 1, 3) == "PNG" -> PNG
        head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> JPEG
        ascii(head, 0, 6) == "GIF87a" || ascii(head, 0, 6) == "GIF89a" -> GIF
        ascii(head, 0, 4) == "RIFF" && head.size >= 12 && ascii(head, 8, 4) == "WEBP" -> WEBP
        else -> null
    }

    private fun detectAudio(head: ByteArray): String? = when {
        head.size < 4 -> null
        ascii(head, 0, 4) == "RIFF" && head.size >= 12 && ascii(head, 8, 4) == "WAVE" -> WAV
        ascii(head, 0, 4) == "OggS" -> OGG
        ascii(head, 0, 3) == "ID3" -> MP3
        head.size >= 8 && ascii(head, 4, 4) == "ftyp" -> MP4
        // ADTS Palabra de sincronización de cuadros:11 Cada uno es 1, el primer byte es 0xFF y los 6 bits superiores del segundo byte son todos unos.
        // El fotograma MPEG desnudo sin encabezado ID3 coincide con el comienzo de ADTS. Si la sentencia falla, la prórroga se utilizará para determinar el fin.
        head[0] == 0xFF.toByte() && head[1].toInt() and 0xF6 == 0xF0 -> AAC
        else -> null
    }

    /**
     * Determinar el tipo de archivo que se ha descargado: se da prioridad al encabezado del archivo. Si no se puede determinar, mire la extensión. Si no lo reconoces regresa [OCTET_STREAM].
     *
     * Si el cabezal de lectura falla (el archivo se limpia al mismo tiempo y no hay permiso), se devuelve nulo, que será corregido por la persona que llama; se arroja un error inapropiado.
     */
    fun of(file: File): String? {
        val head = readHead(file) ?: return null
        return detect(head) ?: EXTENSION_TO_MIME[file.extension.lowercase(Locale.ROOT)] ?: OCTET_STREAM
    }

    /**
     * Lea el encabezado del archivo.
     *
     * No hay necesidad `InputStream.readNBytes`: Ese es un método que solo está disponible en API 33. Relleno cero cuando el archivo es más corto de lo esperado.
     * La función de determinación simplemente toma los primeros bytes según la longitud.
     */
    private fun readHead(file: File): ByteArray? = runCatching {
        val buffer = ByteArray(HEADER_BYTES)
        file.inputStream().use { input ->
            var filled = 0
            while (filled < HEADER_BYTES) {
                val read = input.read(buffer, filled, HEADER_BYTES - filled)
                if (read < 0) break
                filled += read
            }
        }
        buffer
    }.getOrNull()

    /** `data:` URI El tipo declarado en; devuelve nulo si no se declara. */
    fun declaredMime(value: String): String? {
        if (!value.startsWith("data:")) return null
        val header = value.substringAfter("data:").substringBefore(',', "")
        if (!header.contains("base64")) return null
        return header.substringBefore(';').takeIf { it.contains('/') }?.lowercase(Locale.ROOT)
    }

    /** Despegar `data:…;base64,` prefijo, devuelve una carga útil base64 pura. */
    fun inlinePayload(value: String): String = value.substringAfter(',', value)

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (bytes.size < offset + length) "" else String(bytes, offset, length, Charsets.US_ASCII)
}
