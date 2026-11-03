package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.rag.RagConfig

/** Provider Referencias globales y a nivel de sesión que deben limpiarse sincrónicamente cuando se eliminan. */
internal data class ProviderReferenceCleanup(
    val providers: List<ProviderConfig>,
    val activeProviderId: String?,
    val selectedModelId: String?,
    val toolModelId: String?,
    val subagentModelId: String?,
    val compressModelId: String?,
    val visionModelId: String?,
    val visionProviderId: String?,
    val utilityModelBinding: UtilityModelBinding?,
    val utilityLargeModelBinding: UtilityModelBinding?,
    val imageGenConfig: ImageGenConfig,
    val videoGenConfig: VideoGenConfig,
    val taskRoutingConfig: SettingsRepository.TaskRoutingConfig,
    val ragConfig: RagConfig,
    val sessionModelOverrides: Map<String, String>,
    val sessionProviderOverrides: Map<String, String>,
)

/**
 * Calcule el estado de referencia después de eliminar el Proveedor.
 *
 * Limpiar únicamente las referencias que se determine que pertenecen al Proveedor eliminado; cuando el modelo con el mismo nombre todavía existe en otros Proveedores,
 * Conserve la ID del modelo para evitar eliminar accidentalmente una selección global que todavía está disponible. Cuando se elimina la anulación del proveedor de sesión,
 * La superposición del modelo de sesión correspondiente también se elimina para evitar la formación de una configuración de superposición de medio conjunto.
 */
@Suppress("LongParameterList")
internal fun cleanupProviderReferences(
    providers: List<ProviderConfig>,
    deletedProviderId: String,
    activeProviderId: String?,
    selectedModelId: String?,
    toolModelId: String?,
    subagentModelId: String?,
    compressModelId: String?,
    visionModelId: String?,
    visionProviderId: String?,
    utilityModelBinding: UtilityModelBinding? = null,
    utilityLargeModelBinding: UtilityModelBinding? = null,
    imageGenConfig: ImageGenConfig = ImageGenConfig(),
    videoGenConfig: VideoGenConfig = VideoGenConfig(),
    taskRoutingConfig: SettingsRepository.TaskRoutingConfig = SettingsRepository.TaskRoutingConfig(),
    ragConfig: RagConfig = RagConfig(),
    sessionModelOverrides: Map<String, String>,
    sessionProviderOverrides: Map<String, String>,
): ProviderReferenceCleanup {
    val deleted = providers.firstOrNull { it.id == deletedProviderId }
    val remaining = providers.filterNot { it.id == deletedProviderId }
    val deletedModelIds = deleted?.models?.map { it.id }?.toSet().orEmpty()
    val remainingModelIds = remaining.flatMap { provider -> provider.models.map { it.id } }.toSet()

    fun cleanModel(modelId: String?): String? =
        if (modelId != null && modelId in deletedModelIds && modelId !in remainingModelIds) null else modelId

    val deletedSessions = sessionProviderOverrides
        .filterValues { it == deletedProviderId }
        .keys
    val cleanedSessionModels = sessionModelOverrides.filterKeys { it !in deletedSessions }
    val cleanedSessionProviders = sessionProviderOverrides.filterKeys { it !in deletedSessions }
    val active = if (activeProviderId == deletedProviderId) remaining.firstOrNull()?.id else activeProviderId
    val visionProvider = if (visionProviderId == deletedProviderId) null else visionProviderId
    val visionModel = if (visionProviderId == deletedProviderId) null else cleanModel(visionModelId)

    // v2.x: Correa de sujeción modelo auxiliar providerId — El enlace del proveedor eliminado se deja directamente en blanco.(Sin retención entre canales:
    // La semántica de la vinculación es"Ese modelo llega a ese canal exactamente.",Si el canal desaparece, dejará de ser válido.).
    fun cleanBinding(binding: UtilityModelBinding?): UtilityModelBinding? =
        if (binding != null && binding.providerId == deletedProviderId) null else binding

    fun cleanProviderModel(providerId: String, modelId: String): Pair<String, String> {
        if (providerId == deletedProviderId) return "" to ""
        return providerId to cleanModel(modelId).orEmpty()
    }

    val (imageProvider, imageModel) = cleanProviderModel(imageGenConfig.providerId, imageGenConfig.modelId)
    val (videoProvider, videoModel) = cleanProviderModel(videoGenConfig.providerId, videoGenConfig.modelId)
    val (chatProvider, chatModel) = cleanProviderModel(
        taskRoutingConfig.chatProviderId.orEmpty(),
        taskRoutingConfig.chatModelId.orEmpty(),
    )
    val (reasoningProvider, reasoningModel) = cleanProviderModel(
        taskRoutingConfig.reasoningProviderId.orEmpty(),
        taskRoutingConfig.reasoningModelId.orEmpty(),
    )
    val (codeProvider, codeModel) = cleanProviderModel(
        taskRoutingConfig.codeProviderId.orEmpty(),
        taskRoutingConfig.codeModelId.orEmpty(),
    )
    val (creativeProvider, creativeModel) = cleanProviderModel(
        taskRoutingConfig.creativeProviderId.orEmpty(),
        taskRoutingConfig.creativeModelId.orEmpty(),
    )
    val (analysisProvider, analysisModel) = cleanProviderModel(
        taskRoutingConfig.analysisProviderId.orEmpty(),
        taskRoutingConfig.analysisModelId.orEmpty(),
    )
    // RAG Integración en la nube: el proveedor se borrará si desaparece.,De lo contrario, cloudProviderId quedará permanentemente vacante.
    val (ragProvider, ragModel) = cleanProviderModel(ragConfig.cloudProviderId, ragConfig.cloudModel)

    return ProviderReferenceCleanup(
        providers = remaining,
        activeProviderId = active,
        selectedModelId = cleanModel(selectedModelId),
        toolModelId = cleanModel(toolModelId),
        subagentModelId = cleanModel(subagentModelId),
        compressModelId = cleanModel(compressModelId),
        visionModelId = visionModel,
        visionProviderId = visionProvider,
        utilityModelBinding = cleanBinding(utilityModelBinding),
        utilityLargeModelBinding = cleanBinding(utilityLargeModelBinding),
        imageGenConfig = imageGenConfig.copy(providerId = imageProvider, modelId = imageModel),
        videoGenConfig = videoGenConfig.copy(providerId = videoProvider, modelId = videoModel),
        taskRoutingConfig = taskRoutingConfig.copy(
            chatProviderId = chatProvider.ifBlank { null },
            chatModelId = chatModel.ifBlank { null },
            reasoningProviderId = reasoningProvider.ifBlank { null },
            reasoningModelId = reasoningModel.ifBlank { null },
            codeProviderId = codeProvider.ifBlank { null },
            codeModelId = codeModel.ifBlank { null },
            creativeProviderId = creativeProvider.ifBlank { null },
            creativeModelId = creativeModel.ifBlank { null },
            analysisProviderId = analysisProvider.ifBlank { null },
            analysisModelId = analysisModel.ifBlank { null },
        ),
        ragConfig = ragConfig.copy(cloudProviderId = ragProvider, cloudModel = ragModel),
        sessionModelOverrides = cleanedSessionModels,
        sessionProviderOverrides = cleanedSessionProviders,
    )
}

/**
 * Una colección de configuración que hace referencia a un ID de modelo específico —— Secciones que deben limpiarse al eliminar un solo modelo.
 *
 * con [ProviderReferenceCleanup] complementario:El responsable de "todo el Proveedor se ha ido",Este tubo "Provider Todavía está ahí, pero cierto modelo ya no existe”.
 */
internal data class ModelReferences(
    val imageGenConfig: ImageGenConfig = ImageGenConfig(),
    val videoGenConfig: VideoGenConfig = VideoGenConfig(),
    val taskRoutingConfig: SettingsRepository.TaskRoutingConfig = SettingsRepository.TaskRoutingConfig(),
    val utilityModelBinding: UtilityModelBinding? = null,
    val utilityLargeModelBinding: UtilityModelBinding? = null,
    val ragConfig: RagConfig = RagConfig(),
    val visionModelId: String? = null,
)

/**
 * Calcula el estado de referencia después de eliminar un modelo de un proveedor.
 *
 * Principios de procesamiento:
 *  - Campo de clase de ID de modelo(fotos/vídeo/Enrutamiento de tareas/RAG cloudModel/Visión)Al señalar el modelo eliminado**dejar en blanco**,
 *    Déjelo en el modelo predeterminado que viene con cada tiempo de ejecución. —— No volver a consultar automáticamente otros modelos con el mismo Proveedor,
 *    Evite vincular silenciosamente modelos que no hayan sido seleccionados por el usuario.
 *  - Correa de sujeción modelo auxiliar providerId,La semántica es "acertar con precisión en el modelo de este canal".,Cuando se accede a un modelo eliminado, todo el campo se deja en blanco.
 *  - El valor original se conserva cuando el modelo con el mismo nombre todavía existe en otros proveedores.(Es común que los sitios de tránsito compartan identificaciones de modelos con el funcionario.).
 */
internal fun cleanupRemovedModelReferences(
    providerId: String,
    removedModelIds: Set<String>,
    retainedModelIds: Set<String>,
    references: ModelReferences,
): ModelReferences {
    fun clean(modelId: String?): String? =
        if (modelId != null && modelId in removedModelIds && modelId !in retainedModelIds) null else modelId

    fun cleanBinding(binding: UtilityModelBinding?): UtilityModelBinding? {
        if (binding == null || binding.providerId != providerId) return binding
        return if (clean(binding.modelId) == null) null else binding
    }

    val routing = references.taskRoutingConfig
    val rag = references.ragConfig
    return references.copy(
        imageGenConfig = references.imageGenConfig.copy(
            modelId = clean(references.imageGenConfig.modelId).orEmpty(),
        ),
        videoGenConfig = references.videoGenConfig.copy(
            modelId = clean(references.videoGenConfig.modelId).orEmpty(),
        ),
        taskRoutingConfig = routing.copy(
            chatModelId = clean(routing.chatModelId),
            reasoningModelId = clean(routing.reasoningModelId),
            codeModelId = clean(routing.codeModelId),
            creativeModelId = clean(routing.creativeModelId),
            analysisModelId = clean(routing.analysisModelId),
        ),
        utilityModelBinding = cleanBinding(references.utilityModelBinding),
        utilityLargeModelBinding = cleanBinding(references.utilityLargeModelBinding),
        ragConfig = rag.copy(cloudModel = clean(rag.cloudModel).orEmpty()),
        visionModelId = clean(references.visionModelId),
    )
}
