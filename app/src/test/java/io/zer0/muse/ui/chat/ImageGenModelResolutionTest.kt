package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Modelo de dibujo de la ruta de la sesión en detalle.:Los modelos de texto no deben utilizarse como modelos de dibujo.
 *
 * ruta de la herramienta(MediaGenToolsImpl)Utilice siempre la comprobación de soportesImageOutput,La ruta de la sesión era originalmente directa.
 * `models.firstOrNull()`,Los dos calibres son inconsistentes.,Como resultado, la misma configuración informó un error ascendente en el chat pero parecía normal en la herramienta.
 */
class ImageGenModelResolutionTest {

    private val textModel = Model(id = "gpt-4o", providerId = "p1")
    private val imageModel = Model(id = "gpt-image-1", providerId = "p1", outputModalities = setOf("text", "image"))

    private fun provider(vararg models: Model): ProviderConfig = ProviderConfig(
        id = "p1",
        displayName = "P1",
        models = models.toList(),
    )

    @Test
    fun requestedImageModelIsUsed() {
        val resolved = resolveImageCapableModel(provider(textModel, imageModel), imageModel)

        assertEquals("gpt-image-1", resolved?.id)
    }

    @Test
    fun textOnlyModelFallsBackToFirstImageCapableModel() {
        val resolved = resolveImageCapableModel(provider(textModel, imageModel), textModel)

        assertEquals("Se deben omitir los modelos que no se pueden trazar.", "gpt-image-1", resolved?.id)
    }

    @Test
    fun providerWithoutImageCapableModelResolvesNothing() {
        val resolved = resolveImageCapableModel(provider(textModel), textModel)

        assertNull("Debe devolverse cuando el proveedor no tenga un modelo dibujable. null,Entrégaselo a la persona que llama para informar un error.", resolved)
    }

    @Test
    fun nullRequestedModelPicksFirstImageCapableModel() {
        val resolved = resolveImageCapableModel(provider(textModel, imageModel), null)

        assertEquals("gpt-image-1", resolved?.id)
    }

    @Test
    fun noProviderResolvesNothing() {
        assertNull("Los modelos no deben darse de la nada cuando faltan proveedores", resolveImageCapableModel(null, textModel))
    }
}
