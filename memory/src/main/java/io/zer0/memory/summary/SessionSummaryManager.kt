package io.zer0.memory.summary

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.format.RollingSummaryFormat
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.pii.PiiGuard
import io.zer0.memory.prompt.RollingSummaryPrompt
import io.zer0.memory.time.TimeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

private val VIBE_LINE_REGEX = Regex("""(?im)^\s*Vibe\s*:\s*(.+)$""")

/**
 * Session Gestor de resumen.
 *
 * Un resumen por sesión(guardar Room),Pase [rollingSummary] actualización continua(Cubierto,no agregar).
 * La salida se fija en [RollingSummaryFormat] El formato de dos secciones del contrato.(facts + timeline).
 *
 * Sirviendo dos caminos:
 *  - memoria ordinaria: [io.zer0.memory.compile.MemoryCompiler] Leer resumen → compresión recursiva → memory.md
 *  - memoria profunda: [io.zer0.memory.deep.DeepMemoryProcessor] leer snapshot diff → Dividir metadatos
 */
class SessionSummaryManager(
    private val dao: SessionSummaryDao,
    private val llmClient: MemoryLlmClient,
) {

    /**
     * v2.6.2(auditoría de memoria): Bloqueos seriales fragmentados por sesión.
     *
     * rolling summary si“Leer resúmenes antiguos → LLM generar → sobrescribir”, no una operación atómica.
     * MemoryTicker Aunque hay internas `_summaryInProgress` Protección de reentrada, pero migración de backend/vaciado manual, etc.
     * La entrada llama directamente a esta clase, que puede ser concurrente con la ruta del teletipo. Las instantáneas de mensajes más antiguos que se completen más tarde sobrescribirán las más nuevas.
     * Resumen y recuento de mensajes. Aquí la lectura-modificación-escritura se serializa mediante sessionId en la capa de administrador.
     *
     * Utilice fragmentación fija en lugar de un Mutex por sesión para evitar un crecimiento ilimitado en la cantidad de sesiones en aplicaciones de larga duración.
     * Lo que lleva a bloquear las fugas en la mesa.
     */
    private val summaryLocks = Array(SUMMARY_LOCK_STRIPES) { Mutex() }

    private fun lockFor(sessionId: String): Mutex = summaryLocks[(sessionId.hashCode() and 0x7fffffff) % SUMMARY_LOCK_STRIPES]

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class SummaryOwnership(
        val assistantId: String = "",
        val spaceId: String = "default",
    )

    /** Estructura de datos de la capa empresarial(Separado de la entidad,Proteger campos internos como snapshot/snapshotAt). */
    data class SummaryData(
        val sessionId: String,
        val createdAt: String,
        val updatedAt: String,
        val summary: String,
        val messageCount: Int,
        val sourceTimeRange: TimeContext.SourceTimeRange? = null,
        val snapshot: String = "",
        val snapshotAt: String? = null,
        val assistantId: String = "",
        val spaceId: String = "default",
    )

    /** Leer el resumen de la sesión especificada.,Devuelve nulo si no está presente. */
    suspend fun getSummary(sessionId: String): SummaryData? = withContext(Dispatchers.IO) {
        dao.get(sessionId)?.toSummaryData()
    }

    /** escribir resumen(Cubierto). */
    suspend fun saveSummary(sessionId: String, data: SummaryData) = withContext(Dispatchers.IO) {
        dao.upsert(data.toEntity(sessionId))
    }

    /** Obtener todo"sucio" session(summary !== snapshot),Para uso de memoria profunda. */
    suspend fun getDirtySessions(since: String? = null): List<SummaryData> = withContext(Dispatchers.IO) {
        dao.getDirty()
            .filter { since == null || it.updatedAt > since }
            .map { it.toSummaryData() }
    }

    /** Marcar la sesión como procesada por la memoria profunda(snapshot = summary). */
    suspend fun markProcessed(sessionId: String) = withContext(Dispatchers.IO) {
        dao.markProcessed(sessionId, Instant.now().toString())
    }

    /** Obtener todos los resúmenes(Orden descendente por update_at). */
    suspend fun getAllSummaries(): List<SummaryData> = withContext(Dispatchers.IO) {
        dao.getAll().map { it.toSummaryData() }
    }

    /**
     * Obtenga resúmenes para un rango de fechas específico.
     *
     * A-19: [mainAssistantId] Cuando no es nulo, solo se devuelve el asistente principal.(y líneas sin dueño sobrantes de la historia)Resumen de —
     * Tubería de compilación(today/facts)Inyectar en el ayudante principal prompt,Los resúmenes de las sesiones del subasistente no deben ser sesgados.
     * Semántica: assistant_id == mainAssistantId o vacío/Se conservan las filas de datos antiguas,El resto está excluido.
     */
    suspend fun getSummariesInRange(
        start: Instant,
        end: Instant,
        since: String? = null,
        mainAssistantId: String? = null,
        spaceId: String? = null,
    ): List<SummaryData> = withContext(Dispatchers.IO) {
        dao.getInRange(start.toString(), end.toString(), since, mainAssistantId, spaceId).map { it.toSummaryData() }
    }

    /** Borrar todos los resúmenes. */
    suspend fun clearAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    /**
     * P2: Eliminar el resumen de la sesión especificada(Se utiliza para la interfaz de usuario de la página de memoria para eliminar un solo elemento de la capa de resumen).
     * Devuelve si la eliminación fue exitosa(session Devuelve si no existe false).
     */
    suspend fun delete(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        // v1.78 (M1): El ajuste de las llamadas suspendidas DAO se debe realizar con resultOf,evitar tragar CancellationException
        resultOf {
            dao.deleteById(sessionId)
            true
        }.onError { msg, t ->
            // v1.78 (M5): Motivo del error de eliminación del registro
            Logger.w("SessionSummaryManager", "delete(${sessionId.take(8)}…) falló: $msg", t)
        }.getOrNull() ?: false
    }

    /**
     * Resumen de la sesión de actualización continua: Se dispara cada 10 rondas o al final de la sesión.
     * Si hay un resumen antiguo, el resumen antiguo + el diálogo nuevo se fusionarán para producir un resumen nuevo.(Portada,no agregar);
     * Si no hay un resumen antiguo, se genera directamente a partir de la conversación.
     *
     * @param sessionId sesión id
     * @param messages Historial completo de conversaciones(Contiene system / user / assistant)
     * @param model modelo objetivo
     * @param locale Idioma
     * @return Actualizar resultados(Contiene texto de resumen y bandera de cambio.;Vuelve la conversación vacía changed=false + Resumen vacío)
     */
    suspend fun rollingSummary(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        ownership: SummaryOwnership = SummaryOwnership(),
    ): RollingResult = withContext(Dispatchers.IO) {
        // v2.6.2(auditoría de memoria): Toda la lectura, modificación y escritura se serializa por sesión para evitar la sobrescritura simultánea.
        lockFor(sessionId).withLock {
            val draft = createRollingSummaryDraft(sessionId, messages, model, locale, timeZone, ownership)
                ?: return@withLock RollingResult(summary = "", changed = false)
            if (draft.changed && draft.data != null) {
                saveSummary(sessionId, draft.data)
            }
            RollingResult(summary = draft.summary, changed = draft.changed)
        }
    }

    /** rollingSummary El resultado de la devolución.v1.0.50: Agregar campo modificado,Deja que la persona que llama distinga"Cambio real"con"LLM Volver vacío". */
    data class RollingResult(
        val summary: String,
        val changed: Boolean,
    )

    /** Generar borrador(No realizar el pedido).null Indica que el diálogo vacío se omitirá directamente. */
    suspend fun createRollingSummaryDraft(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        ownership: SummaryOwnership = SummaryOwnership(),
    ): Draft? = withContext(Dispatchers.IO) {
        val existing = getSummary(sessionId)
        val prevSummary = existing?.summary ?: ""

        // incremento: Recibe solo mensajes nuevos desde el último resumen,Evite la explosión del contexto de sesiones largas
        val lastMessageCount = existing?.messageCount ?: 0
        val newMessages = if (lastMessageCount > 0 && lastMessageCount < messages.size) {
            messages.drop(lastMessageCount)
        } else {
            messages
        }

        val zone = TimeContext.resolveTimeZone(timeZone)
        val timestamps = messages.map { Instant.ofEpochMilli(it.createdAt).toString() }
        val sourceTimeRange = TimeContext.sourceTimeRangeOf(timestamps, zone)
        val convText = buildConversationText(newMessages, locale, zone)
        if (convText.isBlank()) {
            return@withContext Draft(prevSummary, changed = false, null)
        }
        // S-05: Máscara reversible del lado de entrada — Resumen de las llamadas de LLM no deben recibir texto claro PII(Número de teléfono celular/dirección/Número de tarjeta, etc.).
        // El texto saliente es un marcador de posición.([PHONE_1] espera),En el lado de salida, se restaura la desenmascaración y luego se realiza una limpieza para colocar los datos en la biblioteca.
        val maskedConv = PiiGuard.mask(convText)

        // Cuota resumida calculada en base a rondas completas de usuarios
        val turnCount = messages.count { it.role == io.zer0.ai.core.MessageRole.USER }
        val budget = rollingSummaryBudget(turnCount)

        val systemPrompt = RollingSummaryPrompt.buildSystemPrompt(locale = locale)
        val factTitle = RollingSummaryFormat.getFactSectionTitle(locale)
        val timelineTitle = RollingSummaryFormat.getTimelineSectionTitle(locale)
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }

        val factsBudget = maxOf(15, (budget.totalBudget * 0.3).toInt())
        val eventsBudget = budget.totalBudget - factsBudget
        val budgetText = if (isZh) {
            "$factTitle la mayoría $factsBudget palabras.$timelineTitle la mayoría $eventsBudget palabras."
        } else {
            "$factTitle max ${maxOf(
                10,
                (factsBudget * 0.6).toInt(),
            )} words. $timelineTitle max ${maxOf(20, (eventsBudget * 0.6).toInt())} words."
        }
        val prevLabel = if (isZh) "## Ya tengo un resumen." else "## Existing Summary"
        val newLabel = if (isZh) "## Agregar nueva conversación" else "## New Conversation"
        val budgetLabel = if (isZh) "## Este presupuesto resumido" else "## This Run's Summary Budget"
        val userContent = listOfNotNull(
            prevSummary.takeIf { it.isNotBlank() }?.let { "$prevLabel\n\n$it" },
            "$newLabel\n\n${maskedConv.masked}",
            "$budgetLabel\n\n$budgetText",
        ).joinToString("\n\n")

        val rawResult = resultOf {
            llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                model = model,
                temperature = 0.3f,
                maxTokens = budget.visibleMaxTokens,
            )
        }.onError { msg, t ->
            // v1.78 (M5): Registro continuoResumen motivo del fallo de LLM
            Logger.w("SessionSummaryManager", "rollingSummary LLM llamada fallida (${sessionId.take(8)}…): $msg", t)
        }.getOrNull() ?: return@withContext Draft(prevSummary, changed = false, null)

        var newSummary = rawResult.trim()
        if (newSummary.isBlank()) return@withContext Draft(prevSummary, changed = false, null)

        // Verificación de estructura antes de escribir + número limitado de reparaciones de formato
        var repairsUsed = 0
        var validation = RollingSummaryFormat.validate(newSummary)
        while (!validation.ok && repairsUsed < RollingSummaryFormat.MAX_REPAIRS) {
            repairsUsed++
            val repairSystemPrompt = RollingSummaryFormat.buildRepairPrompt(locale)
            val repairInput = RollingSummaryFormat.buildRepairInput(locale, validation.issues, newSummary)
            val repairResult = resultOf {
                llmClient.callText(
                    systemPrompt = repairSystemPrompt,
                    userContent = repairInput,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = budget.visibleMaxTokens,
                )
            }.onError { msg, t ->
                // v1.78 (M5): Motivo de error de LLM de reparación de formato de registro
                Logger.w("SessionSummaryManager", "Error en la llamada LLM para corregir el formato (${sessionId.take(8)}…, attempt=$repairsUsed): $msg", t)
            }.getOrNull() ?: break
            if (repairResult.isBlank()) {
                validation = RollingSummaryFormat.ValidationResult(false, validation.issues + "format repair returned empty")
                break
            }
            newSummary = repairResult.trim()
            validation = RollingSummaryFormat.validate(newSummary)
        }
        if (!validation.ok) {
            // Si no se puede reparar, conserve el resumen anterior.,No cubierto
            return@withContext Draft(prevSummary, changed = false, null)
        }

        // S-05: Restaurar marcador de posición de máscara(solo memoria local;Verificación de formato/Utilice marcadores de posición para la fase de reparación,LLM Sin contacto con el texto original.)
        newSummary = PiiGuard.unmask(newSummary, maskedConv.map)

        // PII Desensibilización
        val (scrubbed, detected) = PiiGuard.scrub(newSummary)
        if (detected.isNotEmpty()) newSummary = scrubbed

        // Compruébelo nuevamente después de la desensibilización.
        val finalValidation = RollingSummaryFormat.validate(newSummary)
        if (!finalValidation.ok) {
            return@withContext Draft(prevSummary, changed = false, null)
        }

        val now = Instant.now().toString()
        val data = SummaryData(
            sessionId = sessionId,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            summary = newSummary,
            messageCount = messages.size,
            sourceTimeRange = sourceTimeRange ?: existing?.sourceTimeRange,
            snapshot = existing?.snapshot ?: "",
            snapshotAt = existing?.snapshotAt,
            assistantId = ownership.assistantId,
            spaceId = ownership.spaceId,
        )
        Draft(newSummary, changed = true, data)
    }

    /** Borrador de resultados. */
    data class Draft(
        val summary: String,
        val changed: Boolean,
        val data: SummaryData?,
    )

    /**
     * Cálculo presupuestario resumido.
     * Escalado lineal por número de rondas.: Cuota de 40 palabras por ronda,10 Ronda limitada a 400 palabras.
     */
    private fun rollingSummaryBudget(turnCount: Int): Budget {
        val totalBudget = minOf(400, maxOf(40, turnCount * 40))
        val visibleMaxTokens = maxOf(150, minOf(750, totalBudget * 3 / 2))
        return Budget(totalBudget, visibleMaxTokens)
    }

    private data class Budget(val totalBudget: Int, val visibleMaxTokens: Int)

    /** Cree texto de conversación con marca de tiempo a partir de la lista de mensajes(Para resumen de LLM). */
    private fun buildConversationText(messages: List<UIMessage>, locale: String, zone: java.time.ZoneId): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val userLabel = if (isZh) "Usuario" else "User"
        val assistantLabel = if (isZh) "asistente" else "Assistant"
        val assistantVibeLabel = if (isZh) {
            "Los sentimientos expresados por el asistente en ese momento (Vibe Léame)"
        } else {
            "Assistant self-reported Vibe"
        }
        val parts = mutableListOf<String>()
        for (msg in messages) {
            val content = msg.content.trim()
            val assistantVibe = if (msg.role == io.zer0.ai.core.MessageRole.ASSISTANT) {
                VIBE_LINE_REGEX.find(msg.mood.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.take(240)
                    ?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            if (content.isEmpty() && assistantVibe == null) continue
            val timePrefix = if (msg.createdAt > 0) {
                val instant = Instant.ofEpochMilli(msg.createdAt)
                val formatted = TimeContext.formatSummaryTimestamp(instant, zone)
                "[$formatted] "
            } else {
                ""
            }
            val speaker = if (msg.role == io.zer0.ai.core.MessageRole.USER) userLabel else assistantLabel
            val messageText = buildList {
                assistantVibe?.let { add("【$assistantVibeLabel】Vibe: $it") }
                content.takeIf { it.isNotBlank() }?.let { add("【$speaker】$it") }
            }.joinToString("\n")
            parts.add("$timePrefix$messageText")
        }
        return parts.joinToString("\n\n")
    }

    // ════════════════════════════
    //  Entity ↔ SummaryData Convertir
    // ════════════════════════════

    private fun SessionSummaryEntity.toSummaryData(): SummaryData {
        val range = sourceTimeRange?.let {
            runCatching {
                json.decodeFromString(TimeContext.SourceTimeRange.serializer(), it)
            }.getOrElse { e ->
                // v1.78 (M5): Error de análisis de JSON de registro de sourceTimeRange
                Logger.w("SessionSummaryManager", "sourceTimeRange JSON El análisis falló: ${e.message}")
                null
            }
        }
        return SummaryData(
            sessionId = sessionId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            summary = summary,
            messageCount = messageCount,
            sourceTimeRange = range,
            snapshot = snapshot,
            snapshotAt = snapshotAt,
            assistantId = assistantId,
            spaceId = spaceId,
        )
    }

    private fun SummaryData.toEntity(sid: String): SessionSummaryEntity {
        val rangeJson = sourceTimeRange?.let {
            json.encodeToString(TimeContext.SourceTimeRange.serializer(), it)
        }
        return SessionSummaryEntity(
            sessionId = sid,
            createdAt = createdAt,
            updatedAt = updatedAt,
            summary = summary,
            messageCount = messageCount,
            sourceTimeRange = rangeJson,
            snapshot = snapshot,
            snapshotAt = snapshotAt,
            assistantId = assistantId,
            spaceId = spaceId,
        )
    }

    private companion object {
        /** v2.6.2: Número de fragmentos de bloqueo en serie resumidos (lo suficientemente grande como para reducir la probabilidad de colisión de diferentes sesiones). */
        const val SUMMARY_LOCK_STRIPES = 64
    }
}
