package dev.r2d8.weather.data.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One version of OpenFreeMap's map data, as described by its TileJSON. */
data class MapDataVersion(
    /** Tile address template with the version in it, e.g. .../planet/20261018_080001_pt/{z}/{x}/{y}.pbf */
    val tiles: String,
    val minZoom: Int,
    val maxZoom: Int,
    val attribution: String,
) {
    /** The version name inside the address, e.g. "20261018_080001_pt"; used to tell versions apart. */
    val name: String get() = tiles.substringAfter("/planet/", "").substringBefore('/').ifEmpty { tiles }
}

/**
 * OpenFreeMap styles load their map data through `https://tiles.openfreemap.org/planet`, which always
 * points to the newest weekly version, so every tile address changes weekly and cached tiles are no
 * longer used. Pinning replaces that reference with one fixed version, so tiles stay valid until the
 * app decides (on Wi-Fi, about monthly) to move to a newer version.
 */
object StylePinning {
    private val json = Json { ignoreUnknownKeys = true }

    /** The map data version from OpenFreeMap's TileJSON (the body of `https://tiles.openfreemap.org/planet`). */
    fun parseTileJson(tileJson: String): MapDataVersion {
        val root = json.parseToJsonElement(tileJson).jsonObject
        val tiles = root.getValue("tiles").jsonArray.first().jsonPrimitive.content
        require("{z}" in tiles && "{x}" in tiles && "{y}" in tiles) { "not a tile template: $tiles" }
        return MapDataVersion(
            tiles = tiles,
            minZoom = root["minzoom"]?.jsonPrimitive?.intOrNull ?: 0,
            maxZoom = root["maxzoom"]?.jsonPrimitive?.int ?: 14,
            attribution = root["attribution"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /**
     * [styleJson] with every vector source that loads OpenFreeMap's "planet" TileJSON replaced by
     * the fixed [version]. Everything else (layers, fonts, icons, other sources) is left as it is.
     */
    fun pin(styleJson: String, version: MapDataVersion): String {
        val style = json.parseToJsonElement(styleJson).jsonObject
        val sources = style["sources"]?.jsonObject ?: error("style without sources")
        var replaced = 0
        val pinnedSources = JsonObject(
            sources.mapValues { (_, source) ->
                val src = source.jsonObject
                val url = src["url"]?.jsonPrimitive?.contentOrNull
                if (src["type"]?.jsonPrimitive?.contentOrNull == "vector" && url != null && isPlanetTileJson(url)) {
                    replaced++
                    JsonObject(
                        (src - "url") + mapOf(
                            "tiles" to JsonArray(listOf(JsonPrimitive(version.tiles))),
                            "minzoom" to JsonPrimitive(version.minZoom),
                            "maxzoom" to JsonPrimitive(version.maxZoom),
                            "attribution" to JsonPrimitive(version.attribution),
                        ),
                    )
                } else {
                    src
                }
            },
        )
        require(replaced > 0) { "style has no OpenFreeMap planet source to pin" }
        return JsonObject(style + ("sources" to pinnedSources)).toString()
    }

    /** The version a pinned style uses, or null if it is not pinned. */
    fun pinnedVersionName(styleJson: String): String? {
        val sources = json.parseToJsonElement(styleJson).jsonObject["sources"]?.jsonObject ?: return null
        return sources.values.firstNotNullOfOrNull { source ->
            source.jsonObject["tiles"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?.takeIf { "/planet/" in it }
                ?.let { MapDataVersion(it, 0, 14, "").name }
        }
    }

    private fun isPlanetTileJson(url: String) = url.trimEnd('/').endsWith("tiles.openfreemap.org/planet")
}
