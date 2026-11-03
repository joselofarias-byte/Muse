package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.script.ToolDeclaration
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Asistente (modelo grande) que redacta solicitudes de complementos.
 *
 * El modelo solo envía texto:JS Código fuente, declaraciones de herramientas y metadatos;ZIP El host siempre presiona los paquetes y manifiestos.
 * [PluginAuthoringRules] Construyendo, el modelo no produce ningún binario. El paquete construido es**borrador sin firmar**,
 * Solo será firmado y habilitado por la clave de autor nativa después de que el usuario asienta explícitamente a la página de administración del complemento.
 */
data class AuthoredPluginRequest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    /** JS Código fuente de entrada; La función (implementación de la herramienta) debe ser de nivel superior. `function` declaración. */
    val code: String,
    /** Declaración de herramienta; El nombre de la función debe existir realmente en [code] en. */
    val tools: List<ToolDeclaration>,
    /** declarar capacidad (v2.x Una vez que se lanza la zona de pruebas, solo se verifica el formato del nombre y solo se usa para la visualización de vista previa de la instalación; no se declaran capacidades de forma predeterminada). */
    val capabilities: List<String> = emptyList(),
)

/**
 * El asistente redacta las reglas del lado del host del complemento (lógica pura, conveniente para pruebas individuales).
 *
 * Calibre de calibración y `tools/market-signer/sign.py` Consistente:id/Nombre de la herramienta/Forma del nombre de la función, lista blanca de capacidades,
 * y "manifest La función declarada realmente debe estar escrita en el código de entrada como una función de nivel superior". Si alguna de ellas falla,
 * Devuelve un motivo legible en chino sin efectos secundarios (la persona que llama lo verifica antes de crear el ZIP).
 */
object PluginAuthoringRules {

    /** El límite superior del tamaño del código de entrada; mucho más pequeño que el archivo único del cargador/Límite de descompresión total para evitar ocupar el disco con grandes corrientes de aire. */
    const val MAX_CODE_BYTES = 256 * 1024

    /** El número máximo de herramientas que se pueden declarar en un solo borrador. */
    const val MAX_TOOLS = 32

    /** El host tiene un nombre de archivo de entrada fijo; el modelo no puede elegir su propia ruta de entrada. */
    const val ENTRY_FILE = "main.js"

    /** Verificar la solicitud; devolver el motivo legible en chino,null Indica paso. */
    fun validate(request: AuthoredPluginRequest): String? {
        if (!PLUGIN_ID_REGEX.matches(request.id)) {
            return "La identificación del complemento es ilegal: Solo puede contener letras minúsculas, números, guiones bajos y guiones, y comenzar con una letra o un número."
        }
        if (request.name.isBlank()) {
            return "El nombre del complemento no puede estar vacío"
        }
        if (PluginVersion.parse(request.version) == null) {
            return "El número de versión del complemento debe ser una versión semántica (como 1.0.0): ${request.version}"
        }
        if (request.code.isBlank()) {
            return "El código del complemento no puede estar vacío"
        }
        val codeBytes = request.code.toByteArray(Charsets.UTF_8).size
        if (codeBytes > MAX_CODE_BYTES) {
            return "El código del complemento excede el límite de tamaño(${MAX_CODE_BYTES / 1024}KB): actual $codeBytes bytes"
        }
        if (request.tools.isEmpty()) {
            return "Los complementos deben declarar al menos una herramienta"
        }
        if (request.tools.size > MAX_TOOLS) {
            return "El número de herramientas enchufables supera el límite superior($MAX_TOOLS): actual ${request.tools.size} un"
        }
        val duplicate = request.tools.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return "Nombre de herramienta de complemento duplicado: ${duplicate.key}"
        }
        request.tools.forEach { tool ->
            if (!TOOL_NAME_REGEX.matches(tool.name)) {
                return "El nombre de la herramienta es ilegal.: ${tool.name}(Solo se permiten letras, números, guiones bajos y guiones, longitud 1-64)"
            }
            if (!FUNCTION_NAME_REGEX.matches(tool.functionName)) {
                return "El nombre de la función de la herramienta es ilegal.: ${tool.functionName}(Solo letras, números, guiones bajos y $y no puede comenzar con un número)"
            }
            if (!definesFunction(request.code, tool.functionName)) {
                return "No hay ninguna función de herramienta definida en el código del complemento. ${tool.functionName}(requiere declaración de función de nivel superior)"
            }
        }
        // v2.x(Versión Sandbox): las habilidades ya no se rechazan según una lista fija, solo se verifica que el formato del nombre sea legal.
        val invalidCapability = request.capabilities.firstOrNull { !PluginSecurityGate.isValidCapabilityName(it) }
        if (invalidCapability != null) {
            return "El formato del nombre de la capacidad declarado por el complemento es ilegal: $invalidCapability"
        }
        return null
    }

    /**
     * Determinar si hay una función de nivel superior en el código de entrada [functionName].
     *
     * con `tools/market-signer/sign.py` El alcance de la verificación es el mismo: desde el principio de la línea (o inmediatamente después de una nueva línea) hasta el nombre de la función.
     * Sólo espacios en blanco y `function` Palabras clave. Las declaraciones de funciones con sangría comunes en los modelos no cuentan como funciones de nivel superior.
     * Porque el motor de complementos solo exporta funciones de nivel superior.
     */
    fun definesFunction(code: String, functionName: String): Boolean =
        Regex("(^|\n)[ \t]*function\\s+" + Regex.escape(functionName) + "\\s*\\(").containsMatchIn(code)

    /**
     * Construya un manifiesto por solicitud de modelo.
     *
     * `trust` Zona de pruebas fija,`kind` Herramienta fija, entrada fija. [ENTRY_FILE];`enabled=true`
     * Solo significa "se puede habilitar una vez confirmado", y la ruta de instalación aún deshabilita por la fuerza la etapa de borrador.
     * (PluginManager Solo se habilita cuando se confirma la instalación).
     */
    fun manifestOf(request: AuthoredPluginRequest): PluginManifest = PluginManifest(
        id = request.id,
        name = request.name,
        version = request.version,
        description = request.description,
        author = "",
        entry = ENTRY_FILE,
        kind = "tool",
        trust = PluginSecurityGate.EXTERNAL_TRUST,
        capabilities = request.capabilities,
        permissions = emptyList(),
        activationEvents = listOf("onStartup"),
        enabled = true,
        tools = request.tools,
        signature = null,
    )

    /** Cree un paquete ZIP con el host;[extraFiles] Solo se usa para reempaquetar contenido instalado (ruta habilitada para firma). */
    fun buildZip(manifest: PluginManifest, entryCode: String, extraFiles: Map<String, String> = emptyMap()): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(
                AppJson.encodeToString(PluginManifest.serializer(), manifest).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(manifest.entry))
            zip.write(entryCode.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            extraFiles.toSortedMap().forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private const val MANIFEST_ENTRY = "manifest.json"
    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val FUNCTION_NAME_REGEX = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]{0,63}$")
}
