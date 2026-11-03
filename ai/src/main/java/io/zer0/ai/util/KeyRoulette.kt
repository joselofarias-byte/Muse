package io.zer0.ai.util

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * API Key Rotador.
 *
 * Admite múltiples configuraciones por proveedor API key(Separados por coma o nueva línea), distribuya la carga según los registros de uso recientes:
 * Se da prioridad a las claves no utilizadas, y cuando se han utilizado todas, se selecciona la clave que lleva más tiempo sin utilizarse.
 *
 * v1.0.1 Mejora:
 *  - [pickNext]: 429 Cambie activamente al siguiente cuando la corriente sea limitada key(saltar actual key)
 *  - Lista negra temporal: las claves omitidas por pickNext están en [BLOCK_TTL_MS] Ya no hay una prioridad dentro,
 *    Evite que las teclas que limitan la corriente se seleccionen repetidamente;TTL Reanudar candidatos automáticamente después del vencimiento
 *  - [markFailed]: No se puede marcar explícitamente una clave(Como la autenticación 401 falló),TTL completamente excluido dentro
 *
 * Uso:
 * ```kotlin
 * val keyRoulette = KeyRoulette()
 * // Selección por primera vez key
 * val key = keyRoulette.pick("provider-123", "key1,key2,key3")
 * // 429 Cambie al siguiente cuando la corriente sea limitada key
 * val nextKey = keyRoulette.pickNext("provider-123", "key1,key2,key3", currentKey = key)
 * ```
 */
class KeyRoulette {

    companion object {
        private const val TAG = "KeyRoulette"
        private const val MAX_RECORDS_PER_PROVIDER = 100
        private const val USAGE_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 horas

        /** v1.0.1: Lista negra temporal de claves limitantes actuales TTL(60 segundos,Consistente con la mayoría de las recomendaciones de reintento posterior del proveedor). */
        private const val BLOCK_TTL_MS = 60 * 1000L
    }

    private data class UsageRecord(
        val key: String,
        val lastUsedAt: Long,
    )

    /** v1.0.1: entrada de lista negra(key → Marca de tiempo de vencimiento). */
    private data class BlockRecord(
        val key: String,
        val expiresAt: Long,
        /** Ya sea para excluir completamente(true=completamente excluido,Como por ejemplo 401;false=Disminuir prioridad,Como por ejemplo 429). */
        val hardBlock: Boolean,
    )

    /** providerId → Registros de uso recientes (los más antiguos primero) */
    private val usage = ConcurrentHashMap<String, MutableList<UsageRecord>>()

    /** v1.0.1: providerId → entrada de lista negra */
    private val blocks = ConcurrentHashMap<String, MutableList<BlockRecord>>()

    /**
     * desde coma/Seleccione una clave de la cadena de claves delimitada por nueva línea.
     * Priorice las claves que no se hayan utilizado recientemente.
     *
     * @param providerId Provider identificador único
     * @param keysString separados por coma o nueva línea API key
     * @return La clave seleccionada; si solo hay una clave, se devuelve la cadena original
     */
    fun pick(providerId: String, keysString: String): String {
        val keys = parseKeys(keysString)
        if (keys.size <= 1) return keysString.trim()

        val now = System.currentTimeMillis()
        val records = usage.getOrPut(providerId) { mutableListOf() }
        val blocksForProvider = blocks.getOrPut(providerId) { mutableListOf() }

        // Borrar lista negra caducada
        synchronized(blocksForProvider) {
            blocksForProvider.removeAll { it.expiresAt < now }
        }

        // Priorizar a aquellos que no están en la lista negra dura key
        val hardBlocked = blocksForProvider.filter { it.hardBlock }.map { it.key }.toSet()
        val candidates = keys.filter { it !in hardBlocked }

        // Todas las claves están en la lista negra dura. → Degenera en devolver el primero.(Deje que el proveedor informe los errores por sí solo)
        if (candidates.isEmpty()) {
            Logger.w(TAG, "pick: all keys hard-blocked for $providerId, returning first key")
            return keys.first()
        }

        val selected = selectKey(candidates, records, now, blocksForProvider)
        recordUsage(records, selected, now)
        return selected
    }

    /**
     * v1.0.1: 429 Cambie activamente a la siguiente tecla cuando la corriente sea limitada.
     *
     * poner [currentKey] Únase a la lista negra suave(60s Menor prioridad internamente),Luego elige la siguiente clave.
     * si solo hay uno key,Volver al original key(No se puede cambiar,Utilice un retroceso exponencial por proveedor).
     *
     * @param providerId Provider logotipo
     * @param keysString Todas las cadenas clave
     * @param currentKey Activando actualmente 429 key
     * @return siguiente key(Puede ser el mismo que currentKey,si solo hay uno key)
     */
    fun pickNext(providerId: String, keysString: String, currentKey: String): String {
        val keys = parseKeys(keysString)
        if (keys.size <= 1) return currentKey

        val now = System.currentTimeMillis()
        val blocksForProvider = blocks.getOrPut(providerId) { mutableListOf() }

        // Agregue la clave actual a la lista negra suave(60s)
        synchronized(blocksForProvider) {
            blocksForProvider.removeAll { it.key == currentKey }
            blocksForProvider.add(
                BlockRecord(
                    key = currentKey,
                    expiresAt = now + BLOCK_TTL_MS,
                    hardBlock = false,
                ),
            )
            // Evitar la expansión de la lista negra
            while (blocksForProvider.size > MAX_RECORDS_PER_PROVIDER) blocksForProvider.removeAt(0)
        }

        Logger.i(TAG, "pickNext: 429 Limitación de corriente,poner key=${maskKey(currentKey)} Únase a la lista negra suave 60s,cambiar al siguiente key")

        // re pick(Se saltará la lista negra suave key,A menos que no haya otra opción)
        return pick(providerId, keysString)
    }

    /**
     * v1.0.1: No se puede marcar explícitamente una clave(Como falla de autenticación 401,key Caducado).
     *
     * con [pickNext] la diferencia:
     *  - [markFailed] Predeterminado hardBlock=true,excluir completamente esto key(hasta que expire TTL)
     *  - [pickNext] Es una lista negra suave,Sólo menor prioridad cuando hay varias claves
     *
     * @param providerId Provider logotipo
     * @param key falló key
     * @param hardBlock true=completamente excluido(Predeterminado,Como por ejemplo 401),false=Disminuir prioridad(Como por ejemplo 429)
     * @param ttlMs lista negra TTL,Predeterminado 5 minutos(hardBlock Escena más larga que 429)
     */
    fun markFailed(providerId: String, key: String, hardBlock: Boolean = true, ttlMs: Long = 5 * 60 * 1000L) {
        val blocksForProvider = blocks.getOrPut(providerId) { mutableListOf() }
        val now = System.currentTimeMillis()
        synchronized(blocksForProvider) {
            blocksForProvider.removeAll { it.key == key }
            blocksForProvider.add(
                BlockRecord(
                    key = key,
                    expiresAt = now + ttlMs,
                    hardBlock = hardBlock,
                ),
            )
            while (blocksForProvider.size > MAX_RECORDS_PER_PROVIDER) blocksForProvider.removeAt(0)
        }
        Logger.w(TAG, "markFailed: key=${maskKey(key)} Marcar como fallido(hardBlock=$hardBlock, ttl=${ttlMs}ms)")
    }

    /** Borre todos los datos de uso de claves almacenados en caché. */
    fun clear() {
        usage.clear()
        blocks.clear()
    }

    /**
     * v1.0.1: Borrar la lista negra del proveedor especificado(usado para"Inténtalo de nuevo"Después de cambios de botón o configuración).
     */
    fun clearBlacklist(providerId: String) {
        blocks.remove(providerId)
    }

    // ---------- método interno ----------

    private fun parseKeys(keysString: String): List<String> {
        return keysString.split(",", "\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun selectKey(
        candidates: List<String>,
        records: MutableList<UsageRecord>,
        now: Long,
        blocksForProvider: MutableList<BlockRecord>,
    ): String {
        // Disminuir la prioridad de las claves en la lista negra suave(Poner la última opción)
        val softBlocked = blocksForProvider.filter { !it.hardBlock }.map { it.key }.toSet()
        val unblocked = candidates.filter { it !in softBlocked }

        if (unblocked.isNotEmpty()) {
            return lruPick(unblocked, records, now)
        }

        // Todos los candidatos están en la lista negra blanda(raro,Por ejemplo, todas las claves acaban de ser restringidas.),degenerar en LRU
        return lruPick(candidates, records, now)
    }

    private fun lruPick(candidates: List<String>, records: MutableList<UsageRecord>, now: Long): String {
        // Reparación de auditoría (3.1): Leer operación y registrar uso synchronized(records) mutuamente excluyentes,
        // Evite ser arrojado por eliminar todo/agregar modificación estructural durante el recorrido durante solicitudes concurrentes. ConcurrentModificationException
        val usedKeys = synchronized(records) {
            records
                .filter { now - it.lastUsedAt < USAGE_EXPIRY_MS }
                .map { it.key }
                .toSet()
        }

        val unusedKeys = candidates.filter { it !in usedKeys }
        return if (unusedKeys.isNotEmpty()) {
            unusedKeys.random()
        } else {
            // Todas las claves se han utilizado recientemente y entre las candidatas se selecciona la que lleva más tiempo sin utilizarse.
            val oldest = synchronized(records) {
                records.filter { it.key in candidates }.minByOrNull { it.lastUsedAt }
            }
            oldest?.key?.takeIf { it in candidates } ?: candidates.random()
        }
    }

    private fun recordUsage(records: MutableList<UsageRecord>, selected: String, now: Long) {
        synchronized(records) {
            records.removeAll { it.key == selected }
            records.add(UsageRecord(selected, now))
            while (records.size > MAX_RECORDS_PER_PROVIDER) {
                records.removeAt(0)
            }
        }
    }

    private fun maskKey(key: String): String {
        return when {
            key.length <= 12 && key.isNotEmpty() -> "****" + key.takeLast(2)
            key.length > 12 -> key.take(4) + "***" + key.takeLast(4)
            else -> ""
        }
    }
}
