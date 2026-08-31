package my.cheysoff.core_sync_net

import my.cheysoff.core_sync_net.http.KtorHttpTransport
import my.cheysoff.core_sync_net.http.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ServerEndpoint], and the certificate pin it carries.
 *
 * ## What is honestly not tested here
 *
 * That the pin is actually **enforced** during a TLS handshake. Proving that needs a TLS server
 * with a certificate this test controls, and OkHttp's `CertificatePinner` is the code that would be
 * under test -- which is not this module's code. What is tested is the part that is ours and that
 * fails silently when it is wrong: that the 32 bytes from the pairing QR are handed to OkHttp in
 * the exact form it expects, scoped to the right host, and that a pin can never be attached to a
 * connection that could not enforce it.
 */
class ServerEndpointTest {

    @Test
    fun `a trailing slash is removed so paths are not doubled`() {
        assertEquals(
            "https://notes.example.com/v1/changes",
            ServerEndpoint("https://notes.example.com/").resolve("/v1/changes"),
        )
    }

    @Test
    fun `a query string is appended with a single separator`() {
        assertEquals(
            "https://notes.example.com/v1/changes?since=4&limit=32",
            ServerEndpoint("https://notes.example.com").resolve("/v1/changes", "since=4&limit=32"),
        )
    }

    @Test
    fun `https is required for anything that is not loopback`() {
        assertRejected("http://notes.example.com")
        assertRejected("http://192.168.1.10:8080")
    }

    /**
     * The one exception, and the reason for it: this server speaks plain HTTP and binds `127.0.0.1`
     * by default precisely so that exposing it directly has to be deliberate. Loopback is the case
     * where no traffic leaves the machine and there is nothing for TLS to protect.
     */
    @Test
    fun `plain http is allowed to loopback and nowhere else`() {
        assertFalse(ServerEndpoint("http://127.0.0.1:8080").isSecure)
        assertFalse(ServerEndpoint("http://localhost:8080").isSecure)
        assertTrue(ServerEndpoint("https://notes.example.com").isSecure)
    }

    /** A pin that is silently not enforced is worse than no pin: it is a belief, not a control. */
    @Test
    fun `a pin cannot be attached to a connection that could not enforce it`() {
        try {
            ServerEndpoint("http://127.0.0.1:8080", ByteArray(32))
            fail("a pin over plain http must be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a pin that is not a sha-256 digest is refused`() {
        try {
            ServerEndpoint("https://notes.example.com", ByteArray(31))
            fail("an SPKI pin is 32 bytes")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a URL carrying a query or a fragment is refused`() {
        assertRejected("https://notes.example.com/?token=abc")
        assertRejected("https://notes.example.com/#anchor")
    }

    @Test
    fun `nonsense URLs are refused`() {
        assertRejected("")
        assertRejected("   ")
        assertRejected("notes.example.com")
        assertRejected("ftp://notes.example.com")
        assertRejected("https://")
    }

    /**
     * The scheme check is not the same rule as the https-or-loopback check, and only loopback tells
     * them apart.
     *
     * `ftp://notes.example.com` above is refused either way -- it is not https and it is not
     * loopback -- so it does not actually prove the scheme is being read. Deleting the scheme check
     * entirely was measured to leave the whole suite green until this case was added. To loopback,
     * where the https rule stands aside, the scheme is the only thing left saying no.
     */
    @Test
    fun `a scheme that is not http or https is refused even to loopback`() {
        assertRejected("ftp://127.0.0.1:8080")
        assertRejected("ws://localhost:8080")
    }

    /**
     * `https://host:notaport` used to be refused by `java.net.URI`, which returned a null host for
     * it; the hand-rolled parser that replaced URI has to refuse it itself, and deleting its port
     * check left the suite green.
     *
     * It matters because [ServerEndpoint.host] is what the certificate pin is scoped to. A parser
     * that split on the wrong character would pin a host the connection never reaches, which is a
     * pin that silently never fires.
     */
    @Test
    fun `a port that is not a number is refused`() {
        assertRejected("https://notes.example.com:")
        assertRejected("https://notes.example.com:https")
        assertRejected("https://notes.example.com:80a")
        assertEquals(
            "notes.example.com",
            ServerEndpoint("https://notes.example.com:8443").host,
        )
    }

    /**
     * A URL cannot smuggle a credential past this type.
     *
     * `https://user:secret@host` parsed under `java.net.URI` as host `host` with the credential
     * quietly dropped -- so it was accepted, and the secret sat in whatever stored the URL. This
     * protocol authenticates with a device key and has nothing for a URL credential to mean.
     */
    @Test
    fun `a URL carrying a username or password is refused`() {
        assertRejected("https://user:secret@notes.example.com")
        assertRejected("https://user@notes.example.com")
    }

    @Test
    fun `the host a pin is scoped to is the endpoint's own`() {
        val endpoint = ServerEndpoint("https://notes.example.com:8443", ByteArray(32) { 1 })
        assertEquals("notes.example.com", endpoint.host)
        // Building the transport is what installs the pin; that it succeeds with this endpoint is
        // the assertion, because CertificatePinner.Builder.add rejects a malformed pattern.
        KtorHttpTransport.create(endpoint)
    }

    private fun assertRejected(url: String) {
        try {
            ServerEndpoint(url)
            fail("expected '$url' to be refused")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
