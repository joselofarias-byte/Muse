package io.zer0.muse.data.routing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Diagnósticos en memoria; nunca cambia de modelo ni activa un fallback de pago. */
object UtilityModelHealth {
    data class Failure(
        val modelId: String,
        val message: String,
        val at: Long,
    )

    private const val KEEP_MS = 24 * 60 * 60 * 1000L
    private val _failures = MutableStateFlow<Map<String, Failure>>(emptyMap())
    val failures: StateFlow<Map<String, Failure>> = _failures.asStateFlow()

    fun record(providerId: String?, modelId: String, message: String, at: Long = System.currentTimeMillis()) {
        if (modelId.isBlank()) return
        _failures.update { failures ->
            failures.filterValues { at - it.at <= KEEP_MS } +
                (key(providerId, modelId) to Failure(modelId, message.take(200), at))
        }
    }

    fun lastFor(providerId: String?, modelId: String?, now: Long = System.currentTimeMillis()): Failure? {
        if (modelId.isNullOrBlank()) return null
        val hit = _failures.value[key(providerId, modelId)] ?: return null
        return hit.takeIf { now - it.at <= KEEP_MS }
    }

    fun clear(providerId: String?, modelId: String?) {
        if (modelId.isNullOrBlank()) return
        _failures.update { it - key(providerId, modelId) }
    }

    private fun key(providerId: String?, modelId: String): String = "${providerId.orEmpty()}|$modelId"
}
