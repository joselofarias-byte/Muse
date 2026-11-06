package io.zer0.muse.util

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import io.zer0.ai.core.UIMessage
import io.zer0.common.resultOf

/**
 * v1.55: Estimador de tokens reales(Basado en la codificación jtokkit BPE).
 *
 * Utilice la codificación cl100k_base(GPT-4/GPT-3.5 Universales),a otros modelos(DeepSeek/Claude/Gemini)
 * es una aproximación,Pero mucho mejor que la estimación heurística del recuento de caracteres.
 *
 * EncodingRegistry Singleton global,Carga diferida(Inicializado en la primera llamada,aprox. 50-100ms).
 * Recurrir a la estimación heurística cuando falla la inicialización(Chino 1,5 palabras/token,ingles 4 caracteres/token).
 */
object TokenEstimator {

    @Volatile
    private var registry: EncodingRegistry? = null

    @Volatile
    private var encoding: Encoding? = null

    /** Estimación de token de imagen visual(cada uno):Gemini ~258,OpenAI high-detail ~1105;Tomar 1000 es conservador. */
    private const val IMAGE_TOKEN_ESTIMATE_PER_IMAGE = 1000

    private fun getEncoding(): Encoding? {
        if (encoding != null) return encoding
        // v1.80 (L-TE1): Usar en su lugar resultOf,Lanzamiento correcto CancellationException(runCatching tragará)
        return resultOf {
            val r = Encodings.newDefaultEncodingRegistry()
            registry = r
            val e = r.getEncoding(EncodingType.CL100K_BASE)
            encoding = e
            e
        }.getOrNull()
    }

    /**
     * Calcule con precisión la cantidad de tokens en un solo párrafo de texto(BPE codificación).
     * jtokkit Recurrir a la estimación heurística cuando falla la inicialización.
     */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val enc = getEncoding()
        if (enc != null) {
            // v1.80 (L-TE1): Utilice resultOf + getOrNull en su lugar para obtener la verdad(Resultado personalizado Ninguno getOrDefault)
            return resultOf { enc.countTokens(text) }.getOrNull() ?: fallbackEstimate(text)
        }
        return fallbackEstimate(text)
    }

    /**
     * Calcule el número total de tokens definidos por un lote de herramientas.
     *
     * Se debe enviar el esquema completo para cada solicitud. Cuantas más herramientas haya, mayor será el problema. Decenas de herramientas pueden consumir decenas de miles de tokens.
     * Y toma la misma ventana que el historial de conversaciones. —— Sin él, la compresión siempre se activará demasiado tarde.
     *
     * Los resultados se almacenan en caché según la huella digital del contenido: el mismo lote de herramientas se evaluará muchas veces en una sesión,
     * Ejecutar la codificación BPE cada vez es un desperdicio.
     */
    fun estimateToolDefinitions(definitions: List<io.zer0.ai.core.ToolDefinition>): Int {
        if (definitions.isEmpty()) return 0
        val fingerprint = definitions.fold(17) { acc, def ->
            acc * 31 + def.name.hashCode() + def.description.hashCode() + def.parametersJsonSchema.hashCode()
        }
        val hit = cached?.takeIf { it.first == fingerprint }?.second
        return hit ?: computeToolDefinitionTokens(definitions).also { cached = fingerprint to it }
    }

    /** Según la forma realmente enviada por la API: nombre + descripción + esquema de parámetros, y luego agregue la sobrecarga del marco fijo de cada definición. */
    private fun computeToolDefinitionTokens(definitions: List<io.zer0.ai.core.ToolDefinition>): Int {
        val text = buildString {
            definitions.forEach { def ->
                append(def.name).append('\n')
                append(def.description).append('\n')
                append(def.parametersJsonSchema).append('\n')
            }
        }
        return estimate(text) + definitions.size * TOOL_DEFINITION_OVERHEAD_TOKENS
    }

    /** Sobrecubierta de envoltura exterior definida por una única herramienta (name/description/parameters campos fijos distintos de). */
    private const val TOOL_DEFINITION_OVERHEAD_TOKENS = 8

    @Volatile
    private var cached: Pair<Int, Int>? = null

    /**
     * Calcule la cantidad total de tokens en la lista de mensajes + aviso del sistema.
     *
     * @param messages Historial de conversaciones(Contiene user/assistant/system/tool)
     * @param systemPrompt Texto de aviso del sistema(SystemPromptAssembler Después del montaje),Vacío predeterminado
     * @return token numero(BPE Retroceso preciso o heurístico)
     */
    fun estimate(
        messages: List<UIMessage>,
        systemPrompt: String = "",
        cache: MessageTokenCache? = null,
    ): Int {
        var total = estimate(systemPrompt)
        messages.forEach { msg -> total += (cache?.count(msg) ?: countMessage(msg)) }
        // M6: Aproximadamente 4 tokens por mensaje(OpenAI Gastos generales fijos por mensaje del protocolo.:role marca + delimitador)
        total += messages.size * PER_MESSAGE_OVERHEAD_TOKENS
        return total
    }

    /**
     * El token ocupado por un solo mensaje (incluido el proceso de pensamiento,MOOD, reflexión, parámetros de llamada de herramientas e imágenes).
     *
     * Sácalo para dejarlo [MessageTokenCache] Se puede almacenar en caché por mensaje —— Reúna el mismo lote de historial en una sola solicitud
     * Se estimará varias veces (presupuesto total, presupuesto histórico, criterio de exceso de límite, ingresos después de la compresión) y se ejecutará BPE cada vez.
     */
    fun countMessage(msg: UIMessage): Int {
        var total = estimate(msg.content)
        // Incluido en el proceso de pensamiento del asistente/borrador de MOOD/bloque de reflexión(ocupará el contexto)
        msg.reasoning?.let { total += estimate(it) }
        msg.mood?.let { total += estimate(it) }
        msg.reflection?.let { total += estimate(it) }
        // toolCalls Los argumentos de(JSON cuerda)
        msg.toolCalls?.forEach { tc -> total += estimate(tc.arguments) }
        // imagen visual(base64 / url)También retoma el contexto.;Nunca lo pensé antes,conducir al diálogo visual
        // El presupuesto es sistemáticamente bajo → AUTO_COMPRESS/distorsión del círculo ocupado,Un contexto muy largo con imágenes puede llegar a 400.
        val imageCount = msg.imageBase64List.size + msg.imageUrls.size
        if (imageCount > 0) {
            total += imageCount * IMAGE_TOKEN_ESTIMATE_PER_IMAGE
        }
        return total
    }

    private const val PER_MESSAGE_OVERHEAD_TOKENS = 4

    /**
     * retroceso heurístico(Chino 1,5 palabras/token,ingles 4 caracteres/token).
     * Solo se usa si falla la inicialización de jtokkit.
     */
    private fun fallbackEstimate(text: String): Int {
        var chineseCount = 0
        var otherCount = 0
        text.forEach { ch ->
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3000..0x303F) {
                chineseCount++
            } else if (!ch.isWhitespace()) {
                otherCount++
            }
        }
        val cnTokens = (chineseCount / 1.5).toInt()
        val enTokens = otherCount / 4
        return cnTokens + enTokens
    }
}
/**
 * Recuento de tokens de un solo mensaje**Caché de identidad**.
 *
 * `UIMessage` Es una clase de datos inmutable y el recuento de la misma instancia debe ser el mismo; en una asamblea de solicitud,
 * El mismo lote de historia se estimará varias veces:
 *   - presupuesto total (messages)
 *   - Presupuesto histórico (eliminar encabezado SISTEMA)
 *   - Juicio sobre límite (eliminar el prefijo y usarlo nuevamente)
 *   - Regreso después de la compresión (compacted = prefix + Resumen + reciente, donde prefijo/reciente sigue siendo la instancia original)
 *
 * usar `IdentityHashMap` en lugar de `HashMap`: El hash basado en contenido es más rápido y ahorra memoria, pero la igualdad de contenido requiere
 * Campo por campo es más largo que pasar cadena y consideramos que la "misma instancia" es la única condición de reutilización confiable que es más segura.
 * El ciclo de vida es el mismo que el de una transformación. La persona que llama puede simplemente crear uno nuevo y no se compartirá entre solicitudes.
 */
class MessageTokenCache {

    private val counts = java.util.IdentityHashMap<UIMessage, Int>()

    private companion object {
        /** Ver el límite superior [count]: Si excede, se borrará todo el valor. */
        const val MAX_CACHED_MESSAGES = 512
    }

    /** Número de aciertos (para fines de prueba y diagnóstico). */
    var hits: Int = 0
        private set

    /** La cantidad de errores es igual a la cantidad de mensajes que realmente ejecutaron BPE (para pruebas y diagnóstico). */
    var misses: Int = 0
        private set

    /**
     * Obtenga el número de token de un mensaje (ejecute BPE y almacene en caché si falla).
     *
     * **Seguridad del hilo**:`IdentityHashMap` No es un contenedor concurrente y la colocación concurrente dañará su estructura (posiblemente un bucle infinito).
     * P25 Promocionar este caché a `ContextTokenUsage` Después de la instancia compartida a nivel de proceso, la tasa de ocupación se actualiza y se ensambla la palabra de aviso.
     * Vendrá de diferentes hilos al mismo tiempo. —— Entonces esto debe estar bloqueado.
     *
     * El bloqueo solo cubre la lectura y escritura de mapas: los cálculos BPE realmente costosos se colocan fuera del bloqueo para evitar serializar la codificación de diferentes subprocesos.
     * El precio es que el mismo mensaje se puede contar dos veces en condiciones de concurrencia extrema (el resultado es el mismo, pero se cuenta una vez).
     */
    fun count(msg: UIMessage): Int {
        synchronized(counts) {
            counts[msg]?.let {
                hits += 1
                return it
            }
        }
        val computed = TokenEstimator.countMessage(msg)
        synchronized(counts) {
            misses += 1
            counts[msg] = computed
            // Delimitado: la clave por identidad de instancia contendrá el mensaje reemplazado; si se excede el límite superior, se borrará todo el mensaje. Es mejor recalcular que ocupar memoria ilimitada.
            if (counts.size > MAX_CACHED_MESSAGES) counts.clear()
        }
        return computed
    }
}
