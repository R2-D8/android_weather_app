package dev.r2d8.weather.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Moonrise, moonset and phase, computed on the phone (Open-Meteo has no moon data).
 *
 * The Moon's position uses the low-precision series of Montenbruck and Pfleger ("Astronomy on the
 * Personal Computer", MiniMoon): about 1 arcminute, which puts rise and set within a minute or two
 * of the US Naval Observatory's times (checked in the unit tests). Rise and set are when the
 * Moon's upper edge touches the horizon, with standard refraction and the Moon's parallax, for a
 * flat horizon at sea level, the same definition almanacs use.
 */
object Moon {
    /** Rise and set on one local day; either can be missing (about once a month each). */
    data class Day(
        val rise: Instant?,
        val set: Instant?,
        /** Without a rise or set: whether the Moon stays up all day (only possible far north). */
        val upAllDay: Boolean,
    )

    data class Phase(
        /** 0 to 1, the lit fraction of the disc. */
        val illumination: Double,
        /** Moon's longitude minus the Sun's, 0 to 360 degrees: 0 new, 90 first quarter, 180 full, 270 last quarter. */
        val elongation: Double,
    ) {
        val waxing: Boolean get() = elongation < 180.0
        val name: String
            get() = when {
                near(0.0) -> "New moon"
                near(90.0) -> "First quarter"
                near(180.0) -> "Full moon"
                near(270.0) -> "Last quarter"
                elongation < 90.0 -> "Waxing crescent"
                elongation < 180.0 -> "Waxing gibbous"
                elongation < 270.0 -> "Waning gibbous"
                else -> "Waning crescent"
            }
        val percent: Int get() = (illumination * 100).roundToInt()

        // The Moon moves about 12.2 degrees a day against the Sun: the four main phases get the
        // day around their exact moment.
        private fun near(target: Double): Boolean = abs(wrap180(elongation - target)) < PHASE_HALF_WIDTH
    }

    private const val PHASE_HALF_WIDTH = 6.1
    private const val STEP_SECONDS = 600L
    private const val REFRACTION_DEG = 0.5667
    private const val SEMIDIAMETER_DEG = 0.2725 // times the parallax gives the apparent radius

    fun day(date: LocalDate, zone: ZoneId, latitude: Double, longitude: Double): Day {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        var rise: Instant? = null
        var set: Instant? = null
        var t0 = start
        var a0 = horizonAltitude(t0, latitude, longitude)
        val firstAltitude = a0
        while (t0 < end) {
            val t1 = minOf(t0.plusSeconds(STEP_SECONDS), end)
            val a1 = horizonAltitude(t1, latitude, longitude)
            if (a0 < 0 && a1 >= 0 && rise == null) rise = crossing(t0, t1, latitude, longitude)
            if (a0 >= 0 && a1 < 0 && set == null) set = crossing(t0, t1, latitude, longitude)
            t0 = t1
            a0 = a1
        }
        return Day(rise, set, upAllDay = rise == null && set == null && firstAltitude >= 0)
    }

    fun phase(time: Instant): Phase {
        val t = centuries(time)
        val moon = moonEcliptic(t)
        val sunLongitude = sunLongitude(t)
        val elongation = wrap360(Math.toDegrees(moon.longitude) - sunLongitude)
        // Angle Sun-Moon seen from Earth; the phase angle at the Moon is its supplement (the Sun is
        // ~390 times further away than the Moon, so the error is under 0.2 degrees).
        val cosPsi = cos(moon.latitude) * cos(Math.toRadians(elongation))
        return Phase(illumination = (1 - cosPsi) / 2, elongation = elongation)
    }

    /** Altitude of the Moon's upper edge above the refracted horizon, in degrees (>= 0: visible). */
    private fun horizonAltitude(time: Instant, latitude: Double, longitude: Double): Double {
        val t = centuries(time)
        val moon = moonEcliptic(t)
        val eps = Math.toRadians(23.43929111 - 0.0130042 * t)
        // Ecliptic to equatorial.
        val x = cos(moon.latitude) * cos(moon.longitude)
        val y = cos(eps) * cos(moon.latitude) * sin(moon.longitude) - sin(eps) * sin(moon.latitude)
        val z = sin(eps) * cos(moon.latitude) * sin(moon.longitude) + cos(eps) * sin(moon.latitude)
        val ra = atan2(y, x)
        val dec = atan2(z, sqrt(x * x + y * y))
        val days = julianDays(time)
        val siderealDeg = 280.46061837 + 360.98564736629 * days + longitude
        val hourAngle = Math.toRadians(siderealDeg) - ra
        val lat = Math.toRadians(latitude)
        val altitude = Math.toDegrees(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hourAngle)))
        // Seen from the surface the Moon sits lower by its parallax; refraction lifts it, and the
        // upper edge is one radius above the centre.
        val parallax = moon.parallaxDeg
        return altitude - parallax + REFRACTION_DEG + SEMIDIAMETER_DEG * parallax
    }

    /** The moment in [a, b] where the altitude changes sign, to within a few seconds. */
    private fun crossing(a: Instant, b: Instant, latitude: Double, longitude: Double): Instant {
        var lo = a
        var hi = b
        val rising = horizonAltitude(a, latitude, longitude) < 0
        while (hi.epochSecond - lo.epochSecond > 5) {
            val mid = lo.plusSeconds((hi.epochSecond - lo.epochSecond) / 2)
            val up = horizonAltitude(mid, latitude, longitude) >= 0
            if (up == rising) hi = mid else lo = mid
        }
        return lo.plusSeconds((hi.epochSecond - lo.epochSecond) / 2)
    }

    private class Ecliptic(val longitude: Double, val latitude: Double, val parallaxDeg: Double)

    /** Geocentric ecliptic longitude and latitude (radians, of date) and horizontal parallax. */
    private fun moonEcliptic(t: Double): Ecliptic {
        val l0 = frac(0.606433 + 1336.855225 * t)
        val l = 2 * PI * frac(0.374897 + 1325.552410 * t) // Moon's mean anomaly
        val ls = 2 * PI * frac(0.993133 + 99.997361 * t) // Sun's mean anomaly
        val d = 2 * PI * frac(0.827361 + 1236.853086 * t) // elongation of the mean Moon
        val f = 2 * PI * frac(0.259086 + 1342.227825 * t) // argument of latitude
        val dl = 22640 * sin(l) - 4586 * sin(l - 2 * d) + 2370 * sin(2 * d) + 769 * sin(2 * l) -
            668 * sin(ls) - 412 * sin(2 * f) - 212 * sin(2 * l - 2 * d) - 206 * sin(l + ls - 2 * d) +
            192 * sin(l + 2 * d) - 165 * sin(ls - 2 * d) - 125 * sin(d) - 110 * sin(l + ls) +
            148 * sin(l - ls) - 55 * sin(2 * f - 2 * d)
        val s = f + (dl + 412 * sin(2 * f) + 541 * sin(ls)) / ARCSEC_PER_RADIAN
        val h = f - 2 * d
        val n = -526 * sin(h) + 44 * sin(l + h) - 31 * sin(-l + h) - 23 * sin(ls + h) +
            11 * sin(-ls + h) - 25 * sin(-2 * l + f) + 21 * sin(-l + f)
        val longitude = 2 * PI * frac(l0 + dl / 1296.0e3)
        val latitude = (18520.0 * sin(s) + n) / ARCSEC_PER_RADIAN
        // Meeus, chapter 47, main terms of the distance, as parallax in degrees.
        val parallax = 0.9508 + 0.0518 * cos(l) + 0.0095 * cos(l - 2 * d) + 0.0078 * cos(2 * d) + 0.0028 * cos(2 * l)
        return Ecliptic(longitude, latitude, parallax)
    }

    /** The Sun's apparent ecliptic longitude in degrees (about 0.01 degrees). */
    private fun sunLongitude(t: Double): Double {
        val n = t * 36525.0
        val meanLongitude = 280.460 + 0.9856474 * n
        val g = Math.toRadians(357.528 + 0.9856003 * n)
        return wrap360(meanLongitude + 1.915 * sin(g) + 0.020 * sin(2 * g))
    }

    private const val ARCSEC_PER_RADIAN = 206264.8062

    /** Days since J2000.0 (2000-01-01 12:00 TT); the ~70 s TT-UT difference is ignored. */
    private fun julianDays(time: Instant): Double = (time.epochSecond - 946_728_000L) / 86_400.0

    private fun centuries(time: Instant): Double = julianDays(time) / 36525.0

    private fun frac(x: Double): Double = x - floor(x)

    private fun wrap360(deg: Double): Double = deg - 360.0 * floor(deg / 360.0)

    private fun wrap180(deg: Double): Double = wrap360(deg + 180.0) - 180.0
}
