package io.zer0.muse.data.plugin

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.hook.HookRegistry
import io.zer0.muse.hook.PromptFinalizeHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v2.x(Sistema de gancho enchufable MVP): Poner**Complemento habilitado**El gancho declarado está enganchado en [HookRegistry].
 *
 * Diseño:
 *  - Suscríbete [PluginManager.revisionFlow], instalación de complementos/Desinstalar/Iniciar y detener/Recarga completa cuando cambie la versión;
 *  - Sólo reenganche los anzuelos registrados por este puente (id prefijo [HOOK_ID_PREFIX]), no toca el gancho Kotlin incorporado;
 *  - MVP Sólo acceso `prompt_finalize` Un punto de enlace (reescribe el historial antes de que finalice la palabra indicada)——
 *    Este es el punto de entrada mínimo para que el complemento afecte el contenido generado. Los puntos de enganche se ampliarán según sea necesario en el futuro;
 *  - No cargar un solo complemento no afectará a otros complementos;
 *  - El código de entrada se pasa cada vez. [PluginManager.loadVerifiedFunction] Vuelva a verificar (no confíe en el caché del registro).
 *
 * ¿Por qué utilizar un puente en lugar de permitir que PluginManager mantenga HookRegistry directamente?
 *  HookRegistry Es un registro puramente lógico,PluginManager es almacenamiento/Dominio de seguridad; los dos se desacoplan a través de este puente,
 *  Evitar inyectar funciones de ejecución en PluginManager también facilita las pruebas.
 */
class PluginHookBridge(
    private val context: android.content.Context,
    private val pluginManager: PluginManager,
    private val hookRegistry: HookRegistry,
    private val appScope: CoroutineScope,
    private val settings: io.zer0.muse.data.SettingsRepository,
) {
    private val registered = HashSet<String>()

    /**
     * El último valor del interruptor maestro enchufable.
     *
     * [sync] Es una función de sincronización (llamada directamente por revisionFlow) y no puede bloquear temporalmente el flujo de lectura en ella.
     * Por lo tanto, la rutina de suscripción vacía el valor en el campo. Valor predeterminado verdadero: presione abrir procesamiento cuando el interruptor no se pueda leer.
     * Todos los complementos instalados por el usuario no se pueden detener silenciosamente debido a un solo error de lectura.
     */
    @Volatile
    private var globallyEnabled = true

    /** Iniciar la suscripción (convocada una vez por la asamblea). */
    fun start() {
        appScope.launch {
            settings.pluginsGloballyEnabledFlow.collect { enabled ->
                globallyEnabled = enabled
                sync()
            }
        }
        appScope.launch {
            pluginManager.revisionFlow.collect { sync() }
        }
        // Primera sincronización (revisionFlow Puede que no haya un lanzamiento inicial)
        appScope.launch { sync() }
    }

    /** Vuelva a instalar todos los ganchos de complementos (idempotentes: primero elimine los antiguos y luego instale los nuevos). */
    fun sync() {
        // Primero elimine todos los registros anteriores de este puente. hook
        synchronized(registered) {
            registered.forEach { id ->
                runCatching { hookRegistry.unregister(PromptFinalizeHook::class, id) }
            }
            registered.clear()
        }

        // Nadie se cuelga cuando el interruptor principal está apagado: el código del complemento no debería tener la oportunidad de ejecutarse, incluidos los Hooks.
        if (!globallyEnabled) {
            Logger.i(TAG, "El interruptor principal del complemento está apagado y no hay nada instalado. hook")
            return
        }

        pluginManager.list().filter { it.enabled && it.kind == "tool" }.forEach { plugin ->
            // La función de entrada debe estar verdaderamente definida (loadVerifiedFunction Leerá el disco nuevamente y pasará por la puerta de seguridad)
            val verified =
                resultOf { pluginManager.loadVerifiedFunction(plugin.id, HOOK_FUNCTION_NAME) }.getOrNull() ?: run {
                    Logger.d(TAG, "Gancho de complemento omitido(indefinido ${HOOK_FUNCTION_NAME} O la verificación de entrada falló): ${plugin.id}")
                    return@forEach
                }
            if (HOOK_PROMPT_FINALIZE !in verified.manifest.hooks) return@forEach

            val hook = PluginPromptFinalizeHook(
                context = context,
                pluginId = plugin.id,
                manifest = verified.manifest,
                entryCode = verified.entryCode,
                configJson = pluginManager.getPluginConfigJson(verified.manifest),
            )
            hookRegistry.register(hook)
            synchronized(registered) { registered.add(hook.id) }
            Logger.i(TAG, "Gancho de complemento registrado: ${plugin.id} → ${hook.id}")
        }
    }

    companion object {
        private const val TAG = "PluginHookBridge"

        /** El prefijo de identificación del gancho registrado por este puente (elija solo el suyo cuando vuelva a colgar). */
        const val HOOK_ID_PREFIX = "plugin:"

        /** MVP Puntos de gancho soportados. */
        const val HOOK_PROMPT_FINALIZE = "prompt_finalize"

        /** El nombre de la función de enlace debe exportarse mediante la entrada del complemento. */
        const val HOOK_FUNCTION_NAME = "onPromptFinalize"
    }
}
