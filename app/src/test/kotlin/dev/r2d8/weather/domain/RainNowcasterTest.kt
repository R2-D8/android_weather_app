package dev.r2d8.weather.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RainNowcasterTest {
    private val now = Instant.parse("2026-10-23T12:07:00Z")

    /** Slots ending at 12:15, 12:30, ... covering 12:00-12:15 etc., with the given mm values. */
    private fun slots(vararg mm: Double) = mm.mapIndexed { i, v ->
        RainSlot(Instant.parse("2026-10-23T12:15:00Z").plusSeconds(i * RainSlot.SLOT_SECONDS), v)
    }

    @Test
    fun `no slots means unavailable`() {
        assertEquals(RainNowcast.Unavailable, RainNowcaster.summarize(emptyList(), now))
    }

    @Test
    fun `slots entirely in the past mean unavailable`() {
        val old = listOf(RainSlot(Instant.parse("2026-10-23T11:00:00Z"), 1.0))
        assertEquals(RainNowcast.Unavailable, RainNowcaster.summarize(old, now))
    }

    @Test
    fun `dry hour`() {
        assertEquals(RainNowcast.Dry, RainNowcaster.summarize(slots(0.0, 0.0, 0.0, 0.0, 0.0), now))
    }

    @Test
    fun `rain beyond the one hour window is ignored`() {
        // Slots end 12:15..13:15; the 13:15 slot starts at 13:00 (inside), the 13:30 one starts at 13:15 (outside).
        assertEquals(RainNowcast.Dry, RainNowcaster.summarize(slots(0.0, 0.0, 0.0, 0.0, 0.0, 2.0), now))
    }

    @Test
    fun `rain starting later is reported with minutes from now`() {
        // First rainy slot is 12:30-12:45, which starts 23 minutes after 12:07.
        val result = RainNowcaster.summarize(slots(0.0, 0.0, 0.7, 0.0), now)
        assertEquals(RainNowcast.RainStarting(RainIntensity.Moderate, startsInMinutes = 23), result)
    }

    @Test
    fun `raining now and stopping`() {
        // Slot 12:00-12:15 rains, 12:15-12:30 is dry: stops in 8 minutes.
        val result = RainNowcaster.summarize(slots(0.3, 0.0, 0.0, 0.0), now)
        assertEquals(RainNowcast.RainingNow(RainIntensity.Light, stopsInMinutes = 8), result)
    }

    @Test
    fun `raining throughout the window never stops`() {
        val result = RainNowcaster.summarize(slots(0.1, 0.1, 0.1, 0.1, 0.1), now)
        assertEquals(RainNowcast.RainingNow(RainIntensity.Light, stopsInMinutes = null), result)
    }

    @Test
    fun `below threshold counts as dry`() {
        assertEquals(RainNowcast.Dry, RainNowcaster.summarize(slots(0.05, 0.09, 0.0, 0.0), now))
    }

    @Test
    fun `intensity uses mm per hour bands`() {
        assertEquals(RainIntensity.Light, RainNowcaster.intensity(0.6))   // 2.4 mm/h
        assertEquals(RainIntensity.Moderate, RainNowcaster.intensity(0.7)) // 2.8 mm/h
        assertEquals(RainIntensity.Moderate, RainNowcaster.intensity(1.8)) // 7.2 mm/h
        assertEquals(RainIntensity.Heavy, RainNowcaster.intensity(2.0))    // 8 mm/h
    }
}
