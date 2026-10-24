package dev.r2d8.weather.data.radar

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Turns what the user typed into the home server's base URL and says what is wrong with it. */
object ServerAddress {
    /**
     * The only host that may be reached over plain HTTP (Tailscale encrypts that link). It must
     * match res/xml/network_security_config.xml, which a unit test checks.
     */
    const val CLEARTEXT_HOST = "100.95.20.125"

    /** "100.x.y.z:8080" or "http://host:8080/anything" becomes "http://host:8080/"; null if unusable. */
    fun normalize(input: String): String? = parse(input)?.toString()

    /** A short message for the user when [input] cannot be used, or null when it is fine. */
    fun problem(input: String): String? {
        if (input.isBlank()) return "Enter the server address, for example 100.x.y.z:8080."
        val url = parse(input) ?: return "That is not a valid address."
        if (cleartextBlocked(url)) {
            return "Plain http:// is only allowed for the home Tailscale address. Use https:// for other hosts."
        }
        return null
    }

    internal fun cleartextBlocked(url: HttpUrl): Boolean = url.scheme == "http" && url.host != CLEARTEXT_HOST

    private fun parse(input: String): HttpUrl? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val withScheme = if ("://" in text) text else "http://$text"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return url.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }
}
