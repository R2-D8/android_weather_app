package dev.r2d8.weather.data.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerAddressTest {
    @Test
    fun `a bare address and port becomes an http base url`() {
        assertEquals("http://100.95.20.125:8080/", ServerAddress.normalize("100.95.20.125:8080"))
        assertEquals("http://100.95.20.125:8080/", ServerAddress.normalize("  100.95.20.125:8080  "))
    }

    @Test
    fun `paths, queries and fragments are dropped`() {
        assertEquals(
            "http://100.95.20.125:8080/",
            ServerAddress.normalize("http://100.95.20.125:8080/tiles/x?y=1#z"),
        )
        assertEquals("https://radar.example.com/", ServerAddress.normalize("https://radar.example.com/frames"))
    }

    @Test
    fun `blank or broken input is not an address`() {
        assertNull(ServerAddress.normalize(""))
        assertNull(ServerAddress.normalize("   "))
        assertNull(ServerAddress.normalize("http://"))
        assertNull(ServerAddress.normalize("ftp://server"))
        assertNull(ServerAddress.normalize("not a host name"))
    }

    @Test
    fun `plain http is only fine for the home tailscale address`() {
        assertNull(ServerAddress.problem("100.95.20.125:8080"))
        assertNull(ServerAddress.problem("https://radar.example.com"))
        assertNotNull(ServerAddress.problem("192.168.1.10:8080"))
        assertNotNull(ServerAddress.problem("http://radar.example.com"))
    }

    @Test
    fun `problems are described for blank and invalid input`() {
        assertTrue(ServerAddress.problem("")!!.contains("Enter"))
        assertTrue(ServerAddress.problem("ftp://x")!!.contains("not a valid"))
    }

    /**
     * Guards the "home server is optional and unknown to friends' builds" rule: the network security
     * config must block cleartext traffic everywhere except the one Tailscale address in [ServerAddress].
     */
    @Test
    fun `network security config blocks cleartext except for the tailscale address`() {
        val xml = File("src/main/res/xml/network_security_config.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue("base config must forbid cleartext", Regex("""<base-config\s+cleartextTrafficPermitted="false"""").containsMatchIn(xml))
        val allowed = Regex("""<domain-config\s+cleartextTrafficPermitted="true">\s*<domain[^>]*>([^<]+)</domain>\s*</domain-config>""")
            .findAll(xml).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf(ServerAddress.CLEARTEXT_HOST), allowed)
        assertEquals(1, Regex("cleartextTrafficPermitted=\"true\"").findAll(xml).count())
    }
}
