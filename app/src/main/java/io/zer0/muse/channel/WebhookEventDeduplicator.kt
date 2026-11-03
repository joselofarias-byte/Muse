package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/** El ID del evento del webhook a corto plazo persiste y el rango de deduplicación no se ve afectado por la cantidad de elementos retenidos en la bandeja de entrada de la interfaz de usuario. */
internal class WebhookEventDeduplicator(
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var entries = loadEntries()

    @Synchronized
    fun claim(platform: String, channelId: String, eventId: String): Boolean {
        require(platform.isNotBlank() && channelId.isNotBlank() && eventId.isNotBlank())
        val now = nowMillis()
        val retained = entries.filter { now - it.recordedAtMillis <= RETENTION_MILLIS }
        val key = digest("$platform\u0000$channelId\u0000$eventId")
        if (retained.any { it.digest == key }) {
            replaceEntries(retained)
            return false
        }
        check(retained.size < MAX_ENTRIES) {
            "Webhook event deduplication capacity reached; refusing event"
        }
        replaceEntries(retained + Entry(key, now))
        return true
    }

    @Synchronized
    fun contains(platform: String, channelId: String, eventId: String): Boolean {
        require(platform.isNotBlank() && channelId.isNotBlank() && eventId.isNotBlank())
        val now = nowMillis()
        val retained = entries.filter { now - it.recordedAtMillis <= RETENTION_MILLIS }
        val key = digest("$platform\u0000$channelId\u0000$eventId")
        val found = retained.any { it.digest == key }
        replaceEntries(retained)
        return found
    }

    private fun loadEntries(): List<Entry> {
        if (!file.exists()) return emptyList()
        val loaded = AppJson.decodeFromString(ListSerializer(Entry.serializer()), file.readText())
        // v2.6.0 (AUD-CHANNEL-F4): Sobrecapacidad (escritura anormal/El libro mayor que ha sido manipulado será aislado y reabierto.
        // En lugar de que el constructor arroje un error——Lanzar un error hará que adjunto marque el libro mayor como no disponible y todos los eventos posteriores serán rechazados.
        // El aislamiento preserva el sitio para el diagnóstico y al mismo tiempo permite que el deduplicador continúe trabajando desde un estado limpio.
        return if (loaded.size > MAX_ENTRIES) {
            AtomicFileStore.quarantine(file, "webhook_ledger_oversize")
            emptyList()
        } else {
            loaded
        }
    }

    private fun replaceEntries(updated: List<Entry>) {
        if (updated == entries) return
        AtomicFileStore.writeText(
            file,
            AppJson.encodeToString(ListSerializer(Entry.serializer()), updated),
        )
        entries = updated
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Serializable
    private data class Entry(
        val digest: String,
        val recordedAtMillis: Long,
    )

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val RETENTION_MILLIS = 10 * 60 * 1000L
    }
}
