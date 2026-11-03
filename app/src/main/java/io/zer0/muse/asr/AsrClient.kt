package io.zer0.muse.asr

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Phase 9.3 (M2): ASR Resultados del reconocimiento.
 *
 * @param text texto reconocido
 * @param isFinal ¿Es el resultado final?(Resultados intermedios de streaming ASR isFinal=false)
 * @param durationMs Duración del audio(milisegundos,Algunos proveedores regresan)
 */
data class AsrResult(
    val text: String,
    val isFinal: Boolean = true,
    val durationMs: Long = 0,
    /** B5-06: Razón del fracaso(No nulo significa que este reconocimiento falló.,text esta vacio). */
    val errorMessage: String? = null,
)

/**
 * Phase 9.3 (M2): ASR Interfaz de cliente.
 *
 * diferentes abstracciones ASR Provider(DashScope Paraformer / Step-Audio espera),
 * La entrada son bytes de audio PCM.,La salida es texto reconocido.
 *
 * Puntos de implementación:
 *  - DashScope: Transmita fragmentos de audio con OkHttp WebSocket,Recibir evento generado por resultados
 *  - Step: Compatible con OpenAI API,base64 audio como input(esqueleto temporal,Step-Audio API Estándares a mejorar)
 *  - SYSTEM: no implementa esta interfaz,Usar directamente Intent(ver SpeechInput)
 *
 * Uso:
 * ```
 * val client = AsrClientFactory.create(config)
 * val result = client.recognize(pcmAudioBytes, sampleRate = 16000)
 * println(result.text)
 * ```
 */
interface AsrClient {
    /**
     * Identificar audios.
     *
     * @param audioData PCM bytes de audio(16-bit little-endian,mono)
     * @param sampleRate Tasa de muestreo(DashScope Paraformer v2 Apoyar cualquier,Predeterminado 16000)
     * @return Resultados del reconocimiento,Retorno del fracaso null
     */
    suspend fun recognize(audioData: ByteArray, sampleRate: Int = 16000): AsrResult?

    /** Liberar recursos(WebSocket conexión, etc). */
    fun close()
}

/**
 * Phase 9.3 (M2): ASR Fábrica cliente.
 *
 * Según [AsrConfig.provider] Crear correspondiente [AsrClient] Ejemplo.
 * SYSTEM modo retorno null(usar Intent,No utilices esta interfaz.).
 */
object AsrClientFactory {
    // M-ASR4: Retenciones singleton de fábrica compartidas OkHttpClient,Evite crear nuevos archivos independientes cada vez que cree client
    // (DashScopeAsrClient Originalmente, cada vez que era nuevo tenía un grupo de subprocesos independiente./grupo de conexiones OkHttpClient,provocando un desperdicio de recursos).
    // Ciclo de vida y fábrica del cliente compartido(proceso de solicitud)consistente,DashScopeAsrClient.close() No lo apagaré.
    private val sharedClient: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    fun create(config: AsrConfig): AsrClient? = when (config.provider) {
        // ASR-3: DashScopeAsrClient ha sido refactorizado en DashScopeAsrController(transmisión,Implementar la interfaz ASRController),
        // Ya no toma la antigua ruta de reconocimiento único de AsrClient. Regresar aquí null,Las misiones posteriores del ASR-4 integrarán el nuevo Controlador.
        // sharedClient Reservar,Inyectado por ASR-4 al crear DashScopeAsrController.
        AsrProviderType.DASHSCOPE -> null
        // Step Refactorizado a streaming StepAsrController(darse cuenta ASRController,ya no se implementa AsrClient),
        // La antigua interfaz AsrClient ya no es compatible,Regreso temporal aquí null,Utilice StepAsrController en su lugar desde la capa superior.
        AsrProviderType.STEP -> null
        AsrProviderType.DASHSCOPE_FILE -> DashScopeFileAsrClient(config)
        AsrProviderType.SYSTEM -> null
        // Todos los nuevos proveedores de streaming implementan la interfaz ASRController,No sigas el antiguo camino de AsrClient,
        // por ChatAudioCoordinator.getOrCreateAsrController Crear controlador.
        AsrProviderType.OPENAI_WHISPER -> null
        AsrProviderType.OPENAI_REALTIME -> null
        AsrProviderType.AGNES -> null
    }

    /**
     * Cree un controlador de grabación de streaming en la aplicación.
     *
     * viejo [create] Reservado para llamadas únicas, como transcripción de archivos.;La grabación del chat debe pasar por esta entrada.,
     * De lo contrario, el proveedor en tiempo real DashScope/Step/OpenAI quedará nulo debido a la incompatibilidad de la interfaz anterior.
     *
     * @param config ASR Configuración
     * @param context Solicitud Context(Para que el controlador de transmisión analice el texto de error visible para el usuario en cadenas localizadas)
     */
    fun createController(config: AsrConfig, context: Context): ASRController? = when (config.provider) {
        AsrProviderType.DASHSCOPE -> DashScopeAsrController(config, sharedClient, context)
        AsrProviderType.STEP -> StepAsrController(config)
        AsrProviderType.OPENAI_WHISPER -> OpenAiWhisperAsrController(config, sharedClient, context)
        AsrProviderType.OPENAI_REALTIME -> OpenAiRealtimeAsrController(config, sharedClient, context)
        AsrProviderType.AGNES -> OpenAiWhisperAsrController(config, sharedClient, context)
        AsrProviderType.SYSTEM, AsrProviderType.DASHSCOPE_FILE -> null
    }
}
