package io.zer0.muse.tools

/**
 * v1.0.53: Resultados de ejecución de herramientas(estructurado),usar `ok` / `error` Dos construcciones de fábrica.
 *
 * diseño:
 *  - [content] Siempre envíe mensajes de texto para LLM(Mantener la semántica de cadenas existente)
 *  - [details] Adjuntar datos estructurados(Para renderizado de interfaz de usuario/rastreo de enlaces/Contexto de aprobación),LLM invisibles
 *  - [isError] bandera de error:UI Puede usarlo para decidir si desea usar el estilo ErrorMessageMapper/rojo.
 *
 * compatibles:Contenido de solo lectura de las personas que llaman cuando no hay cambios;Las nuevas personas que llaman utilizan detalles/isError.
 */
data class ToolOutcome(
    val content: String,
    val details: Map<String, Any?> = emptyMap(),
    val isError: Boolean = false,
) {
    companion object {
        fun ok(content: String, details: Map<String, Any?> = emptyMap()) = ToolOutcome(content, details, isError = false)

        fun error(content: String, details: Map<String, Any?> = emptyMap()) = ToolOutcome(content, details, isError = true)
    }

    /** Texto a LLM(Compatible con la semántica antigua,Tómalo directamente content). */
    val text: String get() = content
}
