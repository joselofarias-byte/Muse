package io.zer0.muse.tools.script

import io.zer0.common.Logger
import io.zer0.muse.tools.JsSandbox
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Basado en WebView V8 [SkillEngine] darse cuenta (P3-1).
 *
 * Encapsulación [JsSandbox] Singleton, agregue un bloqueo mutex para garantizar que la instancia del motor no sea reentrante.
 *
 * Nota:JsSandbox Internamente contiene un singleton WebView global, todas las instancias de WebViewSkillEngine
 * Comparte el mismo motor V8 subyacente. bloqueo mutex [executionMutex] Asegúrese de que solo haya una persona que llama al mismo tiempo
 * Utilice un motor para evitar la diafonía de devolución de llamadas de evaluación de Javascript.
 *
 * Actualmente, debido a la limitación de WebView singleton, el motor no se puede paralelizar; se paralelizará después de conectarse a QuickJS en el futuro.
 */
class WebViewSkillEngine : SkillEngine {

    private val executionMutex = Mutex()

    /**
     * C-30: [scopeKey] pasar por [JsSandbox.execute],Haga que el estado del fusible enchufable esté aislado por la identificación del enchufe.
     * El valor predeterminado se declara en la interfaz. [SkillEngine.eval](Kotlin Deshabilitar la anulación para duplicar los valores predeterminados).
     */
    override suspend fun eval(script: String, timeoutMs: Long, scopeKey: String?, pluginConfigJson: String?): SkillEngineResult {
        if (JsSandbox.isCircuitBrokenFor(scopeKey)) {
            return SkillEngineResult.Error(message = "JS La zona de pruebas se ha roto, inténtelo de nuevo más tarde.")
        }
        return executionMutex.withLock {
            val result = JsSandbox.execute(script, timeoutMs, scopeKey, pluginConfigJson)
            result.fold(
                onSuccess = { jsResult ->
                    if (jsResult.error != null) {
                        SkillEngineResult.Error(
                            message = jsResult.error,
                            consoleLogs = jsResult.consoleLogs,
                        )
                    } else {
                        SkillEngineResult.Success(
                            valueJson = jsResult.value?.toString() ?: "null",
                            consoleLogs = jsResult.consoleLogs,
                        )
                    }
                },
                onFailure = { e ->
                    Logger.w(TAG, "SkillEngine eval falló: ${e.message}")
                    SkillEngineResult.Error(
                        message = e.message ?: "Excepción de ejecución del motor",
                    )
                },
            )
        }
    }

    override fun interrupt() {
        // WebView V8 Realmente no puedo interrumpir la ejecución de JS
        // Después del tiempo de espera, el lado de Kotlin devuelve un error.JS Todavía se ejecuta hasta su finalización (pero los resultados se descartan)
        Logger.d(TAG, "interrupt() se llama (WebView V8 Realmente no puedo interrumpir, espera el tiempo de espera)")
    }

    companion object {
        private const val TAG = "WebViewSkillEngine"
    }
}
