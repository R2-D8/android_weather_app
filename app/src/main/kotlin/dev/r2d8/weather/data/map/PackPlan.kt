package dev.r2d8.weather.data.map

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.tan

/** A box in degrees. */
data class GeoBox(val south: Double, val west: Double, val north: Double, val east: Double)

/** What one offline pack covers. */
data class PackPlan(val box: GeoBox, val minZoom: Int, val maxZoom: Int, val tileCount: Long)

/**
 * Stored with each MapLibre offline pack (its "metadata" bytes), so the app can tell which place
 * and which map data version it belongs to, and replace it when either changes.
 */
@Serializable
data class PackInfo(
    val placeId: Long,
    val latitude: Double,
    val longitude: Double,
    val version: String,
    val maxZoom: Int,
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for packs this app did not make, or an older format. */
        fun decode(bytes: ByteArray?): PackInfo? =
            bytes?.let { runCatching { json.decodeFromString(serializer(), it.decodeToString()) }.getOrNull() }
    }
}

/**
 * Offline packs per saved place, kept within OpenFreeMap's terms for offline downloads: at most
 * [MAX_TILES] tiles and [MAX_BYTES] per pack. A pack covers [RADIUS_KM] around the place from
 * zoom 0 to [MAX_ZOOM]; closer in, the map stretches zoom-12 tiles, which still shows streets.
 */
object PackPlanner {
    const val RADIUS_KM = 30.0
    const val MIN_ZOOM = 0
    const val MAX_ZOOM = 12
    const val MAX_TILES = 3_000L
    const val MAX_BYTES = 50L * 1024 * 1024
    private const val KM_PER_DEGREE_LAT = 111.32

    fun box(latitude: Double, longitude: Double, radiusKm: Double = RADIUS_KM): GeoBox {
        val dLat = radiusKm / KM_PER_DEGREE_LAT
        val dLon = radiusKm / (KM_PER_DEGREE_LAT * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
        return GeoBox(
            south = (latitude - dLat).coerceAtLeast(-85.0),
            west = (longitude - dLon).coerceAtLeast(-180.0),
            north = (latitude + dLat).coerceAtMost(85.0),
            east = (longitude + dLon).coerceAtMost(180.0),
        )
    }

    /**
     * The plan for a place, with the highest zoom up to [maxZoom] that keeps within [MAX_TILES].
     * Null only if not even zoom [MIN_ZOOM]..[MIN_ZOOM] fits, which cannot happen in practice.
     */
    fun plan(latitude: Double, longitude: Double, maxZoom: Int = MAX_ZOOM): PackPlan? {
        val box = box(latitude, longitude)
        return (maxZoom downTo MIN_ZOOM)
            .map { z -> PackPlan(box, MIN_ZOOM, z, tileCount(box, MIN_ZOOM, z)) }
            .firstOrNull { it.tileCount <= MAX_TILES }
    }

    /** Number of web-mercator tiles covering [box] at zoom levels [minZoom]..[maxZoom]. */
    fun tileCount(box: GeoBox, minZoom: Int, maxZoom: Int): Long = (minZoom..maxZoom).sumOf { z ->
        val n = 1 shl z
        val x0 = tileX(box.west, n)
        val x1 = tileX(box.east, n)
        val y0 = tileY(box.north, n)
        val y1 = tileY(box.south, n)
        (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
    }

    private fun tileX(lon: Double, n: Int) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

    private fun tileY(lat: Double, n: Int): Int {
        val rad = Math.toRadians(lat)
        return floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }
}
