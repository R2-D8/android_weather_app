package dev.r2d8.weather.data.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StylePinningTest {
    private val tileJson = """
        {"tiles":["https://tiles.openfreemap.org/planet/20261018_080001_pt/{z}/{x}/{y}.pbf"],
         "minzoom":0,"maxzoom":14,"attribution":"<a href=\"https://openfreemap.org\">OpenFreeMap</a>",
         "vector_layers":[]}
    """.trimIndent()

    // Shaped like OpenFreeMap's styles (cut down).
    private val style = """
        {"version":8,"name":"Liberty",
         "sources":{
           "openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"},
           "ne2_shaded":{"maxzoom":6,"tileSize":256,"tiles":["https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"],"type":"raster"}
         },
         "sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm",
         "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
         "layers":[{"id":"background","type":"background"}]}
    """.trimIndent()

    @Test
    fun `the tilejson gives the versioned tile address`() {
        val version = StylePinning.parseTileJson(tileJson)
        assertEquals("https://tiles.openfreemap.org/planet/20261018_080001_pt/{z}/{x}/{y}.pbf", version.tiles)
        assertEquals(0, version.minZoom)
        assertEquals(14, version.maxZoom)
        assertEquals("20261018_080001_pt", version.name)
    }

    @Test
    fun `pinning replaces the planet reference and keeps everything else`() {
        val version = StylePinning.parseTileJson(tileJson)
        val pinned = Json.parseToJsonElement(StylePinning.pin(style, version)).jsonObject
        val source = pinned.getValue("sources").jsonObject.getValue("openmaptiles").jsonObject

        assertFalse("url" in source)
        assertEquals(version.tiles, source.getValue("tiles").jsonArray.single().jsonPrimitive.content)
        assertEquals("14", source.getValue("maxzoom").jsonPrimitive.content)
        assertEquals(version.attribution, source.getValue("attribution").jsonPrimitive.content)
        assertEquals("vector", source.getValue("type").jsonPrimitive.content)

        val original = Json.parseToJsonElement(style).jsonObject
        assertEquals(original.getValue("sources").jsonObject["ne2_shaded"], pinned.getValue("sources").jsonObject["ne2_shaded"])
        assertEquals(original["layers"], pinned["layers"])
        assertEquals(original["glyphs"], pinned["glyphs"])
        assertEquals(original["sprite"], pinned["sprite"])
    }

    @Test
    fun `a pinned style tells its version`() {
        val version = StylePinning.parseTileJson(tileJson)
        assertEquals("20261018_080001_pt", StylePinning.pinnedVersionName(StylePinning.pin(style, version)))
        assertNull(StylePinning.pinnedVersionName(style))
    }

    @Test
    fun `a style without the planet source is refused instead of silently left unpinned`() {
        val version = StylePinning.parseTileJson(tileJson)
        val other = """{"version":8,"sources":{"x":{"type":"vector","url":"https://example.com/tiles.json"}},"layers":[]}"""
        assertThrows(IllegalArgumentException::class.java) { StylePinning.pin(other, version) }
    }

    @Test
    fun `a tilejson without a tile template is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            StylePinning.parseTileJson("""{"tiles":["https://example.com/planet"]}""")
        }
    }
}
