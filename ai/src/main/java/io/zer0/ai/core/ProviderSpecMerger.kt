package io.zer0.ai.core

/**
 * v1.0.7: Especificaciones del proveedor integradas(spec)Fusionar con la superposición de usuario.
 *
 * Mecanismo de fusión de tres niveles(L1 Builtin Plugin Declaración + L3 Provider Catalog overlay)Implementación pragmática en Android:
 *  - [ProviderConfig.specId] tiempo no vacío,Indica que la configuración se deriva de una especificación incorporada del proveedor.
 *  - Fusionar la lista de modelos predeterminados de especificaciones con la lista de modelos superpuestos de usuario en tiempo de ejecución(Eliminar duplicados con el mismo ID,Los campos de usuario tienen prioridad)
 *  - Después de que los proveedores integrados actualicen la lista de modelos predeterminados,Los usuarios agregados ven automáticamente el nuevo modelo.(No es necesario volver a agregar manualmente)
 *
 * Realizar compensaciones:
 *  - 1muse ProviderConfig es el objeto completo.,Aquí solo se realiza la fusión de listas de modelos.(Puntos de dolor centrales),
 *    campos restantes(baseUrl/displayName/specific espera)Mantenga la configuración del usuario primero(El usuario puede haber personalizado)
 *
 * estrategia de fusión:
 *  1. spec El modelo predeterminado es lo primero(mantener el orden predeterminado),La misma identificación se sobrescribe con la versión del usuario.(El usuario puede haber cambiado name/contextWindow)
 *  2. user Agregar nuevo modelo(no en especificaciones)
 *  3. spec o usuario cuando cualquiera de ellos está vacío,Devuelve directamente el lado no vacío(no fusionar)
 */
object ProviderSpecMerger {

    /**
     * Combinar la lista de modelos predeterminados de especificaciones + la lista de modelos superpuestos de usuario.
     *
     * @param specModels Lista de modelos predeterminados para especificaciones integradas(declaración inmutable,lanzado con la versión)
     * @param userModels Lista de modelos configurados por el usuario(puede estar vacío,expresar"Usar predeterminado";Puede que no esté vacío,expresar"Personalizado/anexar")
     * @return Lista de modelos fusionados:spec Predeterminado + anexo de usuario,El usuario con la misma identificación tiene prioridad
     */
    fun mergeModels(specModels: List<Model>, userModels: List<Model>): List<Model> {
        if (specModels.isEmpty()) return userModels
        if (userModels.isEmpty()) return specModels
        val userById = userModels.associateBy { it.id }
        val merged = LinkedHashMap<String, Model>(specModels.size + userModels.size)
        // spec El modelo predeterminado es lo primero(mantener el orden),El usuario sobrescribe la misma identificación.
        for (model in specModels) {
            merged[model.id] = userById[model.id] ?: model
        }
        // user Agregar nuevo modelo(no en especificaciones)
        for (model in userModels) {
            if (!merged.containsKey(model.id)) {
                merged[model.id] = model
            }
        }
        return merged.values.toList()
    }

    /**
     * Enriquezca la configuración del usuario con valores predeterminados de especificaciones(Actualmente solo se fusionan listas de modelos.).
     *
     * @param config Usuario ProviderConfig(Leer y descifrar desde DataStore apiKey)
     * @param spec Especificaciones del proveedor integradas(Búsqueda por persona que llama por especificación);null No fusionado
     * @return Después de la fusión ProviderConfig;spec Devuelve sin cambios si null o config.specId es nulo
     */
    fun enrichConfig(config: ProviderConfig, spec: ProviderConfig?): ProviderConfig {
        if (spec == null || config.specId == null) return config
        val mergedModels = mergeModels(spec.models, config.models)
        if (mergedModels.size == config.models.size && mergedModels == config.models) {
            return config // Sin cambios,Evite crear nuevos objetos
        }
        return config.copy(models = mergedModels)
    }
}
