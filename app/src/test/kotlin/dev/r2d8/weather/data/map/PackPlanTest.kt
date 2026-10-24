package dev.r2d8.weather.data.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackPlanTest {
    @Test
    fun `tile counts match hand-counted cases`() {
        val world = GeoBox(-85.0, -180.0, 85.0, 180.0)
        assertEquals(1L, PackPlanner.tileCount(world, 0, 0))
        assertEquals(1L + 4 + 16, PackPlanner.tileCount(world, 0, 2))
        // A tiny box is one tile per zoom level.
        val dot = GeoBox(41.9, 12.49, 41.9001, 12.4901)
        assertEquals(13L, PackPlanner.tileCount(dot, 0, 12))
    }

    @Test
    fun `the box is about 60 km across in both directions`() {
        val box = PackPlanner.box(60.45, 22.27) // Turku
        val northSouthKm = (box.north - box.south) * 111.32
        val eastWestKm = (box.east - box.west) * 111.32 * Math.cos(Math.toRadians(60.45))
        assertEquals(60.0, northSouthKm, 0.5)
        assertEquals(60.0, eastWestKm, 0.5)
    }

    @Test
    fun `packs for the places in use stay within OpenFreeMap's tile limit at full zoom`() {
        listOf(41.90 to 12.50, 45.46 to 9.19, 60.45 to 22.27, 37.50 to 15.09).forEach { (lat, lon) ->
            val plan = PackPlanner.plan(lat, lon)!!
            assertEquals(PackPlanner.MAX_ZOOM, plan.maxZoom)
            assertTrue("${plan.tileCount} tiles at $lat,$lon", plan.tileCount <= PackPlanner.MAX_TILES)
            // Sanity: a 60 km box at zoom 12 needs well over a hundred tiles.
            assertTrue(plan.tileCount > 100)
        }
    }

    @Test
    fun `a lower zoom is chosen when the area would need too many tiles`() {
        // Far north, where web-mercator tiles are small on the ground.
        val plan = PackPlanner.plan(80.0, 15.0, maxZoom = 14)!!
        assertTrue(plan.maxZoom < 14)
        assertTrue(plan.tileCount <= PackPlanner.MAX_TILES)
    }

    @Test
    fun `pack info round trips and foreign metadata is ignored`() {
        val info = PackInfo(placeId = 7, latitude = 60.45, longitude = 22.27, version = "20261018_080001_pt", maxZoom = 12)
        assertEquals(info, PackInfo.decode(info.encode()))
        assertNull(PackInfo.decode("not json".encodeToByteArray()))
        assertNull(PackInfo.decode(null))
    }
}
