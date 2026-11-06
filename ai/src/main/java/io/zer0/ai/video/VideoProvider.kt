package io.zer0.ai.video

/**
 * v1.137: Interfaz unificada del proveedor de generación de video.
 *
 * Objetivos de refactorización:
 *  - Desacoplamiento de la lógica de enrutamiento de ID de proveedor(entregar [VideoProviderRegistry] prensa specId / host / type elegir)
 *  - Modelo de tarea asincrónica unificada:submit Regresar taskId(o sincronizar resultados),poll Estado de la consulta
 *  - Soporte asincrónico API(DashScope / MiniMax / Agnes)sincronizar con API(Kling Retorno sincrónico)dos formas
 *
 * Cada proveedor implementa:
 *  - [submit]: Enviar tarea de compilación,Regresar [VideoSubmitResult](TaskId asincrónico o sincrónico videoUrl)
 *  - [poll]: Consultar el estado de la tarea,Regresar [VideoPollResult]
 *
 * Provider No es responsable de la programación de las encuestas. — por [VideoGenerationService] El sondeo adaptativo se realiza de manera uniforme.
 */
interface VideoProvider {
    /** Provider identificador único(Como por ejemplo "kling" / "agnes" / "openai_video_generic"). */
    val providerId: String

    /** Ya sea para admitir el video de Tusheng(Imagen única). */
    val supportsImageToVideo: Boolean

    /** Ya sea para admitir videos de múltiples imágenes(2+ Figura). */
    val supportsMultiFrameToVideo: Boolean

    /**
     * Envíe una tarea de generación de video.
     *
     * @param request Generar solicitud(Contiene aviso/modelo/dibujo de referencia/vale, etc.)
     * @return [VideoSubmitResult]:
     *   - isAsync=true Cuando el ID de tarea no está vacío,seguimiento [poll] sondeo
     *   - isAsync=false cuando videoUrl no está vacío(Devolver resultados sincrónicamente,No es necesario realizar una encuesta)
     */
    suspend fun submit(request: VideoGenRequest): VideoSubmitResult

    /**
     * Consultar el estado de las tareas asincrónicas.
     *
     * solo si [submit] Regresar isAsync=true cuando lo llaman.
     *
     * @param taskId por [submit] Tareas devueltas ID
     * @return [VideoPollResult],status=SUCCESS cuando videoUrl no está vacío
     */
    suspend fun poll(taskId: String): VideoPollResult
}

/**
 * Parámetros de solicitud de generación de video(Provider irrelevante).
 *
 * @param prompt Descripción del vídeo/palabra rápida
 * @param model modelo ID(Como por ejemplo kling-v1 / agnes-video-v2.0)
 * @param width ancho del vídeo(Agnes Predeterminado 1152)
 * @param height altura del vídeo(Agnes Predeterminado 768)
 * @param frameRate Velocidad de fotogramas(Agnes Predeterminado 24,alcance 1-60)
 * @param numFrames Marcos(Agnes debe ser 8n+1,alcance [81, 441];Predeterminado 121 ≈ 5s@24fps)
 * @param referenceImages Lista de figuras de referencia(data URI o URL):
 *   - vacío → Vídeo de Vicente
 *   - 1 zhang → vídeo de tusheng
 *   - 2+ zhang → Vídeo de DuoTuSheng(Sólo apoyado por Agnes, etc.)
 * @param apiKey Provider API Key(por [VideoGenerationService] Inyectar desde ProviderConfig)
 * @param baseUrl Provider Conceptos básicos URL(Si se deja en blanco, el Proveedor tomará el valor predeterminado.)
 * @param videoGenerationsPath Ruta del punto final de generación de vídeo(Solo utilizado por proveedores generales compatibles con OpenAI)
 * @param duration Duración del vídeo(segundos,Kling Esperando uso;Agnes Derivación a través de numFrames)
 * @param resolution cadena de resolución(Como por ejemplo "720p",Kling Esperando uso)
 */
data class VideoGenRequest(
    val prompt: String,
    val model: String,
    val width: Int = 1152,
    val height: Int = 768,
    val frameRate: Int = 24,
    val numFrames: Int = 121,
    val referenceImages: List<String> = emptyList(),
    val apiKey: String = "",
    val baseUrl: String? = null,
    val videoGenerationsPath: String? = null,
    val duration: Int = 5,
    val resolution: String = "720p",
)

/**
 * Resultados del envío de tareas.
 *
 * @param taskId Tareas asincrónicas ID(isAsync=true el tiempo no está vacío)
 * @param videoUrl Sincronizar vídeo devuelto URL(isAsync=false el tiempo no está vacío)
 * @param isAsync ¿Es una tarea asincrónica?:
 *   - true → La persona que llama necesita sondear [VideoProvider.poll] hasta SUCCESS/FAILED
 *   - false → Devuelto sincrónicamente videoUrl,No es necesario realizar una encuesta
 * @param modelName El nombre del modelo que se utilizará al enviar(Se utiliza en escenarios donde se requiere model_name al consultar a Agnes, etc.)
 */
data class VideoSubmitResult(
    val taskId: String? = null,
    val videoUrl: String? = null,
    val isAsync: Boolean = true,
    val modelName: String? = null,
)

/**
 * Estado de sondeo de tareas.
 */
enum class PollStatus {
    /** En cola/procesando(Continuar encuestando). */
    PENDING,

    /** éxito(El vídeo ha sido generado.,videoUrl No vacío). */
    SUCCESS,

    /** falló(Error o cancelación de tarea,errorMessage No vacío). */
    FAILED,
}

/**
 * Resultados de la encuesta de tareas.
 *
 * @param status Estado de la tarea
 * @param videoUrl Descarga de vídeo URL(status=SUCCESS el tiempo no está vacío)
 * @param errorMessage mensaje de error(status=FAILED el tiempo no está vacío)
 */
data class VideoPollResult(
    val status: PollStatus,
    val videoUrl: String? = null,
    val errorMessage: String? = null,
)
