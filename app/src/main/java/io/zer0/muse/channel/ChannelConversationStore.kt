package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * v2.0.1: Almacenamiento de conversaciones de canales — prensa (canal id, Fuente) Guarde varias rondas de conversaciones,Proporciona contexto para la construcción de respuestas automáticas.
 *
 * con [ChannelInbox] isomorfismo(Persistencia global singleton + JSON atómico),Pero lo que queda es"Registro de conversación":
 * Viaje de ida y vuelta entre usuario y asistente + resumen de desplazamiento(para compresión automática contextual).
 *
 * ## Formulario de almacenamiento(2026-11-01 Mejoras de rendimiento)
 *
 * La forma antigua es**Volumen completo de un solo archivo**:`filesDir/channel_conversations.json` Instalar todas las sesiones.
 * Cada vez que se agrega una ronda, se debe volver a serializar toda la tabla + escritura atómica + fsync —— 50 sesiones, cada una como máximo
 * 200 En este momento, un anexo codificará megabytes y solo una de las sesiones cambiará realmente.
 * Cada mensaje proveniente del canal se agrega al menos dos veces (rueda de usuario + rueda de asistente), todas con el mismo bloqueo de objeto.
 *
 * La forma actual es**Fragmentación por sesión**:`filesDir/channel_conversations/<key de sha256>.json`,
 * Cada archivo contiene solo una sesión, que se parece a `{"key":"<channelId>|<from>","conversation":{...}}`.
 * Entonces la cantidad de colocación de anexar = El tamaño de esta sesión (decenas de KB) no tiene nada que ver con otras sesiones;
 * La escritura atómica todavía se realiza archivo por archivo. La conclusión es que "cada sesión es completamente legible en cualquier momento" y es menos propensa a tener problemas que reescribir toda la tabla.
 *
 * Los archivos antiguos siguen siendo compatibles: cuando el directorio de fragmentos está vacío y los archivos antiguos existen, lea los archivos antiguos → escribe fragmento → Cambie el nombre del archivo antiguo a
 * `.migrated`, la falla no afectará el uso (la fragmentación tiene prioridad).
 */
object ChannelConversationStore {

    /** Turno de diálogo único.role: "user" / "assistant". */
    @Serializable
    data class Turn(
        val role: String,
        val text: String,
        val at: Long = System.currentTimeMillis(),
        /** v2.0.1: tipo de medio("image";vacío = texto plano). */
        val mediaKind: String = "",
        /** v2.0.1: fotos base64(Después de la compresión). */
        val mediaBase64: String = "",
        /** v2.x (B4): medios sin imagen(vídeo/Archivo)La ruta local al disco. */
        val mediaPath: String = "",
        /** v2.0.1: Caché de descripción de degradación visual(Generado cuando el modelo no soporta la visión.,Evite la duplicación de análisis). */
        val mediaDescription: String = "",
        /** Envío automático de respuesta ID;Se utiliza para evitar agregar el mismo usuario repetidamente al volver a intentarlo./Turnos de asistente. */
        val dispatchId: String = "",
    )

    /** sola conversación:Resumen de desplazamiento + rondas recientes. */
    @Serializable
    data class Conversation(
        val summary: String = "",
        val turns: List<Turn> = emptyList(),
        val updatedAt: Long = 0L,
        /** Mantener el estado de las rondas de distribución recientes.,Aunque la compresión de resumen ha eliminado los giros originales de los giros. */
        val recentDispatchTurnIds: List<String> = emptyList(),
    )

    private const val TAG = "ChannelConversations"

    /** Nombre de archivo único en el formato antiguo (solo se lee durante la migración). */
    private const val LEGACY_FILE_NAME = "channel_conversations.json"

    /** El nombre del directorio de fragmentos. */
    private const val SHARD_DIR_NAME = "channel_conversations"

    /** Número máximo de conversaciones a mantener(Cuando se supere el importe se eliminará el que lleve más tiempo sin actualizarse.). */
    private const val MAX_CONVERSATIONS = 50

    /** Límite superior estricto para rondas de diálogo únicas(Agregue una capa de cinturón de seguridad encima de la lógica de compresión.). */
    private const val MAX_TURNS = 200
    private const val MAX_DISPATCH_TURN_IDS = 1_000

    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    /** Archivo único en formato antiguo (solo para migración). */
    private var legacyFile: File? = null

    /** Directorio fragmentado: un archivo por sesión. */
    private var shardDir: File? = null

    /** archivos enlazadosDir;null Significa que aún no ha sido vinculado. */
    private var boundFilesDir: File? = null

    /**
     * Vincular el contexto de la aplicación y restaurar el historial(idempotente).
     *
     * Utilice filesDir para determinar la idempotencia en lugar de un indicador booleano: la misma aplicación solo utilizará el mismo filesDir.
     * Pero el directorio temporal de cada caso de uso en una sola prueba es diferente. —— El uso de Boolean dejará el estado en el directorio anterior,
     * Entonces afirmaciones como "¿ha sido escrito?" se leen de otros directorios.
     */
    @Synchronized
    fun attach(context: Context) {
        val filesDir = context.applicationContext.filesDir
        if (boundFilesDir?.absolutePath == filesDir.absolutePath) return
        boundFilesDir = filesDir
        val legacy = File(filesDir, LEGACY_FILE_NAME)
        val dir = File(filesDir, SHARD_DIR_NAME)
        legacyFile = legacy
        shardDir = dir
        when {
            // Prioridad de fragmentación: mientras ya haya archivos en el directorio, los archivos antiguos no se tocarán.
            dir.isDirectory && !dir.listFiles().isNullOrEmpty() -> restoreFromShards(dir)
            legacy.exists() -> migrateFromLegacy(legacy, dir)
            else -> Unit
        }
    }

    private fun restoreFromShards(dir: File) {
        val restored = LinkedHashMap<String, Conversation>()
        dir.listFiles().orEmpty().forEach { shard ->
            if (!shard.isFile || shard.name.endsWith(".tmp", ignoreCase = true)) return@forEach
            runCatching { shard.readText() }
                .onSuccess { text ->
                    val decoded = ChannelConversationFiles.decode(text)
                    if (decoded != null) restored[decoded.first] = decoded.second
                }
                .onFailure { e -> Logger.w(TAG, "Error de lectura del fragmento,saltar ${shard.name}: ${e.message}") }
        }
        _conversations.value = restored
            .entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
    }

    private fun migrateFromLegacy(target: File, dir: File) {
        runCatching {
            AppJson.decodeFromString(
                MapSerializer(String.serializer(), Conversation.serializer()),
                target.readText(),
            )
        }.onSuccess { restored ->
            _conversations.value = restored
            // El error de migración no afecta el uso: si no se puede escribir el fragmento, el siguiente anexo escribirá el fragmento nuevamente.
            // El archivo antiguo continúa utilizándose como fuente de lectura (el fragmento recurrirá a él si no se puede leer externamente).
            val startedAt = System.currentTimeMillis()
            val migrated = restored.all { (key, conversation) ->
                runCatching {
                    AtomicFileStore.writeText(
                        shardFile(dir, key),
                        ChannelConversationFiles.encode(key, conversation),
                    )
                }.isSuccess
            }
            val cost = System.currentTimeMillis() - startedAt
            if (migrated && target.renameTo(File(target.parentFile, LEGACY_FILE_NAME + ".migrated"))) {
                Logger.i(TAG, "El antiguo archivo único se ha migrado al almacenamiento fragmentado.(${restored.size} conversaciones, Consume mucho tiempo ${cost}ms)")
            }
        }.onFailure { e ->
            // v2.6.0 (AUD-CHANNEL-F4): Poner en cuarentena los archivos defectuosos cuando falla la recuperación,Evite ser sobrescrito por escrituras posteriores.
            Logger.w(TAG, "Error al recuperar el historial de conversaciones,Poner en cuarentena archivos malos: ${e.message}")
            io.zer0.muse.data.AtomicFileStore.quarantine(target, "conversation_restore_failed")
        }
    }

    private fun shardFile(dir: File, key: String): File = File(dir, ChannelConversationFiles.shardName(key))

    /** clave de diálogo:ID del canal + fuente(Persona de contacto id). */
    fun key(channelId: String, from: String): String = "$channelId|$from"

    /** Agregar una ronda(Escribir memoria sincrónicamente + colocar en el disco;Canal de noticias de baja frecuencia.,Solo llámalo directamente). */
    @Synchronized
    fun append(
        channelId: String,
        from: String,
        role: String,
        text: String,
        mediaKind: String = "",
        mediaBase64: String = "",
        // v2.x (B4): vídeo/Ruta del disco de archivos;El valor predeterminado está vacío para mantener la compatibilidad con versiones anteriores.
        mediaPath: String = "",
    ) {
        appendTurn(
            channelId,
            from,
            Turn(
                role = role,
                text = text,
                mediaKind = mediaKind,
                mediaBase64 = mediaBase64,
                mediaPath = mediaPath,
            ),
        )
    }

    @Synchronized
    fun appendTurn(channelId: String, from: String, turn: Turn) {
        val key = key(channelId, from)
        val current = _conversations.value[key] ?: Conversation()
        val dispatchTurnKey = channelDispatchTurnIdentity(turn.dispatchId, turn.role)
        if (dispatchTurnKey != null && dispatchTurnKey in current.recentDispatchTurnIds) return
        val turns = (current.turns + turn).takeLast(MAX_TURNS)
        val dispatchTurnIds = dispatchTurnKey
            ?.let { (current.recentDispatchTurnIds + it).takeLast(MAX_DISPATCH_TURN_IDS) }
            ?: current.recentDispatchTurnIds
        val updated = current.copy(
            turns = turns,
            updatedAt = System.currentTimeMillis(),
            recentDispatchTurnIds = dispatchTurnIds,
        )
        val merged = _conversations.value + (key to updated)
        val trimmed = merged.entries
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
        val dropped = merged.keys - trimmed.keys
        // Primero escriba en el disco y luego cambie la memoria (con contrato de recuperación): escriba solo los fragmentos de esta sesión.
        check(persistConversation(key, updated)) { "Channel conversation could not persist an appended turn" }
        // La sesión eliminada eliminará el fragmento correspondiente; no eliminarlo solo afectará el uso del disco y ya no se hará referencia a él en la memoria.
        dropped.forEach { deleteConversation(it) }
        _conversations.value = trimmed
    }

    /** Leer conversación(Devuelve si no existe null). */
    fun conversation(channelId: String, from: String): Conversation? = _conversations.value[key(channelId, from)]

    /**
     * v2.3.2: Comprimir resultados**Reescritura condicional** —— reemplazar el original `replace()` Cobertura ciega.
     *
     * Problemas con la implementación original.:`maybeCompress` si"leer instantánea → sintonizar LLM(Hasta decenas de segundos)→ Reescritura completa",
     * período [append] La nueva ronda que entre será completamente cubierta y descartada directamente.(Lo que el usuario acaba de decir desaparece del contexto del canal.,
     * comportarse como un robot"Olvidé la frase anterior.").
     *
     * La semántica de este método.:
     *  - Solo si el resumen de la conversación sigue siendo el mismo que se leyó antes de la compresión.([snapshotSummary])solo escribe —— De lo contrario significa otra compresión.
     *    El resumen se ha actualizado primero.,Este resultado no es válido(Vuelva a intentarlo la próxima vez que se active);
     *  - solo eliminar [drainedTurns] esta sección(Verificación de identidad basada en rondas de límites),Las rondas para nuevos apéndices se mantienen intactas durante la compactación;
     *  - Las rondas de límites no coinciden(La lista se borra/recorta y se reconstruye.)Cancelar la escritura cuando,Sería mejor estar bajo una fuerte presión en la siguiente ronda que eliminar la nueva ronda por error.
     *
     * @return true = ha sido escrito;false = rendirse(La persona que llama solo registra)
     */
    @Synchronized
    fun applyCompression(channelId: String, from: String, snapshotSummary: String, drainedTurns: List<Turn>, summary: String): Boolean {
        val key = key(channelId, from)
        val current = _conversations.value[key]
        val applicable = compressionApplicable(current, snapshotSummary, drainedTurns)
        if (applicable && current != null) {
            val updated =
                current.copy(
                    summary = summary,
                    turns = current.turns.drop(drainedTurns.size),
                    updatedAt = System.currentTimeMillis(),
                )
            val map = _conversations.value + (key to updated)
            if (!persistConversation(key, updated)) return false
            _conversations.value = map
        }
        return applicable
    }

    /**
     * Si los resultados de la compresión aún se pueden reescribir. Tres premisas(Si no estás satisfecho, renuncia,Preferiría estar bajo una fuerte presión en la próxima ronda que borrar la nueva ronda por error.):
     *  1. La conversación continúa;
     *  2. El resumen sigue siendo el mismo que se leyó antes de la compresión. —— De lo contrario, significa que se ha escrito de forma preventiva otra compresión.;
     *  3. Las rondas de límites siguen siendo las mismas.(marca de tiempo + texto)—— De lo contrario, la lista se borra/recorta y se reconstruye.
     */
    private fun compressionApplicable(current: Conversation?, snapshotSummary: String, drainedTurns: List<Turn>): Boolean {
        val boundary = drainedTurns.lastOrNull()
        val currentBoundary = current?.turns?.getOrNull(drainedTurns.size - 1)
        val sameSummary = current != null && current.summary == snapshotSummary
        val sameBoundary =
            boundary != null && currentBoundary != null &&
                currentBoundary.at == boundary.at && currentBoundary.text == boundary.text
        return sameSummary && sameBoundary
    }

    /** Borrar una sola conversación. Actualice la memoria solo después de eliminar el fragmento con éxito,Asegúrese de que la interfaz sea coherente con el disco. */
    @Synchronized
    fun clear(channelId: String, from: String) {
        val key = key(channelId, from)
        if (!deleteConversation(key)) return
        _conversations.value = _conversations.value - key
    }

    /** v2.0.1: Escribir caché de descripción de degradación visual para rondas de imágenes(Posición por marca de tiempo,Golpea como máximo uno). */
    @Synchronized
    fun updateTurnDescription(channelId: String, from: String, turnAt: Long, description: String) {
        val key = key(channelId, from)
        val conversation = _conversations.value[key] ?: return
        val index = conversation.turns.indexOfFirst { it.at == turnAt && it.mediaKind == "image" }
        if (index < 0) return
        val turns = conversation.turns.toMutableList().also {
            it[index] = it[index].copy(mediaDescription = description)
        }
        val updated = conversation.copy(turns = turns)
        if (persistConversation(key, updated)) _conversations.value = _conversations.value + (key to updated)
    }

    /** Escribe un fragmento de sesión; devuelve falso para indicar que la escritura no fue exitosa (la persona que llama decide si actualiza la memoria en consecuencia). */
    private fun persistConversation(key: String, conversation: Conversation): Boolean {
        if (ProcessWriteGate.restoring) return false
        val dir = shardDir ?: return true
        return runCatching {
            AtomicFileStore.writeText(
                shardFile(dir, key),
                ChannelConversationFiles.encode(key, conversation),
            )
            true
        }.onFailure { e -> Logger.w(TAG, "Falló la persistencia del historial de conversaciones: ${e.message}") }
            .getOrDefault(false)
    }

    /** Eliminar los fragmentos de una sesión; el archivo no existe incluso si no existe. */
    private fun deleteConversation(key: String): Boolean {
        val dir = shardDir ?: return true
        return runCatching {
            val target = shardFile(dir, key)
            !target.exists() || target.delete()
        }.onFailure { e -> Logger.w(TAG, "Error al eliminar el fragmento de conversación: ${e.message}") }
            .getOrDefault(false)
    }
}

/**
 * El nombre del archivo y la codificación del fragmento. Se extrae para permitir pruebas únicas sin iniciar el singleton ni tocar el directorio de archivos real.
 * "La misma clave tiene el mismo nombre estable" "Codificación/Decodificar ida y vuelta" y "el contenido ilegal no se trata como una sesión vacía" son tres cosas.
 */
internal object ChannelConversationFiles {

    /** Contenido del fragmento:key Guárdelo junto, el nombre del archivo es solo un hash y no sabrá a quién pertenece hasta que lo vuelva a leer. */
    @Serializable
    internal data class Envelope(
        val key: String,
        val conversation: ChannelConversationStore.Conversation,
    )

    /** nombre de archivo = key SHA-256 hexagonal.key hay `|`, puede contener separadores de ruta y caracteres de control. */
    fun shardName(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) } + ".json"
    }

    fun encode(key: String, conversation: ChannelConversationStore.Conversation): String =
        AppJson.encodeToString(Envelope.serializer(), Envelope(key, conversation))

    /** Si la decodificación falla, se devuelve nulo (la persona que llama omite el fragmento y no lo escribe en la memoria como una sesión vacía). */
    fun decode(text: String): Pair<String, ChannelConversationStore.Conversation>? =
        runCatching { AppJson.decodeFromString(Envelope.serializer(), text) }
            .getOrNull()
            ?.let { it.key to it.conversation }
}

private fun channelDispatchTurnIdentity(dispatchId: String, role: String): String? =
    dispatchId.takeIf { it.isNotBlank() }?.let { "$it\u0000$role" }
