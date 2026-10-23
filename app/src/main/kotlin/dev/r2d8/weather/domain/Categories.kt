package dev.r2d8.weather.domain

/** Text bands for numeric readings. Higher [level] means worse; 0 is the best band. */
data class Band(val label: String, val level: Int)

object Categories {
    /** UV index bands as defined by the WHO. */
    fun uvIndex(uv: Double): Band = when {
        uv < 3 -> Band("Low", 0)
        uv < 6 -> Band("Moderate", 1)
        uv < 8 -> Band("High", 2)
        uv < 11 -> Band("Very high", 3)
        else -> Band("Extreme", 4)
    }

    /** European Air Quality Index bands as defined by the European Environment Agency. */
    fun europeanAqi(aqi: Int): Band = when {
        aqi <= 20 -> Band("Good", 0)
        aqi <= 40 -> Band("Fair", 1)
        aqi <= 60 -> Band("Moderate", 2)
        aqi <= 80 -> Band("Poor", 3)
        aqi <= 100 -> Band("Very poor", 4)
        else -> Band("Extremely poor", 5)
    }

    /**
     * Simplified, generic pollen bands (grains/m³). Real thresholds differ per species, so
     * treat the label as a rough guide and rely on the number for details.
     */
    fun pollen(grainsPerCubicMetre: Double): Band = when {
        grainsPerCubicMetre < 1 -> Band("None", 0)
        grainsPerCubicMetre < 10 -> Band("Low", 1)
        grainsPerCubicMetre < 50 -> Band("Moderate", 2)
        grainsPerCubicMetre < 200 -> Band("High", 3)
        else -> Band("Very high", 4)
    }

    private val compass = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** Compass point for a meteorological wind direction (the direction the wind blows **from**). */
    fun compassPoint(degrees: Int): String = compass[(((degrees % 360) + 360) % 360 * 16 + 180) / 360 % 16]
}
