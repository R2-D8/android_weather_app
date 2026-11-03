package dev.r2d8.weather.ui.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudLayerTest {
    @Test
    fun `names the layer with the most cloud`() {
        assertEquals("high", mainCloudLayer(12, 38, 90))
        assertEquals("mid", mainCloudLayer(0, 60, 20))
        assertEquals("low", mainCloudLayer(80, 10, 30))
    }

    @Test
    fun `ties go to the lower layer`() {
        assertEquals("low", mainCloudLayer(50, 50, 50))
        assertEquals("mid", mainCloudLayer(0, 40, 40))
    }

    @Test
    fun `no layers, no name`() {
        assertNull(mainCloudLayer(null, 10, 20))
    }
}
