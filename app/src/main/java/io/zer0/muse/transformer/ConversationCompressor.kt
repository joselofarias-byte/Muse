package io.zer0.muse.transformer

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.routing.UtilityModelRouter
import io.zer0.muse.data.routing.UtilityTier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * compresor de diálogo — Resumen de LLM paralelo fragmentado,Utilice modelos económicos independientes.
 *
 * ideas de diseño:
 *  - Pulsa el mensaje histórico a comprimir [CHUNK_SIZE] Cortar en pedazos
 *  - Cada bloque llama a LLM de forma independiente para generar un resumen,Múltiples bloques en paralelo(coroutineScope + async)
 *  - Utilice independiente"modelo comprimido"([compressModelIdFlow],Configurable por el usuario para un modelo más económico.),
 *    Evita ocupar la cuota del modelo de diálogo principal.,Al mismo tiempo, evite el retraso de la primera palabra causado por el bloqueo de sincronización en la canalización.
 *
 * con [ContextCompressTransformer] relación:
 *  - [ContextCompressTransformer] Responsable de la lógica de canalización, como juicio de umbral/omisión de prefijo/retención de prioridad, etc.
 *  - Esta categoría sólo es responsable de"Modelo paralelo fragmentado + independiente"generación de resumen
 *  - [ContextCompressTransformer] Delega esta clase en transform para completar la compresión real
 *
 * estrategia de degradación(v2.3.2 de):
 *  - La compresión de un solo bloque falló/El resumen está vacío → [compressChunk] Regresar null(nunca regresar"Error al generar el resumen"texto de marcador de posición)
 *  - **Cualquier pieza falla → [compress] Regresar en su totalidad null**,por la persona que llama [ContextCompressTransformer] Conserva el texto original,
 *    No se realiza compresión en esta ronda.(Preferiría que el contexto fuera más largo.,El modelo tampoco puede considerar el marcador de posición como la historia que ha visto.)
 *  - completeText Todavía recurrir al fracaso streamChat(con [ContextCompressTransformer] La implementación original es consistente.)
 */
class ConversationCompressor(
    private val chatService: ChatService,
    private val settingsRepository: SettingsRepository,
) {
    /** v2.x: Enrutamiento de modelo auxiliar — Comprimido en un archivo \"Big Tools\"(Déjelo en blanco para conectar en cascada los gadgets,Déjelo en blanco nuevamente para regresar al modelo de diálogo principal.). */
    private val utilityRouter = UtilityModelRouter(settingsRepository)

    companion object {
        private const val TAG = "ConversationCompressor"

        /** Número máximo de mensajes por bloque(Si excede el límite, se dividirá en varios bloques paralelos.). */
        private const val CHUNK_SIZE = 256

        /** Temperatura de compresión(La baja temperatura garantiza que el resumen sea estable y no fabricado.). */
        private const val COMPRESS_TEMPERATURE = 0.3f

        /** La cantidad máxima de tokens en un resumen de un solo bloque. */
        private const val COMPRESS_MAX_TOKENS = 1000

        /** Número máximo de caracteres en un solo mensaje enviado a LLM(Truncar si se excede,Alineado con ContextCompressTransformer). */
        private const val MAX_MSG_CHARS = 1500

        /** v1.0.51: Número máximo de bloques de compresión paralelos — Evite el bombardeo de concurrencia a la API cuando las conversaciones largas reduzcan grandes partes. */
        private const val MAX_CONCURRENT_CHUNKS = 3

        /** v2.3.2: Valor predeterminado conservador cuando se desconoce la ventana de contexto(token). */
        private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_000

        /** v2.3.2: Los límites inferior y superior del presupuesto de caracteres del mensaje de un solo bloque. */
        private const val MIN_CHUNK_CHARS = 6_000
        private const val MAX_CHUNK_CHARS = 48_000

        /** v2.3.2: El presupuesto de entrada como proporción de la ventana de contexto del modelo.(El resto se deja para el resumen y las instrucciones.). */
        private const val CHUNK_BUDGET_RATIO = 0.4

        /**
         * v2.3.2: Compresión de bloque único del presupuesto de caracteres del mensaje.
         *
         * La implementación original solo corta en pedazos según la cantidad de tiras.(Máximo 256 artículos × 1500 caracteres por línea ≈ **38.4 esvástica**),
         * Una vez que se activa la compresión durante una sesión larga, inevitablemente excederá la ventana del modelo de compresión.(Aparece como error 400 o se trunca el resumen.).
         * Ahora haga clic en la ventana de contexto del modelo. [CHUNK_BUDGET_RATIO] estimación(Chino alrededor de 1 carácter ≈ 1 token),
         * Se utiliza cuando la ventana es desconocida. [DEFAULT_CONTEXT_WINDOW_TOKENS] Sea conservador.
         */
        internal fun chunkBudgetChars(contextWindowTokens: Int?): Int {
            val window = contextWindowTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
            return (window * CHUNK_BUDGET_RATIO).toInt().coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
        }

        /**
         * Divida la lista de mensajes en varios fragmentos:**Número de artículos([CHUNK_SIZE])con presupuesto de personaje([maxCharsPerChunk])doble vínculo**.
         *
         * v2.3.2: La implementación original solo corta en pedazos según la cantidad de tiras.,La mayoría de los bloques individuales 256×1500 ≈ 38.4 esvástica,Las sesiones largas inevitablemente comprimen demasiado la ventana del modelo.;
         * Ahora dividido según los caracteres acumulados.(Pulsación única [MAX_MSG_CHARS] Cargo después del truncamiento,Consistente con la longitud del mensaje realmente enviado).
         * Cuando un solo mensaje excede el presupuesto, se separa en bloques.(ya no puedo cortar,La longitud ha sido [MAX_MSG_CHARS] Ten todo en cuenta).
         *
         * @param maxCharsPerChunk Presupuesto de caracteres de un solo bloque(por [chunkBudgetChars] Calcular según la ventana del modelo.)
         */
        internal fun chunkMessages(messages: List<UIMessage>, maxCharsPerChunk: Int): List<List<UIMessage>> {
            if (messages.isEmpty()) return emptyList()
            val result = mutableListOf<List<UIMessage>>()
            var current = mutableListOf<UIMessage>()
            var currentChars = 0
            messages.forEach { msg ->
                val chars = minOf(msg.content.length, MAX_MSG_CHARS)
                val shouldSplit = current.size >= CHUNK_SIZE || currentChars + chars > maxCharsPerChunk
                if (current.isNotEmpty() && shouldSplit) {
                    result.add(current)
                    current = mutableListOf()
                    currentChars = 0
                }
                current.add(msg)
                currentChars += chars
            }
            if (current.isNotEmpty()) result.add(current)
            return result
        }
    }

    /** v1.0.51: Semáforo limitador de concurrencia,Alineado con DeepMemoryProcessor/MemoryTicker(límite superior 3). */
    private val chunkSemaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    /**
     * Comprimir el historial de conversaciones — Paralelo fragmentado + modelo económico independiente.
     *
     * @param toCompress Lista de mensajes a comprimir(La persona que llama ha completado la omisión de prefijo/preservación de prioridad y otros filtros.)
     * @param instruction Instrucciones adicionales para esta compresión.(tiene prioridad sobre el nivel de configuración customCompressPrompt,Se utiliza para el diálogo de compresión manual.)
     * @return Lista de texto resumido(Cada bloque corresponde a un resumen.);**Regresa cuando falla algún bloque null**,
     *         La persona que llama toma la rama de degradación en consecuencia.(Mantener el texto original.),En lugar de tratar el marcador de posición de falla como un resumen legal
     */
    suspend fun compress(
        toCompress: List<UIMessage>,
        instruction: String? = null,
        /**
         * Resumen de la edición anterior. No vacío significa que esta vez se trata de una actualización incremental del resumen existente, en lugar de resumir desde cero.
         *
         * Al comprimir repetidamente, si no lo retroalimenta, el modelo sólo puede "repetir conscientemente" los primeros hechos.——
         * El resumen del historial que ve es un mensaje del sistema y no se fusionará deliberadamente.
         * Entonces, cada vez que lo presionas, el contenido más antiguo se vuelve más claro.
         */
        previousSummary: String? = null,
    ): List<String>? {
        if (toCompress.isEmpty()) return emptyList()

        // v2.3.2: Analizar el modelo una vez(Originalmente cada bloque se analizaba una vez.),Y calcule el presupuesto de caracteres de un solo bloque en consecuencia ——
        // Evite largas sesiones cortando grandes trozos de 380.000 caracteres directamente en la ventana del modelo ultracomprimido.
        val (providerConfig, model) = resolveCompressModel()
        val budgetChars = chunkBudgetChars(model?.contextWindow)

        // fragmentación(Límite superior del número de artículo + doble restricción del presupuesto de caracteres)
        val chunks = chunkMessages(toCompress, budgetChars)
        Logger.i(
            TAG,
            "compress: ${toCompress.size} Los mensajes se dividen en ${chunks.size} bloque de compresión paralela" +
                "(presupuesto de bloque único $budgetChars personaje, ventana ${model?.contextWindow ?: "desconocido"})",
        )

        // v1.0.52: Leer compresión definida por el usuario prompt(null/Una cadena vacía indica el valor predeterminado)
        // H10: Esta instrucción adicional tiene prioridad.,De lo contrario, recurra a la personalización del nivel de configuración prompt
        val customPrompt = instruction
            ?.takeIf { it.isNotBlank() }
            ?: resultOf { settingsRepository.customCompressPromptFlow.first() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        if (customPrompt != null) {
            Logger.i(TAG, "compress: Utilice directivas de compresión personalizadas")
        }

        // Comprimir cada bloque en paralelo(v1.0.51: Limitar la concurrencia con Semaphore,Evite el bombardeo LLM de una gran cantidad de bloques simultáneamente API)
        val results =
            coroutineScope {
                chunks.map { chunk ->
                    async {
                        chunkSemaphore.withPermit {
                            compressChunk(chunk, customPrompt, model, providerConfig, previousSummary)
                        }
                    }
                }.let { deferredList ->
                    deferredList.map { it.await() }
                }
            }
        // v2.3.2: Cualquier pieza falla(Regresar null)Es decir, el juicio global falla. —— Preferiría no hacer compresión en esta ronda.,
        // Tampoco se puede permitir que el modelo"Error al generar el resumen"El marcador de posición se trata como un resumen del historial que ya ha visto.(Eso es amnesia silenciosa).
        val failed = results.count { it == null }
        return if (failed > 0) {
            Logger.w(TAG, "compress: $failed/${results.size} Error en el resumen del bloque, Rebaja general(Regresar null)")
            null
        } else {
            results.filterNotNull()
        }
    }

    /**
     * Comprime mensajes de un solo bloque en texto resumido.
     *
     * v2.3.2: Retorno del fracaso **null**(nunca regresar"Error al generar el resumen"texto de marcador de posición). El texto del marcador de posición se superpondrá
     * Insertar solicitud como resumen legal,Hacer que el modelo piense que ha visto una historia que nunca antes había visto.;Devolver nulo por [compress]
     * Agregado al fracaso general,En consecuencia, el llamante conserva el texto original. No se lanza ninguna excepción excepto cuando se cancela la rutina.,Asegúrese de que los procesos paralelos no se interrumpan debido a una falla de un solo bloque.
     *
     * v1.0.52: apoyo [customPrompt] parámetros — Los usuarios pueden anular las directivas de compresión predeterminadas en la configuración.
     * Utilice instrucciones definidas por el usuario para reemplazar las instrucciones estructuradas predeterminadas cuando no estén vacías.,El historial de conversaciones todavía se adjunta en el mismo formato.
     *
     * @param chunk Bloque de mensajes a comprimir
     * @param customPrompt Instrucciones de compresión definidas por el usuario(null/Una cadena vacía indica la directiva estructurada predeterminada)
     * @param model modelo comprimido(v2.3.2: por [compress] Páselo después del análisis unificado una vez,Ya no se analiza cada bloque una vez)
     * @param providerConfig con [model] Vinculación del proveedor complementario
     * @return texto resumido;Devuelto en caso de error o si el resumen está vacío null
     */
    private suspend fun compressChunk(
        chunk: List<UIMessage>,
        customPrompt: String? = null,
        model: Model? = null,
        providerConfig: ProviderConfig? = null,
        previousSummary: String? = null,
    ): String? {
        val prompt = buildString {
            if (!customPrompt.isNullOrBlank()) {
                // v1.0.52: Instrucciones de compresión definidas por el usuario
                appendLine(customPrompt)
            } else {
                // Directivas de compresión estructuradas predeterminadas
                appendLine("Condense el historial de conversaciones a continuación en un resumen conciso,Mantener información clave(hechos/toma de decisiones/Preferencias de usuario).")
                appendLine("Organice el resumen en la siguiente estructura.(Utilice el formato de viñetas para cada sección.,Una línea por punto;No hay contenido en una sección y se puede omitir.):")
                appendLine("- Key topics: ¿Qué temas se discutieron?,y por qué es importante(Partículas gruesas,Sin cuentas corrientes)")
                appendLine("- Decisions: ¿Qué decisiones se tomaron?,y las razones detrás")
                appendLine("- Current work: Trabajo en curso y su estado actual.")
                appendLine("- Next steps: Próximos pasos a seguir, cuestiones no resueltas, asuntos que requieren seguimiento")
                appendLine("- User preferences: Preferencias o limitaciones expresadas por el usuario.(Si alguno)")
                appendLine("- No inventes cosas que no fueron mencionadas.")
                appendLine("- La extensión total no debe exceder las 800 palabras.")
            }
            if (!previousSummary.isNullOrBlank()) {
                appendLine()
                appendLine("Ya tengo un resumen.(de compresión anterior,debe actualizarse en base a ello,en lugar de recoger otro):")
                appendLine(previousSummary.trim())
                appendLine()
                appendLine("Requisitos de fusión:")
                appendLine("- Se conservarán los hechos y decisiones de los resúmenes existentes.,A menos que un nuevo diálogo lo anule explícitamente")
                appendLine("- Los avances logrados por el nuevo diálogo deben anular las antiguas conclusiones.(Si el estado cambia, escriba un nuevo estado)")
                appendLine("- Hay contenido en ambos lados. No lo escribas dos veces.,Combina uno")
            }
            appendLine()
            appendLine("Historial de conversaciones:")
            chunk.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "Usuario"
                    MessageRole.ASSISTANT -> "asistente"
                    MessageRole.SYSTEM -> "sistema"
                    MessageRole.TOOL -> "Herramientas"
                }
                val raw = msg.content
                // v2.x: Mismo calibre que ContextCompressTransformer —— Mantenga el principio y el final, y deje que la conclusión final entre en el resumen.
                val text = io.zer0.common.TextTruncation.headTailText(raw, MAX_MSG_CHARS)
                appendLine("[$role] $text")
            }
        }

        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "Eres el asistente de compresión de conversaciones.,Genere un resumen chino conciso."),
            UIMessage(role = MessageRole.USER, content = prompt),
        )

        return try {
            // H-COMP2 / L-COMP7: Utilice resultOf en su lugar runCatching,no tragar CancellationException
            // completeText El valor predeterminado es el modo UTILIDAD(pensamiento automático),Adecuado para tareas de resumen en segundo plano
            val completion = resultOf {
                chatService.completeText(
                    messages = messages,
                    model = model,
                    providerConfig = providerConfig,
                    temperature = COMPRESS_TEMPERATURE,
                    maxTokens = COMPRESS_MAX_TOKENS,
                    mode = ChatRequestMode.UTILITY,
                )
            }.onError { msg, t ->
                Logger.w(TAG, "completeText falló,revertir streamChat: $msg", t)
            }.getOrNull() ?: run {
                // Ten todo en cuenta:colección de transmisión(Acceda aquí solo para errores que no sean CancellationException)
                val sb = StringBuilder()
                chatService.streamChat(
                    messages = messages,
                    model = model,
                    providerConfig = providerConfig,
                    temperature = COMPRESS_TEMPERATURE,
                    maxTokens = COMPRESS_MAX_TOKENS,
                    mode = ChatRequestMode.UTILITY,
                ).collect { ev ->
                    if (ev is ChatStreamEvent.ContentDelta) sb.append(ev.delta)
                }
                io.zer0.ai.core.ChatCompletion(text = sb.toString())
            }
            // v1.0.74 fix: despegar <think> etiqueta de inferencia,Evite que los pensamientos se mezclen en resúmenes comprimidos
            // v2.3.2: Los resúmenes vacíos también se tratan como fracasos.(Regresar null),ya no se usa"El resumen está vacío"Resumen de suplantación de marcador de posición
            completion.text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                .takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelar sin tragar la corrutina,transmisión directa
            throw e
        } catch (t: Throwable) {
            Logger.e(TAG, "compressChunk failed (Regresar null,degradado por la persona que llama)", t)
            null
        }
    }

    /**
     * Analice el modelo y ProviderConfig utilizados para la compresión.
     *
     * v2.x: Cambie al archivo "Big Tools" para el enrutamiento del modelo auxiliar — Después de que el usuario esté vinculado a la configuración,Tarea de compresión usando banda. provider
     * La unión precisa de(Se corrigió la falla en v1.0.62 cuando la coincidencia entre proveedores por identificación llega a canales no relacionados);Cascada cuando se deja vacío
     * Modelo de dispositivo,Déjelo vacío y devuelva nulo para que ChatService utilice el modelo actual del proveedor activo.
     */
    private suspend fun resolveCompressModel(): Pair<ProviderConfig?, Model?> {
        val routed = utilityRouter.resolve(UtilityTier.LARGE) ?: return null to null
        return routed.first to routed.second
    }
}
