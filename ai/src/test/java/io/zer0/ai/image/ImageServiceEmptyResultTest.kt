package io.zer0.ai.image

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * F-03 punto de anclaje de retorno: Accidente histórico "imagen real nula imagen falsa".
 *
 * accidente: La versión anterior continuó en silencio cuando el proveedor de imágenes arrojó resultados vacíos., UI La capa representa el resultado vacío en
 * Marcador de posición de "imagen falsa", El usuario ve una imagen que no existe en ningún Proveedor.
 *
 * cadena protectora(Ya existe, Esta prueba está bloqueada):
 *  1. [ImageService.generate] en `finalImages.isEmpty()` lanzamiento de tiempo
 *     [ErrorCode.IMAGE_NO_RESULTS] error de negocio(ImageService.kt:107);
 *  2. [convertToOutputStrings] Utilice mapNotNull para filtrar base64/url todo vacío
 *     [GeneratedImage], Asegúrese de que no se pierdan imágenes nulas UI(ImageService.kt:219);
 *  3. llamador [io.zer0.muse.ui.chat.ImageGenCoordinator] Mostrar resultados vacíos
 *     Aviso de falla y parada de generación., Nunca dejes una foto falsa.
 */
class ImageServiceEmptyResultTest {

    private val emptyProvider = object : ImageProvider {
        override val providerId: String = "empty-provider"
        override val supportsImageEdit: Boolean = false
        override val supportsAsync: Boolean = false
        override suspend fun submit(request: ImageGenRequest): ImageSubmitResult = ImageSubmitResult()
        override suspend fun poll(taskId: String): ImagePollResult = ImagePollResult(PollStatus.PENDING)
    }

    // Nota: ImageProviderRegistry.register Ajuste interno Logger.i(ImageProviderRegistry.kt:32),
    // Debe establecerse @Antes Logger.enabled=false Regístrate más tarde, De lo contrario, se activa el período de inicialización del campo.
    // android.util.Log not mocked.
    private val registry = ImageProviderRegistry()

    private val configStore = object : ProviderConfigStore {
        override suspend fun get(): ProviderConfig? = null
    }

    private val config = ProviderConfig(
        id = "p",
        displayName = "p",
        type = ProviderType.OPENAI,
        baseUrl = "https://example.com",
        apiKey = "k",
        // selectFor Coincidencia exacta por specId provider, puntero explícito emptyProvider
        specId = "empty-provider",
    )

    @Before
    fun disableAndroidLogging() {
        Logger.enabled = false
        registry.register(emptyProvider)
    }

    @After
    fun restoreLogging() {
        Logger.enabled = true
    }

    @Test
    fun `empty provider result throws IMAGE_NO_RESULTS instead of fake image`() {
        val service = ImageService(configStore, registry)
        runBlocking {
            try {
                service.generate(prompt = "un gato", providerConfig = config)
                fail("Se deben arrojar resultados vacíos. IMAGE_NO_RESULTS, en lugar de devolver una lista vacía/foto falsa")
            } catch (e: IllegalStateException) {
                // error(ErrorCode...) Lanzar Kotlin IllegalStateException
                assertTrue(
                    "El mensaje de error debe identificar IMAGE_NO_RESULTS, real: ${e.message}",
                    e.message?.contains(ErrorCode.IMAGE_NO_RESULTS.toMessage()) == true,
                )
            }
        }
    }

    @Test
    fun `convertToOutputStrings drops null images and keeps valid urls`() {
        val images = listOf(
            GeneratedImage(base64 = null, url = null),
            GeneratedImage(base64 = "YmFzZTY0"),
            GeneratedImage(base64 = null, url = "https://example.com/a.png"),
        )
        val out = ImageService(configStore, registry).convertToOutputStrings(images)
        // mapNotNull: null La imagen esta filtrada, URL válida/base64 reservada(El orden es estable.)
        assertEquals(2, out.size)
        assertEquals("data:image/png;base64,YmFzZTY0", out[0])
        assertEquals("https://example.com/a.png", out[1])
    }

    @Test
    fun `non empty provider result maps to output strings`() {
        val oneImageProvider = object : ImageProvider {
            override val providerId: String = "one-image-provider"
            override val supportsImageEdit: Boolean = false
            override val supportsAsync: Boolean = false
            override suspend fun submit(request: ImageGenRequest): ImageSubmitResult =
                ImageSubmitResult(images = listOf(GeneratedImage(url = "https://example.com/a.png")))
            override suspend fun poll(taskId: String): ImagePollResult = ImagePollResult(PollStatus.PENDING)
        }
        val reg = ImageProviderRegistry().apply { register(oneImageProvider) }
        val service = ImageService(configStore, reg)
        val oneConfig = config.copy(specId = "one-image-provider")
        val urls = runBlocking { service.generate(prompt = "un gato", providerConfig = oneConfig) }
        assertEquals(listOf("https://example.com/a.png"), urls)
    }
}
