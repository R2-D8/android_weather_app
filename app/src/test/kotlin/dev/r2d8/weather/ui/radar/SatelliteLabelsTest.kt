package dev.r2d8.weather.ui.radar

import dev.r2d8.weather.domain.MapStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteLabelsTest {
    @Test
    fun `city, village, road and border labels stay on the satellite picture`() {
        listOf(
            "label_city", "label_city_capital", "label_town", "label_village", "label_other",
            "label_state", "label_country_1", "highway-name-major", "highway-name-minor",
            "highway-shield-non-us", "water_name_point_label", "airport",
        ).forEach { assertTrue(it, keepOnSatellite(it, LayerKind.Symbol)) }
    }

    @Test
    fun `points of interest and one-way arrows are dropped`() {
        listOf("poi_r20", "poi_r7", "poi_r1", "poi_transit", "road_one_way_arrow", "road_one_way_arrow_opposite")
            .forEach { assertFalse(it, keepOnSatellite(it, LayerKind.Symbol)) }
    }

    @Test
    fun `roads, bridges and borders stay but railways, waterways and parks do not`() {
        listOf("road_motorway", "road_trunk_primary", "road_minor", "road_minor_casing", "bridge_motorway", "boundary_2", "boundary_3")
            .forEach { assertTrue(it, keepOnSatellite(it, LayerKind.Line)) }
        listOf("road_major_rail", "road_transit_rail", "bridge_major_rail_hatching", "waterway_river", "park_outline", "tunnel_motorway", "aeroway_runway")
            .forEach { assertFalse(it, keepOnSatellite(it, LayerKind.Line)) }
    }

    @Test
    fun `filled areas, shading and background never cover the imagery`() {
        listOf("background", "landcover_wood", "water", "building-3d", "natural_earth")
            .forEach { assertFalse(it, keepOnSatellite(it, LayerKind.Other)) }
    }

    @Test
    fun `every map style is an https OpenFreeMap style and they are all different`() {
        MapStyle.entries.forEach { assertTrue(it.url, it.url.startsWith("https://tiles.openfreemap.org/styles/")) }
        assertEquals(MapStyle.entries.size, MapStyle.entries.map { it.url }.toSet().size)
        assertEquals(MapStyle.Detailed, dev.r2d8.weather.domain.AppSettings().mapStyle)
    }
}
