package io.zer0.muse.data.routing

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * Nivel de tarea auxiliar — modelo de tercera marcha(Gadgets / Grandes herramientas / Visión).
 *
 * con [SettingsRepository.TaskRoutingConfig](Divida el tráfico en el modelo principal según el contenido de la conversación.)son dos dimensiones:
 * Esta enumeración solo maneja tareas auxiliares en segundo plano.(Título, compresión, memoria, juicio de enrutamiento, etc.)selección de modelo.
 */
enum class UtilityTier {
    /** artilugio:Tareas breves como generación de títulos, clasificación ligera, enrutamiento de intenciones, mensajes de portada y resúmenes de canales. */
    SMALL,

    /** grandes herramientas:Escenarios que requieren comprensión, como compresión de contexto, recuperación de memoria, resumen de actividades, desmontaje de tareas, subagente, etc. */
    LARGE,

    /** Visión:Comprensión de imágenes/ayuda OCR(Heredar la configuración del modelo de visión existente). */
    VISION,
}

/**
 * Encuadernación de modelo auxiliar — También lleve Proveedor.
 *
 * v1.0.62 Lecciones:El modelo comprimido solía almacenar solo model id,La coincidencia entre proveedores por identificación afectará a canales irrelevantes
 * Modelo pequeño del mismo nombre.,Conduciendo a alta y baja calidad. Después de vincular con el ID del proveedor,El enrutamiento siempre llega a la elección exacta del usuario.
 */
@Serializable
data class UtilityModelBinding(
    val providerId: String,
    val modelId: String,
)

/**
 * Enrutamiento de modelo auxiliar — Convergencia uniforme de la selección de modelos para todas las tareas auxiliares en segundo plano.
 *
 * reglas en cascada:
 *  - [UtilityTier.LARGE] dejar en blanco → Reutilizar [UtilityTier.SMALL]
 *  - [UtilityTier.SMALL] dejar en blanco → Regresar null,La persona que llama hereda el modelo de conversación principal.
 *  - [UtilityTier.VISION] Heredar la configuración del modelo de visión existente,No habilitado/No seleccionado → null
 *
 * El análisis falló(Provider Eliminado/Modelo eliminado)Devuelve lo mismo null,La persona que llama revierte el modelo principal.,No bloquees tareas.
 */
class UtilityModelRouter(private val settings: SettingsRepository) {

    /** analizar engranaje → (providerConfig, model);null Indica que se utiliza el modelo de diálogo principal. */
    suspend fun resolve(tier: UtilityTier): Pair<ProviderConfig, Model>? {
        val binding = when (tier) {
            UtilityTier.SMALL -> settings.utilityModelBindingFlow.first()
            UtilityTier.LARGE -> settings.utilityLargeModelBindingFlow.first()
                ?: settings.utilityModelBindingFlow.first()
            UtilityTier.VISION -> {
                val providerId = settings.visionProviderIdFlow.first()
                val modelId = settings.visionModelIdFlow.first()
                if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) {
                    null
                } else {
                    UtilityModelBinding(providerId = providerId, modelId = modelId)
                }
            }
        } ?: return null
        return resolveBinding(binding)
    }

    /** Resolver un enlace directamente(página de configuración"Detección"Reutilización de escenas como botones.). */
    suspend fun resolveBinding(binding: UtilityModelBinding): Pair<ProviderConfig, Model>? {
        val provider = settings.getProviderById(binding.providerId) ?: return null
        val model = provider.models.firstOrNull { it.id == binding.modelId } ?: return null
        return provider to model
    }
}
