package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageService
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.data.cover.CoverItem
import io.zer0.muse.data.cover.CoverLibraryRepository
import io.zer0.muse.transformer.PromptTemplateLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * v1.0.53: AI Servicio de generación de cobertura(Enlace de dibujo para flujo de trabajo de portada).
 *
 * proceso:
 *  1. Usar plantilla de generación de portada + título del documento/Resumen de representación de instrucciones de generación de mensajes de LLM
 *  2. ChatService Generar dibujo de portada prompt(ingles,50 dentro de la palabra)
 *  3. ImageService.generate imagen cruda(16:9,1536x640 o proveedor de tamaño más cercano)
 *  4. OkHttp Descargar la URL del resultado en un archivo temporal
 *  5. CoverLibraryRepository.registerGenerated Almacén
 *
 * Volver si algún paso falla Error(Mensaje de brindis de la persona que llama),No queda ningún trabajo en progreso.
 */
class CoverGenerator(
    private val context: Context,
    private val templateLoader: PromptTemplateLoader,
    private val chatService: io.zer0.ai.ChatService,
    private val imageService: ImageService?,
    private val coverLibraryRepository: CoverLibraryRepository,
    private val okHttpClient: OkHttpClient,
    /** v2.x: Enrutamiento de modelo auxiliar(El mensaje de portada se genera en un archivo \"widget\";null Mantenga el comportamiento predeterminado cuando,Construcciones fáciles de probar). */
    private val settingsRepository: io.zer0.muse.data.SettingsRepository? = null,
) {

    companion object {
        private const val TAG = "CoverGenerator"
        private const val COVER_TEMPLATE_NAME = "cover_generation"
        private const val COVER_SIZE = "1536x640"
        private const val MAX_PROMPT_TOKENS = 300
        private const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

        /** R-UI-06/R-TEST-18: Salida LLM vacía degradada a instrucciones de dibujo fijas,Facilita las pruebas unitarias. */
        internal fun resolveCoverPrompt(raw: String, title: String): String =
            raw.trim().ifBlank { "Minimal modern banner cover for: $title, no text, 16:9 aspect ratio" }
    }

    /**
     * Genera una portada y guárdala en la biblioteca.
     *
     * @param title Título del documento(Variables de plantilla)
     * @param description Resumen del documento(Variables de plantilla,Disponible)
     * @param locale código de idioma(Reserva de configuración regional de la plantilla)
     * @return después del almacenamiento [CoverItem];Retorno del fracaso Error
     */
    suspend fun generateCover(title: String, description: String? = null, locale: String? = null): Result<CoverItem> =
        withContext(Dispatchers.IO) {
            resultOf {
                // 1. plantilla de renderizado → LLM Generar cobertura prompt
                val directive = templateLoader.render(
                    name = COVER_TEMPLATE_NAME,
                    locale = locale,
                    context = mapOf(
                        "title" to title,
                        "description" to (description ?: ""),
                    ),
                    fallback = "Generate a minimal modern banner cover image for a document titled: $title",
                )

                // v2.x: El mensaje de portada genera un archivo "gadget" que prioriza el enrutamiento del modelo auxiliar(Déjelo en blanco para regresar al modelo de diálogo principal.)
                val routed = settingsRepository?.let { repo ->
                    runCatching {
                        io.zer0.muse.data.routing.UtilityModelRouter(repo)
                            .resolve(io.zer0.muse.data.routing.UtilityTier.SMALL)
                    }.getOrNull()
                }
                val promptCompletion = chatService.completeText(
                    messages = listOf(
                        UIMessage(role = MessageRole.SYSTEM, content = directive),
                        UIMessage(role = MessageRole.USER, content = "Genere un mensaje de dibujo en inglés para la imagen de portada."),
                    ),
                    model = routed?.second,
                    providerConfig = routed?.first,
                    temperature = 0.7f,
                    maxTokens = MAX_PROMPT_TOKENS,
                    tools = null,
                    reasoningLevel = ReasoningLevel.OFF,
                    mode = ChatRequestMode.UTILITY,
                )
                val rawCoverPrompt = promptCompletion.text.trim()
                // R-UI-06: Salida LLM vacía degradada a instrucciones de dibujo fijas,Ya no arroja IllegalStateException.
                val coverPrompt = resolveCoverPrompt(rawCoverPrompt, title)

                // 2. imagen cruda
                val service = imageService
                    ?: error("Modelo de dibujo no configurado")
                val urls = service.generate(
                    prompt = coverPrompt,
                    params = ImageGenParams(
                        model = "",
                        size = COVER_SIZE,
                        responseFormat = "url",
                        n = 1,
                    ),
                )
                if (urls.isEmpty()) error("El gráfico resultante está vacío.")
                val url = urls.first()

                // 3. Descargar a archivo temporal
                val tmpFile = downloadToTemp(url)

                // 4. Almacén(Tamaño desconocido 0,registerGenerated Procesamiento interno según documentos reales.)
                val item = coverLibraryRepository.registerGenerated(
                    file = tmpFile,
                    width = 0,
                    height = 0,
                ).getOrThrow()

                // 5. Limpiar archivos temporales
                runCatching { tmpFile.delete() }
                item
            }.onError { msg, t ->
                Logger.w(TAG, "Falló la generación de portada: $msg", t)
            }
        }

    /** Descargar la URL de la imagen para almacenar en caché el archivo temporal(Tamaño de banda y límites de tiempo de espera). */
    private suspend fun downloadToTemp(url: String): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) error("Error al descargar la portada: HTTP ${resp.code}")
            val body = resp.body ?: error("Error al descargar la portada: cuerpo de respuesta vacío")
            val file = File.createTempFile("cover_dl_", ".img", context.cacheDir)
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            runCatching { file.delete() }
                            error("La imagen de portada excede ${MAX_DOWNLOAD_BYTES / 1024 / 1024}MB Límite")
                        }
                        output.write(buf, 0, read)
                    }
                }
            }
            file
        }
    }
}
