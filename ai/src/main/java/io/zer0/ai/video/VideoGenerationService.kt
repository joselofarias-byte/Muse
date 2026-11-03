package io.zer0.ai.video

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderKeyRotation
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.137: Servicio unificado de generación de video.(Refactorizar).
 *
 * diseño:
 *  - Pase [VideoProviderRegistry] prensa specId / host / type elegir [VideoProvider]
 *    (Se corrigió la coincidencia difícil del ID del proveedor para v1.136 bug:preset_kling ≠ kling causando falla de enrutamiento)
 *  - Mecanismo de sondeo asincrónico unificado:
 *    - 5 Uno por segundo tick
 *    - Adaptar la frecuencia según la edad de la tarea.:
 *      <2min cada 5s, 2-10min cada 15s, >10min cada 30s
 *    - continuo [MAX_CONSECUTIVE_ERRORS] Error de marca después de errores de consulta
 *    - Unfence([cancelledTasks])Evite que las consultas durante el vuelo se escriban accidentalmente después de que el usuario cancele
 *  - Devolución de llamada de progreso([onProgress])Actualizar cada 5 segundos"Generado Xs"
 *
 * Uso:
 * ```
 * val service = VideoGenerationService(registry)
 * val videoUrl = service.generateVideo(
 *     providerConfig = config,
 *     request = VideoGenRequest(prompt = "...", model = "..."),
 *     onProgress = { elapsed -> updateUI("Generado ${elapsed}s") },
 * ).getOrThrow()
 * ```
 *
 * @param registry Video Provider Registro
 */
class VideoGenerationService(
    private val registry: VideoProviderRegistry,
    /** T1.2: Fábrica de rotación de llaves inyectables.(Prueba de fábrica determinista disponible,La producción va LRU pick),El valor predeterminado es de construcción propia. */
    private val keyRotationFactory: (ProviderConfig) -> ProviderKeyRotation = { ProviderKeyRotation(it) },
) {
    /**
     * Desarmar por taskId.
     *
     * El usuario cancela([cancel])Únase a esta colección más tarde;in-flight Revise la valla después del regreso de las elecciones,
     * Si se cancela, descarta el resultado.,Evite actualizaciones de estado incorrectas después de consultas canceladas.
     */
    private val cancelledTasks: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

    /**
     * Generar vídeo — Enviar tarea → esperar a que se complete → Devuelve la URL del vídeo.
     *
     * @param providerConfig Configuración del proveedor(Para enrutamiento de registro + inyección apiKey/baseUrl)
     * @param request Generar solicitud(prompt / model / Imagen de referencia/duración/resolución, etc.)
     * @param onProgress Devolución de llamada de progreso,El parámetro es el número de segundos transcurridos.;Se activa cada 5 segundos;puede ser null
     * @param timeoutMs tiempo de espera total(milisegundos),Predeterminado 10 minutos(La generación de vídeo tarda entre 5 y 10 minutos como máximo)
     * @return Result.success(videoUrl) o Result.failure(exception)
     */
    suspend fun generateVideo(
        providerConfig: ProviderConfig,
        request: VideoGenRequest,
        onProgress: ((elapsedSec: Long) -> Unit)? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<String> = withContext(Dispatchers.IO) {
        val r = resultOf {
            val provider = registry.selectFor(providerConfig)
            Logger.i(
                TAG,
                "generateVideo: provider=${provider.providerId} " +
                    "specId=${providerConfig.specId} host=${VideoProviderRegistry.extractHost(providerConfig.resolvedBaseUrl())}",
            )

            // Inyectar credenciales
            // T1.2: Rotación de múltiples claves — effectiveApiKey() Presione LRU para seleccionar actualmente disponible key;
            // Escenario clave única(hasMultipleKeys=false)Equivale a regresar directamente config.apiKey,Sin gastos generales adicionales
            val effectiveKey = keyRotationFactory(providerConfig).effectiveApiKey()
            val requestWithCreds = request.copy(
                apiKey = request.apiKey.ifBlank { effectiveKey },
                baseUrl = request.baseUrl ?: providerConfig.resolvedBaseUrl(),
                videoGenerationsPath = request.videoGenerationsPath
                    ?: (providerConfig.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.OpenAI)?.videoGenerationsPath,
            )

            // 1. Enviar tarea
            val submitResult = provider.submit(requestWithCreds)
            val taskId = submitResult.taskId

            // Retorno sincrónico:Tómalo directamente videoUrl(No es necesario taskId)
            if (!submitResult.isAsync) {
                val url = submitResult.videoUrl
                    ?: error("Se devolvió la sincronización de la tarea de vídeo, pero no se proporcionó videoUrl")
                onProgress?.invoke(0)
                return@resultOf url
            }

            // Las tareas asincrónicas deben llevar taskId
            if (taskId.isNullOrBlank()) {
                error("Error al enviar la tarea de vídeo:Ninguno taskId")
            }

            // 2. sondeo asincrónico
            pollUntilCompletion(
                provider = provider,
                taskId = taskId,
                onProgress = onProgress,
                timeoutMs = timeoutMs,
            )
        }
        when (r) {
            is io.zer0.common.Result.Success -> Result.success(r.data)
            is io.zer0.common.Result.Error -> Result.failure(r.throwable ?: IllegalStateException(r.message))
        }
    }

    /**
     * Enviar tareas solamente(No esperes a que se complete),Devolver los resultados del envío.
     *
     * Adecuado para escenarios donde se requiere un sondeo manual en la capa de UI(Por ejemplo, VideoGenerationPage se puede dividir y enviar más tarde./sondeo).
     */
    suspend fun submit(providerConfig: ProviderConfig, request: VideoGenRequest): Result<VideoSubmitResult> = withContext(Dispatchers.IO) {
        val r = resultOf {
            val provider = registry.selectFor(providerConfig)
            val effectiveKey = keyRotationFactory(providerConfig).effectiveApiKey()
            val requestWithCreds = request.copy(
                apiKey = request.apiKey.ifBlank { effectiveKey },
                baseUrl = request.baseUrl ?: providerConfig.resolvedBaseUrl(),
                videoGenerationsPath = request.videoGenerationsPath
                    ?: (providerConfig.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.OpenAI)?.videoGenerationsPath,
            )
            provider.submit(requestWithCreds)
        }
        when (r) {
            is io.zer0.common.Result.Success -> Result.success(r.data)
            is io.zer0.common.Result.Error -> Result.failure(r.throwable ?: IllegalStateException(r.message))
        }
    }

    /**
     * Cancela la tarea con el taskId especificado.
     *
     * Únete a Unfence,Los resultados posteriores de las encuestas durante el vuelo se descartarán.
     * Nota:Este método solo marca cancelación local,No notificará al extremo remoto API(La semántica de cancelación de cada Proveedor se implementa por sí mismo.).
     */
    fun cancel(taskId: String) {
        cancelledTasks[taskId] = true
        Logger.i(TAG, "Tarea cancelada: taskId=$taskId")
    }

    /**
     * Encuesta hasta que la tarea se complete/falle/se agote el tiempo de espera/se cancele.
     *
     * Frecuencia adaptativa:
     *  - 5s uno tick
     *  - ageMs < 2min:     Revisa cada tic(5s)
     *  - 2min <= ageMs < 10min:  Verifique cada 3 tics(15s)
     *  - ageMs >= 10min:   Verifique cada 6 tics(30s)
     *
     * Manejo de errores:
     *  - Una sola encuesta arroja una excepción o devuelve PENDING+errorMessage → Incluido en el recuento de errores consecutivos
     *  - continuo [MAX_CONSECUTIVE_ERRORS] Falló después de 1 error(La fluctuación instantánea de la red puede autocurarse)
     *  - Cualquier consulta exitosa(volver claro SUCCESS/FAILED)Restablecer recuento de errores
     *
     * Unfence:
     *  - Verifique después de que regrese cada encuesta [cancelledTasks]
     *  - Si se cancela,Descarta el resultado y tira [CancellationException]
     */
    private suspend fun pollUntilCompletion(
        provider: VideoProvider,
        taskId: String,
        onProgress: ((elapsedSec: Long) -> Unit)?,
        timeoutMs: Long,
    ): String {
        val startedAt = System.currentTimeMillis()
        var tickCount = 0
        var consecutiveErrors = 0
        var lastProgressSec = -1L

        val result = withTimeoutOrNull(timeoutMs) {
            while (true) {
                // La cancelación de rutina pasará delay() / provider.poll() propagar,No es necesario verificar explícitamente isActive

                val ageMs = System.currentTimeMillis() - startedAt
                tickCount++

                // Devolución de llamada de progreso:Se activa cada 5 segundos(cada uno tick)
                val elapsedSec = ageMs / 1000
                if (onProgress != null && elapsedSec != lastProgressSec) {
                    lastProgressSec = elapsedSec
                    onProgress.invoke(elapsedSec)
                }

                // Juicio de frecuencia adaptativo
                if (shouldCheckThisTick(ageMs, tickCount)) {
                    // poll Las excepciones se tratan como errores transitorios.;FAILED es el estado final,No ingrese el recuento de reintentos instantáneos
                    val pollResult = try {
                        provider.poll(taskId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutiveErrors++
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            error(
                                "${provider.providerId} continuidad de la tarea $consecutiveErrors Excepciones de consulta: ${e.message}",
                            )
                        }
                        Logger.w(
                            TAG,
                            "excepción de consulta ($consecutiveErrors/${MAX_CONSECUTIVE_ERRORS})" +
                                ",Lo intentaré de nuevo: ${e.message}",
                        )
                        null
                    }

                    if (pollResult != null) {
                        // Unfence:poll Comprueba si ha sido cancelado después de regresar
                        if (cancelledTasks.remove(taskId) != null) {
                            throw CancellationException("La tarea ha sido cancelada por el usuario.: taskId=$taskId")
                        }

                        when (pollResult.status) {
                            PollStatus.SUCCESS -> {
                                consecutiveErrors = 0
                                val url = pollResult.videoUrl
                                    ?: error("${provider.providerId} La tarea fue exitosa pero no regresó. videoUrl")
                                return@withTimeoutOrNull url
                            }
                            PollStatus.FAILED -> {
                                consecutiveErrors = 0
                                error(pollResult.errorMessage ?: "${provider.providerId} Tarea fallida")
                            }
                            PollStatus.PENDING -> {
                                // PENDING + errorMessage Indica un error en la propia consulta.(red/HTTP error)
                                if (pollResult.errorMessage != null) {
                                    consecutiveErrors++
                                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                        error(
                                            "${provider.providerId} continuidad de la tarea $consecutiveErrors consultas fallidas," +
                                                "error final: ${pollResult.errorMessage}",
                                        )
                                    }
                                    Logger.w(
                                        TAG,
                                        "Consulta fallida ($consecutiveErrors/${MAX_CONSECUTIVE_ERRORS})" +
                                            ",Lo intentaré de nuevo: ${pollResult.errorMessage}",
                                    )
                                } else {
                                    // puro PENDING(Bajo procesamiento normal),Restablecer recuento de errores
                                    consecutiveErrors = 0
                                }
                            }
                        }
                    }
                }

                delay(TICK_MS)
            }
            null
        } ?: error("${provider.providerId} Tiempo de espera de la tarea(taskId=$taskId, timeoutMs=$timeoutMs)")

        // valla clara(La tarea se ha completado normalmente.)
        cancelledTasks.remove(taskId)
        return result
    }

    companion object {
        private const val TAG = "VideoGenerationService"

        /** Intervalo de tic de sondeo(5 segundos). */
        private const val TICK_MS = 5_000L

        /** 2 umbral de minutos(milisegundos). */
        private const val TWO_MINUTES_MS = 2L * 60 * 1000

        /** 10 umbral de minutos(milisegundos). */
        private const val TEN_MINUTES_MS = 10L * 60 * 1000

        /** Límite superior de error de consulta continua(Fracaso después de alcanzar). */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** Tiempo de espera total predeterminado(10 minutos). */
        private const val DEFAULT_TIMEOUT_MS = 10L * 60 * 1000

        /**
         * Determinar si el tick actual debe ejecutar la consulta(frecuencia adaptativa).
         *
         * - ageMs < 2min:   Revisa cada tic(5s intervalo)
         * - 2-10min:        Verifique cada 3 tics(15s intervalo)
         * - >10min:         Verifique cada 6 tics(30s intervalo)
         */
        internal fun shouldCheckThisTick(ageMs: Long, tickCount: Int): Boolean {
            return when {
                ageMs < TWO_MINUTES_MS -> true
                ageMs < TEN_MINUTES_MS -> tickCount % 3 == 0
                else -> tickCount % 6 == 0
            }
        }
    }
}

/**
 * Estado de la tarea de vídeo(UI Para visualización de capas).
 *
 * v1.137: reservado para la capa UI(VideoGenerationPage)uso;Uso de lógica interna [PollStatus].
 * [PROCESSING] Todavía reservado para la diferenciación de la interfaz de usuario"El procesamiento ha comenzado"con"hacer cola"mostrar,
 * pero [PollStatus] ya no distingue entre los dos(todos pertenecen a PENDING).
 */
enum class VideoTaskStatus {
    /** hacer cola(La tarea ha sido enviada,El procesamiento aún no ha comenzado). */
    PENDING,

    /** Procesamiento(El modelo está generando video.). */
    PROCESSING,

    /** éxito(El vídeo ha sido generado.,Vídeos descargables URL). */
    SUCCESS,

    /** falló(Error o rechazo de tarea). */
    FAILED,
}
