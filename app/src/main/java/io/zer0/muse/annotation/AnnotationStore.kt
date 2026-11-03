package io.zer0.muse.annotation

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * v1.0.92: Almacenamiento de anotaciones de mensajes — Anotación de texto sin formato de un usuario en un solo mensaje.
 *
 * Singleton global(objeto estático):UI escribiendo y leyendo;persistir en filesDir/annotations.json(Escritura atómica).
 * Anotar como datos locales,No involucrado en ninguna carga;Lista de comentarios disponibles para referencia contextual en conversaciones posteriores(Relacionar por ID de mensaje).
 */
object AnnotationStore {

    /** comentario único(Relacionar por ID de mensaje). */
    @Serializable
    data class Annotation(
        val id: String,
        val messageId: String,
        val sessionId: String = "",
        val text: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private const val TAG = "AnnotationStore"
    private const val MAX_ITEMS = 500

    private val _all = MutableStateFlow<List<Annotation>>(emptyList())
    val all: StateFlow<List<Annotation>> = _all.asStateFlow()

    private var file: File? = null

    /** Vincular el contexto de la aplicación y restaurar anotaciones históricas(idempotente). */
    @Synchronized
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, "annotations.json")
        file = target
        if (target.exists()) {
            runCatching {
                AppJson.decodeFromString(ListSerializer(Annotation.serializer()), target.readText())
            }.onSuccess { _all.value = it.take(MAX_ITEMS) }
                .onFailure { e -> Logger.w(TAG, "Falló la recuperación del comentario: ${e.message}") }
        }
    }

    /** Todos los comentarios en un mensaje.(orden ascendente por tiempo). */
    fun ofMessage(messageId: String): List<Annotation> = _all.value.filter { it.messageId == messageId }.sortedBy { it.createdAt }

    /** Añade un nuevo comentario. */
    fun add(messageId: String, sessionId: String, text: String) {
        val trimmed = text.trim()
        if (messageId.isBlank() || trimmed.isEmpty()) return
        val item = Annotation(
            id = "an_" + System.currentTimeMillis(),
            messageId = messageId,
            sessionId = sessionId,
            text = trimmed.take(2000),
        )
        val updated = (listOf(item) + _all.value).take(MAX_ITEMS)
        _all.value = updated
        persist(updated)
    }

    /** Eliminar un comentario. */
    fun remove(id: String) {
        val updated = _all.value.filterNot { it.id == id }
        _all.value = updated
        persist(updated)
    }

    private fun persist(items: List<Annotation>) {
        val target = file ?: return
        runCatching {
            AtomicFileStore.writeText(
                target,
                AppJson.encodeToString(ListSerializer(Annotation.serializer()), items),
            )
        }.onFailure { e -> Logger.w(TAG, "La persistencia de la anotación falló: ${e.message}") }
    }
}
