package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Envío de eventos de activación de complementos.
 *
 * `manifest.activationEvents` Anteriormente era sólo un campo que se mostraba y nunca se enviaba. —— El autor del complemento escribió
 * `["onStartup"]`, pensé que se llamaría una vez para la inicialización al inicio, pero en realidad no pasó nada y no hubo ningún aviso.
 * Este puente convierte las declaraciones en acciones reales.
 *
 * ## Eventos soportados
 *
 *  - `onStartup`: Se distribuye una vez cuando se inicia la aplicación o se habilita el complemento. La entrada del complemento debe exportarse
 *    `onActivate(event)`(JS) o una función con el mismo nombre (Python), introduzca los parámetros `{ event: "onStartup" }`.
 *
 * `onCommand` / `onFileType` Actualmente**sin fuente de activación**(El comando de barra diagonal es una enumeración codificada y la apertura del archivo no
 * puntos de gancho del complemento). Sus declaraciones se conservan pero no se distribuyen; La página de instalación lo indica claramente y no pretende respaldarlo.
 *
 * ## Eliminar duplicados
 *
 * el mismo `<id>@<version>` Activar sólo una vez.revisionFlow Se iniciará durante la instalación, el inicio y el apagado, y durante los cambios de versión.
 * Si no lo recuerda, el complemento se reinicializará cada vez que se cambie la configuración. Naturalmente, los registros dejarán de ser válidos después de deshabilitar o actualizar el complemento.
 * (La colección de registros se reduce cada vez según la lista habilitada actualmente) y al volver a habilitarla se activará nuevamente.
 *
 * ## Manejo de fallas
 *
 * Solo se registran registros si falla la activación. Los complementos son capacidades opcionales y cualquier error de inicialización no debería afectar el inicio de la aplicación.
 * Tampoco debería impedir que se activen otros complementos.
 */
class PluginActivationBridge(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val appScope: CoroutineScope,
    private val settings: io.zer0.muse.data.SettingsRepository,
) {
    private val invoker = PluginFunctionInvoker(context)

    /** Ya activado `<id>@<version>`. */
    private val activatedKeys = HashSet<String>()

    /** Iniciar la suscripción (convocada una vez por la asamblea). */
    fun start() {
        appScope.launch {
            pluginManager.revisionFlow.collect { dispatchStartup() }
        }
        // revisionFlow Es posible que no haya un inicio inicial y la primera sincronización se ejecute por separado.
        appScope.launch { dispatchStartup() }
    }

    /** declarado a todos `onStartup` Y los complementos habilitados se distribuyen una vez para su activación. */
    suspend fun dispatchStartup() {
        // No se activará ningún complemento cuando el interruptor principal esté apagado. Los registros activados se borran al mismo tiempo: deben poder activarse nuevamente cuando se vuelvan a abrir.
        if (!settings.arePluginsGloballyEnabled()) {
            synchronized(activatedKeys) { activatedKeys.clear() }
            Logger.i(TAG, "El interruptor maestro enchufable se apaga y se omite la activación.")
            return
        }
        val plugins = pluginManager.list().filter { it.enabled && it.kind == KIND_TOOL }
        // Mantenga registros únicamente de los complementos que aún existen: deshabilitado/El complemento desinstalado debe poder activarse nuevamente cuando se vuelva a habilitar.
        val liveKeys = plugins.map { keyOf(it) }.toSet()
        synchronized(activatedKeys) { activatedKeys.retainAll(liveKeys) }
        plugins.forEach { activateIfNeeded(it) }
    }

    private suspend fun activateIfNeeded(plugin: PluginManager.InstalledPlugin) {
        val key = keyOf(plugin)
        val verified = resolveActivatable(plugin, key) ?: return

        val result = resultOf {
            invoker.call(
                manifest = verified.manifest,
                entryCode = verified.entryCode,
                functionName = ACTIVATION_FUNCTION,
                argsJson = STARTUP_ARGS,
                configJson = pluginManager.getPluginConfigJson(verified.manifest),
            )
        }.getOrNull()

        when (result) {
            null -> Logger.w(TAG, "Excepción de activación de complemento: ${plugin.id}")
            is SkillEngineResult.Success -> Logger.i(TAG, "El complemento está activado: ${plugin.id}")
            is SkillEngineResult.Error -> Logger.w(TAG, "La activación del complemento falló: ${plugin.id} — ${result.message}")
        }
    }

    /**
     * Determine si este complemento debe activarse en este momento; cuando se activa, devuelve la entrada verificada; de lo contrario, es nula.
     *
     * Las cuatro condiciones previas están encadenadas aquí en lugar de aplanadas en [activateIfNeeded] Aquí:
     * "OnStartup no declarado" y "ya activado" se omiten normalmente y no deben mezclarse con "fallo de activación" en la misma capa.
     * De lo contrario, el registro considerará los dos primeros como eventos que requieren atención.
     */
    private suspend fun resolveActivatable(
        plugin: PluginManager.InstalledPlugin,
        key: String,
    ): PluginManager.VerifiedPlugin? {
        synchronized(activatedKeys) { if (key in activatedKeys) return null }

        // Declarado en el manifiesto en el disco, no en la caché del registro —— Consistente con todas las rutas de ejecución.
        val verified = resultOf { pluginManager.loadVerifiedPlugin(plugin.id) }.getOrNull() ?: run {
            Logger.d(TAG, "Se omitió la activación del complemento(Error en la verificación de entrada): ${plugin.id}")
            return null
        }
        if (ACTIVATION_STARTUP !in verified.manifest.activationEvents) return null

        // Regístrese primero y luego ejecútelo: la función de activación puede ser muy lenta y la revisión ha cambiado durante el período y no debe ejecutarse repetidamente.
        val registered = synchronized(activatedKeys) { activatedKeys.add(key) }
        return if (registered) verified else null
    }

    private fun keyOf(plugin: PluginManager.InstalledPlugin): String = "${plugin.id}@${plugin.version}"

    companion object {
        private const val TAG = "PluginActivation"

        /** Sólo los complementos de herramientas tienen entradas ejecutables. */
        private const val KIND_TOOL = "tool"

        /** Eventos de activación admitidos: Inicio. */
        const val ACTIVATION_STARTUP = "onStartup"

        /** La entrada del complemento debe exportar el nombre de la función de activación. */
        const val ACTIVATION_FUNCTION = "onActivate"

        /** Active los parámetros de entrada (matriz de parámetros JSON, tome el primer elemento como parámetro de entrada de la función). */
        private const val STARTUP_ARGS = """[{"event":"onStartup"}]"""
    }
}
