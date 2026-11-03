package io.zer0.muse.rag

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.muse.data.ProviderConfigChangeBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/**
 * v1.54: Embedding Entrada unificada a servicios.
 *
 * Según [RagConfig.embeddingSource] elegir [EmbeddingProvider]:
 *  - CLOUD: crear [OpenAIEmbeddingProvider],Utilice el proveedor especificado o active Provider
 *  - LOCAL: v1.134 Dar prioridad a intentar [OnnxEmbeddingProvider](Basado en [RagConfig.localModelPath]),
 *           Bajar a cuando el archivo del modelo no esté disponible [LocalKeywordEmbeddingProvider]
 *  - LOCAL_KEYWORD: directo [LocalKeywordEmbeddingProvider](Hashing de palabras clave sin conexión,Ninguno ONNX)
 *
 * Provider Caché de instancia:La clave de caché es "huella digital de configuración"——[RagConfig.hashCode] Agregue los campos que realmente participan en la creación del Proveedor
 * (Provider id, URL base real,apiKey Resumen SHA-256, nombre del modelo,ONNX ¿Está el archivo del modelo en su lugar?).
 * Cualquier cambio es una reconstrucción.,Evite continuar usando la instantánea anterior después de cambiar la clave/punto final. La capa de datos también pasará después de escribir la configuración del Proveedor.
 * [ProviderConfigChangeBus] gatillo [invalidate] Llega al fondo de esto.
 *
 * La degradación ya no es silenciosa:retroceder [LocalKeywordEmbeddingProvider] Registro de advertencias de Shiji,y escribe las razones
 * [activeBackend],Para que la capa superior muestre "qué backend de incrustación se está utilizando actualmente".
 *
 * @param configStore Provider Configurar almacén(SettingsRepository darse cuenta)
 * @param client Reutilizar Koin inyectado OkHttpClient(named("chat"))
 * @param filesDir Solicitud filesDir,Modelo ONNX para resolver rutas relativas(v1.134 Nuevo)
 */
class EmbeddingService(
    private val configStore: ProviderConfigStore,
    private val client: OkHttpClient,
    private val filesDir: File? = null,
) {
    /**
     * La instantánea de backend incorporada actualmente efectiva.
     *
     * @param degraded true Indica que este no es el backend seleccionado por el usuario.(Por ejemplo, la configuración en la nube no está disponible,Palabras clave locales alternativas)
     * @param reason Razón de la degradación([degraded]=true el tiempo no está vacío),Se puede utilizar directamente en redacción publicitaria inmediata.
     */
    data class BackendState(
        val backendId: String,
        val displayName: String,
        val modelName: String,
        val degraded: Boolean,
        val reason: String? = null,
    )

    /** Elemento de caché:Huella digital + instancia + instantánea de backend correspondiente a la instancia. */
    private data class CacheEntry(
        val fingerprint: String,
        val provider: EmbeddingProvider,
        val state: BackendState,
    )

    /** Resultados del análisis de la integración en la nube.;[provider] nulo significa no disponible,[reason] Explique por qué. */
    private data class CloudTarget(
        val provider: ProviderConfig?,
        val reason: String? = null,
    )

    @Volatile
    private var cached: CacheEntry? = null
    private val mutex = Mutex()

    private val _activeBackend = MutableStateFlow<BackendState?>(null)

    /** El backend de inserción actualmente efectivo(null = Aún no analizado). */
    val activeBackend: StateFlow<BackendState?> = _activeBackend.asStateFlow()

    private val changeListener: () -> Unit = { invalidate() }

    init {
        ProviderConfigChangeBus.register(changeListener)
    }

    /** Obtenga el EmbeddingProvider correspondiente al RagConfig actual. */
    suspend fun getProvider(ragConfig: RagConfig): EmbeddingProvider = entryFor(ragConfig).provider

    /** Analizar (o reutilizar)Provider Elementos de caché;[embedWithCache] Al mismo tiempo es necesario obtener las huellas dactilares. */
    private suspend fun entryFor(ragConfig: RagConfig): CacheEntry {
        val target = resolveCloudTarget(ragConfig)
        val fingerprint = fingerprintOf(ragConfig, target)
        cached?.let { if (it.fingerprint == fingerprint) return it }
        return mutex.withLock {
            // doble verificación,Evite la creación de duplicados durante la concurrencia Provider
            cached?.let { if (it.fingerprint == fingerprint) return@withLock it }
            val entry = buildEntry(ragConfig, target, fingerprint)
            cached = entry
            _activeBackend.value = entry.state
            entry
        }
    }

    /** caché no válido(config Se llama cuando se producen cambios o cambia la lista de proveedores.). */
    fun invalidate() {
        cached = null
        _activeBackend.value = null
    }

    // ── caché vectorial ────────────────────────────────────────────────

    private val vectorStore = EmbeddingVectorStore()

    /** Recuento de diagnóstico: aciertos de caché/Miss (registro y prueba de uso). */
    internal val vectorCacheHits: Int get() = vectorStore.hits

    internal val vectorCacheMisses: Int get() = vectorStore.misses

    /**
     * Obtención de vectores por lotes con caché. El texto que no llega al caché no se solicitará y el texto que no llega al caché se completará en lotes.
     *
     * El reordenamiento de la correlación de la memoria requiere "query + Todos los hechos candidatos se codifican una vez; Los hechos candidatos se codifican entre rondas.
     * Está casi sin cambios. Después del almacenamiento en caché, normalmente solo se envía una solicitud a la consulta; incluso la consulta se realiza cuando se vuelve a intentar la misma oración.
     *
     * @return con [texts] Una lista de vectores de igual longitud; si algún paso falla, se devuelve nulo (la persona que llama retrocede en consecuencia)
     */
    suspend fun embedWithCache(ragConfig: RagConfig, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val entry = entryFor(ragConfig)
        return vectorStore.embed(entry.provider, entry.fingerprint, texts)
    }

    /**
     * Verifique solo el caché de vectores: no se activa ninguna codificación y no se ingresa ninguna ventana de tiempo de espera de la red.
     *
     * Para personas que llaman y "no logran fusionarse" (RAG incrustación de consultas)—— Por allá**anormal**Disparar fusible de 5 minutos,
     * y [embedWithCache] Las excepciones del proveedor se considerarán nulas. Los hits se devuelven directamente; los errores son devueltos por la persona que llama
     * yo mismo `withTimeout { provider.embed(...) }`, ajustar de nuevo [rememberVector] Contestar.
     */
    suspend fun cachedVector(ragConfig: RagConfig, text: String): FloatArray? =
        vectorStore.peek(entryFor(ragConfig).fingerprint, text)

    /** Escriba el vector que se ha recuperado en el caché (junto con [cachedVector] uso). */
    suspend fun rememberVector(ragConfig: RagConfig, text: String, vector: FloatArray) {
        vectorStore.store(entryFor(ragConfig).fingerprint, text, vector)
    }

    // ── huella digital de caché ────────────────────────────────────────────────

    /**
     * Decisión de cálculoProvider Huella digital del comportamiento de la instancia".
     *
     * Poner sólo los campos que realmente participan en la creación del Proveedor:[RagConfig.hashCode] Superficie de configuración de la cubierta,
     * Más la identidad del Proveedor resuelta(baseUrl/apiKey/Nombre del modelo)—— Cuando se cambian pero RagConfig permanece sin cambios,
     * No se nota con solo configurar el hash.
     */
    private fun fingerprintOf(ragConfig: RagConfig, target: CloudTarget): String = buildString {
        append(ragConfig.hashCode()).append('|')
        append(ragConfig.embeddingSource.name).append('|')
        append(ragConfig.localModelPath.trim()).append('|')
        when (ragConfig.embeddingSource) {
            RagConfig.EmbeddingSource.LOCAL_KEYWORD -> append("keyword")
            RagConfig.EmbeddingSource.LOCAL ->
                append(resolveModelPath(ragConfig.localModelPath, DEFAULT_ONNX_MODEL) ?: "onnx-missing")
            RagConfig.EmbeddingSource.CLOUD -> {
                val provider = target.provider
                if (provider == null) {
                    append("cloud-unavailable|").append(target.reason.orEmpty())
                } else {
                    append(provider.id).append('|')
                    append(provider.resolvedBaseUrl()).append('|')
                    append(provider.type.name).append('|')
                    append(apiKeyDigest(provider.apiKey)).append('|')
                    append(ragConfig.cloudModel.trim())
                }
            }
        }
    }

    /** apiKey Participar en la toma de huellas dactilares sólo como resumen.,El texto sin formato no cae en ninguna cadena. */
    private fun apiKeyDigest(apiKey: String): String {
        if (apiKey.isEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return digest.take(DIGEST_BYTES).joinToString("") { byte -> "%02x".format(byte) }
    }

    // ── Provider construir ──────────────────────────────────────────

    private fun buildEntry(ragConfig: RagConfig, target: CloudTarget, fingerprint: String): CacheEntry {
        val (provider, state) = when (ragConfig.embeddingSource) {
            RagConfig.EmbeddingSource.LOCAL -> createLocalProvider(ragConfig)
            RagConfig.EmbeddingSource.LOCAL_KEYWORD -> keywordProvider(degraded = false, reason = null)
            RagConfig.EmbeddingSource.CLOUD -> createCloudProvider(ragConfig, target)
        }
        return CacheEntry(fingerprint = fingerprint, provider = provider, state = state)
    }

    private fun createLocalProvider(ragConfig: RagConfig): Pair<EmbeddingProvider, BackendState> {
        // v1.134: LOCAL modo primer intento OnnxEmbeddingProvider,Bajar a hash de palabras clave cuando no esté disponible
        val onnxProvider = tryCreateOnnxProvider(ragConfig.localModelPath)
            ?: return keywordProvider(degraded = true, reason = "La incrustación local de ONNX no está disponible(Modelo no configurado o falta archivo)")
        Logger.i("EmbeddingService", "Usar local ONNX embedding(${onnxProvider.modelName}, ${onnxProvider.dimension}d)")
        return onnxProvider to stateOf(onnxProvider, degraded = false)
    }

    private fun createCloudProvider(ragConfig: RagConfig, target: CloudTarget): Pair<EmbeddingProvider, BackendState> {
        val providerConfig = target.provider
            ?: return keywordProvider(degraded = true, reason = target.reason ?: "El proveedor de la nube no está disponible")
        // v1.103: Anthropic/Gemini No disponible para compatibilidad con OpenAI embedding API,Cuando no se especifica cloudModel
        // OpenAIEmbeddingProvider El constructor lanzará IllegalStateException,Por lo tanto, deténgalo antes de crear la instancia.
        val provider = OpenAIEmbeddingProvider(
            config = providerConfig,
            model = ragConfig.cloudModel,
            client = client,
        )
        Logger.i(
            "EmbeddingService",
            "Usa la nube embedding(provider=${providerConfig.id}, model=${provider.modelName})",
        )
        return provider to stateOf(provider, degraded = false)
    }

    /** Implementación alternativa de palabras clave locales:recuerda warn(Escribe las razones claramente.)Y márquelo con marca degradada. */
    private fun keywordProvider(degraded: Boolean, reason: String?): Pair<EmbeddingProvider, BackendState> {
        if (degraded) {
            Logger.w("EmbeddingService", "${reason ?: "La integración en la nube no está disponible"},Cambiar a palabras clave locales embedding,Disminución de la capacidad de recuperación semántica.")
        } else {
            Logger.i("EmbeddingService", "Utilice palabras clave locales embedding(Modo sin conexión)")
        }
        val provider = LocalKeywordEmbeddingProvider()
        return provider to stateOf(provider, degraded = degraded, reason = reason)
    }

    private fun stateOf(
        provider: EmbeddingProvider,
        degraded: Boolean,
        reason: String? = null,
    ): BackendState = BackendState(
        backendId = provider.id,
        displayName = provider.displayName,
        modelName = provider.modelName,
        degraded = degraded,
        reason = reason,
    )

    // ── Análisis de proveedores de nube ────────────────────────────────────

    /**
     * Analice los proveedores realmente disponibles para la integración en la nube.
     *
     * Tres situaciones de indisponibilidad(Proveedor no encontrado / Proveedor deshabilitado / Capacidades o claves no cumplidas)Todos se devuelven con razones.
     * [CloudTarget],por [createCloudProvider] Degradación y registro unificados,Evite que los usuarios piensen que se está utilizando la búsqueda vectorial.
     */
    private suspend fun resolveCloudTarget(ragConfig: RagConfig): CloudTarget {
        if (ragConfig.embeddingSource != RagConfig.EmbeddingSource.CLOUD) return CloudTarget(provider = null)
        val providerConfig = getCloudProviderConfig(ragConfig)
        return when {
            providerConfig == null -> CloudTarget(
                provider = null,
                reason = if (ragConfig.cloudProviderId.isNotBlank()) {
                    "RAG Configuración especificada Provider(${ragConfig.cloudProviderId}) no existe"
                } else {
                    "No se encontró ninguna nube disponible Provider"
                },
            )
            !providerConfig.enabled ->
                CloudTarget(provider = null, reason = "Provider ${providerConfig.displayName} Desactivado")
            providerConfig.apiKey.isBlank() ->
                CloudTarget(provider = null, reason = "Provider ${providerConfig.displayName} La clave API está vacía")
            ragConfig.cloudModel.isBlank() && providerConfig.type !in OPENAI_EMBEDDING_TYPES -> CloudTarget(
                provider = null,
                reason = "Provider ${providerConfig.displayName}(${providerConfig.type}) No es compatible con la API de incrustación de OpenAI y no se especifica ningún modelo",
            )
            else -> CloudTarget(provider = providerConfig)
        }
    }

    /**
     * v1.134: Intente crear un OnnxEmbeddingProvider.
     *
     * resolución de ruta:
     *  - [localModelPath] es un camino absoluto(Empezar con /)→ Usar directamente
     *  - De lo contrario, se trata como ruta relativa a filesDir
     *  - cadena vacía → Predeterminado filesDir/muse_onnx/embedding.onnx
     *
     * @return disponible OnnxEmbeddingProvider;Si el archivo del modelo no existe, devuelva null
     */
    private fun tryCreateOnnxProvider(localModelPath: String): OnnxEmbeddingProvider? {
        val resolvedPath = resolveModelPath(localModelPath, DEFAULT_ONNX_MODEL) ?: return null
        val provider = OnnxEmbeddingProvider(modelPath = resolvedPath)
        return if (provider.isAvailable()) provider else null
    }

    /** Analizar la ruta del modelo:Utilice la ruta absoluta directamente,Empalme de ruta relativa filesDir,Utilice defaultRelative para cadenas vacías. */
    private fun resolveModelPath(configuredPath: String, defaultRelative: String): String? {
        val path = configuredPath.trim()
        if (path.startsWith("/")) {
            return if (File(path).exists()) path else null
        }
        val files = filesDir ?: return null
        val candidate = if (path.isBlank()) {
            File(files, defaultRelative)
        } else {
            File(files, path)
        }
        return if (candidate.exists()) candidate.absolutePath else null
    }

    /** Utilice ProviderConfig para la integración en la nube. */
    private suspend fun getCloudProviderConfig(ragConfig: RagConfig): ProviderConfig? {
        // Se da prioridad al especificado por RagConfig.cloudProviderId Provider
        if (ragConfig.cloudProviderId.isNotBlank()) {
            return configStore.getAllProviders().firstOrNull { it.id == ragConfig.cloudProviderId }
        }
        // De lo contrario use activado chat Provider(ProviderConfigStore.get() Se omitió la desactivación del proveedor)
        return configStore.get()
    }

    private companion object {
        /** Ruta relativa al modelo ONNX predeterminado en filesDir. */
        const val DEFAULT_ONNX_MODEL = "muse_onnx/embedding.onnx"

        /** apiKey Número de bytes reservados para resumen(16 caracteres hexadecimales,La probabilidad de colisión es lo suficientemente baja para la detección de cambios.). */
        const val DIGEST_BYTES = 8

        /** Tipo de proveedor que admite una interfaz de inserción compatible con OpenAI. */
        val OPENAI_EMBEDDING_TYPES = setOf(ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES)
    }
}
