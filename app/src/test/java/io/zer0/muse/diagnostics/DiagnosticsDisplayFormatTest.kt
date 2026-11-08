package io.zer0.muse.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsDisplayFormatTest {
    @Test
    fun `sizes use Spanish decimal commas and decimal units`() {
        assertEquals("0 B", DiagnosticsDisplayFormat.bytes(0))
        assertEquals("999 B", DiagnosticsDisplayFormat.bytes(999))
        assertEquals("1,0 KB", DiagnosticsDisplayFormat.bytes(1_000))
        assertEquals("4,1 KB", DiagnosticsDisplayFormat.bytes(4_096))
        assertEquals("164,8 KB", DiagnosticsDisplayFormat.bytes(164_800))
        assertEquals("1,0 MB", DiagnosticsDisplayFormat.bytes(1_000_000))
        assertEquals("1,25 GB", DiagnosticsDisplayFormat.bytes(1_250_000_000))
    }

    @Test
    fun `sizes remain Spanish when the device locale is English`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("4,1 KB", DiagnosticsDisplayFormat.bytes(4_096))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `collection timestamp uses local day month year and 24 hour time`() {
        assertEquals(
            "08/11/2026 16:23:14",
            DiagnosticsDisplayFormat.collectedAt("2026-11-08T19:23:14Z", ZoneId.of("America/Argentina/Buenos_Aires")),
        )
    }

    @Test
    fun `scheduled timestamp uses the same local display format`() {
        assertEquals(
            "08/11/2026 16:23:14",
            DiagnosticsDisplayFormat.epochMillis(
                Instant.parse("2026-11-08T19:23:14Z").toEpochMilli(),
                ZoneId.of("America/Argentina/Buenos_Aires"),
            ),
        )
    }

    @Test
    fun `device timezone is read for each formatting call`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("08/11/2026 19:23:14", DiagnosticsDisplayFormat.collectedAt("2026-11-08T19:23:14Z"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"))
            assertEquals("08/11/2026 16:23:14", DiagnosticsDisplayFormat.collectedAt("2026-11-08T19:23:14Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `timezone conversion handles daylight saving and date rollover`() {
        val madrid = ZoneId.of("Europe/Madrid")
        assertEquals("09/11/2026 00:30:00", DiagnosticsDisplayFormat.collectedAt("2026-11-08T23:30:00Z", madrid))
        assertEquals("08/07/2026 16:23:14", DiagnosticsDisplayFormat.collectedAt("2026-07-08T14:23:14Z", madrid))
    }

    @Test
    fun `invalid collection timestamp stays visible for diagnosis`() {
        assertEquals("invalid", DiagnosticsDisplayFormat.collectedAt("invalid"))
    }
}
