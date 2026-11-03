package io.zer0.memory.fact

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.pii.PiiGuard
import io.zer0.memory.ticker.MemoryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp

/**
 * Almacenamiento de metahechos.
 *
 * v6: Búsqueda de texto completo actualizada a la capa de aplicación FTS4 + CJK 2-gram,Mantenga ME GUSTA como palabra/Reversión de excepciones.
 *  - Agregar, eliminar, modificar y verificar(soltero/lote/sesión de prensa/prensa id)
 *  - Búsqueda de etiquetas(json_each coincidencia exacta,OR lógica,Orden descendente por número de coincidencias)
 *  - Búsqueda de texto completo(FTS4 MATCH,ngram preprocesamiento;reserva de una sola palabra LIKE)
 *  - FTS Autocomprobación de coherencia del índice y volumen completo rebuild
 *
 * El campo de hechos es PII insensibilizado antes de cualquier escritura.
 *
 * v5: Agregue datos para eliminar duplicados(Coincidir por prefijo de contenido)y juicio de importancia de inteligencia(impulsado por palabras clave).
 *
 * v1.0.27 P0-1.3: addBatch Agregar transacción,Evitar fallar a mitad de camino y dejar un estado a medio terminar(facts La tabla tiene datos pero falta fact_fts).
 *  Inyecte una instancia de FactDb para usar [androidx.room.withTransaction].
 */
class FactStore(
    private val dao: FactDao,
    private val db: FactDb,
    /**
     * Pre-v15 legacy tombstone file. New deletions are stored transactionally in
     * [FactTombstoneEntity] in this assistant's database, partitioned by scope and space.
     * This optional file is read-only compatibility data because historical entries lack
     * assistant/scope/space ownership. A null file disables only legacy fallback, not Room tombstones.
     */
    private val tombstoneFile: File? = null,
    /**
     * v12: LLM Determinante de deduplicación — capa de algoritmo(Similitud de personajes/Clave de entidad)Los pares de candidatos difusos que no se pueden determinar se entregan al modelo grande para su evaluación.
     * Predeterminado [NoopFactDedupJudge] desafinado LLM,El comportamiento es exactamente el mismo que no conectado.;app Los módulos inyectan implementaciones reales.
     */
    private val dedupJudge: FactDedupJudge = NoopFactDedupJudge,
    /**
     * v13 (T4-1): expediente de revisión de hechos DAO — memoria clave(importance≥1 o fusión de clave de entidad)historia de cambios.
     * null No grabado en todo momento(prueba/degradación de compatibilidad).
     */
    private val revisionDao: FactRevisionDao? = null,
    /**
     * Phase 3 (Fiabilidad P1): Conciliación incremental después de escribir hook(Nuevo/lote/Activado después de actualizar los hechos).
     *
     * FACTS Los productos compilados fueron producidos originalmente solo por el canal diario.(12–24h)reconciliarse una vez,Usuario agregado en la página de memoria./Editar después del hecho
     * system prompt La antigua representación seguirá siendo inyectada. Una vez que la escritura sea exitosa, la cuenta se conciliará inmediatamente a través de este gancho.,Eliminar este retraso.
     *
     * - null(Predeterminado): Análisis diferido de Koin en tiempo de ejecución [io.zer0.memory.compile.MemoryCompiler]
     *   (Producción y montaje sin modificaciones.);Sin entorno DI(prueba única/CLI)Cuando no se puede analizar, degenera en no-op,El comportamiento es consistente con versiones anteriores.
     * - Pasado explícitamente: uso prioritario(Inyección de prueba única fake;DI El ensamblaje también se puede anular explícitamente).
     *
     * Activación de aceleración en corrutinas independientes/fusionar(ver [scheduleReconcile]),No bloquea la ruta de escritura.,
     * hook La protección de recursión ignorará la escritura interna de la biblioteca nuevamente.,Respaldados por la reconciliación diaria.
     */
    private val reconcileHook: FactReconcileHook? = null,
    /** Owner of this per-assistant FactDb; guards explicit delete ownership. */
    val assistantId: String = "default",
    /** Only the default store owns clearing the legacy shared JSON file on a full reset. */
    private val clearLegacyTombstonesOnReset: Boolean = false,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** v6: ¿Ha realizado una verificación de coherencia del índice FTS?(Evite la duplicación en cada búsqueda. COUNT). */
    private var ftsConsistencyChecked = false

    /** datos metafactuales(Estructura de capa empresarial,Separado de la entidad). */
    data class Fact(
        val id: Long = 0,
        val fact: String,
        val tags: List<String> = emptyList(),
        val time: String? = null,
        val sessionId: String? = null,
        val createdAt: String = Instant.now().toString(),
        /** v4: importancia(0=ordinario,1=importante,2=clave). */
        val importance: Int = 0,
        /** v5: clasificación estructurada(Como por ejemplo preference / identity / event / relationship / goal / medical). */
        val category: String = "general",
        /** v5: LLM Confianza en la fiabilidad de los hechos.,0.0 ~ 1.0. */
        val confidence: Float = 1.0f,
        /** v5: fuente de hechos(user_explicit / inferred / imported). */
        val source: String = "inferred",
        /** v5: Tiempo de caducidad del hecho ISO 8601. */
        val expiresAt: String? = null,
        /** v5: Última hora confirmada ISO 8601. */
        val lastConfirmedAt: String? = null,
        /** v7: Hora del último golpe ISO 8601,Se utiliza para el reloj de caída de hitBonus. */
        val lastHitAt: String? = null,
        /** v14: El número acumulado de visitas recuperadas (0 = nunca recordado). */
        val hitCount: Int = 0,
        /**
         * v8: alcance de la memoria,Predeterminado "main" Representa el ámbito auxiliar principal.
         * sub-asistente/Los miembros del equipo usan los suyos. assistantId,Se utiliza para aislar los recuerdos de diferentes Agentes.
         * [add] / [addBatch] El parámetro de alcance anula este campo.
         */
        val scope: String = "main",
        /**
         * v9: espacio de memoria id,Predeterminado "default" Representa el espacio predeterminado.
         * Utilizado para aislamiento de múltiples espacios.(trabajo/vida/Las escenas de aprendizaje no interfieren entre sí.).
         * con [scope] ortogonal:scope Cuarentena por agente,spaceId Aislar por escena.
         * [add] / [addBatch] El parámetro spaceId anula este campo.
         */
        val spaceId: String = "default",
        /** B4-05: Tiempo de fijación manual ISO 8601,null Indica que no está fijado. */
        val pinnedAt: String? = null,
        /**
         * v12: Clave de normalización de la entidad. Diferentes formas de escribir la misma entidad(Como por ejemplo"Zhang San"/"Sr. Zhang")compartir la misma clave,
         * Comprobación precisa de duplicaciones basada en la clave de entidad al escribir,Las visitas se fusionan en lugar de agregarse.
         */
        val entityKey: String? = null,
        /** v17: Fracción de decadencia de persistencia(ver [FactEntity.score]). */
        val score: Float = 10f,
        /** v17: tiempo de juego ISO 8601,null Dijo que todavía estaba siendo retirado(ver [FactEntity.suppressedAt]). */
        val suppressedAt: String? = null,
        /**
         * v18: El viejo recuerdo reemplazado por este recuerdo. id,null Indica que no reemplaza ninguna memoria.(ver [FactEntity.supersedesId]).
         * El antiguo recuerdo puntiagudo todavía está en la biblioteca y la página del recuerdo es visible.,Simplemente no ingrese a la sección HECHOS del resultado compilado.
         */
        val supersedesId: Long? = null,
        /**
         * v19: ¿En qué memoria se fusiona esta memoria?,null Indica que no se ha fusionado.(ver [FactEntity.mergedIntoId]).
         * con [supersedesId] Son dos tipos de relaciones.: Ese es reemplazo de contenido.(contradicción),Esta columna es una agregación repetida.;
         * La fila con este valor todavía está en la biblioteca y la página de memoria está visible.,Simplemente no participa en la recuperación automática ni en la proyección de compilación.
         */
        val mergedIntoId: Long? = null,
        val matchCount: Int? = null,
    )

    /**
     * Phase 3 (Fiabilidad P1): Entrada de conciliación incremental después de escribir los hechos.
     *
     * por [FactStore] Añadiendo nuevo/lote/Llamado después de una actualización exitosa,Llevando el hecho final de este escrito.(Insensibilizado/Fusionado),
     * por parte del anfitrión(El entorno de producción es [io.zer0.memory.compile.MemoryCompiler.reconcileFactsSectionWithStore])
     * Alinear las líneas correspondientes en el producto compilado FACTS con los valores de representación de hechos,Eliminar la inyección rápida del sistema por más tiempo 12–24h Demora.
     *
     * @param facts Los hechos después de este escrito.(texto final;Conciliación solo lectura [Fact.fact])
     * @param scope alcance de la memoria
     * @param spaceId espacio de memoria
     */
    fun interface FactReconcileHook {
        suspend fun onFactsChanged(facts: List<Fact>, scope: String, spaceId: String)
    }

    /**
     * v5: palabras clave → Mapeo de importancia.
     *  - contiene"Alergia/Contraseña/dirección/cumpleaños/tarjeta de identificación/telefono"espera → importancia 2(clave)
     *  - contiene"me gusta/no me gusta/Me encanta comer/la mayoría"espera → importancia 1(importante)
     *  - Otra vida diaria → importancia 0(ordinario)
     */
    private val criticalKeywords = listOf("alérg", "alergia", "contraseña", "dirección", "cumpleaños", "identificación", "teléfono", "telefono", "sangre", "urgente", "historia clínica")
    private val importantKeywords = listOf("gusta", "encanta", "odia", "odio", "miedo", "sueñ", "objetivo", "hábito", "habito")

    /** Negaciones en español, solo como palabra completa para no coincidir dentro de "nombre" o "normal". */
    private val NEGATION_RE = Regex("(?<!\\p{L})(?:no|nunca|jamás|jamas|tampoco|nada|sin)(?!\\p{L})", RegexOption.IGNORE_CASE)

    /** v1.x: Deduplicación mejorada — Umbral de similitud de Jaccard del bigrama de caracteres(≥ Tratar como duplicado).
     * Las duplicaciones reales son en su mayoría diferencias menores en la redacción.(Insertar/eliminar/orden de las palabras),Jaccard Generalmente en 0.5-0.8;
     * 0.5 subumbral"le gusta tomar cafe" vs "le gusta tomar té"(0.43)Sin fusiones erróneas. */
    private val SIMILARITY_THRESHOLD = 0.5

    /**
     * v5: Juicio inteligente de importancia(0/1/2).
     * Priorizar la coincidencia de palabras clave clave,Segundas palabras clave importantes,El valor predeterminado es Normal.
     */
    private fun inferImportanceScore(text: String): Int {
        val lower = text.lowercase()
        if (criticalKeywords.any { lower.contains(it) }) return 2
        if (importantKeywords.any { lower.contains(it) }) return 1
        return 0
    }

    /**
     * v5: Determine si dos hechos son similares basándose en la similitud de coincidencia de prefijos.
     * Obtener texto más corto,Los textos más largos que comienzan con este se consideran duplicados.
     *
     * v9: Primero elimine los prefijos de asunto comunes.,evitar"alérgico a la penicilina"y"El usuario es alérgico a la penicilina."tratados como dos hechos.
     * v1.x: mejorar — Cuando el prefijo no coincide, se utiliza la tasa de superposición del bigrama de caracteres para determinar la duplicación aproximada.
     * (Como por ejemplo"Mañana tengo que hacer el examen CET-4" vs "mañana por la mañana9Prueba de inglés CET-4");
     * Las palabras negativas aparecen en diferentes números.(Como por ejemplo"No comas cilantro" vs "comer cilantro")Fuerza disimilitud,Evite la fusión errónea de inversión semántica.
     */
    private fun isSimilar(a: String, b: String): Boolean {
        val na = normalizeDedupText(a)
        val nb = normalizeDedupText(b)
        if (na == nb) return true
        if (na.isBlank() || nb.isBlank()) return false
        // Polaridad opuesta (gusta/odia) no es un duplicado.
        if (isContradictory(a, b)) return false
        // protección de palabras negativas:Los sucesos negativos varían = La semántica puede ser opuesta.,Nunca fusionar
        if (negationCount(na) != negationCount(nb)) return false
        val short = if (na.length <= nb.length) na else nb
        val long = if (na.length > nb.length) na else nb
        if (long.startsWith(short)) return true
        // mejorar:Tasa de superposición de Jaccard de bigrama de caracteres(repetición semántica aproximada)
        if (na.length >= 4 && nb.length >= 4) {
            return bigramSimilarity(na, nb) >= SIMILARITY_THRESHOLD
        }
        return false
    }

    /**
     * v1.0.72: Encuentre grupos de hechos casi duplicados(Para fusión y deduplicación de LLM).
     *
     * agrupación codiciosa:obtener todos los hechos,usar [isSimilar] Se determina que los grupos que son similares entre sí se agrupan en el mismo grupo.
     * Los hechos individuales no están agrupados. Cada grupo devolvió ≥ 2 Artículo,Depende de la persona que llama decidir si entregarlo a LLM para que lo fusione.
     *
     * @param scope alcance de la memoria(Predeterminado main)
     * @param spaceId espacio de memoria(Predeterminado default,Clúster solo dentro de este espacio)
     * @param maxGroups Número máximo de clústeres devueltos(Limitar el número de llamadas de LLM,Predeterminado 10)
     * @return Lista de grupos similares(Cada grupo está ordenado por identificación en orden ascendente)
     */
    suspend fun findSimilarGroups(scope: String = "main", spaceId: String = "default", maxGroups: Int = 10): List<List<Fact>> =
        withContext(Dispatchers.IO) {
            // v12: Estrictamente doble filtro por alcance + space_id,Evite agrupaciones erróneas en el espacio
            val all = dao.getByScopeAndSpace(scope, spaceId).map { it.toDomainFact() }
            if (all.size < 2) return@withContext emptyList()
            val used = mutableSetOf<Long>()
            val groups = mutableListOf<List<Fact>>()
            for (i in all.indices) {
                if (all[i].id in used) continue
                val cluster = mutableListOf(all[i])
                for (j in i + 1 until all.size) {
                    if (all[j].id in used) continue
                    // Si es similar a cualquiera del grupo, se fusionará en(cierre transitivo)
                    if (cluster.any { isSimilar(it.fact, all[j].fact) }) {
                        cluster.add(all[j])
                    }
                }
                if (cluster.size >= 2) {
                    used.addAll(cluster.map { it.id })
                    groups.add(cluster.sortedBy { it.id })
                    if (groups.size >= maxGroups) break
                } else {
                    used.add(all[i].id)
                }
            }
            groups
        }

    /** Número de palabras negativas(No/No/todavía no/no lo hagas/Ninguno/no). */
    private fun negationCount(text: String): Int = NEGATION_RE.findAll(text).count()

    /** Bigrama de personaje parecido a Jaccard. */
    private fun bigramSimilarity(a: String, b: String): Double {
        fun bigrams(s: String): Set<String> {
            if (s.length < 2) return setOf(s)
            return (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
        }
        val ba = bigrams(a)
        val bb = bigrams(b)
        if (ba.isEmpty() || bb.isEmpty()) return 0.0
        val intersection = ba.intersect(bb).size.toDouble()
        return intersection / (ba.size + bb.size - intersection)
    }

    /**
     * v9: Elimine los prefijos de asunto comunes para realizar comparaciones de deduplicación.
     * Como por ejemplo"El usuario es alérgico a la penicilina." → "alérgico a la penicilina";
     * "The user is allergic to penicillin" → "allergic to penicillin".
     *
     * v12: Normalización mejorada — Plegado de casos + conversión completa de medio ancho + eliminación de espacios en blanco iniciales y finales + compresión de espacios en blanco internos.
     * hacer"Zhang San"/"zhang san"/"Zhang San "El método de escritura cruzada se puede considerar igual,Admite el segundo canal de deduplicación de claves físicas.
     */
    private fun normalizeDedupText(text: String): String {
        return text.trim().lowercase()
            .replace(Regex("^(Usuario|yo|el|ella|este usuario|the user|i am|he is|she is)\\s*"), "")
            .trim()
            .normalizeFullWidth()
            .replace(WHITESPACE_RE, " ")
    }

    /**
     * v12: Normalización de ancho completo y medio — Letras de ancho completo/numeros/Símbolos de uso común de medio ancho.,Compatible con duplicación mixta en chino e inglés.
     * Ejemplo: "abc123" → "abc123", "Zhang San (compañero de clase)" → "Zhang San(compañeros de clase)".
     */
    private fun String.normalizeFullWidth(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            val code = ch.code
            when {
                // Letras de ancho completo/numeros(FF01-FF5E) → medio tamaño(0x21-0x7E)
                code in 0xFF01..0xFF5E -> sb.append((code - 0xFEE0).toChar())
                // espacio de ancho completo
                code == 0x3000 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * v12: Inferir claves de entidad a partir de texto de hechos(Se utiliza cuando no se da la fase de extracción.).
     * Solo reconoce dos tipos de señales de alta confianza.,Evite la combinación errónea de palabras comunes como nombres.:
     *  - PiiGuard Marcador de posición después de la desensibilización {{name_N}}(apellido+El título está insensibilizado.,El marcador de posición es una clave de entidad estable)
     *  - patrón de nombre en inglés(dos palabras que empiezan con mayúsculas,Como por ejemplo "Zhang San")
     * Los nombres chinos se dan explícitamente en la etapa de extracción LLM entity_key(prompt Solicitado);
     * Regresar cuando no sea reconocido null,Cubierto por el canal de similitud de texto,Es mejor omitir que recordar incorrectamente.
     */
    private fun inferEntityKey(fact: String): String? {
        val trimmed = fact.trim()
        if (trimmed.isBlank()) return null
        // La etiqueta de nombre de persona insensibilizada se utiliza directamente como clave de entidad(mask Marcador de posición reversible [NAME_N] espera,Preservar la distinción de entidad)
        val namePlaceholder = namePlaceholderRe.find(trimmed)?.value
        if (namePlaceholder != null) return namePlaceholder
        // patrón de nombre en inglés: dos palabras mayúsculas(Hay pocas escenas insensibles.,Cubre principalmente datos importados.)
        val enName = enNameRe.find(trimmed)?.value
        if (enName != null) return enName.lowercase()
        return null
    }

    /**
     * v12: Extraiga claves de entidad del texto de hechos.
     * Úselo directamente cuando se haya proporcionado la clave de entidad en la fase de extracción.;De lo contrario, se inferirá según las reglas de nombres.;
     * Recurrir al texto normalizado cuando no se puede hacer inferencia(Garantizar que los duplicados del mismo artículo seguirán siendo considerados iguales).
     */
    private fun resolveEntityKey(explicit: String?, fact: String): String? {
        if (!explicit.isNullOrBlank()) return explicit.trim().lowercase()
        return inferEntityKey(fact)
    }

    /** v12: Entrada pública de determinación de la misma entidad.(Diagnóstico/prueba/Reflexionar sobre la reutilización de tareas). */
    fun debugIsSameEntity(a: String, b: String): Boolean = isSameEntityFact(a, b)

    /** v12: LLM Caché de resultados del juicio(En proceso LRU,Acceso sincrónico)— Pregúntele al modelo solo una vez por el mismo par de hechos.,Evite el consumo repetido de tokens. */
    private val dedupVerdictCache = object : LinkedHashMap<String, DedupVerdict>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DedupVerdict>?): Boolean = size > 256
    }

    /**
     * v2.6.2(auditoría de memoria): Eliminar duplicados/Fusionar bloqueos mutex.
     *
     * `dedupPass` / `mergeSameEntityDuplicates` / `dedupPassAllScopes` Todos
     * “leer candidato → Comparación por pares → Escribe paso a paso”Operaciones de varios pasos, llamadas simultáneas (clasificación diaria + clasificación manual de UI) +
     * Write merge) interferirán entre sí: dos corrutinas consideran que el mismo par de hechos está duplicado y se eliminan por separado./cubrir,
     * lo que lleva a la duplicación de hechos,FTS Restantes o eliminados por error. Este bloqueo serializa las operaciones de fusión dentro de la misma tienda.
     */
    private val dedupMutex = kotlinx.coroutines.sync.Mutex()

    private fun dedupCacheKey(entityKey: String, cleaned: String): String = "$entityKey|$cleaned"

    /** v12: PII formulario de marcador de posición — scrub Salida de desensibilización dura [REDACTED];mask Marcador de posición reversible [NAME_1] espera;Compatible con el viejo {{name_0}}.
     * IGNORE_CASE: normalizeDedupText Ya lowercase,Los marcadores de posición en mayúsculas requieren una coincidencia insensible. */
    private val piiPlaceholderRe =
        Regex(
            "\\[REDACTED\\]|\\[(?:API_KEY|INLINE_SECRET|PRIVATE_KEY|ID_CARD|CREDIT_CARD|SSN|EMAIL|PHONE|IPV4|ADDRESS|NAME|ENGLISH_NAME)_\\d+\\]|\\{\\{name_\\d+\\}\\}",
            RegexOption.IGNORE_CASE,
        )

    /** v12: Marcador de posición específico del nombre(mask reversibles [NAME_N] / viejo {{name_N}}) — Sólo este formulario se puede utilizar como clave de entidad. */
    private val namePlaceholderRe = Regex("\\[(?:NAME|ENGLISH_NAME)_\\d+\\]|\\{\\{name_\\d+\\}\\}", RegexOption.IGNORE_CASE)

    /** v12: Candidatos para nombres ingleses que empiezan con mayúsculas. */
    private val enNameRe = Regex("\\b[A-Z][a-z]{1,12}\\s+[A-Z][a-z]{1,12}\\b")

    /**
     * v5: Fusionar dos hechos similares,Mantenga la mayor importancia, la mayor confianza y lo último created_at;
     *     La clasificación prioriza hechos nuevos.,La fuente tiene prioridad con user_explicit.
     *
     * v9: Al fusionar dos hechos, la única diferencia es el tema.,Mantenga la declaración original más corta sin asunto.
     */
    private fun mergeFact(existing: FactEntity, new: Fact): FactEntity {
        val mergedImportance = maxOf(existing.importance, if (new.importance > 0) new.importance else inferImportanceScore(new.fact))
        val mergedTags = (decodeTags(existing.tags) + new.tags).distinct()
        val existingTime = existing.time
        val mergedTime = if (new.time != null && (existingTime == null || new.time > existingTime)) new.time else existingTime
        val mergedSessionId = new.sessionId ?: existing.sessionId
        val mergedCreatedAt = Instant.now().toString()
        val mergedCategory = new.category.takeIf { it.isNotBlank() && it != "general" } ?: existing.category
        val mergedConfidence = maxOf(existing.confidence, new.confidence.coerceIn(0f, 1f))
        val mergedSource = if (existing.source == "user_explicit" || new.source == "user_explicit") "user_explicit" else new.source
        val mergedExpiresAt = new.expiresAt ?: existing.expiresAt
        val mergedLastConfirmedAt = new.lastConfirmedAt ?: existing.lastConfirmedAt
        // v7: Combinado como un solo golpe,restablecer el reloj de decadencia
        val mergedLastHitAt = Instant.now().toString()
        // v12: La fusión conserva claves de entidad no nulas(Los hechos nuevos tienen prioridad cuando se llevan explícitamente)
        val mergedEntityKey = new.entityKey ?: existing.entityKey
        return existing.copy(
            fact = pickMergedWording(existing.fact, new.fact),
            tags = json.encodeToString(ListSerializer(String.serializer()), mergedTags),
            time = mergedTime,
            sessionId = mergedSessionId,
            createdAt = mergedCreatedAt,
            importance = mergedImportance,
            category = mergedCategory,
            confidence = mergedConfidence,
            source = mergedSource,
            expiresAt = mergedExpiresAt,
            lastConfirmedAt = mergedLastConfirmedAt,
            lastHitAt = mergedLastHitAt,
            entityKey = mergedEntityKey,
        )
    }

    /**
     * v9: Fusionar dos textos con hechos similares.
     * Si la semántica es la misma(Igualdad después de eliminar el tema.),Mantenga expresiones más cortas y originales.;
     * En caso contrario, conserva la información de forma más completa.(más largo)versión.
     *
     * v12: PII conciencia de marcador de posición — Versión insensibilizada(Como por ejemplo [REDACTED]Me gusta la fotografía)Aunque más largo, la densidad de información es baja.,
     * Dar prioridad a conservar la versión original del usuario sin marcadores de posición,Evite fusionar y fusionar"Desensibilización".
     */
    private fun pickMergedWording(existing: String, new: String): String {
        val normExisting = normalizeDedupText(existing)
        val normNew = normalizeDedupText(new)
        if (normExisting == normNew) {
            // Mantenga la expresión original más corta cuando solo el sujeto sea diferente
            return if (new.length < existing.length) new else existing
        }
        val existingClean = !piiPlaceholderRe.containsMatchIn(normExisting)
        val newClean = !piiPlaceholderRe.containsMatchIn(normNew)
        return when {
            existingClean && !newClean -> existing // Versión antigua sin marcador de posición,mantener la versión anterior
            !existingClean && newClean -> new // Nueva versión sin marcadores de posición,Mantener nueva versión
            else -> if (new.length > existing.length) new else existing // homomorfismo,mantener más tiempo
        }
    }

    /**
     * v12: Juicio de la misma entidad — que [isSimilar] más flojo,Dedicado a la verificación de duplicaciones bajo la misma clave de entidad.
     *
     * fondo: PiiGuard voluntad"Sr. Zhang"desensibilización {{name_0}},Dos hechos que conducen a la misma entidad
     * (Un texto original, un marcador de posición)La similitud de personajes se destruye.,regular [isSimilar] No igual.
     * Aquí se elimina el marcador de posición y se determina la subcadena.: El texto corto es una subcadena de texto largo que se considera la misma entidad y hecho.
     *
     * seguridad: Solo se utiliza para el mismo canal de clave de entidad(Limitado a la misma entidad),Sin fusiones erróneas entre entidades;
     * La subcadena contiene al menos 4 caracteres.,evitar"me gusta"Juicio erróneo de subcadenas que son demasiado cortas para el nivel.
     */
    internal fun isSameEntityFact(a: String, b: String): Boolean {
        if (isSimilar(a, b)) return true
        val pa = stripNamePlaceholders(a)
        val pb = stripNamePlaceholders(b)
        if (pa.length < 4 || pb.length < 4) return false
        val short = if (pa.length <= pb.length) pa else pb
        val long = if (pa.length > pb.length) pa else pb
        return long.contains(short)
    }

    /** v12: Quitar marcadores de posición de PII y normalizar(Se utiliza para comparar con texto de la misma entidad.). */
    private fun stripNamePlaceholders(text: String): String {
        val normalized = normalizeDedupText(text)
        return normalized.replace(piiPlaceholderRe, " ").replace(WHITESPACE_RE, " ").trim()
    }

    /**
     * v12 (T3-1): tareas de reflexión — Fusionar hechos duplicados bajo la misma clave de entidad(clasificación de existencias).
     * Realizar un escaneo completo de los datos históricos que se han almacenado en la base de datos.: Igual que entidad_clave + duplicación del juicio de conocimiento del marcador de posición
     * fusionarse en uno(mantener la importancia/El partido con mayor confianza y texto más completo),Los hechos fijados no están involucrados.
     *
     * @return Número de elementos fusionados
     */
    suspend fun mergeSameEntityDuplicates(scope: String = "main", spaceId: String = "default"): Int = withContext(Dispatchers.IO) {
        val all = dao.getByScopeAndSpace(scope, spaceId).toMutableList()
        if (all.size < 2) return@withContext 0
        var merged = 0
        var i = 0
        while (i < all.size) {
            val current = all[i]
            var j = i + 1
            while (j < all.size) {
                val other = all[j]
                // Misma clave de entidad + juicio de percepción de marcador de posición + protección superior
                if (other.pinnedAt == null && current.pinnedAt == null &&
                    other.entityKey != null && current.entityKey != null &&
                    other.entityKey == current.entityKey &&
                    isSameEntityFact(current.fact, other.fact)
                ) {
                    val keeper = if (other.importance > current.importance) other else current
                    val mergedEntity = mergeForDedup(keeper, if (keeper.id == current.id) other else current)
                    dao.insert(mergedEntity.copy(id = keeper.id))
                    dao.deleteById(if (keeper.id == current.id) other.id else current.id)
                    dao.deleteFts(if (keeper.id == current.id) other.id else current.id)
                    syncFtsRow(keeper.id, FactFtsManager.toNgram(mergedEntity.fact))
                    all.removeAt(j)
                    if (keeper.id != current.id) all[i] = mergedEntity
                    merged++
                    continue
                }
                j++
            }
            i++
        }
        if (merged > 0) {
            Logger.i("FactStore", "Reflexión sobre la fusión: $merged hechos repetidos(scope=$scope, space=$spaceId)")
        }
        merged
    }

    /**
     * v12 (T3-1): tareas de reflexión — Claves de entidad para rellenar datos históricos.
     * Los primeros datos de la entidad_clave son null(legado después de la migración),Rellenar según las reglas de nombres;
     * Después del relleno, se pueden usar diferentes métodos de escritura para la misma entidad durante la siguiente reflexión. [mergeSameEntityDuplicates] unir.
     *
     * @return Número de artículos de reposición
     */
    suspend fun backfillEntityKeys(scope: String = "main", spaceId: String = "default"): Int = withContext(Dispatchers.IO) {
        val missing = dao.getByScopeAndSpace(scope, spaceId).filter { it.entityKey.isNullOrBlank() }
        if (missing.isEmpty()) return@withContext 0
        var updated = 0
        for (f in missing) {
            val key = inferEntityKey(f.fact) ?: continue
            if (dao.updateEntityKey(f.id, key) > 0) updated++
        }
        if (updated > 0) {
            Logger.i("FactStore", "Claves de entidad de relleno de reflexión: $updated Artículo(scope=$scope, space=$spaceId)")
        }
        updated
    }

    /**
     * v12 (T3-2): tareas de reflexión — Detección de contradicciones(versión de regla determinista).
     * Bajo la misma clave de entidad,Si las afirmaciones de dos hechos son de polaridad opuesta(me gusta/odio, voluntad/No, vete a dormir temprano/Quedarse despierto hasta tarde y otros pares de palabras clave),
     * Devolver pares en conflicto para marcar la capa superior“Por confirmar”(No eliminar automáticamente).
     *
     * v18: Un par que ha sido arbitrado por reemplaza al momento de escribir este artículo ya no se informa. —— La vieja memoria ha salido del contexto.,
     * re-listado“Por confirmar”Sólo permitirá al usuario tomar una nueva decisión sobre el conflicto que ha sido resuelto.
     *
     * @return lista de pares de hechos contradictorios(Cada par está ordenado de forma ascendente por id.)
     */
    suspend fun detectContradictions(scope: String = "main", spaceId: String = "default"): List<Pair<Fact, Fact>> =
        withContext(Dispatchers.IO) {
            val byKey = dao.getByScopeAndSpace(scope, spaceId)
                .filter { !it.entityKey.isNullOrBlank() }
                .groupBy { it.entityKey!! }
            if (byKey.isEmpty()) return@withContext emptyList()
            val result = mutableListOf<Pair<Fact, Fact>>()
            for ((_, facts) in byKey) {
                for (i in facts.indices) {
                    for (j in i + 1 until facts.size) {
                        val a = facts[i].toDomainFact()
                        val b = facts[j].toDomainFact()
                        val resolved = a.supersedesId == b.id || b.supersedesId == a.id
                        if (!resolved && isContradictory(a.fact, b.fact)) {
                            result.add(if (a.id < b.id) a to b else b to a)
                        }
                    }
                }
            }
            result
        }

    /**
     * v12 (T3-2): Determinación de conflictos a nivel de palabras clave — Se consideran contradictorias las afirmaciones opuestas sobre una misma entidad.
     * Pares de palabras polares de alta confianza(me gusta↔odio/odio↔como, voluntad↔No, si↔No, levántate temprano↔Quédate despierto hasta tarde y espera).
     */
    private fun isContradictory(a: String, b: String): Boolean {
        fun polarity(text: String): Int {
            val lower = text.lowercase()
            var score = 0
            val dislikes = lower.contains("no me gusta") || lower.contains("odia") ||
                lower.contains("odio") || lower.contains("miedo") ||
                text.contains("讨厌") || text.contains("不喜欢") || text.contains("害怕")
            val likes = lower.contains("gusta") || lower.contains("encanta") ||
                text.contains("喜欢") || text.contains("爱喝") || text.contains("爱吃")
            if (likes && !dislikes) score += 1
            if (dislikes) score -= 1
            if (lower.contains("levantarse temprano") || lower.contains("acostarse temprano")) score += 1
            if (lower.contains("quedarse despierto hasta tarde") || lower.contains("dormir hasta tarde")) score -= 1
            return score
        }
        val pa = polarity(a)
        val pb = polarity(b)
        return pa != 0 && pb != 0 && pa * pb < 0
    }

    /**
     * v18: Determinar al escribir“¿Qué viejo recuerdo reemplaza este nuevo recuerdo?”.
     *
     * Volver a viejos recuerdos sólo cuando la evidencia sea suficiente. id(escrito por la persona que llama supersedes_id,Esta línea luego sale del contexto de compilación.);
     * Si no estás seguro, regresa siempre. null —— Ambos permanecen en la biblioteca y entran en el contexto.,Deja que el modelo vea el conflicto.,
     * Más seguro que eliminar por error un recuerdo correcto.
     *
     * Sólo cuando se cumplan todas las condiciones al mismo tiempo se considerará como“reemplazar”:
     *  - el mismo entityKey(Misma entidad;No se emite ningún juicio si no hay una clave.);
     *  - Los nuevos recuerdos son declaraciones explícitas del usuario.(source=user_explicit) —— inferencia/La declaración importada no es suficiente para revertir los hechos existentes.;
     *  - afirmar la polaridad opuesta([isContradictory] Determinación del nivel de palabras clave);
     *  - contradictorio con ello**presente**Sólo hay un viejo recuerdo(Múltiples explicaciones indican que el antiguo estatus en sí es ambiguo.,Deja que la gente juzgue);
     *  - Los viejos recuerdos no se fijaron manualmente en la parte superior(La semántica de permanecer en la cima es“Recuerde siempre los requisitos del usuario”);
     *  - Los viejos recuerdos no son hechos clave(importance=2)mientras la nueva memoria está debajo de la clave —— salud/Las cuestiones de seguridad no quedan anuladas por las declaraciones ordinarias.
     *
     * “presente”= No ha sido reemplazado por otros recuerdos. Las viejas disputas que han cedido ya no participarán en la determinación,De lo contrario el usuario“Como primero,
     * Lo odio, me gusta de nuevo”Este tipo de repetición hará que el sistema se atasque.:El del medio excluye al primero.,Y el último sólo puede ser
     * Compite con el primero.,Como resultado, dos afirmaciones mutuamente excluyentes permanecen en contexto.
     *
     * @return Viejos recuerdos que deberían ser reemplazados por este artículo.;Devolución cuando no se cumplan las condiciones null(Ambos quedan en contexto.)
     */
    private suspend fun findSupersededFact(
        newFact: String,
        newSource: String,
        newImportance: Int,
        entityKey: String?,
        scope: String,
        spaceId: String,
    ): FactEntity? {
        if (entityKey.isNullOrBlank() || newSource != "user_explicit") return null
        val superseded = dao.getSupersededIds(scope, spaceId).toSet()
        val contradictory = dao.findByEntityKey(entityKey, scope, spaceId)
            .filter { it.id !in superseded && isContradictory(newFact, it.fact) }
        val old = contradictory.singleOrNull() ?: return null
        val replaceable = old.pinnedAt == null && !(old.importance >= 2 && newImportance < 2)
        return old.takeIf { replaceable }
    }

    /**
     * v12 (T3-3): tareas de reflexión — Memorice las rutas de promoción.
     * Múltiples hechos sobre la misma entidad(Repetir confirmación ≥ [minConfirmations] Artículo)Describe la entidad/El tema se menciona repetidamente.,
     * Mayor importancia un nivel(0→1,1→2),El umbral de caída de la participación después del ascenso es más alto.
     *
     * @return Número de promociones
     */
    suspend fun promoteRepeatedFacts(scope: String = "main", spaceId: String = "default", minConfirmations: Int = 2): Int =
        withContext(Dispatchers.IO) {
            val byKey = dao.getByScopeAndSpace(scope, spaceId)
                .filter { !it.entityKey.isNullOrBlank() }
                .groupBy { it.entityKey!! }
            if (byKey.isEmpty()) return@withContext 0
            var promoted = 0
            for ((_, facts) in byKey) {
                if (facts.size < minConfirmations) continue
                for (f in facts) {
                    if (f.importance < 2) {
                        if (dao.updateImportance(f.id, f.importance + 1) > 0) promoted++
                    }
                }
            }
            if (promoted > 0) {
                Logger.i("FactStore", "Reflexionar sobre la promoción.: $promoted Los hechos aumentan la importancia(scope=$scope, space=$spaceId)")
            }
            promoted
        }

    /** D6 Número 2: Agrupar múltiples afirmaciones de la misma entidad —— “Combinar sugerencias”Candidato definitivo a,Esto no significa que deban fusionarse. */
    data class SameEntityAssertions(
        /** Clave de normalización de la entidad. */
        val entityKey: String,
        /** La afirmación después de la deduplicación de esta entidad.(Uno para cada [Fact]). */
        val assertions: List<Fact>,
    )

    /**
     * D6 Número 2: Detección“Múltiples afirmaciones para la misma entidad”entidad,para“Combinar sugerencias”Proporcionar candidatos deterministas.
     *
     * diseño(solo detectar,Sin cambios de datos, sin fusión automática):
     *  - Solo cuente entidades cuya clave_entidad no esté vacía y no esté fijada en la parte superior(No hay ningún error en el inventario sin nombre chino.,heredar“Ning Le es bueno”);
     *  - Se utilizan afirmaciones repetidas de la misma entidad. [isSameEntityFact] Contar después de la deduplicación;
     *  - número de afirmaciones ≥ [minAssertions] entidad es considerada como“Múltiples entidades de afirmación”,Puede avisar al usuario o integrarlo en un retrato físico en la siguiente versión de LLM.
     *
     * Posicionamiento: con [mergeSameEntityDuplicates](afirmaciones superpuestas)complementario —— Este método maneja
     * “Múltiples afirmaciones diferentes para la misma entidad”,Proporcionar candidatos para futuras propuestas de fusión.,Actualmente no se están realizando operaciones de escritura.
     *
     * @return Agrupación de aserciones múltiples en orden descendente del número de aserción;Ninguna coincidencia devuelve una lista vacía
     */
    suspend fun findSameEntityMultiAssertionGroups(
        scope: String = "main",
        spaceId: String = "default",
        minAssertions: Int = DEFAULT_MIN_ASSERTIONS,
    ): List<SameEntityAssertions> = withContext(Dispatchers.IO) {
        if (minAssertions < 2) return@withContext emptyList()
        val byKey = dao.getByScopeAndSpace(scope, spaceId)
            .filter { !it.entityKey.isNullOrBlank() && it.pinnedAt == null }
            .map { it.toDomainFact() }
            .groupBy { it.entityKey!! }
        if (byKey.isEmpty()) return@withContext emptyList()
        val result = mutableListOf<SameEntityAssertions>()
        for ((key, facts) in byKey) {
            val distinct = mutableListOf<Fact>()
            for (fact in facts) {
                if (distinct.none { isSameEntityFact(it.fact, fact.fact) }) distinct.add(fact)
            }
            if (distinct.size >= minAssertions) {
                result.add(SameEntityAssertions(key, distinct))
            }
        }
        result.sortedByDescending { it.assertions.size }
    }

    /**
     * v13 (T4-1): Revisión de registros — Sólo para recuerdos clave(importance ≥ 1 O la clave de entidad no está vacía)escribir,Evite la expansión de la mesa.
     * revisionDao Se degrada silenciosamente cuando falla nulo o escritura,No afecta el proceso principal.
     */
    private suspend fun recordRevisionIfKey(factId: Long, oldContent: String, newContent: String, reason: String) {
        val dao2 = revisionDao ?: return
        val current = dao.getById(factId) ?: return
        if (current.importance < 1 && current.entityKey.isNullOrBlank()) return
        if (oldContent == newContent) return
        resultOf {
            dao2.insert(
                FactRevisionEntity(
                    factId = factId,
                    oldContent = oldContent,
                    newContent = newContent,
                    changedAt = Instant.now().toString(),
                    reason = reason,
                ),
            )
        }.onError { msg, t ->
            Logger.w("FactStore", "Error en la revisión de la grabación(fact=$factId): $msg", t)
        }
    }

    /**
     * v13 (T4-1): Historial de revisión de consultas por ID de hecho(nuevo→viejo).
     *
     * @return Lista de registros de revisión(Puede ser nulo)
     */
    suspend fun getRevisions(factId: Long, limit: Int = 20): List<FactRevisionEntity> = withContext(Dispatchers.IO) {
        revisionDao?.getByFactId(factId, limit) ?: emptyList()
    }

    /**
     * v13 (T4-1): Retroceder a la revisión especificada — Restaura el contenido del hecho al valor anterior de una revisión.
     * para confirmación del usuario"Fusión incorrecta/Actualización incorrecta"recuperación posterior.
     *
     * @return Si la reversión fue exitosa
     */
    suspend fun revertToRevision(factId: Long, revisionId: Long): Boolean = withContext(Dispatchers.IO) {
        val dao2 = revisionDao ?: return@withContext false
        val revision = dao2.getByFactId(factId, limit = 50).firstOrNull { it.id == revisionId } ?: return@withContext false
        val current = dao.getById(factId) ?: return@withContext false
        // La reversión en sí misma también es un cambio.,registrar el valor actual
        recordRevisionIfKey(factId, current.fact, revision.oldContent, "revert")
        val ok = dao.updateContent(factId, revision.oldContent, null) > 0
        if (ok) {
            syncFtsRow(factId, FactFtsManager.toNgram(revision.oldContent))
            // Phase 3 (P1): La reversión también cambia el texto del hecho.,Conciliar inmediatamente para evitar inyectar viejas representaciones.
            scheduleReconcile(
                listOf(Fact(id = factId, fact = revision.oldContent, scope = current.scope, spaceId = current.spaceId)),
                current.scope,
                current.spaceId,
            )
        }
        ok
    }

    /**
     * v9: Encuentre hechos existentes en la base de datos que sean semánticamente similares al hecho nuevo.
     * Coincidir primero con el prefijo original,Luego use la coincidencia de prefijo después de eliminar el asunto.,Finalmente, use la subcadena para buscar el resultado final.,
     * asegurar"alérgico a la penicilina"y"El usuario es alérgico a la penicilina."puede identificarse como el mismo artículo.
     *
     * v9 mejorar: Filtro spaceId agregado,Encuentre hechos similares solo dentro del mismo alcance + space_id,
     * Evite la fusión errónea entre espacios(Como por ejemplo"trabajo"espacial"como el cafe americano"no debe usarse con"vida"espacial"como el cafe americano"fusionar).
     *
     * v12 mejorar: Parámetro de entidad clave agregado,Priorice la búsqueda precisa por clave de normalización de entidades.
     * Diferentes formas de escribir la misma entidad(Como por ejemplo“Zhang San”/“Sr. Zhang”/“Maestro Zhang San”)compartir lo mismo entity_key,
     * Un acierto se considera un duplicado.,resolver directamente“3 memorias duplicadas para el mismo nombre de usuario”pregunta.
     *
     * v19: **Las filas ocultas no deben ser objetivos escritos** —— Se agregaron filas fusionadas([FactEntity.mergedIntoId] No vacío)
     * No se procesan ni la recuperación automática ni las secciones de FACTS compiladas.,No se puede utilizar como objetivo para la deduplicación: lo que esta escrito
     * Los usuarios no pueden ver ni recordar,Equivale a una pérdida silenciosa de datos.
     * Apareció([FactEntity.suppressedAt] No vacío), las líneas reemplazadas al menos se pueden ver en la página de memoria.,
     * Aún no en esta lista;Pero si se vuelve a introducir en el futuro“completamente oculto”mecanismo,Todos los canales coincidentes deben llenarse juntos con los predicados correspondientes.
     */
    private suspend fun findSimilarFact(
        cleaned: String,
        scope: String,
        spaceId: String = "default",
        entityKey: String? = null,
        excludeId: Long? = null,
    ): FactEntity? {
        // 0) v12: Comprobación precisa de duplicados de claves físicas — La misma clave_entidad + el mismo alcance/espacio se consideran la misma entidad,
        //    consciente del marcador de posición [isSameEntityFact] Determinar si los textos son sustancialmente iguales.
        //    excludeId: La ruta de actualización se excluye a sí misma,Evite coincidir usted mismo primero después de la actualización.
        if (!entityKey.isNullOrBlank()) {
            // v19: Las filas que se han fusionado con otros hechos ya no son objetivos de deduplicación. —— ha salido del contexto,
            // Incorporar nuevos contenidos equivale a escribir una memoria invisible.
            val sameKey = dao.findByEntityKey(entityKey, scope, spaceId).filter { it.mergedIntoId == null }
            val hit = sameKey.firstOrNull { it.id != excludeId && isSameEntityFact(it.fact, cleaned) }
            if (hit != null) return hit
            // v12: capa de algoritmo miss — Hay candidatos bajo la misma clave de entidad pero el juicio del texto no es similar.(Como semánticamente equivalente pero
            //    Las expresiones son muy diferentes o la PII se escribe de diferentes maneras después de la desensibilización.),Deje que LLM juzgue. Sólo para los mejores candidatos
            //    Preguntar una vez y almacenar en caché los resultados.,Controlar el consumo de tokens;baja confianza/El fracaso preferiría no fusionarse.
            if (dedupJudge !== NoopFactDedupJudge && sameKey.isNotEmpty()) {
                val cacheKey = dedupCacheKey(entityKey, cleaned)
                val cached = synchronized(dedupVerdictCache) { dedupVerdictCache[cacheKey] }
                val verdict = cached ?: runCatching {
                    dedupJudge.judge(sameKey[0].fact, cleaned, entityKey, entityKey)
                }.getOrElse {
                    DedupVerdict.NOT_SAME
                }.also { synchronized(dedupVerdictCache) { dedupVerdictCache.put(cacheKey, it) } }
                if (verdict.highConfidenceSame) {
                    io.zer0.common.Logger.d(
                        "FactStore",
                        "LLM Determinar la misma entidad y hecho.: [${sameKey[0].fact.take(30)}] ≈ [${cleaned.take(30)}] (${verdict.confidence})",
                    )
                    return sameKey[0]
                }
            }
        }

        // 1) Coincidencia de prefijos sin formato(Mantener el comportamiento v5)+ space_id filtrar
        dao.findSimilarBySpace(cleaned.take(40), scope, spaceId)
            .firstOrNull { it.mergedIntoId == null && isSimilar(it.fact, cleaned) }?.let { return it }

        // 2) Coincidencia de prefijo después de la eliminación del sujeto
        val normalized = normalizeDedupText(cleaned)
        if (normalized != cleaned.lowercase() && normalized.isNotBlank()) {
            dao.findSimilarBySpace(normalized.take(40), scope, spaceId)
                .firstOrNull { it.mergedIntoId == null && isSimilar(it.fact, cleaned) }?.let { return it }
        }

        // 3) Ten todo en cuenta:búsqueda de subcadenas,Limite el número para evitar el escaneo completo de la tabla
        // v9: Filtrar por alcance + space_id al mismo tiempo,Evite cruzar ámbitos/Fusión errónea en el espacio cruzado
        dao.likeSearchBySpace(cleaned.take(40), 20, scope, spaceId)
            .firstOrNull { it.mergedIntoId == null && isSimilar(it.fact, cleaned) }?.let { return it }

        return null
    }

    /**
     * v1.x: Quitar todo el peso — Escanee hechos similares dentro del alcance especificado y combínelos de dos en dos,Devuelve la cantidad combinada.
     *
     * Manejo de la duplicación de inventario(Coincidencia de solo prefijo casi duplicados omitidos anteriormente,Como por ejemplo"Mañana prueba CET-4" vs "mañana por la mañana9Prueba de inglés CET-4").
     * fusionar reglas:
     *  - Memoria de pines(pinnedAt No vacío)No involucrado en la fusión(Fijado por el usuario activamente.)
     *  - mantener la importancia/El partido con mayor confianza y texto más completo
     *  - Misma identificación REEMPLAZAR actualización + eliminar otra parte
     */
    suspend fun dedupPass(scope: String = "main", maxPairs: Int = 300, spaceId: String? = null): Int = dedupMutex.withLock {
        withContext(Dispatchers.IO) {
            // v12: spaceId Cuando no sea nulo, deduplica solo dentro de este espacio.,Evite la fusión errónea entre espacios;
            // null mantener el viejo comportamiento(Importe total por alcance)Compatible con llamantes existentes.
            val all = if (spaceId != null) {
                dao.getByScopeAndSpace(scope, spaceId).toMutableList()
            } else {
                dao.getByScope(scope).toMutableList()
            }
            if (all.size < 2) return@withContext 0
            var merged = 0
            var i = 0
            while (i < all.size && merged < maxPairs) {
                val current = all[i]
                var j = i + 1
                while (j < all.size && merged < maxPairs) {
                    val other = all[j]
                    if (current.pinnedAt == null && other.pinnedAt == null && isSimilar(current.fact, other.fact)) {
                        // fusionar other → current(Mantenga los que tienen mayor importancia.,De lo contrario mantener current)
                        val keeper = if (other.importance > current.importance) other else current
                        val removed = if (keeper.id == current.id) other else current
                        val mergedEntity = mergeForDedup(keeper, removed)
                        dao.insert(mergedEntity.copy(id = keeper.id)) // REPLACE:Portada keeper
                        dao.deleteById(removed.id)
                        // v2.6.2(auditoría de memoria): Sincronizar el índice FTS —— La implementación anterior solo insertaba+eliminaba,
                        // Los restos del antiguo índice de las filas fusionadas (facts_after_delete El disparador solo borra la identificación eliminada),
                        // Como resultado, las búsquedas pueden arrojar representaciones antiguas que se han fusionado. Alineado con mergeSameEntityDuplicates.
                        dao.deleteFts(removed.id)
                        syncFtsRow(keeper.id, FactFtsManager.toNgram(mergedEntity.fact))
                        all.removeAt(j)
                        if (keeper.id != current.id) all[i] = mergedEntity
                        merged++
                        continue
                    }
                    j++
                }
                i++
            }
            merged
        }
    }

    /**
     * Deduplicar por separado todos los ámbitos de memoria en el espacio actual.
     * El centro de memoria muestra todos los ámbitos de forma predeterminada, y la entrada de clasificación también debe cubrir todos los ámbitos y no puede tratar solo con el principal.
     */
    suspend fun dedupPassAllScopes(spaceId: String = "default", maxPairs: Int = 300): Int = withContext(Dispatchers.IO) {
        val scopes = dao.getAll(null)
            .asSequence()
            .filter { it.spaceId == spaceId }
            .map { it.scope }
            .distinct()
            .toList()
        var merged = 0
        for (scope in scopes) {
            merged += dedupPass(scope = scope, maxPairs = maxPairs, spaceId = spaceId)
        }
        merged
    }

    /** v1.0.92: La combinación de alcance + espacio que realmente existe en la biblioteca.(Para uso transversal por parte de integradores LLM, etc.,No a través del espacio). */
    suspend fun listScopeSpacePairs(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        dao.getAll(null)
            .asSequence()
            .map { it.scope to it.spaceId }
            .distinct()
            .toList()
    }

    /** La deduplicación se realiza en función de la combinación espacio + alcance que realmente existe en la base de datos y nunca se realizan comparaciones entre espacios. */
    suspend fun dedupPassAllSpaces(maxPairs: Int = 300): Int = withContext(Dispatchers.IO) {
        val groups = dao.getAll(null)
            .asSequence()
            .map { it.scope to it.spaceId }
            .distinct()
            .toList()
        groups.sumOf { (scope, spaceId) ->
            dedupPass(scope = scope, maxPairs = maxPairs, spaceId = spaceId)
        }
    }

    /** v1.x: Fusionar dos hechos similares(Utilizado para la eliminación completa de peso.),Texto que conserva información más completa. */
    private suspend fun mergeForDedup(base: FactEntity, other: FactEntity): FactEntity {
        val mergedTags = (decodeTags(base.tags) + decodeTags(other.tags)).distinct()
        return base.copy(
            fact = if (base.fact.length >= other.fact.length) base.fact else other.fact,
            tags = json.encodeToString(ListSerializer(String.serializer()), mergedTags),
            time = base.time ?: other.time,
            importance = maxOf(base.importance, other.importance),
            category = if (base.category == "general") other.category else base.category,
            confidence = maxOf(base.confidence, other.confidence),
            source = if (base.source == "user_explicit" || other.source == "user_explicit") "user_explicit" else base.source,
            expiresAt = base.expiresAt ?: other.expiresAt,
            lastConfirmedAt = base.lastConfirmedAt ?: other.lastConfirmedAt,
            lastHitAt = Instant.now().toString(),
            // v12: La fusión conserva claves de entidad no nulas
            entityKey = base.entityKey ?: other.entityKey,
        )
    }

    /**
     * v5: Añade un nuevo metadato. Deduplicación automática + importancia inteligente.
     * Fusionar cuando se encuentren hechos existentes similares(Mantenga la máxima importancia y la última hora de actualización).
     *
     * v8: Parámetro de alcance agregado(Predeterminado "main"),Se utiliza para especificar el alcance de la memoria.
     *  - "main":alcance de ayuda principal(Predeterminado),Datos sobre la conversación del usuario con el asistente principal.
     *  - assistantId:sub-asistente/Alcance de los miembros del equipo
     * scope Los parámetros se anularán entry.scope,La persona que llama no necesita configurar una configuración separada en Fact.
     * Al deduplicar, solo busque hechos similares dentro del mismo alcance,Evite la combinación errónea entre alcances.
     *
     * v9: Parámetro spaceId agregado(Predeterminado "default"),Se utiliza para especificar el espacio de memoria.
     *  - "default":Espacio predeterminado(Compatible con personas mayores)
     *  - ID personalizado: trabajo creado por el usuario/vida/Estudio y otros espacios
     * spaceId ortogonal al alcance:Un hecho pertenece a algo Agent scope,También pertenece a un Espacio.
     * Al deduplicar, busque solo dentro del mismo alcance + space_id,Evite fusionar mal los espacios.
     */
    suspend fun add(entry: Fact, scope: String = "main", spaceId: String = "default"): Long = withContext(Dispatchers.IO) {
        val (cleaned, detected) = PiiGuard.scrub(entry.fact)
        if (detected.isNotEmpty()) {
            io.zer0.common.Logger.d("FactStore", "PII detected in fact: $detected")
        }
        // v12: Clave de entidad — La prioridad se da explícitamente durante la fase de extracción.,De lo contrario, se inferirá según las reglas de nombres.,Degenerar de nuevo null
        val entityKey = resolveEntityKey(entry.entityKey, cleaned)
        val newEntry = entry.copy(fact = cleaned, scope = scope, spaceId = spaceId, entityKey = entityKey)
        val existingSimilar = findSimilarFact(cleaned, scope, spaceId, entityKey)
        if (existingSimilar != null) {
            // v13 (T4-1): Registrar revisiones antes de fusionar(Texto original del partido fusionado revisions,rastreable)
            recordRevisionIfKey(
                factId = existingSimilar.id,
                oldContent = existingSimilar.fact,
                newContent = cleaned,
                reason = "merge:add",
            )
            val merged = mergeFact(existingSimilar, newEntry)
            // v2.6.2(auditoría de memoria): Fusionar paquete de escritura en transacción —— antigua implementación updateEntity + syncFtsRow
            // Dos pasos, el fracaso en el medio te dejará.“El texto ha sido cambiado,FTS No sincronizado”estado inconsistente.
            db.withTransaction {
                dao.updateEntity(
                    merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                    merged.createdAt, merged.importance, merged.category, merged.confidence,
                    merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                    merged.entityKey,
                )
                syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
            }
            io.zer0.common.Logger.d(
                "FactStore",
                "Fusionar hechos similares(scope=$scope, space=$spaceId, entity=$entityKey): ${existingSimilar.fact.take(
                    30,
                )}… ↔ ${cleaned.take(30)}… → id=${existingSimilar.id}",
            )
            // Phase 3 (P1): El texto final fusionado se concilia inmediatamente,Las inyecciones ya no llevan viejas representaciones.
            scheduleReconcile(
                listOf(Fact(id = merged.id, fact = merged.fact, scope = scope, spaceId = spaceId)),
                scope,
                spaceId,
            )
            return@withContext existingSimilar.id
        }
        val importance = if (newEntry.importance > 0) newEntry.importance else inferImportanceScore(cleaned)
        val now = Instant.now().toString()
        // v18: arbitraje de conflictos — Sólo se registrarán viejos recuerdos sustituidos por este artículo cuando existan pruebas suficientes.(No eliminar filas antiguas,Salir solo del contexto de compilación)
        val superseded = findSupersededFact(cleaned, newEntry.source, importance, entityKey, scope, spaceId)
        val entity = FactEntity(
            fact = cleaned,
            tags = json.encodeToString(ListSerializer(String.serializer()), newEntry.tags),
            time = newEntry.time,
            sessionId = newEntry.sessionId,
            createdAt = now,
            importance = importance.coerceIn(0, 2),
            category = newEntry.category.takeIf { it.isNotBlank() } ?: "general",
            confidence = newEntry.confidence.coerceIn(0f, 1f),
            source = newEntry.source.takeIf { it.isNotBlank() } ?: "inferred",
            expiresAt = newEntry.expiresAt,
            lastConfirmedAt = newEntry.lastConfirmedAt,
            // v7: Agregar un dato cuenta como un acierto,Disfruta por defecto hitBonus
            lastHitAt = newEntry.lastHitAt ?: now,
            // v8: alcance de la memoria,Especificado por la persona que llama(Predeterminado "main")
            scope = scope,
            // v9: espacio de memoria,Especificado por la persona que llama(Predeterminado "default")
            spaceId = spaceId,
            pinnedAt = newEntry.pinnedAt,
            // v12: Clave de normalización de entidad
            entityKey = entityKey,
            // v18: Viejos recuerdos reemplazados por este artículo.
            supersedesId = superseded?.id,
        )
        // Revisión de corrección (B-22): Transaccionalización de un único anuncio — facts La escritura en las dos tablas de fact_fts está incluida en la misma transacción.,
        // Alinear con addBatch,Evite que insertFts falle y deje un estado a medio completar donde la tabla de hechos tiene datos pero falta FTS.
        val insertedId = db.withTransaction {
            val id = dao.insert(entity)
            dao.insertFts(id, FactFtsManager.toNgram(cleaned))
            id
        }
        // Phase 3 (P1): Conciliación incremental inmediatamente después de agregar(FACTS Las filas sinónimos del producto están alineadas con el texto final de la tabla.)
        scheduleReconcile(
            listOf(Fact(id = insertedId, fact = cleaned, scope = scope, spaceId = spaceId)),
            scope,
            spaceId,
        )
        insertedId
    }

    /**
     * v5: Agregue en lotes. Deduplicación automática + importancia inteligente,Las transacciones atómicas garantizan la coherencia.
     *
     * v8: Parámetro de alcance agregado(Predeterminado "main"),Este alcance se usa de manera uniforme cuando se escribe en lotes.
     * Al realizar la deduplicación, busque únicamente dentro del mismo alcance.,Evite la combinación errónea entre alcances.
     *
     * v9: Parámetro spaceId agregado(Predeterminado "default"),Este espacio se utiliza de manera uniforme al escribir en lotes.
     * Al deduplicar, busque solo dentro del mismo alcance + space_id,Evite fusionar mal los espacios.
     *
     * v1.0.27 P0-1.3: usar [FactDb.withTransaction] envolver todo el bucle,Asegúrese de que los hechos sean consistentes con facts_fts
     * Las escrituras en ambas tablas deben realizarse correctamente o revertirse.,Evitar fallar a mitad de camino y dejarlo a medio terminar.
     */
    suspend fun addBatch(entries: List<Fact>, scope: String = "main", spaceId: String = "default"): Int = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext 0
        val now = Instant.now().toString()
        // Phase 3 (P1): Recopila los hechos finales después de este escrito.,Una vez enviada la transacción, se activa una conciliación incremental de manera uniforme(Fusión del acelerador)
        val touched = mutableListOf<Fact>()
        val inserted = db.withTransaction {
            var inserted = 0
            for (entry in entries) {
                val (cleaned, detected) = PiiGuard.scrub(entry.fact)
                if (detected.isNotEmpty()) {
                    io.zer0.common.Logger.d("FactStore", "PII detected in batch fact: $detected")
                }
                // v12: Clave de entidad — La prioridad se da explícitamente durante la fase de extracción.,De lo contrario, se inferirá según las reglas de nombres.
                val entityKey = resolveEntityKey(entry.entityKey, cleaned)
                val newEntry = entry.copy(fact = cleaned, scope = scope, spaceId = spaceId, entityKey = entityKey)
                val existingSimilar = findSimilarFact(cleaned, scope, spaceId, entityKey)
                if (existingSimilar != null) {
                    val merged = mergeFact(existingSimilar, newEntry)
                    dao.updateEntity(
                        merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                        merged.createdAt, merged.importance, merged.category, merged.confidence,
                        merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                        merged.entityKey,
                    )
                    syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
                    touched.add(Fact(id = merged.id, fact = merged.fact, scope = scope, spaceId = spaceId))
                } else {
                    val importance = if (newEntry.importance > 0) newEntry.importance else inferImportanceScore(cleaned)
                    val insertedId = dao.insert(
                        FactEntity(
                            fact = cleaned,
                            tags = json.encodeToString(ListSerializer(String.serializer()), newEntry.tags),
                            time = newEntry.time,
                            sessionId = newEntry.sessionId,
                            createdAt = now,
                            importance = importance.coerceIn(0, 2),
                            category = newEntry.category.takeIf { it.isNotBlank() } ?: "general",
                            confidence = newEntry.confidence.coerceIn(0f, 1f),
                            source = newEntry.source.takeIf { it.isNotBlank() } ?: "inferred",
                            expiresAt = newEntry.expiresAt,
                            lastConfirmedAt = newEntry.lastConfirmedAt,
                            lastHitAt = newEntry.lastHitAt ?: now,
                            scope = scope,
                            spaceId = spaceId,
                            pinnedAt = newEntry.pinnedAt,
                            entityKey = entityKey,
                            // v18: Determinación conservadora de conflictos con add.
                            supersedesId = findSupersededFact(
                                cleaned, newEntry.source, importance, entityKey, scope, spaceId,
                            )?.id,
                        ),
                    )
                    dao.insertFts(insertedId, FactFtsManager.toNgram(cleaned))
                    // No habrá duplicación de identificadores recién insertados. FTS,Simplemente inserte Fts directamente(upsertFts Un BORRAR más es innecesario)
                    touched.add(Fact(id = insertedId, fact = cleaned, scope = scope, spaceId = spaceId))
                }
                inserted++
            }
            inserted
        }
        scheduleReconcile(touched, scope, spaceId)
        inserted
    }

    /**
     * v6: Búsqueda de texto completo. Prioridad FTS4 MATCH(ngram preprocesamiento),sola palabra/Recurra a LIKE cuando se produzca una excepción.
     * B-06: Los resultados también filtran datos caducados(expires_at < now),Mismo calibre que getAll/getByScope.
     */
    suspend fun searchFullText(query: String, limit: Int = 20): List<Fact> =
        searchFullTextWithMode(query, limit, MatchMode.EXACT)

    /**
     * Realice una búsqueda de texto completo basada en una semántica coincidente especificada.
     *
     * [MatchMode.EXACT] Requerir todas las palabras para acertar([searchFullText] comportamiento original),
     * [MatchMode.ANY] Cualquier acierto cuenta como candidato. —— Se utiliza para recuperar cuando los resultados de búsqueda precisos son insuficientes.
     *
     * @param query Cadena de consulta original,Hacer preprocesamiento interno de ngram/LIKE
     * @param limit Devuelve el límite superior del número de artículos.
     * @param mode Semántica de coincidencias
     */
    suspend fun searchFullTextWithMode(query: String, limit: Int = 20, mode: MatchMode = MatchMode.EXACT): List<Fact> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            ensureFtsIndexConsistent()
            runFtsOrLikeSearch(query.trim(), limit, mode).filterNot { it.isExpired() }.also { reviveSuppressed(it) }
        }

    /**
     * Búsqueda de texto completo en dos etapas:Sea preciso primero,No es suficiente para relajarse,Fusionar y eliminar duplicados antes de tomar el primero [limit] artículo.
     *
     * Las dos etapas solo afectan el recuerdo,No cambia la prioridad en el orden de acierto:Las filas con aciertos exactos siempre ocupan el primer lugar.
     */
    suspend fun searchFullTextTwoStage(query: String, limit: Int = 20): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        val trimmed = query.trim()
        val exact = runFtsOrLikeSearch(trimmed, limit, MatchMode.EXACT).filterNot { it.isExpired() }
        val merged = if (exact.size >= limit) {
            exact
        } else {
            val broad = runFtsOrLikeSearch(trimmed, limit, MatchMode.ANY).filterNot { it.isExpired() }
            mergeDistinct(exact, broad, limit)
        }
        merged.also { reviveSuppressed(it) }
    }

    /** Búsqueda por alcance + espacio, para uso de memoria_búsqueda durante la sesión. */
    suspend fun searchFullTextScoped(query: String, scope: String, spaceId: String, limit: Int = 20): List<Fact> =
        searchFullTextScopedWithMode(query = query, scope = scope, spaceId = spaceId, limit = limit, mode = MatchMode.EXACT)

    /** Búsqueda por alcance + espacio + semántica coincidente especificada. */
    suspend fun searchFullTextScopedWithMode(
        query: String,
        scope: String,
        spaceId: String,
        limit: Int = 20,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        runFtsOrLikeSearchScoped(query.trim(), limit, scope, spaceId, mode)
            .filterNot { it.isExpired() }
            .also { reviveSuppressed(it) }
    }

    /**
     * Búsqueda de texto completo con ámbito de dos etapas:Sea preciso primero,Resultados insuficientes [limit] Presiona cualquier golpe nuevamente para completar una ronda.,Fusionar y eliminar duplicados.
     *
     * Los hechos a menudo se distribuyen en varias líneas.,La conexión predeterminada para FTS4 es AND —— Un acierto se produce cuando todas las palabras aparecen juntas en una frase.
     * Garantía de prioridad precisa“Toda la frase golpea”en el frente;Cualquiera que llegue al fondo garantiza que los hechos individuales dispersos no pasarán desapercibidos en su conjunto.
     */
    suspend fun searchFullTextScopedTwoStage(
        query: String,
        scope: String,
        spaceId: String,
        limit: Int = 20,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        val trimmed = query.trim()
        val exact = runFtsOrLikeSearchScoped(trimmed, limit, scope, spaceId, MatchMode.EXACT).filterNot { it.isExpired() }
        val merged = if (exact.size >= limit) {
            exact
        } else {
            val broad = runFtsOrLikeSearchScoped(trimmed, limit, scope, spaceId, MatchMode.ANY).filterNot { it.isExpired() }
            mergeDistinct(exact, broad, limit)
        }
        merged.also { reviveSuppressed(it) }
    }

    /** Fusionar dos resultados:Mantén el orden del primer párrafo.,El segundo párrafo simplemente completa lo que el primer párrafo no tiene. fact,En total no más de [limit]. */
    private fun mergeDistinct(primary: List<Fact>, secondary: List<Fact>, limit: Int): List<Fact> {
        val seen = HashSet<Long>(primary.size + secondary.size)
        return (primary + secondary).filter { seen.add(it.id) }.take(limit)
    }

    /**
     * Revisión de corrección (B-19): Filtrar por space_id,scope Sin límite(Todos los alcances)Búsqueda de texto completo.
     * centro de memoria scope=null Semántica(Todos)Aún presiona actual [spaceId] cuarentena,Evite fugas de búsqueda entre espacios.
     */
    suspend fun searchFullTextBySpace(query: String, spaceId: String, limit: Int = 20): List<Fact> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureFtsIndexConsistent()
        runFtsOrLikeSearchBySpaceId(query.trim(), limit, spaceId).filterNot { it.isExpired() }.also { reviveSuppressed(it) }
    }

    /**
     * v12 (T2-2): Recuperación de memoria relacionada con el tiempo de ejecución — Recuerde los K hechos relevantes según la pregunta actual.
     * en [searchFullText] En base a esto, agregue alcance + filtrado de espacio,Prevenir ayuda cruzada/memoria de cadena en el espacio cruzado;
     * para aviso del sistema <relevant_memory> segmento(Recordar por consulta,en lugar de inyección completa).
     *
     * v17: Apareció(puntuación por debajo del umbral)La memoria no participa en el recuerdo.;La ventana de toma de números se amplía gradualmente según la situación de apariencia.,
     * Se garantiza que la cantidad de entradas que aparecen no hará que la cantidad de entradas recuperadas disminuya en consecuencia.
     */
    suspend fun searchRelevantFacts(query: String, scope: String = "main", spaceId: String = "default", limit: Int = 8): List<Fact> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            ensureFtsIndexConsistent()
            // El filtrado por alcance + espacio debe realizarse durante la fase de consulta SQL/FTS.
            // Obtenga primero toda la biblioteca limit*3 Luego, otros ayudantes utilizarán el filtrado en memoria./El espacio está lleno de resultados altamente correlacionados,
            // Hace que el alcance actual devuelva resultados vacíos o incompletos aunque haya hechos.
            val hits = recallEligibleFacts(query.trim(), scope, spaceId, limit)
            // presionar reescritura:Deje que last_hit_at refleje el uso real(Bonificación de decadencia vs.“éxitos recientes”todos dependen de ello)
            recordHits(hits)
            hits
        }

    /**
     * v17: Llamar candidatos automáticamente — Excluir apariciones/Caducado [limit] artículo.
     *
     * FTS Primero buscar por ventana y luego filtrar en la memoria,Por tanto, la memoria de apariencia ocupará la cuota de ventana.
     * Si la ventana está llena de resultados y no hay suficientes elementos calificados [limit],Recuperación de ventana de doble ampliación(hasta 8 veces),
     * asegurar“Hizo algunas apariciones”no se convertirá“Menos retiros”.
     *
     * v18: Los viejos recuerdos que han sido reemplazados no se recuperan automáticamente —— De lo contrario, dos afirmaciones mutuamente excluyentes aparecerán juntas en
     * <relevant_memory> adentro,Los resultados del arbitraje contradicen el párrafo de HECHOS.
     */
    private suspend fun recallEligibleFacts(query: String, scope: String, spaceId: String, limit: Int): List<Fact> {
        if (limit <= 0) return emptyList()
        val superseded = supersededIds(scope, spaceId)
        val merged = mergedIds(scope, spaceId)
        var window = limit * 2
        val maxWindow = limit * 8
        while (true) {
            val raw = runFtsOrLikeSearchScoped(query, window, scope, spaceId)
                .filterNot { it.isExpired() }
            val eligible = raw.filter { it.isInRecall() && it.id !in superseded && it.id !in merged }
            // raw No hay suficientes filas en la ventana = Curry no tiene más candidatos,Es inútil acercarse más
            val truncated = raw.size >= window
            if (eligible.size >= limit || !truncated || window >= maxWindow) {
                return eligible.take(limit)
            }
            window *= 2
        }
    }

    /**
     * Reescritura de resultados de búsqueda —— Actualice last_hit_at con puntuación de persistencia.
     *
     * Sólo escribe el tiempo/contar/puntuación,no cambies nada;Solo falla al iniciar sesión,De ninguna manera afecta los resultados de la búsqueda en sí.
     */
    private suspend fun recordHits(hits: List<Fact>) {
        val ids = hits.mapNotNull { fact -> fact.id.takeIf { it > 0L } }
        if (ids.isEmpty()) return
        runCatching { dao.updateLastHitAt(ids, Instant.now().toString()) }
            .onFailure { Logger.w("FactStore", "Falló la reescritura(No afecta la recuperación): ${it.message}") }
        // También se verifica el número de aciertos.“Esta memoria todavía está en uso.”, escribe de nuevo junto con el tiempo
        runCatching { dao.incrementHitCount(ids) }
            .onFailure { Logger.w("FactStore", "Error en el recuento de visitas(No afecta la recuperación): ${it.message}") }
        // v17: Los aciertos también otorgan una bonificación de puntuación duradera.,La memoria referenciada no es fácil de aparecer.
        runCatching {
            val config = MemoryConfig()
            dao.boostScores(ids, config.hitBonus, revivalCeiling(config), Instant.now().toString())
        }.onFailure { Logger.w("FactStore", "Error en la devolución de llamada de puntuación de aciertos(No afecta la recuperación): ${it.message}") }
    }

    /**
     * v17: "Devolución de llamada de acceso" de la memoria de aparición.
     *
     * búsqueda explícita(Página de memoria/herramienta search_memory)Golpea una memoria existente,indica que todavía es necesario:
     * Añade una bonificación de un golpe y borra la marca de salida.,La próxima recuperación automática lo volverá a alcanzar.
     * Procesar solo las filas que han aparecido,La memoria normal no se ve afectada.;Solo falla al iniciar sesión,No afecta los resultados de búsqueda.
     */
    private suspend fun reviveSuppressed(hits: List<Fact>) {
        val ids = hits.filter { it.suppressedAt != null }.map { it.id }.filter { it > 0L }
        if (ids.isEmpty()) return
        runCatching {
            val config = MemoryConfig()
            dao.boostScores(ids, config.hitBonus, revivalCeiling(config), Instant.now().toString())
        }.onFailure { Logger.w("FactStore", "Error en la devolución de llamada de la memoria de apariencia(No afecta la recuperación): ${it.message}") }
    }

    private suspend fun runFtsOrLikeSearchScoped(
        trimmed: String,
        limit: Int,
        scope: String,
        spaceId: String,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> {
        val like: suspend () -> List<Fact> = { likeScopedByWords(trimmed, limit, scope, spaceId) }
        if (FactFtsManager.shouldFallbackToLike(trimmed)) return like()
        val matchQuery = FactFtsManager.toMatchQuery(trimmed, orSemantics = mode == MatchMode.ANY)
        if (matchQuery.isBlank()) return like()
        val ftsResults = resultOf { dao.searchFtsBySpace(matchQuery, limit, scope, spaceId) }
            .onError { msg, t -> Logger.w("FactStore", "scoped FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) return like()
        return ftsResults.map { it.toDomainFact() }
    }

    /**
     * scoped LIKE revertir:Cuando no toque toda la cadena, divida las palabras y revíselas una por una.,Cualquier candidato exitoso,Aquellos con más palabras exitosas ocupan el primer lugar.
     *
     * Toda la cadena de hits mantiene su orden original.(fijar hacia arriba/importancia/tiempo),Los resultados de la división de palabras se reorganizan según la cantidad de palabras clave. ——
     * De esta manera, las consultas de palabras se comportan exactamente como lo hacían antes de dividirse.
     */
    private suspend fun likeScopedByWords(trimmed: String, limit: Int, scope: String, spaceId: String): List<Fact> {
        val whole = dao.likeSearchBySpace(trimmed, limit, scope, spaceId)
        val rows = if (whole.isNotEmpty()) {
            whole
        } else {
            likeByWords(trimmed, limit) { term -> dao.likeSearchBySpace(term, limit, scope, spaceId) }
        }
        return rows.map { it.toDomainFact() }
    }

    /**
     * Me gusta las palabras una por una y luego fusionarlas:Deja solo uno en la misma línea.,Ordenar de forma descendente por número de palabras clave(Mantener el orden de primera aparición con el mismo número.).
     *
     * @param query Cuerpo de la consulta ejecutado palabra por palabra,El alcance lo determina la persona que llama./filtrado espacial
     */
    private suspend fun likeByWords(
        trimmed: String,
        limit: Int,
        query: suspend (String) -> List<FactEntity>,
    ): List<FactEntity> {
        val terms = FactFtsManager.toQueryTerms(trimmed)
        if (terms.size < 2) return emptyList()
        val matched = LinkedHashMap<Long, Pair<FactEntity, Int>>()
        for (term in terms) {
            query(term).forEach { row ->
                val previous = matched[row.id]
                matched[row.id] = if (previous == null) row to 1 else previous.first to previous.second + 1
            }
        }
        return matched.values.sortedByDescending { it.second }.map { it.first }.take(limit)
    }

    /** Revisión de corrección (B-19): Búsqueda de texto completo FTS/LIKE filtrada solo por space_id(FTS Respaldo en caso de error COMO consulta del mismo espacio). */
    private suspend fun runFtsOrLikeSearchBySpaceId(
        trimmed: String,
        limit: Int,
        spaceId: String,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> {
        val like: suspend () -> List<Fact> = {
            val whole = dao.likeSearchBySpaceId(trimmed, limit, spaceId)
            val rows = if (whole.isNotEmpty()) {
                whole
            } else {
                likeByWords(trimmed, limit) { term -> dao.likeSearchBySpaceId(term, limit, spaceId) }
            }
            rows.map { it.toDomainFact() }
        }
        if (FactFtsManager.shouldFallbackToLike(trimmed)) return like()
        val matchQuery = FactFtsManager.toMatchQuery(trimmed, orSemantics = mode == MatchMode.ANY)
        if (matchQuery.isBlank()) return like()
        val ftsResults = resultOf { dao.searchFtsBySpaceId(matchQuery, limit, spaceId) }
            .onError { msg, t -> Logger.w("FactStore", "space-only FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) return like()
        return ftsResults.map { it.toDomainFact() }
    }

    /** Pruebe FTS4 MATCH primero y recurra a LIKE si hay una sola palabra o excepción. */
    private suspend fun runFtsOrLikeSearch(
        trimmed: String,
        limit: Int,
        mode: MatchMode = MatchMode.EXACT,
    ): List<Fact> {
        val like: suspend () -> List<Fact> = {
            val whole = dao.likeSearch(trimmed, limit)
            val rows = if (whole.isNotEmpty()) whole else likeByWords(trimmed, limit) { term -> dao.likeSearch(term, limit) }
            rows.map { it.toDomainFact() }
        }
        if (FactFtsManager.shouldFallbackToLike(trimmed)) return like()
        val matchQuery = FactFtsManager.toMatchQuery(trimmed, orSemantics = mode == MatchMode.ANY)
        if (matchQuery.isBlank()) return like()
        val ftsResults = resultOf { dao.searchFts(matchQuery, limit) }
            .onError { msg, t -> io.zer0.common.Logger.w("FactStore", "FTS search failed, fallback to LIKE: $msg", t) }
            .getOrNull() ?: emptyList()
        if (ftsResults.isEmpty()) return like()
        return ftsResults.map { it.toDomainFact() }
    }

    /**
     * Buscar por etiqueta(coincidencia exacta,OR lógica,Orden descendente por número de coincidencias).
     * Utilice json_each para hacer coincidir exactamente los valores de las etiquetas,Evite la falta de coincidencia de subcadenas LIKE.
     * B-06: Los resultados se filtran por hechos caducados.
     */
    suspend fun searchByTags(queryTags: List<String>, dateRange: DateRange? = null, limit: Int = 20): List<Fact> =
        searchByTagsInternal(queryTags, dateRange, limit, null, null)

    /** Busque etiquetas por alcance + espacio para el uso de search_memory durante la sesión. */
    suspend fun searchByTagsScoped(
        queryTags: List<String>,
        scope: String,
        spaceId: String,
        dateRange: DateRange? = null,
        limit: Int = 20,
    ): List<Fact> = searchByTagsInternal(queryTags, dateRange, limit, scope, spaceId)

    private suspend fun searchByTagsInternal(
        queryTags: List<String>,
        dateRange: DateRange?,
        limit: Int,
        scope: String?,
        spaceId: String?,
    ): List<Fact> = withContext(Dispatchers.IO) {
        if (queryTags.isEmpty()) return@withContext emptyList()
        val plan = TagSearchPlan(queryTags, dateRange, limit, scope, spaceId)
        val rows = dao.tagSearch(SimpleSQLiteQuery(plan.sql, plan.args))
        plan.refine(rows)
            .map { fact ->
                if (scope != null && spaceId != null) {
                    // TagSearchRow Es posible que las columnas nuevas no se rellenen cuando sean compatibles con las proyecciones de habitaciones antiguas;
                    // SQL Ya filtrado por límite, aquí el límite del host se vuelve a escribir explícitamente en el objeto de dominio.
                    fact.copy(scope = scope, spaceId = spaceId)
                } else {
                    fact
                }
            }
            .filterNot { it.isExpired() }
            .also { reviveSuppressed(it) }
    }

    /**
     * Obtener todos los metadatos(Orden descendente por tiempo).
     *
     * v8: Parámetro de alcance opcional agregado,null Representa todo el alcance.,No nulo Devuelve sólo hechos para el ámbito especificado.
     * v17: [includeSuppressed] Predeterminado true(lista/Eliminar duplicados/reconciliación/UI necesito ver todas las filas);
     * Inyectar pase de llamada de recuperación del contexto false,Excluir apariciones(puntuación por debajo del umbral)memoria.
     * v18: Pasar falso también excluye viejos recuerdos que han sido reemplazados.(OK todavía está en la biblioteca.,Simplemente no encaja en el contexto.).
     */
    suspend fun getAll(scope: String? = null, includeSuppressed: Boolean = true): List<Fact> = withContext(Dispatchers.IO) {
        val rows = dao.getAll(scope)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
        if (includeSuppressed) rows else rows.filterRecallable(scope, null)
    }

    /** B-18: Si el hecho ha expirado(expires_at Existe y es anterior a la hora actual.). */
    private fun Fact.isExpired(): Boolean {
        val expiresAt = expiresAt ?: return false
        val t = runCatching { java.time.Instant.parse(expiresAt) }.getOrNull() ?: return false
        return t.isBefore(java.time.Instant.now())
    }

    /** Consulta por session_id.B-06: Filtrar hechos caducados. */
    suspend fun getBySession(sessionId: String): List<Fact> = withContext(Dispatchers.IO) {
        dao.getBySession(sessionId).map { it.toDomainFact() }.filterNot { it.isExpired() }
    }

    /** Consulta por id.B-06: Hechos caducados devueltos null(Mismo calibre que la lista.). */
    suspend fun getById(id: Long): Fact? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toDomainFact()?.takeIf { !it.isExpired() }
    }

    /** total. */
    suspend fun size(): Int = withContext(Dispatchers.IO) { dao.count() }

    /** Compatibility path: derive scope/space from the row inside the transaction; assistant identity comes from this store. */
    suspend fun delete(id: Long): Boolean = deleteInternal(id, expectedAssistantId = assistantId)

    /** Delete only when all caller-provided ownership dimensions match the selected store and row. */
    suspend fun delete(id: Long, assistantId: String, scope: String, spaceId: String): Boolean =
        deleteInternal(id, assistantId, scope, spaceId)

    private suspend fun deleteInternal(
        id: Long,
        expectedAssistantId: String,
        expectedScope: String? = null,
        expectedSpaceId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (normalizeAssistantId(expectedAssistantId) != normalizeAssistantId(assistantId)) return@withContext false
        val deleted = db.withTransaction {
            val target = dao.getById(id) ?: return@withTransaction null
            if (expectedScope != null && target.scope != expectedScope) return@withTransaction null
            if (expectedSpaceId != null && target.spaceId != expectedSpaceId) return@withTransaction null
            val normalized = normalizeTombstone(target.fact)
            // Keep deletion state atomic: if the durable marker cannot be written, the source row and
            // its derived indexes remain untouched when Room rolls this transaction back.
            if (normalized.isNotBlank()) {
                db.factTombstoneDao().insertIfAbsent(
                    FactTombstoneEntity(
                        normalizedFact = normalized,
                        scope = target.scope,
                        spaceId = target.spaceId,
                        deletedAt = Instant.now().toString(),
                    ),
                )
                db.factTombstoneDao().trimScopeToLimit(target.scope, target.spaceId, TOMBSTONE_MAX_ENTRIES)
            }
            dao.deleteFts(id)
            val removed = dao.deleteById(id) > 0
            if (!removed) error("Fact disappeared during transactional delete (id=$id)")
            // memory_links deliberately has no FK cascade; remove both source/target edges atomically.
            db.memoryLinkDao().deleteByFactId(id)
            // v2.6.2(auditoría de memoria): Eliminar el historial de revisiones de forma sincrónica —— De lo contrario, después de que el usuario elimine la memoria confidencial,
            // El texto antiguo permanece en fact_revisions y se puede leer a través de la interfaz de revisiones, formando un residuo privado.
            revisionDao?.deleteByFactId(id)
            target.toDomainFact()
        }
        if (deleted != null) scheduleReconcile(listOf(deleted), deleted.scope, deleted.spaceId)
        deleted != null
    }

    /**
     * Cuando se elimina una sesión, se borran todos los datos generados por la sesión en la base de datos.
     *
     * Eliminar semántica y [delete] consistente:Primero escriba una lápida persistente para cada hecho.,Volver a limpiar el índice FTS, los bordes del mapa y el historial de revisiones,
     * Todo el lote se completa en la misma transacción de Habitación. La lápida es la clave —— El compilador se basa en él para determinar“No se permiten entradas adicionales para este artículo.”,
     * perdiéndolo la próxima ronda [io.zer0.memory.compile.MemoryCompiler] La misma oración en el resumen se agregará nuevamente a la tabla.
     *
     * Idempotente: la sesión no tiene hechos.(o aclarado)regresar cuando 0,No se informarán errores y las lápidas no se escribirán repetidamente.
     *
     * @return número real de filas eliminadas
     */
    suspend fun deleteBySession(sessionId: String): Int {
        val id = sessionId.trim()
        if (id.isEmpty()) return 0
        val rows = withContext(Dispatchers.IO) { dao.getBySession(id) }
        return deleteRows(rows) { dao.deleteBySession(id) }
    }

    /**
     * Cuando se elimina el asistente, se borran todos los datos sobre el alcance en la biblioteca.
     *
     * Los archivos de la subbiblioteca los elimina la parte que elimina el asistente.,Este método solo controla las filas de la biblioteca. —— biblioteca principal `facts.db` adentro
     * Puede que aún queden líneas históricas con el mismo alcance,eso es“Eliminé el asistente pero la página de memoria aún está visible”Otra fuente de.
     *
     * @return número real de filas eliminadas
     */
    suspend fun deleteAllByScope(scope: String): Int {
        val key = scope.trim()
        if (key.isEmpty()) return 0
        val rows = withContext(Dispatchers.IO) { dao.getByScope(key) }
        return deleteRows(rows) { dao.deleteAllByScope(key) }
    }

    /**
     * Eliminar por lotes un conjunto de hechos:Tombstone + FTS + Borde del mapa + Historial de revisiones,Todo un lote de transacciones.
     *
     * [removeRows] responsable de lo real DELETE(facts_fts Hay desencadenantes en cascada,No escriba otra declaración de eliminación);
     * [rows] Solo se utiliza para escribir lápidas y borrar datos derivados.,Por lo tanto, lea primero y luego elimine, el ensamblaje de filas entra en vigor junto con la transacción.
     */
    private suspend fun deleteRows(rows: List<FactEntity>, removeRows: suspend () -> Int): Int = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext 0
        val removed = db.withTransaction {
            val now = Instant.now().toString()
            val touchedScopes = mutableSetOf<Pair<String, String>>()
            rows.forEach { row ->
                val normalized = normalizeTombstone(row.fact)
                if (normalized.isNotBlank()) {
                    db.factTombstoneDao().insertIfAbsent(
                        FactTombstoneEntity(
                            normalizedFact = normalized,
                            scope = row.scope,
                            spaceId = row.spaceId,
                            deletedAt = now,
                        ),
                    )
                    touchedScopes += row.scope to row.spaceId
                }
                // memory_links / fact_revisions Sin cascada de claves externas,Borrar elementos uno por uno por id.,De lo contrario, queda un huérfano que apunta a una línea vacía.
                db.memoryLinkDao().deleteByFactId(row.id)
                revisionDao?.deleteByFactId(row.id)
            }
            touchedScopes.forEach { (scope, spaceId) ->
                db.factTombstoneDao().trimScopeToLimit(scope, spaceId, TOMBSTONE_MAX_ENTRIES)
            }
            removeRows()
        }
        // Programar la conciliación después del envío:La sección HECHOS en el producto compilado se proyecta de acuerdo con los datos de la biblioteca.,Después de la eliminación, se debe volver a calcular.
        rows.groupBy { it.scope to it.spaceId }.forEach { (key, group) ->
            scheduleReconcile(
                group.map { Fact(id = it.id, fact = it.fact, scope = key.first, spaceId = key.second) },
                key.first,
                key.second,
            )
        }
        removed
    }

    private fun normalizeAssistantId(id: String): String = id.trim().ifBlank { "default" }

    /**
     * v2.6.2(auditoría de memoria): Fusión atómica de un conjunto de hechos similares. —— Cambiar el texto del guardián a [mergedContent]
     * y borrar [removableIds] El resto de los miembros están todos en**La misma transacción de habitación**Completado dentro.
     *
     * fondo: Solucionar la pérdida de datos irreversible en la integración de LLM —— Primero elimine el proceso anterior uno por uno y luego actualice el guardián.
     * Los dos pasos no están en la misma transacción; si la eliminación parcial tiene éxito pero la actualización del guardián falla, los hechos originales se han perdido.
     * “escribir de nuevo”Simplemente agregar uno nuevo no restaurará los campos originales de los datos eliminados./fijar hacia arriba/etiqueta/enlace/Revisión.
     *
     * Este método garantiza que toda la transacción se revertirá si falla algún paso.keeper Quédate con los miembros.
     * no irse update() Deduplicación automática interna (para evitar la superposición con la eliminación dentro del clúster)“doble eliminación”).
     *
     * @return El número de miembros realmente eliminados;keeper Devuelve -1 si no existe o la propiedad no coincide (no se realizan cambios)
     */
    suspend fun mergeIntoKeeper(keeperId: Long, mergedContent: String, removableIds: List<Long>, scope: String, spaceId: String): Int =
        withContext(Dispatchers.IO) {
            val trimmed = mergedContent.trim()
            if (trimmed.isEmpty()) return@withContext -1
            val scrubbed = PiiGuard.scrub(trimmed).cleaned
            var removedCount = -1
            var reconciled: Fact? = null
            db.withTransaction {
                val keeper = dao.getById(keeperId) ?: return@withTransaction
                if (keeper.scope != scope || keeper.spaceId != spaceId) return@withTransaction
                // Registro de revisión (dentro de la transacción; el error solo se registra, no hay semántica de fusión de reversión)
                recordRevisionIfKey(
                    factId = keeperId,
                    oldContent = keeper.fact,
                    newContent = scrubbed,
                    reason = "llm_consolidate",
                )
                if (dao.updateContent(keeperId, scrubbed, null) <= 0) return@withTransaction
                syncFtsRow(keeperId, FactFtsManager.toNgram(scrubbed))
                var removed = 0
                for (id in removableIds) {
                    if (id == keeperId) continue
                    if (removeMemberIfOwned(id, scope, spaceId)) removed++
                }
                removedCount = removed
                reconciled = Fact(id = keeperId, fact = scrubbed, scope = scope, spaceId = spaceId)
            }
            reconciled?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
            removedCount
        }

    /**
     * v2.6.2: Elimine un hecho de miembro al fusionar (con limpieza de desecho/FTS/enlaces/revisión).
     * La persona que llama ya debe retener la transacción; si el miembro no existe o la propiedad no coincide, devuelve falso (sin cambios).
     */
    private suspend fun removeMemberIfOwned(id: Long, scope: String, spaceId: String): Boolean {
        val target = dao.getById(id)
        if (target == null || target.scope != scope || target.spaceId != spaceId) return false
        val normalized = normalizeTombstone(target.fact)
        if (normalized.isNotBlank()) {
            db.factTombstoneDao().insertIfAbsent(
                FactTombstoneEntity(
                    normalizedFact = normalized,
                    scope = target.scope,
                    spaceId = target.spaceId,
                    deletedAt = Instant.now().toString(),
                ),
            )
        }
        dao.deleteFts(id)
        dao.deleteById(id)
        db.memoryLinkDao().deleteByFactId(id)
        revisionDao?.deleteByFactId(id)
        return true
    }

    /**
     * v19: Fusionar varias líneas en una —— Agregar un hecho agregado,Marque la línea de origen como“fusionado en él”.
     *
     * con [mergeIntoKeeper] la diferencia: Ese es“Conserva uno, elimina el resto.”,La información única sobre la línea de origen solo puede ser escrita por la persona que llama keeper;
     * No elimines ninguna línea de este artículo.,Pero inserte otro [mergedText],La línea fuente es sólo [FactEntity.mergedIntoId] Señalar a nueva línea,
     * Para que puedas cancelarlo en lotes([unmergeFacts]) —— Los usuarios aún pueden volver después de la fusión.
     *
     * negocios: Inserción de nueva fila + índice FTS + marca de fila de origen, todo dentro de la misma transacción de Room. Si algún paso falla, se revertirá todo el lote.,
     * no se quedará“Se ha creado el nuevo banco y el banco original también participa en la retirada.”estado semiacabado —— Eso pone el contenido duplicado en contexto dos veces.
     *
     * Reglas de herencia de metadatos:
     *  - importance / confidence la fuente más alta;category Tome la primera fuente no general.(con [mergeForDedup] consistente)
     *  - tags Fusionar y eliminar duplicados(Orden de primera aparición por ID de fuente ascendente)
     *  - time / sessionId / expiresAt / lastConfirmedAt / entityKey Obtener el primer valor no nulo
     *  - source: Si hay user_explicit en la fuente, es user_explicit,De lo contrario, utilice la fuente principal.
     *  - pinnedAt: Si hay un pin en la fuente, la nueva línea también quedará fijada.(Consigue el mejor tiempo lo antes posible) —— Los elementos reparados activamente por el usuario no se perderán debido a la fusión.
     *  - hitCount: Suma de fuentes,Las filas fusionadas asumen el número de veces que se recuperaron estas filas
     *  - score Obtenga la puntuación más alta de la fuente —— La fusión solo elimina los duplicados, no los recuerdos nuevos,No borres la edad de viejos recuerdos.;
     *    score_updated_at Registre como el momento de esta fusión.,suppressed_at Borrar(La fusión en sí cuenta como una visita manual.)
     *  - supersedes_id: Solo hay una columna,Cuando hay varios puntos de reemplazo diferentes en la fuente, solo se puede dejar uno.(Toma el número más pequeño),
     *    Intente no permitir que la memoria antigua que ha sido reemplazada vuelva al contexto porque la línea fuente sale del contexto.
     *
     * Filtro de fila de origen: Solo acepte filas que existan, que no se hayan fusionado y que tengan el mismo alcance + el mismo espacio que la fuente principal.
     * Las filas con merged_into_id ya no participarán en la combinación —— De lo contrario, una cadena que apunta a,
     * No puedo saber cuándo deshacer“¿Quién hizo la fusión de esta línea?”.
     *
     * La otra cara del mismo principio: Las filas fusionadas también son**No se puede utilizar como destino de deduplicación de escritura.**(El contenido nuevo se fusionará en una línea invisible.),
     * restringido por [findSimilarFact] en el predicado coincidente.
     *
     * @param sourceIds Hechos involucrados en la fusión id(Se ignoran los valores duplicados y los identificadores inexistentes.)
     * @param mergedText Texto fusionado(Después de la deduplicación, se ha integrado información única de diversas fuentes.)
     * @param scope cheque de pertenencia:Cuando no es nulo, solo se aceptan fuentes de este alcance.
     * @param spaceId cheque de pertenencia:Cuando no es nulo, solo se acepta la fuente de este espacio.
     * @return nuevos hechos id;No hay fuente disponible(Lista vacía / No existen todos o se han fusionado / El texto está vacío)regresar cuando null
     */
    suspend fun mergeFacts(
        sourceIds: List<Long>,
        mergedText: String,
        scope: String? = null,
        spaceId: String? = null,
    ): Long? = withContext(Dispatchers.IO) {
        val trimmed = mergedText.trim()
        if (trimmed.isEmpty()) return@withContext null
        val ids = sourceIds.filter { it > 0L }.distinct()
        if (ids.isEmpty()) return@withContext null
        val scrubbed = PiiGuard.scrub(trimmed).cleaned
        var mergedFact: Fact? = null
        var newId: Long? = null
        db.withTransaction {
            val candidates = ids.mapNotNull { dao.getById(it) }
                .filter { candidate ->
                    candidate.mergedIntoId == null &&
                        (scope == null || candidate.scope == scope) &&
                        (spaceId == null || candidate.spaceId == spaceId)
                }
            val owner = candidates.firstOrNull() ?: return@withTransaction
            // El mismo lote de selecciones debe estar en el mismo ámbito + espacio: las líneas transfronterizas no se fusionan,Evite líneas de origen que apunten a nuevas líneas en otro ámbito
            val sources = candidates
                .filter { it.scope == owner.scope && it.spaceId == owner.spaceId }
                .sortedBy { it.id }
            if (sources.isEmpty()) return@withTransaction
            val now = Instant.now().toString()
            val entity = FactEntity(
                fact = scrubbed,
                tags = json.encodeToString(ListSerializer(String.serializer()), inheritedTags(sources)),
                time = sources.firstNotNullOfOrNull { it.time },
                sessionId = sources.firstNotNullOfOrNull { it.sessionId },
                createdAt = now,
                importance = sources.maxOf { it.importance }.coerceIn(0, 2),
                category = sources.firstOrNull { it.category.isNotBlank() && it.category != "general" }?.category
                    ?: "general",
                confidence = sources.maxOf { it.confidence }.coerceIn(0f, 1f),
                source = if (sources.any { it.source == "user_explicit" }) "user_explicit" else owner.source,
                expiresAt = sources.firstNotNullOfOrNull { it.expiresAt },
                lastConfirmedAt = sources.firstNotNullOfOrNull { it.lastConfirmedAt },
                lastHitAt = now,
                hitCount = sources.sumOf { it.hitCount },
                scope = owner.scope,
                spaceId = owner.spaceId,
                pinnedAt = sources.mapNotNull { it.pinnedAt }.minOrNull(),
                entityKey = sources.firstNotNullOfOrNull { it.entityKey },
                score = sources.maxOf { it.score },
                scoreUpdatedAt = now,
                suppressedAt = null,
                supersedesId = sources.mapNotNull { it.supersedesId }.minOrNull(),
            )
            val id = dao.insert(entity)
            syncFtsRow(id, FactFtsManager.toNgram(scrubbed))
            dao.updateMergedInto(sources.map { it.id }, id)
            newId = id
            mergedFact = entity.copy(id = id).toDomainFact()
        }
        // Conciliación incremental posterior a la escritura: Proyección“presente y no fusionado”de filas,La siguiente inyección es el texto fusionado.(La línea fuente sale automáticamente)
        mergedFact?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
        newId
    }

    /**
     * v19: Deshacer fusión —— Eliminar producto fusionado,Borre el fusionado_into_id de la fila de origen que apunta a él.
     *
     * La misma transacción de habitación: Primero borre el puntero y luego elimine la línea. El orden no se puede revertir —— Eliminar la fila primero dejará un lote de referencias a identificadores que ya no existen.
     * marca huérfana,Esas filas de origen ya no se recuperan ni forman parte de ninguna combinación.
     *
     * Escribir eliminar lápida al eliminar una línea(con [deleteInternal] consistente): De lo contrario, la próxima ronda de conciliación de FACTS incluirá el producto compilado.
     * La línea restante de texto combinado se trata como“producto huérfano”Vuelva a ingresar a la tabla de hechos —— Se revivió la fusión que acababa de deshacerse.
     *
     * @return ¿Se eliminó el producto fusionado?(id Devuelve si no existe false,Sin cambios)
     */
    suspend fun unmergeFacts(mergedFactId: Long): Boolean = withContext(Dispatchers.IO) {
        val removed = db.withTransaction {
            val target = dao.getById(mergedFactId) ?: return@withTransaction null
            dao.clearMergedInto(mergedFactId)
            val normalized = normalizeTombstone(target.fact)
            if (normalized.isNotBlank()) {
                db.factTombstoneDao().insertIfAbsent(
                    FactTombstoneEntity(
                        normalizedFact = normalized,
                        scope = target.scope,
                        spaceId = target.spaceId,
                        deletedAt = Instant.now().toString(),
                    ),
                )
            }
            dao.deleteFts(mergedFactId)
            if (dao.deleteById(mergedFactId) <= 0) error("Merged fact disappeared during transactional unmerge (id=$mergedFactId)")
            db.memoryLinkDao().deleteByFactId(mergedFactId)
            revisionDao?.deleteByFactId(mergedFactId)
            target.toDomainFact()
        }
        removed?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
        removed != null
    }

    /**
     * v19: Qué filas originales se agregan mediante un hecho de fusión(Para visualización de interfaz de usuario“Fusionado por N elementos”Vista previa antes de deshacer).
     *
     * No filtrar por expires_at: La relación de fusión es un hecho histórico,Las filas de origen caducadas todavía pertenecen a esta combinación.
     */
    suspend fun getMergedChildren(factId: Long): List<Fact> = withContext(Dispatchers.IO) {
        dao.getMergedChildren(factId).map { it.toDomainFact() }
    }

    /** v19: Etiquetas heredadas al fusionar —— Cada etiqueta de origen se deduplica en el orden de su primera aparición en orden ascendente de identificación. */
    private fun inheritedTags(sources: List<FactEntity>): List<String> {
        val seen = LinkedHashSet<String>()
        for (source in sources) {
            decodeTags(source.tags).forEach { tag -> if (tag.isNotBlank()) seen.add(tag) }
        }
        return seen.toList()
    }

    /**
     * P2: Actualizar el contenido de un único hecho(Se utiliza para recordar la edición de la interfaz de usuario de la página Capa de hechos).
     *
     * con [add] diferente,Aquí no hay desensibilización de PII(Contenido editado manualmente por los usuarios.,quédate como está),
     * Realice únicamente la eliminación de espacios en blanco ligeros. Devuelve si la actualización fue exitosa(Devuelto si la identificación del objetivo no existe false).
     *
     * v8: Parámetro de alcance opcional agregado:
     *  - scope para null(Predeterminado):Actualizar solo content,Mantener el alcance original
     *  - scope No nulo: actualiza el contenido y scope(Se utiliza para que la interfaz de usuario cambie los alcances de los hechos.)
     */
    suspend fun update(id: Long, content: String, scope: String? = null): Boolean = withContext(Dispatchers.IO) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext false
        // B-17: La actualización también utiliza la desensibilización de PII. — La persona que llama contiene la ruta de salida de LLM(MemoryAutoSaveScheduler
        // update Fusionar resultados de deduplicación),viejo comentario"La edición del manual del usuario no causa desensibilización"La premisa no está establecida.;
        // consistente con agregar,La información confidencial nunca se mantendrá clara.
        val scrubbed = PiiGuard.scrub(trimmed).cleaned
        val target = dao.getById(id)
        val updated = dao.updateContent(id, scrubbed, scope) > 0
        // Phase 3 (P1): Hechos finales actualizados(El escenario fusionado es el resultado fusionado.),Se utiliza para la conciliación incremental posterior a la escritura
        var reconciled: Fact? = null
        if (updated) {
            // v1.0.51: Utilice upsertFts para evitar entradas FTS duplicadas al actualizar datos existentes
            syncFtsRow(id, FactFtsManager.toNgram(scrubbed))
            // v12: Acceda a la verificación de deduplicación después de la actualización — Cuando el nuevo contenido duplica otros hechos bajo el mismo alcance/espacio,
            //    Fusión automática(Eliminar parte cubierta,Mantenga una declaración más completa),Reparación"Ruta de actualización para evitar la deduplicación"lagunas jurídicas.
            val current = target ?: dao.getById(id)
            if (current != null) {
                // v13 (T4-1): update Revisión de registros(importancia ≥1 memoria clave)
                recordRevisionIfKey(
                    factId = id,
                    oldContent = current.fact,
                    newContent = scrubbed,
                    reason = "update",
                )
                val mergedEntity = current.copy(fact = scrubbed)
                // v12: excludeId=mismo — Después de la actualización, findSimilarFact no debería coincidir consigo mismo
                val dup = findSimilarFact(scrubbed, mergedEntity.scope, mergedEntity.spaceId, mergedEntity.entityKey, excludeId = id)
                if (dup != null && dup.id != id) {
                    val merged = mergeFact(
                        dup,
                        Fact(
                            fact = scrubbed,
                            scope = dup.scope,
                            spaceId = dup.spaceId,
                            entityKey = dup.entityKey,
                        ),
                    )
                    dao.updateEntity(
                        merged.id, merged.fact, merged.tags, merged.time, merged.sessionId,
                        merged.createdAt, merged.importance, merged.category, merged.confidence,
                        merged.source, merged.expiresAt, merged.lastConfirmedAt, merged.lastHitAt,
                        merged.entityKey,
                    )
                    syncFtsRow(merged.id, FactFtsManager.toNgram(merged.fact))
                    dao.deleteFts(id)
                    dao.deleteById(id)
                    io.zer0.common.Logger.d("FactStore", "update Activar combinación de deduplicación: $id → ${merged.id}")
                    reconciled = Fact(id = merged.id, fact = merged.fact, scope = merged.scope, spaceId = merged.spaceId)
                } else {
                    reconciled = Fact(
                        id = id,
                        fact = scrubbed,
                        scope = mergedEntity.scope,
                        spaceId = mergedEntity.spaceId,
                    )
                }
            }
        }
        reconciled?.let { scheduleReconcile(listOf(it), it.scope, it.spaceId) }
        updated
    }

    /**
     * v2.6.2(auditoría de memoria): Actualizar atómicamente el texto + importancia + clasificación de un hecho/etiqueta.
     *
     * implementación anterior (MemoryAutoSaveScheduler) ajustado en tres tiempos update/setImportance/updateCategoryAndTags,
     * Cuando el guardado automático simultáneo o dos actualizaciones en el mismo análisis apuntan al mismo hecho, la escritura posterior sobrescribe la escritura anterior y los tres tipos de campos no están en la misma transacción.
     * Habrá una semi-actualización. Este método completa la sincronización de todos los campos con FTS dentro de la misma transacción de Room.
     *
     * @return Si la actualización se realizó correctamente (devuelve falso si el objetivo no existe o la propiedad no coincide)
     */
    // LongParameterList Exención: las actualizaciones de campos ya corresponden a varios campos y dividir objetos reducirá la legibilidad de la llamada.
    @Suppress("LongParameterList")
    suspend fun applyFieldUpdates(
        id: Long,
        scope: String,
        spaceId: String,
        content: String?,
        importance: Int?,
        category: String?,
        tags: List<String>?,
    ): Boolean = withContext(Dispatchers.IO) {
        val scrubbedContent = content?.trim()?.takeIf { it.isNotEmpty() }?.let { PiiGuard.scrub(it).cleaned }
        val scrubbedTags = tags?.map { PiiGuard.scrub(it).cleaned }
        val tagsJson = scrubbedTags?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
        db.withTransaction {
            val target = dao.getById(id) ?: return@withTransaction false
            if (target.scope != scope || target.spaceId != spaceId) return@withTransaction false
            if (scrubbedContent != null) {
                recordRevisionIfKey(factId = id, oldContent = target.fact, newContent = scrubbedContent, reason = "update")
                if (dao.updateContent(id, scrubbedContent, null) <= 0) return@withTransaction false
                syncFtsRow(id, FactFtsManager.toNgram(scrubbedContent))
            }
            if (importance != null) dao.updateImportance(id, importance.coerceIn(0, 2))
            if (category != null || tagsJson != null) dao.updateCategoryAndTags(id, category, tagsJson)
            true
        }
    }

    /**
     * v4: Actualizar la importancia de un solo hecho(Se utiliza para ajustes manuales de la interfaz de usuario de la página de memoria.).
     * @param importance 0=ordinario,1=importante,2=clave
     * @return ¿La actualización fue exitosa?
     */
    suspend fun setImportance(id: Long, importance: Int): Boolean = withContext(Dispatchers.IO) {
        dao.updateImportance(id, importance.coerceIn(0, 2)) > 0
    }

    /** B4-05: ajustes/Cancelar la fijación manual. */
    suspend fun setPinned(id: Long, pinned: Boolean): Boolean = withContext(Dispatchers.IO) {
        dao.updatePinnedAt(id, if (pinned) Instant.now().toString() else null) > 0
    }

    /**
     * v10 P2-3: Actualizar la clasificación y etiqueta del hecho especificado.(Para la gestión de memoria de IA).
     *
     * @param category Clasificación(null Mantener el valor original)
     * @param tags lista de etiquetas(null Mantener el valor original,Si no es nulo, reemplace)
     * @return ¿La actualización fue exitosa?
     */
    suspend fun updateCategoryAndTags(id: Long, category: String? = null, tags: List<String>? = null): Boolean =
        withContext(Dispatchers.IO) {
            // B-17: tags Desensibilización de PII detallada — La persona que llama contiene la ruta LLM(autoCategorize/AI gestión de memoria),
            // prompt Fomentar el etiquetado con los nombres de las personas.,Sin desensibilización, el nombre quedará registrado en la base de datos.
            val scrubbedTags = tags?.map { PiiGuard.scrub(it).cleaned }
            val tagsJson = scrubbedTags?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
            dao.updateCategoryAndTags(id, category, tagsJson) > 0
        }

    /** Limpiar todo. */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.clearFts()
            dao.deleteAll()
            db.factTombstoneDao().deleteAll()
            // v2.6.2(auditoría de memoria): Al mismo tiempo, el historial de revisiones se borra para evitar que el texto antiguo aún pueda leerse después del reinicio.
            revisionDao?.deleteAll()
        }
        if (clearLegacyTombstonesOnReset) {
            synchronized(tombstoneLock) { tombstoneFile?.let { runCatching { it.delete() } } }
        }
    }

    /**
     * P0-10: Restablecer la limpieza en cascada de memoria — Hechos + FTS + Lápidas + Bordes del gráfico de conocimiento(memory_links)Vaciar una vez.
     *
     * anteriormente"restablecer la memoria"Eliminar solo la línea de hechos,Los bordes del gráfico que apuntan a hechos eliminados permanecen huérfanos(memory_links Al crear una tabla
     * No agregar deliberadamente una cascada de claves externas),Y la biblioteca del subasistente no se ha limpiado en absoluto. Este método se utiliza para páginas de gestión de datos."restablecer la memoria"con
     * Elimine el recurso compartido en cascada del asistente.
     */
    suspend fun clearAllWithLinks(): Unit = withContext(Dispatchers.IO) {
        clearAll()
        db.memoryLinkDao().deleteAll()
    }

    // ─── Phase 3 (Fiabilidad P1): Conciliación incremental después de escribir(acelerador/fusionar/Anti-recursión) ───

    /**
     * Ventana de combinación para conciliación posterior a la escritura(ms). Múltiples escrituras dentro de una ventana(addBatch/Agregar/fusionar continuamente)fusionarse en uno
     * hook llamar,Evite escrituras consecutivas que amplifiquen la sobrecarga de conciliación.internal Es conveniente para pruebas únicas y acorta el tiempo de espera.
     */
    internal var reconcileDebounceMs: Long = DEFAULT_RECONCILE_DEBOUNCE_MS

    /** Bloqueo de programación de conciliación:proteger [reconcilePending] con [reconcileJob](Las escrituras pueden provenir de múltiples corrutinas.). */
    private val reconcileLock = Any()

    /** Hechos a conciliar,prensa (scope, spaceId) grupo;El gancho se llama grupo por grupo cuando finaliza la ventana. */
    private val reconcilePending = LinkedHashMap<Pair<String, String>, MutableList<Fact>>()

    /** Programación de tareas para la ventana de limitación actual;Las escrituras repetidas dentro de la ventana no se programan repetidamente. */
    private var reconcileJob: Job? = null

    /** hook Establecido durante la ejecución — Evite que la activación recursiva escriba hechos dentro del gancho. */
    private val reconcileInFlight = AtomicBoolean(false)

    /** Corrutina de reconciliación scope(Sólo realiza tareas ligeras de aceleración,No bloquea la ruta de escritura.). */
    private val reconcileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Caché de resultados de análisis del gancho predeterminado(La inyección explícita tiene prioridad,ver [resolveReconcileHook]). */
    @Volatile
    private var resolvedDefaultHook: FactReconcileHook? = null

    /**
     * Phase 3 (P1): Programe una conciliación incremental después de una escritura.
     *
     * - acelerador/fusionar: [reconcileDebounceMs] Varias escrituras dentro de la ventana se combinan en una llamada de enlace;
     * - Anti-recursión: hook Las escrituras durante la ejecución ya no se activan(Mantenido por la conciliación diaria de la línea de montaje.);
     * - Ninguno hook(prueba única/Ninguno DI)Gastos generales de programación en tiempo cero,El comportamiento es exactamente el mismo que en la versión anterior.
     */
    private fun scheduleReconcile(facts: List<Fact>, scope: String, spaceId: String) {
        if (facts.isEmpty()) return
        if (reconcileInFlight.get()) return
        if (resolveReconcileHook() == null) return
        synchronized(reconcileLock) {
            val bucket = reconcilePending.getOrPut(scope to spaceId) { mutableListOf() }
            facts.forEach { fact ->
                if (bucket.none { it.id == fact.id && it.fact == fact.fact }) bucket.add(fact)
            }
            if (reconcileJob?.isActive != true) {
                reconcileJob = reconcileScope.launch {
                    delay(reconcileDebounceMs)
                    flushReconcile()
                }
            }
        }
    }

    /** Phase 3 (P1): Saque el lote a conciliar y ejecútelo. hook;Alarma solo en caso de falla(Recopilación y conciliación diaria.). */
    private suspend fun flushReconcile() {
        val batches = synchronized(reconcileLock) {
            if (reconcilePending.isEmpty()) {
                reconcileJob = null
                return
            }
            val copy = reconcilePending.entries.map { (key, facts) ->
                Triple(key.first, key.second, facts.toList())
            }
            reconcilePending.clear()
            reconcileJob = null
            copy
        }
        val hook = resolveReconcileHook() ?: return
        reconcileInFlight.set(true)
        try {
            for ((scope, spaceId, facts) in batches) {
                resultOf { hook.onFactsChanged(facts, scope, spaceId) }
                    .onError { msg, t ->
                        Logger.w("FactStore", "La conciliación falló después de escribir(Se compilará la próxima vez.): ${t?.message ?: msg}")
                    }
            }
        } finally {
            reconcileInFlight.set(false)
        }
    }

    /** Phase 3 (P1): La inyección explícita tiene prioridad;De lo contrario, el enlace predeterminado se analiza y almacena en caché de forma diferida.(Si el análisis falla, inténtalo de nuevo la próxima vez.). */
    private fun resolveReconcileHook(): FactReconcileHook? {
        reconcileHook?.let { return it }
        resolvedDefaultHook?.let { return it }
        return resolveDefaultReconcileHook()?.also { resolvedDefaultHook = it }
    }

    // ─── S-04: Eliminar lápida(Evitar que los hechos eliminados se recuperen del resumen) ───

    /**
     * Legacy pre-v15 JSON tombstone read lock. New deletion markers are transactional Room rows
     * and are isolated by this FactStore's assistant database, scope, and space.
     */
    private val tombstoneLock = TOMBSTONE_LOCK

    /** Todas las lápidas de esta biblioteca; principalmente para llamadas de compatibilidad y compilación/La inyección debe utilizar la sobrecarga con alcance y espacio. */
    suspend fun getTombstones(): List<String> = withContext(Dispatchers.IO) {
        (db.factTombstoneDao().getAllNormalizedFacts() + loadLegacyTombstones()).distinct()
    }

    /** Solo devuelve la base de datos del asistente actual,scope y marcadores de eliminación de espacios. */
    suspend fun getTombstones(scope: String, spaceId: String): List<String> = withContext(Dispatchers.IO) {
        val currentFacts = dao.getByScopeAndSpace(scope, spaceId)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
            .map { comparableTombstone(it.fact) }
            .toSet()
        val scoped = db.factTombstoneDao().getNormalizedFacts(scope, spaceId)
            .filterNot { comparableTombstone(it) in currentFacts }
        // Pre-v15 installs have an unscoped shared JSON list. Keep those legacy markers fail-closed,
        // except where an exact active fact in this assistant/scope/space proves it was re-added.
        val legacy = loadLegacyTombstones()
            .filterNot { comparableTombstone(it) in currentFacts }
        (scoped + legacy).distinct()
    }

    private fun loadLegacyTombstones(): List<String> {
        val file = tombstoneFile ?: return emptyList()
        return synchronized(tombstoneLock) {
            if (!file.exists()) return@synchronized emptyList()
            runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), file.readText())
            }.getOrElse {
                Logger.w("FactStore", "readTombstones failed: ${it.message}")
                emptyList()
            }
        }
    }

    /** estandarizar: Eliminar espacios en blanco iniciales y finales + comprimir espacios en blanco consecutivos,Garantizado que seguirá coincidiendo en todos los matices de redacción. */
    private fun normalizeTombstone(text: String): String = text.trim().replace(WHITESPACE_RE, " ")

    /** Exact comparison used to detect a legitimate re-add without applying substring deletion semantics. */
    private fun comparableTombstone(text: String): String = normalizeTombstone(text).replace(PUNCT_RE, "")

    companion object {
        /** D6 Número 2: “Múltiples entidades de afirmación”Umbral predeterminado —— Misma entidad ≥3 Vale la pena incitar a la integración a diferentes afirmaciones. */
        const val DEFAULT_MIN_ASSERTIONS = 3

        /**
         * Semántica coincidente para la recuperación de texto completo.
         *
         * [EXACT] Requiere que se atiendan todas las palabras de la consulta(comportamiento original);[ANY] Cualquier acierto cuenta como candidato.
         * La única diferencia entre los dos es el conector de la expresión MATCH y la clasificación de LIKE.,No cambia de alcance/Filtrado espacial.
         */
        enum class MatchMode {
            /** Todas las palabras acertan. */
            EXACT,

            /** Cualquiera de los dos acertó. */
            ANY,
        }

        private val WHITESPACE_RE = Regex("\\s+")

        /** B-26: Bloqueo de lápida a nivel de proceso(Todas las instancias de FactStore comparten el mismo bloqueo). */
        val TOMBSTONE_LOCK = Any()

        /** B-10: Límite de entrada a la lápida(Más antiguo más allá del recorte,El tamaño del archivo y la sobrecarga de filtrado están limitados). */
        const val TOMBSTONE_MAX_ENTRIES = 1000

        /** Phase 3 (P1): Valor predeterminado de la ventana de combinación de conciliación posterior a la escritura(ms). */
        const val DEFAULT_RECONCILE_DEBOUNCE_MS = 1500L

        /**
         * Phase 3 (P1): Conciliación después de escribir por defecto hook — Análisis diferido de Koin en tiempo de ejecución
         * [io.zer0.memory.compile.MemoryCompiler],Alinee los productos compilados de FACTS con la tabla de hechos.
         *
         * No analizado durante la construcción de FactStore:MemoryCompiler dependencia inversa FactStore,La búsqueda durante la construcción constituirá
         * Koin dependencia circular;Busque solo cuando se active con la primera escritura. Ninguno DI(prueba única/CLI)O regresar cuando no esté registrado null(Desactivar),
         * El comportamiento es consistente con versiones anteriores.(Confíe únicamente en la recopilación y conciliación diaria).
         */
        private fun resolveDefaultReconcileHook(): FactReconcileHook? {
            val koin = org.koin.core.context.GlobalContext.getOrNull() ?: return null
            val compiler = runCatching {
                koin.get<io.zer0.memory.compile.MemoryCompiler>()
            }.getOrNull() ?: return null
            return FactReconcileHook { facts, scope, spaceId ->
                compiler.reconcileFactsSectionWithStore(
                    facts,
                    io.zer0.memory.compile.MemoryCompileTarget(scope = scope, spaceId = spaceId),
                )
            }
        }

        /** B-07: puntuación/eliminación de espacios en blanco(segundo canal coincidente). */
        private val PUNCT_RE = Regex("[,. ! ? ,;:,.!?;:()()\\[\\]【】\"'“”‘’\\s]+")

        /**
         * Revisión de corrección (2.0 B-07): Filtro de lápida compartido — Coincidencia normalizada línea por línea.
         *
         * El objeto compilado es un resumen reescrito de otro pase de LLM.,Es casi seguro que la redacción es diferente del texto original de la lápida.,
         * La coincidencia de contenidos puros a menudo falla. Coincidencia de doble canal:
         *  - Canal 1: coincidencia de subcadenas comprimidas con espacios en blanco(La grabación de Tombstone está comprimida);
         *  - Paso 2: puntuación de tira/Coincidencia de subcadenas después de espacios en blanco(Capture nueva puntuación cuando se reescriba LLM/nueva línea).
         * Normalización semántica completa(sinónimo de reescritura)Se requiere determinación de LLM,más allá del alcance de este método.
         * definido como miembro acompañante,Llamado por MemoryCompiler/DeepMemoryProcessor con nombre de clase.
         */
        fun filterTombstonedLines(text: String, tombstones: List<String>): String {
            if (tombstones.isEmpty()) return text
            return text.lines()
                .filterNot { line ->
                    val normalized = line.trim()
                    normalized.isNotEmpty() && tombstones.any { matchesTombstone(normalized, it) }
                }
                .joinToString("\n")
        }

        /** B-07: Si un solo texto llega a una determinada lápida(Coincidencia de doble canal,ver [filterTombstonedLines]). */
        fun matchesTombstone(text: String, tombstone: String): Boolean {
            val normalized = text.trim().replace(WHITESPACE_RE, " ")
            val tomb = tombstone.trim().replace(WHITESPACE_RE, " ")
            if (normalized.contains(tomb)) return true
            val stripped = normalized.replace(PUNCT_RE, "")
            val strippedTomb = tomb.replace(PUNCT_RE, "")
            return strippedTomb.isNotBlank() && stripped.contains(strippedTomb)
        }
    }

    /**
     * v6: Verifique la coherencia del índice Facts_fts,Si es inconsistente, reconstruya en su totalidad.
     *
     * escena desencadenante:
     * - v5→v6 Después de la migración(facts_fts mesa vacia,Requiere indexación completa por primera vez)
     * - Modificar externamente la tabla de hechos.(Después de la importación de la copia de seguridad)
     * - Corrupción del índice(FTS Después de que la consulta sea anormal, la siguiente búsqueda será de autorreparación.)
     *
     * Configurar después de llamar una vez [ftsConsistencyChecked],Evite duplicar COUNT.
     */
    suspend fun ensureFtsIndexConsistent() = withContext(Dispatchers.IO) {
        if (ftsConsistencyChecked) return@withContext
        ftsConsistencyChecked = true
        val factCount = resultOf { dao.countFacts() }.getOrNull() ?: -1
        val ftsCount = resultOf { dao.countFts() }.getOrNull() ?: -1
        if (factCount < 0 || ftsCount < 0) {
            io.zer0.common.Logger.w("FactStore", "FTS count check failed: facts=$factCount fts=$ftsCount")
            return@withContext
        }
        if (factCount == ftsCount) {
            io.zer0.common.Logger.d("FactStore", "FTS index consistent: $ftsCount rows")
            return@withContext
        }
        io.zer0.common.Logger.i("FactStore", "FTS index inconsistent: facts=$factCount fts=$ftsCount, rebuilding...")
        rebuildFtsIndex()
    }

    /**
     * v6: Reconstruya completamente el índice Facts_fts. Después de borrar, recorra los hechos y vuelva a insertarlos.(ngram Convertir).
     *
     * La persona que llama debe estar en el hilo IO.
     */
    suspend fun rebuildFtsIndex() = withContext(Dispatchers.IO) {
        dao.clearFts()
        val rows = dao.getAllForFtsRebuild()
        var ok = 0
        rows.forEach { row ->
            resultOf {
                dao.insertFts(row.id, FactFtsManager.toNgram(row.fact))
                ok++
            }.onError { msg, t ->
                io.zer0.common.Logger.w("FactStore", "FTS rebuild insert failed for ${row.id}: $msg", t)
            }
        }
        io.zer0.common.Logger.i("FactStore", "FTS rebuild done: $ok/${rows.size} facts indexed")
    }

    /**
     * prensa [MemoryConfig] La configuración realiza una ronda de decadencia de hechos.
     *
     * v4 mejorar:hechos clave(importance=2)nunca decaer,evitar"alergia a la penicilina"Se olvida la información de alto riesgo.
     * v7 mejorar:Introduciendo el bono de golpe(hitBonus).
     * v8 mejorar:Parámetro de alcance opcional agregado,null Representa todo el alcance.(Compatible con personas mayores).
     *
     * v17(La atenuación se puede reducir):de"Eliminación definitiva caducada"Cambiar a"apariencia de la partitura".
     *  - Puntuación caída en la biblioteca. `score` columna,Ya no se calcula en función de la edad cada vez,Existe una base estable para la determinación del umbral.;
     *  - reloj de liquidación:Última hora de liquidación(score_updated_at) → Regresar al primer asentamiento. created_at;
     *    Golpear de nuevo desde el último acuerdo/fusionar(last_hit_at actualizar)línea para rellenar el hitBonus y restablecer el reloj;
     *  - Después de la liquidación, la puntuación es inferior a [MemoryConfig.compileThreshold] filas escritas suppressed_at(aparecer):
     *    Ya no participa en la recuperación automática ni en la inyección de contexto.,pero toda la fila permanece,Se puede hacer referencia más adelante./retroceso de búsqueda explícito;
     *  - rebote de puntuación(o cambiar a clave/fijar hacia arriba)La línea que se borra suprimida_at vuelve a participar en la recuperación.
     * solo `expires_at` Los datos vencidos y urgentes permanecen eliminados —— Eso es explícito al escribir. TTL,No determinado por el umbral de atenuación.
     * limpieza real(Eliminar un solo elemento/borrar la memoria)ir [delete] / [clearAll] Espere una entrada explícita.
     *
     * Este método está en daily pipeline(deepMemory step después)por
     * [io.zer0.memory.deep.DeepMemoryProcessor] llamar,El FactStore de cada asistente se ejecuta una vez.
     *
     * @return Nueva apariencia esta ronda(Es decir, ya no participará en el retiro.)El número de hechos
     */
    suspend fun applyDecay(config: MemoryConfig, scope: String? = null, spaceId: String? = null): Int =
        withContext(Dispatchers.IO) {
            val now = Instant.now()
            val nowIso = now.toString()
            // B-18: Primero limpie los datos caducados(expires_at < now) — Los hechos de puntualidad están determinados por TTL explícito,Sin atenuación
            dao.deleteExpired(nowIso, scope, spaceId)
            // v2.6.2(auditoría de memoria): Borre los bordes huérfanos de manera uniforme después de la eliminación del lote/Revisión.
            db.memoryLinkDao().deleteOrphans()
            revisionDao?.deleteOrphans()

            val candidates = dao.getDecayCandidates(scope, spaceId)
            var outOfRecall = 0
            db.withTransaction {
                for (candidate in candidates) {
                    // Si no se puede analizar la marca de tiempo, la fila no se liquidará en esta ronda.:Preferiría no decaer,No juzgues mal una apariencia con una puntuación baja.
                    val elapsed = elapsedDays(candidate.refAt, now) ?: continue
                    var next = decayedScore(candidate.score, elapsed, config)
                    if (candidate.hitSinceDecay != 0) {
                        next = minOf(next + config.hitBonus, revivalCeiling(config))
                    }
                    dao.updateDecayedScore(candidate.id, next, nowIso)
                }
                outOfRecall = dao.suppressBelowThreshold(nowIso, config.compileThreshold, scope, spaceId)
                dao.releaseAboveThreshold(config.compileThreshold, scope, spaceId)
            }
            if (outOfRecall > 0) {
                Logger.d("FactStore", "applyDecay: $outOfRecall La puntuación de hechos está por debajo del umbral.,Salido del retiro(fila reservada)")
            }
            outOfRecall
        }

    /** milisegundos en un día(Para liquidación de atenuación). */
    private val millisPerDay = 86_400_000.0

    /**
     * [refAt] llegar [now] número de días entre(Los números negativos se cuentan como 0.).
     * Devuelto cuando no se puede analizar la marca de tiempo null —— La persona que llama debe saltarse esta línea.,en lugar de como"infinitamente viejo"Preséntese directamente.
     */
    private fun elapsedDays(refAt: String?, now: Instant): Double? {
        if (refAt.isNullOrBlank()) return null
        val ref = runCatching { Instant.parse(refAt) }.getOrNull() ?: return null
        val millis = java.time.Duration.between(ref, now).toMillis()
        return if (millis <= 0L) 0.0 else millis / millisPerDay
    }

    /**
     * decaimiento exponencial:`score × exp(-λ·días)`,λ = [MemoryConfig.decayPerDay] × [MemoryConfig.forgetSpeed].
     * con [MemoryConfig.factScore] mismo modelo:Índice de falta de memoria,La liquidación incremental equivale a una liquidación única basada en la edad.
     */
    private fun decayedScore(score: Float, elapsedDays: Double, config: MemoryConfig): Float {
        val lambda = (config.decayPerDay * config.forgetSpeed).toDouble()
        if (lambda <= 0.0) return score
        val next = score.toDouble() * exp(-lambda * elapsedDays)
        return if (next.isNaN() || next < 0.0) 0f else next.toFloat()
    }

    /** Límite de puntuación:No más que"Nueva memoria + bonificación de un golpe",Evite que los golpes repetidos acumulen su puntuación a un nivel alto sin sentido. */
    private fun revivalCeiling(config: MemoryConfig): Float = config.baseImportance + config.hitBonus

    /** v17: ¿Esta memoria todavía está dentro del rango de recuperación?(No presente;clave/Siempre en la cima). */
    private fun Fact.isInRecall(): Boolean = suppressedAt == null || pinnedAt != null || importance >= 2

    /**
     * v18: Presione alcance/espacio para eliminar“ha sido reemplazado”Una colección de identificadores de memoria antiguos.
     *
     * Devuelve un conjunto vacío cuando falla la consulta —— Cuando no hay base para el arbitraje, prefiero dejarlos a ambos en su contexto.,
     * Tampoco se puede borrar por error una memoria correcta sólo porque el puntero no se puede leer.
     */
    private suspend fun supersededIds(scope: String?, spaceId: String?): Set<Long> =
        runCatching { dao.getSupersededIds(scope, spaceId).toSet() }
            .onFailure { Logger.w("FactStore", "No se pudo leer la memoria reemplazada(No eliminado esta vez): ${it.message}") }
            .getOrDefault(emptySet())

    /**
     * v19: Presione alcance/espacio para eliminar“fusionado con otros hechos”Una colección de identificadores de fila.
     *
     * con [supersededIds] Igualmente tolerante a fallos: Devuelve un conjunto vacío si no se puede leer. —— Prefiero inyectar un recuerdo duplicado más.,
     * Tampoco se puede bloquear la memoria normal fuera de contexto debido a una lectura fallida.
     */
    private suspend fun mergedIds(scope: String?, spaceId: String?): Set<Long> =
        runCatching { dao.getMergedIds(scope, spaceId).toSet() }
            .onFailure { Logger.w("FactStore", "No se pudo leer la memoria fusionada(No eliminado esta vez): ${it.message}") }
            .getOrDefault(emptySet())

    /**
     * v18/v19: Filtrado de visibilidad para el contexto de inyección. —— Los que ya se han presentado no entrarán.,ha sido reemplazado,
     * No se incluyen los hechos que hayan sido incorporados a otros hechos. lista/Parámetros predeterminados de la página de memoria(Sin filtrado),
     * Por lo tanto, los recuerdos reemplazados y fusionados siguen siendo visibles y aún pueden recuperarse explícitamente.
     */
    private suspend fun List<Fact>.filterRecallable(scope: String?, spaceId: String?): List<Fact> {
        val superseded = supersededIds(scope, spaceId)
        val merged = mergedIds(scope, spaceId)
        return filter { it.isInRecall() && it.id !in superseded && it.id !in merged }
    }

    // ── v8: por alcance(scope)Consulta/observar/Atenuación ────────────────────────────────

    /**
     * v8: Observar lista de hechos por alcance.(Flow forma),Se utiliza para la actualización de la interfaz de usuario en tiempo real.
     * ordenar con [getAll] consistente:importance DESC + time DESC.
     */
    fun observeByScope(scope: String): Flow<List<Fact>> =
        dao.observeByScope(scope, java.time.Instant.now().toString()).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v8: Consulta la lista de hechos de forma sincrónica por alcance.
     * Se utiliza para inyección de avisos del sistema, recuperación de memoria del subasistente y otros escenarios.
     */
    /**
     * v17: [includeSuppressed] Predeterminado true(pagina de memoria/Necesita ver todas las filas para eliminar duplicados, etc.),
     * Escriba la ruta de recuperación pasada al contexto. false —— El recuerdo de la aparición ya no debería participar en la inyección.,
     * pero“puedes ver”con“¿Quieres inyectarte?”Tienen que ser dos cosas.
     * v18: Pasar falso también excluye viejos recuerdos que han sido reemplazados.
     */
    suspend fun getByScope(scope: String, includeSuppressed: Boolean = true): List<Fact> = withContext(Dispatchers.IO) {
        val rows = dao.getByScope(scope)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
        if (includeSuppressed) rows else rows.filterRecallable(scope, null)
    }

    /**
     * v8: Limpiar por alcance —— Eliminar el alcance especificado antes de [cutoffIso] y importance < [minImportance] hecho.
     *
     * Esta es una entrada de limpieza explícita.(Gestión de datos/restablecer escena), no una ruta de atenuación:
     * Atenuación por [applyDecay] Juzgado por la puntuación de durabilidad,Sólo aparecen y no eliminan líneas.;
     * Este método transfiere directamente [cutoffIso] Eliminación definitiva,La persona que llama debe saber claramente que está eliminando datos.
     *
     * @return número real de filas eliminadas
     */
    suspend fun deleteByScopeExceptImportant(scope: String, cutoffIso: String, minImportance: Int): Int = withContext(Dispatchers.IO) {
        dao.deleteByScopeExceptImportant(scope, cutoffIso, minImportance)
    }

    // ── v9: prensa Space(space_id)Consulta/observar/Atenuación ───────────────────────────

    /**
     * v9: Observar la lista de hechos por space_id(Flow forma),Se utiliza para actualizar en tiempo real la interfaz de usuario de la página de memoria.
     */
    fun observeBySpace(spaceId: String): Flow<List<Fact>> =
        dao.observeBySpace(spaceId, java.time.Instant.now().toString()).map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v9: Consulta la lista de hechos de forma sincrónica mediante space_id.
     * Se utiliza para la visualización de la interfaz de usuario de la página de memoria,system prompt Inyección y otros escenarios.
     * v18: [includeSuppressed]=false Al mismo tiempo, se eliminan viejos recuerdos que han sido reemplazados.
     */
    suspend fun getBySpace(spaceId: String, includeSuppressed: Boolean = true): List<Fact> = withContext(Dispatchers.IO) {
        val rows = dao.getBySpace(spaceId)
            .map { it.toDomainFact() }
            .filterNot { it.isExpired() }
        if (includeSuppressed) rows else rows.filterRecallable(null, spaceId)
    }

    /**
     * v9: Lista de hechos de consulta de filtro doble por alcance + space_id.
     * scope Cuarentena por agente,space_id Aislar por escena,Los dos son ortogonales.
     * v18: [includeSuppressed]=false Al mismo tiempo, se eliminan viejos recuerdos que han sido reemplazados.
     */
    suspend fun getByScopeAndSpace(scope: String, spaceId: String, includeSuppressed: Boolean = true): List<Fact> =
        withContext(Dispatchers.IO) {
            val rows = dao.getByScopeAndSpace(scope, spaceId)
                .map { it.toDomainFact() }
                .filterNot { it.isExpired() }
            if (includeSuppressed) rows else rows.filterRecallable(scope, spaceId)
        }

    /**
     * v9: Lista de hechos de observación de doble filtro por alcance + space_id(Flow forma).
     */
    fun observeByScopeAndSpace(scope: String, spaceId: String): Flow<List<Fact>> =
        dao.observeByScopeAndSpace(scope, spaceId, java.time.Instant.now().toString())
            .map { entities -> entities.map { it.toDomainFact() } }

    /**
     * v9: Limpiar por space_id —— Elimine el espacio especificado que sea anterior a [cutoffIso] y importance < [minImportance] hecho.
     *
     * Lo mismo [deleteByScopeExceptImportant]:Borrar entrada explícitamente,No es un camino de atenuación.
     *
     * @return número real de filas eliminadas
     */
    suspend fun deleteBySpaceExceptImportant(spaceId: String, cutoffIso: String, minImportance: Int): Int = withContext(Dispatchers.IO) {
        dao.deleteBySpaceExceptImportant(spaceId, cutoffIso, minImportance)
    }

    /**
     * v9: Cuente el número de hechos en el espacio especificado.
     */
    suspend fun countBySpace(spaceId: String): Int = withContext(Dispatchers.IO) {
        dao.countBySpace(spaceId)
    }

    // ════════════════════════════
    //  conversión interna
    // ════════════════════════════

    /**
     * v1.0.51: Índice FTS de inserción segura — Eliminar primero y luego insertar.,Evite entradas duplicadas de fact_id para tablas FTS4.
     *
     * FTS4 La mesa virtual no tiene restricción única,`INSERT OR REPLACE` Deduplicar solo por rowid y no por fact_id,
     * fusionar/Al actualizar hechos, insertFts directamente generará filas de índice duplicadas,Hace que las búsquedas arrojen resultados duplicados.
     */
    private suspend fun syncFtsRow(factId: Long, contentNgram: String) {
        dao.deleteFts(factId)
        dao.insertFts(factId, contentNgram)
    }

    private fun FactEntity.toDomainFact(): Fact = Fact(
        id = id,
        fact = fact,
        tags = decodeTags(tags),
        time = time,
        sessionId = sessionId,
        createdAt = createdAt,
        importance = importance,
        category = category,
        confidence = confidence,
        source = source,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        lastHitAt = lastHitAt,
        hitCount = hitCount,
        // v8: Transmisión transparente del campo de alcance.
        scope = scope,
        // v9: Transmitir transparentemente el campo spaceId
        spaceId = spaceId,
        // B4-05: Tiempo de transmisión transparente
        pinnedAt = pinnedAt,
        // v12: Clave de normalización de entidad transparente
        entityKey = entityKey,
        // v17: Transmisión transparente de puntuaciones persistentes y marcas de salida.
        score = score,
        suppressedAt = suppressedAt,
        // v18: paso a través“¿Qué viejo recuerdo ha sido reemplazado?”
        supersedesId = supersedesId,
        // v19: paso a través“¿En qué memoria se fusionó?”
        mergedIntoId = mergedIntoId,
    )

    private fun FactTagSearchRow.toDomainFact(): Fact = Fact(
        id = id,
        fact = fact,
        tags = decodeTags(tags),
        time = time,
        sessionId = sessionId,
        createdAt = createdAt,
        importance = importance,
        category = category,
        confidence = confidence,
        source = source,
        expiresAt = expiresAt,
        lastConfirmedAt = lastConfirmedAt,
        lastHitAt = lastHitAt,
        matchCount = matchCount,
        entityKey = entityKey,
        scope = scope,
        spaceId = spaceId,
        score = score,
        suppressedAt = suppressedAt,
    )

    private fun decodeTags(raw: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrElse {
        io.zer0.common.Logger.w("FactStore", "parseTags failed: ${it.message}")
        emptyList()
    }

    /** Rango de fechas. */
    data class DateRange(val from: String? = null, val to: String? = null)
}

/**
 * Plan de búsqueda de etiquetas: construya SQL candidato LIKE y realice coincidencias exactas JSON y ordenación matchCount en la capa Kotlin.
 * Evite confiar en json_each de SQLite (parte del entorno de prueba/No es compatible con versiones anteriores de Android SQLite).
 */
private class TagSearchPlan(
    private val queryTags: List<String>,
    private val dateRange: FactStore.DateRange?,
    private val limit: Int,
    private val scope: String? = null,
    private val spaceId: String? = null,
) {

    val sql: String = buildString {
        val likeClauses = queryTags.joinToString(" OR ") { "tags LIKE ?" }
        append("SELECT *, 0 as matchCount FROM facts WHERE (").append(likeClauses).append(")")
        if (dateRange?.from != null) append(" AND time >= ?")
        if (dateRange?.to != null) append(" AND time <= ?")
        if (scope != null) append(" AND scope = ?")
        if (spaceId != null) append(" AND space_id = ?")
        append(" ORDER BY importance DESC, time DESC LIMIT ?")
    }

    val args: Array<Any> = buildList {
        queryTags.forEach { add("%\"$it\"%") }
        dateRange?.from?.let { add(it) }
        dateRange?.to?.let { add(it) }
        scope?.let { add(it) }
        spaceId?.let { add(it) }
        add(limit * 2) // candidata a dora,Algunos pueden descartarse después del filtrado de memoria.
    }.toTypedArray()

    fun refine(rows: List<FactTagSearchRow>): List<FactStore.Fact> {
        val tagSet = queryTags.toSet()
        return rows.mapNotNull { row ->
            val tags = runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<String>>(row.tags)
            }.getOrDefault(emptyList())
            val matchCount = tags.count { it in tagSet }
            if (matchCount > 0) {
                FactStore.Fact(
                    id = row.id,
                    fact = row.fact,
                    tags = tags,
                    time = row.time,
                    sessionId = row.sessionId,
                    createdAt = row.createdAt,
                    importance = row.importance,
                    category = row.category,
                    confidence = row.confidence,
                    source = row.source,
                    expiresAt = row.expiresAt,
                    lastConfirmedAt = row.lastConfirmedAt,
                    lastHitAt = row.lastHitAt,
                    entityKey = row.entityKey,
                    score = row.score,
                    suppressedAt = row.suppressedAt,
                    matchCount = matchCount,
                )
            } else {
                null
            }
        }.sortedWith(
            compareByDescending<FactStore.Fact> { it.importance }
                .thenByDescending { it.matchCount }
                .thenByDescending { it.time },
        ).take(limit)
    }
}
