package io.zer0.memory.ai

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.observe.FailureClassifier
import io.zer0.memory.pii.PiiGuard
import io.zer0.memory.prompt.PromptExampleGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * v1.0.52 P2-3: AI La memoria del conductor se gestiona automáticamente.
 *
 * Responsabilidades:
 *  - Análisis periódico del historial de conversaciones.,Recuperar la memoria estructurada(entidad/relación/actualizar/fusionar/retrato)
 *  - Resultados del análisis de la tienda(crear/actualizar/Fusionar hechos + crear bordes de gráficos de conocimiento)
 *  - Clasificación automática por lotes y memoria sin clasificar.
 *
 * con [io.zer0.memory.deep.DeepMemoryProcessor] la diferencia:
 *  - DeepMemoryProcessor: Extraiga datos atómicos del resumen de diferencias(daily pipeline,Sin conexión)
 *  - MemoryAutoSaveScheduler: Extraiga recuerdos estructurados de conversaciones completas(tiempo real autoSave,en línea)
 *  Los dos se complementan:DeepMemory Realizar extracción de profundidad sin conexión,AutoSave Realice extracción en línea en tiempo real.
 *
 * tiempo de activación(Llamado por ChatViewModel):
 *  - sesión terminada/Al cambiar(notifySessionEndForCurrent),Hay una ventana de deduplicación de 30 segundos en la misma sesión en el lado que llama.
 *  - C-05: comentario correcto — En realidad no lo es"cada N rondas"gatillo,AUTO_SAVE_TURN_INTERVAL ya no se utiliza como condición desencadenante
 *    (Mantenga constantes solo para referencias compatibles);Cuando el análisis falla, el programador retrasa automáticamente la ejecución de recuperación una vez.(Omitir la ventana de deduplicación del lado de la llamada).
 *
 * control de concurrencia:usar [Semaphore] Limitar las tareas de análisis simultáneas(Predeterminado 1,Evite la limitación de LLM).
 */
class MemoryAutoSaveScheduler(
    private val factDbProvider: FactDbProvider,
    private val llmClient: MemoryLlmClient,
    private val scope: CoroutineScope,
    /**
     * v2.6.2(comentarios de los usuarios): Constructor de complementos de constelaciones de memoria.
     *
     * Una vez finalizada la sesión y se extraen los hechos, se utiliza un algoritmo determinista para completar los bordes (claves de entidad) entre los hechos./Misma sesión/Misma categoria/vecino semántico),
     * Dejemos que las constelaciones se conecten en una red. Omitir si es nulo (probar degradación).
     */
    private val edgeBuilder: MemoryEdgeBuilder? = null,
    /**
     * v2.6.2: Proveedor de vectores vecinos semánticos (servicio de incorporación inyectado por la capa de aplicación);
     * null Indica omitir la capa semántica (cuando no hay incrustación local).
     */
    private val semanticProvider: MemoryEdgeBuilder.SemanticProvider? = null,
    /**
     * v2.6.2: LLM Profundizar en el cambio de proveedor (leer la configuración del usuario); cuando devuelva verdadero, utilice un modelo grande para determinar el tipo de relación para los bordes débiles.
     */
    private val edgeEnhanceEnabled: (() -> Boolean)? = null,
) {

    companion object {
        /** LLM máximo token(memory extract Necesidad de generar resultados estructurados JSON,Dar suficiente espacio). */
        const val EXTRACT_MAX_TOKENS = 4096

        /** Límite superior en el tamaño del lote de clasificación automática. */
        const val CATEGORIZE_BATCH_SIZE = 10

        /** Clasifique automáticamente los tokens máximos de LLM. */
        const val CATEGORIZE_MAX_TOKENS = 2048

        /** Número máximo de conversaciones en el historial.(Evite entradas largas). */
        const val MAX_HISTORY_MESSAGES = 30

        /** El número máximo de caracteres en un solo mensaje del historial de conversaciones.(Truncar mensajes muy largos). */
        const val MAX_MESSAGE_CHARS = 500

        /** Número máximo de vistas previas de hechos existentes(Proporcionar una referencia para la deduplicación para LLM). */
        const val MAX_EXISTING_FACTS_PREVIEW = 20

        /** control de concurrencia:Solo se permite un análisis de autoguardado a la vez(Evite la limitación de LLM). */
        const val MAX_CONCURRENT_ANALYSIS = 1

        /** El intervalo de ronda de conversación que activa el guardado automático. */
        const val AUTO_SAVE_TURN_INTERVAL = 10

        /** v2.6.2: Cuando finaliza la sesión y se completa el margen, se considera como“nuevos hechos”el número más reciente. */
        private const val EDGE_NEW_FACTS_LIMIT = 20
    }

    // Los modelos suelen escribir explícitamente campos opcionales como nulos.coerceInputValues restaurará nulo a
    // Kotlin Valores predeterminados de propiedad, evitar mainProblem.title=null Deje que todo el resultado del análisis no se pueda analizar.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val analysisSemaphore = Semaphore(MAX_CONCURRENT_ANALYSIS)

    /**
     * v12 (T2-3): Caché de huellas digitales de contenido procesado(En proceso LRU,Acceso sincrónico).
     * claves idempotentes = sessionId + Huella histórica del mensaje(id+hash de contenido de los últimos 20 mensajes).
     * Llame solo a la extracción de LLM una vez para la misma sesión y el mismo historial.,Evite que AutoSave y DeepMemory utilicen el mismo contenido
     * Repetir extracción/doble escritura;Fallo no actualiza huella digital(Mantenga oportunidades para carreras de recuperación).
     */
    private val processedFingerprints = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
    }

    /**
     * v12 (T2-3): Calcular huellas digitales de mensajes históricos — toma el ultimo [MAX_HISTORY_MESSAGES] mensaje
     * (id + Consistente con la longitud de truncamiento realmente emitida a LLM content) Picadillo.id Idénticos y tienen el mismo contenido. → misma historia,Salto idempotente.
     *
     * La propia huella dactilar debe cubrir el historial completo:LLM La entrada todavía está sujeta a [MAX_HISTORY_MESSAGES] restricciones,
     * Sin embargo, cuando se edita un mensaje anterior, también se debe permitir la siguiente ejecución del guardado automático para evitar juzgar permanentemente la memoria anterior como procesada.
     */
    internal fun fingerprintHistory(history: List<UIMessage>): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        history.forEach { message ->
            // Incremental updates avoid allocating one giant joined string for long sessions.
            digest.update(message.role.name.toByteArray())
            digest.update(0.toByte())
            digest.update(message.id.toString().toByteArray())
            digest.update(0.toByte())
            digest.update(message.content.toByteArray())
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Estadísticas de resultados de análisis(Para personas que llaman/Observación de registros).
     */
    data class AnalysisResult(
        val extracted: Int = 0,
        val updated: Int = 0,
        val merged: Int = 0,
        val links: Int = 0,
        val profileUpdated: Boolean = false,
    )

    /**
     * Programar guardado automático(sin bloqueo,en [scope] ejecutado en).
     *
     * @param sessionId sesión id
     * @param history Historial de conversaciones(Últimos N artículos)
     * @param assistantId asistente id(decidir cuál usar FactDb,"default" asistente principal)
     * @param spaceId espacio de memoria id
     * @param scope alcance de la memoria(Generalmente lo mismo que asistenteId,El asistente principal es "main")
     * @param model modelo objetivo,null Por defecto en el lado de la implementación
     * @param locale Idioma
     */
    fun scheduleAutoSave(
        sessionId: String,
        history: List<UIMessage>,
        assistantId: String = "default",
        spaceId: String = "default",
        scope: String = "main",
        model: Model? = null,
        locale: String = "es",
    ) {
        if (history.isEmpty()) return
        // v12 (T2-3): Huella digital idempotente — Si la misma sesión y el mismo historial se han procesado correctamente, se omitirá.,
        // Evite la extracción repetida de LLM del mismo contenido(Escenario de escritura doble del mismo contenido con DeepMemory).
        // La identificación de la sesión se puede importar./Para reutilizar múltiples escenarios de asistente, la clave idempotente debe contener la dimensión de aislamiento de memoria.
        val fpKey = listOf(assistantId, scope, spaceId, sessionId).joinToString("|")
        val fp = fingerprintHistory(history)
        val alreadyProcessed = synchronized(processedFingerprints) {
            processedFingerprints[fpKey] == fp
        }
        if (alreadyProcessed) {
            Logger.d("MemoryAutoSaveScheduler", "autoSave salto idempotente(session=${sessionId.take(8)}…, El contenido no ha cambiado.)")
            return
        }
        this.scope.launch {
            analysisSemaphore.withPermit {
                resultOf {
                    runAutoSave(sessionId, history, assistantId, spaceId, scope, model, locale)
                }.onSuccess { result ->
                    if (result == null) {
                        // v2.x(Registro de solución de problemas): El análisis no resuelto es una falla esperada (aguas arriba content_filter /
                        // La respuesta vacía/el análisis de JSON falló), la huella digital no se recordará (aún se procesará la próxima vez) y no se generará ninguna excepción.
                        Logger.w(
                            "MemoryAutoSaveScheduler",
                            "autoSave Análisis no analizado(Fallo predecible, no se registran huellas dactilares): session=${sessionId.take(8)}…",
                        )
                    } else {
                        // v12 (T2-3): Las huellas dactilares se registrarán únicamente si el análisis y el almacenamiento son exitosos;LLM respuesta vacía/JSON Si el análisis falla, se debe realizar una ejecución de recuperación.
                        synchronized(processedFingerprints) { processedFingerprints[fpKey] = fp }
                        Logger.i(
                            "MemoryAutoSaveScheduler",
                            "autoSave Completo(session=${sessionId.take(8)}…): " +
                                "extracted=${result.extracted}, updated=${result.updated}, " +
                                "merged=${result.merged}, links=${result.links}",
                        )
                    }
                }.onError { msg, t ->
                    Logger.w("MemoryAutoSaveScheduler", "autoSave falló(session=${sessionId.take(8)}…): $msg", t)
                    // v2.6.9: Cuota/Saldo/Si el estado final de autenticación falla, no se programará una repetición de 30 segundos.
                    // Upstream ya se está acelerando(Agnes en el registro "not asked again before 02:49" Es el redactor ascendente),
                    // Si lo volvemos a presionar 30 segundos después, continuaremos obteniendo errores 402. restantes(Nerviosidad de la red/Limitación de corriente/analizar)Mantenga el comportamiento original.
                    if (FailureClassifier.isTerminalQuotaOrAuth(t)) {
                        Logger.w(
                            "MemoryAutoSaveScheduler",
                            "autoSave falló por la cuota/Error final de autenticación,No programado para carreras de recuperación(Intentarlo de nuevo sólo seguirá comiendo. 402/401): " +
                                "session=${sessionId.take(8)}…, kind=${FailureClassifier.classify(t)}",
                        )
                        return@onError
                    }
                    // C-05: Retrasar una ejecución de recuperación después de una falla — lado llamante( ChatViewModel)Tenga 30 segundos para deduplicar la misma sesión,
                    // Si vuelve a intentarlo inmediatamente después del error, la deduplicación lo bloqueará.,La memoria se pierde en silencio.;Retrasa 30 segundos para pasar por alto la ventana y recuperar la carrera.
                    // Nota:El parámetro alcance: Cadena de este método enmascarará el campo con el mismo nombre.,Se debe utilizar el procedimiento de respaldo.
                    // this@MemoryAutoSaveScheduler.scope(CoroutineScope) Referencia explícita.
                    this@MemoryAutoSaveScheduler.scope.launch {
                        delay(30_000)
                        if (!this@MemoryAutoSaveScheduler.scope.isActive) return@launch
                        analysisSemaphore.withPermit {
                            resultOf {
                                runAutoSave(sessionId, history, assistantId, spaceId, scope, model, locale)
                                // v2.x(Registro de solución de problemas): mismo camino principal —— null Indica que el análisis no se ha analizado y no se genera ninguna excepción.
                            }.onSuccess { retryResult ->
                                if (retryResult == null) {
                                    Logger.w(
                                        "MemoryAutoSaveScheduler",
                                        "autoSave La ejecución de compensación no pudo resolver el análisis(fracaso esperado): session=${sessionId.take(8)}…",
                                    )
                                } else {
                                    synchronized(processedFingerprints) { processedFingerprints[fpKey] = fp }
                                    Logger.i(
                                        "MemoryAutoSaveScheduler",
                                        "autoSave Carrera de maquillaje exitosa(session=${sessionId.take(8)}…): " +
                                            "extracted=${retryResult.extracted}, updated=${retryResult.updated}, " +
                                            "merged=${retryResult.merged}, links=${retryResult.links}",
                                    )
                                }
                            }.onError { retryMsg, retryT ->
                                Logger.w("MemoryAutoSaveScheduler", "autoSave La ejecución de recuperación aún falla(session=${sessionId.take(8)}…): $retryMsg", retryT)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Realizar guardado automático(Implementación de suspensión interna).
     *
     * pasos:
     *  1. Obtenga el ID del asistente correspondiente FactStore + MemoryLinkDao
     *  2. Cree una vista previa de los hechos existentes(Referencia para la deduplicación de LLM)
     *  3. Ajustar la extracción de LLM ParsedAnalysis
     *  4. applyAnalysis Cayó en la biblioteca
     */
    private suspend fun runAutoSave(
        sessionId: String,
        history: List<UIMessage>,
        assistantId: String,
        spaceId: String,
        scope: String,
        model: Model?,
        locale: String,
    ): AnalysisResult? = withContext(Dispatchers.IO) {
        val factStore = factDbProvider.getFactStore(assistantId)
        val linkDao = factDbProvider.getFactDb(assistantId).memoryLinkDao()

        // 1. Cree una vista previa de los hechos existentes(Obtenga los elementos MAX_EXISTING_FACTS_PREVIEW más recientes)
        val existingFacts = factStore.getByScopeAndSpace(scope, spaceId)
        val existingPreview = if (existingFacts.isEmpty()) {
            null
        } else {
            existingFacts.take(MAX_EXISTING_FACTS_PREVIEW).joinToString("\n") { f ->
                "- ${f.fact.take(60)}"
            }
        }

        // 2. Ajustar la extracción de LLM
        val analysis = extractEntities(history, existingPreview, model, locale)
            ?: return@withContext null

        // 3. Cayó en la biblioteca
        val result = applyAnalysis(analysis, factStore, linkDao, sessionId, spaceId, scope, assistantId)
        // v2.6.2(comentarios de los usuarios): Maquilla los bordes después de dejar caer el inventario. —— Deja que esta nueva incorporación/Los hechos y las acciones actualizados están conectados en red.
        // Estrategia incremental: tomar“Lote de hechos recientemente creado”Como un hecho nuevo (la ventaja suplementaria es incremental y no es necesario realizar un seguimiento preciso).
        // El fallo no afecta el proceso principal.
        edgeBuilder?.let { builder ->
            runCatching {
                val all = factStore.getByScopeAndSpace(scope, spaceId)
                val recent = all.sortedByDescending { it.createdAt }.take(EDGE_NEW_FACTS_LIMIT)
                if (recent.isNotEmpty()) {
                    builder.buildEdges(
                        newFacts = recent,
                        existingFacts = all,
                        scope = scope,
                        spaceId = spaceId,
                        semanticProvider = semanticProvider,
                        // v2.6.2: LLM Interruptor de profundización (configuración de usuario)
                        llmEnhance = edgeEnhanceEnabled?.invoke() ?: false,
                        model = model,
                    )
                }
            }.onFailure { Logger.w("MemoryAutoSaveScheduler", "Error al aplicar parches al final de la sesión(No afecta la memoria): ${it.message}") }
        }
        result
    }

    /**
     * AI Extraer entidades estructuradas(Llame al análisis LLM + JSON).
     *
     * @param history Historial de conversaciones
     * @param existingFactsPreview Vista previa de hechos ya disponible(Opcional,Referencia de deduplicación)
     * @param model modelo objetivo
     * @param locale Idioma
     * @return Analizar resultados(null Indica un error de análisis)
     */
    suspend fun extractEntities(
        history: List<UIMessage>,
        existingFactsPreview: String?,
        model: Model?,
        locale: String = "es",
    ): ParsedAnalysis? = withContext(Dispatchers.IO) {
        if (history.isEmpty()) return@withContext null

        val systemPrompt = MemoryExtractPrompt.buildSystemPrompt(locale, existingFactsPreview)
        // S-05: Máscara reversible del lado de entrada — La PII en el texto histórico se exporta como marcadores de posición,sin salir del dispositivo
        val maskedInput = PiiGuard.mask(buildHistoryText(history, locale))
        val userContent = maskedInput.masked

        val raw = try {
            llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                model = model,
                temperature = 0.3f,
                maxTokens = EXTRACT_MAX_TOKENS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("MemoryAutoSaveScheduler", "extractEntities LLM llamada fallida: ${e.message}", e)
            return@withContext null
        }

        // S-05: Analizar después de restaurar el marcador de posición(Al soltar la biblioteca, haga FactStore.add y luego realice una desensibilización completa)
        val parsed = parseAnalysisResult(PiiGuard.unmask(raw, maskedInput.map))
            ?: return@withContext null
        // v2.x: Ejemplo de prevención de fugas — Las entradas que copian los ejemplos de palabras clave y no se mencionan en el diálogo se eliminan directamente.,No queda en el inventario.
        PromptExampleGuard.sanitize(parsed, userContent)
    }

    /**
     * Analizar la salida de LLM es [ParsedAnalysis].
     *
     * Tolerancia a fallos:
     *  - Quitar <think>...</think> bloquear
     *  - Quitar ```json ... ``` valla
     *  - Extraer objeto JSON(Si no { comienzo,Profundidad del soporte de escaneo)
     *
     * internal Facilita las pruebas unitarias.
     */
    internal fun parseAnalysisResult(raw: String): ParsedAnalysis? {
        if (raw.isBlank()) return null

        var s = raw
        // 1. ir <think>...</think>
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        // 2. ir ```json ... ``` valla
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) {
            s = fenceMatch.groupValues[1]
        } else {
            s = s.trim()
        }

        // 3. Extraer objeto JSON
        if (!s.startsWith("{")) {
            val candidate = findJsonObjectCandidate(s) ?: run {
                Logger.w("MemoryAutoSaveScheduler", "Objeto JSON no encontrado (rawLen=${raw.length})")
                return null
            }
            s = candidate
        }

        // 4. analizar
        return runCatching {
            json.decodeFromString(ParsedAnalysis.serializer(), s)
        }.getOrElse {
            Logger.w("MemoryAutoSaveScheduler", "JSON El análisis falló (rawLen=${raw.length}): ${it.message}")
            null
        }
    }

    /**
     * Resultados del análisis de la tienda(crear/actualizar/Fusionar hechos + crear bordes de gráficos de conocimiento).
     *
     * @return Estadísticas de visitas sin cita previa
     */
    suspend fun applyAnalysis(
        analysis: ParsedAnalysis,
        factStore: FactStore,
        linkDao: MemoryLinkDao,
        sessionId: String,
        spaceId: String,
        scope: String,
        assistantId: String = factStore.assistantId,
    ): AnalysisResult = withContext(Dispatchers.IO) {
        var extracted = 0
        var updated = 0
        var merged = 0
        var links = 0

        // 1. Extraer nuevas entidades → escribir facts(Deduplicación y fusión automáticas)
        if (analysis.extractedEntities.isNotEmpty()) {
            val facts = analysis.userFactsOnly()
                .filter { it.content.isNotBlank() }
                .map { entity ->
                    FactStore.Fact(
                        fact = entity.content,
                        tags = entity.tags,
                        sessionId = sessionId,
                        importance = floatImportanceToInt(entity.importance),
                        category = entity.folderPath?.takeIf { it.isNotBlank() } ?: "general",
                        confidence = entity.credibility.coerceIn(0f, 1f),
                        source = "inferred",
                        // v12: Clave de normalización de la entidad proporcionada durante la fase de extracción.,Presione las teclas físicas para eliminar con precisión los duplicados al escribir
                        entityKey = entity.entityKey,
                    )
                }
            extracted = if (facts.isEmpty()) 0 else factStore.addBatch(facts, scope, spaceId)
        }

        // 2. Actualizar hechos existentes(Partido difuso por título de partido)
        if (analysis.updatedEntities.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            for (update in analysis.updatedEntities) {
                if (update.matchTitle.isBlank() || update.newContent.isBlank()) continue
                val target = findFactByTitle(existing, update.matchTitle) ?: continue
                // v2.6.2(auditoría de memoria): Texto/importancia/Clasificación/Las etiquetas se escriben de forma atómica dentro de la misma transacción,
                // Evite la escritura en tres pasos que lleve a sobrescribir o semiactualizar después de escribir.
                factStore.applyFieldUpdates(
                    id = target.id,
                    scope = scope,
                    spaceId = spaceId,
                    content = update.newContent,
                    importance = update.newImportance?.let { floatImportanceToInt(it) },
                    category = if (update.newFolderPath != null || update.newTags != null) {
                        update.newFolderPath
                    } else {
                        null
                    },
                    tags = if (update.newFolderPath != null || update.newTags != null) update.newTags else null,
                )
                updated++
            }
        }

        // 3. Fusionar recuerdos similares
        if (analysis.mergedEntities.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            for (merge in analysis.mergedEntities) {
                if (merge.mergedContent.isBlank() || merge.sourceTitles.isEmpty()) continue
                val sources = merge.sourceTitles.mapNotNull { title ->
                    findFactByTitle(existing, title)
                }
                if (sources.isEmpty()) continue
                // Crear nuevos hechos combinados
                val mergedFact = FactStore.Fact(
                    fact = merge.mergedContent,
                    sessionId = sessionId,
                    importance = sources.maxOf { it.importance },
                    category = sources.firstOrNull()?.category ?: "general",
                    confidence = sources.maxOf { it.confidence },
                    source = "inferred",
                )
                factStore.add(mergedFact, scope, spaceId)
                // Eliminar hecho fuente(y limpiar los asociados links)
                sources.forEach { src ->
                    factStore.delete(src.id, assistantId, scope, spaceId)
                }
                merged++
            }
        }

        // 4. Crear bordes de gráficos de conocimiento
        if (analysis.links.isNotEmpty()) {
            val existing = factStore.getByScopeAndSpace(scope, spaceId)
            val now = Instant.now().toString()
            val linkEntities = analysis.links.mapNotNull { link ->
                if (link.sourceTitle.isBlank() || link.targetTitle.isBlank()) return@mapNotNull null
                val source = findFactByTitle(existing, link.sourceTitle) ?: return@mapNotNull null
                val target = findFactByTitle(existing, link.targetTitle) ?: return@mapNotNull null
                MemoryLinkEntity(
                    sourceFactId = source.id,
                    targetFactId = target.id,
                    sourceTitle = link.sourceTitle,
                    targetTitle = link.targetTitle,
                    linkType = link.linkType,
                    weight = link.weight.coerceIn(0f, 1f),
                    spaceId = spaceId,
                    scope = scope,
                    createdAt = now,
                )
            }
            // Reparación de auditoría (C-07): Utilice la recopilación de datos más reciente para filtrar los identificadores eliminados antes de crear un enlace.
            // La resolución del enlace se basa en lo anterior. [existing] Instantánea; fusionar el paso de este análisis (paso 3) después de la instantánea y antes de insertar
            // o eliminación simultánea (UI/decay) puede haber eliminado el origen/destino, y la inserción directa dejaría bordes huérfanos de los hechos eliminados.
            // Por lo tanto, el conjunto actual de identificadores supervivientes se vuelve a verificar antes de la inserción y los bordes que apuntan a hechos eliminados se rechazan.
            if (linkEntities.isNotEmpty()) {
                val liveIds = factStore.getByScopeAndSpace(scope, spaceId).map { it.id }.toSet()
                val aliveLinks = linkEntities.filter { it.sourceFactId in liveIds && it.targetFactId in liveIds }
                if (aliveLinks.isNotEmpty()) {
                    linkDao.insertBatch(aliveLinks)
                    links = aliveLinks.size
                }
            }
        }

        // 5. Actualización del retrato del usuario(Guardado temporalmente como category="profile" el hecho,Almacenamiento independiente ampliable más adelante)
        if (!analysis.profileMarkdown.isNullOrBlank()) {
            val profileFact = FactStore.Fact(
                fact = analysis.profileMarkdown,
                tags = listOf("user_profile"),
                sessionId = sessionId,
                importance = 1,
                category = "identity",
                confidence = 1.0f,
                source = "inferred",
            )
            factStore.add(profileFact, scope, spaceId)
        }

        AnalysisResult(
            extracted = extracted,
            updated = updated,
            merged = merged,
            links = links,
            profileUpdated = !analysis.profileMarkdown.isNullOrBlank(),
        )
    }

    /**
     * Clasificación automática de recuerdos no clasificados.(Clasificación de IA por lotes).
     *
     * Buscar categoría como "general" el hecho,por lote [CATEGORIZE_BATCH_SIZE] Artículo,
     * Ajustar la asignación de LLM folderPath,Actualización a la base de datos.
     *
     * @param spaceId espacio de memoria
     * @param scope alcance de la memoria
     * @param assistantId asistente id
     * @param model modelo objetivo
     * @param locale Idioma
     * @return Número de hechos clasificados con éxito
     */
    suspend fun autoCategorizeMemories(
        spaceId: String = "default",
        scope: String = "main",
        assistantId: String = "default",
        model: Model? = null,
        locale: String = "es",
    ): Int = withContext(Dispatchers.IO) {
        val factStore = factDbProvider.getFactStore(assistantId)
        val all = factStore.getByScopeAndSpace(scope, spaceId)
        val uncategorized = all.filter { it.category == "general" }.take(CATEGORIZE_BATCH_SIZE)
        if (uncategorized.isEmpty()) return@withContext 0

        val systemPrompt = MemoryExtractPrompt.buildCategorizeSystemPrompt(locale)
        val userContent = uncategorized.joinToString("\n") { f ->
            "- ${f.fact.take(80)}"
        }

        val raw = try {
            llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                model = model,
                temperature = 0.2f,
                maxTokens = CATEGORIZE_MAX_TOKENS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("MemoryAutoSaveScheduler", "autoCategorize LLM llamada fallida: ${e.message}", e)
            return@withContext 0
        }

        val categorizations = parseCategorizeResult(raw) ?: return@withContext 0
        var count = 0
        for (cat in categorizations) {
            val target = findFactByTitle(uncategorized, cat.title) ?: continue
            val category = cat.folderPath.takeIf { it.isNotBlank() } ?: continue
            if (factStore.updateCategoryAndTags(target.id, category, null)) count++
        }
        count
    }

    // ── herramientas internas ──────────────────────────────────────────────────────

    /** Construir texto de historial de conversación(Para entrada LLM). */
    private fun buildHistoryText(history: List<UIMessage>, locale: String): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val userLabel = if (isZh) "Usuario" else "User"
        val assistantLabel = if (isZh) "asistente" else "Assistant"
        val titleLabel = if (isZh) "## Historial de conversaciones" else "## Conversation History"

        val recent = history.takeLast(MAX_HISTORY_MESSAGES)
        val sb = StringBuilder(titleLabel).append("\n\n")
        for (msg in recent) {
            val role = when (msg.role) {
                io.zer0.ai.core.MessageRole.USER -> userLabel
                io.zer0.ai.core.MessageRole.ASSISTANT -> assistantLabel
                io.zer0.ai.core.MessageRole.TOOL -> if (isZh) "Herramientas" else "Tool"
                io.zer0.ai.core.MessageRole.SYSTEM -> if (isZh) "sistema" else "System"
            }
            val content = msg.content.take(MAX_MESSAGE_CHARS)
            sb.append("**$role**: ").append(content).append("\n\n")
        }
        return sb.toString().trim()
    }

    /**
     * Datos de coincidencia difusa por título(usado para updatedEntities/mergedEntities/links).
     * Priorizar coincidencias exactas,El segundo contiene el partido.
     */
    private fun findFactByTitle(facts: List<FactStore.Fact>, title: String): FactStore.Fact? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        // 1. coincidencia exacta(ignorar caso)
        facts.firstOrNull { it.fact.equals(trimmed, ignoreCase = true) }?.let { return it }
        // 2. contiene coincidencias(el título es una subcadena de hecho,o hecho es una subcadena del título)
        facts.firstOrNull {
            it.fact.contains(trimmed, ignoreCase = true) || trimmed.contains(it.fact, ignoreCase = true)
        }?.let { return it }
        // 3. Coincidencias de prefijos de los primeros 20 caracteres
        val prefix = trimmed.take(20)
        facts.firstOrNull { it.fact.take(20).equals(prefix, ignoreCase = true) }?.let { return it }
        return null
    }

    /** Float importancia(0.0~1.0)→ Int importancia(0/1/2). */
    private fun floatImportanceToInt(importance: Float): Int = when {
        importance >= 0.7f -> 2
        importance >= 0.4f -> 1
        else -> 0
    }

    /** Escanear cadena para encontrar objeto JSON({ ... })candidato. */
    private fun findJsonObjectCandidate(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Clasifique automáticamente los elementos de resultados. */
    @kotlinx.serialization.Serializable
    private data class CategorizeItem(
        val title: String,
        val folderPath: String,
    )

    /** Analizar la salida LLM de clasificación automática. */
    private fun parseCategorizeResult(raw: String): List<CategorizeItem>? {
        if (raw.isBlank()) return null
        var s = raw
        s = s.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        val fenceMatch = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s)
        if (fenceMatch != null) {
            s = fenceMatch.groupValues[1]
        } else {
            s = s.trim()
        }

        if (!s.startsWith("[")) {
            val start = s.indexOf('[')
            val end = s.lastIndexOf(']')
            if (start < 0 || end <= start) return null
            s = s.substring(start, end + 1)
        }

        return runCatching {
            json.decodeFromString(ListSerializer(CategorizeItem.serializer()), s)
        }.getOrElse {
            Logger.w("MemoryAutoSaveScheduler", "categorize JSON El análisis falló: ${it.message}")
            null
        }
    }
}
