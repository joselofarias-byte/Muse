package io.zer0.muse

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.boot.BootReceiver
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.miniphone.work.MiniPhoneMomentScheduler
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.quicknote.QuickNoteStore
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.WebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Entrada de la aplicación. inicialización Koin,Cargar todos los módulos,Iniciar el ticker de memoria.
 *
 * Phase 8.2: Asegúrese de que el Asistente predeterminado esté presente al inicio(Instalación por primera vez/Actualización y migración de usuarios antiguos.).
 * Phase 8.10: Instale CrashHandler global + cree canales de notificación.
 * Phase 8.11: Si el servidor web está configurado como enabled,Iniciar automáticamente.
 * Phase 11.1.6: darse cuenta [ImageLoaderFactory] Registrar códec SVG + GIF
 *   (coil-svg / coil-gif Las dependencias deben registrarse con ImageLoader.components para que surtan efecto.).
 */
class MuseApp : Application(), ImageLoaderFactory {
    private val memoryTicker: MemoryTicker by inject()
    private val memoryBackfillMigration: io.zer0.muse.data.MemoryBackfillMigration by inject()
    private val notificationMemoryScheduler: io.zer0.muse.data.NotificationMemoryScheduler by inject()
    private val memoryHousekeepingScheduler: io.zer0.muse.data.MemoryHousekeepingScheduler by inject()

    // v1.0.52 P2-2: almacén de espacio de memoria,Asegúrese de que el espacio predeterminado exista al inicio
    private val memorySpaceRepository: io.zer0.memory.space.MemorySpaceRepository by inject()
    private val subagentThreadStore: io.zer0.muse.data.subagent.SubagentThreadStore by inject()

    private val assistantRepository: AssistantRepository by inject()
    private val skillRepository: SkillRepository by inject()
    private val builtInKnowledgeDocSeeder: io.zer0.muse.data.knowledge.BuiltInKnowledgeDocSeeder by inject()
    private val notificationManager: MuseNotificationManager by inject()
    private val webServer: WebServer by inject()
    private val settings: SettingsRepository by inject()
    private val scheduledTaskRunner: io.zer0.muse.schedule.ScheduledTaskRunner by inject()
    private val channelAutoReply: io.zer0.muse.channel.ChannelAutoReply by inject()
    private val weClawReceiver: io.zer0.muse.channel.WeClawReceiver by inject()
    private val telegramReceiver: io.zer0.muse.channel.TelegramReceiver by inject()
    private val dingtalkReceiver: io.zer0.muse.channel.DingtalkReceiver by inject()
    private val qqReceiver: io.zer0.muse.channel.QqReceiver by inject()
    private val feishuReceiver: io.zer0.muse.channel.FeishuReceiver by inject()
    /** v2.6.9: Programador de versiones de reconstrucción de mensajes activos (relacionado con el modelo de libro mayor). */
    private val proactiveScheduler: io.zer0.muse.proactive.ProactiveScheduler by inject()
    private val modelCatalogRepository: io.zer0.muse.data.catalog.ModelCatalogRepository by inject()

    /** B14-4: Directorio de modelo local (integrado + usuario + remoto de tres capas)—— La capa remota está desactivada de forma predeterminada y solo se extrae cuando se pasa una URL en el ensamblaje. */
    private val modelCatalogStore: io.zer0.muse.data.preset.ModelCatalogStore by inject()

    // v1.98: Programador de carga programada automática de respaldo en la nube
    private val cloudBackupScheduler: io.zer0.muse.schedule.CloudBackupScheduler by inject()
    private val ttsManager: TtsManager by inject()
    private val webSearchService: WebSearchService by inject()

    /** v1.133: GitHub Release Notificador de actualización(Comprobación asincrónica después del inicio de la aplicación). */
    private val updateNotifier: io.zer0.muse.update.UpdateNotifier by inject()

    /** P2-4: Registrador de auditoría(Borrar registros caducados al iniciar). */
    private val auditLogger: io.zer0.muse.data.audit.AuditLogger by inject()

    /** v1.92: ChatViewModel Para un solo caso,onCleared nunca llames,Los recursos deben liberarse manualmente en ON_STOP. */
    private val chatViewModel: ChatViewModel by inject()

    /** P1-1: Hook Registro — Se llama a AppLifecycleHook durante los eventos del ciclo de vida de la aplicación. */
    private val hookRegistry: io.zer0.muse.hook.HookRegistry by inject()
    private val pluginManager: io.zer0.muse.data.plugin.PluginManager by inject()

    /** P1-2: Worldbook almacén — Registre WorldBookHook al inicio. */
    private val worldBookRepository: io.zer0.muse.worldbook.WorldBookRepository by inject()

    /** v1.0.12: RAG servicio — Cargar asincrónicamente el índice HNSW persistente al inicio(Si el pedido ha sido realizado). */
    private val ragService: io.zer0.muse.rag.RagService by inject()

    /**
     * v1.0.47: Ensamblador de avisos del sistema — Calentamiento asincrónico al inicio buildStaticSnapshot,
     * dejar DataStore(chatPreferences/userProfile) + buildToolManifestSection Carga anticipada de subcaché,
     * Construya el aviso del sistema cuando el usuario ingresa a la sesión por primera vez desde ~690ms hasta ~200ms.
     */
    private val systemPromptAssembler: io.zer0.muse.transformer.SystemPromptAssembler by inject()

    /** v1.0.17: Registre rápidamente la migración de salas — Inyectar almacenamiento JSON antiguo + Room DAO,Migración única al inicio. */
    private val quickNoteStore: QuickNoteStore by inject()
    private val quickNoteDao: QuickNoteDao by inject()

    // B5-01: Restaurar la generación de interrupciones eliminadas al inicio(Generar punto de control)
    private val sessionRepository: io.zer0.muse.data.session.SessionRepository by inject()

    // B5-02: Restaurar el libro de contabilidad generado por un chat grupal que fue interrumpido por una eliminación forzada al inicio
    private val groupChatScheduler: io.zer0.muse.schedule.GroupChatScheduler by inject()
    private val chatGenerationManager: io.zer0.muse.schedule.ChatGenerationManager by inject()

    /** Realizar una copia de seguridad y restaurar el libro mayor; al comenzar, verifique si la última parada fue en medio de la restauración de almacenamiento cruzado. */
    private val restoreJournal: io.zer0.muse.backup.RestoreJournal by inject()

    /** Servicios de restauración; priorizar la reversión de la recuperación de almacenamiento cruzado inconclusa al inicio. */
    private val backupService: io.zer0.muse.backup.BackupService by inject()

    // Guía de inicio del registrador de herramientas:Forzar la creación de instancias de todos lazy single,Ponga a disposición más de 100 herramientas
    private val toolRegistrarBootstrapper: io.zer0.muse.tools.ToolRegistrarBootstrapper by inject()

    // MCP Se debe crear una instancia del registro cuando se inicia la aplicación.,De lo contrario, solo se conectarán los procesos que hayan abierto la página de configuración de MCP. server,
    // Al ingresar a la página principal de chat por primera vez, no puede ver el registro dinámico. mcp_* Herramientas.
    private val mcpRegistry: io.zer0.muse.mcp.McpRegistry by inject()

    /** Alcance a nivel de aplicación: se utiliza para iniciar tareas únicas,Alcance IO registrado independientemente de Koin. */
    // v0.53: agregar GlobalCoroutineExceptionHandler,Evite que las excepciones no detectadas en las rutinas causen fallas en la aplicación(Tolerancia a fallos a nivel empresarial)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)

    /** MainActivity waits for an interrupted restore rollback before creating DB-backed screens. */
    private val _startupReady = MutableStateFlow(false)
    val startupReady: StateFlow<Boolean> = _startupReady.asStateFlow()

    /** El mismo proceso solo activa la inspección app_resume una vez para evitar llamadas repetidas al LLM de toma de decisiones para la reorganización de HomeScreen. */
    private val resumePatrolTriggered = AtomicBoolean(false)

    /** v0.32: keepAwake se mantiene cuando se activa la configuración PARTIAL_WAKE_LOCK,null Indica no retenido. */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        // Phase 8.10: CrashHandler Debe instalarse primero(antes de comenzarKoin,Evite fallas en la inicialización de Koin y capturas perdidas)
        MuseCrashHandler.install(this)
        // v2.5.3 (P1-2): Exponer el contexto de la aplicación a DataStore corrompe la lógica de respaldo,
        // Habilita una copia de seguridad de la escena antes de reconstruirla si el archivo muse_settings está dañado.
        io.zer0.muse.data.SettingsStoreContextHolder.applicationContext = applicationContext
        // Phase 11.3: registro de archivos(Conveniente para la recuperación después de una verificación mecánica real,cacheDir Desinstalar la limpieza automática)
        Logger.initFileLog(this)
        val displayMetrics = resources.displayMetrics
        val configuration = resources.configuration
        Logger.i(
            "MuseApp",
            "device=${Build.MANUFACTURER}/${Build.MODEL}, api=${Build.VERSION.SDK_INT}, " +
                "display=${displayMetrics.widthPixels}x${displayMetrics.heightPixels}, " +
                "densityDpi=${displayMetrics.densityDpi}, density=${displayMetrics.density}, " +
                "scaledDensity=${displayMetrics.scaledDensity}, fontScale=${configuration.fontScale}, " +
                "windowDp=${configuration.screenWidthDp}x${configuration.screenHeightDp}, " +
                "smallestWidthDp=${configuration.smallestScreenWidthDp}",
        )
        // Almacenamiento de memoria de registro de depuración + registro de receptor de registrador(DebugScreen Reciba todos los registros a través de este receptor)
        io.zer0.muse.debug.DebugLogStore.init(this)
        Logger.sink = { level, tag, msg, t ->
            io.zer0.muse.debug.DebugLogStore.log(level, tag, msg, t)
        }
        Logger.i("MuseApp", "onCreate: muse empezar")
        // H1: Safe Mode comprobar — Omitir todos los inicios de servicios después del último bloqueo,Conserve únicamente CrashHandler + Logger.
        // MainActivity.onCreate Lo comprobaré de nuevo y lo mostraré. SafeModeScreen,Vuelve con antelación aquí para evitar
        // Koin/Background Runner vuelve a provocar el mismo bloqueo,haciendo imposible que los usuarios lleguen SafeModeScreen
        if (MuseCrashHandler.checkSafeMode(this)) {
            Logger.w("MuseApp", "Safe mode active — Omitir la inicialización del servicio")
            // v2.6.0: El modo seguro también instala el seguimiento de actividad(OAuth Aún disponible)
            io.zer0.muse.ui.common.CurrentActivityTracker.install(this)
            super.onCreate()
            return
        }
        super.onCreate()
        // v2.6.0: Instalar seguimiento de actividad a nivel de aplicación —— OAuth Auth Code Flow Necesito ser verdad Activity
        // Iniciar navegador Intent,Debajo de algunos contenedores de redacción findActivity() Puede que no entienda el contexto.
        io.zer0.muse.ui.common.CurrentActivityTracker.install(this)
        // StrictMode:depurar solo construir,Detectar lectura y escritura del disco del hilo principal/Violación de acceso a la red y penalización.
        // Debe habilitarse antes de la inicialización del servicio.,para detectar violaciones durante la inicialización de Koin/Runner;release Desactívelo para evitar una sobrecarga de rendimiento.
        if (BuildConfig.DEBUG) {
            configureStrictMode()
        }
        // v1.91-hotfix: startKoin paquete resultOf,Marque el Modo seguro en caso de falla y omita la inicialización del servicio.
        // Si startKoin lanza una excepción(Error al cargar el módulo/Dependencias circulares, etc.)y no ser capturado,Chocará directamente;
        // Lo peor es que GlobalContext puede estar en un estado no registrado.,Seguimiento MainActivity by inject() voluntad
        // colapsar "KoinApplication has not been started" — Ese fue el segundo colapso.,La verdadera razón quedó oculta.
        // Aquí captura el marcado de la publicación. Safe Mode,La próxima vez que se inicie MainActivity, tomará la ruta SafeModeScreen.
        val koinResult =
            resultOf {
                startKoin {
                    androidContext(this@MuseApp)
                    modules(allKoinModules)
                }
            }
        if (koinResult.getOrNull() == null) {
            koinResult.onError { msg, t ->
                Logger.e("MuseApp", "startKoin falló — Marcar Modo seguro y omitir la inicialización del servicio: $msg", t)
            }
            MuseCrashHandler.markSafeMode(this)
            return
        }
        val incompleteRestore = restoreJournal.incompleteEntry()
        val initializeRuntime: MuseApp.() -> Unit = {
            // Fuerce la creación de instancias de todos los registros de herramientas, haciendo que más de 100 herramientas estén disponibles al inicio
            toolRegistrarBootstrapper
            // v2.6.7: Exportación de inventario de tareas pendientes —— Anteriormente, sólo se escribía la memoria del proceso y se perdía la sesión.
            // Utilice el contenedor global para recuperar el repositorio (el registro de la herramienta se inicializa sincrónicamente, por lo que es inconveniente insertar otro parámetro de construcción).
            // Cuando no se puede recuperar, mantiene el comportamiento de memoria pura y no afecta el inicio.
            runCatching {
                val repo =
                    org.koin.core.context.GlobalContext.get()
                        .get<io.zer0.muse.data.todo.SessionTodoRepository>()
                io.zer0.muse.tools.TodoTool.installPersistence { sessionId, list ->
                    repo.save(sessionId, list)
                }
            }.onFailure { Logger.w("MuseApp", "La inyección de persistencia de todo falla y se degrada a memoria pura: ${it.message}") }
            // v1.0.80: Inicializar el módulo de automatización de la interfaz de usuario(Tres niveles de permisos: Accesibilidad/Shell/Root)
            try {
                val toolRegistry =
                    org.koin.java.KoinJavaComponent.get<io.zer0.muse.tools.ToolRegistry>(
                        io.zer0.muse.tools.ToolRegistry::class.java,
                    )
                io.zer0.muse.automation.AutomationInitializer.initialize(this, toolRegistry)
            } catch (e: Exception) {
                Logger.w("MuseApp", "AutomationInitializer failed: ${e.message}")
            }
            // MCP server La conexión y el registro de herramientas son independientes del ciclo de vida de la página de configuración.,Tanto el backend como el chat principal se pueden utilizar directamente.
            mcpRegistry.startAll()
            // La ventana flotante del sistema de grabación rápida está cerrada por defecto:Limpie el estado habilitado predeterminado que puede haber quedado con la versión anterior.
            appScope.launch {
                try {
                    settings.migrateQuickCaptureOverlayDefaultOffIfNeeded()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("MuseApp", "Registre rápidamente la falla al migrar el valor predeterminado de la ventana flotante: ${e.message}", e)
                }
            }
            appScope.launch {
                try {
                    settings.migrateUserAvatarToPrivateStorageIfNeeded()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("MuseApp", "Error en la migración del almacenamiento privado del avatar del usuario: ${e.message}", e)
                }
            }
            // v1.0.53: RAG embedding Las fuentes se migran de una sola vez. La ruta de lectura ya no reescribe la configuración.,Debe migrarse una vez al inicio,
            // De lo contrario, la configuración de la nube guardada en la versión anterior a la v1.0.53 se degradará silenciosamente a la búsqueda de palabras clave.
            appScope.launch {
                try {
                    settings.migrateRagEmbeddingSourceIfNeeded()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("MuseApp", "RAG embedding Error en la migración de origen: ${e.message}", e)
                }
            }
            // ANR Detección + Monitoreo del desempeño(Comenzó después de startKoin pero antes de la inicialización del servicio.)
            // AnrWatcher:El hilo del demonio independiente detecta que el hilo principal no responde(5s+),ANR Recoger la pila de hilos cuando/memoria/Perf Los registros se escriben en el directorio de fallos.
            //   cambiar settings.enableAnrDetection(Predeterminado true);No bloquea el hilo principal en sí.(Hilo independiente + referencia débil Handler).
            io.zer0.muse.crash.AnrWatcher(this, settings, auditLogger).start()
            // Reanudar la carga desde el punto de interrupción(Recuperación de interrupción de herramientas):Inicialice la ruta del archivo persistente para las llamadas a herramientas pendientes.
            // debe Early-init,Asegúrese de que fileRef esté listo cuando se inicie ChatViewModel.
            io.zer0.muse.chat.PendingToolCallStore.init(this)
            // P2-11: Inyecte SecureCredentialStore en el singleton de OAuthManager,
            // Después de iniciar sesión correctamente, el token de OAuth se cifrará y se escribirá de forma independiente. SP(Aislado físicamente de las claves API ordinarias)
            io.zer0.muse.auth.OAuthManager.init(io.zer0.muse.auth.SecureCredentialStore(this))
            // v2.6.0: Montaje del proveedor de proxy global —— token Intercambio/La actualización debe ser coherente con el proxy configurado del usuario.
            // De lo contrario, los usuarios proxy encontrarán“La autorización del navegador es exitosa pero el intercambio de tokens de la aplicación falla”(Conexión directa rechazada por región).
            io.zer0.muse.auth.OAuthManager.installProxyConfigProvider {
                settings.proxyConfigCache
            }
            // v2.6.9(comentarios de los usuarios): Sincronice el proxy con el ProxyProvider global.
            //
            // Antecedentes: solo 3 de los 28 puntos de construcción de OkHttpClient en todo el almacén utilizan proxies.
            // Como resultado, MCP / canal / ASR / análisis de la nube / mercado de complementos todavía están conectados directamente después de que el usuario configura un proxy.——
            // El rendimiento es“Algunas funciones pueden acceder a Internet y otras no.”.
            //
            // Sincronice una vez al comenzar aquí y continúe suscribiéndose a los cambios de configuración (el usuario no necesita reiniciar después de cambiar el proxy)
            // El cliente recién creado utilizará el nuevo proxy; el cliente existente aún debe reiniciarse, como se explica en la página de configuración).
            io.zer0.ai.core.ProxyProvider.set(settings.proxyConfigCache.toProxyDescriptor())
            appScope.launch {
                settings.proxyConfigFlow.collect { cfg ->
                    io.zer0.ai.core.ProxyProvider.set(cfg.toProxyDescriptor())
                }
            }
            // v2.6.2(Codex Solución de inicio de sesión): Autorreparación de los proveedores existentes del Codex —— versión antigua account-id
            // La ruta de análisis es incorrecta. El encabezado ChatGPT-Account-ID nunca se escribió en el proveedor del usuario que inició sesión.
            // Resultando en 403 para todas las solicitudes de inferencia del Codex. Aquí, el id_token almacenado en el cifrado se analiza y reescribe.
            // Permita que los usuarios antiguos se recuperen sin volver a iniciar sesión. Idempotente, omitido silenciosamente sin OAuth.
            appScope.launch {
                resultOf { settings.healCodexAccountHeadersIfNeeded() }
                    .onError { msg, t -> Logger.w("MuseApp", "Codex Falló la autorreparación del encabezado de la cuenta: $msg", t) }
                    .onSuccess { n -> if (n > 0) Logger.i("MuseApp", "Codex Autorreparación del encabezado de cuenta completada: Reparación $n proveedores") }
            }
            // Phase 8.10: Crear un canal de notificación(idempotente,Android 8.0+ requerido)
            notificationManager.ensureChannels()
            // Es posible que el servicio de escucha de notificaciones aún no esté vinculado al sistema;Restaurar primero el historial de notificaciones locales,Deje que la página de configuración y herramientas de notificación
            // Los datos recogidos en la ronda anterior también se pueden leer tras un arranque en frío.
            io.zer0.muse.notification.MuseNotificationListenerService.initialize(this)
            // empezar memory ticker(cada hora daily check,El principal desencadenante sigue siendo ChatViewModel.notifyTurn)
            memoryTicker.start()
            // v2.6.2(comentarios de los usuarios): Iniciar notificación → Clasificación y programación del fondo de la memoria.
            // Desactivado predeterminado (notificationMemoryEnabledFlow=false), el programador está inactivo y no ajusta LLM cuando no está encendido.
            resultOf { notificationMemoryScheduler.start() }
                .onError { msg, t -> Logger.w("MuseApp", "notificationMemoryScheduler.start falló: $msg", t) }
            // v2.6.2(auditoría de memoria): Iniciar programa de limpieza de datos asistido por memoria (memoria de chat grupal/mensaje privado del agente)
            resultOf { memoryHousekeepingScheduler.start() }
                .onError { msg, t -> Logger.w("MuseApp", "memoryHousekeepingScheduler.start falló: $msg", t) }
            // v1.0.51: Migración de memoria de stock — Iniciar la sesión del historial de copias de seguridad por primera vez después de la actualización rollingSummary
            // Tres guardias aseguran la idempotencia:DataStore Bit de bandera + AtomicBoolean + interruptor de memoria
            // Informar el progreso en tiempo real a través de backfillProgressFlow,MemoryScreen Mostrar barra de progreso en la parte superior
            appScope.launch {
                resultOf { memoryBackfillMigration.migrateIfNeeded() }
                    .onError { msg, t -> Logger.w("MuseApp", "memory backfill La migración falló: $msg", t) }
                    .onSuccess { ran -> if (ran) Logger.i("MuseApp", "memory backfill Se ha realizado la migración.") }
            }
            // v1.0.52 P2-2: Asegúrese de que exista el espacio de memoria predeterminado(Evite que las excepciones de migración de bases de datos provoquen que falte el espacio predeterminado)
            appScope.launch {
                resultOf { memorySpaceRepository.ensureDefaultSpaceExists() }
                    .onError { msg, t -> Logger.w("MuseApp", "ensureDefaultSpaceExists falló: $msg", t) }
            }
            // B5-01: Restaurar la generación de interrupciones eliminadas al inicio(Contenido producido + [interrumpido] marca)
            appScope.launch {
                resultOf { sessionRepository.recoverInterruptedGenerations() }
                    .onError { msg, t -> Logger.w("MuseApp", "Falló la generación de interrupción de recuperación: $msg", t) }
            }
            // A9: B5-01 solo manejar generation_checkpoints,Las filas colgantes en el libro mayor redondo deben converger individualmente ——
            // La última vez que se eliminó el proceso, la línea de giro no tuvo posibilidad de escribirse. finishedAt,Ni fracasar ni dejarse dominar.
            // Solo procese filas que excedan la ventana conservadora,Iniciar repetidamente idempotente,El fallo no afecta el inicio.
            appScope.launch {
                resultOf { sessionRepository.closeStaleOpenTurns() }
                    .onSuccess { if (it > 0) Logger.i("MuseApp", "Ronda de generación colgante convergente: $it Artículo") }
                    .onError { msg, t -> Logger.w("MuseApp", "La ronda de generación de suspensión de convergencia falló: $msg", t) }
            }

            // v1.0.74: Comience a limpiar el hilo del agente hijo huérfano(El hilo abierto que quedó cuando se mató el proceso. → closed,
            // evitar"Tareas de subagente en segundo plano"Visualización residual entre versiones)
            appScope.launch {
                resultOf { subagentThreadStore.cleanupOrphanThreads() }
                    .onError { msg, t -> Logger.w("MuseApp", "No se pudieron limpiar los subprocesos del agente secundario huérfano: ${t?.message ?: msg}") }
            }

            // v2.x cierre del legado: Borre de forma conservadora el directorio de medios del canal WeChat al inicio(filesDir/channel_media/),
            // Prevenir vídeos entrantes/La acumulación prolongada de archivos abruma el directorio privado(30 dia TTL + 512MB LRU).
            appScope.launch {
                resultOf {
                    // Attach stores before cleanup so media still referenced by inbox/conversation history is protected.
                    io.zer0.muse.channel.ChannelInbox.attach(this@MuseApp)
                    io.zer0.muse.channel.ChannelConversationStore.attach(this@MuseApp)
                    val protectedMedia = buildSet {
                        io.zer0.muse.channel.ChannelInbox.journal.snapshot()
                            .mapNotNullTo(this) { it.mediaPath.takeIf(String::isNotBlank) }
                        io.zer0.muse.channel.ChannelConversationStore.conversations.value.values
                            .flatMap { it.turns }
                            .mapNotNullTo(this) { it.mediaPath.takeIf(String::isNotBlank) }
                    }
                    io.zer0.muse.channel.ChannelMediaCleaner.cleanup(
                        java.io.File(filesDir, "channel_media"),
                        protectedPaths = protectedMedia,
                    )
                }.onError { msg, t -> Logger.w("MuseApp", "channel_media Error de limpieza: $msg", t) }
            }

            // B5-02: Restaurar el libro de contabilidad generado por un chat grupal que fue interrumpido por una eliminación forzada al inicio(Reanudar desde el punto de interrupción)
            appScope.launch {
                resultOf { groupChatScheduler.recoverInterruptedGenerations() }
                    .onError { msg, t -> Logger.w("MuseApp", "No se pudo restaurar el libro de chat grupal: $msg", t) }
            } // v1.92: ChatViewModel Para un solo caso,onCleared Nunca llamé.
            // Registrar observador ProcessLifecycleOwner,Libere recursos TTS/ASR y deténgase en ON_STOP memory ticker,
            // Reinicie el ticker de memoria en ON_START.
            // P2-23: Creación de instancias forzada durante el inicio ChatViewModel — Su inicio registrará herramientas multimedia.
            // (generate_image / generate_video / generate_qr_code)llegar ToolRegistry;
            // Originalmente lazy inject,Solo primer acceso al proceso UI/frontend,Iniciar flujo en segundo plano(Tareas programadas, automatización,
            // Escenario sin interfaz de usuario)No activado → Faltan herramientas multimedia. El análisis al inicio garantiza que todas las entradas estén disponibles.
            runCatching { chatViewModel.toString() }
                .onFailure { t -> Logger.w("MuseApp", "ChatViewModel Early-init falló: ${t.message}", t) }
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> {
                            Logger.i("MuseApp", "Process ON_STOP: Liberar recursos de ChatViewModel + detener MemoryTicker")
                            // v1.0.29: Si se genera al cambiar al fondo,Inicie el mantenimiento del servicio front-end. Debe ejecutarse sincrónicamente.
                            resultOf { chatViewModel.onAppBackground() }
                                .onError { msg, t -> Logger.w("MuseApp", "chatViewModel.onAppBackground falló: $msg", t) }
                            // ChatGenerationManager Inicie el servicio en primer plano inmediatamente después de registrar la tarea; No se iniciará repetidamente aquí.
                            // De esta manera, los chats individuales, los chats grupales y los planes de herramientas se mantienen vivos mediante la misma instantánea de activeGenerations.
                            resultOf { chatViewModel.releaseForBackground() }
                                .onError { msg, t -> Logger.w("MuseApp", "chatViewModel.release falló: $msg", t) }
                            // v1.0.30: memoryTicker.stop Incluyendo un tiempo de espera de 30 segundos, trasladado a la corrutina
                            appScope.launch {
                                resultOf { memoryTicker.stop() }
                                    .onError { msg, t -> Logger.w("MuseApp", "memoryTicker.stop falló: $msg", t) }
                                // P1-1: AppLifecycleHook.onAppBackground
                                resultOf {
                                    hookRegistry.executeNoResult(
                                        io.zer0.muse.hook.AppLifecycleHook::class,
                                    ) { it.onAppBackground() }
                                }
                                    .onError { msg, t -> Logger.w("MuseApp", "AppLifecycleHook.onAppBackground falló: $msg", t) }
                            }
                        }
                        Lifecycle.Event.ON_START -> {
                            // Es posible que la tarea de generación aún se ejecute y el servicio de mantenimiento de actividad no se puede detener solo porque la página vuelve al primer plano.
                            // ChatGenerationService Saldrá automáticamente después de que activeGenerations quede vacío.
                            resultOf { chatViewModel.onAppForeground() }
                                .onError { msg, t -> Logger.w("MuseApp", "chatViewModel.onAppForeground falló: $msg", t) }
                            // Reiniciar al regresar a la recepción memory ticker
                            resultOf { memoryTicker.start() }
                                .onError { msg, t -> Logger.w("MuseApp", "memoryTicker.start falló: $msg", t) }
                            if (resumePatrolTriggered.compareAndSet(false, true)) {
                                appScope.launch {
                                    resultOf {
                                        proactiveScheduler.runCycle(
                                            io.zer0.muse.proactive.ProactiveScheduler.TRIGGER_RESUME,
                                        )
                                    }.onError { msg, t -> Logger.w("MuseApp", "app_resume Falló la inspección del mensaje activo: $msg", t) }
                                }
                            }
                            // v1.0.16: Regrese a la recepción para limpiar las conexiones inactivas de OkHttp y moverlas a la rutina.
                            appScope.launch {
                                resultOf { io.zer0.ai.core.ProviderHttpSupport.evictIdleConnections() }
                                    .onError { msg, t -> Logger.w("MuseApp", "evictIdleConnections falló: $msg", t) }
                                // P1-1: AppLifecycleHook.onAppForeground
                                resultOf {
                                    hookRegistry.executeNoResult(
                                        io.zer0.muse.hook.AppLifecycleHook::class,
                                    ) { it.onAppForeground() }
                                }
                                    .onError { msg, t -> Logger.w("MuseApp", "AppLifecycleHook.onAppForeground falló: $msg", t) }
                            }
                        }
                        else -> {}
                    }
                },
            )
            // Phase 8.2: Asegúrese de que exista el Asistente predeterminado(fire-and-forget,El fallo no bloquea el inicio)
            appScope.launch {
                // v2.x(comentarios de los usuarios): Dar prioridad al uso del puntero del asistente predeterminado establecido por el usuario (después de eliminar el asistente predeterminado, apuntará a otro asistente)
                resultOf {
                    val preferred = settings.getDefaultAssistantId()
                    assistantRepository.ensureDefaultExists(preferred)
                }
                    .onError { msg, t -> Logger.w("MuseApp", "ensureDefaultExists falló", t) }
                // P1-4: Registrar restricciones de contexto de piso Hook
                resultOf {
                    hookRegistry.register(io.zer0.muse.hook.FloorContextLimiterHook(settings))
                }.onError { msg, t -> Logger.w("MuseApp", "FloorContextLimiterHook Registro fallido: $msg", t) }
                // P1-2: Registrarse Inyección de aviso dinámico de Worldbook Hook(Residente + activador de palabra clave + inyección profunda)
                resultOf {
                    hookRegistry.register(io.zer0.muse.worldbook.WorldBookHook(worldBookRepository))
                }.onError { msg, t -> Logger.w("MuseApp", "WorldBookHook Registro fallido: $msg", t) }
                // v2.x(Sistema de gancho enchufable MVP): Cuelgue el enlace Prompt_finalize declarado por el complemento habilitado en el registro.
                resultOf {
                    io.zer0.muse.data.plugin.PluginHookBridge(
                        context = applicationContext,
                        pluginManager = pluginManager,
                        hookRegistry = hookRegistry,
                        appScope = appScope,
                        settings = settings,
                    ).start()
                }.onError { msg, t -> Logger.w("MuseApp", "PluginHookBridge El inicio falló: $msg", t) }
                // La activación del complemento evalúa el script del complemento al inicio, mientras que JsSandbox originalmente solo se iniciaba cuando se llamaba la herramienta.
        // Como resultado, todos los complementos con entradas JS deben informar "JsSandbox No inicializado".
        // Prepare la zona de pruebas antes de enviar el evento de activación (init idempotente).
        resultOf { io.zer0.muse.tools.JsSandbox.init(applicationContext) }
            .onError { msg, t -> Logger.w("MuseApp", "JsSandbox init falló: $msg", t) }
        // v2.6.13: Evento de activación del complemento (activationEvents) distribución.
                // Anteriormente, este campo sólo se mostraba y nunca se enviaba. Incluso si el autor escribiera en Startup, no se llamaría.
                resultOf {
                    io.zer0.muse.data.plugin.PluginActivationBridge(
                        context = applicationContext,
                        pluginManager = pluginManager,
                        appScope = appScope,
                        settings = settings,
                    ).start()
                }.onError { msg, t -> Logger.w("MuseApp", "PluginActivationBridge El inicio falló: $msg", t) }
                // v2.x(Artículo de exportación de diagnóstico A): Generar inicialización del registrador de seguimiento de enlaces (observador puro, deglución interna anormal)
                resultOf { io.zer0.muse.diagnostic.GenerationTrace.init(applicationContext) }
                    .onError { msg, t -> Logger.w("MuseApp", "GenerationTrace init falló: $msg", t) }
                // v2.x Agent ización: Registrar perfil de capacidad del dispositivo Hook —— Haga que el canal de control de tres capas sea realmente utilizable +
                // Inyección del procedimiento de decisión operativa system prompt,Deje que el modelo elija el canal correcto en el primer paso.,En lugar de depender del ensayo y error.
                resultOf {
                    if (io.zer0.muse.automation.AutomationInitializer.isInitialized) {
                        hookRegistry.register(
                            io.zer0.muse.automation.capability.DeviceCapabilityHook(
                                automationManager = io.zer0.muse.automation.AutomationInitializer.manager,
                                // La puerta de permiso de la pantalla virtual tiene el mismo origen que ShellExecutor.:Puedes crear una pantalla con Shizuku o Root
                                // (Ver el mismo juicio de VirtualDisplayServerManager.channelState).
                                virtualDisplayProbe = {
                                    val st = io.zer0.muse.automation.AutomationInitializer.manager.permissionState.value
                                    st.shellEnabled || st.rootEnabled
                                },
                                // Nota:Esto está dentro de appScope.launch,`this` si CoroutineScope,
                                // El contexto de la aplicación debe pasarse explícitamente.
                                nodeRuntimeProbe = {
                                    io.zer0.muse.runtime.MuseRuntime.isReady(applicationContext)
                                },
                            ),
                        )
                    }
                }.onError { msg, t -> Logger.w("MuseApp", "DeviceCapabilityHook Registro fallido: $msg", t) }
            }
            // Phase 8.8: Inicializar incorporado Skills(Si falta, inserte;El campo Actualizar sólo definición ya existe,Mantener el inicio y la parada del usuario)
            // P0-11: No hay necesidad upsert(REPLACE)——Evite el uso de semillas habilitadas para anular la selección cerrada del usuario.
            // v2.x: cambiar seedOrRefreshBuiltIn——Pure IGNORE hará que el antiguo esquema de la biblioteca nunca se actualice
            // (El modelo no puede ver la adición de habilidades integradas./Parámetros modificados,Como por ejemplo knowledge_search.threshold).
            appScope.launch {
                resultOf {
                    SkillExecutor.BUILT_IN_SKILLS.forEach { skillRepository.seedOrRefreshBuiltIn(it) }
                }.onError { msg, t -> Logger.w("MuseApp", "Error al inicializar las habilidades integradas", t) }
            }
            // v0.43: seed Documentación de desarrollo integrada en la base de conocimientos.(Usar estable id,Al actualizar, el contenido se actualiza pero no se repite.;fileType="devdoc" para filtrado de UI)
            // v2.4.x: seed agregar índice de vector de documento interno(incremental, fondo;embedding Mantener las palabras clave cuando no estén disponibles)
            appScope.launch {
                resultOf { seedDevDocs() }
                    .onError { msg, t -> Logger.w("MuseApp", "seedDevDocs falló", t) }
                resultOf { indexDevDocs() }
                    .onError { msg, t -> Logger.w("MuseApp", "indexDevDocs falló", t) }
            }
            // v1.0.17: registro rápido JSON → Room Migración única
            // Se garantiza que se ejecutará solo una vez a través del indicador SharedPreferences quick_notes_migrad:
            //  - Usuario de actualización por primera vez:logotipo false → Migrar → conjunto true
            //  - Migrado/Nuevo usuario:Marcar verdadero o JSON sin datos → Corriendo atrás true
            // fire-and-forget,El fallo no bloquea el inicio(Inténtalo de nuevo la próxima vez,upsert REPLACE idempotente)
            appScope.launch {
                resultOf { migrateQuickNotesIfNeeded() }
                    .onError { msg, t -> Logger.w("MuseApp", "Registre rápidamente los errores de migración: ${t?.message ?: msg}", t) }
            }
            // v1.0.12: Carga asincrónicamente el índice HNSW desde filesDir/rag/hnsw_index.bin al inicio
            // (startKoin Después de eso,RagService Antes del primer uso). Cuando falla la carga, se realiza un recorrido violento por primera vez para recuperarlo.
            // (compatible con versiones anteriores,<5000 chunk La biblioteca atravesará un violento recorrido),El índice se acumulará en el siguiente indexDocument
            // SAVE_INTERVAL Reconstruya y guarde automáticamente después de un fragmento.fire-and-forget,Empieza sin bloquear.
            appScope.launch {
                resultOf { ragService.loadVectorIndexIfNeeded(settings.getRagConfig()) }
                    .onError { msg, t -> Logger.w("MuseApp", "HNSW Error al cargar el índice: $msg", t) }
            }
            // v1.0.14 P0-1: Suscríbete ChatPreferences.hapticFeedback,Sincronización con el interruptor maestro MuseHaptics.
            // Usuario en "Configuración" → comportamiento de chat → Después de cambiar "retroalimentación háptica",todos MuseHaptics.light/medium/heavy/soft
            // La convocatoria entra en vigor inmediatamente.(No es necesario reiniciar).MuseHaptics.enabled si @Volatile,UI No hay riesgo de concurrencia en la lectura de hilos.
            appScope.launch {
                settings.chatPreferencesFlow.collect { prefs ->
                    io.zer0.muse.ui.theme.MuseHaptics.setEnabled(prefs.hapticFeedback)
                }
            }
            // Phase 8.11: Si el servidor web está habilitado,inicio automático(fire-and-forget,El fallo no bloquea el inicio)
            appScope.launch {
                resultOf {
                    if (settings.webServerConfigFlow.first().enabled) {
                        val started = webServer.start()
                        if (!started) {
                            Logger.w(
                                "MuseApp",
                                "WebServer Inicio no completado: ${webServer.lastError.value ?: "unknown error"}",
                            )
                        }
                    }
                }.onError { msg, t -> Logger.w("MuseApp", "WebServer El inicio falló: $msg", t) }
            }
            // v0.23: Iniciar sondeo de tareas programadas(Corrutina de fondo,Verifique las tareas vencidas cada 60 segundos y notifique)
            // Fallo real solucionado en v0.22(SettingsRepository init orden de bloque),Seguro para recuperar Runner
            // M1: Para uso unificado resultOf{} sustituto runCatching{}(Convención de resultados del proyecto)
            resultOf { scheduledTaskRunner.start() }
                .onError { msg, t -> Logger.w("MuseApp", "ScheduledTaskRunner El inicio falló", t) }
            // v2.6.9: Encendido/Después de reiniciar el proceso, se restaura el mantenimiento de vida residente (se restaura solo después de que el usuario lo haya abierto)
            // Nota: Existen restricciones para iniciar servicios en primer plano desde segundo plano en Android 12+ y una falla no afectará el proceso principal.
            resultOf {
                appScope.launch {
                    if (settings.keepAliveEnabledFlow.first()) {
                        io.zer0.muse.schedule.KeepAliveService.start(this@MuseApp)
                        Logger.i("MuseApp", "Se ha restablecido el servicio de mantenimiento de vida para residentes.")
                    }
                }
            }.onError { msg, t -> Logger.w("MuseApp", "No se pudo restaurar el mantenimiento del residente: $msg", t) }
            // v2.0: Monitoreo de respuesta automática del canal(webhook entrante → Ejecutar automáticamente una ronda → Devolución de datos a la fuente)
            resultOf { channelAutoReply.start() }
                .onError { msg, t -> Logger.w("MuseApp", "ChannelAutoReply El inicio falló", t) }
            // v2.0: ClawBot recibir sondeo largo — El bucle de recepción solo comienza si hay un canal WECLAW activo.
            // Determinar si leer la configuración persistente de ChannelManager(Vacío al inicio StateFlow),Así que ponlo en
            // Al iniciar la rutina, actualice primero y luego juzgue;Adiciones y eliminaciones de canales más adelante/El inicio y la parada son seguidos por el propio receptor.
            appScope.launch {
                resultOf { weClawReceiver.startForEnabledChannels() }
                    .onError { msg, t -> Logger.w("MuseApp", "WeClawReceiver El inicio falló", t) }
            }
            // v2.0: Telegram recibir sondeo largo
            resultOf { telegramReceiver.restart() }
                .onError { msg, t -> Logger.w("MuseApp", "TelegramReceiver El inicio falló", t) }
            // v2.0: Recepción de DingTalk Stream
            resultOf { dingtalkReceiver.restart() }
                .onError { msg, t -> Logger.w("MuseApp", "DingtalkReceiver El inicio falló", t) }
            // v2.0.1: QQ Recepción del Robot WebSocket Gateway(Red pública gratuita)
            resultOf { qqReceiver.restart() }
                .onError { msg, t -> Logger.w("MuseApp", "QqReceiver El inicio falló", t) }
            // v2.0.1: Recepción de conexión larga Feishu(Red pública gratuita)
            resultOf { feishuReceiver.restart() }
                .onError { msg, t -> Logger.w("MuseApp", "FeishuReceiver El inicio falló", t) }
            // v1.104 P3: WorkManager Ten todo en cuenta — App Después de ser eliminado, el sistema lo activará cada 15 minutos para ejecutar la tarea programada.
            // KEEP Estrategia:Si ya existe, conserva el antiguo. schedule(Evite el registro duplicado)
            // Sin restricciones de red/aceleradas establecidas:conformarse a"Ahorra energía"objetivo,Cuando no hay red, la ejecución de tareas se registra internamente. failed
            resultOf {
                val request =
                    PeriodicWorkRequestBuilder<io.zer0.muse.schedule.ScheduledTaskWorker>(
                        15,
                        TimeUnit.MINUTES,
                    ).setConstraints(Constraints.Builder().build()).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    io.zer0.muse.schedule.ScheduledTaskWorker.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onError { msg, t -> Logger.w("MuseApp", "ScheduledTaskWorker Registro fallido", t) }
            // v2.6.9: Reconstrucción proactiva de mensajes — Concéntrese en el controlador del libro mayor y ya no realice encuestas en un tic fijo.
            // Si hay algo en la cuenta, se revisará una vez cada 20 minutos, y si no pasa nada, se revisará una vez cada hora; cuando no haya nada que decir, toda la ronda se realizará localmente.
            registerProactiveDismissReceiver()
            resultOf { proactiveScheduler.start() }
                .onError { msg, t -> Logger.w("MuseApp", "ProactiveScheduler El inicio falló", t) }
            // Catálogo de capacidades de modelos:Inyectar primero el directorio existente(caché/Incorporado)Disponibilidad inmediata garantizada,Actualizar silenciosamente de nuevo;
            // Reinyectar después de una actualización exitosa(Si la extracción falla, el caché existente nunca se tocará.).
            appScope.launch {
                io.zer0.ai.registry.ModelRegistry.installCatalog(modelCatalogRepository.current())
                resultOf { modelCatalogRepository.refresh() }
                    .onSuccess { io.zer0.ai.registry.ModelRegistry.installCatalog(modelCatalogRepository.current()) }
                    .onError { msg, t -> Logger.w("MuseApp", "Error al actualizar el catálogo de modelos", t) }
                // B14-4: directorio de modelo local (ModelCatalogStore) capa remota.
                // Desactivado de forma predeterminada:remoteUrl Cuando está vacío, refrescoRemote regresa inmediatamente sin enviar ninguna solicitud;
                // No se puede recurrir silenciosamente a la caché existente/Directorio incorporado (solo registro).
                resultOf { modelCatalogStore.refreshRemote() }
                    .onSuccess { r ->
                        if (r.updated) Logger.i("MuseApp", "Capa remota del directorio de modelo local: ${r.message}")
                    }
                    .onError { msg, t -> Logger.w("MuseApp", "Error al actualizar la capa remota del directorio del modelo local.", t) }
            }
            // El antiguo calendario del círculo de amigos ha estado fuera de línea: el Runner en proceso ha sido eliminado.periodic Worker Me registré originalmente en KEEP.
            // Simplemente eliminar el código no cancelará las tareas en cola y el dispositivo actualizado debe cancelarse explícitamente una vez.
            resultOf { WorkManager.getInstance(this).cancelUniqueWork(LEGACY_MOMENT_WORK_NAME) }
                .onError { msg, t -> Logger.w("MuseApp", "No se pudo cancelar el círculo de viejos amigos Trabajador: ${t?.message ?: msg}", t) }
            // P3: Publicar automáticamente en Momentos en segundo plano en un teléfono celular pequeño — Despierta cada 3 horas,cuatro juicios(periodo de tranquilidad/90 minuto antivibración/probabilidad/¿Hay un papel?)
            // Todo dentro del módulo :miniphone;KEEP Estrategia:Si ya existe, mantenga el horario anterior.,Cuando el interruptor está apagado, el Trabajador permanece inactivo y sale de cada ronda.
            resultOf { MiniPhoneMomentScheduler.start(this) }
                .onError { msg, t -> Logger.w("MuseApp", "MiniPhoneMomentScheduler Registro fallido: ${t?.message ?: msg}", t) }
            // v1.134 P0-1: Mensaje proactivo WorkManager te dice la verdad — App Después de ser asesinado, el sistema realizará una verificación cada 15 minutos.
            // KEEP Estrategia:Si ya existe, conserva el antiguo. schedule(Evite el registro duplicado);Alineado con ScheduledTaskWorker
            // P2-2: Worker Camino con arranque en frío anti-disturbios.(Solo se actualiza cuando hace mucho tiempo que no se activa lastTriggeredAt,No enviar inmediatamente)
            resultOf {
                // La antigua versión Runner de Worker ha estado fuera de línea. Fue registrado originalmente con la póliza KEEP,
                // Simplemente eliminar el código no cancelará las tareas periódicas en cola, debe cancelarlas explícitamente.
                // De lo contrario, después de la actualización, los dos conjuntos de programaciones enviarán mensajes activos al mismo tiempo.
                // Tecla de tarea periódica para el antiguo planificador. Fue registrado originalmente con la póliza KEEP,
                // Simplemente eliminar el código no cancelará las tareas en cola —— El dispositivo actualizado debe cancelarse explícitamente una vez.
                // De lo contrario, las tareas antiguas seguirán obligando a los usuarios a realizar inspecciones inútiles.
                WorkManager.getInstance(this).cancelUniqueWork(LEGACY_PROACTIVE_WORK_NAME)
                val request =
                    PeriodicWorkRequestBuilder<io.zer0.muse.proactive.ProactiveWorker>(
                        15,
                        TimeUnit.MINUTES,
                    ).setConstraints(Constraints.Builder().build()).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    io.zer0.muse.proactive.ProactiveWorker.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onError { msg, t -> Logger.w("MuseApp", "ProactiveWorker Registro fallido", t) }
            // v1.0.84: resumen diario — 09:00 / 12:00 / 21:00 / 00:00 Cuatro puntos de tiempo independientes.
            // WorkManager Responsable de la seguridad del proceso después de su finalización, el desencadenante en proceso es responsable de la entrega a tiempo cuando el proceso está activo.
            // B-10: scheduleNext Necesita leer el período de configuración.(DataStore),Poner appScope para ejecutar de forma asincrónica
            appScope.launch(io.zer0.muse.util.GlobalCoroutineExceptionHandler) {
                try {
                    io.zer0.muse.schedule.DailySummaryWorker.scheduleNext(this@MuseApp)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w("MuseApp", "DailySummaryWorker La programación falló: ${e.message}", e)
                }
            }
            resultOf {
                io.zer0.muse.schedule.DailySummaryWorker.startInProcess(this, appScope)
            }.onError { msg, t -> Logger.w("MuseApp", "DailySummary Error en la programación en proceso", t) }
            // v1.98: La copia de seguridad en la nube se carga automáticamente con regularidad(Compruebe la caducidad cada 10 minutos.)
            resultOf { cloudBackupScheduler.start() }
                .onError { msg, t -> Logger.w("MuseApp", "CloudBackupScheduler El inicio falló", t) }
            // v1.132: Secretos de WorkManager de copia de seguridad en la nube — App Después de ser eliminado, el sistema lo abrirá cada 15 minutos para verificar la copia de seguridad vencida.
            // KEEP Estrategia:Si ya existe, conserva el antiguo. schedule(Evite el registro duplicado);Alineado con ScheduledTaskWorker
            resultOf { cloudBackupScheduler.registerWorkManagerFallback(this) }
                .onError { msg, t -> Logger.w("MuseApp", "CloudBackupWorker Registro fallido", t) }
            // v1.134 P1-1: Copia de seguridad local automática Worker — 1 punto de control WAL por día + copiar muse.db a backups/
            // Acceda a componentes huérfanos originales v1.107 AutoBackupHelper,App Detenido por WorkManager después de ser asesinado
            resultOf {
                val request =
                    PeriodicWorkRequestBuilder<io.zer0.muse.schedule.AutoBackupWorker>(
                        1,
                        TimeUnit.DAYS,
                    ).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    io.zer0.muse.schedule.AutoBackupWorker.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onError { msg, t -> Logger.w("MuseApp", "AutoBackupWorker Registro fallido", t) }
            // v1.134 P1-2: Actualización de caché de estadísticas Worker — Actualiza completamente la tabla stats_cache una vez al día
            // Acceda a componentes huérfanos originales v1.107 StatsCacheManager,Evite llenar toda la tabla cada vez que se abre la página de estadísticas GROUP BY
            resultOf {
                val request =
                    PeriodicWorkRequestBuilder<io.zer0.muse.schedule.StatsCacheWorker>(
                        1,
                        TimeUnit.DAYS,
                    ).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    io.zer0.muse.schedule.StatsCacheWorker.UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onError { msg, t -> Logger.w("MuseApp", "StatsCacheWorker Registro fallido", t) }
            // R-SVC-05: El bloqueo solo se mantiene durante el período de generación de tareas y keepAwake está activado.;No se mantiene vivo activamente cuando la batería está baja y no se está cargando.
            appScope.launch {
                combine(
                    settings.keepAwakeFlow,
                    chatGenerationManager.activeGenerations,
                    groupChatScheduler.activeGroupGeneration,
                ) { keepAwake, chat, group ->
                    keepAwake && (chat.isNotEmpty() || group?.isResponding == true)
                }.collect { active -> updateWakeLock(active) }
            }
            // Cambio programado de tema(Comprobar cada 30 segundos)
            appScope.launch {
                startThemeScheduler()
            }
            // v0.32: autoLaunch — Suscríbase al interruptor de inicio automático al encender,habilitar/Deshabilite el componente BootReceiver
            // (manifest Predeterminado enabled=false,Cambiar aquí según la configuración del usuario)
            appScope.launch {
                settings.autoLaunchFlow.collect { autoLaunch -> updateBootReceiverEnabled(autoLaunch) }
            }
            // v0.33: configuración de medios — Suscríbete MediaConfig,Cambiar la velocidad de conversación TTS/tono/Método de salida de idioma + audio aplicado a servicios reales.
            appScope.launch {
                settings.mediaConfigFlow.collect { cfg ->
                    ttsManager.applyConfig(cfg)
                }
            }
            // TTS"Seguir la configuración del proveedor"— Actualizar cuando cambie la lista de proveedores,Para síntesis y análisis de la nube baseUrl / apiKey
            appScope.launch {
                settings.providersFlow.collect { providers ->
                    ttsManager.applyProviders(providers)
                }
            }
            // v1.133: Verifique las actualizaciones de la versión de GitHub de forma asincrónica después del inicio de la aplicación(24h intervalo,fire-and-forget)
            // Reutilizar appScope(IO + SupervisorJob + GlobalCoroutineExceptionHandler),
            // Cualquier excepción no afectará el inicio de la aplicación.
            appScope.launch {
                resultOf { updateNotifier.checkAndNotify(this@MuseApp, forceCheck = false) }
                    .onError { msg, t -> Logger.w("MuseApp", "UpdateNotifier Error en la comprobación de inicio: ${t?.message ?: msg}", t) }
            }
            // P2-4: Limpiar registros de auditoría de más de 30 días al inicio(fire-and-forget,El fallo no afecta el inicio de la aplicación)
            appScope.launch {
                resultOf { auditLogger.cleanupOldLogs() }
                    .onError { msg, t -> Logger.w("MuseApp", "Error al limpiar el registro de auditoría: ${t?.message ?: msg}", t) }
            }
            // Reorganización del despertador recordatorio al inicio: el despertador en AlarmManager se perderá después de reiniciar el dispositivo o finalizar el proceso.
            // ReminderStore Todavía hay una entrada "No activada". Un arranque en frío compensará una reprogramación y se le recordará que debe reprogramar en el futuro.
            // Los recordatorios perdidos se enviarán nuevamente para evitar que los usuarios piensen que todavía están allí pero en realidad nunca suenan.fire-and-forget.
            appScope.launch {
                resultOf { io.zer0.muse.tools.reminder.ReminderStore(this@MuseApp).rescheduleAll() }
                    .onSuccess { s ->
                        Logger.i(
                            "MuseApp",
                            "Iniciar recordatorio de reprogramación: éxito=${s.rescheduled}, reedición=${s.catchUpNotified}, falló=${s.failed}",
                        )
                    }
                    .onError { msg, t -> Logger.w("MuseApp", "No se pudo iniciar el recordatorio de reprogramación: ${t?.message ?: msg}", t) }
            }
            // v1.x: Limpiar los archivos de salida de la herramienta con más de 24 horas de antigüedad al inicio(fire-and-forget,El fallo no afecta el inicio de la aplicación).
            // Los resultados completos más allá de la ventana de contexto se guardan en filesDir/tool_outputs/ y se leen mediante read_file en páginas.
            appScope.launch {
                resultOf { io.zer0.muse.tools.cleanupOldToolOutputs(this@MuseApp) }
                    .onError { msg, t -> Logger.w("MuseApp", "Error al limpiar el archivo de salida de la herramienta: ${t?.message ?: msg}", t) }
            }
            // v2.6.7 fix (AUDIT F23): Limpiar registros de puntos de interrupción del flujo de trabajo caducados.
            // WorkflowJournal.cleanup Lograr una convocatoria completa pero nula para todos los puestos. —— Cada vez con runId
            // automation_workflow Escriba una copia de jsonl para uso a largo plazo y crecimiento ilimitado.
            appScope.launch {
                resultOf {
                    org.koin.core.context.GlobalContext.get()
                        .get<io.zer0.muse.tools.WorkflowJournal>()
                        .cleanup()
                }.onError { msg, t ->
                    Logger.w("MuseApp", "Error al limpiar el registro del punto de interrupción del flujo de trabajo: ${t?.message ?: msg}", t)
                }
            }
            // v1.0.47: Instantánea estática del mensaje de calentamiento del sistema — Activado una vez de forma asincrónica buildStaticSnapshot(default asistente),
            // dejar DataStore(chatPreferences/userProfile Primera lectura aprox. 50-100ms) + buildToolManifestSection
            // (Elaboración de lista de herramientas Aprox. 100-200ms) La subcaché se carga con antelación. Se llama a refrescoContextInfo cuando el usuario ingresa a la sesión por primera vez.
            // buildStaticSnapshot llegará a estos subcaché,Cambiar la primera compilación de ~690ms hasta ~200ms.
            // fire-and-forget:El resultado se descarta,Utilice sólo efectos secundarios(población de subcaché);El fallo no afecta el inicio.
            // v1.0.52: Retrasar la ejecución durante 3 segundos. — Evite la inicialización con ChatViewModel + memoryBackfillMigration
            // Grupo de subprocesos IO de competencia(El registro muestra el calentamiento 117s,Se sospecha que compite con backfill LLM para llamar a la base de datos/IO Recursos).
            // Retraso para permitir que la actualizaciónContextInfo de ChatViewModel se ejecute primero(Los usuarios ven la interfaz más rápido),El calentamiento solo llena la subcaché.
            appScope.launch {
                kotlinx.coroutines.delay(3000)
                resultOf {
                    val defaultAssistant = assistantRepository.getById("default")
                    systemPromptAssembler.buildStaticSnapshot(
                        assistant = defaultAssistant,
                        memoryEnabled = true,
                    )
                }.onError { msg, t ->
                    Logger.w("MuseApp", "system prompt Fallo de precalentamiento: ${t?.message ?: msg}", t)
                }.onSuccess {
                    Logger.d("MuseApp", "system prompt Precalentamiento completado (${it.length} chars)")
                }
                // P1-1: AppLifecycleHook.onAppCreate(Ejecución retrasada,Evite bloquear el inicio)
                resultOf { hookRegistry.executeNoResult(io.zer0.muse.hook.AppLifecycleHook::class) { it.onAppCreate() } }
                    .onError { msg, t -> Logger.w("MuseApp", "AppLifecycleHook.onAppCreate falló: $msg", t) }
            }
        } // initializeRuntime

        if (incompleteRestore == null) {
            initializeRuntime()
            _startupReady.value = true
        } else {
            Logger.w(
                "MuseApp",
                "Recuperación de copia de seguridad incompleta detectada: restoreId=${incompleteRestore.restoreId}, " +
                    "phase=${incompleteRestore.phase}, completedStores=${incompleteRestore.completedStores}",
            )
            appScope.launch {
                var claimed = false
                val acquired = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    while (!claimed) {
                        claimed = io.zer0.common.ProcessWriteGate.begin()
                        if (!claimed) kotlinx.coroutines.delay(100)
                    }
                    true
                } == true
                if (!acquired) {
                    // Cuando la puerta está ocupada durante mucho tiempo, la aplicación no puede quedar bloqueada permanentemente en la pantalla de inicio: se iniciará como de costumbre después de la grabación.
                    // Esta reversión se dejará hasta el próximo inicio para volver a intentarlo.
                    Logger.e("MuseApp", "La recuperación de arranque espera el tiempo de espera de la puerta de escritura, omite la reversión y continúa con el arranque")
                    _startupReady.value = true
                    return@launch
                }
                // La reversión generará errores debido a inconsistencia de la base de datos, copias faltantes, fallas de deserialización y otras razones.
                // Nadie se había hecho cargo antes._startupReady para siempre por false —— El usuario solo ve una pantalla de presentación,
                // Y no existe ningún camino de autorrescate excepto borrar los datos. Aquí debes darte cuenta de que "ningún error bloqueará el inicio".
                // Utilice runCatching en su lugar catch(Throwable): La semántica es la misma, pero las señales de cancelación de rutina no se tragan.
                try {
                    val rollbackError = runCatching {
                        backupService.recoverIncompleteRestore(gateAlreadyHeld = true)
                        this@MuseApp.initializeRuntime()
                    }.exceptionOrNull()
                    if (rollbackError != null) {
                        Logger.e("MuseApp", "No se pudo iniciar la reversión de la recuperación. Abandone el punto de recuperación y continúe con el inicio.", rollbackError)
                        resultOf { backupService.abandonIncompleteRestore(rollbackError) }
                            .onError { msg, t -> Logger.w("MuseApp", "Error al marcar el punto de recuperación como no válido: ${t?.message ?: msg}", t) }
                    }
                } finally {
                    io.zer0.common.ProcessWriteGate.end()
                    // Independientemente de si la reversión se realizó correctamente o no, se debe salir de la interfaz de inicio.
                    _startupReady.value = true
                }
            }
        }
    }

    /**
     * Corrutina de cambio de tema programado — Consulta la hora actual cada 30 segundos.,Corta los colores claros hasta la hora de despertarte.,Corta los colores oscuros para la hora de dormir.
     *
     * v1.134 P0-2: Usar en su lugar while(isActive) + GlobalCoroutineExceptionHandler,con otros Runner
     * alineación de estilo;Originales while(true) No saldrá cuando se cancele la corrutina.,Y las excepciones internas terminarán la rutina sin ninguna garantía.
     */
    private suspend fun startThemeScheduler() {
        var currentSchedule = settings.themeScheduleFlow.first()
        // Recoger cambios de horario en paralelo
        appScope.launch(io.zer0.muse.util.GlobalCoroutineExceptionHandler) {
            settings.themeScheduleFlow.collect { currentSchedule = it }
        }
        while (coroutineContext.isActive) {
            try {
                if (currentSchedule.enabled) {
                    val now = java.util.Calendar.getInstance()
                    val currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                    val wakeUpMin = currentSchedule.wakeUpHour * 60 + currentSchedule.wakeUpMinute
                    val sleepMin = currentSchedule.sleepHour * 60 + currentSchedule.sleepMinute

                    val desiredMode =
                        if (sleepMin > wakeUpMin) {
                            if (currentMinute in wakeUpMin until sleepMin) "light" else "dark"
                        } else {
                            if (currentMinute >= sleepMin || currentMinute < wakeUpMin) "dark" else "light"
                        }

                    val currentMode = settings.themeModeFlow.first()
                    if (currentMode != desiredMode && (desiredMode == "light" || desiredMode == "dark")) {
                        settings.saveThemeMode(desiredMode)
                        Logger.i("MuseApp", "Cambio programado de tema: → $desiredMode ($currentMinute puntos)")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Cancelación de rutina,Salir normalmente
            } catch (e: Exception) {
                Logger.w("MuseApp", "ThemeScheduler tick error: ${e.message}")
            }
            kotlinx.coroutines.delay(30_000)
        }
    }

    /**
     * Configuración StrictMode(depurar solo construir,por [onCreate] en [BuildConfig.DEBUG] Llamado cuando es cierto).
     *
     * Detectar violaciones de acceso a la red y lectura y escritura del disco del hilo principal,Salida a través de penalizaciónLog a logcat,
     * Ayuda en la fase de desarrollo a descubrir operaciones de bloqueo como el hilo principal IO/red.(Posibles causas fundamentales de ANR).
     * release La compilación no está habilitada([BuildConfig.DEBUG] == false),Evite la sobrecarga de rendimiento y las fugas de registros.
     *
     * Configurar al mismo tiempo VmPolicy penaltyLog,Detecte violaciones a nivel de VM, como fugas de recursos.
     */
    private fun configureStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .penaltyLog()
                .build(),
        )
        Logger.d("MuseApp", "StrictMode Habilitado(debug): detectDiskReads/detectDiskWrites/detectNetwork + penaltyLog")
    }

    /**
     * Registre un receptor de transmisión para el botón "Esta vez no" de notificación de mensaje activo.
     *
     * Regístrese dinámicamente en tiempo de ejecución en lugar de escribirlo en el manifiesto: esta acción solo sirve las notificaciones que aparecen en esta aplicación.
     * No es necesario que pueda recibirlo cuando no se inicia (este proceso envía la notificación en sí y, si el proceso finaliza, no habrá notificaciones en las que hacer clic).
     * Se puede utilizar el registro dinámico [ContextCompat.RECEIVER_NOT_EXPORTED] Dígale al sistema explícitamente
     * "Esta transmisión no está abierta a aplicaciones externas".
     *
     * Tenga cuidado de no fusionar nuevas líneas: el literal de bandera debe aparecer en la lista de parámetros real de la llamada de registro.
     * Hay una prueba que escaneará las llamadas de RegisterReceiver de todas las posiciones para confirmar que no hay forma de escribir que no exporte ni declare la bandera.
     */
    private fun registerProactiveDismissReceiver() {
        resultOf {
            ContextCompat.registerReceiver(
                this,
                io.zer0.muse.proactive.ProactiveDismissReceiver(),
                IntentFilter(io.zer0.muse.proactive.ProactiveDismissProtocol.ACTION_DISMISS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onError { msg, t -> Logger.w("MuseApp", "Error en el registro del receptor de la acción de notificación de mensaje activo: $msg", t) }
    }

    /**
     * R-SVC-05: Solo genere aplicaciones mientras la tarea esté activa/Suelte PARTIAL_WAKE_LOCK.
     *
     * Se utiliza para mantener la CPU activa durante tareas de compilación de larga duración.,Evite las interrupciones del sueño del dispositivo.
     * Solo bloqueo de CPU,No afecta el brillo de la pantalla;No se mantiene activo activamente cuando la batería está baja y no se está cargando.
     */
    private fun updateWakeLock(activeGeneration: Boolean) {
        if (activeGeneration && !isLowBattery()) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse:KeepAwake").also {
                    it.setReferenceCounted(false)
                    // Tarea larga (generación de video/RAGíndice) puede exceder10minutos, establecer30Minutos para guardar
                    it.acquire(30 * 60 * 1000L)
                }
            Logger.i("MuseApp", "keepAwake: WAKE_LOCK acquired")
        } else {
            wakeLock?.let { if (it.isHeld) resultOf { it.release() } }
            wakeLock = null
            Logger.i("MuseApp", "keepAwake: WAKE_LOCK released")
        }
    }

    /** R-SVC-05: No se mantiene activo activamente cuando la batería está baja y no se está cargando. */
    private fun isLowBattery(): Boolean {
        val battery = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager ?: return false
        val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1..15 && !battery.isCharging
    }

    /**
     * v0.32: Habilitado según el interruptor de inicio automático/Desactivar [BootReceiver] componentes.
     *
     * usar [PackageManager.setComponentEnabledSetting] cambiar:
     *  - ENABLED:El sistema puede entregar a BootReceiver BOOT_COMPLETED
     *  - DISABLED:El sistema se salta esto. receiver,No arranca al arrancar muse
     *
     * manifest Predeterminado enabled=false,Determinado por la configuración del usuario después del primer inicio.
     */
    private fun updateBootReceiverEnabled(autoLaunch: Boolean) {
        val component = ComponentName(this, BootReceiver::class.java)
        val newState =
            if (autoLaunch) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        // M1: Para uso unificado resultOf{} sustituto runCatching{}(Convención de resultados del proyecto)
        resultOf {
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }.onError { msg, t ->
            Logger.w("MuseApp", "setComponentEnabledSetting failed: ${t?.message ?: msg}")
        }
        Logger.i("MuseApp", "autoLaunch: BootReceiver enabled=$autoLaunch")
    }

    /**
     * v0.43: Siembre los documentos de desarrollo de rebajas en activos/devdocs/ en la base de conocimientos.
     *
     * propósito del diseño: Deje que LLM encuentre los documentos de desarrollo propios del proyecto a través de la herramienta Knowledge_Search(Descripción de la función/
     * Skill sistema/sistema de memoria/Configuración del asistente/Funciones de chat/mensajes no solicitados/Web Implementación de búsqueda, etc.),Entonces, cuando el usuario pregunta
     * "¿Qué puedes hacer?""Cómo funciona la memoria a largo plazo"Capaz de responder preguntas con sinceridad.,En lugar de inventarlo de memoria.
     *
     * Estos documentos no se muestran a los usuarios.: KnowledgeScreen filtrará fileType="devdoc" entrada.
     *
     * Idempotencia: Usar estable id `devdoc-<filenameWithoutExt>`,REPLACE La estrategia garantiza que el contenido se actualice pero no se duplique durante la actualización.
     *
     * tolerancia al fallo: Omita silenciosamente el directorio si no existe o se lee de manera anormal.(recuerda uno Logger.w),Empieza sin bloquear.
     */
    private suspend fun seedDevDocs() {
        builtInKnowledgeDocSeeder.ensureSeeded()
    }

    /** v2.4.x: Índice de vectores de documentos internos(incremento;embedding Saltar silenciosamente si no está disponible,La búsqueda todavía tiene detalles de ME GUSTA). */
    private suspend fun indexDevDocs() {
        builtInKnowledgeDocSeeder.ensureIndexed()
    }

    /**
     * v1.0.17: registro rápido JSON → Room Migración única.
     *
     * Pasar el indicador SharedPreferences `quick_notes_migrated` Garantizado para ser ejecutado solo una vez:
     *  - Usuarios que actualizan a v1.0.17 por primera vez:La bandera es false → Ejecutar migración → conjunto true
     *  - Usuarios migrados:La bandera es true → saltar
     *  - Nuevo usuario:JSON El archivo no existe,[QuickNoteStore.migrateToRoom] Regresar 0,Aún listo true
     *
     * Idempotencia:[QuickNoteDao.upsert] usar OnConflictStrategy.REPLACE,Incluso si falta la bandera
     * Las llamadas repetidas no producirán registros duplicados. Pero para evitar atravesar el archivo JSON cada vez que se inicia,Todavía uso banderas para garantizar que solo se ejecute una vez.
     *
     * Los archivos JSON no se eliminan una vez completada la migración,Mantener como copia de seguridad local(Los usuarios pueden limpiar manualmente).
     */
    private suspend fun migrateQuickNotesIfNeeded() {
        val prefs = getSharedPreferences("muse_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_QUICK_NOTES_MIGRATED, false)) {
            return
        }
        val count = quickNoteStore.migrateToRoom(quickNoteDao)
        prefs.edit().putBoolean(KEY_QUICK_NOTES_MIGRATED, true).apply()
        Logger.i("MuseApp", "Migración rápida de registros completada: $count los registros han sido importados Room")
    }

    /**
     * Phase 11.1.6: Fábrica global de Coil ImageLoader.
     *
     * Registrarse [SvgDecoder] y [GifDecoder] después,todos [coil.compose.AsyncImage] llamar
     * Puede decodificar correctamente imágenes vectoriales SVG y animaciones GIF(Se han introducido dependencias. coil-svg / coil-gif,
     * Pero el decodificador debe registrarse a través de ImageLoader.components para que surta efecto.).
     *
     * - SVG: Para gráficos vectoriales en línea Markdown/iconos de asistente remoto(Si apoyos futuros)
     * - GIF: Para emoticonos animados/pegatinas dinámicas(minSdk 26 ≥ api19,GifDecoder Disponible;
     *   api28+ El sistema irá automáticamente ImageDecoderDecoder,Mejor rendimiento)
     */
    // UI_HIDDEN no libera cachés: evita decodificar todas las imágenes al volver a la app.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val tight = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        if (tight) releaseImageCaches()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseImageCaches()
    }

    private fun releaseImageCaches() {
        runCatching { io.zer0.muse.perf.DataUriImageCache.clear() }
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
            .onFailure { Logger.w("MuseApp", "No se pudo liberar la caché de imágenes", it) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(SvgDecoder.Factory())
            // v1.112 (F3): GIF Decodificador de GIF — SegúnAPISelección de nivel de implementación óptima.
            // - API 28+ (Android 9+):usar ImageDecoderDecoder(Basado en ImageDecoder API),
            //   Mejor rendimiento,Menor uso de memoria,y la película está en la sección OEM ROM(OPPO/MIUI Versión alta)Excepción de representación,
            //   Hace que el GIF muestre solo el primer fotograma y se convierta en una imagen estática.
            // - API < 28:usar GifDecoder(Basado en Movie),Compatible con dispositivos más antiguos.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        // v0.36 Optimización del rendimiento:Limitar la memoria caché a la memoria disponible 15%,Evite las imágenes grandesOOM;Agregue almacenamiento en caché de disco para reducir las descargas repetidas.
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(this.cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024) // 256 MB
                .build()
        }
        .crossfade(true)
        .build()

    companion object {

        /** Clave de programación para círculos de viejos amigos que han estado desconectados. Sólo se utiliza para limpiar tareas residuales durante la actualización y no debe utilizarse para el registro. */
        private const val LEGACY_MOMENT_WORK_NAME = "muse_moment_worker"

        /** Antigua clave de programación de mensajes activa que ha estado fuera de línea. Sólo se utiliza para limpiar tareas residuales durante la actualización y no debe utilizarse para el registro. */
        private const val LEGACY_PROACTIVE_WORK_NAME = "proactive_message_check"

        /** v1.0.17: Registre rápidamente banderas de migración SharedPreferences key(Archivo muse_migration). */
        private const val KEY_QUICK_NOTES_MIGRATED = "quick_notes_migrated"
    }
}
