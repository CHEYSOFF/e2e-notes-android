package manana.sync.server

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the wire is allowed to carry beside a sealed envelope, asserted field by field.
 *
 * The other test files ask whether the server behaves correctly. This one asks what the server is
 * *told*, which is a different question and the one the privacy claim rests on. Three fields left
 * the protocol because the server never read them -- a record's `recType`, its `hlc`, and the
 * `receivedAt` it was stamped with -- and a device's label became a sealed blob. Every one of those
 * is the kind of removal that a later "small" change puts back without anybody noticing, because
 * putting a field back breaks nothing: the server would store it and echo it exactly as before.
 *
 * So the assertions here are deliberately exact rather than approximate. A request that carries a
 * removed field must be **rejected**, which strict decoding gives for free, and a response object's
 * key set must equal the expected set rather than merely contain it. Those two shapes are what make
 * a reintroduced field a failing test instead of a silent regression.
 *
 * Why it matters concretely, in the words of the client's own design: `recType` in the clear tells
 * an operator how many folders an account has and when each one changes; an `hlc` in the clear
 * contains the node component of a hybrid logical clock, which for the natural implementation names
 * the device that made every single edit; `receivedAt` in the database is a per-record timeline that
 * survives in a stolen backup; and a plaintext device label is a name a human chose, which
 * identifies the person at least as well as it identifies the phone.
 */
class MetadataTest {

    private val envelope = "sealed".toByteArray()

    private suspend fun io.ktor.client.statement.HttpResponse.keysOf(
        array: String,
        index: Int = 0,
    ): Set<String> = (json()[array]!!.jsonArray[index] as JsonObject).keys

    // -----------------------------------------------------------------------------------------
    // Requests: a removed field cannot come back quietly
    // -----------------------------------------------------------------------------------------

    /** The raw body a client of the previous protocol would send. */
    private fun legacyPushBody(extraField: String): String = """
        {"items":[{"blindedId":"${blindedId(1)}",$extraField
         "baseSeq":0,"envelope":"${B64.encode(envelope)}"}]}
    """.trimIndent()

    @Test
    fun anUpsertItemCarryingARecTypeIsRejected() = serverTest { harness ->
        val me = enrol(harness)

        val response = client.postJson("/v1/records", legacyPushBody("\"recType\":\"note\","), me.token)

        assertEquals(400, response.status.value)
        assertEquals("malformed_request", response.errorCode())
    }

    @Test
    fun anUpsertItemCarryingAnHlcIsRejected() = serverTest { harness ->
        val me = enrol(harness)

        val response =
            client.postJson("/v1/records", legacyPushBody("\"hlc\":\"1-0-pixel7\","), me.token)

        assertEquals(400, response.status.value)
        assertEquals("malformed_request", response.errorCode())
    }

    @Test
    fun aClaimCarryingAPlaintextDeviceLabelIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val ts = harness.clock.now
        val signature = device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts))
        val body = """
            {"accountId":"$accountId","devicePublicKey":"${device.publicKeyB64}",
             "deviceLabel":"Vova's Pixel 7","ts":$ts,"signature":"$signature"}
        """.trimIndent()

        val response = client.postJson("/v1/account", body)

        assertEquals(400, response.status.value)
        assertEquals("malformed_request", response.errorCode())
    }

    // -----------------------------------------------------------------------------------------
    // Responses: exactly three fields describe a record, on every path that returns one
    // -----------------------------------------------------------------------------------------

    private val recordFields = setOf("blindedId", "seq", "envelope")

    @Test
    fun aPulledRecordCarriesOnlyTheBlindedIdSeqAndEnvelope() = serverTest { harness ->
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))

        val pull = client.getAuth("/v1/changes?since=0", me.token)

        assertEquals(recordFields, pull.keysOf("records"))
    }

    @Test
    fun theConflictingVersionReturnedInlineCarriesOnlyThoseThreeFields() = serverTest { harness ->
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))

        // A second create against the same record: `baseSeq = 0` no longer matches its head.
        val conflict = push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))

        assertEquals(409, conflict.status.value)
        val current = conflict.json()["results"]!!.jsonArray[0].jsonObject["current"]!!.jsonObject
        assertEquals(recordFields, current.keys)
    }

    @Test
    fun aHistoryVersionCarriesOnlyThoseThreeFields() = serverTest { harness ->
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))

        val history = client.getAuth("/v1/records/${blindedId(1)}/history", me.token)

        assertEquals(recordFields, history.keysOf("versions"))
    }

    @Test
    fun noRecordResponseCarriesATimestamp() = serverTest { harness ->
        // Stated separately from the key-set assertions because it is the one with a second half:
        // `records` has no timestamp column either, so a copy of `sync.db` shows an operator the
        // order in which an account's edits arrived but not the hours of the day they arrived at.
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))

        val pull = client.getAuth("/v1/changes?since=0", me.token).bodyAsText()

        assertTrue(!pull.contains("receivedAt"), "a record carried a timestamp: $pull")
        assertTrue(!pull.contains(harness.clock.now.toString()), "a record carried the clock: $pull")
    }

    // -----------------------------------------------------------------------------------------
    // Device labels
    // -----------------------------------------------------------------------------------------

    @Test
    fun aDeviceRowCarriesASealedLabelAndNoPlaintextName() = serverTest { harness ->
        val me = enrol(harness)

        val devices = client.getAuth("/v1/devices", me.token)

        assertEquals(
            setOf("deviceId", "sealedLabel", "publicKey", "createdAt", "revokedAt", "self"),
            devices.keysOf("devices"),
        )
    }

    @Test
    fun aSealedLabelIsStoredAndReturnedByteForByte() = serverTest { harness ->
        // The server's whole contract with `DeviceLabelCipher`: whatever the client sealed comes
        // back unchanged, so the other device can open it. Anything less and the label is lost;
        // anything more -- normalising it, trimming it, reading it -- and the server is doing
        // something with a value it has no business understanding.
        val me = enrol(harness)

        val devices: DevicesResponse = client.getAuth("/v1/devices", me.token).decode()

        assertEquals(1, devices.devices.size)
        assertEquals(me.device.sealedLabel, devices.devices[0].sealedLabel)
    }

    @Test
    fun aDeviceEnrolledWithoutALabelIsAccepted() = serverTest { harness ->
        // The field defaults to empty, so a client that has no name to offer -- or does not want to
        // offer one -- is a first-class case rather than a validation failure.
        val accountId = randomAccountId()
        val device = TestDevice()
        val ts = harness.clock.now
        val signature = device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts))
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(accountId, device.publicKeyB64, "", ts, signature)
        )

        val claim = client.postJson("/v1/account", body)

        assertEquals(201, claim.status.value)
        val token = openSession(accountId, claim.decode<ClaimResponse>().deviceId, device)
        val devices: DevicesResponse = client.getAuth("/v1/devices", token).decode()
        assertEquals("", devices.devices[0].sealedLabel)
    }
}
