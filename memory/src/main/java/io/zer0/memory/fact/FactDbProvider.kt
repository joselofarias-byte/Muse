package io.zer0.memory.fact

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-assistant FactDb proveedor (v0.22: Cada asistente es independiente. facts.db).
 *
 * Crear por ID de asistente/Almacene en caché una instancia de FactDb separada:
 *  - assistantId está vacío o "default" → facts.db(Compatible con datos antiguos)
 *  - assistantId = "abc123" → facts_abc123.db
 *
 * Seguridad de subprocesos: utilice ConcurrentHashMap.computeIfAbsent para garantizar que cada ID de asistente solo se cree una vez.
 * FactDb El modo WAL de Room se ha utilizado internamente, lo que hace que la lectura simultánea de múltiples rutinas sea segura.
 *
 * v1.78: Nuevo [release] método,Suelte el identificador de base de datos cuando el asistente elimine,Evite fugas de fd después de un uso prolongado.
 */
class FactDbProvider(
    private val context: Context,
    /** v12: LLM Determinante de deduplicación,Pasado transparentemente a cada uno. per-assistant FactStore(Los candidatos difusos de la misma entidad se entregan al modelo grande para su evaluación.). */
    private val dedupJudge: FactDedupJudge = NoopFactDedupJudge,
) {
    private val cache = ConcurrentHashMap<String, FactDb>()
    private val storeCache = ConcurrentHashMap<String, FactStore>()

    /** Obtenga la FactDb del asistente especificado.assistantId Regresar a cuando esté vacío "default". */
    fun getFactDb(assistantId: String): FactDb {
        val key = if (assistantId.isBlank()) "default" else assistantId
        return cache.computeIfAbsent(key) { id ->
            val dbName = if (id == "default") "facts.db" else "facts_$id.db"
            FactDb.create(context, dbName)
        }
    }

    /** Obtenga FactStore para el asistente especificado (método conveniente). */
    fun getFactStore(assistantId: String): FactStore {
        val key = if (assistantId.isBlank()) "default" else assistantId
        return storeCache.computeIfAbsent(key) {
            // v1.0.27 P0-1.3: FactStore Ahora se requiere una instancia de FactDb para admitir transacciones addBatch
            val db = getFactDb(key)
            // v15: new deletion tombstones are stored atomically in this assistant's FactDb.
            // Keep the old shared JSON file read-only as a fail-closed upgrade fallback; active
            // facts in this exact scope/space exempt legacy text markers from suppressing re-added duplicates.
            // v12: Determinador de deduplicación LLM transparente.
            // v13 (T4-1): El registro de revisión DAO se obtiene directamente de la base de datos(No inyectado,Evite las dependencias circulares de Koin:
            //   FactDbProvider → FactRevisionDao → FactDb → FactDbProvider).
            FactStore(
                db.factDao(),
                db,
                java.io.File(context.filesDir, "fact_tombstones.json"),
                dedupJudge,
                db.factRevisionDao(),
                assistantId = key,
                clearLegacyTombstonesOnReset = key == "default",
            )
        }
    }

    /**
     * v1.78: Libera el identificador de FactDb del asistente especificado y lo elimina de la caché.
     *
     * Llamado cuando se elimina el asistente.,Evite ocupar permanentemente la conexión db de un asistente eliminado(SQLite identificador de archivo + WAL).
     * Nota:Si nuevamente después de llamar getFactDb(Lo mismo id) Se reconstruirá una nueva instancia.
     */
    fun release(assistantId: String) {
        val key = if (assistantId.isBlank()) "default" else assistantId
        storeCache.remove(key)
        cache.remove(key)?.close()
    }

    /** v1.78: Liberar todo lo almacenado en caché FactDb(Para salir de la aplicación o restablecer la memoria). */
    fun releaseAll() {
        storeCache.clear()
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }

    /**
     * Ya existe un asistente de subbiblioteca en el disco. id(`facts_<id>.db`),No incluye biblioteca principal `facts.db`.
     *
     * sesión/El asistente elimina para recorrer todos los subrepositorios,Pero sólo deberían abrirse las bibliotecas realmente existentes:El asistente eliminado ya no tiene archivos,
     * Adivinar la identificación creará una base de datos vacía de la nada.(y permanecer en el disco permanentemente).
     */
    fun listAssistantDbIds(): List<String> {
        val dir = context.getDatabasePath("facts.db").parentFile ?: return emptyList()
        return dir.listFiles()
            .orEmpty()
            .asSequence()
            .map { it.name }
            .filter { it.startsWith(PER_ASSISTANT_DB_PREFIX) && it.endsWith(PER_ASSISTANT_DB_SUFFIX) }
            .map { it.removePrefix(PER_ASSISTANT_DB_PREFIX).removeSuffix(PER_ASSISTANT_DB_SUFFIX) }
            .filter { it.isNotBlank() && it != DEFAULT_ASSISTANT_KEY }
            .distinct()
            .sorted()
            .toList()
    }

    private companion object {
        /** Nombre de la biblioteca principal y sufijo del nombre de la subbiblioteca por asistente(con [getFactDb] Las reglas de nomenclatura son consistentes con). */
        const val PER_ASSISTANT_DB_PREFIX = "facts_"
        const val PER_ASSISTANT_DB_SUFFIX = ".db"

        /** Caché correspondiente a la biblioteca principal key,facts.db no irse `facts_<id>.db` Nombramiento. */
        const val DEFAULT_ASSISTANT_KEY = "default"
    }
}
