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

    /** Whether the server offers snow colours (it has the model snow line). */
    var snow = false
    /** Model rain hours (end of each hour, epoch seconds); empty means the server has none. */
    var modelHours: List<Long> = emptyList()
    var modelFailure: Throwable? = null

    override suspend fun palettes(): ServerPalettes {
        calls++
        return ServerPalettes(
            listOf(ServerPalette("classic", listOf(ServerBand(0.1, "#aae1ff", 170), ServerBand(1.0, "#328cff", 220)))),
            snowSuffix = if (snow) "-snow" else null,
            mixed = if (snow) listOf(ServerBand(0.1, "#ffc8e6", 190)) else emptyList(),
            snow = if (snow) listOf(ServerBand(0.1, "#f0f0ff", 190), ServerBand(0.3, "#d2cdff", 210)) else emptyList(),
        )
    }

    override suspend fun model(): ServerModel {
        calls++
        modelFailure?.let { throw it }
        return ServerModel(hours = modelHours.shuffled().map { ServerModelHour(it, "100-200") })
    }

    override suspend fun health(): ServerHealth {
        calls++
        healthFailure?.let { throw it }
        return ServerHealth("ok", 73)
    }

    /** Hours from 3 h ago to 3 h ahead of [temperatureNow]; those before the run (2 h ago) from an older run. */
    var temperatureNow = 1_793_664_000L
    var temperatureHours: List<ServerTemperatureHour>? = null
    var temperatureFailure: Throwable? = null
    val pointRequests = mutableListOf<Triple<Double, Double, Long>>()

    override suspend fun temperature(): ServerTemperature {
        calls++
        temperatureFailure?.let { throw it }
        val run = temperatureNow - 2 * 3600
        val hours = temperatureHours ?: (-3..3).map { k ->
            val id = temperatureNow + k * 3600L
            ServerTemperatureHour(id, if (id < run) run - 6 * 3600 else run)
        }
        return ServerTemperature(
            hours = hours.shuffled(),
            now = temperatureNow,
            bands = listOf(ServerTemperatureBand(-30.0, "#3c005a"), ServerTemperatureBand(-28.0, "#4a0a6b")),
        )
    }

    override suspend fun temperaturePoint(latitude: Double, longitude: Double, hourId: Long): ServerTemperaturePoint {
        calls++
        temperatureFailure?.let { throw it }
        pointRequests += Triple(latitude, longitude, hourId)
        return ServerTemperaturePoint(temperature = 7.84 + (hourId - temperatureNow) / 3600, groundMetres = 307)
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
    fun `the notice tells whether Tailscale is off or the server is down`() = runTest {
        server.failure = IOException("timeout")
        var vpn: Boolean? = false
        val repo = RadarRepository(rainViewer, vpnActive = { vpn }) { server }

        val off = repo.load(place, serverOn).getOrThrow()
        assertEquals(RadarSourceKind.RainViewer, off.source)
        assertTrue(off.notice!!, off.notice!!.contains("Tailscale is off"))

        vpn = true
        val down = repo.load(place, serverOn).getOrThrow()
        assertTrue(down.notice!!, down.notice!!.contains("not answering"))

        server.healthFailure = IOException("unreachable")
        vpn = false
        val test = repo.testServer("100.95.20.125:8080") as ServerTestResult.Problem
        assertTrue(test.message, test.message.contains("Tailscale is off"))
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

    @Test
    fun `model rain frames follow the radar nowcast`() = runTest {
        server.forecastBase = NEWEST
        // The first model hour ends before the nowcast does, so it is left out.
        server.modelHours = (0..3).map { NEWEST + 1200 + it * 3600L }

        val layers = repository.load(place, serverOn).getOrThrow()

        assertEquals(13 + 3 + 3, layers.frames.size)
        val model = layers.frames.takeLast(3)
        assertTrue(model.all { it.isModel && it.isForecast })
        assertEquals((1..3).map { NEWEST + 1200 + it * 3600L }, model.map { it.time.epochSecond })
        assertEquals(
            "http://100.95.20.125:8080/model/100-200/${NEWEST + 4800}/classic/{z}/{x}/{y}.png",
            model.first().tileUrl,
        )
        assertEquals(RadarRepository.MODEL_ATTRIBUTION, model.first().attribution)
        assertEquals(8, model.first().maxZoom)
        assertEquals(12, layers.newestIndex) // "now" is still the newest observation
        assertEquals(RadarRepository.SERVER_HISTORY_MB + RadarRepository.MODEL_FRAMES_MB, layers.historyEstimateMb)
    }

    @Test
    fun `places without home-server radar get RainViewer then the model frames`() = runTest {
        server.covered = false
        server.modelHours = listOf(NEWEST + 3600L, NEWEST + 7200L)

        val layers = repository.load(Place("Turku", 60.45, 22.27), serverOn).getOrThrow()

        assertEquals(RadarSourceKind.RainViewer, layers.source)
        assertEquals(13 + 2, layers.frames.size)
        assertEquals(7, layers.maxZoom)
        assertEquals(8, layers.frames.last().maxZoom)
        assertTrue(layers.frames.last().tileUrl.startsWith("http://100.95.20.125:8080/model/"))
        assertEquals("the server legend describes the model frames", 2, layers.legend!!.size)
    }

    @Test
    fun `without model frames RainViewer keeps no legend, and a failing model list changes nothing`() = runTest {
        server.covered = false
        val turku = Place("Turku", 60.45, 22.27)
        assertNull(repository.load(turku, serverOn).getOrThrow().legend)

        server.covered = true
        server.modelFailure = IOException("timeout")
        val layers = repository.load(place, serverOn).getOrThrow()
        assertEquals(RadarSourceKind.HomeServer, layers.source)
        assertTrue(layers.frames.none { it.isModel })
    }

    @Test
    fun `snow colours are asked for when the server offers them`() = runTest {
        server.snow = true
        server.forecastBase = NEWEST
        server.modelHours = listOf(NEWEST + 3600L)

        val layers = repository.load(place, serverOn).getOrThrow()

        assertTrue(layers.frames.all { it.tileUrl.contains("/classic-snow/") })
        assertEquals(2, layers.snowLegend.size)
        assertEquals(0xBEF0F0FF.toInt(), layers.snowLegend.first().colorArgb)
        assertEquals(1, layers.mixedLegend.size)
    }

    @Test
    fun `temperature tiles use the version naming both model runs`() = runTest {
        val now = server.temperatureNow
        server.temperatureHours = listOf(ServerTemperatureHour(now, now - 7200, "${now - 7200}-${now - 3600}"))
        val layers = repository.loadTemperature(serverOn).getOrThrow()
        assertTrue(layers.hours[0].tileUrl.contains("/temperature/${now - 7200}-${now - 3600}/$now/"))
    }

    @Test
    fun `temperature maps need the home server and never contact anything without it`() = runTest {
        val result = repository.loadTemperature(AppSettings())
        assertEquals(RadarRepository.NEEDS_SERVER, result.exceptionOrNull()?.message)
        assertTrue(repository.temperatureAt(AppSettings(), 41.9, 12.5, 1L).isFailure)
        assertEquals(0, server.calls)
        assertTrue(requestedBases.isEmpty())
    }

    @Test
    fun `temperature hours are sorted, open on now and carry their run in the tile url`() = runTest {
        val layers = repository.loadTemperature(serverOn).getOrThrow()
        assertEquals(7, layers.hours.size)
        assertEquals(layers.hours.sortedBy { it.id }, layers.hours)
        assertEquals(3, layers.nowIndex)
        val now = server.temperatureNow
        val run = now - 2 * 3600
        assertEquals("http://100.95.20.125:8080/temperature/$run/$now/temp2c/{z}/{x}/{y}.png", layers.hours[3].tileUrl)
        assertTrue("the oldest hour comes from the run before", layers.hours[0].tileUrl.contains("/${run - 6 * 3600}/"))
        assertEquals(2, layers.legend.size)
        assertEquals(0xFF3C005A.toInt(), layers.legend[0].colorArgb)
    }

    @Test
    fun `without the current hour the closest one is chosen`() = runTest {
        val now = server.temperatureNow
        server.temperatureHours = listOf(now - 7200, now + 3600, now + 7200).map { ServerTemperatureHour(it, now - 10_800) }
        assertEquals(1, repository.loadTemperature(serverOn).getOrThrow().nowIndex)
    }

    @Test
    fun `temperature problems are explained`() = runTest {
        server.temperatureHours = emptyList()
        assertTrue(repository.loadTemperature(serverOn).exceptionOrNull()!!.message!!.contains("still collecting"))

        server.temperatureFailure = HttpException(Response.error<Any>(404, "".toResponseBody()))
        assertTrue(repository.loadTemperature(serverOn).exceptionOrNull()!!.message!!.contains("update it"))

        val tailscaleOff = RadarRepository(rainViewer, vpnActive = { false }) { server }
        server.temperatureFailure = IOException("timeout")
        assertTrue(tailscaleOff.loadTemperature(serverOn).exceptionOrNull()!!.message!!.contains("Tailscale is off"))
    }

    @Test
    fun `the tapped point is asked for the chosen hour`() = runTest {
        val value = repository.temperatureAt(serverOn, 46.17, 9.87, server.temperatureNow).getOrThrow()
        assertEquals(7.84, value.celsius!!, 0.001)
        assertEquals(307, value.groundMetres)
        assertEquals(Triple(46.17, 9.87, server.temperatureNow), server.pointRequests.single())
    }
}
