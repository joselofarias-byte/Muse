package io.zer0.muse.transformer

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.session.WarmupHistory
import io.zer0.muse.util.MessageTokenCache
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

internal const val CONTEXT_COMPRESSED_MARKER = "[COMPRESSED]"

/**
 * Mantenga los mensajes recientes y los mensajes prioritarios en el orden histórico original.
 *
 * Las llamadas a herramientas y los resultados pueden cruzar el punto de corte de compresión general; primero combine los dos conjuntos de retención y luego regrese al filtro de lista original,
 * Puede conservar la relación adyacente completa y evitar empalmar los resultados de la HERRAMIENTA en assistant(tool_call) frente.
 */
internal fun retainMessagesInOriginalOrder(
    messages: List<UIMessage>,
    recent: List<UIMessage>,
    priorityMessages: List<UIMessage>,
): List<UIMessage> {
    val retainedIds = (recent + priorityMessages).map { it.id.toString() }.toSet()
    return messages.filter { it.id.toString() in retainedIds }
}

/** True when the complete payload estimate exceeds the configured compression budget. */
internal fun isOverContextCompressionBudget(messages: List<UIMessage>, tokenBudget: Int): Boolean =
    tokenBudget > 0 && TokenEstimator.estimate(messages) > tokenBudget

/** Igual que el anterior, pero la persona que llama ha calculado la cantidad de tokens (el mismo lote de mensajes en una solicitud solo debe estimarse una vez). */
internal fun isOverContextCompressionBudget(tokens: Int, tokenBudget: Int): Boolean =
    tokenBudget > 0 && tokens > tokenBudget

/**
 * Compresión de contexto Transformer(Phase 8.1 H1).
 *
 * Cuando el historial de mensajes supera el umbral,Comprimir el mensaje antiguo anterior en un resumen del SISTEMA,
 * Mantenga los N elementos más recientes como están,Evite la explosión de tokens causada por un contexto demasiado largo.
 *
 * comportamiento(v2.3.2: Número de artículos y presupuesto simbólico**doble calibre**gatillo,Ver abajo):
 *  - messages.size <= threshold(Predeterminado 20)**y** Número total de fichas <= compress_char_budget(Sin límite por defecto)→ Sin compresión
 *  - excede el umbral:
 *    1. Saltar encabezado de mensajes SISTEMA consecutivos(system prompt / RAG / webSearch / lorebook Esperando inyección dinámica prefix)
 *    2. Tome la parte después del prefijo hasta antes de reciente como la parte a comprimir(Saltar mensajes de resumen comprimidos)
 *    3. Llame a LLM para generar un resumen
 *    4. Reemplazado con un mensaje de SISTEMA("[COMPRESSED] Resumen del diálogo histórico.\n\n...")
 *    5. Deje intacta la última barra KeepRecent
 *    6. v2.3.2: Si el resultado no es más corto que el texto original(Uso intensivo de herramientas/El resumen puede ser más largo si el intervalo compresible es demasiado corto)→ Mantener el texto original.
 *
 * por [context.extra] parámetros de control:
 *  - "compress_enabled" (Boolean, Predeterminado false): Ya sea para habilitar la compresión
 *  - "compress_threshold" (Int, Predeterminado 20): Umbral de recuento de mensajes que desencadena la compresión
 *  - "compress_keep_recent" (Int, Predeterminado 15): Número de mensajes recientes retenidos después de la compresión
 *  - "compress_char_budget" (Int, Predeterminado 0=Sin límite;Se conservan los nombres clave históricos): El presupuesto simbólico que desencadena la compresión(La persona que llama viene dada por la ventana de contexto del modelo.;
 *    Las sesiones de mensajes largas dependen de que se active con anticipación —— A juzgar sólo por el número de elementos"20 Artículo"Puede haber excedido la ventana)
 *
 * Nota: Este transformador llamará LLM(solicitud de red),Lleva más tiempo.
 *       En caso de fallo, baje a"Truncar"(descartar mensajes antiguos,Insertar etiqueta mensaje SISTEMA informando al modelo que el historial está truncado).
 */
/** Si ya existe un resumen comprimido en el historial. */
/** Representa la lista de archivos modificados como una sección al final del resumen; si no hay cambios, se devuelve una cadena vacía. */
private fun buildFileSection(messages: List<UIMessage>): String {
    val files = collectTouchedFiles(messages)
    return if (files.isEmpty()) "" else "\n\narchivos modificados:\n" + files.joinToString("\n") { "- $it" }
}

/** El parámetro puede llevar el nombre clave de la ruta de destino. Los nombres de las diferentes herramientas no son uniformes y cubren los comunes. */
private val FILE_PATH_PATTERN = Regex("\"(?:path|file_path|filePath|target|file)\"\\s*:\\s*\"([^\"]{1,300})\"")

/** Cambiará las características del nombre de la herramienta del disco. Solo reconózcalos y no recuerde la ruta de la herramienta de lectura. */
private val MUTATING_TOOL_HINTS = listOf(
    "write", "create", "edit", "delete", "remove", "move", "rename", "mkdir", "copy",
    "apply", "replace", "patch", "save", "upload",
)

/** Límite de lista. Escribir demasiado estirará el resumen y se perderá el beneficio neto de la compresión. */
private const val MAX_LISTED_FILES = 40

/**
 * Extraiga rutas de archivos modificadas de llamadas a herramientas en rangos comprimidos.
 *
 * Sólo se analizan las llamadas con semántica modificada en los nombres de las herramientas y la lectura de archivos no cuenta. —— De lo contrario, la lista se llenará con el historial de navegación,
 * Lo que realmente ha cambiado queda eliminado.
 */
private fun collectTouchedFiles(messages: List<UIMessage>): List<String> = messages
    .flatMap { it.toolCalls.orEmpty() }
    .filter { call -> MUTATING_TOOL_HINTS.any { hint -> call.name.contains(hint, ignoreCase = true) } }
    .flatMap { call -> FILE_PATH_PATTERN.findAll(call.arguments).map { it.groupValues[1] } }
    .distinct()
    .take(MAX_LISTED_FILES)

private fun hasCompressedSummary(messages: List<UIMessage>): Boolean = messages.any {
    it.role == MessageRole.SYSTEM && it.content.startsWith(CONTEXT_COMPRESSED_MARKER)
}

/**
 * Compresión repetida de la marca de agua.
 *
 * Se presionó una vez pero aún se juzga de acuerdo con el umbral original, lo que se activará hacia adelante y hacia atrás cerca del valor crítico:
 * Después de presionarlo, simplemente cayó y en la siguiente ronda cruzó la línea nuevamente y fue presionado nuevamente. Entonces levante ambos extremos hasta la mitad.
 */
private fun overWatermark(messageCount: Int, historyTokens: Int, threshold: Int, tokenBudget: Int): Boolean {
    val overCount = messageCount > threshold + threshold / 2
    val overBudget = tokenBudget > 0 && historyTokens > tokenBudget + tokenBudget / 2
    return overCount || overBudget
}

/** El texto resumido comprimido ya existe en el historial; si no hay ninguno, se devuelve nulo. */
private fun existingSummary(messages: List<UIMessage>): String? = messages
    .firstOrNull { it.role == MessageRole.SYSTEM && it.content.startsWith(CONTEXT_COMPRESSED_MARKER) }
    ?.content
    ?.removePrefix(CONTEXT_COMPRESSED_MARKER)
    ?.trim()

/** La posición final de los mensajes SISTEMA consecutivos del encabezado; devuelve 0 si no hay ningún prefijo SISTEMA. */
private fun systemPrefixEnd(messages: List<UIMessage>): Int {
    val first = messages.indexOfFirst { it.role != MessageRole.SYSTEM }
    return if (first >= 0) first else messages.size
}

class ContextCompressTransformer(
    private val chatService: ChatService,
    /**
     * Compresor de diálogo opcional(Paralelismo fragmentado + modelo barato independiente).
     * - Delegar cuando no es nulo: transformar [compressor] Compresión paralela fragmentada completa(Recomendado)
     * - null:Retorno a la compresión LLM única de sincronización original(Compatibilidad con versiones anteriores/escenario de prueba)
     */
    private val compressor: ConversationCompressor? = null,
) : Transformer {
    override val name: String = "ContextCompress"

    /**
     * v2.x: En la última transformación"Fusionado en resumen"La identificación del mensaje límite de (el último en orden cronológico).
     *
     * Para que la persona que llama acceda al ingresar al punto de control. —— Los límites sólo pueden ser determinados por**colección comprimida ordenada**De ello se deduce que la inferencia de ordenamiento de ID de mensaje no se puede utilizar.
     * (La identificación del mensaje es un UUID aleatorio).transform Permanece nulo si no se produce ninguna compresión real.
     */
    var lastCoveredBoundaryId: String? = null
        private set

    override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> {
        // El último límite no se puede dejar cuando no se realiza más compresión esta vez; de lo contrario, la persona que llama escribirá un límite vencido.
        lastCoveredBoundaryId = null
        val enabled = (context.extra("compress_enabled") as? Boolean) ?: false
        if (!enabled) return messages

        val threshold = (context.extra("compress_threshold") as? Int) ?: DEFAULT_THRESHOLD
        val keepRecent = (context.extra("compress_keep_recent") as? Int) ?: DEFAULT_KEEP_RECENT
        // Optional internal override; ordinary chat compression uses the settings-level prompt.
        val instruction = context.extra("compress_instruction") as? String
        // Manual compression is an explicit user request: if the summary model fails or
        // produces no net savings, still shrink the payload with a visible fallback marker.
        val forceFallback = (context.extra("compress_force_fallback") as? Boolean) == true
        // v2.3.2: La clave histórica se nombra compress_char_budget,La explicación real se basa en el presupuesto simbólico.
        // TokenEstimator Cuenta para el razonamiento/llamadas a herramientas/imágenes,Evite que la compresión se active demasiado tarde en sesiones con uso intensivo de herramientas o visuales.
        val tokenBudget = (context.extra("compress_char_budget") as? Int) ?: 0
        // El mismo lote de mensajes en una transformación se debe estimar varias veces (presupuesto total/presupuesto histórico/juicio por encima del límite). /
        // ganancias después de la compresión). Se comparte una caché de recuento por instancia y el BPE de cada mensaje solo se ejecuta una vez.
        val tokenCache = MessageTokenCache()
        val totalTokens = TokenEstimator.estimate(messages, cache = tokenCache)
        @Suppress("NAME_SHADOWING")
        val historyTokens = TokenEstimator.estimate(
            messages.dropWhile { it.role == MessageRole.SYSTEM },
            cache = tokenCache,
        )

        // L-COMP6: threshold < keepRecent La semántica de configuración no es válida cuando,alarma
        if (threshold < keepRecent) {
            Logger.w(name, "compress_threshold($threshold) < compress_keep_recent($keepRecent), Es posible que la compresión no se active correctamente")
        }

        // prefix(SISTEMA Continuo al principio) Dibújalo primero: el presupuesto se calcula como "disponibilidad histórica".
        // Entonces, el juicio por exceso del límite solo puede considerar la parte histórica y las indicaciones del sistema no se pueden volver a calcular.
        val prefixEnd = systemPrefixEnd(messages)
        val overCount = messages.size > threshold
        // Sólo es necesario calcular el presupuesto cuando “el número de partidas no exceda”;drop(prefixEnd) es un subconjunto de mensajes,
        // Y el prefijo también es el mismo lote de instancias, por lo que se puede acceder directamente al caché.
        // Tenga en cuenta que `!overCount &&`: El juicio presupuestario sólo tiene sentido en la sección "Número de partidas no excedidas" a continuación.
        // escrito como `overCount &&` "El número de artículos no ha excedido pero el token se ha disparado" se considerará que no excede el presupuesto.
        // Es decir, la compresión nunca se activa. —— Ese era el propósito original de la explicación del presupuesto simbólico.
        val overBudget = !overCount &&
            isOverContextCompressionBudget(
                TokenEstimator.estimate(messages.drop(prefixEnd), cache = tokenCache),
                tokenBudget,
            )
        if (!overCount && !overBudget) return messages

        // Cuando ya existe un resumen, la línea del nivel del agua se eleva medio nivel: se ha superado una vez y todavía se juzga según el umbral original, que se activará repetidamente.
        if (hasCompressedSummary(messages) && !overWatermark(messages.size, historyTokens, threshold, tokenBudget)) {
            return messages
        }

        // Encabezado de mensajes SISTEMA consecutivos (system prompt / RAG / webSearch / lorebook inyección dinámica) no participa en la compresión:
        // Son irrecuperables y eliminarlos significa olvidar tanto la configuración de los caracteres como los resultados de la búsqueda.
        val prefix = messages.subList(0, prefixEnd)

        // v5: Prioridad preservada — Los mensajes de llamada de herramientas y los mensajes que contienen resultados de herramientas deben conservarse,Sin compresión
        val priorityIds =
            messages.filter { msg ->
                msg.toolCalls != null || msg.toolCallInfo != null ||
                    msg.role == MessageRole.TOOL || msg.role == MessageRole.SYSTEM
            }.map { it.id.toString() }.toSet()

        // Intervalo compresible = prefix después de reciente antes
        val compressibleEnd = messages.size - keepRecent
        if (prefixEnd >= compressibleEnd) return messages // prefix Ella misma representa la mayoría,intervalo incompresible

        // M-COMP3: Saltar mensajes de resumen del SISTEMA comprimidos,Evite resúmenes que se superpongan a resúmenes
        val toCompress =
            messages.subList(prefixEnd, compressibleEnd).filter {
                !(it.role == MessageRole.SYSTEM && it.content.startsWith(CONTEXT_COMPRESSED_MARKER))
            }
        val recent = messages.takeLast(keepRecent)

        // v5: enviar mensajes de alta prioridad(Llamadas a herramientas, etc.)Mantener en reciente,Evite ser comprimido.
        // Debe filtrarse de acuerdo con el orden histórico original y no se puede unir con mensajes de prioridad + recientes: cuando assistant(tool_call)
        // con TOOL(result) Cuando se cruza el punto tangente, el empalme pondrá el resultado frente al asistente, destruyendo el emparejamiento del proveedor.
        val priorityMessages = toCompress.filter { it.id.toString() in priorityIds }
        val adjustedRecent = retainMessagesInOriginalOrder(messages, recent, priorityMessages)
        val adjustedToCompress = toCompress.filter { it.id.toString() !in priorityIds }

        // Phase 8.5 Reparación: keepRecent >= messages.size cuando toCompress está vacío,Omita para evitar realizar solicitudes de LLM sin sentido
        if (adjustedToCompress.isEmpty()) return messages

        // v2.x: grabar esta vez"Fusionado en resumen"mensaje de límites (el último en orden cronológico).
        // Debe hacer clic aquí**lista ordenada**No se puede adivinar basándose en el UUID: el ID del mensaje es un UUID aleatorio (no ordenado por tiempo).
        // Una vez que se toma el límite incorrecto, el lado de la asamblea tratará los mensajes descubiertos como cubiertos y los descartará.
        lastCoveredBoundaryId = adjustedToCompress.last().id.toString()

        // v2.3.2: Los tres resultados de la extracción de resúmenes están unificados por [resolveSummary] dado(Prioriza internamente la reutilización del nivel de agua en proceso —— Ver descripción de C,
        // tiempo de espera/Allí también convergen caminos de degradación anormales.);Aquí solo somos responsables de ensamblar los resultados según el final.
        val previousSummary = existingSummary(messages)
        val outcome = resolveSummary(adjustedToCompress, instruction, context.sessionId, previousSummary)
        // tiempo de espera/anormal → degradar a marca de truncamiento(M-COMP4: Informar al modelo que el historial está truncado.,En lugar de descartar silenciosamente toda la historia)
        if (outcome is SummaryOutcome.DegradeToMarker) {
            return if (forceFallback) {
                forceFallbackHistory(prefix, adjustedRecent, tokenBudget)
            } else {
                prefix + listOf(fallbackMessage()) + adjustedRecent
            }
        }
        // v2.3.2: Error al generar el resumen(Retorno total del compresor null)→ **Mantener el texto original.**,No se realizan sustituciones.
        // La historia misma todavía está ahí.,Lo que falló es simplemente"Resumen"este paso;Reemplazar el texto original con texto de marcador de posición es una verdadera amnesia.
        // (transform La persona que llama ha recortado los parámetros de entrada de acuerdo con el presupuesto de contextSize/token.,Mantener el texto original no provocará una expansión ilimitada.)
        if (outcome !is SummaryOutcome.Ok) {
            Logger.w(name, "compress Todos los bloques fallaron, Esta ronda conserva el texto original sin compresión.")
            if (forceFallback) return forceFallbackHistory(prefix, adjustedRecent, tokenBudget)
            return messages
        }
        val summary = outcome.summary

        // El documento modificado no se basa en la representación del modelo: omitirá,El camino tiene el mayor impacto en el trabajo posterior.
        val fileSection = buildFileSection(adjustedToCompress)
        // M-COMP3: Resumen comprimido más [COMPRESSED] etiqueta de prefijo,Reconocer y omitir la siguiente compresión.
        val summaryMsg =
            UIMessage(
                role = MessageRole.SYSTEM,
                content = "$CONTEXT_COMPRESSED_MARKER Resumen del diálogo histórico.\n\n$summary$fileSection",
            )
        val compacted = prefix + listOf(summaryMsg) + adjustedRecent
        // v2.3.2: Verificación de ingresos netos —— Sesión intensiva de herramientas(Un gran número de reservas)O cuando el intervalo compresible es demasiado corto,El resumen puede ser más pequeño que el que se está reemplazando.
        // El texto original aún es largo.:La compresión no sólo no salva el contexto,Otra llamada de LLM desperdiciada. Comparar por número de caracteres(indicador proxy barato,
        // Evite ejecutar codificación BPE en canalizaciones),Si no se reduce, conserva el texto original.
        val afterTokens = TokenEstimator.estimate(compacted, cache = tokenCache)
        if (afterTokens >= totalTokens) {
            Logger.i(name, "compress Sin beneficio neto($totalTokens → $afterTokens token),Mantener el texto original sin comprimir")
            if (forceFallback) return forceFallbackHistory(prefix, adjustedRecent, tokenBudget)
            return messages
        }
        // v2.3.2 (C/D): Resumen**de hecho adoptado**Sólo cuando se registra el nivel del agua. —— Registra qué mensajes han sido sobrescritos por él.
        // Anótelo aquí en lugar de antes.,es para evitar"El cheque de beneficio neto rechazó esta compresión y el texto original permanece en contexto.,
        // Pero marcó el texto original como resumido."Como resultado, se filtró por error durante la reconstrucción del árbol de diálogo.(perder la historia).
        CompressionSummaryStore.remember(
            sessionId = context.sessionId,
            coveredIds = adjustedToCompress.map { it.id.toString() }.toSet(),
            summary = summary,
        )
        return compacted
    }

    /**
     * v2.x: Reúna la entrada necesaria para los puntos de control de la sesión (más de 6 parámetros, utilice clases de datos en lugar de largas listas de parámetros,
     * También facilita a la persona que llama pasar parámetros por nombre y evitar la transmisión de posición incorrecta).
     */
    data class CheckpointRequest(
        val sessionId: String,
        val previousBoundaryId: String?,
        val generatedBoundaryId: String,
        val coveredSeq: Long,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val reason: String,
        /**
         * esta vez"Fusionado en resumen"La identificación del mensaje límite de (el último en orden cronológico), dada por [lastCoveredBoundaryId] proporcionó.
         *
         * **Debe pasarse explícitamente**: La identificación del mensaje es un UUID aleatorio y no hay forma de inferir quién está limitado por la clasificación.
         */
        val boundaryMessageId: String? = null,
        /**
         * El número acumulado de elementos combinados desde el último punto de control. Los nuevos puntos de control reemplazan a los antiguos según la semántica de cobertura continua,
         * El valor acumulado debe ser traído y acumulado por la persona que llama, de lo contrario el"¿Cuántos elementos se han comprimido?"Se notará cada vez menos.
         */
        val previousTotalCovered: Int = 0,
        /**
         * El número de resúmenes recién fusionados esta vez (presionados por la persona que llama**Lista de mensajes ordenados**obtenido del último límite).
         *
         * No utilice la clasificación de ID de mensaje en esta clase para inferir: la ID del mensaje es un UUID aleatorio y la colección no está ordenada.
         * Adivina clasificando"¿Cuáles son las nuevas coberturas?"Calculará mal con datos reales.
         */
        val newlyCoveredCount: Int = 0,
    )

    /**
     * v2.x: Reúna el resultado de una compresión en**punto de control de sesión**(Para que la persona que llama entre a la biblioteca).
     *
     * Semántica de desplazamiento: alcance cubierto por nuevos puntos de control = Todos los mensajes después del último límite (incluidas las partes cubiertas la última vez,
     * Debido a que el resumen en sí se reescribe en lugar de agregarse), por lo tanto [ContextCheckpointEntity.coveredCount] recuerda
     * **Resumen de esta fusión**número de artículos,[ContextCheckpointEntity.tokensBefore] Lo que se registra es el tamaño del contexto antes de la compresión.
     * Ambos se toman de la instantánea de la ronda actual en poder de la persona que llama y no se vuelven a estimar aquí.
     *
     * @return El puesto de control reunido; devuelve nulo si falta el resumen o no se puede localizar el límite (sería mejor no escribirlo que escribir un límite incorrecto)
     */
    fun buildCheckpoint(request: CheckpointRequest): io.zer0.memory.summary.ContextCheckpointEntity? {
        val entry = CompressionSummaryStore.entry(request.sessionId)
        val summary = entry?.summary?.takeIf { it.isNotBlank() }
        val boundaryUuid = entry?.let { resolveBoundaryUuid(it, request) }
        val newlyCovered = request.newlyCoveredCount
        return if (entry == null || summary == null || boundaryUuid == null) {
            null
        } else {
            io.zer0.memory.summary.ContextCheckpointEntity(
                sessionId = request.sessionId,
                coveredSeq = request.coveredSeq,
                lastCoveredMessageId = boundaryUuid.toString(),
                coveredCount = newlyCovered,
                totalCoveredCount = request.previousTotalCovered + newlyCovered,
                summary = summary,
                tokensBefore = request.tokensBefore,
                tokensAfter = request.tokensAfter,
                strategy = io.zer0.memory.summary.ContextCheckpointEntity.STRATEGY_SUMMARY,
                reason = request.reason,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Puntero de límite: dado primero por la persona que llama**ordenado**frontera ([CheckpointRequest.boundaryMessageId],
     * Obtenido por transformación en el orden cronológico de la colección comprimida); Solo regrese al límite anterior cuando no se pueda obtener y finalmente use el valor generado para cubrir.
     */
    private fun resolveBoundaryUuid(entry: CompressionSummaryStore.Entry, request: CheckpointRequest): Uuid? {
        val explicit = request.boundaryMessageId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        val previous = request.previousBoundaryId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        return explicit
            ?: previous
            ?: runCatching { Uuid.parse(request.generatedBoundaryId) }.getOrNull()
    }

    /**
     * v2.x: Entrada para la reconstrucción local de puntos de control (con más de 6 parámetros, use clases de datos en lugar de largas listas de parámetros).
     *
     * @param covered Los mensajes que se cubrirán esta vez (**en orden cronológico**, viejo→nuevo); si está vacío, no se generará ningún punto de control.
     * @param previousBoundaryId Los límites del último punto de control (null significa primera vez)
     * @param coveredSeq La secuencia real del nuevo mensaje de límite en la tabla de mensajes (analizada por la biblioteca de la persona que llama; si no se encuentra, pase 0)
     */
    data class LocalCheckpointRequest(
        val sessionId: String,
        val covered: List<UIMessage>,
        val previousBoundaryId: String?,
        val coveredSeq: Long,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val reason: String,
        /** Presupuesto de caracteres para el cuerpo del extracto (null Utilice el valor predeterminado de ContextHistoryDigest). */
        val maxChars: Int? = null,
    )

    /**
     * v2.x: construir**reconstrucción local**Puntos de control (cero solicitudes de modelo).
     *
     * Se utiliza cuando el resumen no está disponible (red deficiente, error del modelo de resumen, tiempo de espera):"Mensajes que se sobrescribirán"Hacer extractos de texto deterministas
     * ([io.zer0.ai.core.ContextHistoryDigest]), todavía escribe un punto de control con un puntero de límite.
     *
     * Por qué este paso es importante: si no se escribe nada después de que falla el resumen, reinicie la aplicación/Al volver a la sesión se vuelve al historial completo.
     * Tendré que intentarlo de nuevo la próxima vez.——Lo que ve el usuario es"Comprimido pero inútil". Aunque la reconstrucción local no resume los puntos principales,
     * pero**El límite entra en vigor**: Los mensajes cubiertos ya no entran en el modelo y el historial se reduce de forma determinista a este tamaño.
     */
    fun buildLocalCheckpoint(request: LocalCheckpointRequest): io.zer0.memory.summary.ContextCheckpointEntity? {
        val covered = request.covered
        val digest = request.maxChars?.let {
            io.zer0.ai.core.ContextHistoryDigest.build(covered, it)
        } ?: io.zer0.ai.core.ContextHistoryDigest.build(covered)
        val summary = digest?.content?.takeIf { it.isNotBlank() }
        val previous = request.previousBoundaryId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        // Toma de límites**El último en orden cronológico.**(covered Presionado por la persona que llama→Recién dado).
        // No disponible aquí maxOrNull(): Eso supone que la hora UUIDv7 está ordenada y la identificación del mensaje es aleatoria v4 (orden aleatorio),
        // Una vez que se toma el límite incorrecto, el lado de la asamblea"Aún no cubierto"Los mensajes también se tratan como sobrescritos y descartados.
        val boundary = covered.lastOrNull()
            ?.let { runCatching { Uuid.parse(it.id.toString()) }.getOrNull() }
            ?: previous
        return if (covered.isEmpty() || summary == null || boundary == null) {
            null
        } else {
            io.zer0.memory.summary.ContextCheckpointEntity(
                sessionId = request.sessionId,
                coveredSeq = request.coveredSeq,
                lastCoveredMessageId = boundary.toString(),
                coveredCount = covered.size,
                summary = summary,
                tokensBefore = request.tokensBefore,
                tokensAfter = request.tokensAfter,
                strategy = io.zer0.memory.summary.ContextCheckpointEntity.STRATEGY_LOCAL,
                reason = request.reason,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Obtener el resultado resumido(v2.3.2: Convergir los rendimientos originalmente dispersos en transformación en tipos explícitos,Longitud de la función fácil de controlar). */
    private sealed interface SummaryOutcome {
        /** ponerse abstracto(Posiblemente desde la marca de agua del caché,También puede ser de nueva generación.). */
        data class Ok(val summary: String) : SummaryOutcome

        /** tiempo de espera/anormal → inserción de llamada"El historial aún no está disponible"Marca truncada. */
        object DegradeToMarker : SummaryOutcome

        /** El compresor falla en general → La persona que llama conserva el texto original.,No hay compresión en esta ronda. */
        object KeepOriginal : SummaryOutcome
    }

    /**
     * Obtenga el resumen de esta compresión.
     *
     * v2.3.2 (C): Primero verifique el nivel del agua en el proceso. —— Después del cambio/recarga de sesión desde la base de datos,En realidad, el mismo período de la historia se ha resumido,
     * Simplemente reutilícelo directamente,Guarde una llamada de LLM(y un retraso en la primera palabra de hasta 20 segundos).
     * Sólo si el intervalo a comprimir esta vez**Todos**Reutilizar sólo si está dentro de la cobertura de caché;La cobertura parcial perderá la historia.,Dáselo al compresor para que lo rehaga.
     *
     * tiempo de espera/Ruta de degradación inusual con"El compresor falla en general"La distinción de también se da uniformemente desde aquí.(ver [SummaryOutcome]).
     */
    private suspend fun resolveSummary(
        toCompress: List<UIMessage>,
        instruction: String?,
        sessionId: String?,
        previousSummary: String? = null,
    ): SummaryOutcome {
        val cached = reusableSummary(CompressionSummaryStore.entry(sessionId), toCompress)
        if (cached != null) {
            Logger.i(name, "Reutilizar resumen de caché(${toCompress.size} Artículo),Saltar llamadas de compresión")
            return SummaryOutcome.Ok(cached)
        }
        val generated =
            try {
                // v2.x: Comprimir llamadas y agregar barreras de tiempo de espera — Ocasionalmente, largos períodos de falta de respuesta del modelo de compresión ralentizarán todo el ciclo de generación.(La medición real tiene ~30s retraso de la primera palabra),
                // Tome el camino marcado para bajar de categoría después del tiempo de espera,No más esperas interminables.
                val text =
                    withTimeout(COMPRESS_TIMEOUT_MS) {
                        if (compressor != null) {
                            // Dar prioridad al paralelismo de bloques + modelo barato independiente
                            compressWithCompressor(toCompress, instruction, previousSummary)
                        } else {
                            // revertir:Compresión LLM de disparo único de sincronización sin procesar
                            compressMessages(toCompress, instruction)
                        }
                    }
                text?.let { SummaryOutcome.Ok(it) } ?: SummaryOutcome.KeepOriginal
            } catch (timeout: TimeoutCancellationException) {
                // El tiempo de espera es un tipo de falla de compresión.:degradar a marca de truncamiento,Sin interrumpir el proceso principal
                Logger.w(name, "compress timeout after ${COMPRESS_TIMEOUT_MS}ms, fallback to marker")
                SummaryOutcome.DegradeToMarker
            } catch (e: CancellationException) {
                // H-COMP1: no tragar CancellationException,Volver a lanzar directamente(La cancelación de rutina debe propagarse)
                throw e
            } catch (t: Throwable) {
                Logger.e(name, "compress failed, fallback to truncation", t)
                SummaryOutcome.DegradeToMarker
            }
        return generated
    }

    /** M-COMP4: mensaje de marca de degradación — Informar al modelo que el historial está truncado.(En lugar de descartar silenciosamente toda la historia). */
    private fun fallbackMessage(): UIMessage = UIMessage(
        role = MessageRole.SYSTEM,
        content = "(El historial aún no está disponible,Mantener solo mensajes recientes)",
    )

    private fun forceFallbackHistory(prefix: List<UIMessage>, recent: List<UIMessage>, tokenBudget: Int): List<UIMessage> {
        val budget = if (tokenBudget > 0) tokenBudget else WarmupHistory.FALLBACK_BUDGET_TOKENS
        val prefixTokens = TokenEstimator.estimate(prefix)
        // L3-1: prefix(system prompt + RAG + lorebook) ha superado el presupuesto, sólo deja los recientes 1 token
        // Seguirá trayendo la cantidad total del prefijo —— La longitud total sigue siendo enorme y casi toda la historia se pierde, lo que es peor que ninguna compresión.
        // En este momento**Mantener el texto original.**(Preferiría que el contexto fuera más largo que"Truncamiento de shell vacío").
        if (prefixTokens >= budget) {
            Logger.w(
                name,
                "forceFallback Renunciar:prefix Ya por encima del presupuesto(prefix=$prefixTokens budget=$budget), mantenga el texto original sin truncamiento",
            )
            return prefix + recent
        }
        val prefixBudget = (budget - prefixTokens).coerceAtLeast(1)
        val retained = WarmupHistory.trimToBudget(
            history = listOf(fallbackMessage()) + recent,
            budgetTokens = prefixBudget,
        ).history
        return prefix + retained
    }

    /** Llame a LLM para comprimir mensajes antiguos en resúmenes. */
    private suspend fun compressMessages(oldMessages: List<UIMessage>, instruction: String? = null): String {
        val prompt =
            buildString {
                appendLine("Condense el historial de conversaciones a continuación en un resumen conciso,Mantener información clave(hechos/toma de decisiones/Preferencias de usuario).")
                appendLine("- en forma de bala,Una línea por punto")
                appendLine("- No inventes cosas que no fueron mencionadas.")
                appendLine("- La extensión total no debe exceder las 800 palabras.")
                // H10: Instrucciones adicionales de compresión manual(Como por ejemplo"Centrarse en preservar las discusiones presupuestarias"),Comando de compresión de entrada
                if (!instruction.isNullOrBlank()) {
                    appendLine("- Requisitos adicionales: $instruction")
                }
                appendLine()
                appendLine("Historial de conversaciones:")
                oldMessages.forEach { msg ->
                    val role =
                        when (msg.role) {
                            MessageRole.USER -> "Usuario"
                            MessageRole.ASSISTANT -> "asistente"
                            MessageRole.SYSTEM -> "sistema"
                            MessageRole.TOOL -> "Herramientas"
                        }
                    // L-COMP5: Agregar en el truncamiento "…" marca(en lugar de truncamiento silencioso)
                    // v1.116 (C1-5): El umbral de truncamiento de un solo mensaje aumentó de 500 a 1500 caracteres,
                    // Evite respuestas largas(como bloques de código/Análisis detallado)El truncamiento excesivo hace que el resumen pierda información clave.
                    val raw = msg.content
                    // v2.x: Protege la cabeza y la cola en lugar de solo la cabeza. —— Los resultados de la herramienta y las conclusiones de los errores se encuentran al final.
                    // Dejar solo el encabezado permitirá obtener el resumen"llamada iniciada"Pero no puedo conseguirlo"cual es el resultado".
                    val text = io.zer0.common.TextTruncation.headTailText(raw, MAX_COMPRESS_MSG_CHARS)
                    appendLine("[$role] $text")
                }
            }

        val request = listOf(UIMessage(role = MessageRole.USER, content = prompt))
        // H-COMP2 / L-COMP7: Utilice resultOf en su lugar runCatching.getOrElse
        //  - resultOf revenderá CancellationException(Cancelar sin tragar la corrutina)
        //  - Otros errores se convierten en Result.Error,onError Respaldo después de registrar la excepción original streamChat
        //  - CancellationException no llegará getOrNull(),Entonces no hay ningún error de respaldo
        val completion: ChatCompletion =
            resultOf {
                chatService.completeText(messages = request)
            }.onError { msg, t ->
                Logger.w(name, "completeText falló,revertir streamChat: $msg", t)
            }.getOrNull() ?: run {
                // Ten todo en cuenta: colección de transmisión(Acceda aquí solo para errores que no sean CancellationException)
                val sb = StringBuilder()
                chatService.streamChat(messages = request).collect { ev ->
                    if (ev is ChatStreamEvent.ContentDelta) sb.append(ev.delta)
                }
                ChatCompletion(text = sb.toString())
            }
        // v1.0.74 fix: despegar <think> etiqueta de inferencia,Evite que los pensamientos se mezclen en resúmenes comprimidos
        return io.zer0.muse.transformer.stripThinkTags(completion.text)
            .ifBlank { "Las conversaciones históricas se han comprimido(El resumen está vacío)" }
    }

    /**
     * confiar [compressor] Compresión paralela fragmentada completa,Combine varios extractos en un solo texto.
     *
     * - v2.3.2: Compresor**Cualquier pieza falla**Regresar en su totalidad null,por [transform] ir"Mantener el texto original."Rebajar
     *   (no más"Error al generar el resumen"Marcador de posición como resumen legal —— Eso haría que el modelo pensara que ha visto una historia que no ha visto.)
     * - Para resúmenes multibloque "[Resumen de la conversación i/N]" Etiquetas de segmento fusionadas en un mensaje de SISTEMA
     *   (con [ContextCompressTransformer] Originales"Resumen del SISTEMA único"La semántica sigue siendo consistente,
     *    Evite la fragmentación basada en la lógica de persistencia/transformador aguas abajo)
     */
    private suspend fun compressWithCompressor(
        oldMessages: List<UIMessage>,
        instruction: String? = null,
        previousSummary: String? = null,
    ): String? {
        val summaries = compressor!!.compress(oldMessages, instruction, previousSummary) ?: return null
        return when {
            summaries.isEmpty() -> "Las conversaciones históricas se han comprimido(El resumen está vacío)"
            summaries.size == 1 -> summaries.first()
            else ->
                buildString {
                    summaries.forEachIndexed { idx, s ->
                        append("[Resumen de la conversación ${idx + 1}/${summaries.size}]\n")
                        append(s)
                        if (idx != summaries.lastIndex) append("\n\n")
                    }
                }
        }
    }

    private companion object {
        // X-5: El valor predeterminado se cambia para hacer referencia a la única fuente de verdad de CompressionPolicy (compartida con el lado del ensamblaje del diálogo principal).
        val DEFAULT_THRESHOLD = CompressionPolicy.THRESHOLD_DEFAULT
        val DEFAULT_KEEP_RECENT = CompressionPolicy.KEEP_RECENT_DEFAULT

        // v1.116 (C1-5): Número máximo de caracteres en un solo mensaje enviado a compresión LLM(Originales 500,promovido a 1500)
        const val MAX_COMPRESS_MSG_CHARS = 1500

        // v2.x: Tiempo de espera de llamada del modelo de compresión(milisegundos);Degradado a marca de truncamiento después del tiempo de espera,Evite ralentizar toda la ronda de generación.
        const val COMPRESS_TIMEOUT_MS = 20_000L
    }
}
