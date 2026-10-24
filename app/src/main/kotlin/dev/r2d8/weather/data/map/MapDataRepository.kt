package dev.r2d8.weather.data.map

import dev.r2d8.weather.data.runCatchingCancellable
import dev.r2d8.weather.domain.MapStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What the app knows about its pinned map data. */
data class MapDataState(val version: MapDataVersion?, val pinnedAt: Instant?)

/**
 * Keeps the map styles the app shows, each pinned to one version of OpenFreeMap's map data (see
 * [StylePinning]), so tile addresses stay the same between visits and cached or offline tiles keep
 * being used.
 *
 * The map and the offline packs load a style from a fixed address ([styleUrl]) that never reaches
 * the network: [interceptor] answers it from the pinned copy on disk. So the style address stays the
 * same when the version changes, and the style is there without a connection.
 *
 * A newer version is fetched only when the app is opened on Wi-Fi and the pinned one is older than
 * [REFRESH_AFTER]. If the pinned version disappears from OpenFreeMap, it is replaced at once on any
 * connection, because otherwise the map would be empty.
 */
class MapDataRepository(
    private val http: OkHttpClient,
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val tileJsonUrl: String = TILE_JSON_URL,
    private val sourceStyleUrl: (MapStyle) -> String = { it.url },
) {
    @Serializable
    private data class Pin(val tiles: String, val minZoom: Int, val maxZoom: Int, val attribution: String, val pinnedAtMs: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val pinFile get() = File(directory, "pin.json")
    private fun styleFile(style: MapStyle) = File(directory, "style_${style.name.lowercase()}.json")

    private val _state = MutableStateFlow(MapDataState(null, null))
    val state: StateFlow<MapDataState> = _state

    /** Last time the pinned version was checked to still exist (once a day is enough). */
    private var lastAliveCheck: Instant? = null

    /**
     * Makes sure [style] is on disk, pinned to the current version, downloading what is missing,
     * and returns its [styleUrl]. Fails only if there is no copy yet and no connection.
     */
    suspend fun ensureStyle(style: MapStyle): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val pin = loadPin() ?: newPin()
                val file = styleFile(style)
                val current = if (file.exists()) runCatching { StylePinning.pinnedVersionName(file.readText()) }.getOrNull() else null
                if (current != pin.version().name) {
                    writeAtomically(file, StylePinning.pin(get(sourceStyleUrl(style)), pin.version()))
                }
                styleUrl(style)
            }
        }
    }

    /**
     * Called when the app comes to the foreground. On Wi-Fi ([unmetered]) a pin older than
     * [REFRESH_AFTER] is moved to the newest version. On any connection, at most once a day, the
     * pinned version is checked to still exist (one tiny request). Returns true if the version changed.
     */
    suspend fun refreshIfDue(unmetered: Boolean): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pin = loadPin() ?: return@withContext false
            val age = Duration.between(Instant.ofEpochMilli(pin.pinnedAtMs), clock.instant())
            val due = unmetered && age >= REFRESH_AFTER
            val gone = !due && isAliveCheckDue() && !runCatchingCancellable { stillExists(pin) }.getOrDefault(true)
            if (!due && !gone) return@withContext false
            val fresh = runCatchingCancellable { newPin() }.getOrNull() ?: return@withContext false
            fresh.version().name != pin.version().name
        }
    }

    /** Reads the stored pin (if any) into [state]. */
    suspend fun load() {
        mutex.withLock { withContext(Dispatchers.IO) { loadPin() } }
    }

    /** Answers [styleUrl] requests from disk; everything else goes to the network unchanged. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != STYLE_HOST) return@Interceptor chain.proceed(request)
        val name = request.url.pathSegments.lastOrNull().orEmpty().removeSuffix(".json")
        val file = MapStyle.entries.firstOrNull { it.name.lowercase() == name }?.let(::styleFile)
        val body = file?.takeIf { it.exists() }?.readBytes()
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404)
            .message(if (body != null) "OK" else "Not Found")
            // MapLibre also caches the style; "no-cache" makes it ask again, so a new pin is seen.
            .header("Cache-Control", "no-cache")
            .body((body ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun loadPin(): Pin? {
        val pin = runCatching { json.decodeFromString(Pin.serializer(), pinFile.readText()) }.getOrNull()
        if (pin != null) _state.value = MapDataState(pin.version(), Instant.ofEpochMilli(pin.pinnedAtMs))
        return pin
    }

    private fun newPin(): Pin {
        val version = StylePinning.parseTileJson(get(tileJsonUrl))
        val pin = Pin(version.tiles, version.minZoom, version.maxZoom, version.attribution, clock.millis())
        writeAtomically(pinFile, json.encodeToString(Pin.serializer(), pin))
        _state.value = MapDataState(version, Instant.ofEpochMilli(pin.pinnedAtMs))
        return pin
    }

    private fun isAliveCheckDue(): Boolean {
        val last = lastAliveCheck
        val now = clock.instant()
        if (last != null && Duration.between(last, now) < Duration.ofDays(1)) return false
        lastAliveCheck = now
        return true
    }

    /** One small request for the world tile of the pinned version. */
    private fun stillExists(pin: Pin): Boolean {
        val url = pin.tiles.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")
        http.newCall(Request.Builder().url(url).head().build()).execute().use { return it.code != 404 && it.code != 410 }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body.string()
        }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun Pin.version() = MapDataVersion(tiles, minZoom, maxZoom, attribution)

    companion object {
        const val TILE_JSON_URL = "https://tiles.openfreemap.org/planet"
        /** How long a map data version is kept before moving to a newer one (on Wi-Fi). */
        val REFRESH_AFTER: Duration = Duration.ofDays(30)
        /** A reserved name (".invalid" never resolves), so these requests can only be answered locally. */
        const val STYLE_HOST = "pinned-style.invalid"

        fun styleUrl(style: MapStyle) = "https://$STYLE_HOST/${style.name.lowercase()}.json"
    }
}
