package dev.r2d8.weather.data

import dev.r2d8.weather.data.local.CachedWeather
import dev.r2d8.weather.data.local.CachedWeatherDao
import dev.r2d8.weather.data.remote.OpenMeteoClient
import dev.r2d8.weather.data.remote.OpenMeteoUrls
import dev.r2d8.weather.domain.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private class FakeDao : CachedWeatherDao {
    val rows = MutableStateFlow<Map<String, CachedWeather>>(emptyMap())
    override fun observe(key: String): Flow<CachedWeather?> = rows.map { it[key] }
    override suspend fun get(key: String): CachedWeather? = rows.value[key]
    override suspend fun upsert(entry: CachedWeather) {
        rows.value = rows.value + (entry.key to entry)
    }
}

class WeatherRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private val dao = FakeDao()
    private var now = Instant.parse("2026-10-23T22:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private var forecastStatus = 200
    private var airStatus = 200
    private val requests = mutableListOf<String>()

    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResource(name)).readText()

    private lateinit var repository: WeatherRepository

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path.orEmpty()
                return when {
                    request.path.orEmpty().startsWith("/v1/forecast") ->
                        if (forecastStatus == 200) MockResponse().setBody(resource("forecast_rome.json")) else MockResponse().setResponseCode(forecastStatus)
                    request.path.orEmpty().startsWith("/v1/air-quality") ->
                        if (airStatus == 200) MockResponse().setBody(resource("air_quality_rome.json")) else MockResponse().setResponseCode(airStatus)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val base = server.url("/").toString()
        val client = OpenMeteoClient(OkHttpClient(), json, OpenMeteoUrls(base, base, base))
        repository = WeatherRepository(client.forecast, client.airQuality, dao, json, clock)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `refresh downloads and caches, observe emits the report`() = runTest {
        assertNull(repository.observe(Place.Rome).first())

        assertTrue(repository.refresh(Place.Rome).isSuccess)

        val report = checkNotNull(repository.observe(Place.Rome).first())
        assertEquals(17.2, report.current.temperature!!, 0.0)
        assertEquals(now, report.fetchedAt)
        assertNotNull(report.airQuality)
    }

    @Test
    fun `requests ask for unix time and automatic timezone`() = runTest {
        repository.refresh(Place.Rome)

        val forecast = requests.first { it.startsWith("/v1/forecast") }
        assertTrue(forecast, "timeformat=unixtime" in forecast && "timezone=auto" in forecast)
        assertTrue(forecast, "latitude=41.89" in forecast && "longitude=12.48" in forecast)
    }

    @Test
    fun `only coordinates rounded to two decimals are sent`() = runTest {
        repository.refresh(Place("Somewhere", 60.451234, 22.266789))

        val sent = requests.filter { it.startsWith("/v1/") }
        assertTrue(sent.toString(), sent.size == 2 && sent.all { "latitude=60.45&" in it && "longitude=22.27&" in it })
    }

    @Test
    fun `fresh cache is not downloaded again, stale cache is`() = runTest {
        repository.refresh(Place.Rome)
        val afterFirst = requests.size

        now = now.plus(Duration.ofMinutes(5))
        repository.refresh(Place.Rome, maxAge = Duration.ofMinutes(10))
        assertEquals(afterFirst, requests.size)

        now = now.plus(Duration.ofMinutes(10))
        repository.refresh(Place.Rome, maxAge = Duration.ofMinutes(10))
        assertTrue(requests.size > afterFirst)
    }

    @Test
    fun `failed forecast fails the refresh and keeps the old cache`() = runTest {
        repository.refresh(Place.Rome)
        forecastStatus = 500

        val result = repository.refresh(Place.Rome)

        assertTrue(result.isFailure)
        assertNotNull(repository.observe(Place.Rome).first())
    }

    @Test
    fun `failed air quality does not fail the refresh and keeps cached air quality`() = runTest {
        repository.refresh(Place.Rome)
        airStatus = 500
        now = now.plus(Duration.ofHours(1))

        assertTrue(repository.refresh(Place.Rome).isSuccess)

        val report = checkNotNull(repository.observe(Place.Rome).first())
        assertEquals(now, report.fetchedAt)
        assertNotNull(report.airQuality)
    }

    @Test
    fun `failed air quality on first load gives a report without air quality`() = runTest {
        airStatus = 500

        assertTrue(repository.refresh(Place.Rome).isSuccess)

        assertNull(checkNotNull(repository.observe(Place.Rome).first()).airQuality)
    }

    @Test
    fun `unreadable cached json is treated as empty`() = runTest {
        dao.upsert(CachedWeather(WeatherRepository.cacheKey(Place.Rome), "{not json", null, 0))

        assertNull(repository.observe(Place.Rome).first())
    }
}
