package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.tools.resource.ResourceLibraryStore

/**
 * P1-3b Desdominio: Registrador de herramientas del repositorio.
 *
 * Registrarse resource_add / resource_list / resource_search / resource_get / resource_delete.
 * La implementación está ubicada en [ResourceToolsImpl.kt].
 */
class ResourceToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    resourceLibrary: ResourceLibraryStore = ResourceLibraryStore(context),
) {
    private val impl = ResourceToolsImpl(context, resourceLibrary)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_add",
                description = "Agregar un recurso a la biblioteca de recursos(Notas/palabra rápida/Contenido de referencia, etc.).",
                parameters = mapOf(
                    "title" to "Requerido,Título del recurso",
                    "content" to "Requerido,Texto de recurso",
                    "tags" to "Opcional,etiqueta,Múltiples separados por comas",
                ),
                required = setOf("title", "content"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.add(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_list",
                description = "Listar recursos en la biblioteca de recursos,Se puede filtrar por palabra clave.",
                parameters = mapOf(
                    "keyword" to "Opcional,Título/Texto/Palabras clave de filtro de etiquetas",
                    "limit" to "Opcional,Número máximo de devoluciones,Predeterminado 20",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.list(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_search",
                description = "buscar en la biblioteca(Consistente con el comportamiento de filtrado de palabras clave de Resource_list).",
                parameters = mapOf(
                    "keyword" to "Opcional,Buscar palabras clave",
                    "limit" to "Opcional,Número máximo de devoluciones,Predeterminado 20",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.list(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_get",
                description = "Obtenga un único recurso en la biblioteca de recursos según la identificación.",
                parameters = mapOf("id" to "Requerido,Recursos id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.get(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_delete",
                description = "Elimine recursos en la biblioteca de recursos según la identificación.",
                parameters = mapOf("id" to "Requerido,Recursos id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.delete(args) }
    }
}
