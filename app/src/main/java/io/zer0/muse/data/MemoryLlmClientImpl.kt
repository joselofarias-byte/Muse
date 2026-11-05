package io.zer0.muse.data

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.prompt.MemoryPromptContract
import io.zer0.muse.R
import io.zer0.muse.data.routing.UtilityModelHealth
import io.zer0.muse.data.routing.UtilityModelRouter
import io.zer0.muse.data.routing.UtilityTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/**
 * [MemoryLlmClient] La implementación del lado de la aplicación.
 *
 * memory Los módulos solo definen interfaces,no depende directamente de [ChatService] —— De esta manera el módulo de memoria puede
 * Compile por separado. Esta clase está registrada por el módulo de la aplicación. Koin,Puente de llamadas del sistema de memoria LLM a
 * [ChatService.completeText](Ir a configuración del proveedor + protocolo compatible con OpenAI).
 *
 * Aquí se reutiliza la configuración del proveedor de ChatService.:El usuario cambia la página de configuración. API Key / baseUrl,
 * memory La ruta entrará en vigor automáticamente.
 *
 * v1.78: Agregue withTimeout para evitar que la canalización diaria se bloquee permanentemente debido a la suspensión de la llamada LLM.;
 *        Agregue Logger para facilitar la resolución de problemas de fallas de compilación de memoria;model Verificación explícita cuando es nula.
 *
 * v1.0.50 (Reparación del sistema de memoria.): Defensa de llamadas de Memory LLM:
 *  1. **razonamiento estructurado**: El servidor del modelo de inferencia puede poner los resultados JSON en reasoning_content
 *     Y el contenido está vacío. En este momento, solo si la palabra del mensaje requiere JSON y el razonamiento contiene una carga útil JSON.
 *     Sólo uso reasoning;El texto de reflexión pura no se escribirá como texto del cuerpo.,Evite contaminar la memoria.md.
 *  2. **think limpieza de etiquetas**: Para algunos modelos `<think>...</think>` Salida envuelta en etiquetas,En cliente LLM
 *     Limpiar una capa primero(memory modulares [io.zer0.memory.state.CompiledMemoryState] es la segunda manera).
 *  3. **reasoning buffer**: modelo de inferencia +1024 token buffer,Evite que los procesos de pensamiento desplacen los resultados visibles
 *     (uso `withReasoningHeadroom`).
 *  4. **Error de respuesta vacía**: text y el razonamiento está vacío,O el razonamiento solo arroja error al pensar en el contenido,
 *     No dejes que los superiores se aprovechen de ello./Los resultados falsos se juzgan erróneamente como éxito.
 *
 * semántica del tiempo de espera(v1.80 L-MEM1): [withTimeout] Lanzar después del tiempo de espera [kotlinx.coroutines.TimeoutCancellationException]
 * (herencia CancellationException). abajo catch(CancellationException) Será arrojado hacia arriba tal como está.
 */
class MemoryLlmClientImpl(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val context: Context,
) : MemoryLlmClient {
    /** v2.x: Enrutamiento de modelo auxiliar — recuperación de memoria/Compílelo y archívelo como un archivo de \"gran herramienta\".(Déjelo en blanco para conectar en cascada los gadgets/modelo maestro). */
    private val utilityRouter = UtilityModelRouter(settings)

    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        // v2.x: Cuando la persona que llama no especifica explícitamente un modelo,Priorizar el archivo "Big Tools" de enrutamiento del modelo auxiliar(Déjelo en blanco para conectar en cascada los gadgets/modelo maestro).
        // correa vinculante provider,Acceda con precisión a modelos de memoria configurados por el usuario en todos los proveedores.
        val routed = if (model == null) {
            runCatching { utilityRouter.resolve(UtilityTier.LARGE) }.getOrNull()
        } else {
            null
        }
        val resolvedModel = model ?: routed?.second ?: settings.getSelectedModel()
        if (resolvedModel == null) {
            throw IllegalStateException(context.getString(R.string.memory_llm_no_model_configured))
        }
        val resolvedProviderConfig = if (model == null) routed?.first else null
        // v2.6.0: Agregar por configuración regional de la aplicación/Contrato británico(La versión antigua sigue el chino independientemente del idioma.)
        val contractLocale = when (val lang = settings.getLanguageSync()) {
            "system", "" -> java.util.Locale.getDefault().language
            else -> lang
        }
        val effectiveSystemPrompt = MemoryPromptContract.append(systemPrompt, contractLocale)
        val effectiveUserContent = buildString {
            appendLine("<memory_input>")
            appendLine(userContent.trim())
            append("</memory_input>")
        }
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = effectiveSystemPrompt),
            UIMessage(role = MessageRole.USER, content = effectiveUserContent),
        )
        // v1.0.50: reasoning buffer — modelo de inferencia +1024 token,Evite que el pensamiento desplace el resultado visible
        val effectiveMaxTokens = withReasoningBuffer(maxTokens, resolvedModel)
        // M-MEM1: Sobre errores reintentables(429/5xx/SocketTimeoutException/IOException)Hacer reintentos limitados,
        // hasta 3 veces(1 veces original + 2 reintentos),Retroceso exponencial 500 ms/1000 ms.
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return withTimeout(timeoutMs) {
                    val completion = chatService.completeText(
                        messages = messages,
                        model = resolvedModel,
                        providerConfig = resolvedProviderConfig,
                        temperature = temperature,
                        maxTokens = effectiveMaxTokens,
                    )
                    // Sólo aceptar texto real;El contenido del pensamiento de razonamiento puro no puede ingresar al enlace de memoria.
                    val raw = resolveMemoryLlmRawText(
                        systemPrompt = effectiveSystemPrompt,
                        text = completion.text,
                        reasoningContent = completion.reasoningContent,
                    )
                    if (raw == null) {
                        val reasoningLen = completion.reasoningContent?.length ?: 0
                        Logger.w(
                            "MemoryLlmClient",
                            "text está vacío y el razonamiento no está vacío pero el resultado no está estructurado,negar escribir (model=${resolvedModel.id}, reasoning=$reasoningLen chars)",
                        )
                        throw IllegalStateException(
                            context.getString(R.string.memory_llm_empty_response, resolvedModel.id),
                        )
                    }
                    // v1.0.50: LLM Limpieza de etiquetas de pensamiento de capa de cliente(primera línea de defensa)
                    stripThinkTags(raw)
                }
            } catch (e: CancellationException) {
                // M-MEM1: La cancelación de rutina debe lanzarse hacia arriba.,No permitido debajo catch (e: Exception) tragado o registrado como un fracaso
                throw e
            } catch (e: Exception) {
                // degradación de transmisión:Algunas estaciones de transferencia no admiten transmisión sin transmisión /v1/chat/completions(stream=false),
                // Devuelve HTTP 400 invalid_request_error. En este punto, recurra a StreamChat para recopilar el texto completo.
                if (isHttp400(e) && attempt == 1) {
                    Logger.i("MemoryLlmClient", "completeText HTTP 400,Bajar a llamada de transmisión (model=${resolvedModel.id})")
                    return@callText withTimeout(timeoutMs) {
                        val contentSb = StringBuilder()
                        val reasoningSb = StringBuilder()
                        chatService.streamChat(
                            messages = messages,
                            model = resolvedModel,
                            providerConfig = resolvedProviderConfig,
                            temperature = temperature,
                            maxTokens = effectiveMaxTokens,
                            mode = io.zer0.ai.core.ChatRequestMode.UTILITY,
                        ).collect { event ->
                            when (event) {
                                is ChatStreamEvent.ContentDelta -> contentSb.append(event.delta)
                                is ChatStreamEvent.ReasoningDelta -> reasoningSb.append(event.delta)
                                else -> Unit
                            }
                        }
                        // La degradación de streaming también solo acepta texto real/estructurado JSON,No aceptes contenido de pensamiento puro.
                        val raw = resolveMemoryLlmRawText(
                            systemPrompt = effectiveSystemPrompt,
                            text = contentSb.toString(),
                            reasoningContent = reasoningSb.toString(),
                        )
                        if (raw == null) {
                            // v1.0.53: Todavía vacío después de la degradación de la transmisión,Lanzar primero los errores HTTP 400 sin procesar(Si el campo del pensamiento no soporta),
                            // en vez de"respuesta vacía"error — Evite encubrir las verdaderas razones del fracaso(Como por ejemplo UNKNOWN_FIELD: thinking)
                            throw e
                        }
                        stripThinkTags(raw)
                    }
                }
                lastError = e
                if (!isRetryable(e) || attempt == maxAttempts) {
                    UtilityModelHealth.record(
                        providerId = resolvedProviderConfig?.id,
                        modelId = resolvedModel.id,
                        message = e.message ?: e::class.simpleName.orEmpty(),
                    )
                    Logger.w(
                        "MemoryLlmClient",
                        "callText failed (model=${resolvedModel.id}, maxTokens=$effectiveMaxTokens, attempt=$attempt/$maxAttempts)",
                        e,
                    )
                    throw e
                }
                val backoffMs = 500L * (1L shl (attempt - 1)) // 500ms, 1000ms
                Logger.w(
                    "MemoryLlmClient",
                    "callText Fallo reintentable,attempt=$attempt/$maxAttempts,${backoffMs}ms Vuelve a intentarlo más tarde (model=${resolvedModel.id})",
                    e,
                )
                delay(backoffMs)
            }
        }
        throw lastError ?: IllegalStateException(context.getString(R.string.memory_llm_call_text_failed))
    }

    /**
     * v1.0.50: reasoning buffer — uso `withReasoningHeadroom`.
     *
     * modelo de inferencia(ModelAbility.REASONING)El proceso de pensamiento consumirá el presupuesto simbólico.,Si maxTokens se establece demasiado pequeño,
     * La salida visible puede truncarse después de la reflexión. Por lo tanto, sobre la base del presupuesto visible, agregue buffer,Dale al modelo espacio para completar su pensamiento.
     * y generar los resultados completos. Los modelos de no inferencia no agregan zonas de influencia.
     */
    private fun withReasoningBuffer(visibleMaxTokens: Int, model: Model): Int {
        if (visibleMaxTokens <= 0) return visibleMaxTokens
        if (ModelAbility.REASONING !in model.abilities) return visibleMaxTokens
        val buffer = REASONING_BUFFER_TOKENS
        val modelLimit = model.maxOutputTokens?.takeIf { it > 0 } ?: return visibleMaxTokens + buffer
        return maxOf(visibleMaxTokens, minOf(modelLimit, visibleMaxTokens + buffer))
    }

    /**
     * v1.0.50: Quitar `<think>...</think>` / `<thinking>...</thinking>` Bloque de etiquetas.
     *
     * modelo parcial(Especialmente aquellos que viajan a través de estaciones de tránsito.)Envuelva el contenido de pensamiento con etiquetas en lugar del campo de contenido_razonamiento,
     * Si no se limpia,El contenido de la etiqueta se almacenará en la memoria como texto.,Contaminar la memoria.md.
     * Esta es la primera limpieza de la capa de cliente LLM,memory CompiledMemoryState del módulo es el segundo paso.
     */
    private fun stripThinkTags(value: String): String {
        if (value.isEmpty()) return ""
        var s = value
        // bloque cerrado
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // Bloque restante sin cerrar al principio(Algunos modelos solo emiten <think> comienzo)
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
        return s.trim()
    }

    /**
     * M-MEM1: Determine si se puede volver a intentar la excepción.
     * - SocketTimeoutException / IOException:Fallo momentáneo de la red(Conexión interrumpida/Tiempo de espera de lectura y escritura)
     * - El mensaje comienza con "HTTP 429" comienzo:Límite de corriente del disparador(Provider El mensaje de error HTTP lanzado parece "HTTP 429 [Límite de corriente del disparador]: ...")
     * - coincidencia de mensajes "HTTP 5xx":Error temporal del lado del servidor
     */
    private fun isRetryable(e: Throwable): Boolean {
        if (e is java.net.SocketTimeoutException) return true
        if (e is java.io.IOException) return true
        val msg = e.message ?: return false
        if (msg.startsWith("HTTP 429")) return true
        if (Regex("HTTP 5\\d\\d").containsMatchIn(msg)) return true
        return false
    }

    /**
     * Determinar si la excepción es HTTP 400(Relay no admite solicitudes que no sean de transmisión, etc.).
     */
    private fun isHttp400(e: Throwable): Boolean {
        val msg = e.message ?: return false
        return msg.startsWith("HTTP 400")
    }

    private companion object {
        /** v1.0.50: Número de tokens de búfer del modelo de inferencia. */
        private const val REASONING_BUFFER_TOKENS = 1024
    }
}

/**
 * Seleccione el texto final de la Memoria LLM.
 *
 * reglas:
 *  - Si el texto no está vacío, siempre se le dará prioridad al texto.;
 *  - Cuando el texto está vacío,Se usa solo si la palabra del mensaje requiere JSON y el razonamiento contiene una carga útil JSON. reasoning;
 *  - Devolución en otros casos null,Manejado como un error por la persona que llama,Evite escribir el pensamiento modelo en la memoria.
 */
internal fun resolveMemoryLlmRawText(systemPrompt: String, text: String, reasoningContent: String?): String? {
    val visible = text.trim()
    if (visible.isNotBlank()) return visible

    val reasoning = reasoningContent?.trim().orEmpty()
    if (reasoning.isBlank()) return null
    if (!JSON_OUTPUT_PROMPT_RE.containsMatchIn(systemPrompt)) return null
    if (!JSON_PAYLOAD_RE.containsMatchIn(reasoning)) return null
    return reasoning
}

private val JSON_OUTPUT_PROMPT_RE = Regex(
    """JSON\s*(?:matriz|objeto)|(?:matriz|objeto)\s+JSON|estricto\s*JSON|strict\s*JSON|JSON\s*(?:array|object)|Output\s+Format""",
    RegexOption.IGNORE_CASE,
)

private val JSON_PAYLOAD_RE = Regex("""[\[{][\s\S]*[\]}]""")
