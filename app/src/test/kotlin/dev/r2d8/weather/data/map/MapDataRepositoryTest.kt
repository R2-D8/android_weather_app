package dev.r2d8.weather.data.map

import dev.r2d8.weather.domain.MapStyle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MapDataRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
    }

    private val server = MockWebServer()
    private val clock = MutableClock(Instant.parse("2026-10-24T10:00:00Z"))
    private var version = "V1"
    private var tileStatus = 200
    private val requests = mutableListOf<String>()

    private val style = """
        {"version":8,"sources":{"openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},"layers":[]}
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(requests) { requests += "${request.method} $path" }
                return when {
                    path == "/planet" -> MockResponse().setBody(
                        """{"tiles":["${server.url("/planet/$version/")}{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14,"attribution":"OFM"}""",
                    )
                    path.startsWith("/styles/") -> MockResponse().setBody(style)
                    path.startsWith("/planet/") -> MockResponse().setResponseCode(tileStatus)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private val http = OkHttpClient()
    private val dir: File by lazy { folder.newFolder("map") }

    private fun repository() = MapDataRepository(
        http = http,
        directory = dir,
        clock = clock,
        tileJsonUrl = server.url("/planet").toString(),
        sourceStyleUrl = { server.url("/styles/${it.name.lowercase()}").toString() },
    )

    private fun pinnedVersion(style: MapStyle) = StylePinning.pinnedVersionName(File(dir, "style_${style.name.lowercase()}.json").readText())

    @Test
    fun `a style is pinned once and then served from disk`() = runTest {
        val repo = repository()
        assertEquals(MapDataRepository.styleUrl(MapStyle.Dark), repo.ensureStyle(MapStyle.Dark).getOrThrow())
        assertEquals("V1", pinnedVersion(MapStyle.Dark))
        assertEquals(listOf("GET /planet", "GET /styles/dark"), requests)

        requests.clear()
        repo.ensureStyle(MapStyle.Dark).getOrThrow()
        // A new instance (app restart) also uses the files.
        repository().ensureStyle(MapStyle.Dark).getOrThrow()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the interceptor answers the style address without the network`() = runTest {
        val repo = repository()
        repo.ensureStyle(MapStyle.Bright).getOrThrow()
        val client = OkHttpClient.Builder().addInterceptor(repo.interceptor).build()

        client.newCall(Request.Builder().url(MapDataRepository.styleUrl(MapStyle.Bright)).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("no-cache", it.header("Cache-Control"))
            assertTrue("V1" in it.body.string())
        }
        // Not pinned yet: a clear 404 rather than a network attempt.
        client.newCall(Request.Builder().url(MapDataRepository.styleUrl(MapStyle.Light)).build()).execute().use {
            assertEquals(404, it.code)
        }
    }

    @Test
    fun `newer map data is taken only on Wi-Fi and only after a month`() = runTest {
        val repo = repository()
        repo.ensureStyle(MapStyle.Detailed).getOrThrow()
        version = "V2"

        clock.now = clock.now.plus(Duration.ofDays(10))
        assertFalse(repo.refreshIfDue(unmetered = true))

        clock.now = clock.now.plus(Duration.ofDays(25))
        assertFalse(repo.refreshIfDue(unmetered = false))
        assertTrue(repo.refreshIfDue(unmetered = true))
        assertEquals("V2", repo.state.value.version?.name)

        // The style is re-pinned the next time it is used.
        repo.ensureStyle(MapStyle.Detailed).getOrThrow()
        assertEquals("V2", pinnedVersion(MapStyle.Detailed))
    }

    @Test
    fun `a pinned version that disappeared is replaced even on mobile data`() = runTest {
        val repo = repository()
        repo.ensureStyle(MapStyle.Detailed).getOrThrow()
        version = "V2"
        tileStatus = 404

        assertTrue(repo.refreshIfDue(unmetered = false))
        assertEquals("V2", repo.state.value.version?.name)
        assertTrue(requests.any { it == "HEAD /planet/V1/0/0/0.pbf" })

        // Checked at most once a day.
        requests.clear()
        repo.refreshIfDue(unmetered = false)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `without a connection and without a copy the style fails cleanly`() = runTest {
        server.shutdown()
        assertTrue(repository().ensureStyle(MapStyle.Detailed).isFailure)
    }
}
