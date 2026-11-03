package io.zer0.memory.deep

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.prompt.FactExtractionPrompt
import io.zer0.memory.prompt.PromptExampleGuard
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryConfig
import io.zer0.memory.time.TimeContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val ASSISTANT_ATTRIBUTED_LINE_REGEX =
    Regex(
        """^\s*[-*+]\s*\[(?:asistente|assistant|léame de vibe del asistente)[^\]]*\].*$""",
        RegexOption.IGNORE_CASE,
    )

internal fun filterAssistantAttributedMemoryEntries(summary: String): String = summary.lineSequence()
    .filterNot { ASSISTANT_ATTRIBUTED_LINE_REGEX.matches(it) }
    .joinToString("\n")

/**
 * Procesador de memoria profunda.
 *
 * Recorrido diario de sucio. session(summary !== snapshot),Utilice LLM de summary diff
 * Extraer metahechos,escribir [FactStore].
 *
 * diseño clave:
 *  - concurrencia: la mayoría [MAX_CONCURRENT] sesiones procesadas simultáneamente(Semaphore)
 *  - Inténtalo de nuevo: La sesión única falló [MAX_RETRIES] markProcessed se omitió después de tiempos,No más reintentos infinitos
 *  - JSON Reparación: LLM La salida puede tardar ```json valla o bloque ict,Necesita ser analizado después de la limpieza.
 *  - normalización del tiempo: fact.time Debe estar dentro de source_time_range.localDates
 *
 * v0.22: Pase [FactDbProvider] Obtenga el Asistente correspondiente mediante resumen.assistantId
 * independiente FactStore,Implemente el aislamiento de Facts.db por asistente.
 */
class DeepMemoryProcessor(
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider,
    private val llmClient: MemoryLlmClient,
) {

    companion object {
        const val MAX_CONCURRENT = 3
        const val MAX_RETRIES = 3
        const val FACT_EXTRACTION_MAX_TOKENS = 4096

        // Después de fallos diarios consecutivos se alcanza este límite markProcessed,no lo intentes de nuevo(Evite llamadas de LLM desperdiciadas)
        const val MAX_DAILY_FAILURES = 3
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val concurrency = Semaphore(MAX_CONCURRENT)

    /**
     * v2.6.2(auditoría de memoria): Bloqueos de fragmentos por sesión para evitar que se realice la misma sesión.
     * [processDirtySessions](Atravesar todo sucio) y [processSession](Fin de Sesión) Doble Ejecución ——
     * Ambos extraerán y escribirán repetidamente desde la misma instantánea y marcaránProcesado respectivamente.
     */
    private val sessionLocks = Array(64) { kotlinx.coroutines.sync.Mutex() }

    private fun lockFor(sessionId: String) = sessionLocks[(sessionId.hashCode() and 0x7fffffff) % sessionLocks.size]

    // session → Número de fallos diarios consecutivos,Claro sobre el éxito,markProcessed se omitió después de alcanzar MAX_DAILY_FAILURES
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** Procesar los resultados. */
    data class ProcessResult(
        val processed: Int,
        val factsAdded: Int,
        val failures: Int,
    )

    /**
     * Travesía sucia session,diff Extraiga hechos y escriba FactStore.
     *
     * v0.32: Nuevo [config] parámetros,Se llama después de procesar cada sesión. [FactStore.applyDecay]
     * Realizar un olvido basado en la configuración(decayPerDay / forgetSpeed Configuración).
     * Predeterminado [MemoryConfig] Equivalente al antiguo comportamiento(El límite predeterminado es de aproximadamente 40 días.,Casi nunca borra).
     *
     * @param summaryManager administrador de resumen
     * @param model modelo objetivo
     * @param locale Idioma
     * @param config configuración de memoria(por [io.zer0.memory.ticker.MemoryTicker] paso a través)
     * @return Estadísticas de proceso
     */
    suspend fun processDirtySessions(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "es",
        config: MemoryConfig = MemoryConfig(),
    ): ProcessResult = withContext(Dispatchers.IO) {
        val dirty = summaryManager.getDirtySessions()
        if (dirty.isEmpty()) return@withContext ProcessResult(0, 0, 0)

        val processed = AtomicInteger(0)
        val factsAdded = AtomicInteger(0)
        val failures = AtomicInteger(0)

        coroutineScope {
            dirty.map { summary ->
                async {
                    concurrency.withPermit {
                        // v2.6.2(auditoría de memoria): Procesando serialización para la misma sesión (processDirtySessions con
                        // processSession Puede activarse simultáneamente). Vuelva a leer el último resumen en el candado y lo que obtendrá no será una instantánea.
                        lockFor(summary.sessionId).withLock {
                            val latest = summaryManager.getSummary(summary.sessionId) ?: return@withLock
                            if (latest.summary == latest.snapshot) return@withLock // Ha sido procesado por otras vías.
                            // v1.78 (M2): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                            resultOf {
                                processSingleSession(latest, summaryManager, model, locale, config)
                            }.onSuccess { added ->
                                processed.incrementAndGet()
                                factsAdded.addAndGet(added)
                            }.onError { msg, t ->
                                // CancellationException Ha sido relanzado por resultOf,Aqui no estara CancellationException
                                Logger.w("DeepMemoryProcessor", "processSingleSession failed for ${summary.sessionId.take(8)}…: $msg", t)
                                failures.incrementAndGet()
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        ProcessResult(processed.get(), factsAdded.get(), failures.get())
    }

    /**
     * v1.0.51: Maneja la extracción de hechos para la sesión especificada.(Se utiliza para procesar inmediatamente después de que finaliza la sesión.).
     *
     * con [processDirtySessions] diferencia:
     *  - Sólo se especifica el proceso sessionId,No escanear todo dirty sessions
     *  - suministro [io.zer0.memory.ticker.MemoryTicker.notifySessionEnd] Se llama después de que RollingSummary tenga éxito
     *  - Reutilizar [concurrency] Semaphore(3) Limitar la simultaneidad global
     *
     * @return Agregar cantidad de hechos(session no existe/Devuelve si no está sucio/no se pudo extraer 0)
     */
    suspend fun processSession(
        sessionId: String,
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "es",
        config: MemoryConfig = MemoryConfig(),
    ): Int = withContext(Dispatchers.IO) {
        val summary = summaryManager.getSummary(sessionId) ?: return@withContext 0
        // solo manejar dirty session(summary !== snapshot)
        if (summary.summary == summary.snapshot) return@withContext 0

        concurrency.withPermit {
            // v2.6.2(auditoría de memoria): La misma sesión se serializa con ProcessDirtySessions.
            lockFor(sessionId).withLock {
                resultOf {
                    processSingleSession(summary, summaryManager, model, locale, config)
                }.onSuccess { added ->
                    if (added > 0) {
                        Logger.i("DeepMemoryProcessor", "processSession(${sessionId.take(8)}…): $added nuevos hechos")
                    }
                }.onError { msg, t ->
                    Logger.w("DeepMemoryProcessor", "processSession(${sessionId.take(8)}…) falló: $msg", t)
                }.getOrNull() ?: 0
            }
        }
    }

    /** Procesar una sola sesión. Devuelve la nueva cantidad de hecho. */
    private suspend fun processSingleSession(
        summary: SessionSummaryManager.SummaryData,
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "es",
        config: MemoryConfig = MemoryConfig(),
    ): Int {
        // Los fallos diarios continuos alcanzan el límite superior,Marcar como procesado y no volver a intentarlo(Evite repetidas llamadas desperdiciadas de LLM)
        if (maybeSkipExhaustedSession(summary, summaryManager)) return 0

        val summaryText = summary.summary
        val prevSnapshot = summary.snapshot

        // v0.22: Presione resumen.assistantId para obtener la información independiente del Asistente correspondiente FactStore
        val factStore = factDbProvider.getFactStore(summary.assistantId)

        // Revisión de corrección (2.0 A-11): Filtrado de lápidas — El hecho de que el usuario lo haya eliminado no debe eliminarse del resumen de la sesión sucia.
        // Vuelva a extraer la tabla de hechos.(S-04 Las lápidas anteriormente solo funcionaban compileFacts/compileToday,
        // deepMemory Tanto el resumen de entrada como el resultado del LLM no están filtrados.,El contenido eliminado"resurrección").
        val memoryScope = if (summary.assistantId.isBlank() || summary.assistantId == "default") "main" else summary.assistantId
        val tombstones = resultOf { factStore.getTombstones(memoryScope, summary.spaceId) }.getOrNull() ?: emptyList()
        val effectiveSummary = filterAssistantAttributedMemoryEntries(
            FactStore.filterTombstonedLines(summaryText, tombstones),
        )
        val effectiveSnapshot = filterAssistantAttributedMemoryEntries(
            FactStore.filterTombstonedLines(prevSnapshot, tombstones),
        )
        if (effectiveSummary == effectiveSnapshot) {
            summaryManager.markProcessed(summary.sessionId)
            return 0
        }

        // Construir aportes de LLM
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val hasPrevious = prevSnapshot.isNotBlank()
        val systemPrompt = FactExtractionPrompt.buildSystemPrompt(locale, hasPrevious)
        val userContent = buildExtractionUserContent(
            isZh = isZh,
            hasPrevious = hasPrevious,
            timeContextText = renderTimeContext(summary, isZh),
            effectiveSnapshot = effectiveSnapshot,
            effectiveSummary = effectiveSummary,
        )

        // sintonizar LLM(con reintento)
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val rawResult = llmClient.callText(
                    systemPrompt = systemPrompt,
                    userContent = userContent,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = FACT_EXTRACTION_MAX_TOKENS,
                )
                val facts = parseFactExtractionResult(rawResult, summary, locale)
                // v1.0.51: El análisis falló(null)→ No markProcessed,Lanza una excepción y vuelve a intentarlo.,Evite omitir permanentemente datos perdidos
                if (facts == null) {
                    throw RuntimeException("fact extraction El análisis falló(rawLen=${rawResult.length})")
                }
                // v2.x: Ejemplo de prevención de fugas — Copie los ejemplos de palabras clave y descarte los hechos que no se mencionan en el resumen.(Comentarios de los usuarios de que los ejemplos fueron memorizados)
                val guardInputText = "$effectiveSnapshot\n$effectiveSummary"
                val guardedFacts = facts.filterNot {
                    PromptExampleGuard.isUngroundedExample(it.fact, guardInputText)
                }
                if (guardedFacts.isEmpty()) {
                    // LLM Juicio legal sin hechos → markProcessed,No lo manejaré la próxima vez
                    Logger.d(
                        "DeepMemoryProcessor",
                        "fact extraction returned empty (rawLen=${rawResult.length}, session=${summary.sessionId.take(8)}…)",
                    )
                    failureCounts.remove(summary.sessionId)
                    summaryManager.markProcessed(summary.sessionId)
                    return 0
                }
                // B-19: scope calibre alineado con autoSave — asistente principal("default"/vacío)volver "main",
                // El subasistente utiliza el ID del asistente. Anteriormente, addBatch no superaba el alcance. "main",
                // Los hechos profundos de la sesión del asistente infantil están grabados en la memoria del asistente principal.(La consulta pierde la mitad de los datos).
                val scope = memoryScope
                // A-11: LLM Extraer salida filtrada por lápidas — El hecho de que la lápida haya sido golpeada se descarta directamente.,Ya no volver a escribir en la tabla de hechos
                val filteredFacts = if (tombstones.isEmpty()) {
                    guardedFacts
                } else {
                    guardedFacts.filter { f -> tombstones.none { t -> FactStore.matchesTombstone(f.fact, t) } }
                }
                if (filteredFacts.isEmpty()) {
                    // Todos los resultados de la extracción llegan a las lápidas:considerado como un hecho no escrito,Marca procesada para evitar volver a dibujar cada ronda.
                    val tombstoneSkipMsg = "fact extraction Todos golpearon la lápida(session=${summary.sessionId.take(8)}…),Saltar escritura"
                    Logger.d("DeepMemoryProcessor", tombstoneSkipMsg)
                    failureCounts.remove(summary.sessionId)
                    summaryManager.markProcessed(summary.sessionId)
                    return 0
                }
                // D6 Número 2: control de atomicidad — Solicitud de extracción solicitada“afirmación una por una”,Aquí está el resultado final:
                // Las entradas que contienen múltiples afirmaciones concatenadas con conectivos se dividen de forma conservadora.(no cambies schema,Si la división falla, déjala como está.).
                val atomicity = FactAtomicityValidator.enforce(filteredFacts)
                if (atomicity.splitCount > 0) {
                    Logger.i(
                        "DeepMemoryProcessor",
                        "control de atomicidad: dividir ${atomicity.splitCount} Muchas afirmaciones de hecho(+${atomicity.addedCount} Artículo, " +
                            "session=${summary.sessionId.take(8)}…)",
                    )
                }
                // El resumen conlleva atribución espacial y la extracción de profundidad ya no puede caer en el valor predeterminado.
                val added = factStore.addBatch(atomicity.facts, scope = scope, spaceId = summary.spaceId)
                // v0.32: Acceso MemoryConfig —— Después de procesar cada sesión sucia, ejecute un decaimiento,
                // dejar decayPerDay / forgetSpeed / compileThreshold / baseImportance Realmente efectivo.
                // El incremento de hecho de una sola sesión es muy pequeño.,applyDecay Los gastos generales de;El fallo no bloquea el proceso principal.
                // v1.78 (H4): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf {
                    factStore.applyDecay(config, scope = scope, spaceId = summary.spaceId)
                }.onError { msg, t ->
                    Logger.w("DeepMemoryProcessor", "applyDecay failed for ${summary.sessionId.take(8)}…: $msg", t)
                }
                failureCounts.remove(summary.sessionId)
                summaryManager.markProcessed(summary.sessionId)
                return added
            } catch (e: CancellationException) {
                // v1.78 (H5): Hay que volver a lanzarlo.,De lo contrario, la señal de cancelación de rutina se pierde.,repeat El bucle continúa reintentando y no se puede salir.
                throw e
            } catch (e: Exception) {
                lastError = e
                Logger.w(
                    "DeepMemoryProcessor",
                    "fact extraction attempt ${attempt + 1}/$MAX_RETRIES failed for ${summary.sessionId.take(8)}…: ${e.message}",
                )
                // v1.78 (M6): retroceso exponencial,Evite la limitación actual de LLM reintentando inmediatamente para agravar la limitación actual
                if (attempt < MAX_RETRIES - 1) {
                    delay(1000L * (1L shl attempt)) // 1s, 2s, 4s
                }
            }
        }

        // v1.78 (M5): No se ajusta cuando se agotan los reintentos. markProcessed —— De lo contrario, la sesión se saltará permanentemente.,
        // Los verdaderos fracasos se ocultan. Cambiar a solo errores de registro,Deje que el proceso diario se recupere y vuelva a intentarlo la próxima vez.
        // Incrementar el recuento de fallos consecutivos,Sáltate la sesión del siguiente día después de alcanzar MAX_DAILY_FAILURES.
        failureCounts[summary.sessionId] = (failureCounts[summary.sessionId] ?: 0) + 1
        Logger.w(
            "DeepMemoryProcessor",
            "fact extraction Reintentos agotados(${MAX_RETRIES}veces),session=${summary.sessionId.take(8)}…," +
                "Fallo diario continuo ${failureCounts[summary.sessionId]}/$MAX_DAILY_FAILURES," +
                "La canalización diaria volverá a intentarlo la próxima vez.: ${lastError?.message}",
            lastError,
        )
        throw lastError ?: RuntimeException("fact extraction failed after $MAX_RETRIES retries")
    }

    /** Construir entrada de LLM de extracción de hechos(por idioma/¿Hay una última instantánea?). */
    private fun buildExtractionUserContent(
        isZh: Boolean,
        hasPrevious: Boolean,
        timeContextText: String,
        effectiveSnapshot: String,
        effectiveSummary: String,
    ): String {
        val timeContextLabel = if (isZh) "## contexto temporal" else "## Time Context"
        return if (hasPrevious) {
            val prevLabel = if (isZh) "## última instantánea" else "## Previous Snapshot"
            val curLabel = if (isZh) "## Resumen actual" else "## Current Summary"
            listOf(timeContextLabel, timeContextText, prevLabel, effectiveSnapshot, curLabel, effectiveSummary)
                .joinToString("\n\n")
        } else {
            val curLabel = if (isZh) "## Contenido resumido" else "## Summary Content"
            "$timeContextLabel\n\n$timeContextText\n\n$curLabel\n\n$effectiveSummary"
        }
    }

    /** La marca de sesión con fallas continuas que alcanzan el límite superior se procesó y se omitió.,Evite repetidas llamadas desperdiciadas de LLM. */
    private suspend fun maybeSkipExhaustedSession(
        summary: SessionSummaryManager.SummaryData,
        summaryManager: SessionSummaryManager,
    ): Boolean {
        if ((failureCounts[summary.sessionId] ?: 0) < MAX_DAILY_FAILURES) return false
        val skipMsg = "session=${summary.sessionId.take(8)}… Fallo diario continuo ${MAX_DAILY_FAILURES} veces,Marcar como procesado omitido"
        Logger.w("DeepMemoryProcessor", skipMsg)
        summaryManager.markProcessed(summary.sessionId)
        failureCounts.remove(summary.sessionId)
        return true
    }

    /** Texto contextual del tiempo de construcción(Para referencia de Maestría en Derecho). */
    private fun renderTimeContext(summary: SessionSummaryManager.SummaryData, isZh: Boolean): String {
        val range = summary.sourceTimeRange ?: return if (isZh) "(sin información de tiempo)" else "(no time info)"
        val localDatesLabel = if (isZh) "fecha local" else "Local dates"
        val timezoneLabel = if (isZh) "zona horaria" else "Timezone"
        return "$timezoneLabel: ${range.timezone}\n$localDatesLabel: ${range.localDates.joinToString(", ")}"
    }

    /**
     * Analizar los resultados de la extracción de hechos.
     * LLM La salida puede tardar ```json valla o <think> bloquear,Necesita limpieza.
     *
     * v1.0.51: Tipo de devolución cambiado nullable —— Devolver si falla el análisis null(La persona que llama no debe markProcessed),
     * LLM El juicio legal no arroja hechos emptyList(Sólo se puede llamar markProcessed).
     * Reparación"JSON El análisis falló → emptyList → markProcessed → session Saltar permanentemente → hechos perdidos"error fatal.
     */
    // Phase 2.3: Se cambió a lógica de tolerancia a fallas JSON de prueba unitaria interna(No es necesario ingresar este método mediante una llamada LLM)
    internal fun parseFactExtractionResult(
        raw: String,
        summary: SessionSummaryManager.SummaryData,
        locale: String,
    ): List<FactStore.Fact>? {
        if (raw.isBlank()) return emptyList()

        // 1. Ir al prefijo <think>...</think> bloquear
        var s = raw
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 2. ir ```json ... ``` valla
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) {
            s = fenceMatch.groupValues[1]
        } else {
            s = s.trim()
        }

        // 3. Extraer matriz JSON(Si no [ comienzo,Profundidad del soporte de escaneo)
        if (!s.startsWith("[")) {
            // v1.0.51: Candidato de matriz JSON no encontrado → El análisis falló,Devuelve nulo para activar el reintento
            val candidate = extractJsonArray(s)
            if (candidate == null) {
                Logger.w("DeepMemoryProcessor", "fact extraction Matriz JSON no encontrada (rawLen=${raw.length}, session=${summary.sessionId.take(8)}…)")
                return null
            }
            s = candidate
        }

        // 4. analizar
        val dtos = runCatching {
            json.decodeFromString(ListSerializer(FactDto.serializer()), s)
        }.getOrElse {
            // v1.0.51: JSON El análisis falló → Regresar null,La persona que llama no markProcessed,Vuelve a intentarlo la próxima vez todos los días.
            Logger.w(
                "DeepMemoryProcessor",
                "fact extraction JSON El análisis falló (rawLen=${raw.length}, session=${summary.sessionId.take(8)}…): ${it.message}",
            )
            return null
        }

        // 5. Normalización horaria + transferencia Fact
        val localDates = summary.sourceTimeRange?.localDates ?: emptyList()
        val summaryTimes = io.zer0.memory.time.TimeContext.scanSummaryTimeSignals(summary.summary).dateTimes
        val ctx = TimeContext.FactTimeContext(localDates, summaryTimes)

        return dtos.mapNotNull { dto ->
            val normalizedTime = io.zer0.memory.time.TimeContext.normalizeFactTimestamp(dto.time, ctx)
            FactStore.Fact(
                fact = dto.fact,
                tags = dto.tags,
                time = normalizedTime,
                sessionId = summary.sessionId,
                importance = dto.importance.coerceIn(0, 2),
                category = dto.category.takeIf { it.isNotBlank() } ?: "general",
                confidence = dto.confidence?.coerceIn(0f, 1f) ?: if (dto.source == "user_explicit") 1.0f else 0.7f,
                source = dto.source.takeIf { it.isNotBlank() } ?: "inferred",
                expiresAt = dto.expiresAt,
                lastConfirmedAt = dto.lastConfirmedAt,
                // H-MEM-2: Pase LLM extraído entityKey,Admite una deduplicación precisa
                entityKey = dto.entityKey,
            )
        }
    }

    /**
     * Escanee la cadena para encontrar la primera matriz JSON completa(máquina de estado de profundidad de soporte).
     */
    private fun extractJsonArray(s: String): String? {
        val start = s.indexOf('[')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** fact extraction JSON DTO.v5: Contiene importance / category / confidence / source / expires_at / last_confirmed_at campo.
     * H-MEM-2: Nuevo entityKey — LLM Requerir la salida de claves de entidad al extraer,Admite un canal de deduplicación preciso(v12+). */
    @Serializable
    private data class FactDto(
        val fact: String,
        val tags: List<String> = emptyList(),
        val time: String? = null,
        val importance: Int = 0,
        val category: String = "general",
        val confidence: Float? = null,
        val source: String = "inferred",
        val expiresAt: String? = null,
        val lastConfirmedAt: String? = null,
        val entityKey: String? = null,
    )
}
