package dev.r2d8.weather.data.radar

import dev.r2d8.weather.domain.AppSettings
import dev.r2d8.weather.domain.Place
import dev.r2d8.weather.domain.RadarPalette
import dev.r2d8.weather.domain.RadarSourceKind
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Instant

private const val NEWEST = 1_792_791_900L

class FakeRainViewer(private val frames: Int = 13, var fail: Boolean = false) : RainViewerApi {
    var calls = 0
    override suspend fun maps(): RainViewerMaps {
        calls++
        if (fail) throw IOException("offline")
        // 10-minute steps, oldest first like the real API
        return RainViewerMaps(
            host = "https://tiles.example",
            radar = RainViewerRadar((frames - 1 downTo 0).map { RainViewerFrame(NEWEST - it * 600L, "/v2/radar/$it") }),
        )
    }
}

class FakeServer(
    var covered: Boolean = true,
    var frameIds: List<Long> = (36 downTo 0).map { NEWEST - it * 300L },
    var forecastBase: Long? = null,
) : HomeServerApi {
    var calls = 0
    var failure: Throwable? = null
    var healthFailure: Throwable? = null

    override suspend fun frames(): ServerFrames {
        calls++
        failure?.let { throw it }
        val forecast = forecastBase?.let { base -> listOf(10, 20, 30).map { ServerForecastFrame(it, base + it * 60L) } }
        return ServerFrames(frameIds.map { ServerFrame(it) }, forecastBase = forecastBase, forecast = forecast.orEmpty())
    }

    override suspend fun coverage(latitude: Double, longitude: Double): ServerCoverage {
        calls++
        failure?.let { throw it }
        return ServerCoverage(covered)
    }

    override suspend fun palettes(): ServerPalettes {
        calls++
        return ServerPalettes(
            listOf(ServerPalette("classic", listOf(ServerBand(0.1, "#aae1ff", 170), ServerBand(1.0, "#328cff", 220)))),
        )
    }

    override suspend fun health(): ServerHealth {
        calls++
        healthFailure?.let { throw it }
        return ServerHealth("ok", 73)
    }
}

class RadarRepositoryTest {
    private val rainViewer = FakeRainViewer()
    private val server = FakeServer()
    private val requestedBases = mutableListOf<String>()
    private val repository = RadarRepository(rainViewer) { base ->
        requestedBases += base
        server
    }
    private val place = Place("Rome", 41.89, 12.48)
    private val serverOn = AppSettings(homeServerEnabled = true, homeServerAddress = "100.95.20.125:8080")

    @Test
    fun `by default RainViewer is used and the server is never contacted`() = runTest {
        val layers = repository.load(place, AppSettings()).getOrThrow()

        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertEquals(13, layers.frames.size)
        assertEquals(Instant.ofEpochSecond(NEWEST), layers.newest.time)
        assertEquals("https://tiles.example/v2/radar/0/256/{z}/{x}/{y}/2/1_1.png", layers.newest.tileUrl)
        assertEquals(RadarRepository.RAINVIEWER_MAX_ZOOM, layers.maxZoom)
        assertNull(layers.notice)
        assertEquals("no server request may be made", 0, server.calls)
        assertTrue(requestedBases.isEmpty())
    }

    @Test
    fun `an address without the switch is ignored`() = runTest {
        repository.load(place, AppSettings(homeServerEnabled = false, homeServerAddress = "100.95.20.125:8080"))
        assertEquals(0, server.calls)
        assertTrue(requestedBases.isEmpty())
    }

    @Test
    fun `the switch without an address is ignored`() = runTest {
        val layers = repository.load(place, AppSettings(homeServerEnabled = true, homeServerAddress = "  ")).getOrThrow()
        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertEquals(0, server.calls)
        assertNull(layers.notice)
    }

    @Test
    fun `a covered place uses the server with its palette and legend`() = runTest {
        val layers = repository.load(place, serverOn.copy(radarPalette = RadarPalette.Classic)).getOrThrow()

        assertEquals(RadarSourceKind.HomeServer, layers.source)
        assertEquals(listOf("http://100.95.20.125:8080/"), requestedBases.distinct())
        assertEquals(13, layers.frames.size) // 2 hours, every 10 minutes
        assertEquals("http://100.95.20.125:8080/tiles/$NEWEST/classic/{z}/{x}/{y}.png", layers.newest.tileUrl)
        assertEquals(NEWEST - 7200, layers.frames.first().time.epochSecond)
        assertEquals(8, layers.maxZoom)
        assertEquals(256, layers.tileSize)
        assertEquals(2, layers.legend!!.size)
        assertEquals(0xAAAAE1FF.toInt(), layers.legend!!.first().colorArgb)
        assertNull(layers.notice)
        assertEquals(0, rainViewer.calls)
    }

    @Test
    fun `the chosen palette is part of the tile urls`() = runTest {
        val layers = repository.load(place, serverOn.copy(radarPalette = RadarPalette.Blues)).getOrThrow()
        assertTrue(layers.newest.tileUrl.contains("/blues/"))
    }

    @Test
    fun `a place the server has no data for falls back to RainViewer with a notice`() = runTest {
        server.covered = false

        val layers = repository.load(Place("Turku", 60.45, 22.27), serverOn).getOrThrow()

        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertTrue(layers.notice!!.contains("no radar for this place"))
    }

    @Test
    fun `an unreachable server falls back to RainViewer with a notice`() = runTest {
        server.failure = IOException("timeout")

        val layers = repository.load(place, serverOn).getOrThrow()

        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertTrue(layers.notice!!.contains("not reachable"))
    }

    @Test
    fun `a server without frames falls back to RainViewer`() = runTest {
        server.frameIds = emptyList()
        val layers = repository.load(place, serverOn).getOrThrow()
        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertTrue(layers.notice!!.contains("no frames"))
    }

    @Test
    fun `an unusable address never reaches the network`() = runTest {
        val layers = repository.load(place, serverOn.copy(homeServerAddress = "http://example.com")).getOrThrow()

        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertTrue(layers.notice!!.contains("Plain http"))
        assertTrue(requestedBases.isEmpty())
        assertEquals(0, server.calls)
    }

    @Test
    fun `if RainViewer fails there is no radar`() = runTest {
        rainViewer.fail = true
        assertTrue(repository.load(place, AppSettings()).isFailure)
    }

    @Test
    fun `RainViewer failing does not hide a working server`() = runTest {
        rainViewer.fail = true
        assertEquals(RadarSourceKind.HomeServer, repository.load(place, serverOn).getOrThrow().source)
    }

    @Test
    fun `pickFrames takes the newest and every ten minutes back over two hours`() {
        val ids = (36 downTo 0).map { NEWEST - it * 300L } // 3 hours of 5-minute frames
        val picked = RadarRepository.pickFrames(ids)
        assertEquals(13, picked.size)
        assertEquals(NEWEST, picked.last())
        assertEquals(NEWEST - 7200, picked.first())
        assertTrue(picked.zipWithNext().all { (a, b) -> b - a == 600L })
    }

    @Test
    fun `pickFrames copes with gaps and short histories`() {
        assertEquals(listOf(NEWEST), RadarRepository.pickFrames(listOf(NEWEST)))
        assertEquals(emptyList<Long>(), RadarRepository.pickFrames(emptyList()))
        // the frames at -600 and -1200 are missing: the neighbour at -900 stands in for them (once)
        val withGap = listOf(NEWEST - 900, NEWEST - 300, NEWEST)
        assertEquals(listOf(NEWEST - 900, NEWEST), RadarRepository.pickFrames(withGap))
        // a frame more than 5 minutes from any target is skipped, not substituted
        assertEquals(listOf(NEWEST), RadarRepository.pickFrames(listOf(NEWEST, NEWEST - 20_000)))
    }

    @Test
    fun `test connection reports success with the number of frames`() = runTest {
        assertEquals(ServerTestResult.Ok(73), repository.testServer("100.95.20.125:8080"))
    }

    @Test
    fun `test connection explains the problems`() = runTest {
        server.healthFailure = HttpException(Response.error<Any>(503, "".toResponseBody()))
        val notReady = repository.testServer("100.95.20.125:8080") as ServerTestResult.Problem
        assertTrue(notReady.message.contains("not ready"))

        server.healthFailure = IOException("unreachable")
        val unreachable = repository.testServer("100.95.20.125:8080") as ServerTestResult.Problem
        assertTrue(unreachable.message.contains("Tailscale"))

        val blank = repository.testServer("") as ServerTestResult.Problem
        assertTrue(blank.message.contains("Enter"))
        assertFalse(requestedBases.isEmpty())
    }

    @Test
    fun `the server's short forecast follows the observed frames`() = runTest {
        server.forecastBase = NEWEST

        val layers = repository.load(place, serverOn).getOrThrow()

        assertEquals(16, layers.frames.size) // 13 observed + 3 forecast
        assertEquals(listOf(null, 10, 20, 30), layers.frames.takeLast(4).map { it.forecastMinutes })
        assertEquals(NEWEST, layers.newest.time.epochSecond) // "now" is the newest observation
        assertEquals(12, layers.newestIndex)
        assertEquals("http://100.95.20.125:8080/forecast/$NEWEST/20/classic/{z}/{x}/{y}.png", layers.frames[14].tileUrl)
    }

    @Test
    fun `a forecast made from another frame is ignored`() = runTest {
        server.forecastBase = NEWEST - 300 // the frame list and the forecast do not match (race)
        val layers = repository.load(place, serverOn).getOrThrow()
        assertTrue(layers.frames.none { it.isForecast })
    }
}
