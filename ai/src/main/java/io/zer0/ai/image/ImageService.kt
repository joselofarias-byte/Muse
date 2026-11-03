package io.zer0.ai.image

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5-G / v1.0.18: Servicio de generación de imágenes.
 *
 * v1.0.18 Refactorizar:Eliminar la verificación de tipo OPENAI / OPENAI_RESPONSES,pasar en su lugar
 * [ImageProviderRegistry] prensa [ProviderConfig] Elige el correcto [ImageProvider],
 * Deje que Agnes / Gemini / estaciones de transferencia domésticas, etc., utilicen sus propios adaptadores.
 *
 * diseño:
 *  - La persona que llama todavía usa [generate] conseguir `List<String>`(URL o data URI),Manténgase compatible con versiones anteriores;
 *  - manija interna [ImageGenParams] convertir a [ImageGenRequest],confiar [ImageProvider] Completar llamada HTTP;
 *  - Sincronizar tareas(provider.submit Regresar directamente images)Regresar inmediatamente;
 *  - Tareas asincrónicas(provider.submit Regresar taskId)Empezar a sondear,existente poller:
 *      5s tick, frecuencia adaptativa según edad de la tarea(<2min Cada tic / 2-10 min Cada 3 tic / 10 min+ Cada 6 tick),
 *      El fracaso se determina después de 5 errores consecutivos.
 *  - Los modelos predeterminados ya no están codificados dall-e-3,Cambiar de [ProviderConfig.models] Filtrar
 *    outputModalities Contiene "image" modelo;Si no se encuentra, se utiliza el valor predeterminado del propio proveedor.
 */
class ImageService(
    private val configStore: ProviderConfigStore,
    private val registry: ImageProviderRegistry,
) {

    /**
     * Genera imágenes. Lista de URL de imagen/URI de datos de retorno(Generalmente 1 hoja,n=1).
     *
     * si [params.referenceImageUri] No vacío,luego active la ruta del gráfico;
     * De lo contrario, siga el diagrama vicenciano. El punto final específico y el método de codificación se seleccionan mediante [ImageProvider] decisión.
     *
     * @param prompt Descripción de la imagen/instrucciones de modificación
     * @param params Parámetros de dibujo(Modelo/tamaño/calidad/estilo/cantidad/dibujos de referencia, etc.)
     * @param providerConfig Específicamente especificado Provider;null Se utiliza cuando configStore está actualmente activo Provider
     */
    suspend fun generate(prompt: String, params: ImageGenParams = ImageGenParams(), providerConfig: ProviderConfig? = null): List<String> =
        withContext(Dispatchers.IO) {
            val config = providerConfig ?: configStore.get()
                ?: error(ErrorCode.NO_PROVIDER_CONFIGURED.toMessage())
            if (config.apiKey.isBlank() && !config.allowMissingApiKey) {
                error(ErrorCode.IMAGE_API_KEY_MISSING.toMessage())
            }

            // v1.0.18: Seleccionar a través del Registro provider,El tipo OPENAI ya no es necesario
            val provider = registry.selectFor(config)
                ?: error(ErrorCode.IMAGE_UNSUPPORTED_MODEL.toMessage("no_provider_for_${config.id}"))

            // Prioridad de selección de modelo:
            //  1. params.model Pasado explícitamente;
            //  2. ProviderSpecificConfig.OpenAI.imageModel(OpenAI compatibles provider);
            //  3. ProviderConfig.models La primera salidaModalidades contiene "image" modelo;
            //  4. provider valor predeterminado propio(AgnesImageProvider.DEFAULT_MODEL_ID / ImageModelCatalog.DEFAULT_MODEL_ID)
            val effectiveModel = resolveModelId(params, config, provider)

            Logger.i(
                TAG,
                "generate: provider=${provider.providerId} model=$effectiveModel " +
                    "size=${params.size} n=${params.n} ref=${!params.referenceImageUri.isNullOrBlank()}",
            )

            val referenceImages = params.referenceImageUri?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                ?: emptyList()
            val request = ImageGenRequest(
                prompt = prompt,
                model = effectiveModel,
                size = params.size,
                quality = params.quality,
                style = params.style,
                n = params.n,
                referenceImages = referenceImages,
                responseFormat = params.responseFormat,
                config = config,
            )

            val result = try {
                provider.submit(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // error() Los errores comerciales arrojados se propagan sin cambios
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "image submit failed: ${e.message}")
                error(ErrorCode.IMAGE_GEN_FAILED.toMessage(e.message ?: ""))
            }

            // Sincronizar tareas:conversión directa images
            val finalImages = if (!result.isAsync || result.taskId == null) {
                result.images
            } else {
                // Tareas asincrónicas:Encuesta hasta estado final
                pollUntilDone(provider, result.taskId)
            }
            if (finalImages.isEmpty()) error(ErrorCode.IMAGE_NO_RESULTS.toMessage())
            convertToOutputStrings(finalImages)
        }

    /**
     * Analizar el modelo final ID(No más codificación dall-e-3).
     */
    @Suppress("ReturnCount")
    internal fun resolveModelId(params: ImageGenParams, config: ProviderConfig, provider: ImageProvider): String {
        // 1. Pasado explícitamente
        params.model.takeIf { it.isNotBlank() }?.let { return it }

        // 2. ProviderSpecificConfig.OpenAI.imageModel
        val specific = config.resolvedSpecific()
        if (specific is ProviderSpecificConfig.OpenAI) {
            specific.imageModel.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 3. ProviderConfig.models El primer modelo en China que admite la salida de imágenes.
        config.models.firstOrNull { it.supportsImageOutput() }?.let { return it.id }

        // 4. provider valor predeterminado propio
        return when (provider.providerId) {
            AgnesImageProvider.PROVIDER_ID -> AgnesImageProvider.DEFAULT_MODEL_ID
            GeminiImageProvider.PROVIDER_ID -> GeminiImageProvider.DEFAULT_MODEL_ID
            else -> ImageModelCatalog.DEFAULT_MODEL_ID
        }
    }

    /**
     * Sondeo de tareas asincrónicas,existente poller:
     *  - 5s tick;
     *  - Adaptar la frecuencia según la edad de la tarea.(<2min Cada tic / 2-10 min Cada 3 tic / 10 min+ Cada 6 tick);
     *  - El fracaso se determina después de 5 errores consecutivos.;
     *  - tiempo de espera total [POLL_TIMEOUT_MS](10 minutos).
     */
    private suspend fun pollUntilDone(provider: ImageProvider, taskId: String): List<GeneratedImage> {
        val createdAt = System.currentTimeMillis()
        var tick = 0
        var consecutiveErrors = 0
        // Registre el número de fallas de tareas (seguridad de concurrencia)
        errorCounts.remove(taskId)
        while (true) {
            val ageMs = System.currentTimeMillis() - createdAt
            if (ageMs > POLL_TIMEOUT_MS) {
                error(ErrorCode.IMAGE_GEN_FAILED.toMessage("poll timeout taskId=$taskId"))
            }
            tick++
            if (!shouldCheckThisTick(ageMs, tick)) {
                delay(TICK_MS)
                continue
            }
            try {
                val pollResult = provider.poll(taskId)
                consecutiveErrors = 0
                errorCounts.remove(taskId)
                when (pollResult.status) {
                    PollStatus.SUCCESS -> return pollResult.images
                    PollStatus.FAILED -> {
                        val msg = pollResult.errorMessage ?: "image generation failed"
                        error(ErrorCode.IMAGE_GEN_FAILED.toMessage(msg))
                    }
                    PollStatus.PENDING -> {
                        // Continuar encuestando
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // provider usar error() Errores comerciales arrojados,difundir como está
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                errorCounts[taskId] = consecutiveErrors
                Logger.w(
                    TAG,
                    "poll $taskId failed ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS): ${e.message}",
                )
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    errorCounts.remove(taskId)
                    error(ErrorCode.IMAGE_GEN_FAILED.toMessage("poll failed ${consecutiveErrors}x: ${e.message}"))
                }
            }
            delay(TICK_MS)
        }
    }

    /**
     * Determine si el tick actual desencadena una consulta real.
     */
    internal fun shouldCheckThisTick(ageMs: Long, tickCount: Int): Boolean {
        return when {
            ageMs < TWO_MINUTES_MS -> true // < 2 min: cada tick
            ageMs < TEN_MINUTES_MS -> tickCount % 3 == 0 // 2-10 min: cada 3 tick
            else -> tickCount % 6 == 0 // 10 min+: cada 6 tick
        }
    }

    /**
     * poner [GeneratedImage] La lista se convierte en `List<String>`(Compatibilidad con versiones anteriores de personas mayores).
     *
     *  - base64 Prioridad → luchar data URI(Si el proveedor tiene el prefijo base64 mime,Como por ejemplo "image/png|xxx",luego despegar);
     *  - De lo contrario regresar url;
     *  - Si ambos están vacíos, omita.
     */
    internal fun convertToOutputStrings(images: List<GeneratedImage>): List<String> {
        return images.mapNotNull { img ->
            when {
                !img.base64.isNullOrBlank() -> {
                    // OpenAIImageProvider se agregará antes de base64 "mime|" prefijo llevando mime;Desnúdate aquí
                    val (mime, raw) = if (img.base64.contains("|")) {
                        img.base64.substringBefore("|") to img.base64.substringAfter("|")
                    } else {
                        "image/png" to img.base64
                    }
                    "data:$mime;base64,$raw"
                }
                !img.url.isNullOrBlank() -> {
                    // C-02: url escriba el prefijo del protocolo de verificación de resultados,Prevenir respuestas anormales(cuerda desnuda/ruta relativa)Fluir hacia la capa de renderizado
                    val url = img.url
                    if (url.startsWith("http") || url.startsWith("data:image/")) url else null
                }
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "ImageService"

        /** Intervalo de tic de sondeo (5 segundos). */
        private const val TICK_MS = 5_000L

        /** 2 minutos. */
        private const val TWO_MINUTES_MS = 2L * 60 * 1000

        /** 10 minutos. */
        private const val TEN_MINUTES_MS = 10L * 60 * 1000

        /** Tiempo de espera total de sondeo(10 minutos,Alineado con TEN_MINUTES_MS). */
        private const val POLL_TIMEOUT_MS = 10L * 60 * 1000

        /** Límite de error continuo. */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** taskId → Recuento continuo de errores (seguridad de concurrencia). */
        private val errorCounts = ConcurrentHashMap<String, Int>()
    }
}

/**
 * v0.34: Parámetros de solicitud de generación de imágenes(La persona que llama usa).
 *
 * v1.0.18: Hecho internamente de [ImageService] convertir a [ImageGenRequest] confiar más tarde [ImageProvider].
 * Esta clase de datos se conserva por motivos de compatibilidad con versiones anteriores. [io.zer0.muse.ui.ChatViewModel] /
 * [io.zer0.muse.ui.chat.ImageGenCoordinator] / [io.zer0.muse.tools.SkillExecutor] Espere a la persona que llama.
 */
data class ImageGenParams(
    /** modelo de dibujo ID,Como por ejemplo dall-e-3 / gpt-image-1 / agnes-image-2.1-flash. */
    val model: String = "",
    /** Tamaño de imagen,Como por ejemplo 1024x1024 / 1792x1024 / 1:1(proporción,Agnes apoyo). */
    val size: String = "1024x1024",
    /** Calidad de imagen:standard / hd. */
    val quality: String = "standard",
    /** Estilo de imagen:vivid / natural. */
    val style: String = "vivid",
    /** Formato de devolución:url / b64_json. */
    val responseFormat: String = "url",
    /** Generar cantidad. */
    val n: Int = 1,
    /**
     * Imagen de referencia URI(http/https/data URI/locales file).
     * Activa la ruta del gráfico cuando no está vacía.
     */
    val referenceImageUri: String? = null,
)
