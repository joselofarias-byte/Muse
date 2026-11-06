package io.zer0.muse.worldbook

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * P1-2: Worldbook depósito.
 *
 * Responsable de la consulta de concordancia de palabras clave CRUD + + SillyTavern World Info JSON Importar y exportar.
 *
 * Palabra clave que coincide con dos modos:
 *  - isRegex=false: coincidencia de subcadenas(contains),mayúsculas y minúsculas por [WorldBookEntryEntity.caseSensitive] controlar
 *  - isRegex=true:  Partido normal,Palabras clave como cadenas de origen normales;caseSensitive controlar RegexOption.IGNORE_CASE
 *
 * Los resultados de la compilación regular se almacenan en caché en [regexCache],Evite la compilación repetida para cada escaneo(La sobrecarga de compilación regular es mucho mayor que la coincidencia de subcadenas).
 */
class WorldBookRepository(
    private val dao: WorldBookDao,
) {
    fun observeAll(): Flow<List<WorldBookEntryEntity>> = dao.observeAll()

    fun observeEnabled(): Flow<List<WorldBookEntryEntity>> = dao.observeEnabled()

    suspend fun getEnabled(): List<WorldBookEntryEntity> = dao.getEnabled()

    suspend fun getAlwaysActive(assistantId: String?): List<WorldBookEntryEntity> = dao.getAlwaysActive(assistantId)

    suspend fun getById(id: String): WorldBookEntryEntity? = dao.getById(id)

    suspend fun upsert(entity: WorldBookEntryEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * Coincide con las entradas del Worldbook en el texto dado.
     * Cualquier acierto de palabra clave se considera una coincidencia.;Devolver todas las entradas coincidentes(prensa priority DESC, name ASC).
     *
     * @param entries Entrada de candidato(debe ser filtrado enabled)
     * @param text Texto a coincidir
     */
    fun matchAgainst(entries: List<WorldBookEntryEntity>, text: String): List<WorldBookEntryEntity> {
        if (entries.isEmpty() || text.isBlank()) return emptyList()
        return entries
            .filter { it.enabled && !it.alwaysActive } // Las entradas de residentes son manejadas por la ruta siempre activa
            .filter { entry ->
                val keywords = parseKeywords(entry.keywordsJson)
                if (keywords.isEmpty()) return@filter false
                keywords.any { kw ->
                    if (kw.isBlank()) {
                        false
                    } else {
                        matchKeyword(text, kw, entry.caseSensitive, entry.isRegex, entry.wholeWord)
                    }
                }
            }
            .sortedWith(compareByDescending<WorldBookEntryEntity> { it.priority }.thenBy { it.name })
    }

    /**
     * Escanear reciente [scanDepth] Mensaje de USUARIO de capa,Devuelve la entrada de hit.
     * scanDepth Controlado por cada entrada.;Aquí, la profundidad de escaneo máxima de todas las entradas candidatas se toma como ventana de escaneo.,
     * Luego, solo escanee los mensajes dentro de su propio rango de profundidad de escaneo para cada entrada.
     *
     * @param userMessages Todos los mensajes de USUARIO(en orden cronológico)
     * @param assistantId asistente actual id(Se utiliza para filtrar entradas vinculantes.)
     * @return lista de resultados(Ordenado, deduplicado)
     */
    suspend fun getKeywordEntries(userMessages: List<UIMessage>, assistantId: String?): List<WorldBookEntryEntity> {
        val candidates = dao.getEnabled()
            .filter { it.alwaysActive.not() } // La residencia se maneja mediante la ruta siempre activa.
            .filter { it.assistantId == null || it.assistantId == assistantId }
        if (candidates.isEmpty() || userMessages.isEmpty()) return emptyList()

        val matched = LinkedHashMap<String, WorldBookEntryEntity>()
        for (entry in candidates) {
            val depth = entry.scanDepth.coerceAtLeast(1)
            val window = userMessages.takeLast(depth)
            val hit = window.any { msg -> matchAgainst(listOf(entry), msg.content).isNotEmpty() }
            if (hit) matched[entry.id] = entry
        }
        return matched.values
            .sortedWith(compareByDescending<WorldBookEntryEntity> { it.priority }.thenBy { it.name })
    }

    /**
     * Lógica central de coincidencia de palabras clave.
     *
     * patrón regular:Palabras clave como cadenas de origen normales,caseSensitive=false Agregue IGNORE_CASE cuando sea necesario.
     * Registrar y regresar cuando falla la compilación regular false(Evite que una sola regla incorrecta bloquee todo el análisis).
     * Prensa regular de resultados de compilación "cadena fuente|ignoreCase" Cache.
     */
    private fun matchKeyword(text: String, keyword: String, caseSensitive: Boolean, isRegex: Boolean, wholeWord: Boolean): Boolean {
        return if (isRegex) {
            val cacheKey = "$keyword|$caseSensitive"
            val pattern = regexCache.computeIfAbsent(cacheKey) {
                runCatching {
                    val opts = if (caseSensitive) emptySet() else setOf(kotlin.text.RegexOption.IGNORE_CASE)
                    Regex(keyword, opts)
                }.getOrElse { e ->
                    Logger.w(TAG, "La compilación regular falló: ${e.message}, keyword=$keyword")
                    null
                }
            } ?: return false
            pattern.containsMatchIn(text)
        } else if (wholeWord && keyword.none { it.code > 0x2E80 }) {
            // v2.x: coincidencia de palabras completas(ingles/escena digital,Las palabras clave antes y después son \b frontera);
            // Las palabras clave que contienen CJK no tienen concepto de límite de palabras,Degenera automáticamente a la subcadena que coincide a continuación.
            val cacheKey = "ww|$keyword|$caseSensitive"
            val pattern = regexCache.computeIfAbsent(cacheKey) {
                runCatching {
                    val opts = if (caseSensitive) emptySet() else setOf(kotlin.text.RegexOption.IGNORE_CASE)
                    Regex("\\b" + Regex.escape(keyword) + "\\b", opts)
                }.getOrElse { e ->
                    Logger.w(TAG, "Falló la compilación regular de palabras completas: ${e.message}, keyword=$keyword")
                    null
                }
            } ?: return false
            pattern.containsMatchIn(text)
        } else {
            if (caseSensitive) {
                text.contains(keyword)
            } else {
                text.contains(keyword, ignoreCase = true)
            }
        }
    }

    // ── SillyTavern World Info JSON Importar y exportar ──

    /**
     * Exportar al formato JSON de SillyTavern World Info(Compatible con la importación de SillyTavern).
     *
     * Mapeo de campo(Muse → SillyTavern):
     *  - name → comment
     *  - keywordsJson → key (matriz)
     *  - content → content
     *  - alwaysActive → constant
     *  - priority → order
     *  - enabled → disable (negar)
     *  - caseSensitive → caseSensitive
     *  - scanDepth → scanDepth
     *  - injectTarget → role (0=system/1=user/2=assistant)
     *  - injectPosition → position (0=before/1=after/4=at_depth)
     *  - insertionDepth → depth
     */
    suspend fun exportSillyTavernJson(): String {
        val entries = dao.getAll()
        val entriesObj = buildJsonObject {
            entries.forEachIndexed { index, entry ->
                put(index.toString(), buildSillyTavernEntry(entry, index))
            }
        }
        val root = buildJsonObject {
            put("entries", entriesObj)
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Importar desde SillyTavern World Info JSON.
     * Se sobrescribirán las entradas existentes con la misma identificación.(id regenerarse en nuevo UUID,Evite conflictos con los existentes.).
     *
     * @return Número de artículos importados correctamente
     */
    suspend fun importSillyTavernJson(jsonText: String): Int {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }
            .getOrElse { e ->
                Logger.w(TAG, "SillyTavern JSON El análisis falló: ${e.message}")
                return 0
            }
        val entriesObj = root["entries"]?.jsonObject ?: return 0
        val toInsert = mutableListOf<WorldBookEntryEntity>()
        val now = System.currentTimeMillis()
        for ((_, value) in entriesObj) {
            val obj = value.jsonObject
            val entry = parseSillyTavernEntry(obj, now) ?: continue
            toInsert.add(entry)
        }
        if (toInsert.isEmpty()) return 0
        dao.insertAll(toInsert)
        Logger.i(TAG, "SillyTavern Importación completada: ${toInsert.size} Artículo")
        return toInsert.size
    }

    private fun buildSillyTavernEntry(entry: WorldBookEntryEntity, uid: Int): JsonObject = buildJsonObject {
        put("uid", uid)
        val keys = parseKeywords(entry.keywordsJson)
        put("key", JsonArray(keys.map { JsonPrimitive(it) }))
        put("keysecondary", JsonArray(emptyList()))
        put("comment", entry.name)
        put("content", entry.content)
        put("constant", entry.alwaysActive)
        put("vectorized", false)
        put("selective", true)
        put("selectiveLogic", 0)
        put("addMemo", false)
        put("order", entry.priority)
        put(
            "position",
            when (WorldBookInjectPosition.fromStorage(entry.injectPosition)) {
                WorldBookInjectPosition.PREPEND -> 0
                WorldBookInjectPosition.APPEND -> 1
                WorldBookInjectPosition.AT_DEPTH -> 4
            },
        )
        put("disable", !entry.enabled)
        put("excludeRecursion", false)
        put("preventRecursion", false)
        put("delayUntilRecursion", false)
        put("probability", 100)
        put("useProbability", true)
        put("title", entry.name)
        put(
            "role",
            when (WorldBookInjectTarget.fromStorage(entry.injectTarget)) {
                WorldBookInjectTarget.SYSTEM -> 0
                WorldBookInjectTarget.USER -> 1
                WorldBookInjectTarget.ASSISTANT -> 2
            },
        )
        put("depth", entry.insertionDepth)
        put("group", "")
        put("groupOverride", false)
        put("groupWeight", 100)
        put("scanDepth", entry.scanDepth)
        put("caseSensitive", entry.caseSensitive)
        put("matchWholeWords", null)
        put("automationId", "")
        put("displayIndex", uid)
    }

    private fun parseSillyTavernEntry(obj: JsonObject, now: Long): WorldBookEntryEntity? {
        return runCatching {
            val keys = obj["key"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            val comment = (obj["comment"] as? JsonPrimitive)?.contentOrNull
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            val name = comment?.ifBlank { title } ?: title ?: "Sin nombre"
            val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
            val alwaysActive = (obj["constant"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val priority = (obj["order"] as? JsonPrimitive)?.intOrNull ?: 50
            val disabled = (obj["disable"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val caseSensitive = (obj["caseSensitive"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val scanDepth = (obj["scanDepth"] as? JsonPrimitive)?.intOrNull ?: 3
            val role = (obj["role"] as? JsonPrimitive)?.intOrNull ?: 0
            val position = (obj["position"] as? JsonPrimitive)?.intOrNull ?: 1
            val depth = (obj["depth"] as? JsonPrimitive)?.intOrNull ?: 0
            WorldBookEntryEntity(
                id = "wb-${UUID.randomUUID()}",
                name = name,
                keywordsJson = encodeKeywords(keys),
                content = content,
                priority = priority,
                enabled = !disabled,
                caseSensitive = caseSensitive,
                isRegex = false,
                alwaysActive = alwaysActive,
                scanDepth = scanDepth,
                injectTarget = when (role) {
                    1 -> WorldBookInjectTarget.USER.storage
                    2 -> WorldBookInjectTarget.ASSISTANT.storage
                    else -> WorldBookInjectTarget.SYSTEM.storage
                },
                injectPosition = when (position) {
                    0 -> WorldBookInjectPosition.PREPEND.storage
                    4 -> WorldBookInjectPosition.AT_DEPTH.storage
                    else -> WorldBookInjectPosition.APPEND.storage
                },
                insertionDepth = depth,
                assistantId = null,
                createdAt = now,
                updatedAt = now,
            )
        }.getOrElse { e ->
            Logger.w(TAG, "SillyTavern Error al analizar la entrada: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WorldBookRepository"

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        private val stringListSerializer = ListSerializer(String.serializer())

        /** Caché de compilación regular:key = "cadena fuente|caseSensitive". */
        private val regexCache = ConcurrentHashMap<String, Regex?>()

        /** keywordsJson Analizar caché. */
        private val keywordsCache = ConcurrentHashMap<String, List<String>>()

        fun parseKeywords(keywordsJson: String): List<String> = keywordsCache.computeIfAbsent(keywordsJson) {
            runCatching { json.decodeFromString(stringListSerializer, it) }
                .getOrElse { e ->
                    Logger.w(TAG, "keywordsJson El análisis falló: ${e.message}, raw=$keywordsJson")
                    emptyList()
                }
        }

        fun encodeKeywords(keywords: List<String>): String = runCatching { json.encodeToString(stringListSerializer, keywords) }
            .getOrDefault("[]")
    }
}
