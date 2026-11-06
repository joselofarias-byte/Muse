package io.zer0.ai.plugin

import androidx.compose.runtime.mutableStateMapOf
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * P2-10: Provider Centro de registro de complementos.
 *
 * Mantener una lista de complementos a nivel de memoria(usar [mutableStateMapOf] Permitir que Compose observe directamente las adiciones y eliminaciones),
 * responsable:
 *  - Registrar/cancelar registro del complemento
 *  - Cargar complemento desde el archivo JSON local(SAF Vaya a este método después de seleccionar el archivo)
 *  - Convertir el complemento a [ProviderConfig],Habilítelo para que lo utilicen los sistemas de proveedores existentes.(
 *    [io.zer0.ai.ProviderRegistry] / [io.zer0.ai.ChatService])Usar directamente
 *
 * Compensaciones de diseño:
 *  - Solo controlador de configuración JSON,No ejecutar JS sandbox(Versión simplificada)
 *  - Transferido por complemento [ProviderConfig] siempre ve [ProviderType.OPENAI] Acuerdo,
 *    Porque OpenAI Chat Completions es el estándar de compatibilidad de facto
 *  - v1.134 P1-3: persistir en [storageDir](Inyectado por la persona que llama filesDir/muse_plugins/),
 *    App Restaurar automáticamente después del reinicio;Escanee sincrónicamente el directorio y cargue todos los archivos .json durante la construcción,
 *    [register] / [unregister] Sincronizar el disco con el archivo correspondiente.
 *
 * @param storageDir directorio de persistencia;null Indica modo de sólo memoria(compatible con versiones anteriores)
 */
class ProviderPluginRegistry(
    private val storageDir: File? = null,
) {

    /** tabla de complementos:id → complemento.Compose Puede observar las adiciones y eliminaciones de este mapa. */
    private val plugins = mutableStateMapOf<String, ProviderPlugin>()

    /** JSON analizador:Tolerancia a fallos para campos desconocidos,Evite fallos cuando los autores de complementos agreguen metadatos adicionales. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    init {
        // v1.134 P1-3: Escanear al iniciar storageDir,Cargue automáticamente todos los complementos persistentes
        storageDir?.let { dir ->
            runCatching {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                        ?.forEach { file ->
                            runCatching {
                                val plugin = json.decodeFromString<ProviderPlugin>(file.readText())
                                validate(plugin)
                                // escribe directamente map,bypass register() lógica de colocación(El archivo ya existe,No es necesario volver a escribir)
                                plugins[plugin.id] = plugin
                                Logger.i(TAG, "Complemento de persistencia cargado: ${plugin.id} (${plugin.displayName})")
                            }.onFailure { e ->
                                Logger.e(TAG, "No se pudo cargar el archivo del complemento: ${file.absolutePath}", e)
                            }
                        }
                }
            }.onFailure { e ->
                Logger.e(TAG, "Error al escanear el directorio del complemento: ${dir.absolutePath}", e)
            }
        }
    }

    /**
     * Registre el complemento. Anular con la misma identificación.
     * si [storageDir] No vacío,Escriba sincrónicamente el JSON del complemento ${id}.json(Cubierto).
     */
    fun register(plugin: ProviderPlugin) {
        plugins[plugin.id] = plugin
        persist(plugin)
        Logger.i(TAG, "El complemento está registrado: ${plugin.id} (${plugin.displayName})")
    }

    /**
     * Cancelar el registro del complemento. Ignorado silenciosamente si no está presente.
     * si [storageDir] No vacío,Eliminación sincrónica correspondiente ${id}.json archivo.
     */
    fun unregister(pluginId: String) {
        val removed = plugins.remove(pluginId)
        if (removed != null) {
            removePersistedFile(pluginId)
            Logger.i(TAG, "El complemento ha sido desconectado: $pluginId")
        }
    }

    /**
     * Listar todos los complementos registrados(Ordenar por identificación,Garantizar la estabilidad de la lista de UI).
     */
    fun list(): List<ProviderPlugin> = plugins.values.sortedBy { it.id }

    /**
     * Cargue un archivo JSON local y regístrelo como complemento.
     *
     * La persona que llama suele utilizar SAF (ActivityResultContracts.OpenDocument) archivo seleccionado,
     * Copie Uri al directorio de caché para obtener [File],Luego páselo a este método.
     *
     * escenario de falla(Regresar Result.failure):
     *  - Falló la lectura del archivo
     *  - JSON El análisis falló
     *  - id / displayName / baseUrl esta vacio
     *  - apiKeyPattern No es una regla legal
     *
     * Cuando tiene éxito, el complemento tiene [register] a la tabla de memoria y persistió,Devuelve la instancia del complemento directamente.
     */
    suspend fun loadFromFile(file: File): Result<ProviderPlugin> = withContext(Dispatchers.IO) {
        // P2-28: JSON Lectura directa sin límite superior de tamaño OOM — Bloquee primero los archivos de gran tamaño
        if (file.length() > MAX_PROVIDER_JSON_BYTES) {
            Logger.w(TAG, "Provider El complemento JSON supera el límite de tamaño(${file.length()} > $MAX_PROVIDER_JSON_BYTES): ${file.absolutePath}")
            return@withContext Result.failure(
                IllegalArgumentException("Provider El complemento JSON supera el límite de tamaño(${MAX_PROVIDER_JSON_BYTES / 1024}KB)"),
            )
        }
        val r = resultOf {
            val text = file.readText()
            val plugin = json.decodeFromString<ProviderPlugin>(text)
            validate(plugin)
            register(plugin)
            plugin
        }
        r.onError { _, t -> Logger.e(TAG, "No se pudo cargar el complemento: ${file.absolutePath}", t) }
        when (r) {
            is io.zer0.common.Result.Success -> Result.success(r.data)
            is io.zer0.common.Result.Error -> Result.failure(r.throwable ?: IllegalStateException(r.message))
        }
    }

    /**
     * Convertir el complemento a [ProviderConfig],Hacerlo utilizable por los sistemas de proveedores existentes.
     *
     * - [ProviderType] fijo a OPENAI(El complemento JSON describe el protocolo compatible con OpenAI)
     * - [ProviderConfig.category] = [ProviderCategory.CUSTOM](Definido por el usuario)
     * - apiKey dejar en blanco,por [io.zer0.muse.ui.settings.ProviderEditPage] Complete cuando el usuario edite
     * - v1.134 P2-2: headers / requestTemplate / responsePath / streamResponsePath
     *   Todos han sido transmitidos de forma transparente a [ProviderSpecificConfig.Custom] Campo con el mismo nombre;ai Implementación del módulo por parte del proveedor.
     *   Estos campos se pueden utilizar más adelante para implementar plantillas de solicitud personalizadas y análisis de rutas de respuesta.
     */
    fun toProviderConfig(plugin: ProviderPlugin): ProviderConfig {
        val custom = io.zer0.ai.core.ProviderSpecificConfig.Custom(
            chatCompletionsPath = "",
            customHeaders = plugin.headers,
            requestTemplate = plugin.requestTemplate,
            responsePath = plugin.responsePath,
            streamResponsePath = plugin.streamResponsePath,
        )
        return ProviderConfig(
            id = "plugin-${plugin.id}",
            displayName = plugin.displayName,
            type = ProviderType.OPENAI,
            baseUrl = plugin.baseUrl,
            apiKey = "",
            models = plugin.models.map { it.toModel(plugin.id) },
            enabled = true,
            builtIn = false,
            category = ProviderCategory.CUSTOM,
            specific = custom,
        )
    }

    /** Verificar la integridad del campo del complemento,tirar cuando es ilegal [IllegalArgumentException]. */
    private fun validate(plugin: ProviderPlugin) {
        require(plugin.id.isNotBlank()) { "La identificación del complemento no puede estar vacía" }
        require(plugin.displayName.isNotBlank()) { "El nombre para mostrar del complemento no puede estar vacío" }
        require(plugin.baseUrl.isNotBlank()) { "La baseUrl del complemento no puede estar vacía" }
        // Seguridad del nombre de archivo:id Usado directamente como nombre de archivo,No permitir separadores de ruta y caracteres nulos
        require(!plugin.id.contains('/') && !plugin.id.contains('\\') && !plugin.id.contains('\u0000')) {
            "La identificación del complemento no puede contener separadores de ruta: ${plugin.id}"
        }
        if (plugin.apiKeyPattern.isNotBlank()) {
            // Verificar previamente la legalidad de la regularidad.,Evite que el usuario complete la apiKey solo para descubrir que el patrón no se puede compilar
            try {
                Regex(plugin.apiKeyPattern)
            } catch (e: Exception) {
                throw IllegalArgumentException("El complemento apiKeyPattern no es un patrón regular legal: ${plugin.apiKeyPattern}", e)
            }
        }
    }

    /** [PluginModel] → [Model] Convertir. */
    private fun PluginModel.toModel(providerId: String): Model = Model(
        id = id,
        name = displayName,
        providerId = providerId,
        contextWindow = contextWindow,
        supportsVision = supportsVision,
        abilities = if (supportsTools) setOf(ModelAbility.TOOL) else emptySet(),
    )

    /** v1.134 P1-3: Escribir complemento JSON storageDir/${id}.json(Colocación sincrónica). */
    private fun persist(plugin: ProviderPlugin) {
        val dir = storageDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${plugin.id}.json")
            file.writeText(json.encodeToString(ProviderPlugin.serializer(), plugin))
        }.onFailure { e ->
            Logger.e(TAG, "El complemento de persistencia falló: ${plugin.id}", e)
        }
    }

    /** v1.134 P1-3: Eliminar storageDir/${id}.json(si existe). */
    private fun removePersistedFile(pluginId: String) {
        val dir = storageDir ?: return
        runCatching {
            val file = File(dir, "$pluginId.json")
            if (file.exists()) file.delete()
        }.onFailure { e ->
            Logger.e(TAG, "No se pudo eliminar el archivo del complemento: $pluginId", e)
        }
    }

    companion object {
        internal const val TAG = "ProviderPluginRegistry"

        /** P2-28: Provider Límite de tamaño de importación JSON del complemento(1MB). */
        const val MAX_PROVIDER_JSON_BYTES: Long = 1_048_576L
    }
}
