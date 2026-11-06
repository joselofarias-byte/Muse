package io.zer0.muse.data.preset

import android.content.Context
import io.zer0.ai.core.Model
import io.zer0.ai.core.VisionCapabilities
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * P1 Directorio de modelos (mantenible localmente).
 *
 * Objetivo: resolver el contexto y la caducidad del manifiesto del modelo codificado de PresetProviders.kt/El problema de la capacidad inexacta.
 *
 * Tres capas de datos:
 * 1. Valor predeterminado incorporado: por [builtinEntries] Proporcionado (publicado con la versión, predeterminado en el código).
 * 2. Directorio de la nube: por [cloudCatalog] proporcionado [io.zer0.ai.core.ModelCatalog](app inyección de capa,
 *    Los datos provienen de `assets/model-catalog.json` o caché remoto) aplanado.**Alinear solo la lista de modelos de proveedores**,
 *    Los modelos de proveedores declarativos (como Codex) se vacían de forma predeterminada y su inventario está determinado completamente por el directorio de la nube.
 * 3. Cobertura de usuarios:`filesDir/model_catalog/user_models.json`, campos corregidos manualmente por el usuario.
 * 4. Directorio remoto:`filesDir/model_catalog/remote_models.json`, por [refreshRemote] de [remoteUrl] Jalar.
 *    **Desactivado de forma predeterminada**:[remoteUrl] Cuando es una cadena vacía, no se enviará ninguna solicitud de red y no se aplicará la capa remota.
 *
 * Fusionar reglas (prioridad de usuario):
 * - Prioridad de fusión: usuarios > nube > extremo lejano > Integrado (nivel de campo, el nivel alto solo cubre los campos no nulos declarados por él mismo).
 * - Mismo proveedorId + modeloId: los campos cubiertos por el usuario tienen prioridad, los campos descubiertos vuelven al nivel alto y, finalmente, vuelven al nivel integrado.
 * - Modelo añadido por el usuario: reservado;builtInRemoved=true Indica el modelo oculto de esta capa.
 *
 * Falta alguna capa/El error no afectará a la capa anterior: si la extracción remota falla, se conservarán la caché existente y el directorio integrado.
 * Solo iniciar sesión (reversión silenciosa).
 */
class ModelCatalogStore(
    private val context: Context,
    /**
     * URL del directorio de modelo remoto (JSON, ver formato [RemoteCatalog]).
     *
     * cadena vacía = No habilitado (predeterminado):[refreshRemote] Regrese inmediatamente sin realizar ninguna solicitud de red,
     * [entries] La capa remota tampoco se aplicará. Habilitar un escenario requiere que la persona que llama pase explícitamente la URL.
     */
    private val remoteUrl: String = "",
    /**
     * Proveedor de catálogo de modelos en la nube (perezoso, sincrónico).
     *
     * Devolver capa de aplicación por [io.zer0.muse.data.catalog.ModelCatalogRepository] cargado
     * [io.zer0.ai.core.ModelCatalog](Activo integrado o caché remoto).
     * No habilite esta capa cuando sea nula/devuelva una biblioteca vacía.
     *
     * v2.6.0: La lista de modelos (incluidos los modelos más recientes de proveedores de OAuth como Codex) está sujeta al directorio de la nube.
     * **No preestablecido en el código** —— La publicación de un nuevo catálogo simplemente reemplaza el JSON del lado del servidor.
     */
    private val cloudCatalog: (() -> io.zer0.ai.core.ModelCatalog)? = null,
) {

    @Serializable
    data class ModelCatalogEntry(
        val providerId: String,
        val modelId: String,
        val displayName: String? = null,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsVision: Boolean? = null,
        val supportsStreaming: Boolean? = null,
        val supportsVideo: Boolean? = null,
        val supportsTools: Boolean? = null,
        val supportsReasoning: Boolean? = null,
        val inputModalities: Set<String>? = null,
        val outputModalities: Set<String>? = null,
        val visionCapabilities: VisionCapabilities? = null,
        val updatedAt: Long = 0L,
        val userEdited: Boolean = false,
        val builtInRemoved: Boolean = false,
    )

    @Serializable
    private data class UserOverrides(
        val items: List<ModelCatalogEntry> = emptyList(),
        val updatedAt: Long = 0L,
    )

    /**
     * Formato de documento de directorio remoto(JSON):schemaVersion / publishedAt / items tres secciones,
     * con incorporado/Anulación de usuario compartida [ModelCatalogEntry], fusión a nivel de campo.
     */
    @Serializable
    data class RemoteCatalog(
        val schemaVersion: Int = 1,
        val publishedAt: String = "",
        val items: List<ModelCatalogEntry> = emptyList(),
    )

    private val catalogDir: File
        get() = File(context.filesDir, "model_catalog")

    private val userOverridesFile: File
        get() = File(catalogDir, "user_models.json")

    /** Caché local del directorio remoto(Realice el pedido después de realizar un extracción exitosa). */
    private val remoteCatalogFile: File
        get() = File(catalogDir, "remote_models.json")

    @Volatile
    private var userCache: UserOverrides? = null

    /** Caché de memoria del directorio remoto(null Indica no cargado o no existe). */
    @Volatile
    private var remoteCache: RemoteCatalog? = null

    /**
     * Para aplanar el caché del directorio de la nube, presione [io.zer0.ai.core.ModelCatalog.publishedAt] Inválido.
     *
     * Evite volver a recorrer miles de entradas de directorio cada vez que se proyecta el modelo.
     */
    @Volatile
    private var cloudEntriesCache: Pair<String, List<ModelCatalogEntry>>? = null

    /** Si el directorio remoto ha intentado leer el disco(Evite registros repetidos de errores de análisis y E/S repetidos). */
    @Volatile
    private var remoteLoaded: Boolean = false

    /**
     * Directorio de modelo predeterminado incorporado (publicado con la versión).
     *
     * Solo cubre la corriente principal/Modelos de uso común, la interfaz de usuario se muestra cuando faltan campos“No incluido/desconocido”,
     * No fuerce las conjeturas y evite enviar un contexto de error al proveedor.
     */
    fun builtinEntries(): List<ModelCatalogEntry> = buildList {
        // OpenAI
        add(entry("openai", "gpt-4o", "GPT-4o", 128_000, 16_384, vision = true, tools = true))
        add(entry("openai", "gpt-4o-mini", "GPT-4o mini", 128_000, 16_384, vision = true, tools = true))
        add(entry("openai", "gpt-4.1", "GPT-4.1", 1_047_576, 32_768, vision = true, tools = true))
        add(entry("openai", "gpt-4.1-mini", "GPT-4.1 mini", 1_047_576, 32_768, vision = true, tools = true))
        add(entry("openai", "o3", "o3", 200_000, 100_000, tools = true, reasoning = true))
        add(entry("openai", "o4-mini", "o4-mini", 200_000, 100_000, vision = true, tools = true, reasoning = true))
        // Anthropic
        add(
            entry(
                "anthropic",
                "claude-sonnet-4-5-20250514",
                "Claude Sonnet 4.5",
                200_000,
                64_000,
                vision = true,
                tools = true,
                reasoning = true,
            ),
        )
        add(
            entry(
                "anthropic",
                "claude-opus-4-1-20250805",
                "Claude Opus 4.1",
                200_000,
                32_000,
                vision = true,
                tools = true,
                reasoning = true,
            ),
        )
        add(entry("anthropic", "claude-haiku-4-5-20251001", "Claude Haiku 4.5", 200_000, 64_000, vision = true, tools = true))
        // Gemini
        add(entry("gemini", "gemini-2.5-pro", "Gemini 2.5 Pro", 1_048_576, 65_536, vision = true, tools = true, reasoning = true))
        add(entry("gemini", "gemini-2.5-flash", "Gemini 2.5 Flash", 1_048_576, 65_536, vision = true, tools = true, reasoning = true))
        // DeepSeek
        add(entry("deepseek", "deepseek-chat", "DeepSeek Chat", 64_000, 8_192, tools = true))
        add(entry("deepseek", "deepseek-reasoner", "DeepSeek Reasoner", 64_000, 8_192, reasoning = true))
        // significado general
        add(entry("qwen", "qwen-plus", "Qwen Plus", 131_072, 8_192, tools = true))
        add(entry("qwen", "qwen-max", "Qwen Max", 32_000, 8_192, vision = true, tools = true))
        // espectro de sabiduría
        add(entry("zhipu", "glm-4-plus", "GLM-4 Plus", 128_000, 8_192, tools = true))
        add(entry("zhipu", "glm-4-flash", "GLM-4 Flash", 128_000, 8_192, tools = true))
        // Kimi
        add(entry("moonshot", "kimi-k2", "Kimi K2", 128_000, 16_384, tools = true))
        add(entry("moonshot", "kimi-k2.7", "Kimi K2.7", 256_000, 16_384, tools = true, reasoning = true))
        // bolsa de frijol
        add(entry("doubao", "doubao-pro-32k", "Doubao Pro 32K", 32_000, 4_096, tools = true))
        // xAI
        add(entry("xai", "grok-4", "Grok 4", 256_000, 32_768, vision = true, tools = true))
        add(entry("xai", "grok-4-heavy", "Grok 4 Heavy", 256_000, 32_768, vision = true, tools = true, reasoning = true))
        // Ollama Modelos locales comunes
        add(entry("ollama", "llama3.1", "Llama 3.1", 128_000, null, tools = true))
        add(entry("ollama", "qwen2.5", "Qwen 2.5", 131_072, null, tools = true))
        // SiliconFlow Gratis y de uso común
        add(entry("siliconflow", "deepseek-ai/DeepSeek-V3", "DeepSeek V3", 64_000, 8_192, tools = true))
        add(entry("siliconflow", "Qwen/Qwen2.5-7B-Instruct", "Qwen 2.5 7B", 131_072, 8_192, tools = true))
    }

    /**
     * Devuelve el directorio completo después de la combinación de anulaciones de usuario integradas.
     *
     * @param providerId Si está vacío, se devolverá todo; si no está vacío, sólo se devolverá al proveedor.
     */
    fun entries(providerId: String? = null): List<ModelCatalogEntry> {
        val merged = merge(builtinEntries(), loadRemoteEntries(), cloudEntries(), loadUserOverrides())
        return merged.filter { providerId == null || it.providerId.equals(providerId, ignoreCase = true) }
    }

    /** Consulta exacta basada en Id. de proveedor + Id. de modelo (incluida la cobertura remota y de usuario). */
    fun find(providerId: String, modelId: String): ModelCatalogEntry? =
        entries(providerId).firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }

    /** Si el directorio remoto está habilitado (falso cuando no se configura ninguna URL, no se iniciará ninguna solicitud de red). */
    fun isRemoteEnabled(): Boolean = remoteUrl.isNotBlank()

    /** El directorio remoto actualmente efectivo (prioridad de caché); no habilitado/desaparecido/Devuelve nulo si falla el análisis. */
    fun remoteCatalog(): RemoteCatalog? {
        if (remoteUrl.isBlank()) return null
        remoteCache?.let { return it }
        if (remoteLoaded) return null
        remoteLoaded = true
        if (!remoteCatalogFile.exists()) return null
        val loaded = runCatching {
            AppJson.decodeFromString(RemoteCatalog.serializer(), remoteCatalogFile.readText())
        }.onFailure { Logger.w(TAG, "Error al analizar el directorio del modelo remoto,Procesar como vacío", it) }.getOrNull()
        remoteCache = loaded
        return loaded
    }

    private fun loadRemoteEntries(): List<ModelCatalogEntry> = remoteCatalog()?.items.orEmpty()

    /**
     * Capa de directorio en la nube (desde [io.zer0.muse.data.catalog.ModelCatalogRepository],assets/remoto).
     *
     * poner `providers: providerId → (modelId → Habilidad)` La estructura anidada se aplana para formar entradas planas de la tienda.
     * prensa [io.zer0.ai.core.ModelCatalog.publishedAt] Caché, recalcula automáticamente después de actualizar el directorio.
     */
    private fun cloudEntries(): List<ModelCatalogEntry> {
        val catalog = cloudCatalog?.invoke()
        val cached = cloudEntriesCache
        return when {
            catalog == null || catalog.providers.isEmpty() -> emptyList()
            cached != null && cached.first == catalog.publishedAt -> cached.second
            else -> flattenCatalog(catalog).also { cloudEntriesCache = catalog.publishedAt to it }
        }
    }

    /** Aplane los directorios de capacidades anidados en entradas planas para la tienda. */
    private fun flattenCatalog(catalog: io.zer0.ai.core.ModelCatalog): List<ModelCatalogEntry> = buildList {
        for ((providerId, models) in catalog.providers) {
            for ((modelId, entry) in models) {
                add(
                    ModelCatalogEntry(
                        providerId = providerId,
                        modelId = modelId,
                        displayName = entry.name.ifBlank { null },
                        contextWindow = entry.context?.toInt(),
                        maxOutputTokens = entry.maxOutput?.toInt(),
                        supportsVision = entry.image,
                        supportsTools = entry.toolUse?.supportsTools,
                        supportsReasoning = entry.reasoning,
                    ),
                )
            }
        }
    }

    /**
     * de [remoteUrl] Extraiga el directorio remoto y guárdelo en caché.
     *
     * - URL no configurada ([isRemoteEnabled]=false), regresar inmediatamente,**No realices ninguna solicitud de red.**(desactivado de forma predeterminada);
     * - red/Fallo de análisis: conserve el caché existente y los directorios integrados, solo registre (reversión silenciosa);
     * - [publishedAt] consistente con la actualidad y no [forceFresh] El disco no se escribirá repetidamente.
     */
    suspend fun refreshRemote(forceFresh: Boolean = false): RemoteRefreshResult = withContext(Dispatchers.IO) {
        val url = remoteUrl.trim()
        if (url.isEmpty()) {
            return@withContext RemoteRefreshResult(enabled = false, ok = false, updated = false, message = "El directorio remoto no está habilitado")
        }
        val text = try {
            httpGet(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo extraer el directorio remoto: ${e.message}")
            null
        }
        if (text.isNullOrBlank()) {
            return@withContext RemoteRefreshResult(enabled = true, ok = false, updated = false, message = "Error en la solicitud de red")
        }
        val parsed = runCatching {
            AppJson.decodeFromString(RemoteCatalog.serializer(), text)
        }.onFailure { Logger.w(TAG, "El formato del directorio remoto es ilegal.: ${it.message}") }.getOrNull()
        if (parsed == null || parsed.items.isEmpty()) {
            return@withContext RemoteRefreshResult(enabled = true, ok = false, updated = false, message = "El directorio remoto está vacío o tiene un formato no válido.")
        }
        val currentPublished = remoteCatalog()?.publishedAt.orEmpty()
        if (!forceFresh && currentPublished.isNotBlank() && currentPublished == parsed.publishedAt) {
            return@withContext RemoteRefreshResult(enabled = true, ok = true, updated = false, message = "Ya lo ultimo(${parsed.publishedAt})")
        }
        runCatching {
            catalogDir.mkdirs()
            remoteCatalogFile.writeText(text)
            remoteCache = parsed
            remoteLoaded = true
            Logger.i(TAG, "El directorio de modelos remotos ha sido actualizado.: ${parsed.publishedAt}, ${parsed.items.size} Artículo")
        }.onFailure { e ->
            Logger.w(TAG, "No se pudo escribir en el directorio del modelo remoto: ${e.message}")
            return@withContext RemoteRefreshResult(enabled = true, ok = false, updated = false, message = "Falló la escritura en caché")
        }
        RemoteRefreshResult(enabled = true, ok = true, updated = true, message = "ha sido actualizado a ${parsed.publishedAt}")
    }

    /** Resumen del estado del directorio remoto (página de configuración/con fines de diagnóstico). */
    fun remoteStatus(): RemoteStatus {
        val catalog = remoteCatalog()
        return RemoteStatus(
            enabled = isRemoteEnabled(),
            url = remoteUrl,
            publishedAt = catalog?.publishedAt.orEmpty(),
            itemCount = catalog?.items?.size ?: 0,
        )
    }

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = REMOTE_TIMEOUT_MS
            conn.readTimeout = REMOTE_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Entradas del directorio del proyecto en la lista de modelos de tiempo de ejecución.
     *
     * Los metadatos del catálogo se aplicarán a los modelos de proveedores existentes; Los modelos agregados por el usuario en el catálogo también se incluirán en la lista.
     * De esta manera“Mantenido en el directorio de modelos.”con“selector de modelo/La solicitud fue realmente utilizada.”Mantén los mismos datos.
     */
    fun mergeIntoModels(providerId: String, models: List<Model>, runtimeProviderId: String = providerId): List<Model> {
        val catalogEntries = entries(providerId)
            .associateBy { keyOf(providerId, it.modelId) }
        val adoptCatalogFully = providerId.trim().lowercase() in CATALOG_AUTHORITATIVE_PROVIDERS
        val result = LinkedHashMap<String, Model>()
        if (!adoptCatalogFully) {
            for (model in models) {
                val entry = catalogEntries[keyOf(providerId, model.id)]
                if (entry?.builtInRemoved == true) continue
                // Conserve la identidad del proveedor de tiempo de ejecución (por ejemplo, preset_openai) y utilice únicamente el ID del proveedor del directorio para realizar coincidencias.
                result[model.id.trim().lowercase()] = model.applyCatalogEntry(entry)
            }
        }
        for (entry in catalogEntries.values) {
            val key = entry.modelId.trim().lowercase()
            // Omitir: entradas de catálogo marcadas para su eliminación, sobrescritas por modelos existentes o entradas de catálogo de proveedores no autorizados que no han sido agregadas explícitamente por el usuario
            // (Este último sólo participa en la cobertura de campo anterior y no agrega nuevos modelos basados ​​en el catálogo para evitar completar todos los modelos históricos del proveedor).
            val skip = entry.builtInRemoved || result.containsKey(key) || (!adoptCatalogFully && !entry.userEdited)
            if (skip) continue
            result[key] = entry.toModel(runtimeProviderId)
        }
        return result.values.toList()
    }

    /**
     * Guarde las modificaciones de campo del usuario en un modelo.
     *
     * Sólo se escriben los campos modificados explícitamente por el usuario; otros campos incorporados continúan retrocediendo.
     */
    @Synchronized
    fun saveUserOverride(providerId: String, modelId: String, patch: ModelCatalogEntry): ModelCatalogEntry {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        requirePositiveOrNull(patch.contextWindow, "contextWindow")
        requirePositiveOrNull(patch.maxOutputTokens, "maxOutputTokens")
        val current = find(providerId, modelId)
        val base = current ?: ModelCatalogEntry(providerId = providerId, modelId = modelId)
        val merged = base.copy(
            providerId = providerId,
            modelId = modelId,
            displayName = patch.displayName ?: base.displayName,
            contextWindow = patch.contextWindow ?: base.contextWindow,
            maxOutputTokens = patch.maxOutputTokens ?: base.maxOutputTokens,
            supportsVision = patch.supportsVision ?: base.supportsVision,
            supportsStreaming = patch.supportsStreaming ?: base.supportsStreaming,
            supportsVideo = patch.supportsVideo ?: base.supportsVideo,
            supportsTools = patch.supportsTools ?: base.supportsTools,
            supportsReasoning = patch.supportsReasoning ?: base.supportsReasoning,
            inputModalities = patch.inputModalities ?: base.inputModalities,
            outputModalities = patch.outputModalities ?: base.outputModalities,
            visionCapabilities = patch.visionCapabilities ?: base.visionCapabilities,
            updatedAt = System.currentTimeMillis(),
            userEdited = true,
            builtInRemoved = false,
        )
        val overrides = loadUserOverrides()
        val newList = overrides.filterNot {
            keyOf(it.providerId, it.modelId) == keyOf(providerId, modelId)
        } + merged
        persistUserOverrides(UserOverrides(items = newList, updatedAt = System.currentTimeMillis()))
        return merged
    }

    /** El usuario elimina el modelo integrado (oculto en el directorio, sin destruir los datos integrados). */
    @Synchronized
    fun removeModel(providerId: String, modelId: String) {
        val overrides = loadUserOverrides()
        val removed = ModelCatalogEntry(
            providerId = providerId,
            modelId = modelId,
            updatedAt = System.currentTimeMillis(),
            userEdited = true,
            builtInRemoved = true,
        )
        val newList = overrides.filterNot {
            keyOf(it.providerId, it.modelId) == keyOf(providerId, modelId)
        } + removed
        persistUserOverrides(UserOverrides(items = newList, updatedAt = System.currentTimeMillis()))
    }

    /** Restaura los valores predeterminados integrados (elimina todas las anulaciones de usuario para este modelo). */
    @Synchronized
    fun resetModel(providerId: String, modelId: String) {
        val overrides = loadUserOverrides()
        val newList = overrides.filterNot {
            keyOf(it.providerId, it.modelId) == keyOf(providerId, modelId)
        }
        persistUserOverrides(UserOverrides(items = newList, updatedAt = System.currentTimeMillis()))
    }

    /** El usuario agrega manualmente un modelo que no existe en el directorio. */
    @Synchronized
    fun addUserModel(providerId: String, modelId: String, displayName: String? = null): ModelCatalogEntry = saveUserOverride(
        providerId,
        modelId,
        ModelCatalogEntry(
            providerId = providerId,
            modelId = modelId,
            displayName = displayName ?: modelId,
        ),
    )

    /** Exporte el directorio de combinación actual como JSON (copia de seguridad/para compartir). */
    fun exportJson(): String = AppJson.encodeToString(
        ListSerializer(ModelCatalogEntry.serializer()),
        entries(),
    )

    /** Importar catálogo JSON (solo actualiza las anulaciones del usuario; las funciones integradas aún se proporcionan mediante código). */
    @Synchronized
    @Suppress("CyclomaticComplexMethod")
    fun importJson(json: String): Boolean {
        return runCatching {
            val imported = AppJson.decodeFromString<List<ModelCatalogEntry>>(json)
            val overrides = loadUserOverrides()
            val existing = overrides.filterNot { existingEntry ->
                imported.any {
                    keyOf(it.providerId, it.modelId) == keyOf(existingEntry.providerId, existingEntry.modelId)
                }
            }
            persistUserOverrides(
                UserOverrides(
                    items = existing + imported.map { it.copy(userEdited = true, updatedAt = System.currentTimeMillis()) },
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { e ->
            Logger.w(TAG, "Error al importar el catálogo de modelos", e)
        }.isSuccess
    }

    private fun entry(
        providerId: String,
        modelId: String,
        displayName: String? = null,
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        vision: Boolean? = null,
        tools: Boolean? = null,
        reasoning: Boolean? = null,
    ) = ModelCatalogEntry(
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsVision = vision,
        supportsTools = tools,
        supportsReasoning = reasoning,
    )

    /**
     * Fusión de tres niveles (Prioridad: Usuario > extremo lejano > incorporado).
     *
     * Fusionar con el nivel de campo clave: la capa de alto nivel solo cubre los campos no nulos declarados por ella misma;
     * `builtInRemoved=true` Indica que esta capa oculta este modelo (la eliminación del usuario tiene prioridad sobre otras capas).
     */
    private fun merge(
        builtin: List<ModelCatalogEntry>,
        remote: List<ModelCatalogEntry>,
        cloud: List<ModelCatalogEntry>,
        user: List<ModelCatalogEntry>,
    ): List<ModelCatalogEntry> {
        val result = LinkedHashMap<String, ModelCatalogEntry>()
        builtin.forEach { b -> result[keyOf(b.providerId, b.modelId)] = b }
        applyLayer(result, remote)
        applyLayer(result, cloud)
        applyLayer(result, user)
        return result.values.toList()
    }

    /** Cubra la capa (remota/usuario) se aplica in situ a la tabla fusionada. */
    private fun applyLayer(target: LinkedHashMap<String, ModelCatalogEntry>, layer: List<ModelCatalogEntry>) {
        layer.forEach { entry ->
            val key = keyOf(entry.providerId, entry.modelId)
            if (entry.builtInRemoved) {
                target.remove(key)
                return@forEach
            }
            val base = target[key]
            target[key] = if (base == null) entry else base.mergeFields(entry)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun Model.applyCatalogEntry(entry: ModelCatalogEntry?): Model {
        if (entry == null) return this
        val mergedAbilities = abilities.toMutableSet().apply {
            if (entry.supportsTools == true) add(io.zer0.ai.core.ModelAbility.TOOL)
            if (entry.supportsTools == false) remove(io.zer0.ai.core.ModelAbility.TOOL)
            if (entry.supportsReasoning == true) add(io.zer0.ai.core.ModelAbility.REASONING)
            if (entry.supportsReasoning == false) remove(io.zer0.ai.core.ModelAbility.REASONING)
        }
        return copy(
            name = entry.displayName ?: name,
            contextWindow = entry.contextWindow ?: contextWindow,
            maxOutputTokens = entry.maxOutputTokens ?: maxOutputTokens,
            supportsVision = entry.supportsVision ?: supportsVision,
            supportsStreaming = entry.supportsStreaming ?: supportsStreaming,
            supportsVideo = entry.supportsVideo ?: supportsVideo,
            inputModalities = entry.inputModalities ?: inputModalities,
            outputModalities = entry.outputModalities ?: outputModalities,
            visionCapabilities = entry.visionCapabilities ?: visionCapabilities,
            abilities = mergedAbilities,
        )
    }

    private fun ModelCatalogEntry.toModel(providerId: String): Model = Model(
        id = modelId,
        name = displayName ?: modelId,
        providerId = providerId,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsVision = supportsVision ?: false,
        supportsStreaming = supportsStreaming ?: true,
        supportsVideo = supportsVideo ?: false,
        // issue #3.1 Parches (comentarios de los usuarios): aguas arriba**No declarado**Cuando se trata de capacidades, no las escriba en texto plano.
        //
        // Implementación anterior:inputModalities ?: setOf("text"). El resultado es que la estación de transferencia lo retiró y admite el reconocimiento de imágenes.
        // Los modelos nativos (como gpt-4o con openrouter/ prefijo) se solidifican en texto plano y siempre estarán en la lista de modelos.
        // No aparecerá y no hay forma de activar la ayuda visual.
        //
        // Cambiar a: No declarado → Deje el conjunto vacío y déselo a ModelRegistry.enhanceModel para inferir por modelId;
        // No se puede inferir el tiempo aguas abajo supportsVisionInput() Todavía devuelve falso y el comportamiento no cambia.
        // aguas arriba**declaración clara** text de (size==1 y contiene texto) permanece como está, sin anular el anterior.
        inputModalities = inputModalities ?: emptySet(),
        outputModalities = outputModalities ?: setOf("text"),
        visionCapabilities = visionCapabilities,
        abilities = buildSet {
            if (supportsTools == true) add(io.zer0.ai.core.ModelAbility.TOOL)
            if (supportsReasoning == true) add(io.zer0.ai.core.ModelAbility.REASONING)
        },
    )

    @Suppress("CyclomaticComplexMethod")
    private fun ModelCatalogEntry.mergeFields(overlay: ModelCatalogEntry): ModelCatalogEntry = copy(
        displayName = overlay.displayName ?: displayName,
        contextWindow = overlay.contextWindow ?: contextWindow,
        maxOutputTokens = overlay.maxOutputTokens ?: maxOutputTokens,
        supportsVision = overlay.supportsVision ?: supportsVision,
        supportsStreaming = overlay.supportsStreaming ?: supportsStreaming,
        supportsVideo = overlay.supportsVideo ?: supportsVideo,
        supportsTools = overlay.supportsTools ?: supportsTools,
        supportsReasoning = overlay.supportsReasoning ?: supportsReasoning,
        inputModalities = overlay.inputModalities ?: inputModalities,
        outputModalities = overlay.outputModalities ?: outputModalities,
        visionCapabilities = overlay.visionCapabilities ?: visionCapabilities,
        updatedAt = overlay.updatedAt.let { if (it > 0) it else updatedAt },
        userEdited = overlay.userEdited || userEdited,
        builtInRemoved = overlay.builtInRemoved || builtInRemoved,
    )

    private fun loadUserOverrides(): List<ModelCatalogEntry> {
        val cached = userCache
        if (cached != null) return cached.items
        if (!userOverridesFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString(UserOverrides.serializer(), userOverridesFile.readText()).items
        }.onSuccess {
            userCache = UserOverrides(items = it)
        }.getOrElse { e ->
            Logger.w(TAG, "Error en el análisis de anulación del modelo de usuario,Procesar como vacío", e)
            emptyList()
        }
    }

    private fun persistUserOverrides(overrides: UserOverrides) {
        runCatching {
            catalogDir.mkdirs()
            userOverridesFile.writeText(AppJson.encodeToString(UserOverrides.serializer(), overrides))
            userCache = overrides
        }.onFailure { e ->
            Logger.w(TAG, "Error de escritura de sobrescritura del modelo de usuario", e)
        }
    }

    private fun keyOf(providerId: String, modelId: String) = "${providerId.trim().lowercase()}\u0000${modelId.trim().lowercase()}"

    private fun requirePositiveOrNull(value: Int?, field: String) {
        require(value == null || value > 0) { "$field must be positive when provided" }
    }

    companion object {
        private const val TAG = "ModelCatalogStore"

        /**
         * Proveedor autorizado del catálogo: la lista de modelos está completamente determinada por el catálogo en la nube, ignorando los ajustes preestablecidos/Lista de modelos antiguos que se han conservado.
         *
         * Propósito: Los modelos disponibles del proveedor cambian con las políticas del lado del servidor (como el modelo de suscripción ChatGPT del Codex que a menudo
         * jubilado/Recién agregado), la lista codificada de forma predeterminada es fácil de caducar. Para dichos proveedores prevalecerá el directorio en la nube.
         * La publicación de un nuevo catálogo simplemente reemplaza el JSON del lado del servidor.
         *
         * La clave es**Directorio providerId**(Nombres con guiones, ver [CatalogProviderIds]).
         */
        val CATALOG_AUTHORITATIVE_PROVIDERS: Set<String> = setOf("openai-codex-oauth")

        /** Tiempo de espera de extracción del directorio remoto (conexión/lectura, milisegundos). */
        private const val REMOTE_TIMEOUT_MS = 10_000
    }
}

/** Resultados de extracción de directorio remoto. */
data class RemoteRefreshResult(
    /** Si la capa remota está habilitada (falso cuando no se configura ninguna URL, no se envía ninguna solicitud). */
    val enabled: Boolean,
    val ok: Boolean,
    val updated: Boolean,
    val message: String,
)

/** Resumen del estado del directorio remoto. */
data class RemoteStatus(
    val enabled: Boolean,
    val url: String,
    val publishedAt: String,
    val itemCount: Int,
)
