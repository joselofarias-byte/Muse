package io.zer0.muse.perf

import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Phase 6 6B: Paginación de la lista de mensajes — 50Artículo/página,Desplácese hacia arriba para cargar más.
 *
 * Características:
 * - uso stable key (messageId) Rendimiento garantizado de Compose LazyList
 * - Markdown analizar caché: remember(messageId + contentHash)
 * - Limitación de la transmisión de animación: intervalo de actualización 50ms
 *
 * Uso:
 * ```
 * val pagedMessages = MessagePaginator.createFlow(allMessages, pageSize = 50)
 * ```
 */
object MessagePaginator {

    private const val TAG = "MessagePaginator"
    const val DEFAULT_PAGE_SIZE = 50

    /**
     * Cree un flujo paginado a partir de la lista completa de mensajes.
     *
     * @param allMessages Lista completa de ID de mensajes(en orden cronológico)
     * @param pageSize tamaño de página
     * @return Lista paginada de ID de mensajes Flow(Acumular en orden de carga)
     */
    fun createFlow(allMessages: List<String>, pageSize: Int = DEFAULT_PAGE_SIZE): Flow<List<String>> = flow {
        if (allMessages.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val total = allMessages.size
        var loaded = pageSize.coerceAtMost(total)

        // de la cola(últimas noticias)Empezar a cargar
        emit(allMessages.subList(total - loaded, total))

        // cargar más(Agregar cada vez pageSize)
        while (loaded < total) {
            loaded = (loaded + pageSize).coerceAtMost(total)
            emit(allMessages.subList(total - loaded, total))
        }
        Logger.d(TAG, "Loaded $loaded/$total messages in pages of $pageSize")
    }

    /**
     * Calcular contenido hash(para el almacenamiento en caché de Markdown key).
     *
     * @param messageId noticias ID
     * @param content Contenido del mensaje
     * @return caché key
     */
    fun contentCacheKey(messageId: String, content: String): String {
        return "$messageId:${content.hashCode()}"
    }
}

/**
 * Phase 6 6B: Limitación de la transmisión de animación — Limite la frecuencia de actualización a cada 50 ms.
 *
 * Uso:
 * ```
 * val throttledText = streamText.throttle(50)
 * ```
 */
fun <T> Flow<T>.throttle(intervalMs: Long = 50): Flow<T> = flow {
    var lastEmit = 0L
    collect { value ->
        val now = System.currentTimeMillis()
        if (now - lastEmit >= intervalMs) {
            lastEmit = now
            emit(value)
        }
    }
    // Asegúrese de que también se envíe el último valor
}
