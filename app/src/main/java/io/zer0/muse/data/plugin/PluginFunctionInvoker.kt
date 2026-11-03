package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine

/**
 * prensa `manifest.runtime` Envíe una llamada de función de complemento al motor correspondiente.
 *
 * Motivo de la extracción: Ya existen tres puntos de llamada (ejecución de herramienta,`prompt_finalize` Hook, iniciar la activación),
 * Escribe cada lugar una vez `if (runtime == python)` Si es así, deberá recordar cambiarlo tres veces si agrega un tercer tiempo de ejecución en el futuro.
 * El rendimiento que falta es "El complemento está instalado pero cierta capacidad no surte efecto de forma silenciosa"—— No se informa ningún error, simplemente no hay respuesta.
 *
 * La semántica de los parámetros de los dos tiempos de ejecución se ha alineado (código de entrada + nombre de la función + matriz de parámetros JSON + configuración JSON).
 * Por lo tanto, no es necesario reescribir el método de llamada según el tiempo de ejecución, solo cambiar la implementación del motor.
 */
internal class PluginFunctionInvoker(private val context: Context) {

    suspend fun call(
        manifest: PluginManifest,
        entryCode: String,
        functionName: String,
        argsJson: String,
        configJson: String?,
        timeoutMs: Long = SkillEngine.DEFAULT_TIMEOUT_MS,
    ): SkillEngineResult =
        if (isPython(manifest)) {
            PythonSkillEngine(context).callPluginFunction(
                entryCode = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = timeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
            )
        } else {
            WebViewSkillEngine().callFunction(
                functionDef = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = timeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
            )
        }

    companion object {
        /** `manifest.runtime` representa el valor del subproceso CPython incorporado. */
        const val RUNTIME_PYTHON = "python"

        /** Si el complemento se ejecuta en el tiempo de ejecución de Python. */
        fun isPython(manifest: PluginManifest): Boolean =
            manifest.runtime.equals(RUNTIME_PYTHON, ignoreCase = true)
    }
}
