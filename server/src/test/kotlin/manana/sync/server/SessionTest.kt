package manana.sync.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /v1/session/challenge` and `POST /v1/session`. */
class SessionTest {

    @Test
    fun aSignedChallengeYieldsAWorkingToken() = serverTest { harness ->
        val enrolled = enrol(harness)
        assertTrue(enrolled.token.isNotEmpty())
        assertEquals(200, client.getAuth("/v1/devices", enrolled.token).status.value)
    }

    /**
     * The challenge is single use, so a captured `POST /v1/session` body is worthless: the signature
     * is still valid, and the server still refuses it, because the nonce it names is gone.
     */
    @Test
    fun replayingASignedSessionRequestIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val claim = claimAccount(harness, accountId, device)

        val challenge: ChallengeResponse = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(accountId, claim.deviceId)),
        ).decode()
        val body = JSON_LENIENT.encodeToString(
            SessionRequest(
                accountId,
                claim.deviceId,
                challenge.challenge,
                device.sign(SignedMessage.session(accountId, claim.deviceId, challenge.challenge)),
            )
        )

        assertEquals(200, client.postJson("/v1/session", body).status.value)
        val replay = client.postJson("/v1/session", body)
        assertEquals(401, replay.status.value)
        assertEquals("bad_challenge", replay.errorCode())
    }

    @Test
    fun aChallengeSignedByTheWrongKeyIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val impostor = TestDevice()
        val claim = claimAccount(harness, accountId, device)

        val challenge: ChallengeResponse = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(accountId, claim.deviceId)),
        ).decode()
        val response = client.postJson(
            "/v1/session",
            JSON_LENIENT.encodeToString(
                SessionRequest(
                    accountId,
                    claim.deviceId,
                    challenge.challenge,
                    impostor.sign(
                        SignedMessage.session(accountId, claim.deviceId, challenge.challenge)
                    ),
                )
            ),
        )
        assertEquals(401, response.status.value)
        assertEquals("bad_signature", response.errorCode())
    }

    /**
     * A failed attempt burns the challenge too. That costs an honest client one extra round trip
     * and costs an online guesser the ability to keep trying against one nonce.
     */
    @Test
    fun aFailedSessionAttemptConsumesTheChallenge() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val impostor = TestDevice()
        val claim = claimAccount(harness, accountId, device)

        val challenge: ChallengeResponse = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(accountId, claim.deviceId)),
        ).decode()

        client.postJson(
            "/v1/session",
            JSON_LENIENT.encodeToString(
                SessionRequest(
                    accountId, claim.deviceId, challenge.challenge,
                    impostor.sign(SignedMessage.session(accountId, claim.deviceId, challenge.challenge)),
                )
            ),
        )

        // Now the real device tries the same challenge with a correct signature. Too late.
        val second = client.postJson(
            "/v1/session",
            JSON_LENIENT.encodeToString(
                SessionRequest(
                    accountId, claim.deviceId, challenge.challenge,
                    device.sign(SignedMessage.session(accountId, claim.deviceId, challenge.challenge)),
                )
            ),
        )
        assertEquals(401, second.status.value)
        assertEquals("bad_challenge", second.errorCode())
    }

    /** A challenge issued for one device cannot be redeemed by another, even a legitimate one. */
    @Test
    fun aChallengeCannotBeRedeemedByADifferentDevice() = serverTest { harness ->
        val first = enrol(harness)
        val second = TestDevice("second")
        val secondId = authorizeDevice(harness, first.accountId, first.deviceId, first.device, second)
            .decode<AuthorizeResponse>().deviceId

        val challenge: ChallengeResponse = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(first.accountId, first.deviceId)),
        ).decode()

        val response = client.postJson(
            "/v1/session",
            JSON_LENIENT.encodeToString(
                SessionRequest(
                    first.accountId, secondId, challenge.challenge,
                    second.sign(SignedMessage.session(first.accountId, secondId, challenge.challenge)),
                )
            ),
        )
        assertEquals(401, response.status.value)
    }

    @Test
    fun anExpiredChallengeIsRejected() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val claim = claimAccount(harness, accountId, device)

        val challenge: ChallengeResponse = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(accountId, claim.deviceId)),
        ).decode()
        harness.clock.now += harness.config.challengeTtlMillis + 1

        val response = client.postJson(
            "/v1/session",
            JSON_LENIENT.encodeToString(
                SessionRequest(
                    accountId, claim.deviceId, challenge.challenge,
                    device.sign(SignedMessage.session(accountId, claim.deviceId, challenge.challenge)),
                )
            ),
        )
        assertEquals(401, response.status.value)
    }

    @Test
    fun anExpiredTokenStopsWorking() = serverTest { harness ->
        val enrolled = enrol(harness)
        assertEquals(200, client.getAuth("/v1/devices", enrolled.token).status.value)
        harness.clock.now += harness.config.sessionTtlMillis + 1
        assertEquals(401, client.getAuth("/v1/devices", enrolled.token).status.value)
    }

    @Test
    fun eachSessionGetsADistinctToken() = serverTest { harness ->
        val accountId = randomAccountId()
        val device = TestDevice()
        val claim = claimAccount(harness, accountId, device)
        val first = openSession(accountId, claim.deviceId, device)
        val second = openSession(accountId, claim.deviceId, device)
        assertNotEquals(first, second)
        assertEquals(200, client.getAuth("/v1/devices", first).status.value)
        assertEquals(200, client.getAuth("/v1/devices", second).status.value)
    }

    @Test
    fun aChallengeForAnUnknownDeviceIsRejected() = serverTest { harness ->
        val enrolled = enrol(harness)
        val response = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(enrolled.accountId, "nope")),
        )
        assertEquals(404, response.status.value)
    }

    @Test
    fun aChallengeForAnUnknownAccountIsRejected() = serverTest {
        val response = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(randomAccountId(), "nope")),
        )
        assertEquals(404, response.status.value)
    }

    /**
     * Token resolution refuses a revoked device even when a session row for it exists.
     *
     * Revocation already deletes that device's sessions, so this second barrier is unreachable
     * through the HTTP surface -- which is exactly why it needs a test that reaches past it. The row
     * is inserted through the store directly, standing in for a future code path that creates a
     * session without re-checking revocation. Without the `revoked_at IS NULL` join condition in
     * `sessionByTokenHash`, this token would work; a mutation confirmed that no other test noticed
     * its removal.
     */
    @Test
    fun aSessionRowForARevokedDeviceDoesNotResolve() = serverTest { harness ->
        val first = enrol(harness)
        val second = TestDevice("second")
        val secondId = authorizeDevice(harness, first.accountId, first.deviceId, first.device, second)
            .decode<AuthorizeResponse>().deviceId
        client.deleteAuth("/v1/devices/$secondId", first.token)

        val smuggled = "smuggled-token"
        val smuggledHash = sha256Hex(smuggled.toByteArray(Charsets.UTF_8))
        harness.store.createSession(
            accountId = first.accountId,
            deviceId = secondId,
            tokenHash = smuggledHash,
            expiresAt = harness.clock.now + 60_000,
        )

        assertNull(harness.store.sessionByTokenHash(smuggledHash))
        assertEquals(401, client.getAuth("/v1/devices", smuggled).status.value)
    }

    /**
     * The token is a bearer credential, so the database must not hold anything that can be replayed
     * against the server. It stores a SHA-256 digest; the token itself exists only in the client's
     * memory and in the `Authorization` header of a live request.
     */
    @Test
    fun theDatabaseStoresOnlyADigestOfTheToken() = serverTest { harness ->
        val enrolled = enrol(harness)
        // Presenting the digest as though it were the token must not authenticate.
        val digest = sha256Hex(enrolled.token.toByteArray(Charsets.UTF_8))
        // The digest is what the sessions table is keyed on -- so the token itself is not stored.
        assertNotNull(harness.store.sessionByTokenHash(digest))
        assertNull(harness.store.sessionByTokenHash(enrolled.token))
        assertEquals(401, client.getAuth("/v1/devices", digest).status.value)
        // And the real token still works, so the digest is genuinely a different string.
        assertEquals(200, client.getAuth("/v1/devices", enrolled.token).status.value)
    }
}
