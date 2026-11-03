package io.zer0.muse.proactive

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.data.routing.UtilityModelRouter
import io.zer0.muse.data.routing.UtilityTier
import kotlinx.coroutines.flow.first

/**
 * Qué modelo utilizar para esta ronda de mensajes activos.
 *
 * Por defecto, es coherente con el modelo que utiliza este asistente para el chat diario. —— Las noticias activas hablan por sí solas,
 * Cambiar de modelo equivale a cambiar de persona. Una fractura de personalidad es mucho peor que enviar demasiados mensajes;
 * El dinero simbólico ahorrado no vale la pena aquí.
 *
 * Orden alternativa: modelo especificado por el asistente → Modelo seleccionado globalmente → Modelo de archivo de herramienta.
 * El último paso es simplemente asegurarse de que "aún puedas hablar". Es mejor tener mal tono que no poder decirlo en absoluto.
 */
internal object ProactiveModelPicker {

    private const val TAG = "ProactiveModel"

    suspend fun pick(
        settings: SettingsRepository,
        assistant: AssistantEntity,
    ): Pair<ProviderConfig, Model>? {
        val router = UtilityModelRouter(settings)
        val providerId = assistant.providerId?.takeIf { it.isNotBlank() }
            ?: readOrNull { settings.activeProviderIdFlow.first() }
        val modelId = assistant.modelId?.takeIf { it.isNotBlank() }
            ?: readOrNull { settings.selectedModelIdFlow.first() }

        if (modelId.isNullOrBlank()) {
            Logger.w(TAG, "Ni el asistente ni el modelo global están vinculados al modelo, así que regrese al archivo de herramientas.")
            return utilityModel(router)
        }
        val resolved = runCatching {
            router.resolveBinding(UtilityModelBinding(providerId = providerId.orEmpty(), modelId = modelId))
        }.onFailure { e -> Logger.w(TAG, "El análisis según el modelo de chat falló y volvió al archivo de la herramienta.: ${e.message}") }
            .getOrNull()
        return resolved ?: utilityModel(router)
    }

    private suspend fun utilityModel(router: UtilityModelRouter): Pair<ProviderConfig, Model>? =
        runCatching { router.resolve(UtilityTier.LARGE) }.getOrNull()

    private suspend fun <T> readOrNull(read: suspend () -> T): T? = runCatching { read() }.getOrNull()
}
