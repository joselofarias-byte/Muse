package io.zer0.muse.channel

/**
 * Medios a enviar.
 *
 * Tres fuentes corresponden a diferentes rutas de carga: bytes de memoria, archivos locales y direcciones públicas.
 * La elección específica está determinada por la tabla de capacidades de cada plataforma (ver [MediaCapabilities.inputModes]),
 * La persona que llama no necesita conocer las diferencias de plataforma.
 */
sealed interface OutboundMedia {
    /** El tipo de medio determina el método de entrega. */
    val kind: MediaKind

    /** Nombre del archivo, requerido para algunas plataformas (como la carga de Feishu). */
    val fileName: String

    /** MIME tipo. */
    val mimeType: String

    /** bytes de memoria. */
    data class Bytes(
        override val kind: MediaKind,
        val bytes: ByteArray,
        override val fileName: String,
        override val mimeType: String,
    ) : OutboundMedia

    /** Ruta del archivo local. */
    data class LocalFile(
        override val kind: MediaKind,
        val path: String,
        override val fileName: String,
        override val mimeType: String,
    ) : OutboundMedia

    /** Dirección de acceso público. */
    data class RemoteUrl(
        override val kind: MediaKind,
        val url: String,
        override val fileName: String,
        override val mimeType: String,
    ) : OutboundMedia
}

/** Los medios envían resultados. Proporciona un motivo legible del error para que se pueda enviar un recibo al usuario. */
data class MediaSendResult(
    val ok: Boolean,
    val detail: String = "",
) {
    companion object {
        fun success(): MediaSendResult = MediaSendResult(ok = true)
        fun failure(reason: String): MediaSendResult = MediaSendResult(ok = false, detail = reason)
    }
}

/**
 * Comprueba si un canal puede entregar un determinado medio según lo especificado.
 *
 * Devuelve nulo para indicar que se puede enviar; en caso contrario, devuelva el motivo por el cual no se puede enviar.
 * Botón de evaluación de límite superior [MediaInputMode] La diferencia se debe a que el mismo medio tiene diferentes restricciones de memoria y URL.
 */
fun ChannelPlatform.checkMediaSendable(media: OutboundMedia): String? {
    val caps = capabilities.mediaCapabilities
    val mode = when (media) {
        is OutboundMedia.Bytes -> MediaInputMode.BUFFER
        is OutboundMedia.LocalFile -> MediaInputMode.LOCAL_FILE
        is OutboundMedia.RemoteUrl -> MediaInputMode.PUBLIC_URL
    }
    val actual = when (media) {
        is OutboundMedia.Bytes -> media.bytes.size.toLong()
        is OutboundMedia.LocalFile -> runCatching { java.io.File(media.path).length() }.getOrDefault(0L)
        // El tamaño de la dirección remota no se puede predecir y se deja a la plataforma para juzgarlo.
        is OutboundMedia.RemoteUrl -> 0L
    }
    return when {
        !caps.supports(media.kind) ->
            "$name No soportado para enviar ${media.kind.name.lowercase()}"
        mode !in caps.inputModes ->
            "$name No soportado como ${mode.name.lowercase()} Enviar por"
        else -> {
            val limit = caps.limitFor(mode, media.kind)
            if (actual > 0L && limit > 0L && actual > limit) {
                val mb = actual / (1024.0 * 1024.0)
                val limitMb = limit / (1024.0 * 1024.0)
                "$name de ${media.kind.name.lowercase()} El límite superior es " +
                    "${"%.1f".format(limitMb)}MB, actualmente ${"%.1f".format(mb)}MB"
            } else {
                null
            }
        }
    }
}

/**
 * Contenido a subir.
 *
 * Los parámetros de la interfaz de carga de cada plataforma son muy similares (bytes + nombre de archivo + MIME).
 * Empacarlo en un objeto evita distribuir siete u ocho parámetros para cada método.
 */
data class MediaPayload(
    val kind: MediaKind,
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaPayload) return false
        return kind == other.kind &&
            bytes.contentEquals(other.bytes) &&
            fileName == other.fileName &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }

    companion object {
        /** de [OutboundMedia] Leer bytes; lea archivos locales desde el disco y descárguelos desde direcciones remotas. */
        suspend fun from(media: OutboundMedia): MediaPayload? {
            val bytes = when (media) {
                is OutboundMedia.Bytes -> media.bytes
                is OutboundMedia.LocalFile ->
                    runCatching { java.io.File(media.path).readBytes() }.getOrNull()
                is OutboundMedia.RemoteUrl -> ChannelImageFetcher.downloadRaw(media.url)
            } ?: return null
            return MediaPayload(
                kind = media.kind,
                bytes = bytes,
                fileName = media.fileName,
                mimeType = media.mimeType,
            )
        }
    }
}

/**
 * Subir objetivo.
 *
 * Cada plataforma requiere "enviar a qué sesión", pero los nombres de los campos son diferentes.
 */
data class UploadTarget(
    /** Identificación del objetivo en el lado de la plataforma (chat_id / openid / openConversationId etcétera). */
    val id: String,
    /** Si tener una conversación grupal. */
    val isGroup: Boolean,
)
