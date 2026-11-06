package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.image.ImageGenParams
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.web.WebSearchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.x: Configuración a nivel de chat Controlador abstraído de ChatViewModel.
 *
 * Responsabilidades:Apertura y cierre de la barra lateral, modo de dibujo, parámetros de dibujo, extracción de modelo ascendente, modelo de herramienta, limpieza de brindis de una sola vez.
 * (provider/modelo"cambiar"Los métodos setActiveProvider/setSelectedModel dependen del nivel de sesión override map,
 * Posteriormente, se importará junto con el mapa de anulación.)
 */
@Suppress("TooManyFunctions")
class ChatSettingsController(
    private val accessor: ChatStateAccessor,
    private val settings: SettingsRepository,
    private val appContext: Context,
    private val selectionStore: SessionModelSelectionStore,
) {

    /** Alternar apertura y cierre de la barra lateral. */
    fun toggleDrawer(open: Boolean) {
        accessor.update { it.copy(isDrawerOpen = open) }
    }

    /** P5-G: Cambia el modo de dibujo. El marcador de posición del campo de entrada cambia después de abrirlo,send Vaya a ImageService. */
    fun toggleDrawMode() {
        if (accessor.snapshot.isStreaming) return
        val newMode = !accessor.snapshot.isDrawMode
        accessor.update {
            it.copy(
                isDrawMode = newMode,
                // Borrar dibujos de referencia temporales al salir del modo de dibujo
                imageGenParams = if (!newMode) {
                    it.imageGenParams.copy(referenceImageUri = null)
                } else {
                    it.imageGenParams
                },
            )
        }
    }

    /** v0.34: Actualizar los parámetros de dibujo actuales(Puede anular temporalmente los valores predeterminados de configuración). */
    fun updateImageGenParams(params: ImageGenParams) {
        accessor.update { it.copy(imageGenParams = params) }
    }

    /** Activación de alternar Provider;Escriba anulaciones a nivel de sesión, borre modelos antiguos y active extracciones de /modelos cuando sea necesario. */
    fun setActiveProvider(providerId: String) {
        if (accessor.snapshot.isStreaming) return
        val st = accessor.snapshot
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        accessor.coroutineScope.launch {
            val defaultModelId = accessor.snapshot.providers.firstOrNull { it.id == providerId }
                ?.models?.firstOrNull()?.id
            selectionStore.setProviderOverride(sessionId, providerId)
            selectionStore.setModelOverride(sessionId, if (defaultModelId.isNullOrBlank()) null else defaultModelId)
            settings.saveSessionProviderOverride(sessionId, providerId)
            settings.saveSessionModelOverride(sessionId, defaultModelId)
            accessor.update { it.copy(activeProviderId = providerId, selectedModelId = defaultModelId) }
            val provider = accessor.snapshot.providers.firstOrNull { it.id == providerId }
            if (provider != null && provider.models.isEmpty() && provider.apiKey.isNotBlank()) {
                refreshModels(providerId)
            }
        }
    }

    /** Seleccione el modelo específico del Proveedor actual;Pasar nulo para borrar,Recurrir al modelo principal del proveedor. */
    fun setSelectedModel(modelId: String?) {
        if (accessor.snapshot.isStreaming) return
        val st = accessor.snapshot
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        accessor.coroutineScope.launch {
            val prevId = accessor.snapshot.selectedModelId
            val resolvedModelId = modelId ?: accessor.snapshot.providers
                .firstOrNull { it.id == accessor.snapshot.activeProviderId }
                ?.models?.firstOrNull()?.id
            val providerId = accessor.snapshot.activeProviderId
            selectionStore.setModelOverride(sessionId, if (resolvedModelId.isNullOrBlank()) null else resolvedModelId)
            selectionStore.setProviderOverride(sessionId, if (providerId.isNullOrBlank()) null else providerId)
            settings.saveSessionModelOverride(sessionId, resolvedModelId)
            settings.saveSessionProviderOverride(sessionId, providerId)
            accessor.update {
                it.copy(
                    selectedModelId = resolvedModelId,
                    toast = if (resolvedModelId != null && resolvedModelId != prevId) {
                        appContext.getString(R.string.err_chat_model_switched_toast)
                    } else {
                        it.toast
                    },
                )
            }
        }
    }

    /**
     * v1.22: manuales/Extrae automáticamente la lista de modelos ascendentes del proveedor especificado.
     * Después de que la extracción sea exitosa, actualice ProviderConfig.models y consérvelo.,Error al escribir fetchModelsError.
     */
    fun refreshModels(providerId: String) {
        if (accessor.snapshot.isFetchingModels) return
        val provider = accessor.snapshot.providers.firstOrNull { it.id == providerId } ?: return
        accessor.coroutineScope.launch {
            accessor.update { it.copy(isFetchingModels = true, fetchModelsError = null) }
            val result = resultOf {
                withContext(Dispatchers.IO) {
                    ProviderRegistry.create(provider).listModels(provider)
                }
            }
            accessor.update { it.copy(isFetchingModels = false) }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    accessor.update {
                        it.copy(fetchModelsError = appContext.getString(R.string.err_chat_fetch_models_empty))
                    }
                } else {
                    io.zer0.ai.core.ModelListCache.put(provider, models)
                    val updated = provider.copy(models = models)
                    settings.updateProvider(updated)
                    accessor.update { it.copy(fetchModelsError = null) }
                }
            }.onError { _, t ->
                val msg = t?.message ?: appContext.getString(R.string.err_chat_fetch_models_failed)
                accessor.update {
                    it.copy(
                        fetchModelsError = resolveFetchModelsError(msg),
                    )
                }
            }
        }
    }

    /** v1.60-A: Configurar modelo de herramienta(null Borrar,Heredar el modelo de diálogo principal.). */
    fun setToolModel(modelId: String?) {
        accessor.coroutineScope.launch {
            settings.saveToolModel(modelId)
        }
    }

    /** v0.39: Alternar el interruptor de pensamiento profundo(Solo estado de tiempo de ejecución,No persistente,La próxima vez que ingrese al asistente de restauración de sesión predeterminado). */
    fun toggleDeepThinking() {
        accessor.update { it.copy(deepThinkingEnabled = !it.deepThinkingEnabled) }
    }

    /** v1.0.47 P5-6: Nivel circular de pensamiento profundo(LOW → MEDIUM → HIGH → XHIGH → LOW). */
    fun cycleDeepThinkingLevel() {
        accessor.update { state ->
            val next = when (state.deepThinkingLevel) {
                ReasoningLevel.LOW -> ReasoningLevel.MEDIUM
                ReasoningLevel.MEDIUM -> ReasoningLevel.HIGH
                ReasoningLevel.HIGH -> ReasoningLevel.XHIGH
                ReasoningLevel.XHIGH -> ReasoningLevel.LOW
                else -> ReasoningLevel.HIGH // Ten todo en cuenta:OFF/AUTO Volver al valor predeterminado HIGH
            }
            state.copy(deepThinkingLevel = next)
        }
    }

    /** v0.51: Limpiar una vez toast(Toast Llamado por la interfaz de usuario inmediatamente después de aparecer,Evite repetidos bombardeos durante la reorganización.). */
    fun clearToast() {
        accessor.update { it.copy(toast = null) }
    }

    /** v1.117: Activar y desactivar la búsqueda web(Cambiar UI + persistencia settings). */
    fun toggleWebSearch() {
        val cfg = accessor.snapshot.webSearchConfig
        val enabling = !cfg.enabled || cfg.mode == WebSearchMode.OFF
        val newCfg = cfg.copy(
            enabled = enabling,
            mode = if (enabling) {
                if (cfg.mode == WebSearchMode.OFF) WebSearchMode.AUTO else cfg.mode
            } else {
                WebSearchMode.OFF
            },
        )
        accessor.update {
            it.copy(webSearchEnabled = newCfg.enabled, webSearchConfig = newCfg)
        }
        accessor.coroutineScope.launch {
            resultOf { settings.saveWebSearchConfig(newCfg) }
        }
    }

    /** Asigne el mensaje de error obtenido por /models al texto de error localizado. */
    private fun resolveFetchModelsError(msg: String): String = when {
        msg.contains("401") || msg.contains("403") -> appContext.getString(R.string.err_chat_auth_invalid)
        msg.contains("Unable to resolve") || msg.contains("UnknownHost") ->
            appContext.getString(R.string.err_chat_fetch_models_no_server)
        msg.contains("timeout", ignoreCase = true) -> appContext.getString(R.string.err_chat_fetch_models_timeout)
        msg.contains("404") -> appContext.getString(R.string.err_chat_fetch_models_not_supported)
        else -> appContext.getString(R.string.err_chat_fetch_models_failed_msg, msg.take(120))
    }
}
