package io.zer0.muse.data.lorebook

import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Lorebook almacén — Phase 8.5.
 *
 * Responsable de las consultas de concordancia de palabras clave CRUD + de Lorebook.
 * Lógica de concordancia de palabras clave([matchAgainst])Llamado por LorebookTransformer.
 */
class LorebookRepository(
    private val dao: LorebookDao,
) {
    /** observar todo Lorebook(Para la página de gestión). */
    fun observeAll(): Flow<List<LorebookEntity>> = dao.observeAll()

    /** Observación habilitada Lorebook(Para inyector). */
    fun observeEnabled(): Flow<List<LorebookEntity>> = dao.observeEnabled()

    /**
     * Obtener entrada habilitada por id(Assistant atado).
     * L-DAO1: Consulta por lotes(DAO_BATCH_SIZE por lote id),Evite listas de identificadores demasiado grandes que generen demasiados parámetros SQL.
     */
    suspend fun getByIdsEnabled(ids: List<String>): List<LorebookEntity> {
        if (ids.isEmpty()) return emptyList()
        val uniqueIds = ids.distinct()
        val result = mutableListOf<LorebookEntity>()
        for (batch in uniqueIds.chunked(DAO_BATCH_SIZE)) {
            result.addAll(dao.getByIdsEnabled(batch))
        }
        // Reordenar después de una consulta por lotes,Manténgase consistente con DAO priority DESC, name ASC
        return result.sortedWith(compareByDescending<LorebookEntity> { it.priority }.thenBy { it.name })
    }

    suspend fun getById(id: String): LorebookEntity? = dao.getById(id)

    suspend fun upsert(entity: LorebookEntity) = dao.upsert(entity)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * Une las entradas del Lorebook en el texto dado.
     * Cualquier acierto de palabra clave se considera una coincidencia.;Devuelve todas las entradas coincidentes.
     *
     * método de emparejamiento:
     *  - wholeWord=false(Predeterminado): contains coincidencia de subcadenas,mayúsculas y minúsculas por [LorebookEntity.caseSensitive] controlar
     *  - wholeWord=true(v1.0.47): coincidencia de palabras completas,Las palabras clave deben ir precedidas y seguidas de límites de palabras.(\b),Reducir los desencadenantes falsos
     *    (Por ejemplo palabras clave"cat"no golpeará"category")
     * M-LB2: Ordenar explícitamente por prioridad descendente + nombre ascendente.
     * L-LB10: Filtrado defensivo de entradas deshabilitadas(Normalmente la persona que llama ha sido filtrada.).
     *
     * @param entries Entrada al libro de historia del candidato
     * @param text Texto a coincidir(Generalmente el mensaje de usuario más reciente.)
     * @return lista de éxitos(Ordenar por prioridad descendente + nombre ascendente)
     */
    fun matchAgainst(entries: List<LorebookEntity>, text: String): List<LorebookEntity> {
        if (entries.isEmpty() || text.isBlank()) return emptyList()
        return entries
            .filter { it.enabled } // L-LB10: filtrado defensivo
            .filter { entry ->
                val keywords = parseKeywords(entry.keywordsJson)
                if (keywords.isEmpty()) return@filter false
                keywords.any { kw ->
                    if (kw.isBlank()) {
                        false
                    } else {
                        matchKeyword(text, kw, entry.caseSensitive, entry.wholeWord)
                    }
                }
            }
            .sortedWith(compareByDescending<LorebookEntity> { it.priority }.thenBy { it.name }) // M-LB2
    }

    /**
     * v1.0.47: Lógica central de coincidencia de palabras clave,Admite modos de coincidencia de subcadenas y de palabras completas.
     *
     * Utilice la concordancia de palabras completas Regex \b límites de palabras:
     *  - \b definido como carácter de palabra([A-Za-z0-9_])entre caracteres que no son palabras
     *  - Los metacaracteres regulares en las palabras clave se escapan usando Regex.escape,Prevenir la inyección
     *  - mayúsculas y minúsculas por [caseSensitive] controlar(ignoreCase = !caseSensitive)
     */
    private fun matchKeyword(text: String, keyword: String, caseSensitive: Boolean, wholeWord: Boolean): Boolean {
        return if (wholeWord) {
            // coincidencia de palabras completas:\b escapar de palabras clave\b,Se escapan los metacaracteres regulares.
            val pattern = Regex("\\b" + Regex.escape(keyword) + "\\b", if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE))
            pattern.containsMatchIn(text)
        } else {
            // coincidencia de subcadenas(compatible con versiones anteriores)
            if (caseSensitive) {
                text.contains(keyword)
            } else {
                text.contains(keyword, ignoreCase = true)
            }
        }
    }

    companion object {
        // L-DAO1: DAO Consultar el número máximo de ID en cada lote en lotes
        const val DAO_BATCH_SIZE = 500

        private val json = Json { ignoreUnknownKeys = true }
        private val stringListSerializer = ListSerializer(String.serializer())

        // L-LB5: keywordsJson analizar caché,Evite el análisis repetido cada vez que coincida con Contra
        private val keywordsCache = ConcurrentHashMap<String, List<String>>()

        /**
         * Analizar palabras clave Json como List<String>.
         * L-LB5: Resultados del análisis de caché utilizando palabras clave Cadena Json como clave,Evite el análisis repetido.
         * L-DAO2: Registros de Logger.w cuando falla el análisis(en lugar de regresar en silencio emptyList).
         */
        fun parseKeywords(keywordsJson: String): List<String> = keywordsCache.computeIfAbsent(keywordsJson) {
            runCatching { json.decodeFromString(stringListSerializer, it) }
                .getOrElse { e ->
                    Logger.w("LorebookRepository", "keywordsJson El análisis falló: ${e.message}, Regresar emptyList (raw=$keywordsJson)")
                    emptyList()
                }
        }

        /** codificación List<String> para palabras claveJson. */
        fun encodeKeywords(keywords: List<String>): String = runCatching { json.encodeToString(stringListSerializer, keywords) }
            .getOrDefault("[]")
    }
}
