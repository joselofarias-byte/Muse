package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecMerger
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.backup.CloudBackupConfig
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.preset.ModelCatalogStore
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.data.preset.SiliconFlowFreeModels
import io.zer0.muse.data.prompttemplate.PromptTemplate
import io.zer0.muse.data.routing.UtilityModelBinding
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.tools.SessionPermissionMode
import io.zer0.muse.ui.theme.CustomTheme
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.atomic.AtomicBoolean

/** Resumen del asistente diario más reciente generado con éxito,Para reutilización de saludos en la página de inicio. */
data class DailySummarySnapshot(
    val date: String,
    val text: String,
)

/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

/**
 * Aplicar repositorio de configuración global — Basado en Jetpack DataStore (Preferences).
 *
 * Responsabilidades:
 *  - Provider Lista/Proveedor activo/Modelo/Persistencia de clave API
 *  - Assistant Configuración/acceso al retrato de usuario(entidades específicas van Room,Aquí sólo se almacenan los estados activos, etc.)
 *  - Cambios de apariencia y comportamiento, como modo de tema/tamaño de fuente/keepAwake/autoLaunch
 *  - Configuración de mensajes activos(interruptor + intervalo + último tiempo de activación)
 *  - WebServer Configuración/Configuración de búsqueda web/Configuración ASR/Configuración de medios/Configuración de proxy
 *  - Estado de la cuenta(Modo de inicio de sesión/invitado)
 *  - ¿Se muestra el primer arranque?
 *  - MCP OAuth token almacenamiento(prensa serverId)
 *
 * realizado al mismo tiempo [ProviderConfigStore] interfaz,Inyectado por ChatService/ImageService del módulo ai.
 *
 * estrategia de almacenamiento en caché:hot path Los campos se almacenan en caché con AtomicBoolean(Como por ejemplo memoryEnabled),Evite ir al DataStore cada vez.
 */
class SettingsRepository(
    private val appContext: Context,
    /** P2-4: Registrador de auditoría,Se utiliza para registrar acciones clave del usuario.(Si se elimina Provider). */
    private val auditLogger: AuditLogger,
    /** Directorio de capacidades del modelo; opcional para mantener las pruebas de capa de datos puros compatibles con personas que llaman de mayor edad. */
    private val modelCatalogStore: ModelCatalogStore? = null,
) : ProviderConfigStore {
    /** P2-2: Apariencia/Sub-repositorio de temas(compartir muse_settings DataStore). */
    val appearance = AppearanceSettingsStore(appContext)

    /** P2-2: Sub-repositorio de configuración a nivel de aplicación(idioma, etc.). */
    val appSettings = AppSettingsStore(appContext)

    /** P2-2: seguro/Subrepositorio de cambio de comportamiento. */
    val security = SecuritySettingsStore(appContext)

    /** P2-2: Sub-repositorio de configuración de comportamiento de chat. */
    val chatSettings = ChatSettingsStore(appContext)

    private val store get() = appContext.museSettingsDataStore
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryEnabledCache = AtomicBoolean(true)

    // M-SR5: Evitar que migraciónLegacyProviderIfNeeded se ejecute repetidamente durante la inicialización simultánea(leer viejo JSON + addProvider + remove Hay una carrera entre)
    private val migrationDone = AtomicBoolean(false)

    /** v1.0.51: Evite que la migración de reabastecimiento de memoria se ejecute repetidamente durante la inicialización simultánea(Existe una condición de carrera entre leer bits de bandera + ejecutar el relleno + escribir bits de bandera). */
    private val memoryBackfillMigrationDone = AtomicBoolean(false)

    /** v2.x: Evite que se repitan las migraciones de combinaciones de teclas antiguas del modelo secundario. */
    private val utilityBindingMigrationDone = AtomicBoolean(false)

    /**
     * v1.0.7: Declaraciones de especificaciones de proveedores integradas.
     * lazy inicialización,suministro [providersFlow] Fusionar modelo predeterminado de especificaciones + superposición de usuario.
     */
    private val presetProviders by lazy { PresetProviders(appContext, modelCatalogStore) }

    /**
     * v0.32: Caché de memoria para la MemoryConfig actual(Para que el cierre getConfig de MemoryTicker se lea sincrónicamente).
     *
     * MemoryConfig El usuario puede ajustarlo en tiempo real en la página de configuración.(escribir DataStore),Pero cada vez que el planificador tick /
     * Léelo en cada ronda de conversación.,No apto para ejecutar suspender cada vez [getMemoryConfig]. imitar aquí
     * [memoryEnabledCache] modo:Suscripción a rutinas en segundo plano [memoryConfigFlow],soltar el último valor a
     * Este campo @Volatile;MemoryTicker usar `getConfig = { settings.memoryConfigCache }`
     * Puede obtener la configuración actual con bloqueo cero.
     */
    @Volatile
    var memoryConfigCache: io.zer0.memory.ticker.MemoryConfig = io.zer0.memory.ticker.MemoryConfig()

    /**
     * v2.6.2: Memoria caché para recordar la configuración de la constelación (para el interruptor de profundización LLM de lectura síncrona de capa de borde complementaria).
     * imitar [memoryConfigCache] Modo:init Al suscribirse a un flujo, éste cae en la memoria caché.
     */
    @Volatile
    var memoryGraphConfigCache: io.zer0.muse.data.MemoryGraphConfig = io.zer0.muse.data.MemoryGraphConfig()
        private set

    /**
     * v0.32: Memoria caché del ExperimentsConfig actual(Para SystemPromptAssembler/ChatViewModel, etc.
     * Lectura sincrónica de interruptores experimentales.).
     *
     * imitar [memoryConfigCache] modo:Suscripción a rutinas en segundo plano [experimentsFlow],Suelte el último valor aquí
     * @Volatile campo;llamador(`getExperiments = { settings.experimentsCache }`)Lecturas de bloqueo cero.
     *
     * efectos secundarios:cuando [ExperimentsConfig.longMemoryCompression] = true tiempo,dejará [memoryConfigCache]
     * El umbral de compilación cae a 3.0(Recopilación más agresiva). Los dos cachés se actualizan conjuntamente a través del flujo combinado.,
     * Se garantiza que entrará en vigor inmediatamente después de que el usuario cambie el interruptor.
     */
    @Volatile
    var experimentsCache: ExperimentsConfig = ExperimentsConfig()
        private set

    /**
     * v1.25: Memoria caché del MultiAgentConfig actual,Para lecturas de bloqueo cero mediante SystemPromptAssembler.
     */
    @Volatile
    var multiAgentConfigCache: MultiAgentConfig = MultiAgentConfig()
        private set

    /**
     * v1.98: Memoria caché del cambio de base de experiencia actual,Para lecturas de bloqueo cero mediante SystemPromptAssembler.
     * imitar [experimentsCache] modo:Suscripción a rutinas en segundo plano [experienceEnabledFlow],Coloque el último valor en el campo @Volatile.
     */
    @Volatile
    var experienceEnabledCache: Boolean = false
        private set

    /**
     * v1.116: Memoria caché del cambio de emoticones y probabilidad de envío.,Para lecturas de bloqueo cero mediante ChatViewModel.
     * imitar [experimentsCache] modo:Correspondencia de suscripción de rutina en segundo plano Flow,Coloque el último valor en el campo @Volatile.
     * ChatViewModel Lea estos dos cachés al construir la lista de herramientas,Decida si exponer las herramientas de pegatinas a LLM según la probabilidad.
     */
    @Volatile
    var stickerEnabledCache: Boolean = false
        private set

    @Volatile
    var stickerSendProbabilityCache: Int = 30
        private set

    /**
     * v2.x: Caché de engranajes de frecuencia de envío de emoticonos(occasionally/normal/frequent).
     * Leer por SystemPromptAssembler bajo el enlace de etiqueta,generar"Frecuencia de uso"Consejos.
     */
    @Volatile
    var stickerFrequencyCache: String = STICKER_FREQ_NORMAL
        private set

    /** v2.x: Límite de caché de memoria de sesión(Configuraciones avanzadas,Predeterminado 5;alcance 2..12). */
    @Volatile
    var sessionCacheSizeCache: Int = 5
        private set

    /**
     * PII Guard cambiar la memoria caché,Para que ChatViewModel lea sin bloquear antes de enviar el mensaje.
     * imitar [stickerEnabledCache] modo:Suscripción a rutinas en segundo plano [piiGuardEnabledFlow],Coloque el último valor en el campo @Volatile.
     * Predeterminado true(encender),El usuario está en"Datos y privacidad"La página de configuración se puede cerrar.
     */
    @Volatile
    var piiGuardEnabledCache: Boolean = true
        private set

    /**
     * ANR Detectar caché de memoria del conmutador,Para lectura sincrónica sin bloqueo por parte de AnrWatcher.
     * imitar [piiGuardEnabledCache] modo:Suscripción a rutinas en segundo plano [anrDetectionFlow] soltar caché,Verdadero predeterminado.
     */
    @Volatile
    var anrDetectionCache: Boolean = true
        private set

    /**
     * v1.39: Memoria caché del ProxyConfig actual,Proporcionado para que AppKoinModule cree OkHttpClient para lecturas sin bloqueo.
     *
     * imitar [memoryConfigCache] modo:init Suscripción a corrutina en segundo plano de tiempo [proxyConfigFlow] Eliminar caché.
     * Volver al valor predeterminado cuando el caché no esté listo [ProxyConfig](Sin proxy),Después de que el usuario cambie la configuración del proxy, entrará en vigor la próxima vez que cree un cliente.
     * Esto elimina 2 cosas en AppKoinModule `runBlocking`(Causa raíz de ANR del hilo principal).
     */
    @Volatile
    var proxyConfigCache: ProxyConfig = ProxyConfig()
        private set

    /**
     * v1.74: Analizar la configuración desde JSON sin formato de DataStore,Registrar cuando falla el análisis(No más excepciones para tragar silenciosamente).
     *
     * Elimina más de 13 lugares `prefs[KEY]?.let { runCatching { decodeFromString(...) }.getOrNull() } ?: default`
     * Repita la plantilla. Cuando los datos persistentes no se pueden analizar debido a cambios de esquema o corrupción,Logger.w dejar pistas diagnósticas,
     * evitar que los usuarios se sientan"La configuración se perdió repentinamente."Pero no hay registros para verificar.
     *
     * @param raw DataStore cadena JSON sin formato en(null significa no establecido)
     * @param serializer Serializador para el tipo correspondiente.
     * @param keyName Nombre del elemento de configuración(Se utiliza sólo para la identificación de registros)
     * @return El objeto de configuración se devuelve después de un análisis exitoso.,raw Devuelto como nulo o error de análisis null
     */
    private fun <T> decodePrefsOrNull(raw: String?, serializer: KSerializer<T>, keyName: String): T? {
        if (raw == null) return null
        return runCatching { AppJson.decodeFromString(serializer, raw) }.getOrElse {
            Logger.w("SettingsRepository", "$keyName El análisis falló,Volver al valor predeterminado", it)
            null
        }
    }

    // ── Flows (Debe declararse antes del bloque de inicio.,Kotlin Inicializar en orden de declaración;
    // De lo contrario, cuando la rutina iniciada en init acceda asincrónicamente a proveedoresFlow, será null) ──
    val providersFlow: Flow<List<ProviderConfig>> =
        store.data.mapDistinct { prefs ->
            // v1.53-A2: Descifrar después de leer apiKey(Transmisión transparente de datos antiguos en texto plano,Descifrado de datos cifrados)
            val raw = prefs[KEY_PROVIDERS]?.let { json -> decodeProviders(json) } ?: emptyList()
            // v1.0.7: fusión de tres niveles — specId tiempo no vacío,Fusionar lista de modelos predeterminados de especificaciones con superposición de usuario
            raw.map { config ->
                val isLegacyFreeProvider =
                    config.allowMissingApiKey &&
                        config.baseUrl.contains("siliconflow.cn", ignoreCase = true) &&
                        (
                            config.displayName.contains("gratis", ignoreCase = true) ||
                                config.displayName.contains("free", ignoreCase = true)
                            ) &&
                        config.models.any { it.id in io.zer0.ai.core.FreeModelConfig.FREE_MODEL_IDS }
                val internalized =
                    if (
                        config.id == SiliconFlowFreeModels.PROVIDER_ID ||
                        isLegacyFreeProvider
                    ) {
                        config.copy(hiddenFromSettings = true)
                    } else {
                        config
                    }
                val enriched = enrichWithSpecDefaults(internalized)
                // El directorio del modelo de usuario primero se fusiona con el Proveedor.
                // Luego se mejora mediante un registro de capacidades unificado para garantizar que las páginas de configuración, los selectores de modelos y las solicitudes reales vean los mismos metadatos.
                // v2.6.0: Mapeo de ID de proveedor de directorio —— Cuando el specId predeterminado no es exactamente el mismo que la clave del directorio de la nube
                // Se requiere conversión; de lo contrario, la capa del catálogo (como el último modelo de Codex) no coincidirá y solo se mostrará la lista anterior de códigos preestablecidos.
                val rawSpecId = enriched.specId ?: enriched.id.removePrefix("preset_")
                val catalogProviderId = io.zer0.muse.data.preset.CatalogProviderIds.toCatalogId(rawSpecId)
                val projectedModels =
                    modelCatalogStore?.mergeIntoModels(
                        providerId = catalogProviderId,
                        models = enriched.models,
                        runtimeProviderId = enriched.id,
                    ) ?: enriched.models
                enriched.copy(models = projectedModels.map(ModelRegistry::enhanceModel))
            }
        }.catch {
            // M-SR3: Excepción ascendente(DataStore IO / Falló el descifrado)No se debe permitir que el flujo expire permanentemente,Registro y lista vacía alternativa
            Logger.w("SettingsRepository", "providersFlow anormal,lista vacía alternativa", it)
            emit(emptyList())
        }
    val activeProviderIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_ACTIVE_PROVIDER_ID] }
    val providerConfigFlow: Flow<ProviderConfig?> =
        store.data.mapDistinct { activeProviderFromPrefs(it) }
            .catch {
                // M-SR3: Reversión de excepciones ascendentes null,Evite fallas continuas en la interfaz de usuario
                Logger.w("SettingsRepository", "providerConfigFlow anormal,revertir null", it)
                emit(null)
            }
    val selectedModelIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_SELECTED_MODEL] }

    /** Anulación del modelo para la sesión actual, la clave es sessionId; no participa en la configuración global predeterminada del modelo. */
    private val sessionModelOverrideSerializer = MapSerializer(String.serializer(), String.serializer())
    val sessionModelOverridesFlow: Flow<Map<String, String>> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(
                prefs[KEY_SESSION_MODEL_OVERRIDES],
                sessionModelOverrideSerializer,
                "SessionModelOverrides",
            ) ?: emptyMap()
        }

    /** La anulación del proveedor de la sesión actual, junto con la anulación del modelo, garantiza que el cambio de página de chat no contamine el valor predeterminado global. */
    val sessionProviderOverridesFlow: Flow<Map<String, String>> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(
                prefs[KEY_SESSION_PROVIDER_OVERRIDES],
                sessionModelOverrideSerializer,
                "SessionProviderOverrides",
            ) ?: emptyMap()
        }

    /** v1.60-A: modelo de herramienta id(Un modelo ligero para rondas de invocación de herramientas.,null Indica que el modelo de diálogo principal se hereda.). */
    val toolModelIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_TOOL_MODEL_ID] }

    /**
     * v2.0: ID del modelo de subagente (en desuso).
     *
     * v2.6.7 fix (AUDIT F19): El modelo de subagente ha sido cambiado. `UtilityModelRouter.resolve(UtilityTier.LARGE)`
     * (Ver `SubagentRunner.resolveSubagentModel`). Esta posición completa de Flow**sin lectores**,
     * solo queda `ProviderReferenceCleanup` Mover el valor después de eliminar la ruta de limpieza del proveedor ——
     * Está reservado solo para compatibilidad con la clave y la lógica de limpieza en la copia de seguridad anterior y no debe usarse en código nuevo.
     */
    @Deprecated("El modelo de subagente se cambió a UtilityTier.LARGE y este flujo no tiene lectores.")
    val subagentModelIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_SUBAGENT_MODEL_ID] }

    /**
     * v2.x: Enlace de modelo de gadget(Enrutamiento de puesto de tarea auxiliar,traer provider;tener prioridad sobre lo viejo [toolModelIdFlow]).
     * Se utiliza para tareas breves como generación de títulos, clasificación ligera, enrutamiento de intenciones, mensajes de portada y resúmenes de canales.
     */
    val utilityModelBindingFlow: Flow<UtilityModelBinding?> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_UTILITY_MODEL_BINDING], UtilityModelBinding.serializer(), "UtilityModelBinding")
        }

    /** v2.x: Encuadernación de modelos de herramientas grandes(Compresión/recuperación de memoria/Resumen de actividad/Desglose de tareas/subagente;Dejar en blanco para multiplexación en cascada [utilityModelBindingFlow]). */
    val utilityLargeModelBindingFlow: Flow<UtilityModelBinding?> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_UTILITY_LARGE_MODEL_BINDING], UtilityModelBinding.serializer(), "UtilityLargeModelBinding")
        }

    /**
     * modelo comprimido id(Compresión de resumen paralelo fragmentada para ConversationCompressor).
     * null Indica que se hereda el modelo de diálogo principal actual.([selectedModelIdFlow] / Activar el primer modelo del proveedor.).
     * Los usuarios pueden configurar un modelo económico aquí.(Como el modelo gratuito SiliconFlow)Especializados en compresión de resúmenes,Evite el bloqueo del modelo principal.
     */
    val compressModelIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_COMPRESS_MODEL_ID] }

    /**
     * v1.0.52: Compresión personalizada prompt(El usuario puede anular las directivas de compresión predeterminadas).
     * null O una cadena vacía significa usar el mensaje predeterminado incorporado de ConversationCompressor.
     * Los usuarios pueden anular en la configuración,Implementar estilos de compresión personalizados(tan corto/Más detalles/formato específico).
     */
    val customCompressPromptFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_CUSTOM_COMPRESS_PROMPT] }

    /**
     * v1.0.52: Nomenclatura de conversación personalizada prompt(El usuario puede anular las directivas de nomenclatura predeterminadas).
     * null O una cadena vacía significa usar el mensaje predeterminado incorporado de ChatViewModel.autoTitleSession.
     * Los usuarios pueden anular en la configuración,Implementar un estilo de nomenclatura personalizado(Como título en inglés./con fecha/con prefijo).
     */
    val customTitlePromptFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_CUSTOM_TITLE_PROMPT] }

    /** v1.0.47: Token Cambio de estimación(Desactivado de forma predeterminada,El usuario lo activa explícitamente para evitar una sobrecarga de rendimiento.). */
    val tokenEstimateEnabledFlow: Flow<Boolean> get() = chatSettings.tokenEstimateEnabledFlow

    /** v1.0.47 P5-2: Cambio de pegado de texto largo a archivo(Habilitado por defecto,Al pegar texto que excede el umbral, se le solicita convertirlo en un archivo adjunto de texto.). */
    val pasteAsFileEnabledFlow: Flow<Boolean> get() = chatSettings.pasteAsFileEnabledFlow

    /** v1.0.47 P5-2: Umbral para pegar y convertir texto largo en archivos(Número de caracteres,Si excede el límite, se le pedirá que transfiera el archivo.). */
    val pasteAsFileThresholdFlow: Flow<Int> get() = chatSettings.pasteAsFileThresholdFlow

    /** P1-4: Final de carrera contextual basado en el suelo(Tomar mensaje de USUARIO como piso,Mantenga los N niveles más recientes de conversaciones completas). */
    val floorLimiterEnabledFlow: Flow<Boolean> get() = chatSettings.floorLimiterEnabledFlow

    /** P1-4: El contexto basado en pisos limita el número de pisos(8/16/32,Predeterminado 16). */
    val floorLimitFlow: Flow<Int> get() = chatSettings.floorLimitFlow

    /** v2.x: Límite de ronda de herramientas(0=Ilimitado;1..N Establecer valor para el usuario). */
    val toolLoopMaxRoundsFlow: Flow<Int> get() = chatSettings.toolLoopMaxRoundsFlow

    /** C3: Lista de ID de sesiones de navegación recientes(Más reciente primero,Ir a restablecer arriba,Máximo 10,Se puede recuperar rápidamente si se devuelve por error). */
    val recentSessionsFlow: Flow<List<String>> get() = chatSettings.recentSessionsFlow
    val memoryEnabledFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_MEMORY_ENABLED] ?: true }

    /** v1.0.51: ¿Se ha completado la migración de la memoria existente?(Resumen de la sesión del historial del primer inicio después de la actualización). */
    val memoryBackfillMigrationDoneFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs -> prefs[KEY_MEMORY_MIGRATION_V1_0_51_DONE] ?: false }

    /**
     * v1.0.52 P2-2: Espacio de memoria actualmente seleccionado id(Predeterminado "default").
     * El usuario escribe cuando la página de memoria cambia de espacio.,MemoryViewModel Filtre la lista de hechos por spaceId después de leer.
     */
    val currentSpaceIdFlow: Flow<String> = store.data.mapDistinct { prefs -> prefs[KEY_CURRENT_SPACE_ID] ?: "default" }
    val themeModeFlow: Flow<String> get() = appearance.themeModeFlow

    /** H5: Cambio de tema de alto contraste(Mejorar las perspectivas/contraste de fondo,Para usuarios con baja visión). */
    val highContrastFlow: Flow<Boolean> get() = appearance.highContrastFlow

    /** v2.5.0: modo de vidrio líquido(off/water/frost)con intensidad(0..100). */
    val liquidGlassModeFlow: Flow<String> get() = appearance.liquidGlassModeFlow
    val liquidGlassStrengthFlow: Flow<Int> get() = appearance.liquidGlassStrengthFlow

    /** v1.60-C: Idioma de la interfaz de la aplicación(system=Sigue el sistema / zh=chino / en=ingles / ja=japonés / ko=coreano / ru=ruso). */
    val languageFlow: Flow<String> get() = appSettings.languageFlow
    val themeIdFlow: Flow<String> get() = appearance.themeIdFlow

    /** Tema independiente del modo oscuro id(Una cadena vacía indica una versión oscura que sigue el tema claro.). */
    val darkThemeIdFlow: Flow<String> get() = appearance.darkThemeIdFlow

    /** Configuración de conmutación programada de tema. */
    val themeScheduleFlow: Flow<ThemeScheduleConfig> get() = appearance.themeScheduleFlow

    /** v1.65: Material You Interruptor de selección de color dinámico(Android 12+). */
    val dynamicColorFlow: Flow<Boolean> get() = appearance.dynamicColorFlow

    /**
     * v1.97 gap7: Lista de temas definidos por el usuario — Genera un ColorScheme basado en el color de la semilla.
     *
     * Persistente como matriz JSON,Devuelve una lista vacía en la primera instalación.(No se crearon temas personalizados).
     * Recurrir a la lista vacía cuando falla el análisis,Evite la corrupción de un solo tema que provoque que Flow falle permanentemente.
     */
    val customThemesFlow: Flow<List<CustomTheme>> get() = appearance.customThemesFlow

    val fontSizeScaleFlow: Flow<String> get() = appearance.fontSizeScaleFlow

    /** E2: Ruta de archivo de fuente de texto personalizada(filesDir/fonts/ abajo);null Indica el valor predeterminado del sistema. */
    val customFontPathFlow: Flow<String?> get() = appearance.customFontPathFlow

    /** v1.95: Iniciar página predeterminada(0=Tarea, 1=Agent, 2=chat grupal). */
    val defaultHomePageFlow: Flow<Int> get() = appearance.defaultHomePageFlow
    val onboardingShownFlow: Flow<Boolean> get() = appearance.onboardingShownFlow

    // v1.95: El reconocimiento de voz del sistema pregunta por primera vez si se ha mostrado(Solo indica cuando se usa por primera vez,Llámalo directamente más tarde Intent)
    val asrTipShownFlow: Flow<Boolean> get() = appearance.asrTipShownFlow

    // v1.95: Cambio de biblioteca de emoticonos(Desactivado de forma predeterminada);Después de encenderlo, el modelo puede enviar emoticonos al responder.
    val stickerEnabledFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_STICKER_ENABLED] ?: false }

    // v1.95: Probabilidad de enviar emoticones(0-100,Predeterminado 30);El modelo tiene esta probabilidad de llamar cada vez que responde. send_sticker
    val stickerSendProbabilityFlow: Flow<Int> = store.data.mapDistinct { prefs -> prefs[KEY_STICKER_SEND_PROBABILITY] ?: 30 }

    // v2.x: Rango de frecuencia de envío de emoticonos(occasionally/normal/frequent,Predeterminado normal);Marque el enlace para la inyección del aviso del sistema
    val stickerFrequencyFlow: Flow<String> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_STICKER_FREQUENCY] ?: STICKER_FREQ_NORMAL
        }

    // v2.x: Límite de caché de memoria de sesión(Avanzado,Predeterminado 5;2..12)
    val sessionCacheSizeFlow: Flow<Int> = store.data.mapDistinct { prefs -> prefs[KEY_SESSION_CACHE_SIZE] ?: 5 }

    // v1.135: llamar WebSearchConfig.decrypted() Descifrado unificado apiKey + apiKeys,Y sincronice la antigua clave única con el mapeo de apiKeys
    val webSearchConfigFlow: Flow<WebSearchConfig> =
        store.data.mapDistinct { prefs ->
            val config =
                decodePrefsOrNull(
                    prefs[KEY_WEB_SEARCH_CONFIG],
                    WebSearchConfig.serializer(),
                    "WebSearchConfig",
                )?.decrypted() ?: WebSearchConfig()
            // Migración de estrategia:
            //  v1 Configuración antigua sin número de versión,Posibilidad de guardar el antiguo presupuesto predeterminado. 2;
            //  v2(esta vez):Liberalización presupuestaria —— Históricamente, estaba bloqueado por defecto. 5(Y el lado de Koin coerceIn(1,5)),
            //   dando como resultado el modelo“no puedo encontrarlo”. Las configuraciones que no hayan alcanzado la versión 2 se actualizarán a la nueva configuración predeterminada 50(El usuario puede ajustar).
            if (config.policyVersion < 2) {
                config.copy(maxSearchesPerTurn = 50, policyVersion = 2)
            } else {
                config
            }
        }.catch {
            // M-SR3: Descifrar/Analizar el valor predeterminado de reserva de excepción,Evite el fallo permanente de Flow
            Logger.w("SettingsRepository", "webSearchConfigFlow anormal,Volver al valor predeterminado", it)
            emit(WebSearchConfig())
        }

    // H-SR2: CloudBackupConfig Contiene credenciales confidenciales como s3SecretKey/webdavPassword,Tanto leer como escribir SecureKeyStore
    val cloudBackupConfigFlow: Flow<CloudBackupConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_CLOUD_BACKUP_CONFIG], CloudBackupConfig.serializer(), "CloudBackupConfig")
                ?.decrypted()
                ?: CloudBackupConfig()
        }.catch {
            Logger.w("SettingsRepository", "cloudBackupConfigFlow anormal,Volver al valor predeterminado", it)
            emit(CloudBackupConfig())
        }

    // ── v1.132: Flujo de configuración detallada de la copia de seguridad en la nube(Proporciona enlace bidireccional para formularios de CloudBackupPage) ──
    // H8: WebServerConfig Contiene credenciales sensibles a contraseña/PIN,Tanto leer como escribir SecureKeyStore
    val webServerConfigFlow: Flow<WebServerConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_WEB_SERVER_CONFIG], WebServerConfig.serializer(), "WebServerConfig")
                ?.decrypted()
                ?: WebServerConfig()
        }
    val asrConfigFlow: Flow<AsrConfig> =
        store.data.mapDistinct {
                prefs ->
            decodePrefsOrNull(prefs[KEY_ASR_CONFIG], AsrConfig.serializer(), "AsrConfig")?.let {
                    c ->
                c.copy(apiKey = SecureKeyStore.decrypt(c.apiKey))
            } ?: AsrConfig()
        }
            .catch {
                Logger.w("SettingsRepository", "asrConfigFlow anormal,Volver al valor predeterminado", it)
                emit(AsrConfig())
            }

    /** v1.54: RAG Configuración(embedding fuente + parámetros de búsqueda). */
    val ragConfigFlow: Flow<RagConfig> =
        store.data.mapDistinct { prefs ->
            // La ruta de lectura solo decodifica y no reescribe la configuración.v1.0.53 La migración de usuarios heredados ha convergido a
            // migrateRagEmbeddingSourceIfNeeded()(Ejecutar una vez al inicio);Sobrescribir aquí daría como resultado
            // Después de que el usuario selecciona "palabras clave locales", cada lectura se devuelve a CLOUD.
            decodePrefsOrNull(prefs[KEY_RAG_CONFIG], RagConfig.serializer(), "RagConfig") ?: RagConfig()
        }

    // H-SR2: MCP Tanto los tokens estáticos como el ID/secreto de la aplicación Feishu utilizan SecureKeyStore.
    val mcpServersFlow: Flow<List<io.zer0.muse.mcp.McpServerConfig>> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_MCP_SERVERS], ListSerializer(io.zer0.muse.mcp.McpServerConfig.serializer()), "McpServers")
                ?.map { it.decrypted() }
                ?: emptyList()
        }.catch {
            Logger.w("SettingsRepository", "mcpServersFlow anormal,lista vacía alternativa", it)
            emit(emptyList())
        }

    /** v1.58: Prompt Lista de plantillas(La primera lectura devuelve la plantilla incorporada.,Persistencia general después de las modificaciones del usuario.). */
    val promptTemplatesFlow: Flow<List<PromptTemplate>> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(
                prefs[KEY_PROMPT_TEMPLATES],
                ListSerializer(PromptTemplate.serializer()),
                "PromptTemplates",
            ) ?: PromptTemplate.getBuiltInPromptTemplates(appContext)
        }

    // v0.30-a: Retrato de usuario(6 Utilice la sección de retrato de usuario en el paso 1 del flujo de trabajo.)
    val userProfileFlow: Flow<UserProfile> =
        store.data.mapDistinct { prefs ->
            val profile = decodePrefsOrNull(prefs[KEY_USER_PROFILE], UserProfile.serializer(), "UserProfile") ?: UserProfile()
            // B0-09: Fusionar claves de cuentas antiguas,Apodo antiguo garantizado después de la primera actualización/El avatar no se pierde
            profile.copy(
                userNickName = profile.userNickName ?: prefs[KEY_ACCOUNT_USER_NAME],
                avatarUri = prefs[KEY_ACCOUNT_AVATAR_URI] ?: profile.avatarUri,
            )
        }

    // v0.31: Preferencias de comportamiento en el chat(Embalaje y almacenamiento,Serializar una vez)
    // v0.31: Preferencias de comportamiento en el chat(Embalaje y almacenamiento,Serializar una vez)
    val chatPreferencesFlow: Flow<ChatPreferences> get() = chatSettings.chatPreferencesFlow

    // v0.32: Configuración avanzada del sistema de memoria
    val memoryConfigFlow: Flow<io.zer0.memory.ticker.MemoryConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(
                prefs[KEY_MEMORY_CONFIG],
                io.zer0.memory.ticker.MemoryConfig.serializer(),
                "MemoryConfig",
            ) ?: io.zer0.memory.ticker.MemoryConfig()
        }

    // v0.32: Política de notificación(never / when_unfocused / always)
    val notificationPolicyFlow: Flow<String> get() = security.notificationPolicyFlow

    // v0.32: Cambio de biblioteca de experiencias(Desactivado de forma predeterminada)
    val experienceEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_EXPERIENCE_ENABLED] ?: false
        }

    // PII Guard:Enmascare automáticamente información confidencial antes de enviar mensajes a LLM(tarjeta de identificación/teléfono celular/Correo electrónico, etc.),Habilitado de forma predeterminada.

    // v1.0.63: Asistente predeterminado para nuevas tareas(Vincularse cada vez que inicie una nueva tarea)
    val defaultAssistantIdFlow: Flow<String> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_DEFAULT_ASSISTANT_ID] ?: "default"
        }

    val piiGuardEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_PII_GUARD_ENABLED] ?: true
        }

    // ANR Interruptor de detección(Predeterminado true),Para que AnrWatcher lea sincrónicamente cuando se ejecuta.
    val anrDetectionFlow: Flow<Boolean> get() = security.anrDetectionFlow

    // v0.32: mantente despierto(Desactivado de forma predeterminada)
    val keepAwakeFlow: Flow<Boolean> get() = security.keepAwakeFlow

    // v0.32: Iniciar automáticamente al arrancar(Desactivado de forma predeterminada)
    val autoLaunchFlow: Flow<Boolean> get() = security.autoLaunchFlow

    // v0.32: Interruptor de función experimental(Embalaje y almacenamiento)
    val experimentsFlow: Flow<ExperimentsConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_EXPERIMENTS], ExperimentsConfig.serializer(), "ExperimentsConfig") ?: ExperimentsConfig()
        }

    // v0.32: Compartir configuración de plantilla
    val shareTemplateFlow: Flow<ShareTemplateConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_SHARE_TEMPLATE], ShareTemplateConfig.serializer(), "ShareTemplateConfig") ?: ShareTemplateConfig()
        }

    // v0.32: configuración de medios(Grabación de voz/salida de audio)
    // P0-2: MediaConfig Contiene ttsApiKey,Descifrar después de leer(La versión anterior de texto sin formato es compatible con la transmisión transparente de descifrado.)
    val mediaConfigFlow: Flow<MediaConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_MEDIA_CONFIG], MediaConfig.serializer(), "MediaConfig")?.decrypted() ?: MediaConfig()
        }

    // Configuración del proxy de red global
    // H-SR2: ProxyConfig.password son credenciales sensibles,Pase ProxyConfig.decrypted() Descifrar después de leer
    // (La antigua contraseña en texto plano es compatible con la transmisión transparente de descifrado.),Llamado por saveProxyConfig al escribir encrypted() Cifrado
    val proxyConfigFlow: Flow<ProxyConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_PROXY_CONFIG], ProxyConfig.serializer(), "ProxyConfig")?.let { c -> c.decrypted() } ?: ProxyConfig()
        }.catch {
            Logger.w("SettingsRepository", "proxyConfigFlow anormal,Volver al valor predeterminado(Sin proxy)", it)
            emit(ProxyConfig())
        }

    // Configuración de mensajes activos(El asistente envía mensajes de forma proactiva a los usuarios a intervalos regulares, como una persona real.)
    val proactiveMessageConfigFlow: Flow<ProactiveMessageConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(
                prefs[KEY_PROACTIVE_MESSAGE],
                ProactiveMessageConfig.serializer(),
                "ProactiveMessageConfig",
            ) ?: ProactiveMessageConfig()
        }

    // v0.34: Configuración de parámetros predeterminados de generación de imágenes(Tamaño/calidad/estilo/Cantidad)
    val imageGenConfigFlow: Flow<ImageGenConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_IMAGE_GEN_CONFIG], ImageGenConfig.serializer(), "ImageGenConfig") ?: ImageGenConfig()
        }

    /** Flujo de configuración de parámetros predeterminados de generación de video(Configuración de usuario en la página de configuración.). */
    val videoGenConfigFlow: Flow<VideoGenConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_VIDEO_GEN_CONFIG], VideoGenConfig.serializer(), "VideoGenConfig") ?: VideoGenConfig()
        }

    // v1.25: Configuración de colaboración multiagente(Lista de equipos y cambio maestro)
    // v1.201: fusionar independiente DataStore key(multi_agent_review_model / multi_agent_llm_review_enabled)
    //         llegar MultiAgentConfig —— Estos dos campos son @Transient,No serializado con JSON,
    //         Leer y escribir por separado mediante clave independiente.,Evite condiciones de carrera de doble escritura entre updateMultiAgentConfig y el método de guardado independiente.
    val multiAgentConfigFlow: Flow<MultiAgentConfig> =
        store.data.mapDistinct { prefs ->
            val base =
                decodePrefsOrNull(
                    prefs[KEY_MULTI_AGENT_CONFIG],
                    MultiAgentConfig.serializer(),
                    "MultiAgentConfig",
                ) ?: MultiAgentConfig()
            base.copy(
                reviewModelId = prefs[KEY_MULTI_AGENT_REVIEW_MODEL],
                llmReviewEnabled = prefs[KEY_MULTI_AGENT_LLM_REVIEW_ENABLED] ?: false,
            )
        }

    // v1.25: Interruptor de ayuda visual(Desactivado de forma predeterminada)
    val visionEnabledFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_VISION_ENABLED] ?: false }

    // v1.25: Modelos utilizados como ayudas visuales. ID
    val visionModelIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_VISION_MODEL_ID] }

    // v1.25: Proveedores de ayudas visuales. ID
    val visionProviderIdFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_VISION_PROVIDER_ID] }

    // ── Account state ──
    val accountStateFlow: Flow<AccountState> =
        store.data.mapDistinct { prefs ->
            val storedProfile =
                decodePrefsOrNull(
                    prefs[KEY_USER_PROFILE],
                    UserProfile.serializer(),
                    "UserProfile(accountState)",
                )
            AccountState(
                isLoggedIn = prefs[KEY_ACCOUNT_LOGGED_IN] ?: false,
                userName = prefs[KEY_ACCOUNT_USER_NAME] ?: "",
                loginAt = prefs[KEY_ACCOUNT_LOGIN_AT] ?: 0L,
                loginMethod = prefs[KEY_ACCOUNT_LOGIN_METHOD] ?: "",
                isGuestMode = prefs[KEY_ACCOUNT_GUEST_MODE] ?: false,
                // La clave de cuenta dedicada es la principal fuente de avatares;Portrait JSON como datos heredados/Recuperación después de una reescritura anormal.
                avatarUri = prefs[KEY_ACCOUNT_AVATAR_URI] ?: storedProfile?.avatarUri,
            )
        }

    /** ¿Estás conectado?(marca local). */

    // v2.3: Configuración de enrutamiento de tareas Flujo + caché(Debe declararse antes del bloque de inicio.,De lo contrario, la rutina en init accede null)
    val taskRoutingConfigFlow: Flow<TaskRoutingConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_TASK_ROUTING_CONFIG], TaskRoutingConfig.serializer(), "TaskRoutingConfig")
                ?: TaskRoutingConfig()
        }

    @Volatile
    var taskRoutingConfigCache: TaskRoutingConfig = TaskRoutingConfig()
        private set

    // ── v1.133: Aplicar configuración de verificación de actualización ───────────────────────────────────
    /** Marca de tiempo de la última actualización actualizada(milisegundos).0L Indica que nunca se revisó. */
    val lastUpdateCheckTimeFlow: Flow<Long> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_LAST_UPDATE_CHECK_TIME] ?: 0L
        }

    /**
     * Caché JSON de información de la última versión(serializado UpdateChecker.ReleaseInfo).
     * null Indica que actualmente no hay ninguna versión nueva.(o no marcado).UI Banner Suscríbase a esta transmisión para decidir si desea mostrarla.
     */
    val latestReleaseInfoFlow: Flow<String?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_LATEST_RELEASE_INFO]
        }

    /** Si se deben habilitar las comprobaciones de actualización automáticas(Predeterminado true;Los usuarios pueden desactivarlo en la configuración.). */
    val updateCheckEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_UPDATE_CHECK_ENABLED] ?: true
        }

    /**
     * v1.0.72: Número de versión de actualización que el usuario ignora activamente(tagName,Como por ejemplo "v1.0.71").
     * Banner No volver a mostrar esta versión,UpdateNotifier borrará el caché hasta que se actualice a esta versión.
     */
    val ignoredUpdateVersionFlow: Flow<String?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_IGNORED_UPDATE_VERSION]
        }

    /** v1.0.72: Guarda los números de versión de las actualizaciones ignoradas por el usuario. */
    suspend fun saveIgnoredUpdateVersion(version: String?) {
        store.edit {
            if (version == null) it.remove(KEY_IGNORED_UPDATE_VERSION) else it[KEY_IGNORED_UPDATE_VERSION] = version
        }
    }

    /**
     * v1.0.72: Pulsador de resumen diario(Desactivado de forma predeterminada).
     * Todos los días a las 09:0012:00,21:00,00:00 generar dia/Resumen de la conversación del día anterior.;La generación del resumen de la página de inicio aún se conserva cuando se cierra.
     */
    val dailySummaryEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            // v1.0.74: Cambiado a desactivado de forma predeterminada — Los nuevos usuarios no deberían preocuparse por las notificaciones programadas
            prefs[KEY_DAILY_SUMMARY_ENABLED] ?: false
        }

    /** v1.0.72: Guarde el interruptor de resumen diario. */
    suspend fun saveDailySummaryEnabled(enabled: Boolean) {
        store.edit { it[KEY_DAILY_SUMMARY_ENABLED] = enabled }
    }

    /**
     * v1.xxx: Período de resumen diario(24 hora por hora,almacenamiento separado por comas).
     * Volver al período predeterminado si no está configurado [DEFAULT_DAILY_SUMMARY_SLOTS](09/12/21/00),Asegúrese de que el comportamiento de los usuarios antiguos permanezca sin cambios después de la actualización.
     * Worker Durante la ejecución, este período se utiliza para determinar si se activa el punto de tiempo actual.
     */
    val dailySummarySlotsFlow: Flow<List<Int>> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_DAILY_SUMMARY_SLOTS]
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in 0..23 }
                ?.distinct()
                ?.sorted()
                ?: DEFAULT_DAILY_SUMMARY_SLOTS
        }

    /** v1.xxx: Guardar período de resumen diario(Elimina automáticamente duplicados, ordena y filtra horas ilegales;Volver al valor predeterminado cuando esté vacío). */
    suspend fun saveDailySummarySlots(slots: List<Int>) {
        val normalized =
            slots
                .mapNotNull { it }
                .filter { it in 0..23 }
                .distinct()
                .sorted()
        store.edit {
            if (normalized.isEmpty()) {
                // Borrar la entrada del usuario se considera como restaurar el valor predeterminado.,evitar"Todos los períodos de tiempo están deshabilitados."estado desatendido
                it.remove(KEY_DAILY_SUMMARY_SLOTS)
            } else {
                it[KEY_DAILY_SUMMARY_SLOTS] = normalized.joinToString(",")
            }
        }
    }

    /**
     * Resumen diario de la generación exitosa más reciente.
     *
     * Worker Incluso si se omite la notificación porque la aplicación está en primer plano,También lo escribiré aquí primero.,
     * Deje que el saludo de la página de inicio siga mostrando contenido resumido.
     */
    val dailySummaryFlow: Flow<DailySummarySnapshot?> =
        store.data.mapDistinct { prefs ->
            val date = prefs[KEY_DAILY_SUMMARY_DATE]
            val text = prefs[KEY_DAILY_SUMMARY_TEXT]?.trim()
            if (date.isNullOrBlank() || text.isNullOrBlank()) {
                null
            } else {
                DailySummarySnapshot(date = date, text = text)
            }
        }

    /**
     * Aprovecha un tiempo de resumen diario.
     *
     * El mismo momento puede ser activado por WorkManager y la programación en proceso al mismo tiempo.DataStore.edit una sola transacción
     * Garantizado que solo pase un ejecutor. Conserve únicamente los 16 logotipos más recientes para evitar el crecimiento a largo plazo.
     */
    suspend fun claimDailySummarySlot(slotKey: String): Boolean {
        val cleanKey = slotKey.trim()
        if (cleanKey.isBlank()) return false
        var claimed = false
        store.edit { prefs ->
            val keys = (prefs[KEY_DAILY_SUMMARY_CLAIMED_SLOTS] ?: emptySet()).toMutableSet()
            if (cleanKey !in keys) {
                keys += cleanKey
                if (keys.size > 16) {
                    val retained = keys.toList().sorted().takeLast(16).toSet()
                    prefs[KEY_DAILY_SUMMARY_CLAIMED_SLOTS] = retained
                } else {
                    prefs[KEY_DAILY_SUMMARY_CLAIMED_SLOTS] = keys
                }
                claimed = true
            }
        }
        return claimed
    }

    /** El punto de tiempo del resumen diario marcado se ha guardado correctamente y se puede distinguir después de reiniciar el proceso.“Se adelantó pero se estrelló a mitad de camino”y“Completado”. */
    suspend fun completeDailySummarySlot(slotKey: String) {
        val cleanKey = slotKey.trim()
        if (cleanKey.isBlank()) return
        store.edit { prefs ->
            val keys = (prefs[KEY_DAILY_SUMMARY_COMPLETED_SLOTS] ?: emptySet()).toMutableSet()
            keys += cleanKey
            prefs[KEY_DAILY_SUMMARY_COMPLETED_SLOTS] =
                if (keys.size > DAILY_SUMMARY_SLOT_HISTORY_LIMIT) {
                    keys.toList().sorted().takeLast(DAILY_SUMMARY_SLOT_HISTORY_LIMIT).toSet()
                } else {
                    keys
                }
        }
    }

    /** Compruebe si el punto de tiempo del resumen diario se ha guardado correctamente. */
    suspend fun isDailySummarySlotCompleted(slotKey: String): Boolean {
        val cleanKey = slotKey.trim()
        if (cleanKey.isBlank()) return false
        return store.data.first()[KEY_DAILY_SUMMARY_COMPLETED_SLOTS]?.contains(cleanKey) == true
    }

    /** Cuando falla la generación, el titular del punto de tiempo se libera para que se pueda volver a intentar el siguiente activador de WorkManager/en proceso. */
    suspend fun releaseDailySummarySlot(slotKey: String) {
        val cleanKey = slotKey.trim()
        if (cleanKey.isBlank()) return
        store.edit { prefs ->
            val keys = prefs[KEY_DAILY_SUMMARY_CLAIMED_SLOTS]?.toMutableSet() ?: return@edit
            if (keys.remove(cleanKey)) {
                prefs[KEY_DAILY_SUMMARY_CLAIMED_SLOTS] = keys
            }
        }
    }

    /** Consultar si un determinado punto de tiempo de resumen diario se ha adelantado con éxito,Proporciona activación suplementaria frontal para evitar despertar repetidamente al trabajador. */
    suspend fun isDailySummarySlotClaimed(slotKey: String): Boolean {
        val cleanKey = slotKey.trim()
        if (cleanKey.isBlank()) return false
        return store.data.first()[KEY_DAILY_SUMMARY_CLAIMED_SLOTS]?.contains(cleanKey) == true
    }

    /** Guardar resumen diario,Guarde la fecha y el texto por separado,Evite que los delimitadores del cuerpo interrumpan el análisis. */
    suspend fun saveDailySummary(date: String, summary: String) {
        val cleanDate = date.trim()
        val cleanSummary = summary.trim().take(200)
        if (cleanDate.isBlank() || cleanSummary.isBlank()) return
        store.edit {
            it[KEY_DAILY_SUMMARY_DATE] = cleanDate
            it[KEY_DAILY_SUMMARY_TEXT] = cleanSummary
        }
    }

    /**
     * v1.0.72: AI Número diario de elementos dinámicos en Momentos(0-10,Predeterminado 2;0 = cerrar).
     * El usuario selecciona libremente la frecuencia,El planificador divide el día en segmentos según la cantidad de artículos y los entrega.
     */
    val dailyMomentCountFlow: Flow<Int> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_DAILY_MOMENT_COUNT] ?: 2
        }

    /** v1.0.72: Guarde la cantidad diaria de publicaciones en Momentos. */
    suspend fun saveDailyMomentCount(count: Int) {
        store.edit { it[KEY_DAILY_MOMENT_COUNT] = count.coerceIn(0, 10) }
    }

    /**
     * v2.6.7(P0-1): Modo de contenido de momentos.
     *
     * Hay dos niveles: tipo real/tipo de personaje, que los usuarios pueden elegir según su propia jugabilidad. Predeterminado al sistema real (más conservador,
     * No habrá contenido que los usuarios no hayan experimentado). Determina la dinámica de tu círculo de amigos,AI Una perspectiva generativa sobre comentarios, diarios.
     */
    val momentContentModeFlow: Flow<io.zer0.muse.data.moment.MomentContentMode> =
        store.data.mapDistinct { prefs ->
            io.zer0.muse.data.moment.MomentContentMode.fromStorage(prefs[KEY_MOMENT_CONTENT_MODE])
        }

    /** Lea el modo de contenido actual una vez (para generación omitida, sin recopilación continua). */
    suspend fun momentContentMode(): io.zer0.muse.data.moment.MomentContentMode = momentContentModeFlow.first()

    /** Modo de contenido Guardar Momentos. */
    suspend fun saveMomentContentMode(mode: io.zer0.muse.data.moment.MomentContentMode) {
        store.edit { it[KEY_MOMENT_CONTENT_MODE] = mode.toStorage() }
    }

    // ── v2.6.9: Residente se mantiene vivo. ─────────────────────────────────────────────

    /** Interruptor de mantenimiento de vida residente (apagado de forma predeterminada). Después de encenderlo, inicie KeepAliveService para fijar el proceso en primer plano. */
    val keepAliveEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs -> prefs[KEY_KEEP_ALIVE_ENABLED] ?: false }

    suspend fun saveKeepAliveEnabled(enabled: Boolean) {
        store.edit { it[KEY_KEEP_ALIVE_ENABLED] = enabled }
    }

    /** Si se debe mantener WakeLock cuando sea residente (desactivado de forma predeterminada). Apagar la pantalla después de encenderla también mantiene activa la CPU, lo que consume más energía. */
    val keepAliveWakeLockFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs -> prefs[KEY_KEEP_ALIVE_WAKELOCK] ?: false }

    suspend fun saveKeepAliveWakeLock(enabled: Boolean) {
        store.edit { it[KEY_KEEP_ALIVE_WAKELOCK] = enabled }
    }

    /** Lea el interruptor WakeLock una vez (para uso interno del servicio, no se requiere recopilación continua). */
    suspend fun keepAliveWakeLockFlowSnapshot(): Boolean = keepAliveWakeLockFlow.first()

    /** v2.6.9: Título de notificación permanente (cadena vacía = Usar copia predeterminada). */
    val keepAliveNotificationTitleFlow: Flow<String> =
        store.data.mapDistinct { prefs -> prefs[KEY_KEEP_ALIVE_NOTIFICATION_TITLE] ?: "" }

    /** v2.6.9: Texto de notificación permanente (cadena vacía = Usar copia predeterminada). */
    val keepAliveNotificationTextFlow: Flow<String> =
        store.data.mapDistinct { prefs -> prefs[KEY_KEEP_ALIVE_NOTIFICATION_TEXT] ?: "" }

    suspend fun saveKeepAliveNotification(title: String, text: String) {
        store.edit {
            it[KEY_KEEP_ALIVE_NOTIFICATION_TITLE] = title.trim()
            it[KEY_KEEP_ALIVE_NOTIFICATION_TEXT] = text.trim()
        }
    }

    /** Lea la copia de la notificación una vez (se utiliza cuando el servicio crea la notificación). */
    suspend fun keepAliveNotificationSnapshot(): Pair<String, String> =
        keepAliveNotificationTitleFlow.first() to keepAliveNotificationTextFlow.first()

    // ── v1.xxx: Control maestro de programación backend(Pausa global periódica del trabajador/restaurar) ─────────────────

    /**
     * Interruptor de control maestro de despacho backend(Habilitado por defecto).
     * Cada ciclo de WorkManager después del apagado Worker(tareas programadas/mensajes no solicitados/Copia de seguridad automática/Estadísticas/Copia de seguridad en la nube)en
     * doWork La entrada comprueba este valor.,Si se cierra, se omitirá el cuerpo de ejecución y se devolverá el éxito directamente. El calendario periódico en sí sigue siendo,
     * Reanudar la ejecución inmediatamente después de la reapertura(con"Saltar cuerpo de ejecución"en vez de"Cancelar tarea"La consistencia semántica de).
     */
    val scheduleWorkEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_SCHEDULE_WORK_ENABLED] ?: true
        }

    /** v1.xxx: Guarde el interruptor de control maestro de programación en segundo plano. */
    suspend fun saveScheduleWorkEnabled(enabled: Boolean) {
        store.edit { it[KEY_SCHEDULE_WORK_ENABLED] = enabled }
    }

    /**
     * Interruptor maestro enchufable (habilitado de forma predeterminada).
     *
     * Después de cerrar todas las herramientas complementarias,Hook, la activación de inicio no se ejecuta en absoluto —— pero**No desinstalar**cualquier cosa:
     * Los archivos, las configuraciones y el estado habilitado se conservan tal como están y se restauran cuando se vuelven a abrir.
     *
     * Por qué lo necesita: cuando hay un problema con un complemento, los únicos métodos existentes son "deshabilitar uno por uno" y "desinstalar todos".
     * El primero no está disponible cuando hay más de una docena de complementos instalados y el segundo perderá la configuración.
     */
    val pluginsGloballyEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_PLUGINS_ENABLED] ?: true
        }

    /** Guarde el interruptor maestro enchufable. */
    suspend fun savePluginsGloballyEnabled(enabled: Boolean) {
        store.edit { it[KEY_PLUGINS_ENABLED] = enabled }
    }

    /** Interruptor maestro del complemento de lectura única (para la ejecución de ruta activa, no es necesario abrir una suscripción). */
    suspend fun arePluginsGloballyEnabled(): Boolean = pluginsGloballyEnabledFlow.first()

    // ── v1.0.74: Actuar de forma autónoma a altas horas de la noche(Los diarios escritos fuera del período de tiempo no serán enviados.) ─────────────────

    /** Interruptor de acción autónomo nocturno(Habilitado por defecto:Escribir un diario automáticamente durante las inspecciones fuera de horario,no empujes). */
    val nightPatrolEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_NIGHT_PATROL_ENABLED] ?: true
        }

    /** v1.0.74: Guarde el interruptor de acción autónoma nocturna. */
    suspend fun saveNightPatrolEnabled(enabled: Boolean) {
        store.edit { it[KEY_NIGHT_PATROL_ENABLED] = enabled }
    }

    // ── v2.6.2(comentarios de los usuarios): Notificación → Interruptor de tarjeta de memoria ─────────────────────

    /**
     * Interruptor de memoria de notificaciones (desactivado por defecto).
     *
     * Después de encenderlo, el asistente clasificará periódicamente las notificaciones recibidas (modelo de herramienta Zou Da) y enumerará las que vale la pena recordar (entrega urgente)./horario/
     * factura/Acuerdos, etc.) se escriben en la memoria y se mencionan de forma natural en los mensajes activos. Desactivado de forma predeterminada —— La notificación contiene mucha información personal.
     * Información, habilitada explícitamente por el usuario.
     */
    val notificationMemoryEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_NOTIFICATION_MEMORY_ENABLED] ?: false
        }

    /** v2.6.2: Guardar interruptor de memoria de notificaciones. */
    suspend fun saveNotificationMemoryEnabled(enabled: Boolean) {
        store.edit { it[KEY_NOTIFICATION_MEMORY_ENABLED] = enabled }
    }

    // ── v2.6.2(comentarios de los usuarios): Configuración de la constelación de memoria ────────────────────────────

    /** Configuración de la constelación de memoria (AI Interruptor de profundización + frecuencia complementaria). */
    val memoryGraphConfigFlow: Flow<io.zer0.muse.data.MemoryGraphConfig> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(
                prefs[KEY_MEMORY_GRAPH_CONFIG],
                io.zer0.muse.data.MemoryGraphConfig.serializer(),
                "MemoryGraphConfig",
            ) ?: io.zer0.muse.data.MemoryGraphConfig()
        }

    /** Guarde la configuración de la constelación de memoria. */
    suspend fun saveMemoryGraphConfig(config: io.zer0.muse.data.MemoryGraphConfig) {
        store.edit {
            it[KEY_MEMORY_GRAPH_CONFIG] = AppJson.encodeToString(io.zer0.muse.data.MemoryGraphConfig.serializer(), config)
        }
    }

    // ── v1.0.74: Pequeño interruptor principal de teléfono celular.(Controla la visibilidad del pequeño icono de teléfono celular en la página de inicio) ─────────────────
    // v1.0.86: Desactivado de forma predeterminada — Comentarios de los usuarios"Después de instalar la aplicación, el teléfono pequeño la abrirá de forma predeterminada.",Se cambió a una nueva instalación y no está habilitado de forma predeterminada.;
    // Los usuarios que han activado manualmente conservan su selección(Su clave es persistente. true).

    /** Pequeño interruptor de función de teléfono celular.(Desactivado de forma predeterminada). */
    val miniPhoneEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MINIPHONE_ENABLED] ?: false
        }

    /** v1.0.74: Guarde el pequeño interruptor del teléfono. */
    suspend fun saveMiniPhoneEnabled(enabled: Boolean) {
        store.edit { it[KEY_MINIPHONE_ENABLED] = enabled }
    }

    /** Interruptor maestro de cápsula de grabación rápida(Desactivado de forma predeterminada,La manija deslizante lateral se muestra solo después de que el usuario accede a la configuración y la enciende.). */
    val quickCaptureEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_QUICK_CAPTURE_ENABLED] ?: false
        }

    /** Guarde el interruptor maestro de la cápsula de registro rápido. */
    suspend fun saveQuickCaptureEnabled(enabled: Boolean) {
        store.edit { it[KEY_QUICK_CAPTURE_ENABLED] = enabled }
    }

    /**
     * AUD-20261011-TASKER-ABUSE: Tasker/Interruptor maestro de enlace de transmisión automática externo(Desactivado de forma predeterminada).
     *
     * TaskerTriggerReceiver Es una entrada exportada sin permiso.,Se puede enviar cualquier solicitud.;
     * Desactivado de forma predeterminada garantiza"El usuario nunca ha habilitado"completamente insensible(Evite el abuso de costos y el spam de notificaciones).
     */
    val taskerTriggerEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_TASKER_TRIGGER_ENABLED] ?: false
        }

    /** Guarde el interruptor maestro de enlace de transmisión de Tasker. */
    suspend fun saveTaskerTriggerEnabled(enabled: Boolean) {
        store.edit { it[KEY_TASKER_TRIGGER_ENABLED] = enabled }
    }

    /**
     * Ya sea para habilitar la grabación rápida de ventanas flotantes del sistema(Desactivado de forma predeterminada,Requiere que el usuario otorgue permiso de ventana flotante).
     *
     * Retorno forzado antes de completar la migración única del valor predeterminado false,Evitar que se escriban versiones anteriores true
     * de los usuarios reiniciaron la ventana flotante del sistema instantáneamente después de la actualización.
     */
    val quickCaptureOverlayEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            if (prefs[KEY_QUICK_CAPTURE_OVERLAY_DEFAULT_MIGRATED] == true) {
                prefs[KEY_QUICK_CAPTURE_OVERLAY_ENABLED] ?: DEFAULT_QUICK_CAPTURE_OVERLAY_ENABLED
            } else {
                DEFAULT_QUICK_CAPTURE_OVERLAY_ENABLED
            }
        }

    /** Guardar el interruptor de registro rápido de la ventana flotante del sistema. */
    suspend fun saveQuickCaptureOverlayEnabled(enabled: Boolean) {
        store.edit {
            it[KEY_QUICK_CAPTURE_OVERLAY_ENABLED] = enabled
            it[KEY_QUICK_CAPTURE_OVERLAY_DEFAULT_MIGRATED] = true
        }
    }

    /**
     * Limpia lo que pueda quedar de la versión antigua de una vez“Habilitado por defecto”estado.
     *
     * Después de que el usuario lo active manualmente, persistirá normalmente.,No se borrará nuevamente en inicios posteriores.
     */
    suspend fun migrateQuickCaptureOverlayDefaultOffIfNeeded() {
        store.edit { prefs ->
            if (prefs[KEY_QUICK_CAPTURE_OVERLAY_DEFAULT_MIGRATED] != true) {
                prefs.remove(KEY_QUICK_CAPTURE_OVERLAY_ENABLED)
                prefs[KEY_QUICK_CAPTURE_OVERLAY_DEFAULT_MIGRATED] = true
            }
        }
    }

    /**
     * La posición vertical de la barra lateral de la ventana flotante del sistema.,Guardado usando una escala normalizada de 0..1.
     *
     * Guardar escala en lugar de píxeles,Evite que la posición vuelva al medio después de cambiar la resolución de la pantalla, la pantalla horizontal o vertical o el tamaño de la ventana de MuMu.
     */
    val quickCaptureOverlayVerticalPositionFractionFlow: Flow<Float> =
        store.data.mapDistinct { prefs ->
            (prefs[KEY_QUICK_CAPTURE_OVERLAY_VERTICAL_POSITION] ?: DEFAULT_QUICK_CAPTURE_OVERLAY_VERTICAL_POSITION)
                .coerceIn(0f, 1f)
        }

    /** Guarde la posición vertical de la barra lateral de la ventana flotante del sistema. */
    suspend fun saveQuickCaptureOverlayVerticalPositionFraction(position: Float) {
        store.edit {
            it[KEY_QUICK_CAPTURE_OVERLAY_VERTICAL_POSITION] = position.coerceIn(0f, 1f)
        }
    }

    /** AI El ID de la imagen del álbum que el usuario oculta. */
    val miniAlbumHiddenImageIdsFlow: Flow<Set<String>> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MINI_ALBUM_HIDDEN_IMAGE_IDS] ?: emptySet()
        }

    /** AI El ID de la imagen recopilada por el usuario en el álbum. */
    val miniAlbumFavoriteImageIdsFlow: Flow<Set<String>> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MINI_ALBUM_FAVORITE_IMAGE_IDS] ?: emptySet()
        }

    /** Cambie el estado de la colección de imágenes del álbum AI. */
    suspend fun toggleMiniAlbumFavoriteImage(imageId: String) {
        store.edit { prefs ->
            val current = prefs[KEY_MINI_ALBUM_FAVORITE_IMAGE_IDS] ?: emptySet()
            prefs[KEY_MINI_ALBUM_FAVORITE_IMAGE_IDS] =
                if (imageId in current) current - imageId else current + imageId
        }
    }

    /** Ocultar imágenes del álbum AI,Los mensajes de chat correspondientes no se eliminarán. */
    suspend fun hideMiniAlbumImage(imageId: String) {
        store.edit { prefs ->
            val current = prefs[KEY_MINI_ALBUM_HIDDEN_IMAGE_IDS] ?: emptySet()
            prefs[KEY_MINI_ALBUM_HIDDEN_IMAGE_IDS] = current + imageId
        }
    }

    /** Reanudar la visualización de imágenes del álbum AI. */
    suspend fun unhideMiniAlbumImage(imageId: String) {
        store.edit { prefs ->
            val current = prefs[KEY_MINI_ALBUM_HIDDEN_IMAGE_IDS] ?: emptySet()
            prefs[KEY_MINI_ALBUM_HIDDEN_IMAGE_IDS] = current - imageId
        }
    }

    // ── v1.0.74: Fondo de chat(charlar/Agent/Compartir chat grupal) ──────────────────────

    /** Imagen de fondo del chat(data URI o URL;null = Color de fondo predeterminado). */
    val chatBackgroundFlow: Flow<String?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_CHAT_BACKGROUND]
        }

    /**
     * v2.6.2(comentarios de los usuarios): Fondo dedicado al modo oscuro (opcional).
     *
     * El usuario enciende "Con el sistema/Al "cambiar la luz y la oscuridad con regularidad", espero que el fondo también cambie en consecuencia. Simplemente haz uno opcional aquí
     * Superposición oscura: el modo oscuro todavía se usa cuando no está configurado [chatBackgroundFlow] fondo principal, manteniendo el comportamiento anterior.
     */
    val chatBackgroundDarkFlow: Flow<String?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_CHAT_BACKGROUND_DARK]
        }

    /** Guardar fondo específico del modo oscuro (null = No hay distinción entre oscuros y claros, los colores oscuros también ocupan el fondo principal). */
    suspend fun saveChatBackgroundDark(uri: String?) {
        store.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(KEY_CHAT_BACKGROUND_DARK) else prefs[KEY_CHAT_BACKGROUND_DARK] = uri
        }
    }

    /** v1.0.74: Guarde la imagen de fondo del chat. */
    suspend fun saveChatBackground(uri: String?) {
        store.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(KEY_CHAT_BACKGROUND) else prefs[KEY_CHAT_BACKGROUND] = uri
        }
    }

    // ── v2.6.2(comentarios de los usuarios): Fondo de chat de tres capas (nivel de sesión > nivel asistente > incumplimiento global) ──

    /** Configuración de fondo a nivel de asistente (assistantId → ruta de la imagen). */
    val assistantChatBackgroundFlow: Flow<Map<String, String>> =
        store.data.mapDistinct { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val name = key.name
                if (name.startsWith(KEY_CHAT_BACKGROUND_ASSISTANT_PREFIX) && value is String) {
                    name.removePrefix(KEY_CHAT_BACKGROUND_ASSISTANT_PREFIX) to value
                } else {
                    null
                }
            }.toMap()
        }

    /** Configuración en segundo plano a nivel de sesión (sessionId → ruta de la imagen). */
    val sessionChatBackgroundFlow: Flow<Map<String, String>> =
        store.data.mapDistinct { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val name = key.name
                if (name.startsWith(KEY_CHAT_BACKGROUND_SESSION_PREFIX) && value is String) {
                    name.removePrefix(KEY_CHAT_BACKGROUND_SESSION_PREFIX) to value
                } else {
                    null
                }
            }.toMap()
        }

    /** Guardar fondo de nivel de asistente (null = Borre la anulación del asistente y vuelva a global). */
    suspend fun saveAssistantChatBackground(assistantId: String, path: String?) {
        if (assistantId.isBlank()) return
        val key = stringPreferencesKey("$KEY_CHAT_BACKGROUND_ASSISTANT_PREFIX$assistantId")
        store.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(key) else prefs[key] = path
        }
    }

    /** Guardar fondo a nivel de sesión (null = Borre la anulación para esta sesión y recurra al Asistente/en general). */
    suspend fun saveSessionChatBackground(sessionId: String, path: String?) {
        if (sessionId.isBlank()) return
        val key = stringPreferencesKey("$KEY_CHAT_BACKGROUND_SESSION_PREFIX$sessionId")
        store.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(key) else prefs[key] = path
        }
    }

    // ── E3: Fondo degradado dinámico de chat(La imagen de fondo tiene prioridad;Si ninguno de ellos está disponible, se utilizará el color de fondo predeterminado del tema.) ──

    /** fondo degradado de chat(Degradado lineal de dos colores;null = No usado). */
    val chatGradientFlow: Flow<ChatGradient?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_CHAT_GRADIENT]?.let { raw ->
                runCatching { AppJson.decodeFromString(ChatGradient.serializer(), raw) }
                    .onFailure { android.util.Log.w("SettingsRepository", "ChatGradient El análisis falló", it) }
                    .getOrNull()
            }
        }

    /** E3: guardar/fondo claro degradado de chat(null Borrar). */
    suspend fun saveChatGradient(gradient: ChatGradient?) {
        store.edit { prefs ->
            if (gradient == null) {
                prefs.remove(KEY_CHAT_GRADIENT)
            } else {
                prefs[KEY_CHAT_GRADIENT] = AppJson.encodeToString(ChatGradient.serializer(), gradient)
            }
        }
    }

    // ── v1.0.73: Fondo de portada de momentos ──────────────────────────────────────

    /** Imagen de fondo de portada de Moments(data URI o URL;null = Degradado predeterminado). */
    val momentsCoverImageFlow: Flow<String?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MOMENTS_COVER_IMAGE]
        }

    /**
     * v1.0.73: Guarde la imagen de fondo de portada de Moments.
     * P3-7: base64 data URI Ya no hay almacenamiento directo DataStore(Expansión de datos a nivel de sesión + riesgo de fuga de lista blanca de respaldo),
     * Después de decodificar el archivo, solo se guarda la ruta.;URL/Las rutas de archivos existentes se guardan sin cambios. Devuelve el valor eliminado real para uso inmediato por parte de la interfaz de usuario.
     */
    suspend fun saveMomentsCoverImage(uri: String?): String? = persistImageAsset("moments", COVER_IMAGE_SLOT, uri) { resolved ->
        store.edit { prefs ->
            if (resolved == null) prefs.remove(KEY_MOMENTS_COVER_IMAGE) else prefs[KEY_MOMENTS_COVER_IMAGE] = resolved
        }
    }

    // ── v1.0.73: Fondo de pantalla de escritorio de teléfono celular pequeño ─────────────────────────────────────

    /** Fondo de pantalla de escritorio de teléfono celular pequeño(data URI o URL;null = Degradado predeterminado). */
    val miniPhoneWallpaperFlow: Flow<String?> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MINIPHONE_WALLPAPER]
        }

    /** v1.0.73: Guarde el pequeño fondo de pantalla del escritorio del teléfono celular. */
    suspend fun saveMiniPhoneWallpaper(uri: String?): String? = persistImageAsset("moments", WALLPAPER_SLOT, uri) { resolved ->
        store.edit { prefs ->
            if (resolved == null) prefs.remove(KEY_MINIPHONE_WALLPAPER) else prefs[KEY_MINIPHONE_WALLPAPER] = resolved
        }
    }

    /**
     * P3-7: Asistencia con archivos de recursos de imágenes — data URI decodificar escribir filesDir/<dir>/<slot>.img,
     * Ruta del archivo de retorno(o pasado tal cual URL/file).null Borrar:Elimine el archivo y devuelva nulo.
     */
    private suspend fun persistImageAsset(dirName: String, slot: String, uri: String?, persist: suspend (String?) -> Unit): String? {
        val resolved =
            if (uri.isNullOrBlank()) {
                null
            } else if (uri.startsWith("data:", ignoreCase = true)) {
                val bytes = decodeDataUri(uri) ?: return null
                val file = java.io.File(java.io.File(appContext.filesDir, dirName), "$slot.img")
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                }
                file.absolutePath
            } else {
                // URL o camino existente:Almacenar como está
                uri
            }
        persist(resolved)
        return resolved
    }

    /** P3-7: analizar data:<mime>;base64,<payload> → bytes;Devuelve nulo en caso de error. */
    private fun decodeDataUri(uri: String): ByteArray? = runCatching {
        val comma = uri.indexOf(',')
        if (comma <= 0) return@runCatching null
        val base64 = uri.substring(comma + 1)
        android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    }.getOrNull()

    // ── v1.0.73: Estado no leído de Momentos ─────────────────────────────────────

    /** Lista de momentos hora de la última vez que se vio(Punto rojo no leído = Nuevo recuento de actividad). */
    val momentsLastReadAtFlow: Flow<Long> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MOMENTS_LAST_READ_AT] ?: 0L
        }

    /** v1.0.73: Registre el tiempo de navegación de Moments. */
    suspend fun markMomentsRead() {
        store.edit { it[KEY_MOMENTS_LAST_READ_AT] = System.currentTimeMillis() }
    }

    /** Hora de la última visualización del centro de mensajes(mensajes no leídos = Nuevos me gusta/Número de comentarios). */
    val momentMessagesLastReadAtFlow: Flow<Long> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MOMENT_MESSAGES_LAST_READ_AT] ?: 0L
        }

    /** v1.0.73: Registre el tiempo de navegación del centro de mensajes. */
    suspend fun markMomentMessagesRead() {
        store.edit { it[KEY_MOMENT_MESSAGES_LAST_READ_AT] = System.currentTimeMillis() }
    }

    /** Una colección de ID dinámicos de la colección de amigos del usuario. */
    val momentFavoriteIdsFlow: Flow<Set<String>> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_MOMENT_FAVORITE_IDS] ?: emptySet()
        }

    /** Guarde la colección de ID dinámicas del usuario en el círculo de amigos. */
    suspend fun saveMomentFavoriteIds(ids: Set<String>) {
        store.edit { it[KEY_MOMENT_FAVORITE_IDS] = ids }
    }

    // ── v2.0+: Informe de fallos ───────────────────────────────────────────────
    //
    // v2.6.13: Se ha solucionado la salida de informes (servidor de desarrollador → No regresar al correo electrónico del desarrollador),
    // Los usuarios ya no configuran "método de informe/recepción de correo electrónico/URL de webhook". Los viejos tres Flow vs.
    // correspondiente save* Los métodos están fuera de línea juntos —— Pedir a los usuarios finales que completen la infraestructura del desarrollador es inherentemente un mal diseño.
    // Información sobre la implementación de informes [io.zer0.muse.crash.CrashReporterFactory].

    /** Guarde la marca de tiempo de verificación de la última actualización. */
    suspend fun saveLastUpdateCheckTime(timestamp: Long) {
        store.edit { it[KEY_LAST_UPDATE_CHECK_TIME] = timestamp }
    }

    /**
     * Guarde la información de la última versión JSON(null Indica borrar caché).
     * [json] para serializado ReleaseInfo;null Indica que la versión actual es la última versión.,Borrar pancarta.
     */
    suspend fun saveLatestReleaseInfo(json: String?) {
        store.edit {
            if (json == null) it.remove(KEY_LATEST_RELEASE_INFO) else it[KEY_LATEST_RELEASE_INFO] = json
        }
    }

    /** guardar"Habilitar comprobaciones de actualizaciones automáticas"cambiar. */
    suspend fun saveUpdateCheckEnabled(enabled: Boolean) {
        store.edit { it[KEY_UPDATE_CHECK_ENABLED] = enabled }
    }

    // ── v1.0.20: Modo de permiso de sesión predeterminado global(Interruptor de aprobación de herramienta) ───────────────────────────

    /**
     * Modo de permiso de sesión predeterminado global — Controla si las llamadas a herramientas requieren la aprobación del usuario.
     *
     * tercera marcha:
     *  - [SessionPermissionMode.TRUSTED]:Delegación completa de autoridad,Todas las herramientas se llaman directamente.,No se requiere aprobación individual
     *    (Deshabilitar explícitamente/El rechazo total de parámetros y la propia zona de pruebas de la herramienta aún están vigentes.)
     *  - [SessionPermissionMode.ASK](Predeterminado):SAFE Las herramientas se liberan automáticamente.,NORMAL/HIGH Se requiere aprobación
     *  - [SessionPermissionMode.STRICT]:modo estricto,Sólo está disponible la herramienta de lista blanca,Todos los demás están prohibidos.
     *
     * El usuario está en"ajustes → charlar → Aprobación de llamada de herramienta"Cambiar entre.
     * Al crear una nueva sesión por [io.zer0.muse.data.session.SessionRepository] Lea este valor como modo de inicialización de sesión.
     */
    val defaultSessionPermissionModeFlow: Flow<SessionPermissionMode> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_DEFAULT_SESSION_PERMISSION_MODE]?.let { name ->
                runCatching { SessionPermissionMode.valueOf(name) }.getOrNull()
            } ?: SessionPermissionMode.ASK
        }

    /** Guarde el modo de permiso de sesión predeterminado global. */
    suspend fun setDefaultSessionPermissionMode(mode: SessionPermissionMode) {
        store.edit { it[KEY_DEFAULT_SESSION_PERMISSION_MODE] = mode.name }
    }

    /**
     * v1.114: cerrar cacheScope,Liberar recursos de rutina.
     * SettingsRepository Es un singleton de Koin,App Llamado a la salida.
     */
    fun shutdown() {
        cacheScope.cancel()
        Logger.i("SettingsRepository", "cacheScope cancelled")
    }

    init {
        cacheScope.launch { store.data.collect { prefs -> memoryEnabledCache.set(prefs[KEY_MEMORY_ENABLED] ?: true) } }
        // v0.32: Suscríbete memoryConfigFlow + experimentsFlow,Coloque la última MemoryConfig en la memoria caché,
        // Proporciona lectura de bloqueo cero para el cierre getConfig de MemoryTicker(ver [memoryConfigCache]).
        // v0.32 Acceso experimental a longMemoryCompression:Cuando el interruptor esté activado, reduzca el compileThreshold del valor predeterminado 4.5 a 3.0,
        // Dejemos que los hechos entren en la memoria.md de forma más agresiva.combine Deje que ambas secuencias se vuelvan a calcular para cualquier cambio.,Asegúrese de que el cambio del usuario entre en vigor inmediatamente.
        cacheScope.launch {
            combine(memoryConfigFlow, experimentsFlow) { memCfg, expCfg ->
                if (expCfg.longMemoryCompression) {
                    memCfg.copy(compileThreshold = 3.0f)
                } else {
                    memCfg
                }
            }.collect { combined ->
                memoryConfigCache = combined
            }
        }
        // v0.32: Suscríbete experimentsFlow,Coloque la última ExperimentsConfig en la memoria caché,
        // Para lectura sin bloqueo mediante SystemPromptAssembler/ChatViewModel, etc.(ver [experimentsCache]).
        cacheScope.launch { experimentsFlow.collect { experimentsCache = it } }
        // v1.98: Suscríbete experienceEnabledFlow,Coloque el último valor del interruptor en la memoria caché,
        // Para lecturas de bloqueo cero mediante SystemPromptAssembler(ver [experienceEnabledCache]).
        cacheScope.launch { experienceEnabledFlow.collect { experienceEnabledCache = it } }
        // v1.25: Suscríbete multiAgentConfigFlow,Coloque la última versión de MultiAgentConfig en la memoria caché,
        // Para lecturas de bloqueo cero mediante SystemPromptAssembler(ver [multiAgentConfigCache]).
        cacheScope.launch { multiAgentConfigFlow.collect { multiAgentConfigCache = it } }
        // v2.6.2: Suscríbete memoryGraphConfigFlow,Proporciona un interruptor de profundización LLM de lectura sin bloqueo para capas de borde complementarias.
        cacheScope.launch { memoryGraphConfigFlow.collect { memoryGraphConfigCache = it } }
        // v1.39: Suscríbete proxyConfigFlow,Coloque el último ProxyConfig en la memoria caché,
        // Proporciona lectura sin bloqueo para AppKoinModule al crear OkHttpClient(ver [proxyConfigCache]),Eliminar el bloqueo de ejecución.
        cacheScope.launch { proxyConfigFlow.collect { proxyConfigCache = it } }
        // v1.116: Suscríbete al cambio de emoticones y probabilidad. Flow,Para lecturas de bloqueo cero mediante ChatViewModel.
        cacheScope.launch { stickerEnabledFlow.collect { stickerEnabledCache = it } }
        cacheScope.launch { stickerSendProbabilityFlow.collect { stickerSendProbabilityCache = it } }
        // v2.x: Suscríbete al rango de frecuencia de emoticonos,Para que el sistema solicite inyectar lecturas de bloqueo cero.
        cacheScope.launch { stickerFrequencyFlow.collect { stickerFrequencyCache = it } }
        // v2.x: Límite de memoria caché de la sesión de suscripción(SessionMemoryCache lectura caliente).
        cacheScope.launch { sessionCacheSizeFlow.collect { sessionCacheSizeCache = it } }
        // PII Guard:Cambio de suscripción Flow,Para lecturas sin bloqueo por parte de ChatViewModel dentro de launchStream.
        cacheScope.launch { piiGuardEnabledFlow.collect { piiGuardEnabledCache = it } }
        // ANR Interruptor de detección:Suscríbete al caché de caída de flujo,Para que AnrWatcher lea sincrónicamente(Admite cambio de tiempo de ejecución).
        cacheScope.launch { anrDetectionFlow.collect { anrDetectionCache = it } }
        cacheScope.launch { migrateLegacyProviderIfNeeded() }
        // v2.x: Antiguo modelo de herramienta/La clave de identificación única del modelo de subagente se migró al enlace del modelo auxiliar con el proveedor(idempotente,solo corre una vez)
        cacheScope.launch { migrateLegacyUtilityBindingsIfNeeded() }
        // La "Salida de tarjeta de preferencias" se desactiva una vez (el interruptor predeterminado de la versión anterior ya es válido en JSON para los usuarios existentes).
        // El enlace de representación de la tarjeta todavía se está modificando y dejarlo activado de forma predeterminada mostrará cosas poco confiables a primera vista del usuario.
        cacheScope.launch { chatSettings.migrateCardOutputOnOnce() }
        // Inyecte automáticamente proveedores gratuitos de SiliconFlow (no es necesario iniciar sesión para intentarlo).
        //
        // Sólo inyectar, no activar:
        // El valor de la cobertura gratuita es“Hay algo en la lista que puedo pedir.”, no es necesario que el usuario tome decisiones.
        // La vieja lógica es“El usuario no tiene proveedores.”Cuando lo configuras convenientemente como activo, en realidad hay dos consecuencias:
        //  1. El usuario borra todos los proveedores (para reasignar el suyo), y la próxima vez que lo inicia, vuelve y ocupa la posición de activación;
        //  2. Durante el proceso de incorporación, si el usuario acaba de completar su proveedor pero aún no ha escrito el bit de activación, es posible que lo atrape.
        //
        // La inyección y el "ceder" deben ser en serie: ceder primero y luego inyectar. De lo contrario, no se sabe quién corre primero cuando los dos son concurrentes.
        cacheScope.launch {
            try {
                releaseAutoActivatedFreeProvider()
                val providers = providersFlow.first()
                val hasFree = providers.any { it.id == SiliconFlowFreeModels.PROVIDER_ID }
                if (!hasFree) {
                    val freePreset =
                        presetProviders.byId(SiliconFlowFreeModels.PROVIDER_ID)
                            ?: return@launch
                    // Utilice addProviderIfAbsent atómico para evitar que proveedores duplicados escriban simultáneamente con el proceso de arranque
                    if (addProviderIfAbsent(freePreset)) {
                        Logger.i("SettingsRepository", "Proveedor gratuito de SiliconFlow inyectado automáticamente(Sin activación automática)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("SettingsRepository", "Falló la inyección del proveedor gratuito SiliconFlow: ${e.message}", e)
            }
        }
        // v2.3: Restaurar caché de prueba de conexión
        cacheScope.launch { restoreConnectionTestCache() }
        // v2.3: Configuración de enrutamiento de tareas de suscripción a la memoria caché(taskRoutingConfigFlow Declarado antes del inicio,seguro)
        cacheScope.launch { taskRoutingConfigFlow.collect { taskRoutingConfigCache = it } }
    }

    suspend fun mockLogin(userName: String, method: String = "phone") {
        store.edit {
                prefs ->
            prefs[KEY_ACCOUNT_LOGGED_IN] = true
            prefs[KEY_ACCOUNT_USER_NAME] = userName.ifBlank { appContext.getString(R.string.settings_repo_default_user_name) }
            prefs[KEY_ACCOUNT_LOGIN_AT] = System.currentTimeMillis()
            prefs[KEY_ACCOUNT_LOGIN_METHOD] = method
            prefs[KEY_ACCOUNT_GUEST_MODE] = false
        }
    }

    /** Introducir visitantes(Experiencia sin conexión)modo — Omita el inicio de sesión y vaya directamente a la interfaz principal. */
    suspend fun enterGuestMode() {
        // Mantener la página de inicio/El apodo ya está en el perfil.,sólo volver a“turistas”
        val profile = getUserProfile()
        val displayName =
            profile.userNickName?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.settings_repo_guest_name)
        store.edit { prefs ->
            prefs[KEY_ACCOUNT_GUEST_MODE] = true
            prefs[KEY_ACCOUNT_LOGGED_IN] = false
            prefs[KEY_ACCOUNT_USER_NAME] = displayName
            prefs[KEY_ACCOUNT_LOGIN_AT] = 0L
            prefs[KEY_ACCOUNT_LOGIN_METHOD] = "guest"
        }
    }

    suspend fun logout() {
        store.edit {
                prefs ->
            prefs[KEY_ACCOUNT_LOGGED_IN] = false
            prefs[KEY_ACCOUNT_USER_NAME] = ""
            prefs[KEY_ACCOUNT_LOGIN_AT] = 0L
            prefs[KEY_ACCOUNT_LOGIN_METHOD] = ""
            prefs[KEY_ACCOUNT_GUEST_MODE] = false
        }
    }

    // ── Model profiles ──
    // Almacenar como JSON Map<modelId, ModelProfile>,evitar versiones antiguas `;`/`,` El formato delimitado se trunca cuando avatarUrl contiene un delimitador.
    private val modelProfileSerializer = MapSerializer(String.serializer(), ModelProfile.serializer())

    val modelProfilesFlow: Flow<Map<String, ModelProfile>> =
        store.data.mapDistinct { prefs ->
            decodePrefsOrNull(prefs[KEY_MODEL_PROFILES], modelProfileSerializer, "ModelProfiles") ?: emptyMap()
        }

    suspend fun saveModelProfile(modelId: String, profile: ModelProfile) {
        store.edit {
            val current = decodePrefsOrNull(it[KEY_MODEL_PROFILES], modelProfileSerializer, "ModelProfiles(save)") ?: emptyMap()
            val updated = current.toMutableMap().apply { put(modelId, profile) }
            it[KEY_MODEL_PROFILES] = AppJson.encodeToString(modelProfileSerializer, updated)
        }
    }

    fun isMemoryEnabled(): Boolean = memoryEnabledCache.get()

    suspend fun saveMemoryEnabled(enabled: Boolean) {
        store.edit { it[KEY_MEMORY_ENABLED] = enabled }
    }

    /** v1.0.51: Protección de simultaneidad de migración de memoria existente — compareAndSet Garantizado para ejecutarse solo una vez,Incluso si ambas corrutinas leen falso al mismo tiempo. */
    fun tryStartMemoryBackfillMigration(): Boolean = memoryBackfillMigrationDone.compareAndSet(false, true)

    suspend fun saveMemoryBackfillMigrationDone(done: Boolean) {
        store.edit { it[KEY_MEMORY_MIGRATION_V1_0_51_DONE] = done }
    }

    /** v1.0.52 P2-2: Guarde la identificación del espacio de memoria actualmente seleccionado. */
    suspend fun saveCurrentSpaceId(spaceId: String) {
        store.edit { it[KEY_CURRENT_SPACE_ID] = spaceId }
    }

    suspend fun saveThemeMode(mode: String) = appearance.saveThemeMode(mode)

    /** H5: Guarde el cambio de tema de alto contraste. */
    suspend fun saveHighContrast(enabled: Boolean) = appearance.saveHighContrast(enabled)

    /** v2.5.0: Guarda el modo e intensidad del vaso líquido. */
    suspend fun saveLiquidGlassMode(mode: String) = appearance.saveLiquidGlassMode(mode)
    suspend fun saveLiquidGlassStrength(strength: Int) = appearance.saveLiquidGlassStrength(strength)

    suspend fun saveThemeId(id: String) = appearance.saveThemeId(id)

    /** Guardar tema independiente del modo oscuro id(Una cadena vacía indica una versión oscura que sigue el tema claro.). */
    suspend fun saveDarkThemeId(id: String) = appearance.saveDarkThemeId(id)

    /** Guarde la configuración de cambio programado del tema. */
    suspend fun saveThemeSchedule(config: ThemeScheduleConfig) = appearance.saveThemeSchedule(config)

    /** v1.65: Guarde el interruptor de selección de color dinámico. */
    suspend fun saveDynamicColor(enabled: Boolean) = appearance.saveDynamicColor(enabled)
    /**
     * v1.97 gap7: Guardar lista de temas personalizados(Reemplazo completo).
     *
     * @param themes Lista completa de temas personalizados;Una lista vacía significa borrar todos los temas personalizados
     */

    /**
     * v1.97 gap7: Agregar o actualizar un único tema personalizado(Eliminar duplicados por id).
     *
     * si [theme.id] Reemplazar si ya existe,De lo contrario, añádalo al final de la lista.
     *
     * M-SR: lectura atómica-cambiar-escribir — en un solo [store.edit] Leer la lista actual, aplicar la transformación, volver a escribir dentro de la transacción,
     * Evite llamadas simultáneas basadas en [customThemesFlow.first()] las instantáneas se sobrescriben entre sí y pierden actualizaciones
     * (con [saveModelProfile] / [addProvider] / [updateMultiAgentConfig] Mismo modo).
     */
    suspend fun upsertCustomTheme(theme: CustomTheme) = appearance.upsertCustomTheme(theme)

    /**
     * v1.97 gap7: Eliminar tema personalizado por identificación.
     *
     * Si después de la eliminación el themeId actual apunta al tema eliminado,La persona que llama debe volver al tema predeterminado por sí mismo.(Como por ejemplo warm_paper).
     *
     * M-SR: lectura atómica-cambiar-escribir — en un solo [store.edit] Lea la lista actual dentro de la transacción, escríbala nuevamente después de filtrar,
     * Evite llamadas simultáneas basadas en [customThemesFlow.first()] las instantáneas se sobrescriben entre sí y pierden actualizaciones
     * (con [deleteProvider] / [saveModelProfile] Mismo modo).
     */
    suspend fun deleteCustomTheme(id: String) = appearance.deleteCustomTheme(id)

    suspend fun saveFontSizeScale(scale: String) = appearance.saveFontSizeScale(scale)

    /** E2: guardar/Borrar ruta de fuente de cuerpo personalizada(null Borrar,Restaurar los valores predeterminados del sistema). */
    suspend fun setCustomFontPath(path: String?) = appearance.saveCustomFontPath(path)

    /** v1.95: Guardar página predeterminada de inicio(0=Tarea, 1=Agent, 2=chat grupal). */
    suspend fun saveDefaultHomePage(page: Int) = appearance.saveDefaultHomePage(page)

    // v1.135: llamar WebSearchConfig.encrypted() cifrado unificado apiKey + apiKeys
    suspend fun saveWebSearchConfig(config: WebSearchConfig) {
        store.edit {
            it[KEY_WEB_SEARCH_CONFIG] =
                AppJson.encodeToString(
                    WebSearchConfig.serializer(),
                    config.encrypted(),
                )
        }
    }

    // H-SR2: CloudBackupConfig Contiene credenciales sensibles a s3SecretKey/webdavPassword,Cifrar antes de escribir(Los valores nulos se dejan como están.)
    suspend fun saveCloudBackupConfig(config: CloudBackupConfig) {
        // B-9/P0-1: backupPasswordSet Solo se establece si la contraseña no está vacía true(Nueva contraseña del usuario);Si la contraseña está vacía, se conserva la bandera existente.
        // no se puede recalcular como backupPassword.isNotEmpty() —— Keystore decrypt vuelve a leer una cadena vacía después de perder la clave,
        // cualquier salvamento superfluo(Interruptor de sincronización automática de Joo Chiat)Limpiará las señales false,conducir a la nube/La guardia de exportación local falla,
        // Las copias de seguridad se degradan silenciosamente a texto sin cifrar. Sólo si el usuario borra explícitamente la contraseña(UI en capas backupPasswordSet=false)Simplemente reinicie.
        val withFlag = config.copy(backupPasswordSet = config.backupPassword.isNotEmpty() || config.backupPasswordSet)
        val encrypted = withFlag.encrypted()
        store.edit { it[KEY_CLOUD_BACKUP_CONFIG] = AppJson.encodeToString(CloudBackupConfig.serializer(), encrypted) }
    }

    // H8: WebServerConfig Contiene credenciales sensibles a contraseña/PIN,Cifrar antes de escribir
    suspend fun saveWebServerConfig(config: WebServerConfig) {
        val encrypted = config.encrypted()
        store.edit { it[KEY_WEB_SERVER_CONFIG] = AppJson.encodeToString(WebServerConfig.serializer(), encrypted) }
    }

    suspend fun saveAsrConfig(config: AsrConfig) {
        store.edit {
            it[KEY_ASR_CONFIG] = AppJson.encodeToString(AsrConfig.serializer(), config.copy(apiKey = SecureKeyStore.encrypt(config.apiKey)))
        }
    }

    /** v1.54: RAG Configurar lectura y escritura. */
    suspend fun getRagConfig(): RagConfig = ragConfigFlow.first()

    suspend fun saveRagConfig(config: RagConfig) {
        store.edit { it[KEY_RAG_CONFIG] = AppJson.encodeToString(RagConfig.serializer(), config) }
    }

    /**
     * v1.0.53: embedding Migración única de fuentes(Convergencia desde la ruta de lectura hasta aquí,Ejecutado una vez al inicio).
     *
     * El antiguo usuario RagConfig carece del campo embeddingSource,Después de la deserialización, el valor predeterminado es LOCAL_KEYWORD;
     * Si ha configurado explícitamente la nube embedding(cloudProviderId o cloudModel no está vacío),Explique que estaba usando la nube antes de actualizar,
     * Aquí la fuente se vuelve a escribir en CLOUD y se establece la bandera,Evite romper las configuraciones disponibles.
     *
     * Las banderas y la configuración están escritas en la misma edición.:O surten efecto juntos,Cualquiera de los dos no tendrá efecto,La migración no se perderá si falla a mitad de camino;
     * Una vez completada la migración, la ruta de lectura ya no reescribirá la configuración.,La selección explícita de "palabras clave locales" por parte del usuario realmente puede surtir efecto.
     */
    suspend fun migrateRagEmbeddingSourceIfNeeded() {
        if (store.data.first()[KEY_RAG_EMBEDDING_SOURCE_MIGRATED] == true) return
        store.edit { prefs ->
            if (prefs[KEY_RAG_EMBEDDING_SOURCE_MIGRATED] == true) return@edit
            val config = decodePrefsOrNull(prefs[KEY_RAG_CONFIG], RagConfig.serializer(), "RagConfig")
            if (config != null &&
                config.embeddingSource == RagConfig.EmbeddingSource.LOCAL_KEYWORD &&
                (config.cloudProviderId.isNotBlank() || config.cloudModel.isNotBlank())
            ) {
                prefs[KEY_RAG_CONFIG] =
                    AppJson.encodeToString(
                        RagConfig.serializer(),
                        config.copy(embeddingSource = RagConfig.EmbeddingSource.CLOUD),
                    )
            }
            prefs[KEY_RAG_EMBEDDING_SOURCE_MIGRATED] = true
        }
    }

    // ── F-33: El indicador de configuración de incrustación utilizado para el índice más reciente ──

    /**
     * F-33: La configuración de incrustación utilizada en el índice exitoso más reciente key(por [io.zer0.muse.rag.RagConfig.embeddingModelKey] generar).
     * null significa que nunca ha sido indexado;con corriente [ragConfigFlow] Cuando las claves calculadas son inconsistentes,Consejos para la página de base de conocimientos"Recomendar volver a indexar".
     */
    val lastEmbeddingModelKeyFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_LAST_EMBEDDING_MODEL] }

    /** F-33: Registre la clave de configuración de incrustación utilizada para el índice exitoso más reciente. */
    suspend fun saveLastEmbeddingModelKey(key: String?) {
        store.edit {
            if (key.isNullOrBlank()) it.remove(KEY_LAST_EMBEDDING_MODEL) else it[KEY_LAST_EMBEDDING_MODEL] = key
        }
    }

    // H-SR2: MCP El token estático y el ID/secreto de la aplicación Feishu se cifran antes de escribir.
    suspend fun saveMcpServers(servers: List<io.zer0.muse.mcp.McpServerConfig>) {
        store.edit {
            it[KEY_MCP_SERVERS] =
                AppJson.encodeToString(
                    ListSerializer(io.zer0.muse.mcp.McpServerConfig.serializer()),
                    servers.map { server -> server.encrypted() },
                )
        }
    }

    /** v1.58: Guardar lista de plantillas de mensajes(Reemplazo completo). */
    suspend fun savePromptTemplates(templates: List<PromptTemplate>) {
        store.edit { it[KEY_PROMPT_TEMPLATES] = AppJson.encodeToString(ListSerializer(PromptTemplate.serializer()), templates) }
    }

    // v0.30-a: Lectura y escritura de retratos de usuario.(SystemPromptAssembler usar)
    suspend fun getUserProfile(): UserProfile = userProfileFlow.first()

    suspend fun saveUserProfile(profile: UserProfile) {
        store.edit { prefs ->
            prefs[KEY_USER_PROFILE] = AppJson.encodeToString(UserProfile.serializer(), profile)
            // B0-09: Sincronizar claves de cuenta,No es necesario migrar los lectores antiguos de AccountScreen/AccountState
            prefs[KEY_ACCOUNT_USER_NAME] = profile.userNickName?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.settings_repo_default_user_name)
            // El avatar se escribe mediante un proceso dedicado en la página de la cuenta. Se puede obtener el ahorro antivibración de la página de imagen
            // Aún no cargado null,No puedo guardar el apodo porque/El avatar persistente fue eliminado por error por preferencia.
            if (!profile.avatarUri.isNullOrBlank()) {
                prefs[KEY_ACCOUNT_AVATAR_URI] = profile.avatarUri
            }
        }
    }

    /** Migre el URI del contenido del avatar antiguo aún legible al directorio privado de la aplicación al inicio. */
    suspend fun migrateUserAvatarToPrivateStorageIfNeeded() {
        val profile = getUserProfile()
        val currentUri = profile.avatarUri?.takeIf { it.isNotBlank() } ?: return
        val persistentUri = AvatarStorage.persist(appContext, currentUri) ?: return
        if (persistentUri != currentUri) {
            saveUserProfile(profile.copy(avatarUri = persistentUri))
            Logger.i("SettingsRepository", "El avatar del usuario se ha movido al directorio privado de la aplicación.")
        }
    }

    // v0.31: Preferencia de comportamiento de chat lectura y escritura
    // v0.31: Preferencia de comportamiento de chat lectura y escritura
    suspend fun getChatPreferences(): ChatPreferences = chatSettings.getChatPreferences()

    /** v2.5.3 (P1-1): Entrada recomendada —— Actualización funcional atómica de las preferencias de chat. */
    suspend fun updateChatPreferences(block: (ChatPreferences) -> ChatPreferences) = chatSettings.updateChatPreferences(block)

    @Deprecated("Usar en su lugar updateChatPreferences(block) Realice actualizaciones funcionales atómicas para evitar que toda la reescritura cubra modificaciones simultáneas")
    @Suppress("DEPRECATION")
    suspend fun saveChatPreferences(prefs: ChatPreferences) = chatSettings.saveChatPreferences(prefs)

    // v0.32: Configuración avanzada del sistema de memoria
    suspend fun getMemoryConfig(): io.zer0.memory.ticker.MemoryConfig = memoryConfigFlow.first()

    suspend fun saveMemoryConfig(config: io.zer0.memory.ticker.MemoryConfig) {
        store.edit { it[KEY_MEMORY_CONFIG] = AppJson.encodeToString(io.zer0.memory.ticker.MemoryConfig.serializer(), config) }
    }

    // v0.32: Política de notificación
    suspend fun saveNotificationPolicy(policy: String) = security.saveNotificationPolicy(policy)

    // v0.32: Cambio de biblioteca de experiencias
    suspend fun saveExperienceEnabled(enabled: Boolean) {
        store.edit { it[KEY_EXPERIENCE_ENABLED] = enabled }
    }

    // v1.0.63: Asistente predeterminado para nuevas tareas
    suspend fun saveDefaultAssistantId(assistantId: String) {
        store.edit { it[KEY_DEFAULT_ASSISTANT_ID] = assistantId }
    }

    /** v2.x(comentarios de los usuarios): Una lectura única del puntero auxiliar predeterminado (utilizado por asegurarDefaultExists al inicio). */
    suspend fun getDefaultAssistantId(): String = store.data.mapDistinct { it[KEY_DEFAULT_ASSISTANT_ID] ?: "default" }.first()

    // PII Guard cambiar(Habilitado por defecto)
    suspend fun savePiiGuardEnabled(enabled: Boolean) {
        store.edit { it[KEY_PII_GUARD_ENABLED] = enabled }
    }

    // ANR Interruptor de detección
    suspend fun saveAnrDetection(enabled: Boolean) = security.saveAnrDetection(enabled)

    // Interruptor de informes de datos de rendimiento

    // v0.32: mantente despierto
    suspend fun saveKeepAwake(enabled: Boolean) = security.saveKeepAwake(enabled)

    // v0.32: Iniciar automáticamente al arrancar
    suspend fun saveAutoLaunch(enabled: Boolean) = security.saveAutoLaunch(enabled)

    // v0.32: Funciones experimentales
    suspend fun saveExperiments(config: ExperimentsConfig) {
        store.edit { it[KEY_EXPERIMENTS] = AppJson.encodeToString(ExperimentsConfig.serializer(), config) }
    }

    // v0.32: Compartir plantilla
    suspend fun saveShareTemplate(config: ShareTemplateConfig) {
        store.edit { it[KEY_SHARE_TEMPLATE] = AppJson.encodeToString(ShareTemplateConfig.serializer(), config) }
    }

    // v0.32: configuración de medios
    // P0-2: MediaConfig Contiene credenciales confidenciales ttsApiKey,Cifrar antes de escribir(Mismo modo que WebSearchConfig)
    suspend fun saveMediaConfig(config: MediaConfig) {
        store.edit { it[KEY_MEDIA_CONFIG] = AppJson.encodeToString(MediaConfig.serializer(), config.encrypted()) }
    }

    // Lectura y escritura de configuración de proxy de red global
    // H-SR2: Usar antes de escribir ProxyConfig.encrypted() Cifrado password(Deje la contraseña vacía como está)
    suspend fun saveProxyConfig(config: ProxyConfig) {
        store.edit { it[KEY_PROXY_CONFIG] = AppJson.encodeToString(ProxyConfig.serializer(), config.encrypted()) }
    }

    // Lectura y escritura de configuración de mensajes activos.
    suspend fun saveProactiveMessageConfig(config: ProactiveMessageConfig) {
        store.edit { it[KEY_PROACTIVE_MESSAGE] = AppJson.encodeToString(ProactiveMessageConfig.serializer(), config) }
    }

    // v0.34: Lectura y escritura de configuración de parámetros predeterminados de generación de imágenes
    suspend fun saveImageGenConfig(config: ImageGenConfig) {
        store.edit { it[KEY_IMAGE_GEN_CONFIG] = AppJson.encodeToString(ImageGenConfig.serializer(), config) }
    }

    /** Guarde la configuración de parámetros predeterminada para la generación de video. */
    suspend fun saveVideoGenConfig(config: VideoGenConfig) {
        store.edit { it[KEY_VIDEO_GEN_CONFIG] = AppJson.encodeToString(VideoGenConfig.serializer(), config) }
    }

    // v1.25: Interruptor de ayuda visual
    suspend fun saveVisionEnabled(enabled: Boolean) {
        store.edit { it[KEY_VISION_ENABLED] = enabled }
    }

    // v1.25: Modelo de ayuda visual ID
    suspend fun saveVisionModelId(modelId: String?) {
        store.edit { if (modelId != null) it[KEY_VISION_MODEL_ID] = modelId else it.remove(KEY_VISION_MODEL_ID) }
    }

    // v1.25: Proveedor de ayudas visuales ID
    suspend fun saveVisionProviderId(providerId: String?) {
        store.edit { if (providerId != null) it[KEY_VISION_PROVIDER_ID] = providerId else it.remove(KEY_VISION_PROVIDER_ID) }
    }

    /**
     * M3: Actualización atómica de configuraciones multi-Agente,evitar leer-cambiar-Escribe una condición de carrera.
     * Lea el valor actual, aplique la transformación, vuelva a escribir dentro de la transacción de edición del DataStore.
     *
     * Nota:[MultiAgentConfig.reviewModelId] con [MultiAgentConfig.llmReviewEnabled] para @Transient,
     * No serializado con JSON —— Para modificar estos dos campos, debe utilizar [saveReviewModelId] / [saveLlmReviewEnabled],
     * en lugar de este método(Las modificaciones a ellos en el bloque de este método no persistirán.).
     */
    suspend fun updateMultiAgentConfig(block: (MultiAgentConfig) -> MultiAgentConfig) {
        store.edit { prefs ->
            val current =
                decodePrefsOrNull(prefs[KEY_MULTI_AGENT_CONFIG], MultiAgentConfig.serializer(), "MultiAgentConfig")
                    ?: MultiAgentConfig()
            prefs[KEY_MULTI_AGENT_CONFIG] = AppJson.encodeToString(MultiAgentConfig.serializer(), block(current))
        }
    }

    /**
     * v1.201: Guarde la identificación del modelo utilizado por la revisión integral de LLM.
     * @param modelId modelo id;null borrar configuración,Recurrir al modelo predeterminado del proveedor activo
     */
    suspend fun saveReviewModelId(modelId: String?) {
        store.edit { if (modelId != null) it[KEY_MULTI_AGENT_REVIEW_MODEL] = modelId else it.remove(KEY_MULTI_AGENT_REVIEW_MODEL) }
    }

    /**
     * v1.201: Guarde el cambio de revisión integral global de LLM.
     * La política de agregación LLM_REVIEW se degrada automáticamente a EXPERT_REVIEW al cerrar.
     */
    suspend fun saveLlmReviewEnabled(enabled: Boolean) {
        store.edit { it[KEY_MULTI_AGENT_LLM_REVIEW_ENABLED] = enabled }
    }

    suspend fun saveMcpToken(serverId: String, token: io.zer0.muse.mcp.McpTokenInfo) {
        store.edit {
            it[stringPreferencesKey("mcp_token_$serverId")] =
                AppJson.encodeToString(
                    io.zer0.muse.mcp.McpTokenInfo.serializer(),
                    token.copy(
                        accessToken = SecureKeyStore.encrypt(token.accessToken),
                        refreshToken = SecureKeyStore.encrypt(token.refreshToken),
                    ),
                )
        }
    }

    suspend fun getMcpToken(serverId: String): io.zer0.muse.mcp.McpTokenInfo? {
        return decodePrefsOrNull(
            store.data.first()[stringPreferencesKey("mcp_token_$serverId")],
            io.zer0.muse.mcp.McpTokenInfo.serializer(),
            "McpToken($serverId)",
        )?.let {
                t ->
            t.copy(accessToken = SecureKeyStore.decrypt(t.accessToken), refreshToken = SecureKeyStore.decrypt(t.refreshToken))
        }
    }

    suspend fun clearMcpToken(serverId: String) {
        store.edit { it.remove(stringPreferencesKey("mcp_token_$serverId")) }
    }

    suspend fun saveOnboardingShown() {
        store.edit { it[KEY_ONBOARDING_SHOWN] = true }
    }

    // v1.95: Guarde el primer mensaje de reconocimiento de voz del sistema para ver si se ha mostrado
    suspend fun saveAsrTipShown(shown: Boolean) = appearance.saveAsrTipShown(shown)

    // v1.95: Guardar interruptor de biblioteca de emoticonos
    suspend fun saveStickerEnabled(enabled: Boolean) {
        store.edit { it[KEY_STICKER_ENABLED] = enabled }
    }

    // v2.x: Guarde el rango de frecuencia de envío de emoticones(occasionally/normal/frequent;Otros valores concluyen como normal)
    suspend fun saveStickerFrequency(frequency: String) {
        val normalized =
            when (frequency) {
                STICKER_FREQ_OCCASIONALLY, STICKER_FREQ_FREQUENT -> frequency
                else -> STICKER_FREQ_NORMAL
            }
        store.edit { it[KEY_STICKER_FREQUENCY] = normalized }
    }

    // v2.x: Guardar límite de memoria caché de sesión(2..12 envolver)
    suspend fun saveSessionCacheSize(size: Int) {
        store.edit { it[KEY_SESSION_CACHE_SIZE] = size.coerceIn(2, 12) }
    }

    suspend fun addProvider(config: ProviderConfig) {
        store.edit {
                prefs ->
            val list = decodePrefsOrNull(prefs[KEY_PROVIDERS], ListSerializer(ProviderConfig.serializer()), "Providers(add)") ?: emptyList()
            prefs[KEY_PROVIDERS] = encodeProviders(list + config)
        }
        auditLogger.log(
            category = "user_action",
            action = "add_provider",
            target = config.id,
            detail = mapOf("display_name" to config.displayName),
        )
    }

    /**
     * v2.0: Proveedor de guardado idempotente — Si ya existe la misma identificación, reemplácela en su totalidad.,Si no existe, agréguelo.
     *
     * para arrancar"Prueba exitosa y guarda inmediatamente"Compartido con página invertida y parte inferior.,Evite entradas duplicadas.
     */
    suspend fun upsertProvider(config: ProviderConfig) {
        store.edit { prefs ->
            val list =
                decodePrefsOrNull(
                    prefs[KEY_PROVIDERS],
                    ListSerializer(ProviderConfig.serializer()),
                    "Providers(upsert)",
                ) ?: emptyList()
            val updated =
                if (list.any { it.id == config.id }) {
                    list.map { if (it.id == config.id) config else it }
                } else {
                    list + config
                }
            prefs[KEY_PROVIDERS] = encodeProviders(updated)
        }
    }

    /**
     * v1.0.18: Atom "Agregar solo si no existe"— Si ya existe la misma identificación, omítala,Evite la duplicación de condiciones de carrera para guardar la inyección automática y la página de inicio.
     *
     * con [addProvider] diferencia:en un solo [store.edit] Leer dentro de la transacción-comprobar-escribir,Idempotencia garantizada.
     * Para uso con inyección automática del proveedor gratuito SiliconFlow.
     *
     * @return true Indica recién agregado;false Indica que ya existe la misma identificación.,saltar
     */
    private suspend fun addProviderIfAbsent(config: ProviderConfig): Boolean {
        var added = false
        store.edit { prefs ->
            val list =
                decodePrefsOrNull(
                    prefs[KEY_PROVIDERS],
                    ListSerializer(ProviderConfig.serializer()),
                    "Providers(addIfAbsent)",
                ) ?: emptyList()
            if (list.none { it.id == config.id }) {
                prefs[KEY_PROVIDERS] = encodeProviders(list + config)
                added = true
            }
        }
        return added
    }

    suspend fun updateProvider(config: ProviderConfig) {
        store.edit { prefs ->
            val list =
                decodePrefsOrNull(
                    prefs[KEY_PROVIDERS],
                    ListSerializer(ProviderConfig.serializer()),
                    "Providers(update)",
                ) ?: emptyList()
            val previous = list.firstOrNull { it.id == config.id }
            prefs[KEY_PROVIDERS] = encodeProviders(list.map { if (it.id == config.id) config else it })
            cleanupReferencesToRemovedModels(prefs, config, previous, list)
        }
        // v1.132: Caché de lista de modelos no válido(baseUrl/apiKey puede haber cambiado)
        io.zer0.ai.core.ModelListCache.invalidate(config.id)
        // Punto final del proveedor/clave/La lista de modelos ha cambiado.:Deje que el caché se derive de la configuración(Como por ejemplo embedding Provider)autoinválido
        ProviderConfigChangeBus.notifyChanged()
        // P2-4: Registro de auditoría — Modificar Provider(Con llave/punto final)
        auditLogger.log(
            category = "user_action",
            action = "update_provider",
            target = config.id,
            detail = mapOf("display_name" to config.displayName),
        )
    }

    /**
     * Después de guardar el proveedor,Borre la referencia al "modelo que se eliminó esta vez".
     *
     * Modelo eliminado id = Lista de modelos antiguos - Lista de modelos nuevos;Los modelos con el mismo nombre no se eliminan si todavía existen en otros proveedores.
     * (Es común que las estaciones de transferencia compartan identificaciones de modelo con el funcionario.,Si lo deja en blanco, se eliminarán las opciones disponibles por error.).
     */
    private suspend fun cleanupReferencesToRemovedModels(
        prefs: MutablePreferences,
        config: ProviderConfig,
        previous: ProviderConfig?,
        allProviders: List<ProviderConfig>,
    ) {
        val previousModelIds = previous?.models?.map { it.id }.orEmpty()
        val currentModelIds = config.models.map { it.id }.toSet()
        val removedModelIds = previousModelIds.filterNot { it in currentModelIds }.toSet()
        if (removedModelIds.isEmpty()) return
        val retainedModelIds = allProviders
            .filterNot { it.id == config.id }
            .flatMap { provider -> provider.models.map { it.id } }
            .toSet()
        val references = readModelReferences(prefs)
        val cleaned = cleanupRemovedModelReferences(
            providerId = config.id,
            removedModelIds = removedModelIds,
            retainedModelIds = retainedModelIds,
            references = references,
        )
        if (cleaned == references) return
        writeModelReferences(prefs, cleaned)
        Logger.i(
            "SettingsRepository",
            "Provider ${config.id} Eliminar modelo ${removedModelIds.joinToString(",")},Se han limpiado las referencias a ellos.",
        )
    }

    /** Lea todas las configuraciones que \"hacen referencia a ID de modelo específicos\"(Después de eliminar el modelo, es necesario volver a escribir todo el modelo.). */
    private fun readModelReferences(prefs: MutablePreferences): ModelReferences = ModelReferences(
        imageGenConfig =
        decodePrefsOrNull(prefs[KEY_IMAGE_GEN_CONFIG], ImageGenConfig.serializer(), "ImageGenConfig(updateProvider)")
            ?: ImageGenConfig(),
        videoGenConfig =
        decodePrefsOrNull(prefs[KEY_VIDEO_GEN_CONFIG], VideoGenConfig.serializer(), "VideoGenConfig(updateProvider)")
            ?: VideoGenConfig(),
        taskRoutingConfig =
        decodePrefsOrNull(
            prefs[KEY_TASK_ROUTING_CONFIG],
            TaskRoutingConfig.serializer(),
            "TaskRoutingConfig(updateProvider)",
        ) ?: TaskRoutingConfig(),
        utilityModelBinding =
        decodePrefsOrNull(
            prefs[KEY_UTILITY_MODEL_BINDING],
            UtilityModelBinding.serializer(),
            "UtilityModelBinding(updateProvider)",
        ),
        utilityLargeModelBinding =
        decodePrefsOrNull(
            prefs[KEY_UTILITY_LARGE_MODEL_BINDING],
            UtilityModelBinding.serializer(),
            "UtilityLargeModelBinding(updateProvider)",
        ),
        ragConfig =
        decodePrefsOrNull(prefs[KEY_RAG_CONFIG], RagConfig.serializer(), "RagConfig(updateProvider)")
            ?: RagConfig(),
        visionModelId = prefs[KEY_VISION_MODEL_ID],
    )

    /** escribir de nuevo [readModelReferences] leer configuración(Solo escrito para implementaciones con cambios.,Evite escrituras sin sentido). */
    private fun writeModelReferences(prefs: MutablePreferences, references: ModelReferences) {
        prefs[KEY_IMAGE_GEN_CONFIG] =
            AppJson.encodeToString(ImageGenConfig.serializer(), references.imageGenConfig)
        prefs[KEY_VIDEO_GEN_CONFIG] =
            AppJson.encodeToString(VideoGenConfig.serializer(), references.videoGenConfig)
        prefs[KEY_TASK_ROUTING_CONFIG] =
            AppJson.encodeToString(TaskRoutingConfig.serializer(), references.taskRoutingConfig)
        writeModelBinding(prefs, KEY_UTILITY_MODEL_BINDING, references.utilityModelBinding)
        writeModelBinding(prefs, KEY_UTILITY_LARGE_MODEL_BINDING, references.utilityLargeModelBinding)
        prefs[KEY_RAG_CONFIG] =
            AppJson.encodeToString(RagConfig.serializer(), references.ragConfig)
        writeNullablePreference(prefs, KEY_VISION_MODEL_ID, references.visionModelId)
    }

    private fun writeModelBinding(
        prefs: MutablePreferences,
        key: Preferences.Key<String>,
        binding: UtilityModelBinding?,
    ) {
        if (binding == null) {
            prefs.remove(key)
        } else {
            prefs[key] = AppJson.encodeToString(UtilityModelBinding.serializer(), binding)
        }
    }

    suspend fun deleteProvider(id: String) {
        require(id.isNotBlank()) { "provider id must not be blank" }
        store.edit { prefs ->
            val providers =
                decodePrefsOrNull(
                    prefs[KEY_PROVIDERS],
                    ListSerializer(ProviderConfig.serializer()),
                    "Providers(delete)",
                ) ?: emptyList()
            val result =
                cleanupProviderReferences(
                    providers = providers,
                    deletedProviderId = id,
                    activeProviderId = prefs[KEY_ACTIVE_PROVIDER_ID],
                    selectedModelId = prefs[KEY_SELECTED_MODEL],
                    toolModelId = prefs[KEY_TOOL_MODEL_ID],
                    subagentModelId = prefs[KEY_SUBAGENT_MODEL_ID],
                    compressModelId = prefs[KEY_COMPRESS_MODEL_ID],
                    visionModelId = prefs[KEY_VISION_MODEL_ID],
                    visionProviderId = prefs[KEY_VISION_PROVIDER_ID],
                    utilityModelBinding =
                    decodePrefsOrNull(
                        prefs[KEY_UTILITY_MODEL_BINDING],
                        UtilityModelBinding.serializer(),
                        "UtilityModelBinding(deleteProvider)",
                    ),
                    utilityLargeModelBinding =
                    decodePrefsOrNull(
                        prefs[KEY_UTILITY_LARGE_MODEL_BINDING],
                        UtilityModelBinding.serializer(),
                        "UtilityLargeModelBinding(deleteProvider)",
                    ),
                    sessionModelOverrides =
                    decodePrefsOrNull(
                        prefs[KEY_SESSION_MODEL_OVERRIDES],
                        sessionModelOverrideSerializer,
                        "SessionModelOverrides(deleteProvider)",
                    ) ?: emptyMap(),
                    sessionProviderOverrides =
                    decodePrefsOrNull(
                        prefs[KEY_SESSION_PROVIDER_OVERRIDES],
                        sessionModelOverrideSerializer,
                        "SessionProviderOverrides(deleteProvider)",
                    ) ?: emptyMap(),
                    imageGenConfig =
                    decodePrefsOrNull(
                        prefs[KEY_IMAGE_GEN_CONFIG],
                        ImageGenConfig.serializer(),
                        "ImageGenConfig(deleteProvider)",
                    ) ?: ImageGenConfig(),
                    videoGenConfig =
                    decodePrefsOrNull(
                        prefs[KEY_VIDEO_GEN_CONFIG],
                        VideoGenConfig.serializer(),
                        "VideoGenConfig(deleteProvider)",
                    ) ?: VideoGenConfig(),
                    taskRoutingConfig =
                    decodePrefsOrNull(
                        prefs[KEY_TASK_ROUTING_CONFIG],
                        TaskRoutingConfig.serializer(),
                        "TaskRoutingConfig(deleteProvider)",
                    ) ?: TaskRoutingConfig(),
                    ragConfig =
                    decodePrefsOrNull(
                        prefs[KEY_RAG_CONFIG],
                        RagConfig.serializer(),
                        "RagConfig(deleteProvider)",
                    ) ?: RagConfig(),
                )
            prefs[KEY_PROVIDERS] = encodeProviders(result.providers)
            writeNullablePreference(prefs, KEY_ACTIVE_PROVIDER_ID, result.activeProviderId)
            writeNullablePreference(prefs, KEY_SELECTED_MODEL, result.selectedModelId)
            writeNullablePreference(prefs, KEY_TOOL_MODEL_ID, result.toolModelId)
            writeNullablePreference(prefs, KEY_SUBAGENT_MODEL_ID, result.subagentModelId)
            writeNullablePreference(prefs, KEY_COMPRESS_MODEL_ID, result.compressModelId)
            writeNullablePreference(prefs, KEY_VISION_MODEL_ID, result.visionModelId)
            writeNullablePreference(prefs, KEY_VISION_PROVIDER_ID, result.visionProviderId)
            // v2.x: Encuadernación de modelo auxiliar(El enlace del Proveedor eliminado se deja en blanco.)
            if (result.utilityModelBinding != null) {
                prefs[KEY_UTILITY_MODEL_BINDING] = AppJson.encodeToString(UtilityModelBinding.serializer(), result.utilityModelBinding)
            } else {
                prefs.remove(KEY_UTILITY_MODEL_BINDING)
            }
            if (result.utilityLargeModelBinding != null) {
                prefs[KEY_UTILITY_LARGE_MODEL_BINDING] =
                    AppJson.encodeToString(
                        UtilityModelBinding.serializer(),
                        result.utilityLargeModelBinding,
                    )
            } else {
                prefs.remove(KEY_UTILITY_LARGE_MODEL_BINDING)
            }
            writeMapPreference(prefs, KEY_SESSION_MODEL_OVERRIDES, result.sessionModelOverrides)
            writeMapPreference(prefs, KEY_SESSION_PROVIDER_OVERRIDES, result.sessionProviderOverrides)
            prefs[KEY_IMAGE_GEN_CONFIG] =
                AppJson.encodeToString(
                    ImageGenConfig.serializer(),
                    result.imageGenConfig,
                )
            prefs[KEY_VIDEO_GEN_CONFIG] =
                AppJson.encodeToString(
                    VideoGenConfig.serializer(),
                    result.videoGenConfig,
                )
            prefs[KEY_TASK_ROUTING_CONFIG] =
                AppJson.encodeToString(
                    TaskRoutingConfig.serializer(),
                    result.taskRoutingConfig,
                )
            // RAG Limpieza de sincronización de referencia de incrustación en la nube:Anteriormente, cloudProviderId apuntaría permanentemente al proveedor eliminado.
            prefs[KEY_RAG_CONFIG] =
                AppJson.encodeToString(
                    RagConfig.serializer(),
                    result.ragConfig,
                )
        }
        // v1.132: Caché de lista de modelos no válido
        io.zer0.ai.core.ModelListCache.invalidate(id)
        // Lista de proveedores/El modelo ha cambiado:Deje que el caché se derive de la configuración(Como por ejemplo embedding Provider)autoinválido
        ProviderConfigChangeBus.notifyChanged()
        // P2-4: Registro de auditoría — Eliminar usuario Provider(fire-and-forget,El fracaso no afecta el negocio)
        auditLogger.log(
            category = "user_action",
            action = "delete_provider",
            target = id,
        )
    }

    suspend fun setActiveProvider(id: String) {
        store.edit { it[KEY_ACTIVE_PROVIDER_ID] = id }
    }

    suspend fun saveSelectedModel(modelId: String?) {
        store.edit { if (modelId != null) it[KEY_SELECTED_MODEL] = modelId else it.remove(KEY_SELECTED_MODEL) }
    }

    /** Guarda las anulaciones del modelo para una sola sesión; pasar nulo borra las anulaciones y vuelve al modelo predeterminado global. */
    suspend fun saveSessionModelOverride(sessionId: String, modelId: String?) {
        saveSessionOverride(KEY_SESSION_MODEL_OVERRIDES, "SessionModelOverrides(save)", sessionId, modelId)
    }

    /** Guarda la anulación del Proveedor para una sola sesión; pasar nulo borra la anulación y vuelve al proveedor predeterminado global. */
    suspend fun saveSessionProviderOverride(sessionId: String, providerId: String?) {
        saveSessionOverride(KEY_SESSION_PROVIDER_OVERRIDES, "SessionProviderOverrides(save)", sessionId, providerId)
    }

    private fun writeNullablePreference(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
    }

    private fun writeMapPreference(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: Map<String, String>,
    ) {
        if (value.isEmpty()) {
            prefs.remove(key)
        } else {
            prefs[key] = AppJson.encodeToString(sessionModelOverrideSerializer, value)
        }
    }

    private suspend fun saveSessionOverride(key: Preferences.Key<String>, keyName: String, sessionId: String, value: String?) {
        store.edit { prefs ->
            val overrides =
                decodePrefsOrNull(prefs[key], sessionModelOverrideSerializer, keyName)
                    ?.toMutableMap() ?: mutableMapOf()
            if (value.isNullOrBlank()) overrides.remove(sessionId) else overrides[sessionId] = value
            if (overrides.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = AppJson.encodeToString(sessionModelOverrideSerializer, overrides)
            }
        }
    }

    /** v1.60-A: Guardar modelo de herramienta id(null significa claro,Heredar el modelo de diálogo principal.). */
    suspend fun saveToolModel(modelId: String?) {
        store.edit { if (modelId != null) it[KEY_TOOL_MODEL_ID] = modelId else it.remove(KEY_TOOL_MODEL_ID) }
    }

    /** v2.x: Guardar enlace de modelo de gadget(null significa claro,Heredar el modelo de diálogo principal.). */
    suspend fun saveUtilityModelBinding(binding: UtilityModelBinding?) {
        store.edit { prefs ->
            if (binding != null) {
                prefs[KEY_UTILITY_MODEL_BINDING] = AppJson.encodeToString(UtilityModelBinding.serializer(), binding)
            } else {
                prefs.remove(KEY_UTILITY_MODEL_BINDING)
            }
        }
    }

    /** v2.x: Guardar enlace de modelo de herramienta grande(null significa claro,Modelo de gadget de reutilización en cascada). */
    suspend fun saveUtilityLargeModelBinding(binding: UtilityModelBinding?) {
        store.edit { prefs ->
            if (binding != null) {
                prefs[KEY_UTILITY_LARGE_MODEL_BINDING] = AppJson.encodeToString(UtilityModelBinding.serializer(), binding)
            } else {
                prefs.remove(KEY_UTILITY_LARGE_MODEL_BINDING)
            }
        }
    }

    /** v1.0.52: Guardar compresión personalizada prompt(null O una cadena vacía significa restaurar el valor predeterminado). */
    suspend fun saveCustomCompressPrompt(prompt: String?) {
        store.edit { if (!prompt.isNullOrBlank()) it[KEY_CUSTOM_COMPRESS_PROMPT] = prompt else it.remove(KEY_CUSTOM_COMPRESS_PROMPT) }
    }

    /** v1.0.52: Guardar nombres de conversaciones personalizados prompt(null O una cadena vacía significa restaurar el valor predeterminado). */
    suspend fun saveCustomTitlePrompt(prompt: String?) {
        store.edit { if (!prompt.isNullOrBlank()) it[KEY_CUSTOM_TITLE_PROMPT] = prompt else it.remove(KEY_CUSTOM_TITLE_PROMPT) }
    }

    /** v1.0.47: Guardar interruptor de estimación de token. */
    suspend fun saveTokenEstimateEnabled(enabled: Boolean) = chatSettings.saveTokenEstimateEnabled(enabled)

    /** v1.0.47 P5-2: Guarde el texto largo y péguelo en el cambio de archivo. */
    suspend fun savePasteAsFileEnabled(enabled: Boolean) = chatSettings.savePasteAsFileEnabled(enabled)

    /** v1.0.47 P5-2: Guarde el texto largo y pegue para convertir el umbral del archivo(Número de caracteres). */
    suspend fun savePasteAsFileThreshold(threshold: Int) = chatSettings.savePasteAsFileThreshold(threshold)

    /** P1-4: Guarde el interruptor de límite de contexto estilo piso. */
    suspend fun saveFloorLimiterEnabled(enabled: Boolean) = chatSettings.saveFloorLimiterEnabled(enabled)

    /** P1-4: Guarde el contexto de estilo de piso para limitar la cantidad de pisos. */
    suspend fun saveFloorLimit(limit: Int) = chatSettings.saveFloorLimit(limit)

    /** v2.x: Límite de ronda de herramientas(0=Ilimitado). */
    suspend fun saveToolLoopMaxRounds(limit: Int) = chatSettings.saveToolLoopMaxRounds(limit)

    /** v2.x: Límite de ronda de herramienta de lectura(0=Ilimitado),Se llama antes de la creación del bucle de herramientas. */
    suspend fun getToolLoopMaxRounds(): Int = toolLoopMaxRoundsFlow.first()

    /** C3: Grabar una sesión de navegación(Ir a restablecer arriba,Corte de gran capacidad,suministro"Visto recientemente"Recuperación rápida). */
    suspend fun recordSessionViewed(sessionId: String) = chatSettings.recordSessionViewed(sessionId)

    /**
     * v1.60-C: Guardar el idioma de la interfaz de la aplicación(system / zh / en).
     *
     * v1.131: Escritura sincrónica [languageSyncCache],asegurar [getLanguageSync] Devuelve el último valor inmediatamente,
     * Evite el riesgo de ANR cuando el subproceso principal runBlocking lea DataStore cuando adjuntoBaseContext se inicie en frío la próxima vez.
     */
    suspend fun saveLanguage(lang: String) = appSettings.saveLanguage(lang)

    /**
     * v1.131: Leer la configuración de idioma sincrónicamente(Caché rápido de SharedPreferences).
     *
     * usado para [io.zer0.muse.MainActivity.attachBaseContext] Espere el escenario en el que el valor del idioma debe obtenerse sincrónicamente en el hilo principal,
     * reemplazar viejo `runBlocking { languageFlow.first() }` — Esta última es la primera vez que DataStore comienza a leer el disco en frío.
     * Puede causar un bloqueo del hilo principal de 50-200 ms,ANR se activa en casos extremos.
     *
     * estrategia de almacenamiento en caché:
     *  - escribir:[saveLanguage] Escritura sincrónica SP + DataStore(DataStore para source of truth)
     *  - leer:Este método lee directamente SP,Sin bloqueo de E/S
     *  - Migrar:Si SP está vacío cuando se construye por primera vez,Sincronización asincrónica una vez desde DataStore a SP(Ver bloque de inicio)
     */
    fun getLanguageSync(): String = appSettings.getLanguageSync()

    /** Función2: Leer el borrador de entrada para la sesión especificada.(Ningún borrador devuelve una cadena vacía). */
    suspend fun loadChatDraft(sessionId: String): String {
        return store.data.first().let { prefs ->
            val drafts =
                decodePrefsOrNull(
                    prefs[KEY_CHAT_DRAFTS],
                    MapSerializer(String.serializer(), String.serializer()),
                    "ChatDrafts",
                ) ?: emptyMap()
            drafts[sessionId] ?: ""
        }
    }

    /** R-UI-02: Leer la última sesión de tarea que estaba viendo el usuario id(puede ser null). */
    suspend fun getViewedSessionId(): String? = store.data.first()[KEY_VIEWED_SESSION_ID]

    /**
     * v2.6.7 Reparación (comentarios de los usuarios: no se puede volver a la posición original después de eliminar el fondo):
     * Leer el índice del último mensaje y el desplazamiento de una sesión.
     *
     * La implementación original solo almacena en caché la posición de desplazamiento en la memoria de ViewModel (`ChatUiState.listFirstVisibleItemIndex`),
     * Una vez que se finaliza el proceso, se restablecerá a cero y, una vez reconstruido, estará en la parte superior de la lista. Aquí se cambia para colocarlo junto con la identificación de la sesión.
     *
     * @return index to offset, devuelto cuando no está registrado null
     */
    suspend fun getChatScrollPosition(sessionId: String): Pair<Int, Int>? {
        if (sessionId.isBlank()) return null
        val prefs = store.data.first()
        // Ambas claves deben existir al mismo tiempo para que se consideren un registro válido. Si falta una cara, se considerará no registrada.
        return prefs[chatScrollIndexKey(sessionId)]?.let { idx ->
            idx to (prefs[chatScrollOffsetKey(sessionId)] ?: 0)
        }
    }

    /** v2.6.7: Conservar la posición de desplazamiento de una sesión.index<0 Claro cuando. */
    suspend fun saveChatScrollPosition(sessionId: String, index: Int, offset: Int) {
        if (sessionId.isBlank()) return
        store.edit { prefs ->
            if (index < 0) {
                prefs.remove(chatScrollIndexKey(sessionId))
                prefs.remove(chatScrollOffsetKey(sessionId))
            } else {
                prefs[chatScrollIndexKey(sessionId)] = index
                prefs[chatScrollOffsetKey(sessionId)] = offset.coerceAtLeast(0)
            }
        }
    }

    private fun chatScrollIndexKey(sessionId: String) = intPreferencesKey("chat_scroll_index_$sessionId")

    private fun chatScrollOffsetKey(sessionId: String) = intPreferencesKey("chat_scroll_offset_$sessionId")

    /** R-UI-02: Persistir en la sesión vista actualmente id;null/Se borra cuando la cadena está vacía. */
    suspend fun saveViewedSessionId(id: String?) {
        store.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(KEY_VIEWED_SESSION_ID) else prefs[KEY_VIEWED_SESSION_ID] = id
        }
    }

    /** R-UI-02: Leer la sesión que se genera id(Se utiliza para no cambiar el enfoque de la vista al restaurar). */
    suspend fun getGeneratingSessionId(): String? = store.data.first()[KEY_GENERATING_SESSION_ID]

    /** R-UI-02: Persistir en la sesión que se está generando id;null/Se borra cuando la cadena está vacía. */
    suspend fun saveGeneratingSessionId(id: String?) {
        store.edit { prefs ->
            if (id.isNullOrBlank()) prefs.remove(KEY_GENERATING_SESSION_ID) else prefs[KEY_GENERATING_SESSION_ID] = id
        }
    }

    /** v1.x: Leer la fecha del último saludo, notificación de recordatorio personalizado.(YYYY-MM-DD,Sin reglas null). */
    suspend fun getLastGreetingNotifyDate(): String? = store.data.first()[KEY_LAST_GREETING_NOTIFY_DATE]

    /** v1.x: Grabe la fecha de notificación del recordatorio personalizado del saludo. */
    suspend fun saveLastGreetingNotifyDate(date: String) {
        store.edit { prefs -> prefs[KEY_LAST_GREETING_NOTIFY_DATE] = date }
    }

    /** v1.x: Leer saludo LLM genera caché de resultados(Formato "date|hint",Sin reglas null). */
    suspend fun getGreetingHintCache(): String? = store.data.first()[KEY_GREETING_HINT_CACHE]

    /** v1.x: Guarde el caché de resultados generados por LLM de saludo. */
    suspend fun saveGreetingHintCache(value: String) {
        store.edit { prefs -> prefs[KEY_GREETING_HINT_CACHE] = value }
    }

    /**
     * Obtener el seleccionado actualmente [Model](Buscar por selectedModelId de los modelos que activan el Proveedor).
     * selectedModelId Si está vacío, recurra al primer modelo que activó el Proveedor.
     * Devuelve nulo si no hay ningún proveedor activo o la lista de modelos está vacía.
     */
    suspend fun getSelectedModel(): Model? {
        val config = get() ?: return null
        val modelId = selectedModelIdFlow.first()
        return config.models.firstOrNull { it.id == modelId } ?: config.models.firstOrNull()
    }

    /**
     * Encuentre proveedores configurados y habilitados por ID.
     *
     * Un proveedor desactivado equivale a inexistencia:La persona que llama se vuelve nula y luego hace lo suyo."No configurado"rama,
     * En lugar de tomar una copia de la configuración que el usuario ha desactivado y continuar enviando solicitudes.
     */
    suspend fun getProviderById(id: String): ProviderConfig? =
        providersFlow.first().firstOrNull { it.id == id && it.enabled }

    suspend fun markOnboardingShown() = appearance.markOnboardingShown()

    /**
     * Proveedor actualmente activo:Los elementos de activación tienen prioridad,El segundo es el primer proveedor habilitado.
     *
     * Devuelto cuando todos están deshabilitados o no configurados en absoluto null —— No tomes el primero,Deje que la persona que llama lo maneje explícitamente"No hay proveedores disponibles".
     */
    override suspend fun get(): ProviderConfig? {
        val enabledProviders = providersFlow.first().filter { it.enabled }
        if (enabledProviders.isEmpty()) return null
        val activeId = activeProviderIdFlow.first()
        return enabledProviders.firstOrNull { it.id == activeId } ?: enabledProviders.first()
    }

    /**
     * v2.2.1: [ProviderConfigStore] Reserva preferida — Para tareas en segundo plano"Modelo actualmente seleccionado"En lugar de activar el primer modelo del Proveedor.
     */
    override suspend fun getPreferredModel(): Model? = getSelectedModel()

    /** v1.54: Toda la lista de proveedores(para incorporar la selección del proveedor). */
    override suspend fun getAllProviders(): List<ProviderConfig> = providersFlow.first()

    /**
     * v2.6.2(Codex Solución de inicio de sesión): Los proveedores existentes del Codex se recuperan por sí solos.
     *
     * fondo:La versión anterior lee desde el nivel superior de id_token. `chatgpt_account_id`(En realidad anidado en
     * `https://api.openai.com/auth` espacio de nombres), siempre es nulo, lo que provoca que el usuario que ha iniciado sesión
     * provider Los customHeaders nunca se escriben `ChatGPT-Account-ID`. Este método comienza
     * Escanee proveedores que hayan almacenado tokens de OAuth, analice y reescriba el encabezado del id_token almacenado cifrado,
     * Permita que los usuarios antiguos se recuperen sin volver a iniciar sesión.
     *
     * idempotente:Solo escriba cuando los encabezados carezcan de ChatGPT-Account-ID y el ID de cuenta se pueda analizar;
     * No se produjo ningún error en el almacenamiento/análisis de OAuth/no se realiza ninguna acción si el encabezado ya existe.
     *
     * @return Número de proveedores realmente reparados
     */
    suspend fun healCodexAccountHeadersIfNeeded(): Int {
        val store = io.zer0.muse.auth.OAuthManager.secureStoreOrNull() ?: return 0
        var healed = 0
        providersFlow.first().forEach { provider ->
            // Solo maneja proveedores basados en OpenAI configurados con OAuth (Codex Predeterminado type=OPENAI)
            if (provider.oauthConfig == null) return@forEach
            if (provider.type != io.zer0.ai.core.ProviderType.OPENAI) return@forEach
            val bundle = runCatching { store.getOAuthToken(provider.id) }.getOrNull() ?: return@forEach
            // id_token tiene prioridad (fuente estándar); respaldo si falta access_token ——
            // Codex El access_token también es un JWT y contiene el mismo reclamo de espacio de nombres.
            val accountId = io.zer0.muse.auth.parseChatGptAccountId(bundle.idToken)
                ?: io.zer0.muse.auth.parseChatGptAccountId(bundle.accessToken)
            if (accountId.isNullOrBlank()) return@forEach
            val specific = provider.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.OpenAI
                ?: return@forEach
            if (specific.customHeaders["ChatGPT-Account-ID"] == accountId) return@forEach
            val updated = specific.copy(
                customHeaders = specific.customHeaders +
                    mapOf("ChatGPT-Account-ID" to accountId, "originator" to "muse"),
            )
            try {
                updateProvider(provider.copy(specific = updated))
                healed++
                Logger.i(
                    "SettingsRepository",
                    "Codex Autorreparación del encabezado de cuenta: provider=${provider.id} account=${accountId.take(8)}…",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("SettingsRepository", "Codex No se pudo escribir la autorreparación del encabezado de la cuenta: ${e.message}", e)
            }
        }
        return healed
    }

    private suspend fun migrateLegacyProviderIfNeeded() {
        // M-SR5: AtomicBoolean Asegúrese de que la migración solo se realice una vez,Evite llamadas iniciales simultáneas
        // Dos corrutinas observan simultáneamente que los proveedores están vacíos y repiten addProvider(El JSON antiguo se analizará dos veces)
        if (!migrationDone.compareAndSet(false, true)) return
        if (providersFlow.first().isNotEmpty()) return
        val legacy = store.data.first()[KEY_PROVIDER_LEGACY]
        if (legacy != null) {
            runCatching { AppJson.decodeFromString(ProviderConfig.serializer(), legacy) }.onSuccess { config ->
                addProvider(config)
                store.edit { it.remove(KEY_PROVIDER_LEGACY) }
            }
        }
    }

    /**
     * v2.x: Migración de enlace de modelo asistida — antigua clave de identificación única(modelo de herramienta/modelo subagente) → Vinculante con el proveedor.
     *
     * fondo:v1.60-A Solo existen modelos de herramientas y modelos de subagente v2.0 model id,Coincidencia por identificación entre proveedores
     * Un modelo pequeño con el mismo nombre que puede llegar a canales no relacionados(v1.0.62 La causa principal de la obsolescencia del modelo de compresión). nueva encuadernación
     * {providerId, modelId},Al migrar, verifique la propiedad de los proveedores existentes.
     *
     * idempotente:AtomicBoolean solo corre una vez;No sobrescriba la nueva clave si ya existe;Conservar claves antiguas sin borrarlas(Reversión de soporte).
     * No se puede encontrar la atribución(Provider Eliminado)Omita este elemento cuando,No bloquea el inicio.
     */
    /**
     * Devuelve el bit de activación del proveedor gratuito que la versión anterior configuró automáticamente para activación.
     *
     * Sólo actúe cuando se cumplan dos condiciones al mismo tiempo:
     *  1. El bit de activación actual apunta al proveedor gratuito;
     *  2. Hay otros proveedores en la lista (configurados por el usuario).
     *
     * No te muevas cuando no haya otros proveedores. —— En ese momento, la versión gratuita era la única disponible y renunciar a ella sólo convertiría a los usuarios en“Ningún modelo disponible”.
     * Idempotente: después de darse por vencido una vez, el bit de activación ya no apunta a la versión gratuita y la siguiente llamada volverá directamente.
     */
    private suspend fun releaseAutoActivatedFreeProvider() {
        runCatching {
            val providers = providersFlow.first()
            val activeId = activeProviderIdFlow.first()
            if (activeId != SiliconFlowFreeModels.PROVIDER_ID) return@runCatching
            val replacement = providers.firstOrNull { it.id != SiliconFlowFreeModels.PROVIDER_ID } ?: return@runCatching
            setActiveProvider(replacement.id)
            Logger.i("SettingsRepository", "El bit de activación se ha movido del proveedor gratuito al configurable por el usuario. ${replacement.id}")
        }.onFailure { Logger.w("SettingsRepository", "No se pudo liberar el bit de activación del proveedor gratuito: ${it.message}") }
    }

    private suspend fun migrateLegacyUtilityBindingsIfNeeded() {
        if (!utilityBindingMigrationDone.compareAndSet(false, true)) return
        val prefs = store.data.first()
        val legacyTool = prefs[KEY_TOOL_MODEL_ID]
        val legacySubagent = prefs[KEY_SUBAGENT_MODEL_ID]
        val needSmall = prefs[KEY_UTILITY_MODEL_BINDING] == null && !legacyTool.isNullOrBlank()
        val needLarge = prefs[KEY_UTILITY_LARGE_MODEL_BINDING] == null && !legacySubagent.isNullOrBlank()
        if (!needSmall && !needLarge) return
        val providers = providersFlow.first()

        fun bind(modelId: String?): UtilityModelBinding? {
            if (modelId.isNullOrBlank()) return null
            val owner = providers.firstOrNull { p -> p.models.any { it.id == modelId } } ?: return null
            return UtilityModelBinding(providerId = owner.id, modelId = modelId)
        }
        val small = if (needSmall) bind(legacyTool) else null
        val large = if (needLarge) bind(legacySubagent) else null
        if (small == null && large == null) return
        store.edit { e ->
            if (small != null) e[KEY_UTILITY_MODEL_BINDING] = AppJson.encodeToString(UtilityModelBinding.serializer(), small)
            if (large != null) e[KEY_UTILITY_LARGE_MODEL_BINDING] = AppJson.encodeToString(UtilityModelBinding.serializer(), large)
        }
        Logger.i(
            "SettingsRepository",
            "legacy utility bindings migrated: small=${small?.providerId}/${small?.modelId}, large=${large?.providerId}/${large?.modelId}",
        )
    }

    private suspend fun activeProviderFromPrefs(prefs: Preferences): ProviderConfig? {
        // Los proveedores desactivados no participan en la selección del tiempo de ejecución;Deshabilitar todo equivale a no configurar(Regresar null,No tomes el primero)
        val providers = prefs[KEY_PROVIDERS]?.let { decodeProviders(it) }?.filter { it.enabled } ?: return null
        if (providers.isEmpty()) return null
        val activeId = prefs[KEY_ACTIVE_PROVIDER_ID]
        val active = if (activeId == null) {
            providers.firstOrNull()
        } else {
            providers.firstOrNull { it.id == activeId } ?: providers.firstOrNull()
        }
        return active?.let { enrichWithSpecDefaults(it) }
    }

    /**
     * v1.0.7: fusión de tres niveles — Enriquezca la configuración del usuario con la lista de modelos predeterminados de especificaciones.
     *
     * specId tiempo no vacío,de [presetProviders]
     * encontrar spec,Fusionar modelo predeterminado de especificaciones + modelo de superposición de usuario(Los usuarios con el mismo ID tienen prioridad).
     * specId para null(Proveedor personalizado puro)o la especificación se devuelve sin cambios si no se encuentra.
     */
    private fun enrichWithSpecDefaults(config: ProviderConfig): ProviderConfig {
        val specId = config.specId ?: return config
        val spec = presetProviders.bySpecId(specId) ?: return config
        return ProviderSpecMerger.enrichConfig(config, spec)
    }

    /**
     * v1.0.7: Migración de datos — Dar a los que ya tienen "preset_" Inferencia de configuración para la identificación del prefijo specId(idempotente).
     *
     * datos antiguos(v1.0.6 y antes)Después de la deserialización specId=null,Complete aquí por prefijo de identificación:
     *  "preset_openai" → specId="openai"
     *  "preset_deepseek" → specId="deepseek"
     *
     * idempotente:Las configuraciones con specId existente no se ven afectadas. Proveedor personalizado puro(id no con "preset_" comienzo)No afectado.
     * la próxima vez [updateProvider] specId es naturalmente persistente al escribir,Las lecturas posteriores ya no requieren inferencias.
     */
    private fun migrateSpecId(config: ProviderConfig): ProviderConfig {
        if (config.specId != null) return config
        if (!config.id.startsWith("preset_")) return config
        val inferred = config.id.removePrefix("preset_").ifBlank { null } ?: return config
        return config.copy(specId = inferred)
    }

    // ── v1.53-A2: Campos sensibles(apiKey)Cifrado y descifrado transparente ──────────────────────────
    // Cifrar antes de escribir en DataStore,Descifrar después de leer.ai Módulo y UI inconscientes.
    // Los datos de texto sin formato de la versión anterior descifran la transmisión transparente,cifrar cifra automáticamente la próxima vez que escriba(No se requiere migración explícita).

    /** Cifre la apiKey de cada proveedor y serialícela en JSON. */
    private suspend fun encodeProviders(list: List<ProviderConfig>): String = AppJson.encodeToString(
        ListSerializer(ProviderConfig.serializer()),
        list.map { it.copy(apiKey = SecureKeyStore.encrypt(it.apiKey)) },
    )

    /**
     * Descifre la apiKey de cada proveedor después de deserializar el JSON.
     *
     * M-SR1: El error de análisis ya no traga silenciosamente las excepciones — Registro Logger.w para un fácil diagnóstico"La configuración se perdió repentinamente"pregunta,
     * Devuelve nulo para permitir que el upstream recurra a la lista vacía(Comportamiento consistente con decodePrefsOrNull).
     *
     * usar [resultOf] En lugar de runCatching: el bloque contiene una llamada suspendida(SecureKeyStore.decrypt),
     * runCatching Se tragará CancellationException y destruirá la concurrencia estructurada.,resultOf Se revenderá.
     */
    private suspend fun decodeProviders(json: String): List<ProviderConfig>? = resultOf {
        AppJson.decodeFromString(ListSerializer(ProviderConfig.serializer()), json)
            .map { it.copy(apiKey = SecureKeyStore.decrypt(it.apiKey)) }
            // v1.0.7: Migración idempotente — dar datos antiguos(v1.0.6 y antes,Ningún campo especificado)inferencia specId
            .map { migrateSpecId(it) }
    }.onError { msg, t ->
        Logger.w("SettingsRepository", "Providers JSON El análisis falló,revertir null: $msg", t)
    }.getOrNull()

    // ── copia de seguridad: Configurar la exportación de instantáneas/importar ─────────────────────────────────────

    /**
     * Para respaldo: Exporte todas las configuraciones de DataStore no confidenciales a Map<String, String>.
     *
     * sensible key(PIN/Provider API Key/MCP token/Credenciales de respaldo en la nube/Contraseña del agente, etc.)saltado,
     * Porque el cifrado depende del nivel del dispositivo SecureKeyStore,El descifrado entre dispositivos fallará.
     * Los usuarios deben volver a ingresar estas configuraciones confidenciales después de la recuperación.
     */
    suspend fun exportSettingsSnapshot(includeSecrets: Boolean = false): Map<String, String> {
        val prefs = store.data.first()
        val result = mutableMapOf<String, String>()
        // tipo de cadena segura key
        val safeStringKeys = if (includeSecrets) {
            // v2.6.0: Cuando el usuario marca explícitamente "Incluir clave de proveedor", la lista blanca se expande a proveedores_json (
            // Contiene el token apiKey/oauth de cada proveedor)—— El texto sin formato ingresa al archivo de respaldo y la interfaz de usuario debe advertir antes de exportar.
            SettingsSnapshotPolicy.safeStringKeys + SettingsSnapshotPolicy.secretStringKeys
        } else {
            SettingsSnapshotPolicy.safeStringKeys
        }
        val safeBooleanKeys = SettingsSnapshotPolicy.safeBooleanKeys
        val safeIntKeys = SettingsSnapshotPolicy.safeIntKeys
        val safeLongKeys = SettingsSnapshotPolicy.safeLongKeys
        val safeStringSetKeys = SettingsSnapshotPolicy.safeStringSetKeys
        for (name in safeStringKeys) {
            prefs[stringPreferencesKey(name)]?.let { result[name] = it }
        }
        for (name in safeBooleanKeys) {
            prefs[booleanPreferencesKey(name)]?.let { result["bool:$name"] = it.toString() }
        }
        for (name in safeIntKeys) {
            prefs[intPreferencesKey(name)]?.let { result["int:$name"] = it.toString() }
        }
        for (name in safeLongKeys) {
            prefs[longPreferencesKey(name)]?.let { result["long:$name"] = it.toString() }
        }
        // v2.6.9 fix (AUDIT §11.3-7): stringSet El tipo no había exportado ningún bucle antes,
        // Nunca se puede realizar una copia de seguridad de la colección de momentos/álbumes de fotos. Codificación ver [encodeStringSet].
        for (name in safeStringSetKeys) {
            prefs[stringSetPreferencesKey(name)]?.let { set ->
                result[SettingsSnapshotPolicy.STRING_SET_PREFIX + name] = encodeStringSet(set)
            }
        }
        return result
    }

    /**
     * Para respaldo y recuperación: Vuelva a escribir la instantánea de la configuración en el DataStore.
     *
     * Sobrescribe solo los contenidos en la instantánea. key,no afecta a otros key(como sensible key).
     * Cuatro tipos de escalares(string/bool/int/long)Consistente con la semántica de stringSet:La clave está presente en la instantánea.**Portada**,
     * Si la clave no se incluye en la instantánea, el valor original en el dispositivo de destino permanece sin cambios. —— Sin fusión de colecciones,Tampoco se borra cuando falta.
     * Razones para alinearse con otros tipos:La fusión hará“Copia de seguridad antigua + dispositivo nuevo ya guardado”Consigue la unión de dos copias,
     * Los usuarios no pueden ver lo que se ha restaurado.;Y la cobertura es al menos predecible.
     */
    suspend fun restoreSettingsSnapshot(snapshot: Map<String, String>, includeSecrets: Boolean = false) {
        if (snapshot.isEmpty()) return
        // B-22: Clave de seguridad de filtrado secundario del lado de recuperación.
        // Lado de exportación(exportSettingsSnapshot)Exportado por la lista blanca de SettingsSnapshotPolicy,
        // Pero los datos de recuperación en sí pueden provenir de una falsificación externa./Copia de seguridad manipulada(Inyecte directamente cualquier DataStore key,
        // Como sobrescribir configuraciones confidenciales). La misma lista blanca de SettingsSnapshotPolicy.isSafeKey se reutiliza aquí como última línea de defensa.,
        // Consistente con la semántica del lado de las exportaciones,Evite escribir claves no seguras durante la recuperación.
        // v2.6.0: includeSecrets proveedores_json (simétrico al lado de exportación que contiene la lista blanca de claves).
        val safeFilter: (String) -> Boolean =
            if (includeSecrets) SettingsSnapshotPolicy::isSafeKeyWithSecrets else SettingsSnapshotPolicy::isSafeKey
        val safe = snapshot.filterKeys(safeFilter)
        if (safe.isEmpty()) return
        store.edit { prefs ->
            for ((rawKey, value) in safe) prefs.writeSnapshotEntry(rawKey, value)
        }
    }

    /**
     * Vuelve a escribir una entrada por el prefijo de tipo de la clave de instantánea.
     *
     * No es para desarmarlo“Reducir el número de filas.”,pero deja [restoreSettingsSnapshot] Únicamente responsable del filtrado y las transacciones.:
     * El envío de tipos en sí es una tabla de cinco ramas.,Tarde o temprano se añadirán tipos.,y“Filtrado de seguridad + transacciones”mezclado en una función,
     * En el futuro, la complejidad ciclomática deberá recalcularse cada vez que se agregue una nueva categoría.
     *
     * El análisis falló(datos incorrectos)uniformemente `continue`(es decir, omitir el key,no escribas):Escribe una colección vacía o escribe un valor predeterminado
     * Destruirá silenciosamente los datos del usuario,Peor que ninguna recuperación.
     */
    private fun MutablePreferences.writeSnapshotEntry(rawKey: String, value: String) {
        when {
            rawKey.startsWith("bool:") -> {
                this[booleanPreferencesKey(rawKey.removePrefix("bool:"))] = value.toBoolean()
            }
            rawKey.startsWith("int:") -> value.toIntOrNull()?.let { parsed ->
                this[intPreferencesKey(rawKey.removePrefix("int:"))] = parsed
            }
            rawKey.startsWith("long:") -> value.toLongOrNull()?.let { parsed ->
                this[longPreferencesKey(rawKey.removePrefix("long:"))] = parsed
            }
            rawKey.startsWith(SettingsSnapshotPolicy.STRING_SET_PREFIX) -> decodeStringSet(value)?.let { parsed ->
                this[stringSetPreferencesKey(rawKey.removePrefix(SettingsSnapshotPolicy.STRING_SET_PREFIX))] = parsed
            }
            else -> this[stringPreferencesKey(rawKey)] = value
        }
    }

    companion object {
        /**
         * stringSet codificación de instantáneas:JSON Cadena de matriz.
         *
         * No hay necesidad `joinToString(",")`:El valor en sí es el hashCode o dinámica de la uri de la imagen. id,
         * Aunque actualmente no contiene comas.,Pero no hay ningún acuerdo que prohíba;JSON Las matrices no son ambiguas debido a los separadores en los valores.,
         * También puedes poner una colección vacía.("[]")con“no, esto key”diferenciar. Salida después de clasificar,Garantizado para ser el mismo conjunto.
         * El texto exportado dos veces es consistente.(Conveniente para realizar copias de seguridad y comparar).
         */
        internal fun encodeStringSet(values: Set<String>): String =
            AppJson.encodeToString(ListSerializer(String.serializer()), values.sorted())

        /** decodificar [encodeStringSet] producto de;Devoluciones en formato incorrecto null(La persona que llama se salta el key). */
        internal fun decodeStringSet(raw: String): Set<String>? = resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), raw).toSet()
        }.getOrNull()

        private val KEY_PROVIDERS = stringPreferencesKey("providers_json")
        private val KEY_ACTIVE_PROVIDER_ID = stringPreferencesKey("active_provider_id")
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model_id")
        private val KEY_SESSION_MODEL_OVERRIDES = stringPreferencesKey("session_model_overrides_json")
        private val KEY_SESSION_PROVIDER_OVERRIDES = stringPreferencesKey("session_provider_overrides_json")
        private val KEY_TOOL_MODEL_ID = stringPreferencesKey("tool_model_id")

        /** v2.0: modelo subagente id(Un modelo ligero utilizado por subagentes en segundo plano.). */
        private val KEY_SUBAGENT_MODEL_ID = stringPreferencesKey("subagent_model_id")

        /** v2.x: Enlace de modelo de gadget(JSON {providerId, modelId};tener prioridad sobre lo viejo tool_model_id). */
        private val KEY_UTILITY_MODEL_BINDING = stringPreferencesKey("utility_model_binding")

        /** v2.x: Encuadernación de modelos de herramientas grandes(JSON {providerId, modelId};tener prioridad sobre lo viejo subagent_model_id). */
        private val KEY_UTILITY_LARGE_MODEL_BINDING = stringPreferencesKey("utility_large_model_binding")

        /** modelo comprimido id(Modelo económico independiente,Para uso de ConversationCompressor). */
        private val KEY_COMPRESS_MODEL_ID = stringPreferencesKey("compress_model_id")

        /** v1.0.52: Compresión personalizada prompt(El usuario puede anular las directivas de compresión predeterminadas,null Indica el valor predeterminado). */
        private val KEY_CUSTOM_COMPRESS_PROMPT = stringPreferencesKey("custom_compress_prompt")

        /** v1.0.52: Nomenclatura de conversación personalizada prompt(El usuario puede anular las directivas de nomenclatura predeterminadas,null Indica el valor predeterminado). */
        private val KEY_CUSTOM_TITLE_PROMPT = stringPreferencesKey("custom_title_prompt")
        /** v1.0.47: Token Cambio de estimación. */
        /** P1-4: restricciones de contexto del piso */
        private val KEY_MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")

        /** v1.0.51: Marca de migración de memoria de almacenamiento única — El resumen continuo de la sesión del historial se inicia por primera vez después de la actualización. */
        private val KEY_MEMORY_MIGRATION_V1_0_51_DONE = booleanPreferencesKey("memory_migration_v1_0_51_done")

        /** v1.0.52 P2-2: Espacio de memoria actualmente seleccionado id(Predeterminado "default"). */
        private val KEY_CURRENT_SPACE_ID = stringPreferencesKey("current_space_id")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_THEME_ID = stringPreferencesKey("theme_id")
        private val KEY_DARK_THEME_ID = stringPreferencesKey("dark_theme_id")
        private val KEY_THEME_SCHEDULE = stringPreferencesKey("theme_schedule_json")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

        /** v1.97 gap7: Lista de temas personalizados JSON(Serialización de matrices,matriz vacía=No creado). */
        private val KEY_CUSTOM_THEMES = stringPreferencesKey("custom_themes_json")
        private val KEY_FONT_SIZE_SCALE = stringPreferencesKey("font_size_scale")

        /** v1.95: Iniciar página predeterminada(0=Tarea, 1=Agent, 2=chat grupal). */
        private val KEY_DEFAULT_HOME_PAGE = intPreferencesKey("default_home_page")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")

        // v1.95: El reconocimiento de voz del sistema pregunta por primera vez si se ha mostrado
        private val KEY_ASR_TIP_SHOWN = booleanPreferencesKey("asr_tip_shown")

        // v1.95: Cambio de biblioteca de emoticonos(Desactivado de forma predeterminada)
        private val KEY_STICKER_ENABLED = booleanPreferencesKey("sticker_enabled")

        // v1.95: Probabilidad de enviar emoticones(0-100,Predeterminado 30)
        private val KEY_STICKER_SEND_PROBABILITY = intPreferencesKey("sticker_send_probability")

        // v2.x: Rango de frecuencia de envío de emoticonos(occasionally/normal/frequent,Predeterminado normal)
        private val KEY_STICKER_FREQUENCY = stringPreferencesKey("sticker_frequency")

        // v2.x: Límite de caché de memoria de sesión(2..12,Predeterminado 5)
        private val KEY_SESSION_CACHE_SIZE = intPreferencesKey("session_cache_size")
        private val KEY_WEB_SEARCH_CONFIG = stringPreferencesKey("web_search_config_json")
        private val KEY_CLOUD_BACKUP_CONFIG = stringPreferencesKey("cloud_backup_config_json")
        private val KEY_WEB_SERVER_CONFIG = stringPreferencesKey("web_server_config_json")
        private val KEY_ASR_CONFIG = stringPreferencesKey("asr_config_json")
        private val KEY_MCP_SERVERS = stringPreferencesKey("mcp_servers_json")
        private val KEY_QUICK_CAPTURE_ENABLED = booleanPreferencesKey("quick_capture_enabled")

        /** AUD-20261011-TASKER-ABUSE: Tasker Conmutador de enlace de transmisión(Desactivado de forma predeterminada). */
        private val KEY_TASKER_TRIGGER_ENABLED = booleanPreferencesKey("tasker_trigger_enabled")
        private val KEY_QUICK_CAPTURE_OVERLAY_ENABLED = booleanPreferencesKey("quick_capture_overlay_enabled")
        private val KEY_QUICK_CAPTURE_OVERLAY_DEFAULT_MIGRATED =
            booleanPreferencesKey("quick_capture_overlay_default_migrated")
        private val KEY_QUICK_CAPTURE_OVERLAY_VERTICAL_POSITION =
            floatPreferencesKey("quick_capture_overlay_vertical_position")
        private const val DEFAULT_QUICK_CAPTURE_OVERLAY_ENABLED = false
        private const val DEFAULT_QUICK_CAPTURE_OVERLAY_VERTICAL_POSITION = 0.5f
        private val KEY_PROMPT_TEMPLATES = stringPreferencesKey("prompt_templates_json")
        private val KEY_PROVIDER_LEGACY = stringPreferencesKey("provider_config_json")
        private val KEY_ACCOUNT_LOGGED_IN = booleanPreferencesKey("account_logged_in")
        private val KEY_ACCOUNT_USER_NAME = stringPreferencesKey("account_user_name")
        private val KEY_ACCOUNT_LOGIN_AT = longPreferencesKey("account_login_at")
        private val KEY_ACCOUNT_LOGIN_METHOD = stringPreferencesKey("account_login_method")
        private val KEY_ACCOUNT_GUEST_MODE = booleanPreferencesKey("account_guest_mode")
        private val KEY_ACCOUNT_AVATAR_URI = stringPreferencesKey("account_avatar_uri")
        private val KEY_MODEL_PROFILES = stringPreferencesKey("model_profiles_json")

        // v0.30-a: Retrato de usuario
        private val KEY_USER_PROFILE = stringPreferencesKey("user_profile_json")

        // v0.31: Preferencias de comportamiento en el chat
        // v0.32: Configuración avanzada del sistema de memoria
        private val KEY_MEMORY_CONFIG = stringPreferencesKey("memory_config_json")
        private val KEY_EXPERIENCE_ENABLED = booleanPreferencesKey("experience_enabled")
        private val KEY_DEFAULT_ASSISTANT_ID = stringPreferencesKey("default_assistant_id")

        // PII Guard cambiar(Habilitado por defecto)
        private val KEY_PII_GUARD_ENABLED = booleanPreferencesKey("pii_guard_enabled")
        /** ANR Interruptor de detección(Predeterminado true). */
        /** Interruptor de informes de datos de rendimiento(Predeterminado false,Privacidad primero). */
        // v1.104: PIN Bloquear la persistencia de la protección contra grietas por fuerza bruta(Usado antes rememberSaveable,Mata el proceso y reinícialo.)
        private val KEY_EXPERIMENTS = stringPreferencesKey("experiments_json")
        private val KEY_SHARE_TEMPLATE = stringPreferencesKey("share_template_json")
        private val KEY_MEDIA_CONFIG = stringPreferencesKey("media_config_json")
        private val KEY_PROXY_CONFIG = stringPreferencesKey("proxy_config_v1")
        private val KEY_PROACTIVE_MESSAGE = stringPreferencesKey("proactive_message_json")
        private val KEY_IMAGE_GEN_CONFIG = stringPreferencesKey("image_gen_config_json")

        /** Configuración de parámetros predeterminados para la generación de video. */
        private val KEY_VIDEO_GEN_CONFIG = stringPreferencesKey("video_gen_config_json")

        /** v1.25: Configuración de colaboración multiagente (lista de equipos y switch maestro). */
        private val KEY_MULTI_AGENT_CONFIG = stringPreferencesKey("multi_agent_config_json")

        /** v1.201: LLM Modelo utilizado en revisión integral id(independiente key,No serializado con MultiAgentConfig JSON). */
        private val KEY_MULTI_AGENT_REVIEW_MODEL = stringPreferencesKey("multi_agent_review_model")

        /** v1.201: Cambio de revisión integral de Global LLM(independiente key,No serializado con MultiAgentConfig JSON). */
        private val KEY_MULTI_AGENT_LLM_REVIEW_ENABLED = booleanPreferencesKey("multi_agent_llm_review_enabled")

        /** v1.25: Interruptor de asistencia visual (permitir que los modelos de solo texto pasen a los modelos visuales)"ver"imagen). */
        private val KEY_VISION_ENABLED = booleanPreferencesKey("vision_enabled")

        /** P2-12: Interruptor de entrada de texto enriquecido(Después de abrir, InputBar se reemplaza por RichInputBar). */
        /** v1.25: El ID del modelo utilizado por la ayuda visual. */
        private val KEY_VISION_MODEL_ID = stringPreferencesKey("vision_model_id")

        /** v1.25: El ID del proveedor utilizado por la ayuda visual. */
        private val KEY_VISION_PROVIDER_ID = stringPreferencesKey("vision_provider_id")

        /** v1.54: RAG Configuración(embedding fuente + parámetros de búsqueda). */
        private val KEY_RAG_CONFIG = stringPreferencesKey("rag_config_json")

        /** v1.0.53: RAG embedding Indicador de finalización de migración única de origen(ver migrateRagEmbeddingSourceIfNeeded). */
        private val KEY_RAG_EMBEDDING_SOURCE_MIGRATED =
            booleanPreferencesKey("rag_embedding_source_migrated")

        /** F-33: La clave de configuración de incorporación utilizada en el índice exitoso más reciente. */
        private val KEY_LAST_EMBEDDING_MODEL = stringPreferencesKey("last_embedding_model_key")
        private val KEY_CHAT_DRAFTS = stringPreferencesKey("chat_drafts_json")

        // v2.3: Caché de prueba de conexión JSON(providerId → result,traer TTL)
        private val KEY_CONNECTION_TEST_CACHE = stringPreferencesKey("connection_test_cache_json")

        // v2.3: Configuración de enrutamiento del modelo de tarea
        private val KEY_TASK_ROUTING_CONFIG = stringPreferencesKey("task_routing_config_json")

        // v1.133: GitHub Release Actualizar clave relacionada con la verificación
        private val KEY_LAST_UPDATE_CHECK_TIME = longPreferencesKey("last_update_check_time")
        private val KEY_LATEST_RELEASE_INFO = stringPreferencesKey("latest_release_info_json")
        private val KEY_UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")

        // v1.0.72: Número de versión de actualización que el usuario ignora activamente(tagName),Esta versión ya no está disponible Banner
        private val KEY_IGNORED_UPDATE_VERSION = stringPreferencesKey("ignored_update_version")

        // v1.0.72: Pulsador de resumen diario(Desactivado de forma predeterminada)
        private val KEY_DAILY_SUMMARY_ENABLED = booleanPreferencesKey("daily_summary_enabled")

        /** v1.xxx: Período de resumen diario(24 hora por hora,separados por comas;El período de reserva predeterminado no está configurado). */
        private val KEY_DAILY_SUMMARY_SLOTS = stringPreferencesKey("daily_summary_slots")

        /** v1.xxx: Interruptor de control maestro de despacho backend(Habilitado por defecto;El trabajador se salta el cuerpo de ejecución en cada ciclo después del cierre). */
        private val KEY_SCHEDULE_WORK_ENABLED = booleanPreferencesKey("schedule_work_enabled")

        /** v2.6.13: Interruptor maestro enchufable(Habilitado por defecto;Cerrar la herramienta posterior al complemento/Hook/No se ejecutan todas las activaciones.). */
        private val KEY_PLUGINS_ENABLED = booleanPreferencesKey("plugins_globally_enabled")

        // v1.x: El último resumen diario.,Para mostrar el saludo de la página de inicio
        private val KEY_DAILY_SUMMARY_DATE = stringPreferencesKey("daily_summary_date")
        private val KEY_DAILY_SUMMARY_TEXT = stringPreferencesKey("daily_summary_text")

        // Resumen diario de puntos de tiempo adelantados en el formato aaaa-MM-dd#HHmm; Se utiliza para la deduplicación en proceso y de WorkManager.
        private val KEY_DAILY_SUMMARY_CLAIMED_SLOTS = stringSetPreferencesKey("daily_summary_claimed_slots")

        // El momento en el que se ha guardado correctamente, separado del reclamado, permite que el primer plano vuelva a intentarlo después de que el proceso salga a mitad de generación.
        private val KEY_DAILY_SUMMARY_COMPLETED_SLOTS = stringSetPreferencesKey("daily_summary_completed_slots")
        private const val DAILY_SUMMARY_SLOT_HISTORY_LIMIT = 16

        /** v1.xxx: Período predeterminado del resumen diario(09/12/21/00),Manténgase coherente con el comportamiento histórico codificado. */
        val DEFAULT_DAILY_SUMMARY_SLOTS: List<Int> = listOf(0, 9, 12, 21)

        // v1.0.72: AI Número diario de elementos dinámicos en Momentos(0-10,Predeterminado 2)
        private val KEY_DAILY_MOMENT_COUNT = intPreferencesKey("daily_moment_count")

        /** v2.6.7(P0-1): Modo de contenido de momentos (real / character) cadena. */
        private val KEY_MOMENT_CONTENT_MODE = stringPreferencesKey("moment_content_mode")

        // v2.6.9: Residente se mantiene vivo.
        private val KEY_KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        private val KEY_KEEP_ALIVE_WAKELOCK = booleanPreferencesKey("keep_alive_wakelock")
        private val KEY_KEEP_ALIVE_NOTIFICATION_TITLE = stringPreferencesKey("keep_alive_notification_title")
        private val KEY_KEEP_ALIVE_NOTIFICATION_TEXT = stringPreferencesKey("keep_alive_notification_text")
        private val KEY_MOMENTS_COVER_IMAGE = stringPreferencesKey("moments_cover_image")
        private val KEY_MINIPHONE_WALLPAPER = stringPreferencesKey("miniphone_wallpaper")

        // P3-7: cubrir/Se corrigió la ranura para el nombre del archivo cuando el fondo de pantalla se coloca en el archivo.(filesDir/moments/)
        private const val COVER_IMAGE_SLOT = "cover"
        private const val WALLPAPER_SLOT = "wallpaper"
        private val KEY_MOMENTS_LAST_READ_AT = longPreferencesKey("moments_last_read_at")
        private val KEY_MOMENT_MESSAGES_LAST_READ_AT = longPreferencesKey("moment_messages_last_read_at")
        private val KEY_MOMENT_FAVORITE_IDS = stringSetPreferencesKey("moment_favorite_ids")
        private val KEY_CHAT_BACKGROUND = stringPreferencesKey("chat_background")

        // v2.6.2(comentarios de los usuarios): Fondo dedicado al modo oscuro (opcional; si no se configura, el modo oscuro seguirá usando el fondo principal)
        private val KEY_CHAT_BACKGROUND_DARK = stringPreferencesKey("chat_background_dark")

        // v2.6.2(comentarios de los usuarios): Fondo de chat de tres capas —— nivel de sesión > nivel asistente > Incumplimiento global.
        // Los valores son todas rutas de archivos de imagen (file:// principio) o nulo. Prefijo dinámico, concatenado con id como clave.
        private const val KEY_CHAT_BACKGROUND_ASSISTANT_PREFIX = "chat_background_assistant_"
        private const val KEY_CHAT_BACKGROUND_SESSION_PREFIX = "chat_background_session_"
        private val KEY_CHAT_GRADIENT = stringPreferencesKey("chat_gradient_json")
        private val KEY_MINIPHONE_ENABLED = booleanPreferencesKey("miniphone_enabled")
        private val KEY_MINI_ALBUM_HIDDEN_IMAGE_IDS = stringSetPreferencesKey("mini_album_hidden_image_ids")
        private val KEY_MINI_ALBUM_FAVORITE_IMAGE_IDS = stringSetPreferencesKey("mini_album_favorite_image_ids")
        private val KEY_NIGHT_PATROL_ENABLED = booleanPreferencesKey("night_patrol_enabled")
        private val KEY_NOTIFICATION_MEMORY_ENABLED = booleanPreferencesKey("notification_memory_enabled")
        private val KEY_MEMORY_GRAPH_CONFIG = stringPreferencesKey("memory_graph_config")

        // v1.0.20: Modo de permiso de sesión predeterminado global(TRUSTED / ASK / STRICT,Predeterminado ASK)
        private val KEY_DEFAULT_SESSION_PERMISSION_MODE = stringPreferencesKey("default_session_permission_mode")

        // v2.6.13: Los informes de fallos ya no tienen elementos de configuración de usuario.
        //
        // versión antigua crash_report_method / crash_report_email / crash_report_webhook_url tres
        // DataStore La clave ha estado fuera de línea junto con el elemento de configuración: la salida de informes se fija en [io.zer0.muse.crash.CrashReporterFactory].
        // Los datos existentes permanecen inofensivos (no se pueden leer) en el DataStore y no se borran mediante la migración.

        // R-UI-02: Se restableció el foco de la sesión — La sesión que ve actualmente el usuario se conserva por separado de la sesión que se genera.
        private val KEY_VIEWED_SESSION_ID = stringPreferencesKey("viewed_session_id")
        private val KEY_GENERATING_SESSION_ID = stringPreferencesKey("generating_session_id")

        // v1.x: Saludo Notificación de recordatorio personalizado — Fecha de última notificación(YYYY-MM-DD),Máximo una vez al día
        private val KEY_LAST_GREETING_NOTIFY_DATE = stringPreferencesKey("last_greeting_notify_date")

        // v1.x: Saludos caché de resultados de compilación de LLM(Formato "date|hint",No se repetirán llamadas el mismo día. LLM)
        private val KEY_GREETING_HINT_CACHE = stringPreferencesKey("greeting_hint_cache")
    }

    // ── v2.3: Provider Caché de prueba de conexión ───────────────────────────────────

    /** Pruebe las entradas de la caché para una única conexión. */
    @kotlinx.serialization.Serializable
    data class ConnectionTestCacheEntry(
        val result: String,
        val isSuccess: Boolean,
        val testedAt: Long = System.currentTimeMillis(),
    )

    /** Mapeo de caché de prueba de conexión(providerId → entrada de caché),TTL = 5 minutos. */
    private var connectionTestCache: Map<String, ConnectionTestCacheEntry> = emptyMap()

    /** Obtener resultados de pruebas de conexión en caché(Volver al caché si no ha caducado,De lo contrario null). */
    suspend fun getCachedConnectionTest(providerId: String): ConnectionTestCacheEntry? {
        val now = System.currentTimeMillis()
        val entry = connectionTestCache[providerId] ?: return null
        if (now - entry.testedAt > 300_000L) { // 5 minutos TTL
            connectionTestCache = connectionTestCache - providerId
            return null
        }
        return entry
    }

    /** Guarde el caché de prueba de conexión. */
    suspend fun saveConnectionTestCache(providerId: String, result: String, isSuccess: Boolean) {
        val entry = ConnectionTestCacheEntry(result = result, isSuccess = isSuccess)
        connectionTestCache = connectionTestCache + (providerId to entry)
        store.edit {
            it[KEY_CONNECTION_TEST_CACHE] =
                AppJson.encodeToString(
                    MapSerializer(String.serializer(), ConnectionTestCacheEntry.serializer()),
                    connectionTestCache,
                )
        }
    }

    /** restaurar caché(init llamado cuando). */
    private suspend fun restoreConnectionTestCache() {
        val cached = store.data.first()[KEY_CONNECTION_TEST_CACHE]
        if (cached != null) {
            connectionTestCache =
                runCatching {
                    AppJson.decodeFromString(
                        MapSerializer(String.serializer(), ConnectionTestCacheEntry.serializer()),
                        cached,
                    )
                }.getOrDefault(emptyMap())
        }
    }

    // ── v2.3: Enrutamiento del modelo de tarea ─────────────────────────────────────────

    /** Enumeración del tipo de tarea. */
    enum class TaskType(val displayName: String, val description: String) {
        CHAT("charlar", "Conversaciones diarias y preguntas y respuestas rápidas"),
        REASONING("razonamiento", "Razonamiento lógico complejo y matemáticas."),
        CODE("Programación", "Generación y depuración de código."),
        CREATIVE("crear", "Escritura y generación de ideas"),
        ANALYSIS("análisis", "Análisis de datos e informes."),
    }

    /** Configuración de enrutamiento de tareas——Cada tipo de tarea está vinculada a un modelo predeterminado,e interruptor principal. */
    @kotlinx.serialization.Serializable
    data class TaskRoutingConfig(
        val enabled: Boolean = false,
        val chatModelId: String? = null,
        val reasoningModelId: String? = null,
        val codeModelId: String? = null,
        val creativeModelId: String? = null,
        val analysisModelId: String? = null,
        /** El modelo para cada tipo de tarea pertenece a Provider,Evite errores de enrutamiento con la misma identificación de modelo entre proveedores. */
        val chatProviderId: String? = null,
        val reasoningProviderId: String? = null,
        val codeProviderId: String? = null,
        val creativeProviderId: String? = null,
        val analysisProviderId: String? = null,
    )

    /** Selección final de enrutamiento de tareas,Llevar tanto modelo como proveedor. */
    data class TaskRouteSelection(
        val modelId: String?,
        val providerId: String?,
    )

    /** Detecta automáticamente el tipo de tarea según el texto ingresado. */
    fun detectTaskType(input: String): TaskType {
        val text = input.trim().lowercase()
        return when {
            // El código tiene prioridad sobre la creación:"Escribir código/"Escribir una función" no se puede startsWith("escribir") Mal juzgado como CREATIVO.
            text.startsWith("escribir codigo") || text.startsWith("Programación") || text.startsWith("darse cuenta") ||
                text.startsWith("debug") || text.startsWith("Depuración") || text.startsWith("función") ||
                text.contains("código") || text.contains("bug") || text.contains("algoritmo") -> TaskType.CODE
            text.startsWith("análisis") || text.startsWith("Resumen") || text.startsWith("Estadísticas") ||
                text.startsWith("comparar") || text.startsWith("Contraste") || text.startsWith("informe") ||
                text.contains("análisis de datos") -> TaskType.ANALYSIS
            text.startsWith("por qué") || text.startsWith("como") || text.startsWith("razonamiento") ||
                text.startsWith("probar") || text.startsWith("Derivación") || text.startsWith("explicar") ||
                text.contains("lógica") || text.contains("Matemáticas") || text.contains("Razón") -> TaskType.REASONING
            text.startsWith("escribir") || text.startsWith("crear") || text.startsWith("componer poesía") ||
                text.startsWith("poesía") || text.startsWith("historia") || text.startsWith("novela") ||
                text.contains("escribe uno") || text.contains("Creación uno") -> TaskType.CREATIVE
            else -> TaskType.CHAT
        }
    }

    /** Recomendar modelos según el tipo de tarea id(Devuelve el modelo vinculante cuando el enrutamiento está habilitado,De lo contrario null). no suspend,Basado en memoria caché. */
    fun recommendModelForTask(input: String, fallbackModelId: String?): String? {
        return recommendTaskRoute(input, fallbackModelId, null)?.modelId ?: fallbackModelId
    }

    /**
     * Detecta el tipo de tarea según la entrada y devuelve la selección de Modelo + Proveedor.
     * Devuelto cuando el tipo correspondiente no está configurado null,La persona que llama continúa usando la sesión actual./Selección global.
     */
    fun recommendTaskRoute(input: String, fallbackModelId: String?, fallbackProviderId: String?): TaskRouteSelection? {
        val config = taskRoutingConfigCache
        if (!config.enabled) return null
        val type = detectTaskType(input)
        val selection =
            when (type) {
                TaskType.CHAT -> TaskRouteSelection(config.chatModelId, config.chatProviderId)
                TaskType.REASONING -> TaskRouteSelection(config.reasoningModelId, config.reasoningProviderId)
                TaskType.CODE -> TaskRouteSelection(config.codeModelId, config.codeProviderId)
                TaskType.CREATIVE -> TaskRouteSelection(config.creativeModelId, config.creativeProviderId)
                TaskType.ANALYSIS -> TaskRouteSelection(config.analysisModelId, config.analysisProviderId)
            }
        if (selection.modelId.isNullOrBlank() && selection.providerId.isNullOrBlank()) return null
        return TaskRouteSelection(
            modelId = selection.modelId ?: fallbackModelId,
            providerId = selection.providerId ?: fallbackProviderId,
        )
    }
}

/** v2.x: Rango de frecuencia de envío de emoticonos(Marcar enlace;off El archivo se expresa mediante la etiqueta del interruptor maestroEnabled). */
const val STICKER_FREQ_OCCASIONALLY = "occasionally"
const val STICKER_FREQ_NORMAL = "normal"
const val STICKER_FREQ_FREQUENT = "frequent"
