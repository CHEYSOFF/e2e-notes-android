package my.cheysoff.feature_settings

import my.cheysoff.feature_settings.model.SyncServerUrlCheck
import my.cheysoff.feature_settings.model.checkSyncServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address the settings screen will and will not store.
 *
 * This is a security boundary, not a formatting nicety: the value that passes here is the value a
 * `SyncHttpClient` gets built with, and therefore the host a bearer session token would be sent
 * to. The scheme cases below are the ones that matter most — an `http` LAN address slipping
 * through would put a token that can write to the account on a plaintext hop.
 */
class SyncServerUrlTest {

    private fun accepted(raw: String): String {
        val result = checkSyncServerUrl(raw)
        assertTrue("expected $raw to be accepted, got $result", result is SyncServerUrlCheck.Ok)
        return (result as SyncServerUrlCheck.Ok).normalized
    }

    private fun rejected(raw: String): String {
        val result = checkSyncServerUrl(raw)
        assertTrue(
            "expected $raw to be rejected, got $result",
            result is SyncServerUrlCheck.Rejected,
        )
        return (result as SyncServerUrlCheck.Rejected).message
    }

    // -- accepted ------------------------------------------------------------------------------

    @Test
    fun `https host is accepted`() {
        assertEquals("https://notes.example.com", accepted("https://notes.example.com"))
    }

    @Test
    fun `https with a port and a path prefix is accepted`() {
        // A server mounted under a path on a shared reverse proxy is a supported deployment;
        // ServerEndpoint.resolve appends its routes to whatever base it is given.
        assertEquals(
            "https://example.com:8443/manana",
            accepted("https://example.com:8443/manana"),
        )
    }

    @Test
    fun `a trailing slash is normalised away`() {
        // So that one server cannot be stored as two different strings, which would look like two
        // servers to anything that compares them -- the transport-provider cache included.
        assertEquals("https://notes.example.com", accepted("https://notes.example.com/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        // Pasting an address from anywhere picks up a space or a newline; that is not a typo the
        // user should have to hunt for.
        assertEquals("https://notes.example.com", accepted("  https://notes.example.com\n"))
    }

    @Test
    fun `an uppercase scheme is accepted`() {
        // Schemes are case-insensitive per RFC 3986, and a keyboard that capitalises the first
        // letter is a real thing that happens.
        assertTrue(checkSyncServerUrl("HTTPS://notes.example.com") is SyncServerUrlCheck.Ok)
    }

    // -- the scheme rule -----------------------------------------------------------------------

    @Test
    fun `plain http to a LAN address is refused`() {
        val message = rejected("http://192.168.1.10:8080")
        assertTrue(
            "the message must say why, not just that: $message",
            message.contains("loopback") || message.contains("https"),
        )
    }

    @Test
    fun `plain http to a named host is refused`() {
        rejected("http://notes.example.com")
    }

    @Test
    fun `plain http to localhost is accepted`() {
        // The one exception, and it is not a convenience: nothing leaves the device, so there is
        // no hop for TLS to protect. See ServerEndpoint's own reasoning.
        assertEquals("http://localhost:8080", accepted("http://localhost:8080"))
    }

    @Test
    fun `plain http to 127 0 0 1 is accepted`() {
        assertEquals("http://127.0.0.1:8080", accepted("http://127.0.0.1:8080"))
    }

    // -- refused input -------------------------------------------------------------------------

    @Test
    fun `an empty address is refused`() {
        rejected("")
    }

    @Test
    fun `a blank address is refused`() {
        rejected("   ")
    }

    @Test
    fun `an address with no scheme is refused`() {
        rejected("notes.example.com")
    }

    @Test
    fun `an address with a space in it is refused and says so`() {
        val message = rejected("https://notes example.com")
        assertEquals("A server address can't contain spaces.", message)
    }

    @Test
    fun `a non-http scheme is refused`() {
        rejected("ftp://notes.example.com")
    }

    @Test
    fun `a scheme with no host is refused`() {
        rejected("https://")
    }

    @Test
    fun `a query string is refused`() {
        // Every route this client builds is base + path; a base that already carries a query would
        // produce "…?a=1/v1/changes", which is nobody's server.
        rejected("https://notes.example.com/?token=abc")
    }

    @Test
    fun `a fragment is refused`() {
        rejected("https://notes.example.com/#section")
    }

    @Test
    fun `every refusal message is a sentence`() {
        // The messages come from ServerEndpoint, which writes them lower case for an exception.
        // On screen they are sentences, and this is what checks that the lift actually happens.
        listOf("", "notes.example.com", "ftp://x.example.com", "http://192.168.1.10").forEach {
            val message = rejected(it)
            assertTrue("not capitalised: $message", message.first().isUpperCase())
            assertTrue("no full stop: $message", message.endsWith("."))
        }
    }
}
