package manana.sync.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `POST /v1/devices/authorize`, `GET /v1/devices`, `DELETE /v1/devices/{id}`. */
class DeviceTest {

    @Test
    fun anEnrolledDeviceCanVouchForANewOne() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("tablet")

        val response = authorizeDevice(harness, first.accountId, first.deviceId, first.device, joining)
        assertEquals(201, response.status.value)
        val authorized: AuthorizeResponse = response.decode()

        // The new device can now authenticate entirely on its own.
        val token = openSession(first.accountId, authorized.deviceId, joining)
        assertEquals(200, client.getAuth("/v1/devices", token).status.value)
    }

    @Test
    fun listDevicesShowsBothAndMarksTheCaller() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("tablet")
        val authorized: AuthorizeResponse =
            authorizeDevice(harness, first.accountId, first.deviceId, first.device, joining).decode()

        val listed: DevicesResponse = client.getAuth("/v1/devices", first.token).decode()
        assertEquals(2, listed.devices.size)
        assertEquals(1, listed.devices.count { it.self })
        assertTrue(listed.devices.single { it.self }.deviceId == first.deviceId)
        assertNotNull(listed.devices.singleOrNull { it.deviceId == authorized.deviceId })
        assertTrue(listed.devices.all { it.revokedAt == null })
    }

    /**
     * The property the whole revocation feature exists for. A revoked device must not be able to
     * bring itself -- or a fresh key it controls -- back into the account by vouching.
     */
    @Test
    fun aRevokedDeviceCannotVouchForANewDevice() = serverTest { harness ->
        val first = enrol(harness)
        val second = TestDevice("second")
        val secondId = authorizeDevice(harness, first.accountId, first.deviceId, first.device, second)
            .decode<AuthorizeResponse>().deviceId
        val secondToken = openSession(first.accountId, secondId, second)

        // The first device revokes the second.
        assertEquals(200, client.deleteAuth("/v1/devices/$secondId", first.token).status.value)

        // The revoked device now tries to vouch for a third.
        val third = TestDevice("third")
        val response = authorizeDevice(harness, first.accountId, secondId, second, third)
        assertEquals(403, response.status.value)
        assertEquals("device_revoked", response.errorCode())

        // And its own token is dead.
        assertEquals(401, client.getAuth("/v1/devices", secondToken).status.value)

        // Nothing was enrolled.
        val listed: DevicesResponse = client.getAuth("/v1/devices", first.token).decode()
        assertEquals(2, listed.devices.size)
        assertNotNull(listed.devices.single { it.deviceId == secondId }.revokedAt)
    }

    /**
     * Revocation also has to survive the device coming back with a *fresh* session attempt: the
     * challenge endpoint refuses it, so there is no path to a new token either.
     */
    @Test
    fun aRevokedDeviceCannotOpenANewSession() = serverTest { harness ->
        val first = enrol(harness)
        val second = TestDevice("second")
        val secondId = authorizeDevice(harness, first.accountId, first.deviceId, first.device, second)
            .decode<AuthorizeResponse>().deviceId
        client.deleteAuth("/v1/devices/$secondId", first.token)

        val response = client.postJson(
            "/v1/session/challenge",
            JSON_LENIENT.encodeToString(ChallengeRequest(first.accountId, secondId)),
        )
        assertEquals(403, response.status.value)
        assertEquals("device_revoked", response.errorCode())
    }

    @Test
    fun authorizeSignedByAKeyThatIsNotTheVoucherIsRejected() = serverTest { harness ->
        val first = enrol(harness)
        val impostor = TestDevice("impostor")
        val joining = TestDevice("joining")

        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            AuthorizeRequest(
                first.accountId,
                joining.publicKeyB64,
                joining.sealedLabel,
                ts,
                first.deviceId,
                // Correct message, wrong key.
                impostor.sign(SignedMessage.authorize(first.accountId, joining.publicKeyB64, ts)),
            )
        )
        val response = client.postJson("/v1/devices/authorize", body)
        assertEquals(401, response.status.value)
        assertEquals("bad_signature", response.errorCode())
    }

    /**
     * A signature over `("authorize", …)` for one key must not enrol a different key. This is what
     * the length-prefixed canonical encoding buys, and it is the shape of the bug that a naive
     * "verify the signature, then read the fields again" handler ships with.
     */
    @Test
    fun authorizeWithASignatureOverADifferentKeyIsRejected() = serverTest { harness ->
        val first = enrol(harness)
        val intended = TestDevice("intended")
        val attacker = TestDevice("attacker")

        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            AuthorizeRequest(
                first.accountId,
                attacker.publicKeyB64,
                attacker.sealedLabel,
                ts,
                first.deviceId,
                first.device.sign(SignedMessage.authorize(first.accountId, intended.publicKeyB64, ts)),
            )
        )
        assertEquals(401, client.postJson("/v1/devices/authorize", body).status.value)
    }

    /**
     * Replay: the exact same signed `authorize` body, sent twice. The first attempt enrols; the
     * second must be refused even though its signature is perfectly valid.
     */
    @Test
    fun replayingAValidAuthorizeIsRejected() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("joining")

        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            AuthorizeRequest(
                first.accountId,
                joining.publicKeyB64,
                joining.sealedLabel,
                ts,
                first.deviceId,
                first.device.sign(SignedMessage.authorize(first.accountId, joining.publicKeyB64, ts)),
            )
        )
        assertEquals(201, client.postJson("/v1/devices/authorize", body).status.value)

        val replay = client.postJson("/v1/devices/authorize", body)
        assertEquals(401, replay.status.value)
        assertEquals("replay_detected", replay.errorCode())

        val listed: DevicesResponse = client.getAuth("/v1/devices", first.token).decode()
        assertEquals(2, listed.devices.size)
    }

    /**
     * A stale signed request is refused by the freshness window before the replay cache is even
     * consulted, which is why the cache only has to remember one window's worth of messages.
     */
    @Test
    fun anAuthorizeOlderThanTheFreshnessWindowIsRejected() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("joining")
        val ts = harness.clock.now
        val body = JSON_LENIENT.encodeToString(
            AuthorizeRequest(
                first.accountId,
                joining.publicKeyB64,
                joining.sealedLabel,
                ts,
                first.deviceId,
                first.device.sign(SignedMessage.authorize(first.accountId, joining.publicKeyB64, ts)),
            )
        )
        harness.clock.now += harness.config.signatureWindowMillis + 1

        val response = client.postJson("/v1/devices/authorize", body)
        assertEquals(401, response.status.value)
        assertEquals("stale_timestamp", response.errorCode())
    }

    @Test
    fun authorizeAgainstAnUnknownVoucherIsRejected() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("joining")
        val response =
            authorizeDevice(harness, first.accountId, "not-a-real-device", first.device, joining)
        assertEquals(404, response.status.value)
        assertEquals("unknown_device", response.errorCode())
    }

    @Test
    fun authorizeAgainstAnUnknownAccountIsRejected() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("joining")
        val response =
            authorizeDevice(harness, randomAccountId(), first.deviceId, first.device, joining)
        assertEquals(404, response.status.value)
        assertEquals("unknown_device", response.errorCode())
    }

    /**
     * Enrolment is idempotent, and this is the bug that made it worth fixing.
     *
     * A device that enrolled successfully but never saw the reply -- a dropped response, a killed
     * app, a second tap -- used to be told `409 device_exists` on every retry, forever. It was
     * correctly on the account and permanently unable to learn its own id, so it could never sync,
     * with nothing wrong on either side. `claimAccount` has always answered this shape of question
     * with `AlreadyClaimed`; this is the same idea one call to its left.
     */
    @Test
    fun enrollingTheSameKeyTwiceReturnsTheSameDeviceId() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("joining")
        val created = authorizeDevice(harness, first.accountId, first.deviceId, first.device, joining)
        assertEquals(201, created.status.value)
        val firstAnswer: AuthorizeResponse = created.decode()

        harness.clock.now += 1 // a different ts, so this is not a replay
        val retry = authorizeDevice(harness, first.accountId, first.deviceId, first.device, joining)

        assertEquals(200, retry.status.value, "a retry is not a creation")
        assertEquals(
            firstAnswer.deviceId,
            retry.decode<AuthorizeResponse>().deviceId,
            "a retry must hand back the id the first attempt assigned",
        )

        // The point of returning the id rather than minting one: the account has two devices, not
        // three. A retry that enrolled again would grow a row per dropped reply.
        val listed: DevicesResponse = client.getAuth("/v1/devices", first.token).decode()
        assertEquals(2, listed.devices.size)
    }

    /**
     * The half that must NOT be idempotent.
     *
     * A revoked key coming back through the front door would undo the only thing revocation does.
     * It is refused, and named as revoked rather than as "already enrolled" -- which is true of it
     * and useless to whoever has to act on it, since the fix is to pair again for a fresh key.
     */
    @Test
    fun enrollingARevokedKeyIsRefusedAsRevoked() = serverTest { harness ->
        val first = enrol(harness)
        val joining = TestDevice("joining")
        val joiningId = authorizeDevice(harness, first.accountId, first.deviceId, first.device, joining)
            .decode<AuthorizeResponse>().deviceId
        assertEquals(200, client.deleteAuth("/v1/devices/$joiningId", first.token).status.value)

        harness.clock.now += 1
        val response = authorizeDevice(harness, first.accountId, first.deviceId, first.device, joining)

        assertEquals(403, response.status.value)
        assertEquals("device_revoked", response.errorCode())

        // And it is still revoked -- the refusal must not have quietly restored anything.
        val listed: DevicesResponse = client.getAuth("/v1/devices", first.token).decode()
        assertNotNull(listed.devices.single { it.deviceId == joiningId }.revokedAt)
    }

    @Test
    fun revokingAnUnknownDeviceIs404() = serverTest { harness ->
        val first = enrol(harness)
        val response = client.deleteAuth("/v1/devices/nosuchdevice", first.token)
        assertEquals(404, response.status.value)
        assertEquals("unknown_device", response.errorCode())
    }

    @Test
    fun aDeviceMayRevokeItself() = serverTest { harness ->
        val first = enrol(harness)
        assertEquals(200, client.deleteAuth("/v1/devices/${first.deviceId}", first.token).status.value)
        assertEquals(401, client.getAuth("/v1/devices", first.token).status.value)
    }

    @Test
    fun deviceEndpointsRequireABearerToken() = serverTest { harness ->
        val first = enrol(harness)
        assertEquals(401, client.getAuth("/v1/devices", null).status.value)
        assertEquals(401, client.getAuth("/v1/devices", "not-a-token").status.value)
        assertEquals(401, client.deleteAuth("/v1/devices/${first.deviceId}", null).status.value)
    }

    /** One account's token must never resolve to another account's devices. */
    @Test
    fun aTokenIsScopedToItsOwnAccount() = serverTest { harness ->
        val alice = enrol(harness)
        val bob = enrol(harness)

        val listed: DevicesResponse = client.getAuth("/v1/devices", alice.token).decode()
        assertEquals(1, listed.devices.size)
        assertFalse(listed.devices.any { it.deviceId == bob.deviceId })

        // Alice cannot revoke Bob's device by naming it.
        assertEquals(404, client.deleteAuth("/v1/devices/${bob.deviceId}", alice.token).status.value)
    }
}
