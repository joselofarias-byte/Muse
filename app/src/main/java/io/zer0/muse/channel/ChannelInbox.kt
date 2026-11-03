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
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * v1.0.92: Bandeja de entrada entrante del canal — Grabar mensajes enviados por plataformas de mensajería instantánea externas(webhook lado receptor).
 *
 * Singleton global(objeto estático):WebServer La ruta del webhook escribe,UI Leer pantalla.
 * Registros procesados más recientemente [MAX_ITEMS] Artículo;Los eventos pendientes persisten como una cola delimitada.
 * El contenido de la bandeja de entrada está enmascarado por PII antes de almacenarse,Reducir los residuos de información sensible.
 */
object ChannelInbox {

    @Serializable
    enum class DispatchState {
        /** Falta el campo de cola JSON heredado;El historial no se reproduce después de la actualización. */
        COMPLETED,
        PENDING,
        PROCESSING,
        IGNORED,

        /** Estado final que requiere revisión manual;Mantenga los materiales de recuperación necesarios.,No lo volverá a intentar automáticamente. */
        BLOCKED,
    }

    data class Media(
        val kind: String = "",
        val base64: String = "",
        val path: String = "",
    )

    data class Source(
        val platform: String,
        val from: String,
        val channelId: String = "",
        /** Eventos de plataforma ID;Se puede utilizar una clave de retorno derivada del receptor cuando la plataforma carece de una identificación estable. */
        val eventId: String = "",
        /** El texto secreto de SecureKeyStore de la credencial de devolución de datos temporal del canal.;Realice pedidos únicamente mientras el evento esté pendiente. */
        val encryptedReplyContextToken: String = "",
    )

    /** Mensaje entrante único(Resumen + carga útil sin procesar,Para uso en la resolución de problemas y enrutamiento posterior). */
    @Serializable
    data class Inbound(
        val platform: String,
        /** Fuente(grupo/Usuario openid/chat_id, etc.,Varía según la plataforma). */
        val from: String = "",
        /** resumen legible por humanos(texto del mensaje,Enmascaramiento de PII y truncado). */
        val summary: String = "",
        /** carga bruta(Raw JSON,Truncar almacenamiento). */
        val raw: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        /** v2.0.1: tipo de medio("image" espera;vacío = texto plano). */
        val mediaKind: String = "",
        /** v2.0.1: fotos base64(Después de la compresión;clase de imagen solamente). */
        val mediaBase64: String = "",
        /**
         * v2.x (B4): medios sin imagen(vídeo/Archivo)La ruta absoluta local después de la colocación.
         *
         * vídeo/Tamaño de archivo grande,No apto para caminar [mediaBase64];Después de la descarga, guárdelo en un directorio privado.,
         * El camino está registrado aquí.,para su posterior procesamiento/Cita. vacío = Sin archivos locales(Texto plano o no colocado).
         */
        val mediaPath: String = "",
        /** ID de configuración del canal: la fuente del webhook es la configuración de autenticación,La fuente de la conexión larga es la configuración de recepción actual. */
        val sourceChannelId: String = "",
        /** Deduplicación de eventos ID;Las fuentes sin ID estables disponibles pueden estar vacías. */
        val sourceEventId: String = "",
        /** distribución local ID;El mismo elemento de la cola también se puede localizar y restaurar sin un ID de evento de plataforma. */
        val dispatchId: String = "",
        /** JSON heredado se completa de forma predeterminada cuando falta este campo,Evite reproducir mensajes históricos después de la actualización. */
        val dispatchState: DispatchState = DispatchState.COMPLETED,
        val attemptCount: Int = 0,
        val retryAtMillis: Long = 0L,
        /** Persistencia antes de enviar,Reutilice cuando vuelva a intentar para evitar llamar al modelo repetidamente para generar respuestas diferentes. */
        val preparedReply: String = "",
        /** El canal devuelve temporalmente el texto cifrado de la credencial.;Vacíe inmediatamente después de completar la distribución. */
        val encryptedReplyContextToken: String = "",
        /** Puntos de interrupción redondos de la herramienta Agente cifrado;Vacíe inmediatamente después de completar la distribución. */
        val encryptedAgentCheckpoint: String = "",
        /** código de error final;Actualmente se utiliza para marcar puntos de interrupción del Agente ilegibles. */
        val dispatchErrorCode: String = "",
        /**
         * Número de serie de la cola (2026-11-01 Introducción del almacenamiento fragmentado).
         *
         * Úselo para ordenar los nombres de los archivos de fragmentos y restaurar el orden. = Orden de matriz para la implementación anterior;0 Representa datos antiguos, que se completarán de acuerdo con el subíndice original durante la migración.
         * solo expresa**Orden de incorporación al equipo.**, no expresa prioridad de programación —— La prioridad todavía está determinada por el orden de lista de estado de envío +.
         */
        val seq: Long = 0L,
    )

    private const val MAX_ITEMS = 100
    internal const val MAX_PENDING_ITEMS = MAX_ITEMS
    private const val MAX_RAW_LENGTH = 4000
    private const val INBOX_FILE = "channel_inbox.json"

    /** El nombre del directorio fragmentado (también debe incluirse en la lista blanca de copia de seguridad de tipo directorio). */
    internal const val INBOX_DIR_NAME = "channel_inbox"
    private const val WEBHOOK_EVENT_LEDGER_FILE = "channel_webhook_event_ids.json"

    private val _messages = MutableStateFlow<List<Inbound>>(emptyList())
    val messages: StateFlow<List<Inbound>> = _messages.asStateFlow()

    internal val journal = ChannelInboxJournal(_messages)

    /** Compatible con antiguos observadores.;Solo significa que se han ingresado nuevos eventos. inbox,No significa que se complete el procesamiento automático de respuesta. */
    var onInbound: ((Inbound) -> Unit)?
        get() = journal.onInbound
        set(value) {
            journal.onInbound = value
        }

    /** Vincular el contexto de la aplicación y restaurar el historial(idempotente;webhook Se llama cuando se activa por primera vez o cuando se ingresa a la interfaz de usuario). */
    @Synchronized
    fun attach(context: Context) {
        val directory = context.applicationContext.filesDir
        journal.attach(
            File(directory, INBOX_FILE),
            File(directory, WEBHOOK_EVENT_LEDGER_FILE),
        )
    }

    /** Registrar un mensaje entrante(summary Hacer enmascaramiento y truncamiento de PII). */
    fun record(source: Source, text: String?, rawPayload: String, media: Media = Media()): Boolean {
        val safeSummary = text
            ?.let { t ->
                runCatching { io.zer0.memory.pii.PiiGuard.scrub(t).cleaned }
                    .getOrDefault("")
            }
            ?.take(2000)
            .orEmpty()
        val item = Inbound(
            platform = source.platform,
            from = source.from,
            summary = safeSummary,
            raw = rawPayload.take(MAX_RAW_LENGTH),
            mediaKind = media.kind,
            mediaBase64 = media.base64,
            mediaPath = media.path,
            sourceChannelId = source.channelId,
            sourceEventId = source.eventId,
            dispatchId = UUID.randomUUID().toString(),
            dispatchState = DispatchState.PENDING,
            encryptedReplyContextToken = source.encryptedReplyContextToken,
        )
        return journal.record(source, item)
    }

    /** Borrar historial procesado;Los elementos de la cola sin terminar se conservan,Evite perder respuestas automáticas para operaciones de limpieza. */
    fun clear() = journal.clear()
}

@Suppress("TooManyFunctions") // Queue transitions stay together so every state write shares the same persistence boundary.
internal class ChannelInboxJournal(
    private val messages: MutableStateFlow<List<ChannelInbox.Inbound>> = MutableStateFlow(emptyList()),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Archivo único antiguo (fuente de migración); Una vez que el directorio fragmentado esté listo, solo se conservará como un producto histórico en la lista blanca de respaldo. */
    private var file: File? = null

    /** Directorio de fragmentos: un elemento de cola por archivo, consulte [persistItem]. */
    private var shardDir: File? = null

    /** Número de secuencia máximo asignado (+1 para nuevos eventos). */
    private var maxSeq: Long = 0L

    private var webhookEventDeduplicator: WebhookEventDeduplicator? = null
    private var webhookEventDeduplicationFailure: Throwable? = null

    @Volatile
    var onInbound: ((ChannelInbox.Inbound) -> Unit)? = null

    @Volatile
    private var pendingListener: (() -> Unit)? = null

    @Synchronized
    fun attach(inboxFile: File, ledgerFile: File) {
        if (shardDir != null) return
        file = inboxFile
        val dir = File(inboxFile.parentFile, ChannelInbox.INBOX_DIR_NAME)
        shardDir = dir
        when {
            // Prioridad de fragmentación: si ya hay un archivo en el directorio, el archivo único anterior no se tocará.
            dir.isDirectory && !dir.listFiles().isNullOrEmpty() -> restoreFromShards(dir)
            inboxFile.exists() -> migrateFromLegacyFile(inboxFile)
            else -> Unit
        }
        runCatching { WebhookEventDeduplicator(ledgerFile, nowMillis) }
            .onSuccess {
                webhookEventDeduplicator = it
                webhookEventDeduplicationFailure = null
            }.onFailure { error ->
                webhookEventDeduplicationFailure = error
                Logger.e(TAG, "Webhook El libro de contabilidad de deduplicación de eventos no está disponible y se rechaza el procesamiento de mensajes con ID de evento.", error)
            }
    }

    fun record(source: ChannelInbox.Source, item: ChannelInbox.Inbound): Boolean {
        var wake = false
        var observerItem: ChannelInbox.Inbound? = null
        var ledgerFailure: Throwable? = null
        val accepted = synchronized(this) {
            val stableEventId = source.eventId.isNotBlank() && source.channelId.isNotBlank()
            val existing = if (stableEventId) messages.value.firstOrNull { it.matches(source) } else null
            if (existing != null) {
                wake = existing.isDispatchActive()
                if (wake && source.encryptedReplyContextToken.isNotBlank() &&
                    source.encryptedReplyContextToken != existing.encryptedReplyContextToken
                ) {
                    val index = messages.value.indexOf(existing)
                    val updated = messages.value.toMutableList().also {
                        it[index] = existing.copy(encryptedReplyContextToken = source.encryptedReplyContextToken)
                    }
                    check(persistItem(updated[index])) { "Channel inbox could not refresh the inbound reply context" }
                    messages.value = updated
                }
                ledgerFailure = runCatching { claimEvent(source) }.exceptionOrNull()
                false
            } else if (isEventClaimed(source)) {
                // Reconectar Reanudar Los eventos duplicados reeditados caerán aquí;El libro mayor se conserva durante 10 minutos.,
                // Si no hay ningún registro, desaparecerán silenciosamente.,Después no se pudo encontrar ningún rastro.
                Logger.i(
                    TAG,
                    "El evento ha sido reclamado por el libro mayor deduplicado.,saltar(platform=${source.platform}, from=${source.from}, " +
                        "eventId=${source.eventId})",
                )
                false
            } else {
                if (pendingCountLocked() >= ChannelInbox.MAX_PENDING_ITEMS) throw ChannelInboxCapacityException()
                val enriched = item.copy(seq = ++maxSeq)
                val candidate = listOf(enriched) + messages.value
                val retained = retain(candidate)
                // Primero descargue este nuevo evento y luego modifique la memoria; la entrada eliminada se eliminará del fragmento.
                check(persistItem(enriched)) { "Channel inbox could not persist the inbound event" }
                // Usa seq para juzgar"¿Te han despedido?", no puede ser igual a la clase de datos:
                // normalize Se borrará el mediaBase64 de la entrada final y la copia borrada no será igual al objeto original. ——
                // El juicio se basará en la igualdad"El que acaba de estandarizarse."Confundiéndolo con un recorte, elimine sus fragmentos.
                val keptSeqs = retained.mapTo(HashSet()) { it.seq }
                deleteDropped(candidate.filterNot { it.seq in keptSeqs })
                messages.value = retained
                observerItem = enriched
                wake = true
                ledgerFailure = runCatching { claimEvent(source) }.exceptionOrNull()
                true
            }
        }
        observerItem?.let { inbound ->
            runCatching { onInbound?.invoke(inbound) }
                .onFailure { error -> Logger.w(TAG, "Error en la devolución de llamada del oyente entrante: ${error.message}") }
        }
        if (wake) notifyPendingListener()
        ledgerFailure?.let { throw it }
        return accepted
    }

    fun setPendingListener(listener: (() -> Unit)?) {
        val shouldWake = synchronized(this) {
            pendingListener = listener
            listener != null && messages.value.any { it.dispatchState == ChannelInbox.DispatchState.PENDING }
        }
        if (shouldWake) notifyPendingListener()
    }

    @Synchronized
    fun recoverInterruptedDispatches() {
        val current = messages.value
        val recovered = normalize(
            current.map { item ->
                if (item.dispatchState == ChannelInbox.DispatchState.PROCESSING) {
                    item.copy(dispatchState = ChannelInbox.DispatchState.PENDING, retryAtMillis = 0L)
                } else {
                    item
                }
            },
        )
        if (recovered != current) {
            val changedSeqs = recovered
                .filter { updated -> current.none { it.seq == updated.seq && it == updated } }
                .mapTo(HashSet()) { it.seq }
            changedSeqs.forEach { seq ->
                val normalizedItem = recovered.first { it.seq == seq }
                check(persistItem(normalizedItem)) { "Channel inbox could not recover interrupted dispatches" }
            }
            // Igual que arriba: eliminar solo"seq ya no existe"de fragmentos. Según la sentencia de igualdad, los distintos elementos que acaban de cambiar de estado se eliminarán juntos (son
            // En actual está PROCESANDO, en recuperado está PENDIENTE), lo que significa borrarlo tan pronto como termines de escribir.
            val keptSeqs = recovered.mapTo(HashSet()) { it.seq }
            deleteDropped(current.filterNot { it.seq in keptSeqs })
            messages.value = recovered
        }
    }

    @Synchronized
    fun claimNextPending(): ChannelInbox.Inbound? {
        val now = nowMillis()
        val index = messages.value.indexOfFirst {
            it.dispatchState == ChannelInbox.DispatchState.PENDING && it.retryAtMillis <= now
        }
        if (index < 0) return null
        val current = messages.value
        val claimed = current[index].copy(dispatchState = ChannelInbox.DispatchState.PROCESSING)
        val updated = current.toMutableList().also { it[index] = claimed }
        check(persistItem(claimed)) { "Channel inbox could not persist dispatch claim" }
        messages.value = updated
        return claimed
    }

    @Synchronized
    fun savePreparedReply(dispatchId: String, reply: String): Boolean {
        if (reply.isBlank()) return false
        return updateProcessing(dispatchId) { it.copy(preparedReply = reply) }
    }

    @Synchronized
    fun completeDispatch(dispatchId: String, ignored: Boolean) {
        check(
            updateProcessing(dispatchId) {
                it.copy(
                    dispatchState = if (ignored) {
                        ChannelInbox.DispatchState.IGNORED
                    } else {
                        ChannelInbox.DispatchState.COMPLETED
                    },
                    retryAtMillis = 0L,
                    preparedReply = "",
                    encryptedReplyContextToken = "",
                    encryptedAgentCheckpoint = "",
                    dispatchErrorCode = "",
                )
            },
        ) { "Channel inbox dispatch disappeared before completion was recorded" }
    }

    /**
     * Convierta las distribuciones que no se pueden restaurar automáticamente a un estado final de revisión manual.
     *
     * Agent checkpoint No puedo caminar cuando hay una avería. [completeDispatch]: Se perderá el borrado del texto cifrado.
     * identificar/Se necesitan pruebas para comprobar si se han producido efectos secundarios. Borrar solo tokens de devolución de datos de una sola vez,
     * Mantener puntos de control y respuestas preparadas para su posterior diagnóstico.
     */
    @Synchronized
    fun blockDispatch(dispatchId: String, errorCode: String) {
        require(errorCode.isNotBlank()) { "A blocked dispatch must include an error code" }
        check(
            updateProcessing(dispatchId) {
                it.copy(
                    dispatchState = ChannelInbox.DispatchState.BLOCKED,
                    retryAtMillis = 0L,
                    encryptedReplyContextToken = "",
                    dispatchErrorCode = errorCode,
                )
            },
        ) { "Channel inbox dispatch disappeared before blocked state was recorded" }
    }

    @Synchronized
    fun retryDispatch(dispatchId: String): Long {
        val now = nowMillis()
        var retryAt = now
        check(
            updateProcessing(dispatchId) { current ->
                val attempts = current.attemptCount + 1
                retryAt = now + retryDelayMillis(attempts)
                current.copy(
                    dispatchState = ChannelInbox.DispatchState.PENDING,
                    attemptCount = attempts,
                    retryAtMillis = retryAt,
                    dispatchErrorCode = "",
                )
            },
        ) { "Channel inbox dispatch disappeared before retry was recorded" }
        return retryAt
    }

    @Synchronized
    fun releaseDispatch(dispatchId: String) {
        updateProcessing(dispatchId) {
            it.copy(
                dispatchState = ChannelInbox.DispatchState.PENDING,
                retryAtMillis = 0L,
                dispatchErrorCode = "",
            )
        }
    }

    @Synchronized
    fun nextRetryDelayMillis(): Long? {
        val pendingDelay = messages.value
            .asSequence()
            .filter { it.dispatchState == ChannelInbox.DispatchState.PENDING }
            .minOfOrNull { it.retryAtMillis }
            ?.let { dueAt -> (dueAt - nowMillis()).coerceAtLeast(0L) }
        val processingDelay = RETRY_BASE_DELAY_MS.takeIf {
            messages.value.any { item -> item.dispatchState == ChannelInbox.DispatchState.PROCESSING }
        }
        return listOfNotNull(pendingDelay, processingDelay).minOrNull()
    }

    @Synchronized
    fun pendingCount(): Int = pendingCountLocked()

    fun snapshot(): List<ChannelInbox.Inbound> = messages.value

    @Synchronized
    fun encryptedReplyContextToken(dispatchId: String): String? = messages.value
        .firstOrNull {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        ?.encryptedReplyContextToken

    @Synchronized
    fun encryptedAgentCheckpoint(dispatchId: String): String? = messages.value
        .firstOrNull {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        ?.encryptedAgentCheckpoint

    @Synchronized
    fun saveEncryptedAgentCheckpoint(dispatchId: String, checkpoint: String): Boolean {
        if (checkpoint.isBlank()) return false
        return updateProcessing(dispatchId) { it.copy(encryptedAgentCheckpoint = checkpoint) }
    }

    /** Borrar historial procesado;Los elementos pendientes de la cola no se pierden debido a las operaciones de limpieza de la interfaz de usuario. */
    @Synchronized
    fun clear() {
        val retained = messages.value.filter { it.isDispatchActive() }
        val keptSeqs = retained.mapTo(HashSet()) { it.seq }
        deleteDropped(messages.value.filterNot { it.seq in keptSeqs })
        messages.value = retained
    }

    private fun updateProcessing(dispatchId: String, transform: (ChannelInbox.Inbound) -> ChannelInbox.Inbound): Boolean {
        val current = messages.value
        val index = current.indexOfFirst {
            it.dispatchId == dispatchId && it.dispatchState == ChannelInbox.DispatchState.PROCESSING
        }
        if (index < 0) return false
        val transformed = transform(current[index])
        val updated = current.toMutableList().also { it[index] = transformed }
        val retained = retain(updated)
        // Utilizado para la colocación**Después de la normalización**Una copia de:transform Las entradas resultantes también pueden tener mediaBase64,
        // Y normalizar borrará los bytes de la entrada final. Escribir la copia original equivale a escribir las cosas que se van a borrar en el disco.
        val normalized = retained.firstOrNull { it.seq == transformed.seq } ?: transformed
        check(persistItem(normalized)) { "Channel inbox could not persist dispatch state" }
        val keptSeqs = retained.mapTo(HashSet()) { it.seq }
        deleteDropped(updated.filterNot { it.seq in keptSeqs })
        messages.value = retained
        return true
    }

    private fun isEventClaimed(source: ChannelInbox.Source): Boolean {
        if (source.eventId.isBlank() || source.channelId.isBlank() || file == null) return false
        val deduplicator = webhookEventDeduplicator
            ?: error("Webhook event deduplication is unavailable: ${webhookEventDeduplicationFailure?.message}")
        return deduplicator.contains(source.platform, source.channelId, source.eventId)
    }

    private fun claimEvent(source: ChannelInbox.Source): Boolean {
        check(!ProcessWriteGate.restoring) { "La copia de seguridad y la restauración están en curso; los eventos del canal no se aceptan por el momento." }
        if (source.eventId.isBlank() || source.channelId.isBlank() || file == null) return true
        val deduplicator = webhookEventDeduplicator
            ?: error("Webhook event deduplication is unavailable: ${webhookEventDeduplicationFailure?.message}")
        return deduplicator.claim(source.platform, source.channelId, source.eventId)
    }

    private fun pendingCountLocked(): Int = messages.value.count { it.isDispatchActive() }

    private fun ChannelInbox.Inbound.isDispatchActive(): Boolean = dispatchState == ChannelInbox.DispatchState.PENDING ||
        dispatchState == ChannelInbox.DispatchState.PROCESSING

    private fun ChannelInbox.Inbound.matches(source: ChannelInbox.Source): Boolean = platform == source.platform &&
        sourceChannelId == source.channelId &&
        sourceEventId == source.eventId

    private fun normalize(items: List<ChannelInbox.Inbound>): List<ChannelInbox.Inbound> {
        // El nombre del archivo del fragmento contiene seq, que falta (seq==0, a partir de datos antiguos o entradas anormales) se completan en el orden actual.
        items.forEach { if (it.seq > maxSeq) maxSeq = it.seq }
        val withSeq = items.map { item -> if (item.seq > 0L) item else item.copy(seq = ++maxSeq) }
        val assigned = withSeq
        val migrated = assigned.map { item ->
            when {
                item.isDispatchActive() && item.dispatchId.isBlank() ->
                    item.copy(dispatchId = UUID.randomUUID().toString())
                // Se debe borrar la imagen base64 de la entrada final.
                //
                // Se ha escrito en ChannelConversationStore con la ronda en este momento (respuesta automática al construir contexto
                // Acabo de hacer una copia) y la dejé en la bandeja de entrada sin ningún consumidor. —— Y la bandeja de entrada es**Colocación de toda la mesa**de:
                // Una imagen histórica hará que este byte se retransmita cada vez que se complete el registro/reclamo/distribución.
                // Hay más de una docena de piezas de historia con imágenes, y una sola ubicación ocupa varios MB, todos los cuales contienen bloqueos de objetos.
                //
                // BLOCKED Es el estado final de "revisión manual" (el punto de control y la respuesta preparada aún quedan para el diagnóstico), y las imágenes también se conservan.
                // Solo borre base64 y deje mediaPath intacto: este último es una referencia de archivo de disco.ChannelMediaCleaner Úselo para determinar a qué archivos todavía se hace referencia.
                !item.isDispatchActive() &&
                    item.dispatchState != ChannelInbox.DispatchState.BLOCKED &&
                    item.mediaBase64.isNotEmpty() ->
                    item.copy(mediaBase64 = "")
                else -> item
            }
        }
        val active = migrated.filter { it.isDispatchActive() }
        val historySlots = (MAX_ITEMS - active.size).coerceAtLeast(0)
        return active + migrated.filterNot { it.isDispatchActive() }.take(historySlots)
    }

    private fun retain(items: List<ChannelInbox.Inbound>): List<ChannelInbox.Inbound> = normalize(items)

    // ── Persistencia fragmentada (2026-11-01 mejoras de rendimiento)────────────────────────
    //
    // La forma anterior es un único canal_inbox.json: la tabla completa se reescribe para cada transición de estado. En el ciclo de vida de un mensaje en un canal,
    // Obtener/guardar borrador / **Puntos de interrupción para cada llamada de herramienta (una vez antes y después de la ejecución)** / Completo, se reescribe toda la tabla. ——
    // Cuanto más grande es la cola, más llamadas a herramientas, más exagerada es la amplificación de escritura y todas mantienen bloqueos de objetos.
    //
    // Ahora un elemento de la cola por archivo:channel_inbox/<seq 12 poco>-<key huella digital>.json.
    // Nombre de archivo con número de serie → Durante la recuperación, la clasificación por nombre de archivo restaura el orden de puesta en cola y no se requieren archivos de índice adicionales.
    //
    // Cambio semántico (intencional y más seguro): donde antes una escritura cubría toda la cola, ahora cada entrada se escribe de forma atómica.
    // No hay invariantes de entrada cruzada entre entradas, por lo que después del bloqueo"escritura parcial"Aún legal:
    // Cada artículo está en el estado anterior o en el nuevo estado, y no habrá mitades de artículos.

    private fun restoreFromShards(dir: File) {
        val loaded = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            // Orden de entrada:record Poner nuevos eventos en la lista**primero**(listOf(item) + lista antigua),
            // Por lo tanto, cuanto mayor sea la secuencia, más cerca estará del frente. —— El orden debe ser descendente al restaurar; de lo contrario, el orden de recolección se invertirá después de reiniciar.
            .sortedByDescending { it.name }
            .mapNotNull { shard ->
                runCatching {
                    AppJson.decodeFromString(ChannelInbox.Inbound.serializer(), shard.readText())
                }.onFailure { error ->
                    Logger.w(TAG, "Error de lectura del fragmento,saltar ${shard.name}: ${error.message}")
                    AtomicFileStore.quarantine(shard, "inbox_shard_parse")
                }.getOrNull()
            }
        maxSeq = loaded.maxOfOrNull { it.seq } ?: 0L
        val normalized = normalize(loaded)
        messages.value = normalized
        // La normalización puede cortar las entradas (que superen el límite superior) y eliminar los fragmentos correspondientes para evitar que se vuelvan a leer la próxima vez.
        val kept = normalized.mapTo(HashSet()) { shardKeyOf(it) }
        loaded.filterNot { shardKeyOf(it) in kept }.forEach { deleteItem(it) }
    }

    private fun migrateFromLegacyFile(inboxFile: File) {
        runCatching {
            AppJson.decodeFromString(
                ListSerializer(ChannelInbox.Inbound.serializer()),
                inboxFile.readText(),
            )
        }.onSuccess { restored ->
            // matriz antigua**El orden es el orden de la memoria.**(los nuevos eventos primero), por lo que el subíndice 0 toma la secuencia más grande,
            // El orden restaurado en orden descendente de secuencia es exactamente el mismo que el de la implementación anterior.
            val withSeq = restored.mapIndexed { index, item -> item.copy(seq = (restored.size - index).toLong()) }
            maxSeq = withSeq.maxOfOrNull { it.seq } ?: 0L
            val normalized = normalize(withSeq)
            messages.value = normalized
            val startedAt = System.currentTimeMillis()
            val allWritten = normalized.all { persistItem(it) }
            val cost = System.currentTimeMillis() - startedAt
            if (allWritten && inboxFile.renameTo(File(inboxFile.parentFile, inboxFile.name + ".migrated"))) {
                Logger.i(TAG, "La bandeja de entrada se ha migrado al almacenamiento fragmentado(${normalized.size} Artículo, Consume mucho tiempo ${cost}ms)")
            }
        }.onFailure { error ->
            Logger.w(TAG, "Falló la recuperación de la bandeja de entrada,Poner en cuarentena archivos malos: ${error.message}")
            AtomicFileStore.quarantine(inboxFile, "inbox_restore_failed")
        }
    }

    /** La parte estable del nombre del segmento:dispatchId Prioridad; las entradas del historial pueden estar vacías → Devolver huella digital del contenido. */
    private fun shardKeyOf(item: ChannelInbox.Inbound): String =
        item.dispatchId.ifBlank { "${item.platform}|${item.from}|${item.timestamp}|${item.summary.take(64)}" }

    private fun shardFile(item: ChannelInbox.Inbound): File? {
        val dir = shardDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(shardKeyOf(item).toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(dir, "%012d-%s.json".format(item.seq, digest))
    }

    private fun persistItem(item: ChannelInbox.Inbound): Boolean {
        if (ProcessWriteGate.restoring) return false
        val target = shardFile(item) ?: return true
        return runCatching {
            AtomicFileStore.writeText(target, AppJson.encodeToString(ChannelInbox.Inbound.serializer(), item))
            true
        }.onFailure { error -> Logger.w(TAG, "Error al escribir el fragmento de la bandeja de entrada: ${error.message}") }.getOrDefault(false)
    }

    private fun deleteItem(item: ChannelInbox.Inbound) {
        val target = shardFile(item) ?: return
        runCatching {
            if (target.exists() && !target.delete()) Logger.w(TAG, "Error al eliminar el fragmento: ${target.name}")
        }.onFailure { error -> Logger.w(TAG, "Excepción de eliminación de fragmentos: ${error.message}") }
    }

    /** Eliminar [dropped] Fragmentación de todas las entradas que contiene (para recortar/borrar el historial). */
    private fun deleteDropped(dropped: List<ChannelInbox.Inbound>) { dropped.forEach { deleteItem(it) } }

    private fun notifyPendingListener() {
        runCatching { pendingListener?.invoke() }
            .onFailure { error -> Logger.w(TAG, "Error en el envío entrante del despertador: ${error.message}") }
    }

    private fun retryDelayMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 8)
        return (RETRY_BASE_DELAY_MS * multiplier).coerceAtMost(RETRY_MAX_DELAY_MS)
    }

    private companion object {
        const val TAG = "ChannelInbox"
        const val MAX_ITEMS = 100
        const val RETRY_BASE_DELAY_MS = 1_000L
        const val RETRY_MAX_DELAY_MS = 5 * 60 * 1000L
    }
}

internal class ChannelInboxCapacityException : IllegalStateException(
    "Channel inbox pending capacity reached; refusing event",
)
