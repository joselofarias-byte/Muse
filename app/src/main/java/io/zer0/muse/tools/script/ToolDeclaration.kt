package io.zer0.muse.tools.script

import kotlinx.serialization.Serializable

/**
 * Declaración de herramienta — uno `.muse-plugin` Los paquetes de complementos pueden exponer múltiples herramientas.
 *
 * Enlace de producción: por [io.zer0.muse.data.plugin.PluginPackageLoader] analizar `.muse-plugin`
 * Se obtiene el manifest.json del paquete. Una vez instalado el complemento, se registra en ToolRegistry (el nombre de la herramienta tiene el prefijo pluginId).
 *
 * Cada herramienta corresponde a una entrada invocable, a través de [functionName] Ruta al definido en el archivo de entrada JS
 * Funciones con el mismo nombre (a través de [WebViewSkillEngine] ejecución).
 */
@Serializable
data class ToolDeclaration(
    /** Nombre de la herramienta (slug formato,LLM utilizado al llamar). */
    val name: String,
    /** descripción de la herramienta (LLM Decida si llamar basándose en esto). */
    val description: String,
    /** Parámetros Esquema JSON(OpenAI formato compatible). */
    val parametersJson: String = "{}",
    /** Matriz de nombres de parámetros requeridos (JSON Cadena, como `["expr"]`). */
    val requiredJson: String = "[]",
    /** JS El nombre de la función definida en el archivo de entrada (como "calculate"). */
    val functionName: String,
)
