package dev.r2d8.weather.domain

/**
 * What the weather looks like, used to pick an icon. [severity] orders kinds from harmless to
 * dangerous, to choose one condition to represent a stretch of hours.
 */
enum class ConditionKind(val severity: Int) {
    Clear(0),
    PartlyCloudy(1),
    Cloudy(2),
    Fog(3),
    /** Display only (no weather code has it): dry main forecast, but a high rain chance. */
    PossibleRain(4),
    Drizzle(5),
    Rain(6),
    /** Display only: set by [withPhase] when rain and snow fall together (Open-Meteo has no such code). */
    RainAndSnow(7),
    Snow(8),
    FreezingRain(9),
    HeavyRain(10),
    Thunderstorm(11),
}

/** A WMO weather interpretation code (as used by Open-Meteo) with its English text. */
enum class WeatherCondition(val code: Int, val label: String, val kind: ConditionKind) {
    ClearSky(0, "Clear sky", ConditionKind.Clear),
    MainlyClear(1, "Mainly clear", ConditionKind.Clear),
    PartlyCloudy(2, "Partly cloudy", ConditionKind.PartlyCloudy),
    Overcast(3, "Overcast", ConditionKind.Cloudy),
    Fog(45, "Fog", ConditionKind.Fog),
    RimeFog(48, "Freezing fog", ConditionKind.Fog),
    LightDrizzle(51, "Light drizzle", ConditionKind.Drizzle),
    Drizzle(53, "Drizzle", ConditionKind.Drizzle),
    DenseDrizzle(55, "Dense drizzle", ConditionKind.Drizzle),
    LightFreezingDrizzle(56, "Light freezing drizzle", ConditionKind.FreezingRain),
    FreezingDrizzle(57, "Freezing drizzle", ConditionKind.FreezingRain),
    LightRain(61, "Light rain", ConditionKind.Rain),
    Rain(63, "Rain", ConditionKind.Rain),
    HeavyRain(65, "Heavy rain", ConditionKind.HeavyRain),
    LightFreezingRain(66, "Light freezing rain", ConditionKind.FreezingRain),
    FreezingRain(67, "Freezing rain", ConditionKind.FreezingRain),
    // 68, 69, 83 and 84 are WMO codes Open-Meteo never sends; only withPhase (PrecipitationPhase.kt) sets them.
    LightRainAndSnow(68, "Light rain and snow", ConditionKind.RainAndSnow),
    RainAndSnow(69, "Rain and snow", ConditionKind.RainAndSnow),
    LightSnow(71, "Light snow", ConditionKind.Snow),
    Snow(73, "Snow", ConditionKind.Snow),
    HeavySnow(75, "Heavy snow", ConditionKind.Snow),
    SnowGrains(77, "Snow grains", ConditionKind.Snow),
    LightShowers(80, "Light rain showers", ConditionKind.Rain),
    Showers(81, "Rain showers", ConditionKind.Rain),
    HeavyShowers(82, "Heavy rain showers", ConditionKind.HeavyRain),
    LightRainAndSnowShowers(83, "Light rain and snow showers", ConditionKind.RainAndSnow),
    RainAndSnowShowers(84, "Rain and snow showers", ConditionKind.RainAndSnow),
    LightSnowShowers(85, "Light snow showers", ConditionKind.Snow),
    HeavySnowShowers(86, "Heavy snow showers", ConditionKind.Snow),
    Thunderstorm(95, "Thunderstorm", ConditionKind.Thunderstorm),
    ThunderstormHail(96, "Thunderstorm with hail", ConditionKind.Thunderstorm),
    ThunderstormHeavyHail(99, "Thunderstorm with heavy hail", ConditionKind.Thunderstorm),
    Unknown(-1, "Unknown", ConditionKind.Cloudy);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int?): WeatherCondition = byCode[code] ?: Unknown
    }
}

/** From this precipitation probability a dry-looking condition is shown as "chance of rain". */
const val RAIN_LIKELY_PERCENT = 50

private val dryButCloudyKinds = setOf(ConditionKind.PartlyCloudy, ConditionKind.Cloudy, ConditionKind.Fog)

/** Whether [rainChance] is high enough that a cloudy or foggy [this] should be presented as possible rain. */
fun WeatherCondition.rainLikelyDespiteCode(rainChance: Int?): Boolean =
    kind in dryButCloudyKinds && rainChance != null && rainChance >= RAIN_LIKELY_PERCENT

/**
 * The kind to draw. The weather code and the amount come from one detailed model, while the rain
 * probability is the share of about 30 coarser ensemble runs with at least 0.1 mm. So the
 * probability can be high while the code still says "overcast" and 0 mm: usually "very likely a
 * few drops, timing uncertain". A plain cloud next to "92%" looks wrong and a rain icon promises
 * rain the forecast does not have, so a cloud with one drop ("possible rain") is used. Clear
 * skies are left alone.
 */
fun WeatherCondition.displayKind(rainChance: Int?): ConditionKind =
    if (rainLikelyDespiteCode(rainChance)) ConditionKind.PossibleRain else kind

/** The text matching [displayKind]. */
fun WeatherCondition.displayLabel(rainChance: Int?): String =
    if (rainLikelyDespiteCode(rainChance)) "Chance of light rain" else label
