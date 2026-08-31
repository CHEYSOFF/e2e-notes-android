package manana.sync.server

import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `POST /v1/account` -- the trust-on-first-use claim. */
class AccountTest {

    @Test
    fun claimCreatesAccountAndEnrolsFirstDevice() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice("pixel")
        val claim = claimAccount(harness, accountId, device)

        assertEquals(accountId, claim.accountId)
        assertTrue(claim.deviceId.isNotEmpty())
        assertTrue(harness.store.accountExists(accountId))
    }

    @Test
    fun claimingAnAlreadyClaimedAccountIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        claimAccount(harness, accountId, TestDevice("first"))

        val squatter = TestDevice("squatter")
        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(
                accountId,
                squatter.publicKeyB64,
                squatter.sealedLabel,
                ts,
                squatter.sign(SignedMessage.claim(accountId, squatter.publicKeyB64, ts)),
            )
        )
        val response = client.postJson("/v1/account", body)
        assertEquals(409, response.status.value)
        assertEquals("account_exists", response.errorCode())
    }

    /**
     * The claim carries a self-signature so that the key being enrolled is one the caller actually
     * holds. Without it, anyone who learned an unclaimed `accountId` could install a public key
     * they cannot sign for -- and that key would then be the account's only vouching device.
     */
    @Test
    fun claimWithASignatureFromADifferentKeyIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val enrolling = TestDevice("enrolling")
        val other = TestDevice("other")
        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(
                accountId,
                enrolling.publicKeyB64,
                enrolling.sealedLabel,
                ts,
                other.sign(SignedMessage.claim(accountId, enrolling.publicKeyB64, ts)),
            )
        )
        val response = client.postJson("/v1/account", body)
        assertEquals(401, response.status.value)
        assertEquals("bad_signature", response.errorCode())
        assertTrue(!harness.store.accountExists(accountId))
    }

    @Test
    fun claimWithAStaleTimestampIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val ts = harness.clock.now - harness.config.signatureWindowMillis - 1
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(
                accountId,
                device.publicKeyB64,
                device.sealedLabel,
                ts,
                device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts)),
            )
        )
        val response = client.postJson("/v1/account", body)
        assertEquals(401, response.status.value)
        assertEquals("stale_timestamp", response.errorCode())
    }

    /** A timestamp far in the *future* is as much a replay tool as one in the past. */
    @Test
    fun claimWithAFutureTimestampIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val ts = harness.clock.now + harness.config.signatureWindowMillis + 1
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(
                accountId,
                device.publicKeyB64,
                device.sealedLabel,
                ts,
                device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts)),
            )
        )
        assertEquals(401, client.postJson("/v1/account", body).status.value)
    }

    @Test
    fun claimWithAMalformedAccountIdIsRejected() = serverTest { harness ->
        val device = TestDevice()
        // Well-formed base64url, but eight bytes rather than sixteen.
        val shortId = B64.encode(ByteArray(8))
        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(
                shortId,
                device.publicKeyB64,
                device.sealedLabel,
                ts,
                device.sign(SignedMessage.claim(shortId, device.publicKeyB64, ts)),
            )
        )
        val response = client.postJson("/v1/account", body)
        assertEquals(400, response.status.value)
        assertEquals("invalid_account_id", response.errorCode())
    }

    /**
     * An off-curve "public key" would enrol a device slot that can never sign anything, and that no
     * later request could distinguish from a real one.
     */
    @Test
    fun claimWithAnOffCurvePublicKeyIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val bad = B64.decodeOrNull(device.publicKeyB64)!!.copyOf()
        bad[64] = (bad[64].toInt() xor 0x01).toByte() // move Y off the curve
        val badB64 = B64.encode(bad)
        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            ClaimRequest(
                accountId,
                badB64,
                device.sealedLabel,
                ts,
                device.sign(SignedMessage.claim(accountId, badB64, ts)),
            )
        )
        val response = client.postJson("/v1/account", body)
        assertEquals(400, response.status.value)
        assertEquals("invalid_public_key", response.errorCode())
    }

    @Test
    fun claimWithAnUnparseableBodyIsRejected() = serverTest {
        val response = client.postJson("/v1/account", "{ this is not json")
        assertEquals(400, response.status.value)
        assertEquals("malformed_request", response.errorCode())
    }

    /**
     * Strict decoding: a field this build does not know about is a protocol the server does not
     * implement, and answering it as though the field were absent is the failure nobody notices.
     */
    @Test
    fun claimWithAnUnknownFieldIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val ts = harness.clock.now
        val signature = device.sign(SignedMessage.claim(accountId, device.publicKeyB64, ts))
        val body = """
            {"accountId":"$accountId","devicePublicKey":"${device.publicKeyB64}",
             "sealedLabel":"","ts":$ts,"signature":"$signature","skipSignatureCheck":true}
        """.trimIndent()
        val response = client.postJson("/v1/account", body)
        assertEquals(400, response.status.value)
    }

    @Test
    fun healthEndpointRevealsNothingAboutAccounts() = serverTest { harness ->
        val enrolled = enrol(harness)
        val body = client.getAuth("/healthz", null).bodyAsText()
        assertEquals("""{"status":"ok","version":"1.0.0"}""", body)
        assertTrue(!body.contains(enrolled.accountId))
    }
}
