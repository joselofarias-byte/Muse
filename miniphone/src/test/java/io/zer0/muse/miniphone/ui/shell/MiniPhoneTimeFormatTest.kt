package io.zer0.muse.miniphone.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * texto de tiempo:tiempo relativo(Compartir en Momentos, comentarios y listas de mensajes)Existen diferentes formatos para separar estos dos lugares de la barra de estado y el chat.
 */
class MiniPhoneTimeFormatTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun missingTimestampRendersEmpty() {
        assertEquals("", relativeTimeOf(epochMs = 0L, now = at(2026, 10, 29, 21, 51)))
    }

    @Test
    fun withinAMinuteShowsJustNow() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("justo ahora", relativeTimeOf(epochMs = now - 30_000L, now = now))
        assertEquals("Está a sólo unos segundos de distancia", "justo ahora", relativeTimeOf(epochMs = now - 59_999L, now = now))
    }

    @Test
    fun withinAnHourShowsMinutesAgo() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("1 hace minutos", relativeTimeOf(epochMs = now - 60_000L, now = now))
        assertEquals("12 hace minutos", relativeTimeOf(epochMs = now - 12L * 60_000L, now = now))
        assertEquals("59 hace minutos", relativeTimeOf(epochMs = now - 59L * 60_000L, now = now))
    }

    @Test
    fun earlierTodayShowsHoursAgo() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("3 hace horas", relativeTimeOf(epochMs = at(2026, 10, 29, 18, 0), now = now))
        assertEquals("Incluso si cruza la hora, se contará como una hora.", "11 hace horas", relativeTimeOf(epochMs = at(2026, 10, 29, 10, 0), now = now))
    }

    @Test
    fun yesterdayCarriesTheClockIncludingBothEdges() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("ayer 21:30", relativeTimeOf(epochMs = at(2026, 10, 28, 21, 30), now = now))
        assertEquals("El primer momento ayer", "ayer 00:00", relativeTimeOf(epochMs = at(2026, 10, 28, 0, 0), now = now))
        assertEquals("último minuto ayer", "ayer 23:59", relativeTimeOf(epochMs = at(2026, 10, 28, 23, 59), now = now))
    }

    @Test
    fun olderDateInTheSameYearShowsMonthDayAndClock() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("10mes26dia 08:00", relativeTimeOf(epochMs = at(2026, 10, 26, 8, 0), now = now))
        assertEquals("1mes1dia 00:00", relativeTimeOf(epochMs = at(2026, 1, 1, 0, 0), now = now))
    }

    @Test
    fun previousYearShowsFullDateWithoutClock() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("2025año12mes31dia", relativeTimeOf(epochMs = at(2025, 12, 31, 20, 0), now = now))
        assertEquals("2025año1mes1dia", relativeTimeOf(epochMs = at(2025, 1, 1, 9, 30), now = now))
    }

    @Test
    fun clockUsesTwoDigitHourAndMinute() {
        assertEquals("09:05", MiniPhoneTimeFormat.clock(at(2026, 10, 29, 9, 5)))
        assertEquals("21:51", MiniPhoneTimeFormat.clock(at(2026, 10, 29, 21, 51)))
    }

    @Test
    fun separatorCarriesDateOnlyWhenTheDayDiffers() {
        val now = at(2026, 10, 29, 21, 51)
        assertEquals("09:05", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 29, 9, 5), now = now))
        assertEquals("ayer 23:30", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 28, 23, 30), now = now))
        assertEquals("10 26 08:00", MiniPhoneTimeFormat.separator(epochMs = at(2026, 10, 26, 8, 0), now = now))
        assertEquals(
            "2025 31 de diciembre 20:00",
            MiniPhoneTimeFormat.separator(epochMs = at(2025, 12, 31, 20, 0), now = now),
        )
    }
}
