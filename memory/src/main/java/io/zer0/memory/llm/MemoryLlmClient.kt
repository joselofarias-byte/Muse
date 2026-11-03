package io.zer0.memory.llm

import io.zer0.ai.core.Model

/**
 * memory Abstracción de cliente LLM para módulos(callText llamada de texto).
 *
 * memory El módulo no depende directamente del módulo ai. [io.zer0.ai.core.Provider],
 * En cambio, el módulo de la aplicación lo inyecta e implementa a través de esta interfaz.(confiado a [io.zer0.ai.ChatService.completeText]).
 *
 * De esta manera el módulo de memoria permanece independiente.,Las pruebas se pueden compilar por separado.
 */
interface MemoryLlmClient {

    /**
     * llamada sin transmisión LLM,Devuelve el texto completo.
     *
     * @param systemPrompt system Contenido del mensaje
     * @param userContent user Contenido del mensaje
     * @param model modelo objetivo,null La implementación utiliza el primer modelo en la configuración del proveedor.
     * @param temperature Temperatura de muestreo(0~2),memory Camino arreglado 0.3
     * @param maxTokens Límite superior de salida(memory Las rutas se asignan por tipo de llamada.: rolling 150-750 / compile 300-600 / fact 4096)
     * @param timeoutMs v1.78: Tiempo de espera en milisegundos,30 por defecto. El tiempo de espera genera TimeoutCancellationException.
     *                  Evite que las llamadas de LLM se cuelguen y provoquen que la canalización diaria se bloquee permanentemente.
     * @return texto completo
     * @throws Exception red/HTTP/error de análisis/tiempo de espera
     */
    suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model? = null,
        temperature: Float = 0.3f,
        maxTokens: Int = 600,
        timeoutMs: Long = 30_000L,
    ): String
}
