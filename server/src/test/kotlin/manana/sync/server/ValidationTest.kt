package manana.sync.server

import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Input validation. Every one of these is a request a well-behaved client never makes, which is
 * exactly why they are worth a test: the only caller who sends them is one that is probing.
 */
class ValidationTest {

    private val envelope = "sealed".toByteArray()

    @Test
    fun anOversizedBodyIsRejectedWithoutBeingProcessed() =
        serverTest(testConfig(maxRequestBytes = 2048)) { harness ->
            val me = enrol(harness)
            val response = client.postJson("/v1/records", "x".repeat(4096), me.token)
            assertEquals(413, response.status.value)
            assertEquals("payload_too_large", response.errorCode())
        }

    @Test
    fun anOversizedEnvelopeIsRejected() =
        serverTest(testConfig(maxEnvelopeBytes = 64)) { harness ->
            val me = enrol(harness)
            val response = push(me.token, upsertItem(blindedId(1), ByteArray(65), baseSeq = 0))
            assertEquals(400, response.status.value)
            assertEquals("invalid_envelope", response.errorCode())
        }

    /**
     * `limit` bounds a page in records; this bounds it in bytes. Without the byte cap, a page of
     * ten 100 KiB records at a 250 KiB budget would come back whole and a client holding it would
     * be holding four times the budget it was promised.
     */
    @Test
    fun `a changes page stops at the byte budget`() =
        serverTest(testConfig(maxChangesBytes = 250 * 1024)) { harness ->
            val me = enrol(harness)
            val items = (0 until 10)
                .map { i -> upsertItem(blindedId(i), ByteArray(100 * 1024), baseSeq = 0) }
                .toTypedArray()
            val pushResponse = push(me.token, *items)
            assertEquals(200, pushResponse.status.value)

            val page: ChangesResponse = client.getAuth("/v1/changes?since=0&limit=200", me.token).decode()

            assertTrue(page.records.size < 10)
            val totalBytes = page.records.sumOf { B64.decodeOrNull(it.envelope)!!.size }
            assertTrue(totalBytes <= 250 * 1024 + 100 * 1024)
        }

    /**
     * The first record always goes in, however large. A page that came back empty here would not
     * mean "too big to send" -- it would mean "you are caught up" to a client that stops paging on
     * an empty page, and the cursor would stop at this record forever.
     */
    @Test
    fun `a single record larger than the whole budget is still returned`() =
        serverTest(testConfig(maxChangesBytes = 100 * 1024, maxEnvelopeBytes = 512 * 1024)) { harness ->
            val me = enrol(harness)
            val response = push(me.token, upsertItem(blindedId(1), ByteArray(300 * 1024), baseSeq = 0))
            assertEquals(200, response.status.value)

            val page: ChangesResponse = client.getAuth("/v1/changes?since=0&limit=200", me.token).decode()

            assertEquals(1, page.records.size)
            assertEquals(300 * 1024, B64.decodeOrNull(page.records.single().envelope)!!.size)
        }

    @Test
    fun anEmptyEnvelopeIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val response = push(me.token, upsertItem(blindedId(1), ByteArray(0), baseSeq = 0))
        assertEquals(400, response.status.value)
        assertEquals("invalid_envelope", response.errorCode())
    }

    @Test
    fun aMalformedBase64EnvelopeIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val body = JSON_LENIENT.encodeToString(
            UpsertRequest(
                listOf(UpsertRequestItem(blindedId(1), 0, "not base64!!! @@@"))
            )
        )
        val response = client.postJson("/v1/records", body, me.token)
        assertEquals(400, response.status.value)
        assertEquals("malformed_base64", response.errorCode())
    }

    @Test
    fun aNonNumericCursorIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val response = client.getAuth("/v1/changes?since=yesterday", me.token)
        assertEquals(400, response.status.value)
        assertEquals("invalid_cursor", response.errorCode())
    }

    @Test
    fun aNegativeCursorIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        assertEquals(400, client.getAuth("/v1/changes?since=-1", me.token).status.value)
    }

    /** Larger than `Long.MAX_VALUE`: not a number this server can hold, so not a cursor. */
    @Test
    fun aCursorLargerThanALongIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val response = client.getAuth("/v1/changes?since=99999999999999999999999", me.token)
        assertEquals(400, response.status.value)
        assertEquals("invalid_cursor", response.errorCode())
    }

    @Test
    fun anOutOfRangeChangesLimitIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        assertEquals(400, client.getAuth("/v1/changes?since=0&limit=0", me.token).status.value)
        assertEquals(400, client.getAuth("/v1/changes?since=0&limit=100000", me.token).status.value)
        assertEquals(400, client.getAuth("/v1/changes?since=0&limit=lots", me.token).status.value)
    }

    @Test
    fun anOutOfRangeHistoryLimitIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        push(me.token, upsertItem(blindedId(1), envelope, 0))
        val path = "/v1/records/${blindedId(1)}/history"
        assertEquals(400, client.getAuth("$path?limit=0", me.token).status.value)
        assertEquals(400, client.getAuth("$path?limit=999", me.token).status.value)
    }

    @Test
    fun anEmptyBatchIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val response =
            client.postJson("/v1/records", JSON_LENIENT.encodeToString(UpsertRequest(emptyList())), me.token)
        assertEquals(400, response.status.value)
        assertEquals("empty_batch", response.errorCode())
    }

    @Test
    fun anOversizedBatchIsRejected() = serverTest(testConfig(maxBatchItems = 3)) { harness ->
        val me = enrol(harness)
        val items = (0 until 4).map { upsertItem(blindedId(it), envelope, 0) }
        val response =
            client.postJson("/v1/records", JSON_LENIENT.encodeToString(UpsertRequest(items)), me.token)
        assertEquals(400, response.status.value)
        assertEquals("batch_too_large", response.errorCode())
    }

    @Test
    fun aBlindedIdOutsideTheBase64UrlAlphabetIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val response = push(me.token, upsertItem("../../etc/passwd", envelope, 0))
        assertEquals(400, response.status.value)
        assertEquals("invalid_blinded_id", response.errorCode())
    }

    @Test
    fun anEmptyOrOverlongBlindedIdIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        assertEquals(400, push(me.token, upsertItem("", envelope, 0)).status.value)
        assertEquals(400, push(me.token, upsertItem("A".repeat(65), envelope, 0)).status.value)
    }

    @Test
    fun aNegativeBaseSeqIsRejected() = serverTest { harness ->
        val me = enrol(harness)
        val response = push(me.token, upsertItem(blindedId(1), envelope, baseSeq = -1))
        assertEquals(400, response.status.value)
        assertEquals("invalid_base_seq", response.errorCode())
    }

    @Test
    fun anOversizedSealedLabelIsRejected() = serverTest { harness ->
        // 700 base64url characters decode to 525 bytes, past the 512-byte cap. The `devices` table
        // is not storage.
        val response = claimWithSealedLabel(harness, "A".repeat(700))
        assertEquals(400, response.status.value)
        assertEquals("invalid_label", response.errorCode())
    }

    @Test
    fun aSealedLabelOutsideTheBase64UrlAlphabetIsRejected() = serverTest { harness ->
        val response = claimWithSealedLabel(harness, "not base64!!")
        assertEquals(400, response.status.value)
        assertEquals("invalid_label", response.errorCode())
    }

    private suspend fun ApplicationTestBuilder.claimWithSealedLabel(
        harness: Harness,
        sealedLabel: String,
    ) = client.postJson(
        "/v1/account",
        run {
            val accountId = randomAccountId()
            val device = TestDevice()
            val ts = harness.clock.now
            JSON_LENIENT.encodeToString(
                ClaimRequest(
                    accountId,
                    device.publicKeyB64,
                    sealedLabel,
                    ts,
                    device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts)),
                )
            )
        },
    )

    /**
     * An unknown `accountId` on an authenticated path cannot even be expressed -- the only way to
     * name an account is to hold a token for it. The unauthenticated paths that do take one answer
     * `404`, and never distinguish "unknown" from "known but wrong device".
     */
    @Test
    fun anUnknownAccountIdIsRejected() = serverTest { harness ->
        val unknown = randomAccountId()
        assertEquals(
            404,
            client.postJson(
                "/v1/session/challenge",
                JSON_LENIENT.encodeToString(ChallengeRequest(unknown, "whatever")),
            ).status.value,
        )
        val joining = TestDevice()
        val voucher = TestDevice()
        assertEquals(
            404,
            authorizeDevice(harness, unknown, "whatever", voucher, joining).status.value,
        )
    }

    @Test
    fun aMalformedJsonBodyNeverBecomesAServerError() = serverTest { harness ->
        val me = enrol(harness)
        for (body in listOf("", "null", "[]", "{}", "{\"items\":\"not-a-list\"}", " ")) {
            val response = client.postJson("/v1/records", body, me.token)
            assertEquals(400, response.status.value, "body was: $body")
        }
    }
}
