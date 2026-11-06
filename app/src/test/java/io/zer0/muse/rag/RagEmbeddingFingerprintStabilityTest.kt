package io.zer0.muse.rag

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-RAG-F3: embedding Las huellas dactilares deben utilizar un calibre de dimensión estable.,No se pueden utilizar "Dimensiones adivinadas antes de la primera inserción".
 *
 * Implementación original:`embeddingFingerprint` Luchar directamente `provider.dimension`,Cuando este valor no se mide realmente, se
 * guessDimension() valor estimado —— Cuando el valor estimado no coincide con la dimensión real,Las huellas dactilares están "adivinando"→entre "reales"
 * saltar,HNSW El índice determina los cambios de huellas dactilares y los reconstruye cada vez que se arranca en frío.
 */
class RagEmbeddingFingerprintStabilityTest {

    private fun ragSource(): String = java.io.File("src/main/java/io/zer0/muse/rag/RagService.kt").readText()

    private fun providerSource(): String = java.io.File("src/main/java/io/zer0/muse/rag/OpenAIEmbeddingProvider.kt").readText()

    @Test
    fun `fingerprint uses stable dimension instead of raw dimension`() {
        val src = ragSource()
        val start = src.indexOf("private fun embeddingFingerprint(")
        assertTrue("no encontrado embeddingFingerprint", start >= 0)
        val body = src.substring(start, start + 600)
        assertTrue(
            "Las huellas dactilares deben estar escritas. fingerprintDimension(calibre estable)",
            body.contains("provider.fingerprintDimension"),
        )
        assertTrue(
            "Las huellas dactilares ya no deberían escribirse directamente provider.dimension",
            !body.contains("\${provider.dimension}"),
        )
    }

    @Test
    fun `cloud provider exposes stable fingerprint dimension`() {
        val src = providerSource()
        assertTrue(
            "OpenAIEmbeddingProvider Debe sobrescribir fingerprintDimension",
            src.contains("override val fingerprintDimension: String"),
        )
        assertTrue(
            "Cuando no se mide, la dimensión de la huella digital debe ser un marcador de posición estable. unknown,en lugar de valor estimado",
            src.contains("measuredDimension?.toString() ?: \"unknown\""),
        )
    }
}
