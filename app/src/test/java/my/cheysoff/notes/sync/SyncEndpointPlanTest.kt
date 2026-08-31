package my.cheysoff.notes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graph-level answer to "can this device talk to a sync server?".
 *
 * The same question the settings screen asks, asked again on the way out of storage, because the
 * two have different jobs: the screen refuses to *write* a bad address, and this refuses to *build
 * a client* from one — including one written by a build with a different rule, or edited into the
 * preferences file directly. A preferences file is not a trusted input.
 *
 * Pure by construction: [planSyncEndpoint] takes two facts and returns a decision, so none of this
 * needs a Keystore, a DataStore or a socket.
 */
class SyncEndpointPlanTest {

    private val url = "https://notes.example.com"

    private fun unusable(paired: Boolean, storedUrl: String?): SyncEndpointPlan.Unusable {
        val plan = planSyncEndpoint(paired, storedUrl)
        assertTrue("expected Unusable, got $plan", plan is SyncEndpointPlan.Unusable)
        return plan as SyncEndpointPlan.Unusable
    }

    @Test
    fun `paired with a valid address yields an endpoint`() {
        val plan = planSyncEndpoint(paired = true, storedUrl = url)
        assertTrue("$plan", plan is SyncEndpointPlan.Usable)
        assertEquals(url, (plan as SyncEndpointPlan.Usable).endpoint.baseUrl)
    }

    @Test
    fun `the endpoint carries no certificate pin`() {
        // Not an oversight and not a TODO: nothing in the app can produce one. Asserted so that
        // the day a pin does get wired through from pairing, this test is what says so.
        val plan = planSyncEndpoint(paired = true, storedUrl = url) as SyncEndpointPlan.Usable
        assertNull(plan.endpoint.spkiPinSha256)
    }

    @Test
    fun `an unpaired device is not configured, whatever the address says`() {
        assertEquals(SyncNotConfigured.NOT_PAIRED, unusable(paired = false, storedUrl = url).reason)
    }

    @Test
    fun `pairing is checked before the address`() {
        // Both are missing; the reason reported is the one the user has to fix first.
        assertEquals(
            SyncNotConfigured.NOT_PAIRED,
            unusable(paired = false, storedUrl = null).reason,
        )
    }

    @Test
    fun `no stored address is not configured`() {
        assertEquals(
            SyncNotConfigured.NO_SERVER_URL,
            unusable(paired = true, storedUrl = null).reason,
        )
    }

    @Test
    fun `an empty or blank stored address is not configured`() {
        // The regression this exists for. An empty string treated as configured would build a
        // client -- ServerEndpoint would throw and take the provider with it -- or, worse, look
        // like a working configuration that quietly goes nowhere.
        listOf("", "   ").forEach { stored ->
            assertEquals(
                "stored=<$stored>",
                SyncNotConfigured.NO_SERVER_URL,
                unusable(paired = true, storedUrl = stored).reason,
            )
        }
    }

    @Test
    fun `a stored plain-http LAN address does not become an endpoint`() {
        // The other regression this exists for. If the scheme rule ever stops being applied on the
        // way out of storage, a value written by some other build would put a session token that
        // can push records and revoke devices onto a plaintext hop.
        val plan = unusable(paired = true, storedUrl = "http://192.168.1.10:8080")
        assertEquals(SyncNotConfigured.UNUSABLE_SERVER_URL, plan.reason)
    }

    @Test
    fun `a stored loopback http address is still an endpoint`() {
        val plan = planSyncEndpoint(paired = true, storedUrl = "http://127.0.0.1:8080")
        assertTrue("$plan", plan is SyncEndpointPlan.Usable)
    }

    @Test
    fun `a stored address that no longer parses is reported as unusable, not as absent`() {
        val plan = unusable(paired = true, storedUrl = "notes.example.com")
        assertEquals(SyncNotConfigured.UNUSABLE_SERVER_URL, plan.reason)
    }

    @Test
    fun `every unusable plan carries a message`() {
        listOf(
            false to url,
            true to null,
            true to "http://192.168.1.10",
        ).forEach { (paired, stored) ->
            val plan = unusable(paired, stored)
            assertTrue("empty message for $plan", plan.message.isNotBlank())
        }
    }
}
