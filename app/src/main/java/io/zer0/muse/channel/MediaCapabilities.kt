package io.zer0.muse.channel

/**
 * Declaración de capacidad de los medios del canal.
 *
 * Antecedentes del problema: anteriormente, el grado de soporte mediático proporcionado por cada canal solo existía en los detalles de implementación. ——
 * Feishu colecciona fotografías y las escribe hasta morir. `"[fotos]"`,QQ Sólo puedes enviar texto y DingTalk no procesa imágenes en absoluto.
 * No hay ningún lugar donde el nivel superior pueda averiguar "si este canal puede publicar imágenes, qué tamaño puede publicar y cómo se puede publicar".
 * Así cada pulsador tiene su propio `when (platform)`, al agregar canales solo se pierde un punto.
 *
 * Aquí, las capacidades se convierten en una declaración estructurada y una verificación obligatoria:
 *  - Cada medio admitido debe declarar un método de entrega.
 *  - Cada método de entrada debe dar un límite superior de bytes.
 * Si la declaración está incompleta, se generará un error durante la inicialización y la falla no esperará hasta que el usuario envíe una imagen.
 */
data class MediaCapabilities(
    /** Este canal recibe/Métodos de entrada admitidos al enviar medios. */
    val inputModes: Set<MediaInputMode>,
    /** Qué tipos de medios son compatibles. */
    val supportedKinds: Set<MediaKind>,
    /**
     * Tamaño máximo (en bytes) por tipo de medio por método de entrada.
     *
     * Los límites superiores de la memoria intermedia y la URL para la misma imagen suelen ser diferentes y deben declararse por separado.
     */
    val maxBytes: Map<MediaInputMode, Map<MediaKind, Long>>,
    /** Cómo se entrega cada tipo de medio. */
    val deliveryByKind: Map<MediaKind, DeliveryMode>,
    /**
     * Si es necesario traer el contexto de "la carta más reciente" para poder responder.
     *
     * El puente WeChat entra en esta categoría: sólo puede responder a la persona que acaba de enviar el mensaje y no puede abrir una nueva conversación de la nada.
     */
    val requiresReplyContext: Boolean,
) {
    /** Autotest estructural. Si la declaración está incompleta, se generará un error directamente y no se dejará en manos del tiempo de ejecución. */
    fun validate(platform: String) {
        require(inputModes.isNotEmpty()) { "$platform: mediaCapabilities.inputModes no puede estar vacío" }
        require(supportedKinds.isNotEmpty()) { "$platform: mediaCapabilities.supportedKinds no puede estar vacío" }
        supportedKinds.forEach { kind ->
            require(deliveryByKind.containsKey(kind)) {
                "$platform: mediaCapabilities.deliveryByKind desaparecido $kind"
            }
        }
        inputModes.forEach { mode ->
            val perKind = maxBytes[mode]
            requireNotNull(perKind) { "$platform: mediaCapabilities.maxBytes Método de entrada faltante $mode" }
            supportedKinds.forEach { kind ->
                require((perKind[kind] ?: 0L) > 0L) {
                    "$platform: mediaCapabilities.maxBytes[$mode] desaparecido $kind límite superior"
                }
            }
        }
    }

    /** Consultar el límite superior de un determinado método de entrada y un determinado medio; No se admite devolver 0. */
    fun limitFor(mode: MediaInputMode, kind: MediaKind): Long = maxBytes[mode]?.get(kind) ?: 0L

    /** Ya sea para apoyar ciertos medios. */
    fun supports(kind: MediaKind): Boolean = kind in supportedKinds
}

/** Método de entrada de medios. */
enum class MediaInputMode {
    /** Flujo de bytes de memoria (cargado directamente después de leer localmente). */
    BUFFER,

    /** Ruta del archivo local (algunas plataformas solo aceptan archivos, no bytes de memoria). */
    LOCAL_FILE,

    /** La dirección remota que requiere autenticación (extraída por la propia plataforma). */
    REMOTE_URL,

    /** Dirección de acceso público. */
    PUBLIC_URL,
}

/** Tipo de medio. */
enum class MediaKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
}

/** Método de entrega: decida cómo la alta dirección entregará los medios a la plataforma. */
enum class DeliveryMode {
    /** Mensajes con imágenes nativas de la plataforma. */
    NATIVE_IMAGE,

    /** Plataforma de mensajería de vídeo nativa. */
    NATIVE_VIDEO,

    /** Plataforma de mensajería de audio nativa. */
    NATIVE_AUDIO,

    /** Mensajes de archivos nativos de la plataforma. */
    NATIVE_DOCUMENT,

    /** Solo se puede enviar como un enlace (la plataforma no admite medios nativos). */
    LINK_ONLY,
}

/** Constantes de bytes de uso común para evitar la escritura repetida de literales en cada canal. */
internal object MediaLimits {
    const val MB = 1024L * 1024L
}
