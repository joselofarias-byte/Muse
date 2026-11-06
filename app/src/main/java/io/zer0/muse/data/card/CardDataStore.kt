package io.zer0.muse.data.card

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.MessageDigest

/**
 * v2.0: Almacenamiento de datos de la tarjeta — Enlace de datos de tarjeta(cardId → JSON datos).
 *
 * llamada de herramienta(show_card / update_card_data)Escribir datos estructurados;
 * El lado de representación de la tarjeta lee mediante la adquisición de datos una sola vez.:
 * Llamada de guión de tarjeta `window.muse.getData(cardId)` → [MuseCardBridge] → Este almacenamiento.
 *
 * ## Formulario de almacenamiento(2026-11-01 Mejoras de rendimiento)
 *
 * La forma antigua es de un solo archivo y volumen completo. `filesDir/card_data.json`: cada vez `put`/`remove` Todos**mesa entera**
 * (hasta [MAX_ITEMS] Número máximo de tiras y tiras individuales [MAX_DATA_LENGTH] char) reserialización + escritura atómica + fsync.
 * Cuando el modelo actualiza continuamente la tarjeta (`update_card_data` Llamado varias veces), cada vez que se reescriben los datos de otras tarjetas,
 * El tamaño de una única ubicación puede alcanzar varios MB.
 *
 * El formulario actual está fragmentado por tarjeta:`filesDir/card_data/<cardId de sha256>.json`,
 * Cada archivo contiene solo un dato de tarjeta, en forma de `{"cardId":"...","data":"..."}`.
 * Entonces la cantidad de escrituras por tiempo = El tamaño de esta tarjeta en sí (≤ [MAX_DATA_LENGTH]), independientemente de cuántas tarjetas haya en la biblioteca.
 *
 * Los archivos antiguos siguen siendo compatibles: el directorio de fragmentos está vacío y es antiguo `card_data.json` Cuando exista, lea el archivo antiguo. → escribe fragmento →
 * Cambiar el nombre de archivos antiguos `.migrated`. Los archivos antiguos todavía están en la lista blanca de respaldo y la máquina que no pudo migrar no perderá datos.
 */
class CardDataStore(context: Context) {

    private val filesDir: File = context.filesDir
    private val legacyFile = File(filesDir, LEGACY_FILE_NAME)
    private val shardDir = File(filesDir, SHARD_DIR_NAME)

    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private val _data = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Restaure el cardId eliminado en la ventana (P28 Parche después de introducir la recuperación asincrónica).
     *
     * La recuperación asincrónica consiste en "primero leer el disco para obtener la instantánea y luego fusionarlo en la memoria". Si el usuario elimina una tarjeta mientras tanto,
     * `remove()` Los fragmentos se eliminaron y la memoria se eliminó, pero la instantánea en manos del hilo de recuperación todavía la tiene. ——
     * directo `restored + current` lo pondré**resurrección**. Anote las ID eliminadas en la ventana aquí y elimínelas durante la fusión.
     *
     * Se borrará una vez completada la recuperación y no crecerá indefinidamente.
     */
    private val removedDuringRestore = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var restoreFinished = false

    /** cardId → JSON texto(Lo último). */
    val data: StateFlow<Map<String, String>> = _data.asStateFlow()

    init {
        // restaurar/Migrar al hilo en segundo plano (2026-11-01 Mejoras de rendimiento P28).
        //
        // Resulta que se hace de forma sincrónica en el constructor. Prueba de equipo: formato antiguo **50 Tiempo necesario para migrar tarjetas 216 ms**,
        // CardDataStore es un singleton de Koin, creado cuando se analiza ChatViewModel por primera vez. —— Es decir, cae sobre**hilo principal**.
        // Cuantas más tarjetas, más tiempo lleva (cada tarjeta se escribe una vez de forma atómica, cada una con un fsync), y los usuarios habituales tendrán retrasos visibles en el primer inicio después de la actualización.
        //
        // Antes de que se complete la recuperación `data` Vacío: la tarjeta mostrará primero el marcador de posición y luego lo llenará con StateFlow.
        // esto es**Ruta de actualización única**, aceptable. Las escrituras se basan en lo que ya está en la memoria (ver restaurado + actual).
        runCatching {
            Thread({ restoreInBackground() }, "card-data-restore").apply {
                isDaemon = true
                start()
            }
        }.onFailure { e -> Logger.w(TAG, "El hilo de recuperación de datos de la tarjeta no pudo iniciarse: ${e.message}") }
    }

    private fun restoreInBackground() {
        val restored = runCatching {
            when {
                shardDir.isDirectory && !shardDir.listFiles().isNullOrEmpty() -> restoreFromShards()
                legacyFile.exists() -> migrateFromLegacy()
                else -> emptyMap()
            }
        }.onFailure { e: Throwable ->
            Logger.w(TAG, "Falló la recuperación de datos de la tarjeta: ${e.message}")
        }.getOrElse { emptyMap() }
        if (restored.isEmpty()) {
            restoreFinished = true
            removedDuringRestore.clear()
            return
        }
        // Fusionar en lugar de sobrescribir: las tarjetas escritas durante la recuperación tienen prioridad; Las tarjetas eliminadas durante la recuperación no se reviven.
        val survivors = restored.filterKeys { it !in removedDuringRestore }
        _data.value = survivors + _data.value
        restoreFinished = true
        removedDuringRestore.clear()
    }

    /** escribir/Actualizar los datos de una tarjeta(JSON texto;Truncamiento extralargo,Cuando se supere el número máximo de inscripciones, se eliminará la más antigua.). */
    fun put(cardId: String, jsonText: String) {
        if (cardId.isBlank()) return
        val trimmed = jsonText.trim().take(MAX_DATA_LENGTH)
        val merged = _data.value + (cardId to trimmed)
        val bounded = if (merged.size <= MAX_ITEMS) {
            merged
        } else {
            merged.entries.drop(merged.size - MAX_ITEMS).associate { it.toPair() }
        }
        // Primero escriba en el disco y luego cambie la memoria: solo se escriben los datos de esta tarjeta y los demás fragmentos permanecen sin cambios.
        if (!persistShard(cardId, trimmed)) return
        (merged.keys - bounded.keys).forEach { dropped -> deleteShard(dropped) }
        _data.value = bounded
    }

    /** Leer un dato de la tarjeta(JSON texto);Devuelve nulo si no está presente. */
    fun get(cardId: String): String? = _data.value[cardId]

    /** Eliminar un dato de la tarjeta. */
    fun remove(cardId: String) {
        // Necesito cambiar la memoria.**antes**Registro: El filtrado del hilo de recuperación lee esta colección. Esto sólo se puede garantizar registrándose primero.
        // "Eliminado y resucitado por instantánea" no sucederá (los resultados son correctos en ambos órdenes de entrelazado).
        if (!restoreFinished) removedDuringRestore.add(cardId)
        if (!_data.value.containsKey(cardId)) return
        deleteShard(cardId)
        _data.value = _data.value - cardId
    }

    // ── Lectura y escritura fragmentadas ────────────────────────────────────────────────

    private fun restoreFromShards(): Map<String, String> {
        val restored = LinkedHashMap<String, String>()
        shardDir.listFiles().orEmpty().forEach { shard ->
            if (!shard.isFile || shard.name.endsWith(".tmp", ignoreCase = true)) return@forEach
            runCatching { shard.readText() }
                .onSuccess { text ->
                    val decoded = decodeShard(text)
                    if (decoded != null) {
                        restored[decoded.first] = decoded.second
                    } else {
                        // El error de análisis debe dejar rastros: el salto silencioso es igual"Los datos de esta tarjeta desaparecen silenciosamente.",
                        // Cuando algo sale mal, ni siquiera puedes descubrir qué pieza está mal.
                        Logger.w(TAG, "Falló el análisis del fragmento de tarjeta,saltar ${shard.name}")
                        io.zer0.muse.data.AtomicFileStore.quarantine(shard, "card_shard_parse")
                    }
                }
                .onFailure { e -> Logger.w(TAG, "Falló la lectura del fragmento de tarjeta,saltar ${shard.name}: ${e.message}") }
        }
        val bounded = restored.entries.toList().takeLast(MAX_ITEMS)
        return bounded.associate { entry -> entry.key to entry.value }
    }

    /** @return Datos migrados (para que el hilo en segundo plano se fusione) `_data`); Devuelve una tabla vacía en caso de error. */
    /** @return Datos migrados (para que el hilo en segundo plano se fusione) `_data`); Devuelve una tabla vacía en caso de error. */
    private fun migrateFromLegacy(): Map<String, String> {
        val restored = runCatching {
            AppJson.decodeFromString(serializer, legacyFile.readText())
        }.onFailure { e ->
            Logger.w(TAG, "Falló la recuperación de datos de la tarjeta: ${e.message}")
        }.getOrNull() ?: return emptyMap()
        val startedAt = System.currentTimeMillis()
        val migrated = restored.all { (cardId, json) -> persistShard(cardId, json) }
        val cost = System.currentTimeMillis() - startedAt
        // El tiempo de migración se registra en el registro: el dispositivo midió aproximadamente 216 ms para 50 tarjetas en el formato antiguo (una escritura atómica + fsync para cada tarjeta),
        // Reinstalar/Después de la actualización"Primer arranque lento"Si se puede atribuir a este artículo, basta con mirar esta línea.
        if (migrated && legacyFile.renameTo(File(filesDir, LEGACY_FILE_NAME + ".migrated"))) {
            Logger.i(TAG, "El antiguo archivo único se ha migrado al almacenamiento fragmentado.(${restored.size} datos de la tarjeta, Consume mucho tiempo ${cost}ms)")
        } else if (migrated) {
            Logger.w(TAG, "El fragmento se ha escrito pero el cambio de nombre del archivo antiguo falló(${restored.size} Artículo, Consume mucho tiempo ${cost}ms)")
        }
        return restored
    }
    private fun persistShard(cardId: String, jsonText: String): Boolean = runCatching {
        AtomicFileStore.writeText(shardFile(cardId), CardDataFiles.encode(cardId, jsonText))
        true
    }.onFailure { e -> Logger.w(TAG, "Falló la persistencia de los datos de la tarjeta: ${e.message}") }.getOrDefault(false)

    private fun deleteShard(cardId: String) {
        runCatching {
            val target = shardFile(cardId)
            if (target.exists() && !target.delete()) {
                Logger.w(TAG, "Error al eliminar el fragmento de tarjeta: ${target.name}")
            }
        }.onFailure { e -> Logger.w(TAG, "Excepción de eliminación de fragmentos de tarjeta: ${e.message}") }
    }

    private fun shardFile(cardId: String): File = File(shardDir, CardDataFiles.shardName(cardId))

    private fun decodeShard(text: String): Pair<String, String>? = CardDataFiles.decode(text)

    companion object {
        private const val TAG = "CardDataStore"

        /** La cantidad máxima de elementos de datos de la tarjeta que se deben conservar. */
        private const val MAX_ITEMS = 50

        /** Longitud máxima de datos individuales(personaje). */
        private const val MAX_DATA_LENGTH = 64_000

        /** Nombre de archivo único en el formato antiguo (solo se lee durante la migración). */
        private const val LEGACY_FILE_NAME = "card_data.json"

        /** El nombre del directorio del fragmento (también debe incluirse en la lista blanca de copias de seguridad, consulte BackupBinaryFiles.allowedPrefixes). */
        private const val SHARD_DIR_NAME = "card_data"
    }
}

/**
 * El nombre del archivo y el códec del fragmento de datos de la tarjeta.
 *
 * Se extrae para poder utilizarlo sin estructura. [CardDataStore](No se requiere contexto) Prueba única
 * "El mismo cardId tiene el mismo nombre" "El viaje de ida y vuelta es consistente" "El contenido ilegal no se trata como datos vacíos".
 */
internal object CardDataFiles {

    /** Contenido del fragmento:cardId Guárdalos juntos. El nombre del archivo es solo un hash. Sólo cuando lo leas de nuevo sabrás a quién pertenece. */
    @Serializable
    internal data class Envelope(
        val cardId: String,
        val data: String,
    )

    /** nombre de archivo = cardId El hexágono SHA-256 (cardId es la identificación del mensaje o `c_xxx`, que contiene delimitadores no es sorprendente). */
    fun shardName(cardId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cardId.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + ".json"
    }

    fun encode(cardId: String, data: String): String =
        AppJson.encodeToString(Envelope.serializer(), Envelope(cardId = cardId, data = data))

    /** Si la decodificación falla, se devuelve nulo (la persona que llama omite el fragmento y no lo escribe en la memoria como datos vacíos). */
    fun decode(text: String): Pair<String, String>? =
        runCatching { AppJson.decodeFromString(Envelope.serializer(), text) }
            .getOrNull()
            ?.let { it.cardId to it.data }
}
