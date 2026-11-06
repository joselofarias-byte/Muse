package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.MessageTokenCache
import io.zer0.muse.util.TokenEstimator

/**
 * El calibre combinado de ocupación del contexto:provider Se da prioridad al uso medido real y la cola que no está cubierta por la medición real se completa con estimaciones locales.
 *
 * Por qué fusionarse: cada ronda de solicitud del proveedor devolverá el uso,`promptTokens` si**Lo que realmente vio esta ronda de modelos
 * entrada**(system prompt, historial, definiciones de herramientas e imágenes), que es mucho más preciso que la estimación de BPE local. Y la tasa de ocupación es
 * Comprime automáticamente las entradas para activar y enviar alertas previas. Usar sólo estimaciones durante mucho tiempo será sistemáticamente bajo. —— Un nivel bajo se manifiesta directamente como"Presione contra la pared".
 *
 * Método de fusión: tomar la última tira `promptTokens` El mensaje del asistente es como"nivel del agua", él y sus partes anteriores se expresan en
 * El valor medido real, los mensajes posteriores (mensajes de usuario recién ingresados, resultados de herramientas de esta ronda, etc.) aún se estiman localmente y los dos se suman.
 *
 * Ruta degenerada: cuando no hay ningún mensaje con uso en la sesión, el resultado es el mismo que la estimación local pura byte a byte. —— No se agregan nuevos modos de falla,
 * Datos de sesión antiguos (usage El campo es NULL) también sigue esta ruta.
 */
internal object ContextTokenUsage {

    /**
     * El límite inferior de la relación creíble entre el valor combinado medido y la estimación general: valor combinado × Este número es aún menor que la estimación y se considera que la medición real no es confiable.
     *
     * El motivo para elegir 2 (es decir, descartar si es menos de la mitad de la estimación): la diferencia legal no puede ser tan grande. Los valores medidos significativamente más bajos suelen deberse a dos tipos de situaciones:
     * —— tránsito/La capa de compatibilidad devuelve un uso recortado o de solo salida;prompt Después del precio del segmento de caché `promptTokens` solo
     * La sección corta que se perdió (Anthropic Enumere cache_read/cache_creation por separado en `cachedTokens`,
     * Este campo, por definición, no está incluido en el total). Este tipo de número hará que la tasa de ocupación sea baja, y el costo de una tasa de ocupación baja es perder la ventana de compresión.
     * Realizar una intercepción fuerte antes de enviar; el costo de informar alto es solo un poco más de historia. Entonces la estimación es el límite inferior aquí.
     */
    private const val MIN_TRUST_RATIO = 2

    /**
     * Subíndice de línea de nivel de agua: medición real de la última franja `promptTokens`(>0) mensaje del asistente; de ​​lo contrario, se devuelve -1.
     *
     * Sólo reconocer assistant —— provider El uso sólo está vinculado a los metadatos de generación del mensaje del asistente; se está generando en forma de transmisión y aún no se ha completado.
     * usage El mensaje del marcador de posición se omitirá y su contenido se dejará en el segmento final según lo estimado.
     */
    fun lastMeasuredIndex(messages: List<UIMessage>): Int {
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role != MessageRole.ASSISTANT) continue
            if ((message.promptTokens ?: 0) > 0) return index
        }
        return -1
    }

    /**
     * Ocupación de medición real de un solo mensaje = prompt + completion, con [io.zer0.ai.core.UsageTokens.total] Mismo calibre.
     *
     * Sin superposición `cachedTokens`:OpenAI lado en caché es `promptTokens` Un subconjunto de , superposición se repite contando;
     * La definición del campo también lo establece."Solo para visualización de información, no incluido en total".Anthropic Por lo tanto, el lado será más bajo, debido a
     * [blend] El límite inferior de la estimación está al alcance.
     */
    fun measuredTokens(message: UIMessage): Int = (message.promptTokens ?: 0) + (message.completionTokens ?: 0)

    /**
     * Combine la medición real y la estimación en la ocupación final (aritmética pura para facilitar una medición única para bloquear el calibre).
     *
     * @param measured Nivel de agua y ocupación real anterior
     * @param tailEstimate Estimación local de mensajes después de la marca de agua (excluyendo el aviso del sistema, que ya está en [measured] Li)
     * @param fullEstimate Todo el contexto (system prompt + estimación local de todos los mensajes), utilizado como referencia de credibilidad y límite inferior
     */
    fun blend(measured: Int, tailEstimate: Int, fullEstimate: Int): Int {
        if (measured <= 0) return fullEstimate
        val merged = measured + tailEstimate
        return if (merged * MIN_TRUST_RATIO < fullEstimate) fullEstimate else merged
    }

    /**
     * Calcule la ocupación del contexto en un solo paso.
     *
     * CPU Denso (la codificación BPE se usa internamente), la persona que llama debe colocar [kotlinx.coroutines.Dispatchers.Default] ejecutar en.
     * Cuando hay una medición real, debes pagar una estimación adicional por el segmento de la cola. El segmento final generalmente solo contiene unos pocos mensajes y el costo es mucho menor que recalcular todo el historial.
     */
    /**
     * Reutilizar una caché de recuentos por instancia de mensaje (2026-11-01 Mejoras de rendimiento P25).
     *
     * Medición real:200 historial + una reevaluación de ocupación solicitada por el sistema **10.29 ms**(JVM), y esta ruta durante la transmisión
     * Se activa una vez cada 400 caracteres y nuevamente en cada ronda de ensamblaje de palabras clave. Durante la transmisión, sólo cambia realmente el último mensaje.
     * Los 199 restantes vuelven a ejecutar BPE cada vez.
     *
     * La caché está codificada por la identidad de la instancia y está limitada (se borra por completo cuando se excede el límite superior), de modo que los mensajes reemplazados no permanecerán indefinidamente.
     */
    private val tokenCache: MessageTokenCache = MessageTokenCache()

    /** El último aviso del sistema calculado y su número de token (reutilizado directamente en la misma instancia). */
    @Volatile
    private var cachedPrompt: String? = null

    @Volatile
    private var cachedPromptTokens: Int = 0

    private fun promptTokens(systemPrompt: String): Int {
        if (systemPrompt.isEmpty()) return 0
        if (systemPrompt === cachedPrompt) return cachedPromptTokens
        val tokens = TokenEstimator.estimate(systemPrompt)
        cachedPrompt = systemPrompt
        cachedPromptTokens = tokens
        return tokens
    }

    fun estimate(messages: List<UIMessage>, systemPrompt: String = ""): Int {
        // Las indicaciones del sistema son las mismas en todas las rondas, pero el BPE debe realizarse nuevamente cada vez (la medición real representa la mayor parte de los gastos generales restantes).
        // Un solo caché es suficiente: es la misma instancia de cadena y la comparación se realiza con === En lugar de iguales, acertar tiene coste cero.
        val fullEstimate = promptTokens(systemPrompt) + TokenEstimator.estimate(messages, cache = tokenCache)
        val measuredIndex = lastMeasuredIndex(messages)
        if (measuredIndex < 0) return fullEstimate
        val measured = measuredTokens(messages[measuredIndex])
        if (measured <= 0) return fullEstimate
        // system prompt Ya incluido en los tokens de aviso medidos reales, si la sección de cola se incluye nuevamente, el conteo se repetirá.
        val tailEstimate = TokenEstimator.estimate(
            messages.subList(measuredIndex + 1, messages.size),
            cache = tokenCache,
        )
        return blend(measured, tailEstimate, fullEstimate)
    }
}
