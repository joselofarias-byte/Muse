@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.zer0.muse.data.plugin

import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Tipo de elemento de configuración de complemento.
 *
 * string / boolean / number / select Los cuatro tipos básicos cubren la mayoría de los escenarios de configuración de complementos.
 */
@Serializable
enum class ConfigItemType {
    /** Entrada de texto libre. */
    string,

    /** Conmutador booleano. */
    boolean,

    /** Entrada numérica (número entero o de punto flotante). */
    number,

    /** Selección de enumeración desplegable;[SelectConfigItem.options] Proporcionar valores candidatos. */
    select,
}

/**
 * Descripción de los valores candidatos para la selección desplegable.
 *
 * @param value El valor realmente escrito en la configuración (LLM/JS valor visto)
 * @param label Etiqueta que se muestra al usuario (puede estar vacía, recurrir a [value])
 */
@Serializable
data class SelectOption(
    val value: String,
    val label: String = value,
)

/**
 * Una declaración de elemento de configuración única (de manifest.contributes.configuration).
 *
 * @param key        identificador único,LLM llamar host.getConfig(key) usado cuando
 * @param type       tipo de valor
 * @param defaultVal Valor predeterminado cuando el usuario no lo establece manualmente
 * @param description Instrucciones de uso (LLM Determine si llamar y cómo pasar parámetros en función de esto)
 * @param options      solo type=select significativo; otros tipos son ignorados
 */
@Serializable
data class ConfigItem(
    val key: String,
    val type: ConfigItemType = ConfigItemType.string,
    val defaultVal: JsonElement? = null,
    val description: String = "",
    val options: List<SelectOption> = emptyList(),
)

/**
 * Sobre de firma del editor del complemento externo.
 *
 * [publicKey] y [signature] Ambos son Base64 estándar sin nuevas líneas: la clave pública es X.509
 * SubjectPublicKeyInfo, la firma es `SHA256withECDSA` Codificación DER. La clave pública solo describe
 * La identidad del editor no implica confianza; La confianza está determinada por la raíz de confianza local en el almacenamiento privado de Android.
 */
@Serializable
data class PluginSignature(
    val publisherId: String,
    val publicKey: String,
    val signature: String,
    val algorithm: String = "SHA256withECDSA",
)

/**
 * Lista de complementos.
 *
 * Cada complemento declara metadatos, capacidades, eventos de activación, archivos de entrada y listas de herramientas a través del manifiesto.
 * externo `.muse-plugin` / ZIP El paquete debe contener esta lista;`PluginManifest.BUILT_IN` Solo para mostrar capacidades integradas.
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "0.1.0",
    val description: String = "",
    /** Autor/Fuente. */
    val author: String = "",
    /** Versión mínima compatible de la aplicación(versión semántica,Actualmente no existe ningún bloqueo forzado.,Reservar campos para verificación futura). */
    val minAppVersion: String = "1.0.0",
    /** JS Nombre del archivo de entrada(relativo a la raíz del paquete,Predeterminado main.js). */
    val entry: String = "main.js",
    /**
     * v2.7 Cuando el complemento se está ejecutando:`js`(Por defecto,WebView V8 caja de arena) o `python`(Tiempo de ejecución de Python integrado).
     *
     * para `python` tiempo [entry] debería señalar a `.py` archivos,[tools] de [ToolDeclaration.functionName]
     * Corresponde a la función definida en el módulo de entrada de Python (acepta un parámetro dict).
     * Restricciones de compatibilidad de firmas: los valores predeterminados no participan en la serialización (@EncodeDefault(NEVER))——
     * El manifiesto del paquete de mercado firmado existente no contiene este campo. Si el valor predeterminado está codificado, la verificación de la firma fallará.
     * (El mismo motivo que los ganchos/toolCards, consulte las lecciones aprendidas de la v2.0.0).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val runtime: String = "js",
    /** Tipo de complemento: tool / ui-skin / provider. */
    val kind: String = "tool",
    /** nivel de confianza: Los elementos integrados están disponibles con acceso completo; Los ZIP externos deben estar en un espacio aislado. */
    val trust: String = "sandboxed",
    /** Ya sea para ocultarse en la interfaz de usuario. */
    val hidden: Boolean = false,
    /** capacidades declaradas (resource.read / network / storage.read / storage.write / clipboard.read /
     *  clipboard.write / notify / device.info / ui / ui.mood / ui.skin). */
    val capabilities: List<String> = emptyList(),
    /** Permisos declarados(Alinearse con las capacidades,Uso adicional para verificación de listas maliciosas). */
    val permissions: List<String> = emptyList(),
    /** evento de activación: onStartup / onCommand / onFileType. */
    val activationEvents: List<String> = listOf("onStartup"),
    /**
     * v2.x(Plug-in Hook System MVP): El punto de gancho al que se conectará el complemento.
     * Ahora soporta:"prompt_finalize"(Sugerencias de palabras para reescribir la historia antes de finalizar).
     * Restricciones de compatibilidad de firmas: valor predeterminado**No participa en la serialización JSON.**(@EncodeDefault(NEVER), con
     * toolCards Misma razón)—— El manifiesto del paquete de mercado firmado existente no contiene este campo. Si se utiliza la lista vacía,
     * La codificación hará que todos los paquetes existentes no superen la verificación de firma.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hooks: List<String> = emptyList(),
    /** Si está habilitado. */
    val enabled: Boolean = true,
    /** Lista de herramientas expuestas por el complemento(LLM invocable,Agregue el prefijo pluginId al registrarse). */
    val tools: List<ToolDeclaration> = emptyList(),
    /** Sobre firmado por el editor; Los paquetes antiguos a los que les falta este campo se tratan como no firmados y no son de confianza automáticamente. */
    val signature: PluginSignature? = null,
    /**
     * Lista de elementos de configuración declarados por el complemento.
     *
     * El host presenta el formulario de configuración de acuerdo con este esquema en la página de administración del complemento; en tiempo de ejecución, el complemento puede pasar
     * host.getConfig(key) Lea el valor ingresado por el usuario (valor predeterminado o valor personalizado guardado).
     * Cuando el antiguo manifiesto del complemento no contiene este campo, vuelve a una lista vacía, que es totalmente compatible con versiones anteriores.
     */
    val contributes: PluginContributes? = null,
    /**
     * v1.0.92: Panel de interfaz de usuario del complemento — Ruta relativa al archivo HTML dentro del paquete del complemento(Como por ejemplo "ui/panel.html").
     *
     * Después de la declaración,Proporcionado por la página de administración de complementos"panel abierto"entrada,Renderice la interfaz en modo de solo lectura;
     * Se utiliza para contribuir con tableros personalizados./Interfaz de informe. Los complementos antiguos que no contienen este campo no se ven afectados.
     */
    val uiPanel: String? = null,
    /**
     * v2.0: Tarjeta de herramientas enchufable — Nombre de la herramienta → Ruta relativa del archivo HTML de la tarjeta(Como por ejemplo "cards/stock.html").
     *
     * Después de la declaración,La tarjeta de visita de esta herramienta complementaria se proporciona en el chat."ver tarjeta"entrada,
     * Renderizar en modo de solo lectura HTML;Los parámetros de llamada y los resultados se inyectan a través de window.__TOOL_CARD__.
     *
     * Restricciones de compatibilidad de firmas: valor predeterminado**No participa en la serialización JSON.**(@EncodeDefault(NEVER), con
     * La carga útil de la firma del editor de los paquetes de mercado publicados permanece constante a nivel de bytes); cuando se declaran valores no nulos, estarán completamente involucrados en la firma
     * con resumen de contenido. Cualquier campo opcional nuevo debe seguir las mismas reglas. —— Una vez que el valor predeterminado ingresa a la serialización,
     * Todos los paquetes firmados existentes fallarán inmediatamente en la verificación de firma (v2.0.0 Se codificaron tarjetas de herramientas vacías, lo que resultó en
     * Mercado oficial de todos los informes de complementos."La firma del editor no coincide con el contenido del complemento"). Al cambiar el conjunto de campos, se debe sincronizar.
     * tools/market-signer/sign.py Y vuelva a ejecutar MarketSigningFixtureTest.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolCards: Map<String, String> = emptyMap(),
) {
    companion object {
        val BUILT_IN: List<PluginManifest> = listOf(
            PluginManifest(
                id = "image-gen",
                name = "Generación de imágenes",
                description = "apoyo OpenAI DALL-E / Gemini Imagen Generación de imágenes",
                trust = "full-access",
                capabilities = listOf("network", "resource.write"),
            ),
            PluginManifest(
                id = "beautify",
                name = "mejora estética",
                description = "Markdown Representación y embellecimiento, descripción del estilo de portada.",
                capabilities = listOf("resource.read"),
            ),
            PluginManifest(
                id = "media",
                name = "Procesamiento de medios",
                description = "Audio/vídeo/Análisis y procesamiento de documentos.",
                capabilities = listOf("resource.read", "resource.write"),
            ),
            PluginManifest(
                id = "mcp-bridge",
                name = "MCP puente",
                description = "Model Context Protocol conector externo",
                trust = "full-access",
                capabilities = listOf("network"),
                activationEvents = listOf("onStartup"),
            ),
            PluginManifest(
                id = "office",
                name = "herramientas de oficina",
                description = "PDF/Documentación/Procesamiento de formularios",
                capabilities = listOf("resource.read", "resource.write"),
            ),
        )
    }
}

/**
 * Combinación de manifiestos de complementos/Ampliar bloque de declaración.
 *
 * Actualmente sólo se admite el subbloque de configuración; Las máscaras de interfaz de usuario, la escucha de eventos y otras declaraciones se pueden ampliar en el futuro.
 */
@Serializable
data class PluginContributes(
    /** Lista de elementos de configuración, cada elemento corresponde a un parámetro ajustable por el usuario. */
    val configuration: List<ConfigItem> = emptyList(),
)
