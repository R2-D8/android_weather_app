package dev.r2d8.weather.data

import dev.r2d8.weather.data.local.CachedWeather
import dev.r2d8.weather.data.local.CachedWeatherDao
import dev.r2d8.weather.data.remote.AirQualityApi
import dev.r2d8.weather.data.remote.AirQualityResponse
import dev.r2d8.weather.data.remote.ForecastApi
import dev.r2d8.weather.data.remote.ForecastResponse
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.WeatherReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Single source of truth for weather: the Room cache is what the UI observes, and [refresh]
 * downloads new data into it. Offline, the UI keeps showing the last cached report.
 */
class WeatherRepository(
    private val forecastApi: ForecastApi,
    private val airQualityApi: AirQualityApi,
    private val dao: CachedWeatherDao,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Emits the cached report for [place] (null if none yet) and again after every refresh. */
    fun observe(place: Place): Flow<WeatherReport?> =
        dao.observe(cacheKey(place)).map { it?.toReportOrNull() }.flowOn(Dispatchers.Default)

    /**
     * Downloads new data unless the cache is younger than [maxAge]. A failed air-quality request
     * does not fail the refresh; it keeps the previously cached air quality instead.
     */
    suspend fun refresh(place: Place, maxAge: Duration = Duration.ZERO): Result<Unit> {
        val key = cacheKey(place)
        val cached = dao.get(key)
        if (cached != null && Duration.between(Instant.ofEpochMilli(cached.fetchedAtEpochMs), clock.instant()) < maxAge) {
            return Result.success(Unit)
        }
        return runCatchingCancellable {
            val (forecast, air) = coroutineScope {
                // Privacy: the server only ever sees coordinates rounded to ~1 km, never the exact GPS fix.
                val lat = roundCoordinate(place.latitude)
                val lon = roundCoordinate(place.longitude)
                val forecast = async { forecastApi.forecast(lat, lon) }
                val air = async { runCatchingCancellable { airQualityApi.airQuality(lat, lon) }.getOrNull() }
                forecast.await() to air.await()
            }
            dao.upsert(
                CachedWeather(
                    key = key,
                    forecastJson = json.encodeToString(ForecastResponse.serializer(), forecast),
                    airQualityJson = air?.let { json.encodeToString(AirQualityResponse.serializer(), it) }
                        ?: cached?.airQualityJson,
                    fetchedAtEpochMs = clock.millis(),
                ),
            )
        }
    }

    private fun CachedWeather.toReportOrNull(): WeatherReport? = try {
        val forecast = json.decodeFromString(ForecastResponse.serializer(), forecastJson)
        val air = airQualityJson?.let { json.decodeFromString(AirQualityResponse.serializer(), it) }
        forecast.toReport(air, Instant.ofEpochMilli(fetchedAtEpochMs))
    } catch (_: SerializationException) {
        null // Cache written by an incompatible version: treat as empty, the next refresh replaces it.
    }

    companion object {
        /** Two decimals of a degree is about 1.1 km, finer than the weather models resolve. */
        fun roundCoordinate(value: Double): Double = Math.round(value * 100) / 100.0

        /** Coordinates rounded to ~1 km so GPS jitter reuses the same cache row. */
        fun cacheKey(place: Place): String =
            String.format(Locale.ROOT, "%.2f,%.2f", place.latitude, place.longitude)
    }
}
