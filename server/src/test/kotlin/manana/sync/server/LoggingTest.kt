package manana.sync.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the log file may and may not contain.
 *
 * The README states that the operator can see request counts, sizes, timings and which endpoints
 * were called, and cannot see account IDs, device identities, blinded record IDs or envelope bytes.
 * This is the test that keeps that statement true as the route table grows.
 */
class LoggingTest {

    @Test
    fun logLinesNameRouteTemplatesAndNeverRealPaths() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        push(me.token, upsertItem(id, "sealed".toByteArray(), baseSeq = 0))
        client.getAuth("/v1/records/$id/history", me.token)
        client.getAuth("/v1/changes?since=0", me.token)
        client.deleteAuth("/v1/devices/${me.deviceId}", me.token)

        val log = harness.logLines.joinToString("\n")
        assertTrue(log.contains("GET /v1/records/{id}/history"), log)
        assertTrue(log.contains("DELETE /v1/devices/{id}"), log)
        assertFalse(log.contains(id), "a blinded record id reached the log")
        assertFalse(log.contains(me.deviceId), "a device id reached the log")
        assertFalse(log.contains(me.accountId), "an account id reached the log")
        assertFalse(log.contains(me.token), "a session token reached the log")
        assertFalse(log.contains(me.device.publicKeyB64), "a device public key reached the log")
    }

    @Test
    fun logLinesNeverContainEnvelopeBytes() = serverTest { harness ->
        val me = enrol(harness)
        val envelope = "SEALED-BYTES-THAT-MUST-NOT-BE-LOGGED".toByteArray()
        push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))
        client.getAuth("/v1/changes?since=0", me.token)

        val log = harness.logLines.joinToString("\n")
        assertFalse(log.contains(B64.encode(envelope)), "an envelope reached the log")
        assertFalse(log.contains("SEALED-BYTES"), "an envelope reached the log")
    }

    /** The operator does get the useful, non-identifying half. */
    @Test
    fun logLinesCarryMethodStatusDurationAndSizes() = serverTest { harness ->
        enrol(harness)
        val line = harness.logLines.first { it.contains("POST /v1/account") }
        assertTrue(line.startsWith("INFO "), line)
        assertTrue(line.contains("-> 201"), line)
        assertTrue(line.contains("ms "), line)
        assertTrue(Regex("""in=\d+B out=\d+B""").containsMatchIn(line), line)
    }

    @Test
    fun rejectionsAreLoggedToo() = serverTest { harness ->
        client.postJson("/v1/account", "{ nope")
        assertEquals(1, harness.logLines.count { it.contains("POST /v1/account -> 400") })
    }

    /** Debug detail is off by default, so the quiet path really is quiet. */
    @Test
    fun detailIsSilentUnlessDebugIsEnabled() {
        val quiet = ArrayList<String>()
        RequestLog(sink = { quiet.add(it) }, debugEnabled = false).detail("something")
        assertTrue(quiet.isEmpty())

        val loud = ArrayList<String>()
        RequestLog(sink = { loud.add(it) }, debugEnabled = true).detail("something")
        assertEquals(listOf("DEBUG something"), loud)
    }
}
