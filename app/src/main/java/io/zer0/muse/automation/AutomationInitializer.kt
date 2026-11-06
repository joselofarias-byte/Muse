package io.zer0.muse.automation

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.tools.AutomationTools
import io.zer0.muse.terminal.TermuxChannel
import io.zer0.muse.tools.ToolPermissionStatus
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * UI Entrada de inicialización del módulo de automatización.
 *
 * La aplicación principal está en [android.app.Application.onCreate] llamar [initialize] Completo:
 * 1. crear [AutomationManager] singleton
 * 2. Registrarse [AutomationTools] llegar [ToolRegistry],Hacer que la IA sea invocable
 * 3. Actualizar asincrónicamente el estado de los permisos de tres capas
 *
 * Uso:
 * ```
 * class MuseApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         AutomationInitializer.initialize(this, toolRegistry)
 *     }
 * }
 * ```
 */
object AutomationInitializer {
    private const val TAG = "AutomationInit"

    /**
     * P3-2: Alcance a nivel de módulo (reemplaza a GlobalScope).
     *
     * SupervisorJob Asegurar que el fracaso de una sola subtarea no afecte a otras;
     * Sigue vivo con el proceso, pero es cancelable, comprobable y ya no utiliza GlobalScope sin restricciones.
     */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    @Volatile
    private var _manager: AutomationManager? = null

    /** Singleton global de AutomationManager. Lanza una excepción cuando no se inicializa. */
    val manager: AutomationManager
        get() = _manager ?: error("AutomationInitializer not initialized. Call initialize() first.")

    /** Si se ha inicializado. */
    val isInitialized: Boolean get() = _manager != null

    /**
     * Inicialice el módulo de automatización.
     * @param context Application context
     * @param toolRegistry Registrador de herramientas global(AI Entrada de llamada de herramienta)
     */
    fun initialize(context: Context, toolRegistry: ToolRegistry) {
        if (_manager != null) return
        synchronized(this) {
            if (_manager != null) return
            val appContext = context.applicationContext
            // Comparta el mismo ShizukuAuthorizer con la ruta principal de Shell para evitar“verificación de estado”y“ejecución real”Cada uno adopta su propio enfoque.
            val authorizer =
                runCatching {
                    org.koin.java.KoinJavaComponent.get<ShizukuAuthorizer>(ShizukuAuthorizer::class.java)
                }.getOrElse { ShizukuAuthorizer(appContext) }
            val mgr =
                AutomationManager(
                    context = appContext,
                    shizukuAuthorizer = authorizer,
                )
            _manager = mgr

            // v2.x: Jerarquía de permisos de herramientas — Inyectar el proveedor de estado de autorización del entorno de ejecución(find_tools Visualización de estado + verificación previa antes de la ejecución.)
            toolRegistry.permissionStatusProvider = {
                // v2.7: Utilice instantáneas en tiempo real (vuelva a detectar la accesibilidad con cada verificación previa)—— Servicio de accesibilidad conexión asíncrona,
                // La instantánea de la caché se detendrá en falso al inicio, lo que provocará que la herramienta juzgue mal“No autorizado”.
                val state = mgr.liveSnapshot()
                ToolPermissionStatus(
                    accessibility = state.accessibilityEnabled,
                    shellTier = state.shellEnabled || state.rootEnabled,
                    termux = TermuxChannel.get(appContext).availability() is TermuxChannel.Availability.Ready,
                )
            }

            // Regístrese para el conjunto de herramientas de automatización de la interfaz de usuario
            val workflowJournal = runCatching {
                org.koin.java.KoinJavaComponent.get<io.zer0.muse.tools.WorkflowJournal>(
                    io.zer0.muse.tools.WorkflowJournal::class.java,
                )
            }.getOrNull()
            val virtualDisplayManager = runCatching {
                io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager(appContext, mgr)
            }.onFailure { Logger.w(TAG, "VirtualDisplay manager init failed: ${it.message}") }
                .getOrNull()
            val virtualDisplayClient = virtualDisplayManager?.let { manager ->
                io.zer0.muse.automation.vdisplay.VirtualDisplayClient(appContext, manager)
            }
            val nodeScriptExecutor: suspend (String, Long) -> io.zer0.muse.tools.script.SkillEngineResult = { code, timeoutMs ->
                io.zer0.muse.tools.NodeScriptTool.evaluate(appContext, code, timeoutMs)
            }
            val tools = AutomationTools(mgr, workflowJournal, virtualDisplayClient, virtualDisplayManager, nodeScriptExecutor)
            tools.register(toolRegistry)

            // Registrar herramienta de nivel raíz(Ejecución jerárquica:Shizuku prioridad,Root Ten todo en cuenta,El registro en sí no tiene efectos secundarios.)
            try {
                io.zer0.muse.tools.RootToolsRegistrar(toolRegistry, mgr, appContext)
            } catch (e: Exception) {
                Logger.w(TAG, "RootToolsRegistrar failed: ${e.message}")
            }

            // v2.2.1: GUI Agent herramienta de anillo(Operaciones de varios pasos impulsadas por la visión;VisionBridge Proporcionado por Koin,Si falta, se omitirá el registro.)
            try {
                val visionBridge =
                    org.koin.java.KoinJavaComponent.get<io.zer0.muse.vision.VisionBridge>(
                        io.zer0.muse.vision.VisionBridge::class.java,
                    )
                io.zer0.muse.automation.agent.UiAgentTool(
                    io.zer0.muse.automation.agent.UiAgentRunner(mgr, visionBridge, virtualDisplayClient, virtualDisplayManager),
                ).register(toolRegistry)
            } catch (e: Exception) {
                Logger.w(TAG, "UiAgent tool registration failed: ${e.message}")
            }

            // v2.2.1: herramienta de pantalla virtual(Fondo de pantalla oculta;Shizuku o canal raíz,El registro en sí no tiene efectos secundarios.)
            if (virtualDisplayManager != null && virtualDisplayClient != null) {
                try {
                    io.zer0.muse.automation.vdisplay.VirtualDisplayTool(
                        appContext,
                        virtualDisplayClient,
                        virtualDisplayManager,
                        mgr,
                    ).register(toolRegistry)
                } catch (e: Exception) {
                    Logger.w(TAG, "VirtualDisplay tool registration failed: ${e.message}")
                }
            }

            // v2.x Agent ización: Conjunto de herramientas de control de aplicaciones(app_list/launch/settings/force_stop/clear_data/uninstall).
            // "lista/empezar/Abrir página de configuración"Solo usa tus propios permisos(Se puede utilizar sin autorización automática.),Las acciones destructivas se llevan a cabo de manera uniforme.
            // execTiered(Shizuku prioridad,Root Ten todo en cuenta);¿Qué capacidad se necesita para informar con veracidad cuando hay falta de acceso?
            try {
                io.zer0.muse.automation.appcontrol.AppControlTools(
                    context = appContext,
                    controller = io.zer0.muse.automation.appcontrol.AndroidAppController(appContext, mgr),
                ).register(toolRegistry)
            } catch (e: Exception) {
                Logger.w(TAG, "AppControl tools registration failed: ${e.message}")
            }

            // Actualizar asincrónicamente el estado del permiso(No bloquea el inicio de la aplicación)
            // P3-2(informe de auditoría): Utilice el alcance a nivel de aplicación en lugar de GlobalScope.
            // GlobalScope No está sujeto a restricciones del ciclo de vida, no se puede cancelar, es difícil de probar y es fácil de salir cuando finaliza el proceso.
            // Con tareas inacabadas intercaladas entre ellos.SupervisorJob Asegúrese de que un solo fallo no afecte a otras subtareas.
            appScope.launch {
                runCatching {
                    tools.initialize()
                }.onFailure { Logger.w(TAG, "permission refresh failed: ${it.message}") }
            }

            // v2.7: Conexión del servicio de accesibilidad/Actualice proactivamente las instantáneas de permisos cuando esté desconectado.
            // La accesibilidad está conectada de forma asincrónica: el servicio está en ServiceConnected solo después de que el usuario enciende el interruptor;
            // Si no se actualiza, el caché detectado al inicio siempre se detendrá en falso.
            // Conducir a un error de cálculo previo a la verificación de herramientas“No autorizado”(El asistente lee la configuración del sistema pero muestra correctamente que está habilitado).
            io.zer0.muse.accessibility.MuseAccessibilityService.addConnectionListener { connected ->
                appScope.launch {
                    runCatching { mgr.refreshPermissions() }
                        .onFailure { Logger.w(TAG, "a11y conectar($connected)Error al actualizar los permisos después: ${it.message}") }
                }
            }

            Logger.i(TAG, "UI automation module initialized (3-tier: a11y/shell/root)")
        }
    }
}
