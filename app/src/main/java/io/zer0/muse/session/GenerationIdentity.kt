package io.zer0.muse.session

/**
 * Identidades estables generadas una vez a través de eventos entre capas.
 *
 * [generationId] Distinguir entre diferentes generaciones dentro de una misma sesión;[turnId] Representa una ronda de negocios;
 * [streamId] Representa uno de los flujos del proveedor. El reintento o la recuperación se pueden realizar a través de
 * [parentGenerationId] Vinculado a la generación original, pero no puede reutilizar la identidad de la generación original.
 */
data class GenerationIdentity(
    val sessionId: String,
    val turnId: String,
    val generationId: String,
    val streamId: String,
    val parentGenerationId: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(generationId.isNotBlank()) { "generationId must not be blank" }
        require(streamId.isNotBlank()) { "streamId must not be blank" }
    }
}
