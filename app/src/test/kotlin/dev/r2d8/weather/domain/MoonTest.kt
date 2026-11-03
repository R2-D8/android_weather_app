package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Reference times from the US Naval Observatory's API (aa.usno.navy.mil/api/rstt/oneday), fetched 2026-11-03. */
class MoonTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val helsinki = ZoneId.of("Europe/Helsinki")

    private fun assertClock(expected: String, actual: Instant?, zone: ZoneId) {
        assertNotNull("expected $expected", actual)
        val got = actual!!.atZone(zone).toLocalTime()
        val minutes = abs(got.toSecondOfDay() - LocalTime.parse(expected).toSecondOfDay()) / 60.0
        assertTrue("expected $expected, got $got", minutes <= 2.0)
    }

    @Test
    fun `Treviso on 3 November 2026 has a rise and a set`() {
        val day = Moon.day(LocalDate.of(2026, 11, 3), rome, 45.72, 12.25)
        assertClock("00:15", day.rise, rome)
        assertClock("14:19", day.set, rome)
    }

    @Test
    fun `Turku on 3 November 2026 has no moonrise`() {
        val day = Moon.day(LocalDate.of(2026, 11, 3), helsinki, 60.45, 22.27)
        assertNull(day.rise)
        assertClock("15:09", day.set, helsinki)
    }

    @Test
    fun `Treviso near new moon`() {
        val day = Moon.day(LocalDate.of(2026, 11, 10), rome, 45.72, 12.25)
        assertClock("08:32", day.rise, rome)
        assertClock("16:55", day.set, rome)
    }

    @Test
    fun `Turku with the set before the rise`() {
        val day = Moon.day(LocalDate.of(2026, 11, 20), helsinki, 60.45, 22.27)
        assertClock("14:11", day.rise, helsinki)
        assertClock("02:28", day.set, helsinki)
    }

    @Test
    fun `Rome on 1 December 2026 has no moonrise`() {
        val day = Moon.day(LocalDate.of(2026, 12, 1), rome, 41.89, 12.48)
        assertNull(day.rise)
        assertClock("12:40", day.set, rome)
        assertFalse(day.upAllDay)
    }

    @Test
    fun `phases match USNO's moments`() {
        // USNO: last quarter 1 Nov 20:28, new 9 Nov 07:02, first quarter 17 Nov 11:48, full 24 Nov 14:53 (UTC).
        fun at(text: String) = Moon.phase(Instant.parse(text))
        assertEquals("Last quarter", at("2026-11-01T20:28:00Z").name)
        assertEquals(270.0, at("2026-11-01T20:28:00Z").elongation, 0.5)
        assertEquals("New moon", at("2026-11-09T07:02:00Z").name)
        assertEquals(0, at("2026-11-09T07:02:00Z").percent)
        assertEquals("First quarter", at("2026-11-17T11:48:00Z").name)
        assertEquals(50.0, at("2026-11-17T11:48:00Z").percent.toDouble(), 1.0)
        assertEquals("Full moon", at("2026-11-24T14:53:00Z").name)
        assertEquals(100.0, at("2026-11-24T14:53:00Z").percent.toDouble(), 1.0)
    }

    @Test
    fun `phase names between the main phases`() {
        assertEquals("Waning crescent", Moon.phase(Instant.parse("2026-11-04T12:00:00Z")).name)
        assertEquals("Waxing crescent", Moon.phase(Instant.parse("2026-11-12T12:00:00Z")).name)
        val gibbous = Moon.phase(Instant.parse("2026-11-20T12:00:00Z"))
        assertEquals("Waxing gibbous", gibbous.name)
        assertTrue(gibbous.waxing)
        assertEquals("Waning gibbous", Moon.phase(Instant.parse("2026-11-28T12:00:00Z")).name)
    }

    @Test
    fun `far north the Moon can stay up all day`() {
        // Svalbard during the 2025-2026 major lunar standstill: the Moon is circumpolar for days.
        val zone = ZoneId.of("Arctic/Longyearbyen")
        val days = (0L until 30L).map { Moon.day(LocalDate.of(2026, 11, 1).plusDays(it), zone, 78.22, 15.65) }
        assertTrue(days.any { it.upAllDay })
        assertTrue(days.any { it.rise == null && it.set == null && !it.upAllDay })
    }
}
