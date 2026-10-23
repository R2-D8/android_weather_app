package dev.r2d8.weather.domain

import java.time.Duration
import java.time.Instant

/** What the 15-minute precipitation data says about the next hour. */
sealed interface RainNowcast {
    /** Dry for the whole look-ahead window. */
    data object Dry : RainNowcast

    /** Raining now; it stops in [stopsInMinutes] minutes, or not within the window (null). */
    data class RainingNow(val intensity: RainIntensity, val stopsInMinutes: Int?) : RainNowcast

    /** Dry now, rain begins in [startsInMinutes] minutes. */
    data class RainStarting(val intensity: RainIntensity, val startsInMinutes: Int) : RainNowcast

    /** No 15-minute data available. */
    data object Unavailable : RainNowcast
}

enum class RainIntensity(val label: String) {
    Light("light"),
    Moderate("moderate"),
    Heavy("heavy"),
}

object RainNowcaster {
    /** A 15-minute slot counts as rain from this amount (about 0.4 mm/h). */
    const val RAIN_THRESHOLD_MM = 0.1

    private val LOOK_AHEAD: Duration = Duration.ofMinutes(60)

    fun intensity(millimetresPer15Min: Double): RainIntensity {
        val perHour = millimetresPer15Min * 4
        return when {
            perHour < 2.5 -> RainIntensity.Light
            perHour < 7.6 -> RainIntensity.Moderate
            else -> RainIntensity.Heavy
        }
    }

    fun summarize(slots: List<RainSlot>, now: Instant): RainNowcast {
        val windowEnd = now.plus(LOOK_AHEAD)
        // Slots that still matter: they end after now and start before the end of the window.
        val upcoming = slots.filter { it.end > now && it.start < windowEnd }.sortedBy { it.end }
        if (upcoming.isEmpty()) return RainNowcast.Unavailable

        fun RainSlot.isRain() = millimetres >= RAIN_THRESHOLD_MM
        fun minutesFromNow(at: Instant) = Duration.between(now, at).toMinutes().toInt().coerceAtLeast(0)

        val first = upcoming.first()
        if (first.start <= now && first.isRain()) {
            val peak = upcoming.takeWhile { it.isRain() }.maxOf { it.millimetres }
            val dryAfter = upcoming.firstOrNull { !it.isRain() && it.start > now }
            return RainNowcast.RainingNow(intensity(peak), dryAfter?.let { minutesFromNow(it.start) })
        }
        val firstRain = upcoming.firstOrNull { it.isRain() } ?: return RainNowcast.Dry
        val run = upcoming.dropWhile { it !== firstRain }.takeWhile { it.isRain() }
        return RainNowcast.RainStarting(intensity(run.maxOf { it.millimetres }), minutesFromNow(firstRain.start))
    }
}
