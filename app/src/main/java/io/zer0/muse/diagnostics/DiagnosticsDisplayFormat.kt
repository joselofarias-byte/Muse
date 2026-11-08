package io.zer0.muse.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display-only formatting; exported diagnostic keys and timestamps stay unchanged. */
internal object DiagnosticsDisplayFormat {
    private val spanish = Locale.forLanguageTag("es-ES")
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", spanish)

    fun bytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(spanish, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(spanish, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(spanish, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    fun collectedAt(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        dateTime.withZone(zone).format(Instant.parse(iso))
    }.getOrDefault(iso)

    fun epochMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTime.withZone(zone).format(Instant.ofEpochMilli(millis))
}
