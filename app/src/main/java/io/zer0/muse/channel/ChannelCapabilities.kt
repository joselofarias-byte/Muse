package io.zer0.muse.channel

/**
 * Declaración de capacidad del canal.
 *
 * Resulta que el nivel superior solo puede escribir "si esta plataforma puede enviar activamente/si está ingresando".
 * `when (platform)`, agregar una plataforma requiere cambiar todos los puntos de juicio y es fácil pasarlo por alto.
 *
 * Después de convertir las habilidades en datos,UI La capa de programación solo lee esta tabla y las nuevas plataformas solo agregan una entrada.
 */
data class ChannelCapabilities(
    /** ¿Puede iniciar un mensaje de forma proactiva (sin que la otra parte hable primero)? */
    val proactive: Boolean,
    /** Ya sea para admitir instrucciones de \"escribir\". */
    val typing: Boolean,
    /** Ya sea para admitir mensajes de los medios. */
    val media: Boolean,
    /** Ya sea para admitir la recepción de mensajes/Leer. */
    val receipt: Boolean,
    /**
     * Si un único mensaje debe tener el contexto del "correo electrónico más reciente" antes de poder responderlo.
     *
     * El puente WeChat entra en esta categoría: sólo puede responder a la persona que acaba de enviar el mensaje y no puede abrir una nueva conversación de la nada.
     */
    val requiresRecentInbound: Boolean,
    /** Tabla de capacidad de medios: método de entrada, tipo admitido, límite de bytes, método de entrega. */
    val mediaCapabilities: MediaCapabilities,
)

/** Tabla de capacidades para cada plataforma. Simplemente agregue una nueva plataforma aquí. */
val ChannelPlatform.capabilities: ChannelCapabilities
    get() = when (this) {
        ChannelPlatform.FEISHU -> ChannelCapabilities(
            proactive = true,
            typing = false,
            media = true,
            receipt = true,
            requiresRecentInbound = false,
            mediaCapabilities = FEISHU_MEDIA,
        )
        ChannelPlatform.QQ -> ChannelCapabilities(
            proactive = true,
            typing = false,
            media = true,
            receipt = false,
            requiresRecentInbound = false,
            mediaCapabilities = QQ_MEDIA,
        )
        ChannelPlatform.DINGTALK -> ChannelCapabilities(
            proactive = true,
            typing = false,
            media = true,
            receipt = false,
            requiresRecentInbound = false,
            mediaCapabilities = DINGTALK_MEDIA,
        )
        ChannelPlatform.TELEGRAM -> ChannelCapabilities(
            proactive = true,
            typing = true,
            media = true,
            receipt = false,
            requiresRecentInbound = false,
            mediaCapabilities = TELEGRAM_MEDIA,
        )
        // El puente WeChat solo puede responder a la persona que acaba de enviar el mensaje y no puede abrir activamente una nueva conversación.
        ChannelPlatform.WECLAW -> ChannelCapabilities(
            proactive = false,
            typing = false,
            media = true,
            receipt = false,
            requiresRecentInbound = true,
            mediaCapabilities = WECLAW_MEDIA,
        )
    }

// ── Tabla de capacidad de medios para cada canal. ────────────────────────────────────────────────
// Los valores se toman de los límites estrictos de las interfaces oficiales de cada plataforma; los valores que no se indican explícitamente se toman como valores conservadores.

/** Fei Shu:im/v1/images Subir, imagen única 10 MB; archivo vaya a im/v1/files. */
private val FEISHU_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.LOCAL_FILE, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.AUDIO to DeliveryMode.NATIVE_AUDIO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** QQ: La interfaz de medios enriquecidos debe cargar file_info primero y luego usar msg_type=7 Enviar. */
private val QQ_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.LOCAL_FILE, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 30 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 30 * MediaLimits.MB,
            MediaKind.VIDEO to 30 * MediaLimits.MB,
            MediaKind.AUDIO to 30 * MediaLimits.MB,
            MediaKind.DOCUMENT to 30 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.AUDIO to DeliveryMode.NATIVE_AUDIO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** DingTalk: Los mensajes de robots solo admiten imágenes de forma nativa; Los vídeos y audios se degradan a enlaces. */
private val DINGTALK_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** Telegram:sendPhoto/sendVideo/sendAudio/sendDocument;bot El límite de carga es de 50 MB. */
private val TELEGRAM_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.LOCAL_FILE, MediaInputMode.PUBLIC_URL),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 50 * MediaLimits.MB,
            MediaKind.AUDIO to 50 * MediaLimits.MB,
            MediaKind.DOCUMENT to 50 * MediaLimits.MB,
        ),
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 50 * MediaLimits.MB,
            MediaKind.AUDIO to 50 * MediaLimits.MB,
            MediaKind.DOCUMENT to 50 * MediaLimits.MB,
        ),
        MediaInputMode.PUBLIC_URL to mapOf(
            MediaKind.IMAGE to 5 * MediaLimits.MB,
            MediaKind.VIDEO to 20 * MediaLimits.MB,
            MediaKind.AUDIO to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.AUDIO to DeliveryMode.NATIVE_AUDIO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = false,
)

/** Puente WeChat: solo puede responder a las personas que acaban de escribir y los medios utilizan CDN + AES. */
private val WECLAW_MEDIA = MediaCapabilities(
    inputModes = setOf(MediaInputMode.BUFFER, MediaInputMode.LOCAL_FILE),
    supportedKinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.DOCUMENT),
    maxBytes = mapOf(
        MediaInputMode.BUFFER to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
        MediaInputMode.LOCAL_FILE to mapOf(
            MediaKind.IMAGE to 10 * MediaLimits.MB,
            MediaKind.VIDEO to 20 * MediaLimits.MB,
            MediaKind.DOCUMENT to 20 * MediaLimits.MB,
        ),
    ),
    deliveryByKind = mapOf(
        MediaKind.IMAGE to DeliveryMode.NATIVE_IMAGE,
        MediaKind.VIDEO to DeliveryMode.NATIVE_VIDEO,
        MediaKind.DOCUMENT to DeliveryMode.NATIVE_DOCUMENT,
    ),
    requiresReplyContext = true,
)

/**
 * Inicie la autoprueba: recorra las declaraciones de capacidad de medios de todas las plataformas y genere un error si faltan los campos.
 *
 * Póngalo aquí en lugar de esperar el tiempo de ejecución, porque una vez que este tipo de error se filtra a la línea,
 * El rendimiento es "no hay respuesta cuando los usuarios publican imágenes", lo cual es difícil de localizar en los registros.
 */
internal fun validateChannelCapabilities() {
    ChannelPlatform.entries.forEach { platform ->
        platform.capabilities.mediaCapabilities.validate(platform.name)
    }
}
