package io.zer0.memory.compile

// NOTE-i18n: section Título(hechos importantes/esperando hoy)Participar en el contrato de análisis de salida de LLM,Solo se puede extraer después de que se separen el contrato y la redacción y se cambie la estructura.

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.format.RollingSummaryFormat
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.prompt.CompilePrompts
import io.zer0.memory.state.CompiledMemoryState
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Compilador de memoria.
 *
 * Cuatro bloques se compilan de forma independiente. + assemble:
 *  - compileToday: Resumen de sesiones del día. → today.md(Room)
 *  - compileDaily: hoy borrador para el día terminado/Resumen → daily/{date}.md(Archivo)
 *  - assembleWeekFromDaily: daily/ Los ensamblajes de archivos puros N más recientes del directorio → week.md(cero LLM)
 *  - compileWeek: Entrada compatible,Prioridad assembleWeekFromDaily,Recurrir a la antigua ruta LLM cuando no existe ningún archivo diario
 *  - compileLongterm: week/daily El contenido se dobla en longterm.md(una vez al dia)
 *  - compileFacts: 30 sección de hechos del resumen del día → facts.md
 *  - rollDailyWindow: Elimine el archivo fuente después de desplegar la entrada diaria de la ventana y plegarla a largo plazo.
 *  - assemble: cuatro piezas empalmadas en memory.md(sincronizar,desafinado LLM)
 *
 * Cada bloque tiene un caché de huellas dactilares.: Omita la llamada LLM si la entrada no ha cambiado.
 * Las sesiones vacías no escriben huellas dactilares.(Evite quedar bloqueado por huellas dactilares durante el período de falla continua).
 */
/** D3-P2: Longitud máxima de línea única para la absorción del candidato(Las líneas extralargas se tratan como párrafos.,No incluido en la lista). */
private const val MAX_ABSORB_LINE_CHARS = 200

class MemoryCompiler(
    private val sectionDao: CompiledSectionDao,
    private val llmClient: MemoryLlmClient,
    /** v6: Salida documentada,Si es nulo, no escriba el archivo.(Fácil de probar). */
    private val fileWriter: MemoryFileWriter? = null,
    /**
     * Reparación de auditoría (S-04): Eliminar fuente de desecho(FactStore).
     *
     * Después de que el usuario borra la memoria.,Los hechos eliminados no deben volver a compilarse del resumen de la sesión."resurrección".compileFacts/
     * compileToday prensa [FactStore.getTombstones] Filtrar entrada y salida. El filtrado está deshabilitado cuando es nulo.
     */
    private val factStore: FactStore? = null,
    /** v3: scoped Compilar productos;null mantener pruebas antiguas/Caminos compatibles. */
    private val scopedSectionDao: io.zer0.memory.summary.ScopedCompiledSectionDao? = null,
    private val compileContext: MemoryCompileContext = MemoryCompileContext(),
    /** Resolve assistant-local facts and tombstones for scoped compile targets. */
    private val factDbProvider: FactDbProvider? = null,
) {

    /** Teclas de cuatro secciones. */
    enum class Section(val key: String) {
        FACTS("facts"),
        TODAY("today"),
        WEEK("week"),
        LONGTERM("longterm"),
        ;

        companion object {
            val ALL = listOf(FACTS, TODAY, WEEK, LONGTERM)
        }
    }

    /**
     * Resultados de la compilación.
     * v1.0.51: Nuevo FAILED — LLM Se utiliza cuando la llamada falla o devuelve una respuesta vacía.
     *   - COMPILED: Compilado y escrito con éxito.
     *   - SKIPPED: golpe de huella digital,No es necesario compilar(seguro)
     *   - FAILED: LLM Fracaso o respuesta vacía,no escribas,No marcado checkpoint,Inténtalo de nuevo la próxima vez
     */
    enum class Result { COMPILED, SKIPPED, FAILED }

    private suspend fun currentScope(): String = compileContext.getScope().ifBlank { "main" }

    private suspend fun currentSpaceId(): String = compileContext.getSpaceId().ifBlank { "default" }

    private suspend fun readStoredSection(
        key: String,
        target: MemoryCompileTarget? = null,
    ): io.zer0.memory.summary.ScopedCompiledSectionEntity? {
        val scope = target?.normalizedScope ?: currentScope()
        val spaceId = target?.normalizedSpaceId ?: currentSpaceId()
        val scoped = scopedSectionDao
        return if (scoped != null) {
            scoped.get(key, scope, spaceId)
                ?: if (target == null && scope == "main" && spaceId == "default") {
                    sectionDao.get(key)?.let { legacy ->
                        io.zer0.memory.summary.ScopedCompiledSectionEntity(
                            sectionKey = legacy.sectionKey, scope = scope, spaceId = spaceId,
                            content = legacy.content, fingerprint = legacy.fingerprint, updatedAt = legacy.updatedAt,
                        )
                    }
                } else {
                    null
                }
        } else {
            sectionDao.get(key)?.let { legacy ->
                io.zer0.memory.summary.ScopedCompiledSectionEntity(
                    sectionKey = legacy.sectionKey,
                    content = legacy.content,
                    fingerprint = legacy.fingerprint,
                    updatedAt = legacy.updatedAt,
                )
            }
        }
    }

    private suspend fun updateStoredContent(
        key: String,
        content: String,
        fingerprint: String?,
        now: String,
        target: MemoryCompileTarget? = null,
    ) {
        val scoped = scopedSectionDao
        if (scoped != null) {
            scoped.updateContent(
                key,
                target?.normalizedScope ?: currentScope(),
                target?.normalizedSpaceId ?: currentSpaceId(),
                content,
                fingerprint,
                now,
            )
        } else {
            sectionDao.updateContent(key, content, fingerprint, now)
        }
    }

    private suspend fun clearStoredAll(now: String) {
        scopedSectionDao?.clearAll(now) ?: sectionDao.clearAll(now)
    }

    private suspend fun clearStoredByKey(key: String, now: String, target: MemoryCompileTarget? = null) {
        scopedSectionDao?.clearByKey(
            key,
            target?.normalizedScope ?: currentScope(),
            target?.normalizedSpaceId ?: currentSpaceId(),
            now,
        ) ?: sectionDao.clearByKey(key, now)
    }

    private suspend fun upsertStored(
        key: String,
        content: String,
        fingerprint: String?,
        now: String,
        target: MemoryCompileTarget? = null,
    ) {
        val scoped = scopedSectionDao
        if (scoped != null) {
            scoped.updateContent(
                key,
                target?.normalizedScope ?: currentScope(),
                target?.normalizedSpaceId ?: currentSpaceId(),
                content,
                fingerprint,
                now,
            )
        } else {
            sectionDao.upsert(
                CompiledSectionEntity(
                    sectionKey = key,
                    content = content,
                    fingerprint = fingerprint,
                    updatedAt = now,
                ),
            )
        }
    }

    /** Leer el contenido actual de un bloque. */
    suspend fun readSection(section: Section): String = withContext(Dispatchers.IO) {
        readStoredSection(section.key)?.content ?: ""
    }

    suspend fun readSection(section: Section, target: MemoryCompileTarget): String = withContext(Dispatchers.IO) {
        readStoredSection(section.key, target)?.content ?: ""
    }

    /** Lea de acuerdo con el alcance explícito + espacio para la inyección del aviso del sistema para evitar depender de la ranura predeterminada global. */
    suspend fun readSection(section: Section, scope: String, spaceId: String): String = withContext(Dispatchers.IO) {
        val normalizedScope = scope.ifBlank { "main" }
        val normalizedSpace = spaceId.ifBlank { "default" }
        scopedSectionDao?.get(section.key, normalizedScope, normalizedSpace)?.content
            ?: if (normalizedScope == "main" && normalizedSpace == "default") {
                sectionDao.get(section.key)?.content
            } else {
                null
            }
            ?: ""
    }

    /**
     * Realice un escaneo de subcadenas palabra por palabra en el resumen compilado.
     *
     * Lo que se lee es el mismo contenido inyectado por el sistema.(Presione alcance + espacio para obtener,Revertir la copia antigua),
     * Y también quitar la fila de lápidas. —— El contenido eliminado por los usuarios no debe eliminarse debido a“También en abstracto”recuperado.
     *
     * @param query Cadena de consulta del usuario
     * @param scope alcance de la memoria
     * @param spaceId espacio de memoria
     * @param limit Número máximo de artículos devueltos
     */
    suspend fun searchCompiledSections(
        query: String,
        scope: String = "main",
        spaceId: String = "default",
        limit: Int = 10,
    ): List<CompiledSectionHit> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val normalizedScope = scope.ifBlank { "main" }
        val normalizedSpace = spaceId.ifBlank { "default" }
        val tombstones = loadTombstones(MemoryCompileTarget(scope = normalizedScope, spaceId = normalizedSpace))
        val sections = Section.ALL.map { section ->
            section.key to filterTombstonedLines(readSection(section, normalizedScope, normalizedSpace), tombstones)
        }
        scanCompiledSections(sections, query, limit)
    }

    /**
     * v1.0.53: Si hay secciones compiladas.
     * Se utiliza para la verificación diaria del progreso de la tubería.:El contenido de actualización heredado tiene semántica de ACTUALIZAR,
     * Las escrituras se pierden silenciosamente cuando la tabla está inicialmente vacía,Pero daily_state todavía registra la finalización → Es necesario restablecer el progreso y ejecutarlo nuevamente.
     */
    suspend fun hasAnyCompiledSection(): Boolean = withContext(Dispatchers.IO) {
        scopedSectionDao?.getAll()?.isNotEmpty() == true || sectionDao.getAll().isNotEmpty()
    }

    // ─── S-04: Quitar filtro de lápida(Evitar que los hechos eliminados se recuperen del resumen) ───

    /**
     * S-04: Filtrar líneas de texto que llegan a lápidas(Coincidencia de subcadenas normalizadas línea por línea).
     * Si la lápida está vacía, se devolverá tal cual.(camino de costo cero).
     * Revisión de corrección (2.0 B-07): La lógica de coincidencia se reduce a [FactStore.filterTombstonedLines]
     * (Compresión de espacios en blanco + eliminación de puntuación de doble canal),Este método preserva las pruebas de compatibilidad de firma y visibilidad.
     */
    internal fun filterTombstonedLines(text: String, tombstones: List<String>): String = FactStore.filterTombstonedLines(text, tombstones)

    /** Resolve the FactStore belonging to the explicit compile scope, preserving per-assistant isolation. */
    private fun factStoreFor(target: MemoryCompileTarget): FactStore? {
        val assistantId = target.assistantId?.takeIf { it.isNotBlank() }
            ?: target.normalizedScope.takeIf { it != "main" }
            ?: "default"
        return if (assistantId == "default") {
            factStore ?: factDbProvider?.getFactStore("default")
        } else {
            factDbProvider?.getFactStore(assistantId) ?: factStore
        }
    }

    private suspend fun effectiveTarget(
        target: MemoryCompileTarget? = null,
        assistantId: String? = null,
        scope: String? = null,
        spaceId: String? = null,
    ): MemoryCompileTarget {
        val resolvedScope = (scope ?: target?.scope ?: currentScope()).ifBlank { "main" }
        val resolvedSpace = (spaceId ?: target?.spaceId ?: currentSpaceId()).ifBlank { "default" }
        val resolvedAssistant = target?.assistantId?.takeIf { it.isNotBlank() }
            ?: assistantId?.takeIf { it.isNotBlank() }
            ?: resolvedScope.takeIf { it != "main" }
            ?: "default"
        return MemoryCompileTarget(resolvedAssistant, resolvedScope, resolvedSpace)
    }

    /** Tombstones are read from the assistant database and exact (scope, space) pair being compiled. */
    private suspend fun loadTombstones(target: MemoryCompileTarget? = null): List<String> {
        val resolved = effectiveTarget(target)
        return runCatching { factStoreFor(resolved)?.getTombstones(resolved.normalizedScope, resolved.normalizedSpaceId) }
            .getOrNull() ?: emptyList()
    }

    /**
     * S-04: Elimine inmediatamente los resultados de desecho de cada segmento inyectado compilado.
     *
     * Se llama después de que el usuario elimina un hecho de la página de memoria.(FactStore.delete Lápida marcada),No hay necesidad de esperar a la próxima
     * Compilación cronometrada — La eliminación tiene efecto inmediatamente después de la inyección. Devuelve si se ha eliminado algún contenido.
     *
     * Revisión de corrección (2.0 A-05): Cubrir todas las secciones de inyección. — Anteriormente solo se borraba el segmento FACTS,y TODAY/WEEK/
     * LONGTERM también inyectar system prompt(week Montado por el archivo diario cero LLM,longterm
     * generado por pliegue,No filtrar lápidas),La privacidad eliminada por los usuarios seguirá existiendo.
     */
    suspend fun purgeTombstonedFacts(target: MemoryCompileTarget? = null): Boolean = withContext(Dispatchers.IO) {
        val resolvedTarget = effectiveTarget(target)
        val tombstones = loadTombstones(resolvedTarget)
        if (tombstones.isEmpty()) return@withContext false
        var removedAny = false
        for (section in Section.ALL) {
            val current = readSection(section, resolvedTarget)
            val filtered = filterTombstonedLines(current, tombstones)
            if (filtered != current) {
                // Configuración de huellas dactilares null,Se garantiza que se volverá a fusionar la próxima vez que se compile(en vez de SKIPPED)
                updateStoredContent(section.key, filtered, null, Instant.now().toString(), resolvedTarget)
                val removedCount = countRemovedLines(current, filtered)
                Logger.i("MemoryCompiler", "purgeTombstonedFacts: ${section.key} sacrificio $removedCount OK")
                removedAny = true
            }
        }
        removedAny
    }

    private fun countRemovedLines(before: String, after: String): Int {
        val beforeLines = before.lines().filter { it.isNotBlank() }.size
        val afterLines = after.lines().filter { it.isNotBlank() }.size
        return (beforeLines - afterLines).coerceAtLeast(0)
    }

    /** Lea las cuatro piezas ensambladas. memory.md(Aviso de uso del sistema de inyección).
     *  Revisión de corrección (2.0 A-05): Las rutas de lectura de inyección se filtran uniformemente mediante lápidas — Purgar cualquier segmento que no se haya purgado a tiempo,
     *  El contenido eliminado no se filtrará system prompt(Coincidencia de doble canal ver FactStore.filterTombstonedLines). */
    suspend fun readCompiledMemoryMarkdown(
        locale: String = "es",
        scope: String? = null,
        spaceId: String? = null,
        target: MemoryCompileTarget? = null,
    ): String = withContext(Dispatchers.IO) {
        val resolvedTarget = effectiveTarget(target, scope = scope, spaceId = spaceId)
        val tombstones = loadTombstones(resolvedTarget)
        val read: suspend (Section) -> String = { section -> readSection(section, resolvedTarget) }
        val facts = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.FACTS), tombstones),
        )
        val today = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.TODAY), tombstones),
        )
        val week = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.WEEK), tombstones),
        )
        val longterm = CompiledMemoryState.normalizeSectionBody(
            filterTombstonedLines(read(Section.LONGTERM), tombstones),
        )
        val md = assembleCompiledMarkdown(facts, today, week, longterm, locale)
        // v6: Salida simultánea al sistema de archivos.,Fácil de depurar y realizar copias de seguridad
        fileWriter?.writeMemoryMd(md, locale, resolvedTarget)
        md
    }

    /**
     * Compilar hoy: hoy sessions → today.md.
     */
    suspend fun compileToday(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "es",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
        /**
         * A-19: asistente principal id — Cuando no es nulo, solo se compila el asistente principal.(y datos antiguos sin dueño)Resumen de,
         * Los resúmenes de las sesiones del subasistente no deben serializarse con los inyectados por el asistente principal."hoy"segmento.
         */
        mainAssistantId: String? = null,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId ?: mainAssistantId
        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val logicalDay = io.zer0.memory.time.TimeContext.logicalDayFor(Instant.now(), zone)
        val sessions = summaryManager.getSummariesInRange(
            start = logicalDay.rangeStart,
            end = Instant.now(),
            mainAssistantId = assistantId,
            spaceId = target?.normalizedSpaceId,
        )

        if (sessions.isEmpty()) {
            // Sesiones vacías: Borrar contenido,Sin huellas dactilares
            val current = readSection(Section.TODAY, target ?: MemoryCompileTarget(assistantId = assistantId))
            if (current.isNotEmpty()) {
                updateStoredContent(Section.TODAY.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }

        // huella digital: sessions de (id, updated_at) empalme md5
        // S-04: Lápidas incorporadas a las huellas dactilares — La huella digital cambia después de que el usuario elimina datos,Reedición forzada para eliminar contenido eliminado
        val tombstones = loadTombstones(effectiveTarget(target, assistantId))
        val fpKeys = sessions.joinToString("\n") { "${it.sessionId}:${it.updatedAt}" } +
            "\nT:" + tombstones.joinToString("|")
        val fp = fingerprint(fpKeys)
        val existing = readStoredSection(Section.TODAY.key, target)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        // S-04: Filtro de entrada por lápida,Los hechos eliminados no aparecen en los candidatos de hoy.
        val sessionInput = sessions.joinToString("\n\n---\n\n") { filterTombstonedLines(it.summary, tombstones) }
        if (sessionInput.isBlank()) {
            val current = readSection(Section.TODAY, target ?: MemoryCompileTarget(assistantId = assistantId))
            if (current.isNotEmpty()) {
                updateStoredContent(Section.TODAY.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }
        // v1.0.51: inyectar corriente facts,Informe al LLM qué hechos se han registrado.,Evite la repetición en el día de hoy.
        val currentFacts = readSection(Section.FACTS, target ?: MemoryCompileTarget(assistantId = assistantId)).trim()
        val input = if (currentFacts.isNotBlank()) {
            val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
            val factsLabel = if (isZh) {
                "## hechos materiales registrados(como referencia,No repita en hoy)"
            } else {
                "## Already Recorded Facts (for reference, do not repeat in today)"
            }
            "$factsLabel\n\n$currentFacts\n\n---\n\n$sessionInput"
        } else {
            sessionInput
        }
        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildTodayPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                maxTokens = 450,
            )
        }.onError { msg, t ->
            // v1.78 (M1): Registrar el motivo del fracaso del LLM,Evite excepciones silenciosas
            Logger.w("MemoryCompiler", "compileToday LLM llamada fallida: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: Defensa de respuesta nula — LLM Volver vacío/Cuando se utilizan etiquetas puras, el contenido existente no se sobrescribirá.,Devolver FALLADO para el próximo reintento
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileToday")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileToday: LLM Devolver respuesta vacía,Mantener contenido antiguo,Regresar FAILED")
            return@withContext Result.FAILED
        }
        // v2.6.2(auditoría de memoria): La lectura y la escritura deben utilizar el mismo target —— La implementación anterior utiliza destino para lectura y falla para escritura.
        // resultados en sub-ayudante/Los resultados de la compilación del espacio no predeterminado se escriben en la ranura predeterminada del tiempo de ejecución.
        updateStoredContent(Section.TODAY.key, normalized, fp, Instant.now().toString(), target)
        Result.COMPILED
    }

    /**
     * El día que terminó la recopilación. → memory/daily/{date}.md.
     *
     * v6.1: Ingrese prioridad para usar la versión final del borrador de hoy el día anterior([yesterdayTodayDraft]).
     * Cuando falte el borrador, recurra a la compilación del resumen de la sesión del día actual.,Primer día de actualización garantizado/Los datos no se pierden cuando se pierde el estado.
     * No se generarán archivos cuando no haya contenido ese día.(Ocupación cero).
     *
     * @param logicalDate día lógico para compilar(yyyy-MM-dd),Generalmente ayer
     * @param yesterdayTodayDraft Borrador final del segmento Room Today del día anterior,puede ser null
     */
    @Suppress("LongParameterList")
    suspend fun compileDaily(
        summaryManager: SessionSummaryManager,
        logicalDate: String,
        yesterdayTodayDraft: String? = null,
        model: Model?,
        locale: String = "es",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
        /**
         * Revisión de corrección (2.0 B-09): asistente principal id — camino sucio(Compilar por resumen de sesión cuando no hay borrador de hoy)
         * Obtenga solo el resumen del asistente principal,No se debe aprobar el resumen del subasistente. daily → week → longterm La plataforma de cadena se inyecta en el asistente principal.
         */
        mainAssistantId: String? = null,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId ?: mainAssistantId
        if (fileWriter == null) return@withContext Result.SKIPPED

        val date = runCatching { LocalDate.parse(logicalDate) }.getOrNull()
            ?: return@withContext Result.SKIPPED

        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val dayStart = date.atStartOfDay(zone)
            .plusHours(io.zer0.memory.time.TimeContext.LOGICAL_DAY_CUTOVER_HOUR.toLong())
            .toInstant()
        val dayEnd = dayStart.plus(1, ChronoUnit.DAYS)

        // Priorizar el uso del borrador de ayer de hoy;Si falta, volverá al resumen de la sesión del día actual.
        val input = when {
            !yesterdayTodayDraft.isNullOrBlank() -> yesterdayTodayDraft.trim()
            else -> {
                val sessions = summaryManager.getSummariesInRange(
                    start = dayStart,
                    end = dayEnd,
                    mainAssistantId = assistantId,
                    spaceId = target?.normalizedSpaceId,
                )
                if (sessions.isEmpty()) {
                    fileWriter.deleteDailyMd(logicalDate, target)
                    return@withContext Result.COMPILED
                }
                // B-09: El camino inferior también filtra las lápidas. — No se permite la entrada de contenido eliminado daily → week → longterm cadena de inyección
                val tombstones = loadTombstones(effectiveTarget(target, assistantId))
                sessions.joinToString("\n\n---\n\n") { filterTombstonedLines(it.summary, tombstones) }
            }
        }

        if (input.isBlank()) {
            fileWriter.deleteDailyMd(logicalDate, target)
            return@withContext Result.COMPILED
        }

        val fp = fingerprint(input)
        val existingFp = fileWriter.readDailyFingerprint(logicalDate, target)
        if (existingFp == fp && fileWriter.readDailyEntryBody(logicalDate, target).isNotBlank()) {
            return@withContext Result.SKIPPED
        }

        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildDailyPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                // v1.0.51: 100 → 250 — 100 token Alrededor de 50 a 70 palabras en chino,Como un día es demasiado corto para finalizar,
                // conducirá a daily → week → longterm El enlace pierde demasiados detalles.
                // 250 token Alrededor de 120-175 palabras,Ventana de 1200 caracteres con semana.(6dia × 200palabra)partido
                maxTokens = 250,
            )
        }.onError { msg, t ->
            Logger.w("MemoryCompiler", "compileDaily($logicalDate) LLM llamada fallida: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: Defensa de respuesta nula — No escribas archivos diarios vacíos.,Devolver FALLADO para el próximo reintento
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileDaily")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileDaily($logicalDate): LLM Devolver respuesta vacía,Regresar FAILED")
            return@withContext Result.FAILED
        }
        fileWriter.writeDailyMd(logicalDate, normalized, target)
        fileWriter.writeDailyFingerprint(logicalDate, fp, target)
        Result.COMPILED
    }

    /**
     * Ensamblando archivos simples desde el directorio diario/ week.md(cero LLM).
     * Obtenga los últimos N días de entradas del diario y concatenelos en orden de fecha positivo,Truncar desde la entrada más antigua si es demasiado larga.
     */
    suspend fun assembleWeekFromDaily(
        maxDays: Int = MemoryFileWriter.DAILY_WINDOW_RETENTION_DAYS,
        maxChars: Int = MemoryFileWriter.WEEK_ASSEMBLY_MAX_CHARS,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (fileWriter == null) {
            // Borrar el segmento de la semana cuando no hay ningún escritor de archivos,Evite dejar atrás contenido antiguo
            val current = readStoredSection(Section.WEEK.key, target)?.content ?: ""
            if (current.isNotEmpty()) {
                updateStoredContent(Section.WEEK.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }

        val assembled = fileWriter.assembleWeekFromDaily(maxDays, maxChars, target).trim()
        if (assembled.isEmpty()) {
            val current = readStoredSection(Section.WEEK.key, target)?.content ?: ""
            if (current.isNotEmpty()) {
                updateStoredContent(Section.WEEK.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }

        val fp = fingerprint(assembled)
        val existing = readStoredSection(Section.WEEK.key, target)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        updateStoredContent(Section.WEEK.key, assembled, fp, Instant.now().toString(), target)
        Result.COMPILED
    }

    /**
     * Compilar para la semana: priorizar el ensamblaje LLM cero del directorio diario;
     * Volver a la ruta anterior cuando no hay un archivo diario(Compilado por el resumen de sesiones de 7 días LLM),Contenido garantizado el primer día de actualización.
     */
    suspend fun compileWeek(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "es",
        timeZone: String = io.zer0.memory.time.TimeContext.DEFAULT_TIMEZONE,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId
        val assembledResult = assembleWeekFromDaily(target = target)
        if (assembledResult == Result.COMPILED || assembledResult == Result.SKIPPED) {
            val currentWeek = readStoredSection(Section.WEEK.key, target)?.content ?: ""
            if (currentWeek.isNotBlank()) return@withContext assembledResult
        }

        // camino alternativo:Compile con el resumen de sesión de 7 días LLM cuando no hay un archivo diario(viejo comportamiento)
        val now = Instant.now()
        val zone = io.zer0.memory.time.TimeContext.resolveTimeZone(timeZone)
        val logicalDay = io.zer0.memory.time.TimeContext.logicalDayFor(now, zone)
        val sevenDaysAgo = logicalDay.rangeStart.minus(7, ChronoUnit.DAYS)
        val sessions = summaryManager.getSummariesInRange(
            start = sevenDaysAgo,
            end = now,
            mainAssistantId = assistantId,
            spaceId = target?.normalizedSpaceId,
        )

        if (sessions.isEmpty()) {
            val current = readStoredSection(Section.WEEK.key, target)?.content ?: ""
            if (current.isNotEmpty()) {
                updateStoredContent(Section.WEEK.key, "", null, Instant.now().toString(), target)
            }
            return@withContext Result.COMPILED
        }

        val fpKeys = sessions.joinToString("\n") { "${it.sessionId}:${it.updatedAt}" }
        val fp = fingerprint(fpKeys)
        val existing = readStoredSection(Section.WEEK.key, target)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        val tombstones = loadTombstones(effectiveTarget(target, assistantId))
        val input = sessions.joinToString("\n\n---\n\n") { filterTombstonedLines(it.summary, tombstones) }
        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildWeekPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                maxTokens = 600,
            )
        }.onError { msg, t ->
            Logger.w("MemoryCompiler", "compileWeek LLM llamada fallida: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: Defensa de respuesta nula — No sobrescribir existente week,Sin huellas dactilares(evitar el encierro),Devolver FALLADO para el próximo reintento
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileWeek")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileWeek: LLM Devolver respuesta vacía,Mantener contenido antiguo,Regresar FAILED")
            return@withContext Result.FAILED
        }
        updateStoredContent(Section.WEEK.key, normalized, fp, Instant.now().toString(), target)
        Result.COMPILED
    }

    /**
     * Doble las entradas diarias sacadas de la ventana de días N en longterm,Eliminar el archivo fuente después del éxito;
     * Las entradas fallidas permanecen en el directorio diario/,Pasarlo a la siguiente ronda para volver a intentarlo.,No descartado en silencio.
     */
    suspend fun rollDailyWindow(
        model: Model?,
        locale: String = "es",
        referenceDate: String = LocalDate.now().toString(),
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (fileWriter == null) return@withContext Result.SKIPPED

        val roll = fileWriter.rollDailyWindow(referenceDate, target = target)
        if (roll.combinedContent.isBlank()) {
            return@withContext Result.COMPILED
        }

        val result = foldIntoLongTerm(roll.combinedContent, model, locale, target)
        // v1.0.51: Eliminar el archivo fuente solo si está COMPILADO — FAILED reservado para el próximo reintento,SKIPPED(golpe de huella digital)tiempo
        //   longterm Ya contiene el mismo contenido.,seguro para eliminar
        if (result == Result.COMPILED || result == Result.SKIPPED) {
            fileWriter.deleteDailyFiles(roll.folded, target)
        }
        result
    }

    /**
     * Compilar a largo plazo: Week.md plegarlo en longterm.md.
     * fingerprint = fingerprint(weekContent),week Si no hay cambios, omítelo.
     */
    suspend fun compileLongterm(model: Model?, locale: String = "es", target: MemoryCompileTarget? = null): Result =
        foldIntoLongTerm(readStoredSection(Section.WEEK.key, target)?.content ?: "", model, locale, target)

    private suspend fun foldIntoLongTerm(
        newContent: String,
        model: Model?,
        locale: String = "es",
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val trimmed = newContent.trim()
        if (trimmed.isBlank()) return@withContext Result.SKIPPED

        val fp = fingerprint(trimmed)
        val existing = readStoredSection(Section.LONGTERM.key, target)
        if (existing?.fingerprint == fp && existing.content.isNotEmpty()) {
            return@withContext Result.SKIPPED
        }

        val prevLongterm = (readStoredSection(Section.LONGTERM.key, target)?.content ?: "").trim()
        // Revisión de corrección (B-21): límite superior de truncamiento 2000 → 4000,Mitigar el descarte irreversible de conocimientos de largo plazo.
        // Mantenga hasta 4000 caracteres de contenido antiguo en cada pliegue,Deje suficiente espacio en la salida de LLM para contenido nuevo
        // (maxTokens=600 Alrededor de 2400 caracteres);La parte recortada se adjunta a"Registro de cultivos"Iniciar sesión,Rastree fácilmente el conocimiento perdido.
        // Consulte el informe de tareas para obtener instrucciones sobre compensaciones.: Contrato LLM de buildLongtermPrompt sin cambios,Solo subir tapa + recorte de registro.
        val prevLongtermCapped = if (prevLongterm.length > 4000) {
            val trimmedTail = prevLongterm.takeLast(prevLongterm.length - 4000)
            Logger.d(
                "MemoryCompiler",
                "foldIntoLongTerm: truncar viejo longterm(${prevLongterm.length} → 4000 chars),perdido ${trimmedTail.length} chars: ${trimmedTail.take(
                    500,
                )}…",
            )
            prevLongterm.take(4000)
        } else {
            prevLongterm
        }
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val input = if (prevLongtermCapped.isNotBlank()) {
            val prevLabel = if (isZh) "## Última situación a largo plazo" else "## Previous long-term context"
            val newLabel = if (isZh) "## Nuevo contenido depositado" else "## Newly settled content"
            "$prevLabel\n\n$prevLongtermCapped\n\n$newLabel\n\n$trimmed"
        } else {
            val newLabel = if (isZh) "## Nuevo contenido depositado" else "## Newly settled content"
            "$newLabel\n\n$trimmed"
        }

        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildLongtermPrompt(locale),
                userContent = input,
                model = model,
                temperature = 0.3f,
                maxTokens = 600,
            )
        }.onError { msg, t ->
            Logger.w("MemoryCompiler", "foldIntoLongTerm LLM llamada fallida: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: Defensa de respuesta nula — No sobrescribir existente longterm,Sin huellas dactilares(evitar el encierro),Regresar FAILED
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileLongterm")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "foldIntoLongTerm: LLM Devolver respuesta vacía,Mantener contenido antiguo,Regresar FAILED")
            return@withContext Result.FAILED
        }
        updateStoredContent(Section.LONGTERM.key, normalized, fp, Instant.now().toString(), target)
        Result.COMPILED
    }

    /**
     * Compile los hechos: sección de hechos del resumen de 30 días → facts.md.
     * No se requieren huellas dactilares(Corre cada vez,Pero la entrada contiene prevFacts,LLM Fusiónate).
     *
     * v0.32: Acceso [MemoryConfig.compileThreshold] —— Calcular la puntuación de deterioro por edad de la sesión,
     * El segmento de hechos de la sesión con una puntuación inferior al umbral no ingresa en la entrada LLM(Se filtran los recuerdos de baja puntuación).
     * Predeterminado [MemoryConfig] umbral predeterminado más bajo 4.5 < Predeterminado baseImportance 10,30 No se filtrará en unos días.,
     * Equivalente al antiguo comportamiento;Solo tendrá efecto si el usuario aumenta el umbral o reduce la importancia base.
     *
     * @param config configuración de memoria(por [io.zer0.memory.ticker.MemoryTicker] paso a través)
     */
    // v2.6.2: Después de agregar spaceId, el cuerpo de la función alcanza las 80 líneas después del filtrado.(límite umbral), y aumenta el número de sucursales; comparado con el existente
    // CyclomaticComplexMethod Exención por la misma causa(Responsabilidades del proceso de compilación centralizado), exentos punto por punto según la práctica del proyecto.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun compileFacts(
        summaryManager: SessionSummaryManager,
        model: Model?,
        locale: String = "es",
        config: MemoryConfig = MemoryConfig(),
        /**
         * A-19: asistente principal id — Cuando no es nulo, solo se compila el asistente principal.(y datos antiguos sin dueño)Resumen de,
         * Los resúmenes de las sesiones del subasistente no deben serializarse con los inyectados por el asistente principal."hechos importantes"segmento.
         */
        mainAssistantId: String? = null,
        target: MemoryCompileTarget? = null,
    ): Result = withContext(Dispatchers.IO) {
        val assistantId = target?.assistantId ?: mainAssistantId
        val resolvedTarget = effectiveTarget(target, assistantId)
        val store = factStoreFor(resolvedTarget)
        val now = Instant.now()
        // L4: Aquí, se utiliza el tiempo absoluto ahora-30d en lugar de la alineación lógica del día.(A diferencia de la semana de compilación).
        // Razón: compileFacts es una ventana deslizante de 30 días,longitud de la ventana(30 dia),Sesgo de atribución de límites entre días
        // El impacto es insignificante en grandes ventanales.;Y la ventana de compilación de la semana es de solo 7 días.,Las desviaciones de los límites entre días son relativamente mayores,
        // Por lo tanto, compileWeek usa logicDay.rangeStart para alinear el día a las 04:00. Aquí no se requiere alineación.
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val sessions = summaryManager.getSummariesInRange(
            start = thirtyDaysAgo,
            end = now,
            mainAssistantId = assistantId,
            spaceId = resolvedTarget.normalizedSpaceId,
        )

        // Extraiga el segmento de hechos de cada resumen.
        // v0.32: Presione simultáneamente (updatedAt edad + config) Calcular fracciones,Filtre los siguientes: compileThreshold session
        // S-04: Filtrado de lápidas — Datos eliminados del producto antiguo./candidato abstracto/LLM Eliminación de salida de tres vías,protegerse contra"resurrección"
        val tombstones = loadTombstones(resolvedTarget)
        val rawPrevFacts = readSection(Section.FACTS, target ?: MemoryCompileTarget(assistantId = assistantId)).trim()
        val prevFacts = filterTombstonedLines(rawPrevFacts, tombstones)
        if (prevFacts != rawPrevFacts) {
            // La eliminación tiene efecto inmediatamente: Elimine primero los productos compilados antiguos,No es necesario esperar a la reprogramación de LLM
            updateStoredContent(Section.FACTS.key, prevFacts, null, Instant.now().toString(), target)
        }

        val factParts = mutableListOf<String>()
        var skippedByThreshold = 0
        for (s in sessions) {
            if (s.summary.isBlank()) continue
            if (!RollingSummaryFormat.hasFactSectionHeading(s.summary)) continue
            val text = RollingSummaryFormat.extractFactSection(s.summary)
            if (text.isNotBlank() && !RollingSummaryFormat.isEmptyFactSection(text)) {
                val ageDays = ageInDays(s.updatedAt, now)
                val score = MemoryConfig.factScore(ageDays, config)
                if (!MemoryConfig.shouldCompile(score, config)) {
                    skippedByThreshold++
                    continue
                }
                // S-04: Eliminación de filas que llega a la lápida dentro de la sección de hechos resumidos;Si se eliminan todos, el resumen no se incluirá en la lista de candidatos.
                val filtered = filterTombstonedLines(text, tombstones)
                if (filtered.isBlank()) continue
                factParts.add(filtered)
            }
        }
        if (skippedByThreshold > 0) {
            // v1.78 (M2): Registre el número de sesiones filtradas por el umbral,Conveniente configuración de umbral de compilación de depuración
            Logger.d("MemoryCompiler", "compileFacts: $skippedByThreshold sessions skipped by threshold")
        }

        if (factParts.isEmpty()) {
            // no hay hechos nuevos: mantener viejo facts(Filtrado por lápida;Cuando se eliminan todos, se borra la parte superior.)
            return@withContext Result.COMPILED
        }

        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val newFacts = factParts.joinToString("\n")
        val combined = if (prevFacts.isNotBlank()) {
            val existingLabel = if (isZh) "## existente Facts" else "## Existing Facts"
            val newLabel = if (isZh) "## Agregar candidato Facts" else "## New Candidate Facts"
            "$existingLabel\n\n$prevFacts\n\n$newLabel\n\n$newFacts"
        } else {
            val newLabel = if (isZh) "## Agregar candidato Facts" else "## New Candidate Facts"
            "$newLabel\n\n$newFacts"
        }

        val result = resultOf {
            llmClient.callText(
                systemPrompt = CompilePrompts.buildFactsPrompt(locale),
                userContent = combined,
                model = model,
                temperature = 0.3f,
                maxTokens = 300,
            )
        }.onError { msg, t ->
            // v1.78 (M1): Registrar el motivo del fracaso del LLM
            Logger.w("MemoryCompiler", "compileFacts LLM llamada fallida: $msg", t)
        }.getOrNull() ?: return@withContext Result.FAILED

        // v1.0.51: Defensa de respuesta nula — No sobrescribir existente facts,Devolver FALLADO para el próximo reintento
        val normalized = CompiledMemoryState.normalizeLlmResult(result, "compileFacts")
        if (normalized.isBlank()) {
            Logger.w("MemoryCompiler", "compileFacts: LLM Devolver respuesta vacía,mantener viejo facts,Regresar FAILED")
            return@withContext Result.FAILED
        }
        // S-04: LLM Filtrar la salida nuevamente(Evite que LLM vuelva a contar hechos eliminados)
        val filteredResult = filterTombstonedLines(normalized, tombstones)
        updateStoredContent(Section.FACTS.key, filteredResult, null, Instant.now().toString(), resolvedTarget)
        // D3-P2: Absorción de candidatos + proyección determinista — LLM Los productos se introducen primero en la tabla como candidatos.(Prevenir la pérdida de huérfanos de digestión),
        // Luego, el segmento HECHOS se superpone con la proyección de la tabla de hechos.(Editar/Eliminar inmediatamente).
        store?.let { scopedStore ->
            val scope = resolvedTarget.normalizedScope
            val spaceId = resolvedTarget.normalizedSpaceId
            resultOf { absorbFactLines(filteredResult, scope, spaceId, tombstones, scopedStore) }
            resultOf { scopedStore.getByScopeAndSpace(scope, spaceId) }
                .onSuccess { resultOf { reconcileFactsSectionWithStore(it, resolvedTarget) } }
                .onError { msg, t -> Logger.w("MemoryCompiler", "compileFacts Proyección fallida(Mantenga los productos LLM): ${t?.message ?: msg}") }
        }
        Result.COMPILED
    }

    /**
     * D3-P2: FACTS Los segmentos convergen con la tabla de hechos. — La conciliación se actualiza a "proyección determinista".
     *
     * proceso:
     *  1) absorber: Filas del segmento actual que no están en la tabla de hechos(Compilar producto huérfano)Entrada complementaria en la tabla(Prevenir pérdidas después de la proyección);
     *  2) proyección: Representar segmentos de FACTS con los datos de tabla más recientes(uno por línea,Alineado con los formatos de productos LLM existentes).
     *
     * hasta ahora"Editar/Eliminar/Nuevo"son inmediatamente consistentes en la siguiente ronda de proyección;v12 Retiro de la semántica de conciliación línea por línea,
     * El filtrado Tombstone todavía actúa como absorbente/Conservas de filtrado de entrada proyectada(S-04).
     *
     * v18: Las aperturas numéricas de las dos secciones son diferentes. —— Absorber ver tabla completa(De lo contrario, la memoria de aparición se tratará como un producto huérfano y se volverá a registrar.),
     * Sólo proyección“presente y no reemplazado”de filas(El resultado de la proyección es el segmento FACTS inyectado en el modelo.).
     *
     * @return 1 = El contenido de la sección ha sido actualizado.;0 = Sin cambios(Contiene tabla vacía/Sin contenido)
     */
    suspend fun reconcileFactsSectionWithStore(facts: List<io.zer0.memory.fact.FactStore.Fact>, target: MemoryCompileTarget? = null): Int =
        withContext(Dispatchers.IO) {
            val resolvedTarget = effectiveTarget(target)
            val tombstones = loadTombstones(resolvedTarget)
            val scope = resolvedTarget.normalizedScope
            val spaceId = resolvedTarget.normalizedSpaceId
            val store = factStoreFor(resolvedTarget)

            // 1) Absorber huérfanos(factStore Cuando esté disponible;Se omitió la línea de impacto de Tombstone,protegerse contra"Eliminar resurrección")
            if (store != null) {
                val currentForAbsorb = readSection(Section.FACTS, resolvedTarget)
                if (currentForAbsorb.isNotBlank()) {
                    absorbFactLines(currentForAbsorb, scope, spaceId, tombstones, store)
                }
            }

            // 2) proyección(factStore Vuelva a verificar la tabla más reciente cuando esté disponible;De lo contrario, recurra a la instantánea entrante,Compatible con escenarios fuera de la tienda)
            //    v18: Proyectar significa inyectar contenido,Para tomar números includeSuppressed=false —— Apareció(puntuación por debajo del umbral)
            //    Los viejos recuerdos que han sido reemplazados no se escriben en el contexto. El período de absorción no se ve afectado.:Busca toda la tabla por sí solo.([absorbFactLines]),
            //    Así que aparece/Las líneas reemplazadas no se tratan como“Huérfano”Vuelva a grabar.
            val source = if (store != null) {
                resultOf { store.getByScopeAndSpace(scope, spaceId, includeSuppressed = false) }
                    .getOrNull() ?: return@withContext 0
            } else {
                facts
            }
            val current = readSection(Section.FACTS, resolvedTarget)
            if (source.isEmpty()) {
                if (current.isBlank()) return@withContext 0
                clearStoredByKey(Section.FACTS.key, Instant.now().toString(), resolvedTarget)
                Logger.i("MemoryCompiler", "FACTS Segmento borrado: La tabla de hechos está vacía(scope=$scope, space=$spaceId)")
                return@withContext 1
            }
            val projected = FactsSectionProjector.project(
                source.filter { filterTombstonedLines(it.fact, tombstones).isNotBlank() },
            )
            if (projected.isBlank()) {
                if (current.isBlank()) return@withContext 0
                clearStoredByKey(Section.FACTS.key, Instant.now().toString(), target)
                Logger.i("MemoryCompiler", "FACTS Segmento borrado: Todos los datos filtrados por lápidas(scope=$scope, space=$spaceId)")
                return@withContext 1
            }
            if (projected.trim() == current.trim()) return@withContext 0
            // usar @Insert(REPLACE) en vez de updateContent(UPSERT Error de sintaxis informado en la versión SQLite durante la prueba parcial);
            // REPLACE Semántica consistente para secciones_compiladas sin claves externas(Eliminar y reconstruir en caso de conflicto).
            upsertStored(
                key = Section.FACTS.key,
                content = projected,
                fingerprint = null,
                now = Instant.now().toString(),
                target = resolvedTarget,
            )
            Logger.i("MemoryCompiler", "FACTS proyección determinista de segmento: ${projected.lines().size} Artículo / ${projected.length} personaje")
            1
        }

    /**
     * D3-P2: Agregar a la tabla entradas en la fila de texto que no están en la tabla de hechos(protegerse contra"Sólo en el producto compilado, no incluido en la tabla."el hecho
     * perdido después de la proyección — Resumen del parche de compatibilidad huérfano).
     *
     * filtrar: Línea vacía / # línea de título / línea de acierto de lápida(Resurrección anti-eliminación) / fila extra larga(> [MAX_ABSORB_LINE_CHARS]
     * Tratar como párrafo obsoleto);ir "- " prefijo;Se omiten las filas que equivalen a la normalización de las entradas de la tabla.(Anti-duplicación).
     * entradas absorbentes source = "compiled",Heredar alcance/espacio.
     *
     * @return Número real de tiras absorbidas
     */
    private suspend fun absorbFactLines(text: String, scope: String, spaceId: String, tombstones: List<String>, store: FactStore?): Int {
        store ?: return 0
        val existing = resultOf { store.getByScopeAndSpace(scope, spaceId) }
            .getOrNull()?.map { normalizeLine(it.fact) }?.toSet() ?: return 0
        var absorbed = 0
        val seen = mutableSetOf<String>()
        for (raw in text.lines()) {
            val trimmed = raw.trim().removePrefix("-").trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (trimmed.length > MAX_ABSORB_LINE_CHARS) continue
            if (filterTombstonedLines(trimmed, tombstones).isBlank()) continue
            val norm = normalizeLine(trimmed)
            if (norm.isBlank() || norm in existing || !seen.add(norm)) continue
            resultOf {
                store.add(
                    io.zer0.memory.fact.FactStore.Fact(fact = trimmed, source = "compiled"),
                    scope = scope,
                    spaceId = spaceId,
                )
            }.onSuccess { absorbed++ }
                .onError { msg, t -> Logger.w("MemoryCompiler", "La aceptación del candidato falló: ${t?.message ?: msg}") }
        }
        if (absorbed > 0) {
            Logger.i("MemoryCompiler", "FACTS absorción candidata: $absorbed Tabla de entrada del suplemento de hechos del producto de compilación de artículos")
        }
        return absorbed
    }

    /** v12: Comparación de igualdad normalizada(Eliminar asunto + mayúsculas y minúsculas + ancho completo + espacio en blanco,Se utiliza para unir filas de productos con entradas de la tabla de hechos.). */
    private fun normalizedEq(a: String, b: String): Boolean {
        val na = normalizeLine(a)
        val nb = normalizeLine(b)
        return na.isNotBlank() && na == nb
    }

    private fun normalizeLine(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("^(Usuario|yo|el|ella|este usuario|the user|i am|he is|she is)\\s*"), "")
            .trim()
            .toHalfWidth()
            .replace(whiteSpaceRe, " ")
            .trim()
    }

    /**
     * v12: Ángulo completo a medio ángulo(letras/numeros/Símbolos comunes),Se utiliza para realizar coincidencias de escritura cruzada entre filas de productos y entradas de la tabla de hechos.
     * espacio de ancho completo(U+3000)Convertir a espacios normales,Compresión unificada por whiteSpaceRe.
     */
    private fun String.toHalfWidth(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            val code = ch.code
            when {
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                code == 0x3000 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** v12: Comprimir espacios en blanco consecutivos(Coincidencia de filas de productos). */
    private val whiteSpaceRe = Regex("\\s+")

    /** Borrar todos los productos compilados(Para restablecer la memoria). */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        clearStoredAll(Instant.now().toString())
    }

    /**
     * P2: Borrar el contenido de la sección especificada.(Se utiliza para recordar la interfaz de usuario de la página para eliminar un segmento único de la capa de compilación.).
     * No eliminar filas,Solo contenido claro/huella digital,Mantenga upsert directamente para la próxima compilación.
     */
    suspend fun clearSection(section: Section) = withContext(Dispatchers.IO) {
        clearStoredByKey(section.key, Instant.now().toString())
    }

    /**
     * P2: Escribe directamente el contenido de la sección especificada.(Se utiliza para la edición de la interfaz de usuario de la página de memoria. Compilar segmento único de capa.).
     * Borrar fingerprint,Para que la próxima compilación programada se pueda regenerar normalmente.
     */
    suspend fun writeSection(section: Section, content: String) = withContext(Dispatchers.IO) {
        upsertStored(
            key = section.key,
            content = content,
            fingerprint = null,
            now = Instant.now().toString(),
        )
    }

    /** P2-33: Presione alcance explícito + espacio para escribir directamente lo especificado section(La página de memoria se edita según el ámbito/espacio seleccionado actualmente.).
     *  La sección de escritura original depende del contexto de compilación. currentScope/currentSpaceId(Predeterminado main/default),
     *  sub-asistente/La sección de compilación de otros espacios se escribió en la ranura incorrecta cuando la interfaz de usuario la editó.,con ruta de lectura inyectada(traer scope/space)Dislocación. */
    suspend fun writeSection(section: Section, content: String, scope: String, spaceId: String) = withContext(Dispatchers.IO) {
        upsertStored(
            key = section.key,
            content = content,
            fingerprint = null,
            now = Instant.now().toString(),
            target = MemoryCompileTarget(
                scope = scope,
                spaceId = spaceId,
            ),
        )
    }

    /** montar memory.md(4 ## párrafo de título,Marcador de posición de escritura de segmento vacío). */
    private fun assembleCompiledMarkdown(facts: String, today: String, week: String, longterm: String, locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val empty = if (isZh) "(Ninguno todavía)" else "(none)"
        val factsTitle = if (isZh) "hechos importantes" else "Key facts"
        val todayTitle = if (isZh) "hoy" else "Today"
        val weekTitle = if (isZh) "a principios de esta semana" else "Earlier this week"
        val longtermTitle = if (isZh) "situación a largo plazo" else "Long-term context"
        val section = { title: String, content: String ->
            "## $title\n\n${content.ifBlank { empty }}"
        }
        return listOf(
            section(factsTitle, facts),
            section(todayTitle, today),
            section(weekTitle, week),
            section(longtermTitle, longterm),
        ).joinToString("\n\n") + "\n"
    }

    private fun fingerprint(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * poner sesión updatedAt(ISO cuerda)Convertir a distancia [now] número de días.
     * Devuelto cuando falla el análisis o se invierte el tiempo 0(equivalente a"acaba de pasar",score más alto,No se filtrará por umbral).
     */
    private fun ageInDays(updatedAtIso: String, now: Instant): Float {
        val updated = runCatching { Instant.parse(updatedAtIso) }.getOrNull() ?: return 0f
        val ms = now.toEpochMilli() - updated.toEpochMilli()
        if (ms < 0) return 0f
        return ms / (1000f * 60 * 60 * 24)
    }
}
