package io.zer0.memory.ai

import io.zer0.common.Logger
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * v2.6.2(comentarios de los usuarios): Constructor de "borde complementario" de constelaciones de memoria.
 *
 * Antecedentes: Los mapas de constelaciones apenas estaban conectados antes. —— Los únicos puntos marginales son los extraídos por LLM al final de la sesión. `links`,
 * Requiere que los títulos de origen/destino coincidan con precisión con las entidades sorteadas en la misma ronda. El umbral es demasiado alto y la mayoría de los recuerdos son puntos aislados.
 *
 * Este constructor utiliza**Algoritmo determinista fuera de línea**Complete los bordes para conectar las constelaciones en una red (se prioriza la densidad visual, se permiten bordes débiles):
 *
 *  1. **Misma clave de entidad**(Fuerte): Los hechos con la misma clave de entidad son consecutivos. `related_to`(weight alto)
 *  2. **Misma sesión**(Débil): Los hechos extraídos con el mismo ID de sesión están conectados (weight bajo)
 *  3. **Misma categoría + cierre en el tiempo**(Débil): Bordes conectados con la misma categoría y tiempo de creación cercano (weight bajo)
 *  4. **vecino semántico**(Centro): use la incrustación para buscar vecinos más cercanos existentes para obtener nuevos datos (se puede desactivar, depende de la calidad de la incrustación)
 *
 * Puntos de diseño:
 *  - **incremento**:Sólo para "Agregar datos" × Los "datos bursátiles" se calculan como márgenes, sin volver a calcular el importe total, y se pueden controlar miles de recuerdos.
 *  - **idempotente**: Saltar cuando el mismo par de hechos ya tenga aristas (sin inserción repetida).
 *  - **Eliminar duplicados**: Deduplicación no dirigida (A-B Tratado igual que B-A).
 *  - **Se puede apagar**: El vecino semántico se basa en la incrustación. La incrustación de palabras clave locales tiene un efecto limitado y se puede desactivar.
 */
@Suppress("LongParameterList", "ReturnCount", "LoopWithTooManyJumpStatements")
class MemoryEdgeBuilder(
    private val linkDao: MemoryLinkDao,
    /**
     * v2.6.2(comentarios de los usuarios): LLM Profundizar las conexiones (opcional).
     *
     * Después de encenderlo, el“Vecinos semánticos pero relación poco clara”borde, utilice un modelo de herramienta grande para determinar el tipo de relación específica
     * (causes / contradicts / part_of). Una llamada LLM por borde, costosa, controlada por conmutadores.
     */
    private val llmClient: io.zer0.memory.llm.MemoryLlmClient? = null,
) {

    /** Resultados de ventaja complementarios. */
    data class BuildResult(
        val entityEdges: Int = 0,
        val sessionEdges: Int = 0,
        val categoryEdges: Int = 0,
        val semanticEdges: Int = 0,
    ) {
        val total: Int get() = entityEdges + sessionEdges + categoryEdges + semanticEdges
    }

    /** Parámetros de solicitud de relleno de bordes (parámetros convergentes de buildEdges para facilitar la lectura y la evolución). */
    data class BuildRequest(
        val newFacts: List<FactStore.Fact>,
        val existingFacts: List<FactStore.Fact>,
        val scope: String,
        val spaceId: String,
        val semanticProvider: SemanticProvider? = null,
        val semanticThreshold: Float = DEFAULT_SEMANTIC_THRESHOLD,
        val semanticTopK: Int = DEFAULT_SEMANTIC_TOPK,
        /** Si se debe utilizar LLM para profundizar el tipo de relación específica de los bordes débiles (requiere que llmClient no sea nulo). */
        val llmEnhance: Boolean = false,
        val model: io.zer0.ai.core.Model? = null,
    )

    /** Entrada cómoda compatible con firmas antiguas. */
    suspend fun buildEdges(
        newFacts: List<FactStore.Fact>,
        existingFacts: List<FactStore.Fact>,
        scope: String,
        spaceId: String,
        semanticProvider: SemanticProvider? = null,
        semanticThreshold: Float = DEFAULT_SEMANTIC_THRESHOLD,
        semanticTopK: Int = DEFAULT_SEMANTIC_TOPK,
        llmEnhance: Boolean = false,
        model: io.zer0.ai.core.Model? = null,
    ): BuildResult = buildEdges(
        BuildRequest(
            newFacts = newFacts,
            existingFacts = existingFacts,
            scope = scope,
            spaceId = spaceId,
            semanticProvider = semanticProvider,
            semanticThreshold = semanticThreshold,
            semanticTopK = semanticTopK,
            llmEnhance = llmEnhance,
            model = model,
        ),
    )

    /**
     * Complete los límites entre un lote de "hechos nuevos" y hechos existentes.
     *
     * Sólo incremental: nuevos hechos × Stock (incluidos otros hechos nuevos). Se omiten los bordes existentes (no dirigidos), lo que garantiza la idempotencia.
     */
    @Suppress("CyclomaticComplexMethod")
    suspend fun buildEdges(req: BuildRequest): BuildResult = withContext(Dispatchers.IO) {
        if (req.newFacts.isEmpty() || req.existingFacts.size < 2) return@withContext BuildResult()

        // Conjunto de bordes existente (deduplicación no dirigida)—— evitar la inserción duplicada
        val existingPairs = linkDao.listBySpaceAndScope(req.spaceId, req.scope)
            .mapTo(mutableSetOf()) { pairKey(it.sourceFactId, it.targetFactId) }
        val ctx = MutableBuildContext(req, existingPairs)

        // 1) Misma clave de entidad (fuerte)
        val byEntity = req.existingFacts.filter { !it.entityKey.isNullOrBlank() }.groupBy { it.entityKey!! }
        for (newFact in req.newFacts) {
            val key = newFact.entityKey?.takeIf { it.isNotBlank() } ?: continue
            byEntity[key]?.forEach { other ->
                if (other.id != newFact.id) ctx.tryAdd(newFact, other, ENTITY_WEIGHT, Kind.ENTITY)
            }
        }

        // 2) Misma sesión (débil)
        val bySession = req.existingFacts.filter { !it.sessionId.isNullOrBlank() }.groupBy { it.sessionId!! }
        for (newFact in req.newFacts) {
            val sid = newFact.sessionId?.takeIf { it.isNotBlank() } ?: continue
            bySession[sid]?.forEach { other ->
                if (other.id != newFact.id) ctx.tryAdd(newFact, other, SESSION_WEIGHT, Kind.SESSION)
            }
        }

        // 3) Misma categoría + cierre en el tiempo (débil)
        val byCategory = req.existingFacts.groupBy { it.category }
        for (newFact in req.newFacts) {
            byCategory[newFact.category]?.forEach { other ->
                if (other.id == newFact.id) return@forEach
                if (withinWindow(newFact.createdAt, other.createdAt)) {
                    ctx.tryAdd(newFact, other, CATEGORY_WEIGHT, Kind.CATEGORY)
                }
            }
        }

        // 4) Vecinos semánticos (medio)—— Depende de la incrustación, puede fallar, omitir si falla
        val semanticCount = if (req.semanticProvider != null) {
            runCatching { ctx.buildSemanticEdges() }
                .onFailure { Logger.w(TAG, "El complemento de borde semántico falló(saltar): ${it.message}") }
                .getOrDefault(0)
        } else {
            0
        }

        if (ctx.pending.isNotEmpty()) {
            // v2.6.2: LLM profundizar —— Determine el tipo de relación específico para el borde débil. Las fallas se degradarán una por una, sin afectar el cuerpo principal del suplemento de borde.
            if (req.llmEnhance && llmClient != null) {
                enhanceWithLlm(ctx.pending, req.model)
            }
            runCatching { linkDao.insertBatch(ctx.pending) }
                .onFailure { Logger.w(TAG, "No se pudo agregar el lado al inventario: ${it.message}") }
        }
        Logger.i(
            TAG,
            "Borde complementario completado: entidad=${ctx.counts[Kind.ENTITY]} sesión=${ctx.counts[Kind.SESSION]} " +
                "Clasificación=${ctx.counts[Kind.CATEGORY]} Semántica=$semanticCount",
        )
        BuildResult(
            entityEdges = ctx.counts[Kind.ENTITY] ?: 0,
            sessionEdges = ctx.counts[Kind.SESSION] ?: 0,
            categoryEdges = ctx.counts[Kind.CATEGORY] ?: 0,
            semanticEdges = semanticCount,
        )
    }

    /** Categoría de borde (para estadísticas). */
    private enum class Kind { ENTITY, SESSION, CATEGORY }

    /** El estado mutable de un side-fill. */
    private inner class MutableBuildContext(
        private val req: BuildRequest,
        private val existingPairs: MutableSet<Long>,
    ) {
        val pending = mutableListOf<MemoryLinkEntity>()
        val counts = mutableMapOf<Kind, Int>()
        private val now = Instant.now().toString()

        fun tryAdd(a: FactStore.Fact, b: FactStore.Fact, weight: Float, kind: Kind) {
            if (a.id == b.id) return
            val key = pairKey(a.id, b.id)
            if (!existingPairs.add(key)) return
            pending.add(makeEdge(a.id, b.id, a.fact, b.fact, "related_to", weight))
            counts[kind] = (counts[kind] ?: 0) + 1
        }

        fun makeEdge(sourceId: Long, targetId: Long, sourceText: String, targetText: String, type: String, weight: Float) =
            MemoryLinkEntity(
                sourceFactId = sourceId,
                targetFactId = targetId,
                sourceTitle = sourceText.take(TITLE_MAX),
                targetTitle = targetText.take(TITLE_MAX),
                linkType = type,
                weight = weight.coerceIn(0f, 1f),
                spaceId = req.spaceId,
                scope = req.scope,
                createdAt = now,
            )

        suspend fun buildSemanticEdges(): Int {
            val provider = req.semanticProvider ?: return 0
            val others = req.existingFacts
            if (others.isEmpty()) return 0
            val texts = req.newFacts.map { it.fact } + others.map { it.fact }
            val vectors = provider.embed(texts)
            if (vectors.size != texts.size) return 0
            val newVectors = vectors.subList(0, req.newFacts.size)
            val otherVectors = vectors.subList(req.newFacts.size, vectors.size)
            var count = 0
            for (i in req.newFacts.indices) {
                val nv = newVectors[i]
                val scored = others.mapIndexed { j, other -> other to cosine(nv, otherVectors[j]) }
                    .filter { it.second >= req.semanticThreshold && it.first.id != req.newFacts[i].id }
                    .sortedByDescending { it.second }
                    .take(req.semanticTopK)
                for ((other, sim) in scored) {
                    if (!existingPairs.add(pairKey(req.newFacts[i].id, other.id))) continue
                    pending.add(
                        makeEdge(req.newFacts[i].id, other.id, req.newFacts[i].fact, other.fact, "related_to", sim),
                    )
                    count++
                }
            }
            return count
        }
    }

    /** Abstracción del proveedor de vectores semánticos (evita que el módulo de memoria dependa del EmbeddingService de la aplicación). */
    fun interface SemanticProvider {
        suspend fun embed(texts: List<String>): List<FloatArray>
    }

    /**
     * v2.6.2: Profundización en tipos de relación específicos de aristas débiles con LLM.
     *
     * solo manejar linkType==related_to El borde de (clave de entidad/Los tipos existentes no se mueven). Llamado uno por uno, si falla, se conservará el tipo original.
     */
    private suspend fun enhanceWithLlm(pending: MutableList<MemoryLinkEntity>, model: io.zer0.ai.core.Model?) {
        val client = llmClient ?: return
        for (i in pending.indices) {
            val edge = pending[i]
            if (edge.linkType != "related_to") continue
            val judged = runCatching {
                val raw = client.callText(
                    systemPrompt = EDGE_ENHANCE_SYSTEM_PROMPT,
                    userContent = "A: ${edge.sourceTitle}\nB: ${edge.targetTitle}",
                    model = model,
                    temperature = 0.2f,
                    maxTokens = 32,
                    timeoutMs = 20_000L,
                )
                parseLinkType(raw)
            }.getOrNull() ?: continue
            if (judged != null) {
                pending[i] = edge.copy(linkType = judged)
            }
        }
    }

    /** Resolver el tipo de relación a partir del resultado de LLM; devuelve nulo si no se reconoce. */
    private fun parseLinkType(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            "contradict" in lower -> "contradicts"
            "causes" in lower || "causa y efecto" in raw || "because" in lower -> "causes"
            "part_of" in lower || "part of" in lower || "contiene" in raw || "pertenecer a" in raw -> "part_of"
            "related" in lower || "asociación" in raw -> "related_to"
            else -> null
        }
    }

    private fun pairKey(a: Long, b: Long): Long {
        val lo = min(a, b)
        val hi = max(a, b)
        // Combinado en una clave única: use 64 bits;id Es un número positivo que aumenta automáticamente y la probabilidad de conflicto es insignificante.
        return (lo shl 32) or (hi and 0xFFFFFFFFL)
    }

    private fun withinWindow(a: String, b: String): Boolean {
        val ta = runCatching { Instant.parse(a).toEpochMilli() }.getOrNull() ?: return false
        val tb = runCatching { Instant.parse(b).toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(ta - tb) <= CATEGORY_TIME_WINDOW_MS
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom <= 0f) 0f else dot / denom
    }

    companion object {
        private const val TAG = "MemoryEdgeBuilder"
        private const val TITLE_MAX = 60

        /** Misma clave de entidad: relación sólida. */
        const val ENTITY_WEIGHT = 0.85f

        /** Misma sesión: Media a débil. */
        const val SESSION_WEIGHT = 0.45f

        /** Misma categoría + cierre en el tiempo: débil. */
        const val CATEGORY_WEIGHT = 0.3f

        /** Umbral predeterminado del vecino semántico (suelto, persiguiendo la densidad visual B). */
        const val DEFAULT_SEMANTIC_THRESHOLD = 0.55f

        /** Cada nuevo hecho tiene como máximo varios vecinos semánticos. */
        const val DEFAULT_SEMANTIC_TOPK = 5

        /** Misma categoría + ventana de tiempo (24 horas). */
        private const val CATEGORY_TIME_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** v2.6.2: LLM Aviso del sistema para profundizar los tipos de relaciones. */
        private const val EDGE_ENHANCE_SYSTEM_PROMPT =
            "Eres el analizador de relaciones de memoria. Dados dos hechos A y B, determine su tipo de relación." +
                "Solo responde con una palabra:causes(A conducir a B)/ contradicts(Contradictorio)/ " +
                "part_of(B es parte de A o viceversa)/ related_to(asociación ordinaria). No expliques."
    }
}
