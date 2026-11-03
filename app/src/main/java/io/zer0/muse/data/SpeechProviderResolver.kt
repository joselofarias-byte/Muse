package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType

/**
 * Fuente de credenciales para las capacidades de habla.
 *
 * - [MANUAL]: Utilice el llenado manual en la página de configuración TTS/ASR endpoint / apiKey / model
 * - [PROVIDER]: Utilice la configuración del proveedor baseUrl / apiKey,El modelo toma el modelo seleccionado manualmente en modo de seguimiento id
 */
enum class SpeechEndpointSource {
    PROVIDER,
    MANUAL,
}

/**
 * Los parámetros de conexión finales que entran en vigor para una solicitud de voz.
 *
 * @param baseUrl Dirección de interfaz efectiva real(Puede ser una cadena vacía en modo manual.,Se cuidan los valores predeterminados integrados de cada cliente.)
 * @param apiKey realmente efectivo apiKey(Descifrado)
 * @param model Nombre del modelo real
 * @param source Fuente de parámetros
 * @param fallbackReason Razones por las que el interruptor se enciende pero vuelve a la configuración manual;
 *   null Indica que no se ha producido ninguna reversión(Apagar el interruptor no cuenta como reversión)
 */
data class ResolvedSpeechEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val source: SpeechEndpointSource,
    val fallbackReason: String? = null,
) {
    /** ¿Se debe a que no se siguió?(La persona que llama llama en consecuencia WARN). */
    val isProviderFallback: Boolean
        get() = source == SpeechEndpointSource.MANUAL && fallbackReason != null
}

/**
 * TTS / ASR Analizador "Seguir la configuración del proveedor".
 *
 * función pura:Ingrese la configuración actual + lista de proveedores,Generar la dirección efectiva real/Credenciales/modelo,No se realiza ninguna IO y no se lanzan excepciones.
 * La persona que llama es responsable de [ResolvedSpeechEndpoint.fallbackReason] Iniciar sesión cuando no esté vacío,Fácil de solucionar problemas
 * Este tipo de reversión silenciosa ocurre cuando "el interruptor está encendido pero aún se realiza la configuración manual".
 *
 * Prioridad de regla de mayor a menor:
 *  1. apagar → Siempre configurado manualmente([SpeechEndpointSource.MANUAL]);
 *  2. Encendido + Proveedor analizable y habilitado + Modelo seleccionado → Configuración del proveedor([SpeechEndpointSource.PROVIDER]);
 *  3. El interruptor está encendido pero falta el proveedor./Eliminado, proveedor deshabilitado, modelo no seleccionado → Recurrir a la configuración manual con motivos.
 */
object SpeechProviderResolver {
    /** El proveedor señalado por el siguiente patrón ya no existe(o ser eliminado). */
    const val REASON_PROVIDER_MISSING = "provider_missing"

    /** El proveedor señalado por el siguiente patrón existe pero está desactivado. */
    const val REASON_PROVIDER_DISABLED = "provider_disabled"

    /** No se selecciona ningún modelo en el modo de seguimiento. */
    const val REASON_MODEL_MISSING = "model_missing"

    /** analizar TTS(motor de nube)parámetros de conexión reales. */
    fun resolveMedia(config: MediaConfig, providers: List<ProviderConfig>): ResolvedSpeechEndpoint =
        resolve(
            follow = config.ttsFollowProvider,
            providerId = config.ttsProviderId,
            modelId = config.ttsModelId,
            manualBaseUrl = config.ttsEndpoint,
            manualApiKey = config.ttsApiKey,
            manualModel = config.ttsModel,
            providers = providers,
        )

    /**
     * Analice los parámetros de conexión reales del ASR.
     *
     * DashScope Identificación en tiempo real de puntos finales wss integrados([AsrConfig.asrEndpoint]),El proveedor baseUrl no participa,
     * Por lo tanto, el interruptor de seguimiento simplemente se ignora en este motor.(Tratar como configuración manual,sin motivo de devolución):
     * Cuando el usuario enciende por primera vez, sigue en otro motor y luego cambia a DashScope,Tampoco uso indebido de direcciones de proveedores.,
     * No se eliminará por culpa de los proveedores./Aparece una falsa alarma de respaldo debido a la desactivación.
     */
    fun resolveAsr(config: AsrConfig, providers: List<ProviderConfig>): ResolvedSpeechEndpoint {
        if (config.provider == AsrProviderType.DASHSCOPE) {
            return manual(config.baseUrl, config.apiKey, config.model)
        }
        return resolve(
            follow = config.followProvider,
            providerId = config.providerId,
            modelId = config.modelId,
            manualBaseUrl = config.baseUrl,
            manualApiKey = config.apiKey,
            manualModel = config.model,
            providers = providers,
        )
    }

    private fun manual(baseUrl: String, apiKey: String, model: String): ResolvedSpeechEndpoint =
        ResolvedSpeechEndpoint(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            source = SpeechEndpointSource.MANUAL,
        )

    private fun resolve(
        follow: Boolean,
        providerId: String,
        modelId: String,
        manualBaseUrl: String,
        manualApiKey: String,
        manualModel: String,
        providers: List<ProviderConfig>,
    ): ResolvedSpeechEndpoint {
        val manualResult = manual(manualBaseUrl, manualApiKey, manualModel)
        // Regla 1: el interruptor tiene la máxima prioridad cuando está cerrado,La lista de proveedores no participa en absoluto.
        if (!follow) return manualResult

        val provider = providers.firstOrNull { it.id == providerId }
        val reason = if (providerId.isBlank() || provider == null) {
            REASON_PROVIDER_MISSING
        } else if (!provider.enabled) {
            REASON_PROVIDER_DISABLED
        } else if (modelId.isBlank()) {
            REASON_MODEL_MISSING
        } else {
            null
        }
        if (reason != null) return manualResult.copy(fallbackReason = reason)
        val resolvedProvider = provider ?: return manualResult.copy(fallbackReason = REASON_PROVIDER_MISSING)
        return ResolvedSpeechEndpoint(
            baseUrl = resolvedProvider.resolvedBaseUrl(),
            apiKey = resolvedProvider.apiKey,
            model = modelId,
            source = SpeechEndpointSource.PROVIDER,
        )
    }
}

/**
 * Caída de los resultados del análisis a la configuración ASR.,Para que cada controlador de transmisión lea directamente.
 *
 * Volver al modo manual tal cual(Asegúrese de que los valores completados manualmente no se sobrescriban);Reemplace solo cuando esté seguro de acudir al proveedor.
 * apiKey / baseUrl / model,campos restantes(tasa de muestreo,VAD, palabras calientes, etc.)permanecer sin cambios.
 */
fun AsrConfig.withResolvedEndpoint(resolved: ResolvedSpeechEndpoint): AsrConfig = when (resolved.source) {
    SpeechEndpointSource.MANUAL -> this
    SpeechEndpointSource.PROVIDER -> copy(
        apiKey = resolved.apiKey,
        baseUrl = resolved.baseUrl,
        model = resolved.model,
    )
}
