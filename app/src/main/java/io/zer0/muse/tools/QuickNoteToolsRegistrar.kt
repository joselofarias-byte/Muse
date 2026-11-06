package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b Desdominio: registrador de herramientas de registro rápido.
 *
 * Registrarse quick_note_add / list / search / get / update / delete / pin.
 * La implementación está ubicada en [QuickNoteToolsImpl.kt].
 */
class QuickNoteToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = QuickNoteToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_add",
                description = "Agregar un registro rápido(notas ligeras). El modelo puede ayudar a los usuarios a anotar tareas pendientes durante las conversaciones/inspiración/Contenido de referencia.",
                parameters = mapOf(
                    "title" to "Requerido,título del registro",
                    "content" to "Opcional,Grabar texto",
                    "tags" to "Opcional,etiqueta,Múltiples separados por comas",
                ),
                required = setOf("title"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.add(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_list",
                // v1.0.75 fix (Revisión de herramientas 01): El Quick_note_search original es un duplicado exacto de este y ha sido eliminado.,
                // Modelo de rondas de desperdicio de apuestas de dos opciones. Esta herramienta unifica la lista/filtrar/Buscar.
                description = "lista/Buscar registros rápidos. Admite el filtrado de etiquetas del cuerpo del título por palabras clave y un filtrado preciso por etiquetas. Si necesita ver el contenido completo de una sola nota, utilice quick_note_get.",
                parameters = mapOf(
                    "keyword" to "Opcional,Título/Texto/Palabras clave de filtro de etiquetas",
                    "tag" to "Opcional,Filtrar precisamente por etiquetas",
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
                name = "quick_note_get",
                description = "Obtenga un único registro rápido basado en la identificación.",
                parameters = mapOf("id" to "Requerido,grabar id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.get(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_update",
                description = "Actualice el registro rápido especificado.",
                parameters = mapOf(
                    "id" to "Requerido,grabar id",
                    "title" to "Opcional,nuevo título",
                    "content" to "Opcional,Nuevo texto",
                    "tags" to "Opcional,nueva pestaña,Múltiples separados por comas",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.update(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_delete",
                description = "Eliminar registro rápido especificado(Mover a la papelera de reciclaje,Se puede restaurar en la Papelera de reciclaje).",
                parameters = mapOf("id" to "Requerido,grabar id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.delete(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_pin",
                description = "fijar hacia arriba/Desanclar un registro rápido.",
                parameters = mapOf(
                    "id" to "Requerido,grabar id",
                    "pinned" to "Requerido,true/false",
                ),
                required = setOf("id", "pinned"),
                category = "built-in",
                parameterTypes = mapOf("pinned" to "boolean"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.pin(args) }
    }
}
