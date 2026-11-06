package io.zer0.muse.transformer

/**
 * Estrategia de umbral para la compresión automática de contexto —— Fuente única de verdad.
 *
 * Reseña encontrada (X-5): El umbral de compresión estaba previamente codificado en varios lugares:
 *  - [ContextCompressTransformer] El valor predeterminado complementario 20 / 15
 *  - `ChatStreamCoordinator` Escribir literales al montar extras `if (longMemoryCompression) 10 else 20` / `8 else 15`
 *  - `ChannelAutoReply` de COMPRESS_THRESHOLD = 40(Subsistema independiente en el lado del canal)
 *  - `ChatViewModel.manualCompress` pasar threshold = 1(Gatillo manual forzado)
 *
 * Cuatro semánticas**no es lo mismo**, no se puede fusionar a ciegas:
 *  - Este objeto sólo unifica"**Diálogo principal comprimido automáticamente**"Esta semántica (Transformer El valor predeterminado y el valor del diálogo principal deben ser el mismo conjunto);
 *  - Comprimido manualmente `1` si"forzar el gatillo"Semántica ([FORCE_TRIGGER_THRESHOLD]), deliberadamente diferentes de los umbrales convencionales;
 *  - Prensa lateral del canal"rondas(turn)"en vez de"Número de mensajes(message)"El conteo y el almacenamiento independiente pertenecen a otro subsistema.
 *    su umbral permanece en `ChannelAutoReply`, aquí solo se proporciona la descripción a la que se cita.
 *
 * Cambiar el valor aquí también afectará las rutas de diálogo principales y predeterminadas de Transformer para evitar"Cambiar un lugar no tendrá efecto".
 */
internal object CompressionPolicy {
    /** Compresión automática de la conversación principal: el umbral de la cantidad de mensajes que activa la compresión (archivo predeterminado). */
    const val THRESHOLD_DEFAULT = 20

    /** Compresión automática de la conversación principal: la cantidad de mensajes recientes retenidos después de la compresión (archivo predeterminado). */
    const val KEEP_RECENT_DEFAULT = 15

    /**
     * Compresión automática del cuadro de diálogo principal: se activa antes cuando la compresión experimental longMemoryCompression está activada.
     *
     * Restricciones:threshold debe > keepRecent; de lo contrario, el intervalo compresible estará vacío y la compresión nunca se activará.
     * (ContextCompressTransformer Hay una advertencia al respecto).
     */
    const val THRESHOLD_LONG_MEMORY = 10

    /** Diálogo principal comprimido automáticamente:longMemoryCompression La cantidad de mensajes recientes retenidos cuando está habilitado. */
    const val KEEP_RECENT_LONG_MEMORY = 8

    /**
     * Umbral de activación de fuerza para compresión manual —— No existe un umbral para la cantidad de elementos cuando los usuarios hacen clic activamente en la compresión (se comprimirá cualquier historial).
     * La semántica es intencionalmente diferente de los umbrales convencionales ([THRESHOLD_DEFAULT]), no lo reemplace con un valor regular.
     */
    const val FORCE_TRIGGER_THRESHOLD = 1

    /** Presione el interruptor longMemoryCompression para obtener el umbral de activación. */
    fun threshold(longMemoryCompression: Boolean): Int = if (longMemoryCompression) THRESHOLD_LONG_MEMORY else THRESHOLD_DEFAULT

    /** Presione el interruptor longMemoryCompression para obtener la cantidad de elementos retenidos. */
    fun keepRecent(longMemoryCompression: Boolean): Int = if (longMemoryCompression) KEEP_RECENT_LONG_MEMORY else KEEP_RECENT_DEFAULT
}
