package io.zer0.muse.data.assistant

/**
 * Limpie el enlace de modelo explícito del Asistente después de eliminar el Proveedor.
 *
 * Assistant El ID de proveedor/ID de modelo son pares de referencias de hechos;Provider retenido después de ser eliminado
 * Cualquiera de los campos hará que el análisis posterior alcance una configuración que no existe. Borre todo el par de enlaces aquí, permitiendo a la persona que llama retroceder
 * Vaya al modelo global o vuelva a seleccionar un proveedor disponible.
 */
internal fun clearProviderBindings(assistants: List<AssistantEntity>, deletedProviderId: String): List<AssistantEntity> =
    assistants.map { assistant ->
        if (assistant.providerId == deletedProviderId) {
            assistant.copy(providerId = null, modelId = null)
        } else {
            assistant
        }
    }
