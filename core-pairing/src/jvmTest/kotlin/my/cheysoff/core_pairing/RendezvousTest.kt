package my.cheysoff.core_pairing

import my.cheysoff.core_pairing.protocol.AccountBundle
import my.cheysoff.core_pairing.protocol.AccountDeviceSession
import my.cheysoff.core_pairing.protocol.CollectResult
import my.cheysoff.core_pairing.protocol.DepositResult
import my.cheysoff.core_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.core_pairing.protocol.NewDeviceRendezvous
import my.cheysoff.core_pairing.protocol.OfferOutcome
import my.cheysoff.core_pairing.protocol.PairingFailure
import my.cheysoff.core_pairing.protocol.P256
import my.cheysoff.core_pairing.protocol.PairingProtocol
import my.cheysoff.core_pairing.protocol.PollOutcome
import my.cheysoff.core_pairing.protocol.RendezvousClient
import my.cheysoff.core_pairing.protocol.RendezvousProtocol
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_pairing.protocol.ServerHint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server-assisted return leg, driven end to end with **real crypto and a fake transport**.
 *
 * The transport is faked and nothing else is: the account device is a real [AccountDeviceSession]
 * sealing a real bundle under a real ECDH, and the new device is a real
 * [my.cheysoff.core_pairing.protocol.NewDeviceSession] behind [NewDeviceRendezvous]. What the fake
 * removes is sockets, not checks — which is the point, because the thing worth proving here is
 * that routing QR2 through a server skips **no guard** the camera path runs.
 */
class RendezvousTest {

    private val ark = ByteArray(32) { (it * 3 + 1).toByte() }
    private val bundle = AccountBundle(ark, "acct-desktop", """{"server":"https://example.test"}""")
    private val server = RendezvousUrl.parse("https://pair.example.test")!!

    /**
     * The laptop's long-lived device key, as QR1 carries it.
     *
     * A real generated point rather than 65 arbitrary bytes: the account session validates it
     * against the curve before it will vouch for it, so a fake would be rejected and every test
     * here would fail for the wrong reason.
     */
    private val deviceKey = P256.encodePublicKey(
        P256.generateKeyPair().public as java.security.interfaces.ECPublicKey,
    )

    // -----------------------------------------------------------------------------------------
    // The whole thing
    // -----------------------------------------------------------------------------------------

    /**
     * A laptop and a phone complete a pairing through the drop, and both derive the same SAS.
     *
     * The SAS matching is the load-bearing assertion, not the ARK bytes: the account device knows
     * the ARK from the start, so its SAS proves nothing on its own. The new device can only produce
     * that number by having *opened* the seal, so two matching digits are the new device proving it
     * ended up with the same key from the same session.
     */
    @Test
    fun aBundleTravelsThroughTheDropAndOpensOnTheFarSide() {
        val drop = FakeDrop()
        val desktop = newDevice(drop)

        // Leg one is unchanged: the phone's camera reads QR1 off the laptop's screen.
        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val accepted = phone.accept(desktop.offerCode, bundle.config)!!

        // Leg two is the new part: instead of rendering QR2, the phone posts it.
        val deposit = drop.deposit(sidOf(desktop), accepted.sealCode)
        assertTrue(deposit is DepositResult.Deposited)

        val outcome = desktop.poll()
        assertTrue("expected a pairing, got $outcome", outcome is PollOutcome.Paired)
        outcome as PollOutcome.Paired

        assertArrayEquals(ark, outcome.bundle.ark)
        assertEquals("acct-desktop", outcome.bundle.accountId)
        assertEquals("""{"server":"https://example.test"}""", outcome.bundle.config)
        assertEquals(accepted.sas, outcome.sas)
    }

    /** Until the phone sends, polling says "not yet" and does not consume the session. */
    @Test
    fun pollingAnEmptyDropWaitsIndefinitelyWithinTheTtl() {
        val drop = FakeDrop()
        val desktop = newDevice(drop)

        repeat(20) { assertTrue(desktop.poll() is PollOutcome.Waiting) }

        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val accepted = phone.accept(desktop.offerCode)!!
        drop.deposit(sidOf(desktop), accepted.sealCode)
        assertTrue(desktop.poll() is PollOutcome.Paired)
    }

    /**
     * QR1 now carries the server, which is the one thing about the first leg that changes.
     *
     * The field was always in the wire format and was always empty because nothing filled it; this
     * asserts that the account device reads back exactly what the new device advertised, because
     * that string is what the phone is about to be asked to POST to.
     */
    @Test
    fun theServerUrlReachesTheAccountDeviceThroughQr1() {
        val desktop = newDevice(FakeDrop())
        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)

        phone.onScanned(desktop.offerCode)

        assertEquals(server.base, phone.receivedServerHint?.url)
    }

    // -----------------------------------------------------------------------------------------
    // The guards, proven to still be the scanned path's guards
    // -----------------------------------------------------------------------------------------

    /**
     * A bundle sealed for a different `sid` does not open.
     *
     * This is the `sid` binding — HKDF salt and GCM AAD both — reaching the HTTP leg untouched. The
     * drop is keyed by `sid`, so the way to arrange this is to have a second laptop's session seal
     * happen and then file it under the first laptop's name, which is exactly what an attacker with
     * a stolen blob would do.
     */
    @Test
    fun aBundleSealedForAnotherSessionIsRejectedLoudly() {
        val drop = FakeDrop()
        val victim = newDevice(drop)
        val other = newDevice(drop)

        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val forOther = phone.accept(other.offerCode)!!

        // File the *other* session's seal under the victim's sid.
        drop.force(sidOf(victim), forOther.sealCode)

        val outcome = victim.poll()
        assertTrue(outcome is PollOutcome.Failed)
        // SESSION_MISMATCH rather than SEAL_REJECTED: the frame echoes a `sid` that is not this
        // session's, and the session catches that before it derives anything. Had that comparison
        // been removed, the key schedule would have produced a different Ks and the GCM tag would
        // have failed instead -- which is the whole reason the binding is in two places.
        assertEquals(PairingFailure.SESSION_MISMATCH, (outcome as PollOutcome.Failed).failure)
    }

    /**
     * One flipped byte in the ciphertext is a terminal, loud failure.
     *
     * A server -- or anything on the wire in front of one -- that alters the blob gets exactly the
     * same answer a tampered QR code gets, and the session dies rather than polling again. A retry
     * loop in front of a failing open is an oracle.
     */
    @Test
    fun aModifiedBlobFailsTheTagAndKillsTheSession() {
        val drop = FakeDrop()
        val desktop = newDevice(drop)
        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val accepted = phone.accept(desktop.offerCode)!!

        drop.force(sidOf(desktop), tamperWithTheTag(accepted.sealCode))

        val outcome = desktop.poll()
        assertEquals(PairingFailure.SEAL_REJECTED, (outcome as PollOutcome.Failed).failure)

        // Terminal: a second poll does not re-run the open against whatever arrives next.
        val second = desktop.poll()
        assertEquals(PairingFailure.SESSION_CLOSED, (second as PollOutcome.Failed).failure)
    }

    /** The TTL is the session's own, on a monotonic clock, exactly as it is when scanning. */
    @Test
    fun aSessionPastItsTtlStopsPollingAndRefusesALateBundle() {
        val drop = FakeDrop()
        val clock = FakeClock()
        val desktop = NewDeviceRendezvous(drop, HkdfKeyDerivation, clock, server, deviceKey)

        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val accepted = phone.accept(desktop.offerCode)!!

        clock.advance(PairingProtocol.CODE_TTL_MILLIS)
        drop.force(sidOf(desktop), accepted.sealCode)

        assertEquals(PollOutcome.Expired, desktop.poll())
    }

    /**
     * A body the server had no business returning is terminal rather than retried.
     *
     * Polling past it would be polling something hostile: a server answering with a non-bundle is
     * not a condition that improves by asking again.
     */
    @Test
    fun anUnusableResponseStopsTheSession() {
        val drop = FakeDrop()
        val desktop = newDevice(drop)
        drop.answer = CollectResult.Unusable("not a bundle")

        val outcome = desktop.poll()
        assertTrue(outcome is PollOutcome.Failed)
        assertNull((outcome as PollOutcome.Failed).failure)
        assertTrue(desktop.poll() is PollOutcome.Failed)
    }

    /** A network that is merely down is not a failure; the TTL is the only deadline. */
    @Test
    fun anUnreachableServerIsWaitingRatherThanFailure() {
        val drop = FakeDrop()
        val desktop = newDevice(drop)
        drop.answer = CollectResult.Unreachable("connection refused")

        val outcome = desktop.poll()
        assertTrue(outcome is PollOutcome.Waiting)
        assertEquals("connection refused", (outcome as PollOutcome.Waiting).detail)

        drop.answer = null
        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        drop.deposit(sidOf(desktop), (phone.accept(desktop.offerCode)!!).sealCode)
        assertTrue(desktop.poll() is PollOutcome.Paired)
    }

    /** Success closes the session: a second bundle cannot replace an adopted account key. */
    @Test
    fun aSecondPollAfterSuccessIsRefused() {
        val drop = FakeDrop()
        val desktop = newDevice(drop)
        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        drop.deposit(sidOf(desktop), (phone.accept(desktop.offerCode)!!).sealCode)

        assertTrue(desktop.poll() is PollOutcome.Paired)
        assertEquals(PairingFailure.SESSION_CLOSED, (desktop.poll() as PollOutcome.Failed).failure)
    }

    // -----------------------------------------------------------------------------------------
    // The blob encoding
    // -----------------------------------------------------------------------------------------

    /**
     * `toBlob`/`fromBlob` are inverses, and the round trip lands on the *exact* QR2 payload.
     *
     * Exactness is the requirement: the new device feeds the result straight into `onScanned`, so a
     * blob that came back one character different would fail at the prefix or the base64 rather
     * than at the tag, and the difference between those two failures is the difference between "a
     * bug" and "an attack".
     */
    @Test
    fun theBlobIsTheQrPayloadWithoutItsPrefix() {
        val phone = AccountDeviceSession(HkdfKeyDerivation, FakeClock(), bundle.ark, bundle.accountId)
        val desktop = newDevice(FakeDrop())
        val sealCode = (phone.accept(desktop.offerCode)!!).sealCode

        val blob = RendezvousProtocol.toBlob(sealCode)
        assertFalse(blob.startsWith(PairingProtocol.QR_PREFIX))
        assertEquals(sealCode, RendezvousProtocol.fromBlob(blob))
        assertTrue(RendezvousProtocol.isPlausibleBlob(blob))
    }

    @Test
    fun theSizeBoundFollowsTheProtocolRatherThanAPickedNumber() {
        // A maximal frame is a fixed 98-byte head plus a maximal seal. Restated here from the
        // protocol constants rather than hard-coded, so that growing a field moves both.
        val expected = 98 +
            (1 + 1 + AccountBundle.ARK_SIZE_BYTES + 1 + AccountBundle.MAX_ACCOUNT_ID_BYTES +
                2 + AccountBundle.MAX_CONFIG_BYTES) +
            PairingProtocol.GCM_TAG_SIZE_BITS / 8
        assertEquals(expected, RendezvousProtocol.MAX_SEALED_BYTES)
    }

    @Test
    fun animplausibleBlobIsRejectedBeforeItReachesTheSession() {
        assertFalse(RendezvousProtocol.isPlausibleBlob(""))
        assertFalse(RendezvousProtocol.isPlausibleBlob("not base64!!"))
        assertFalse(RendezvousProtocol.isPlausibleBlob("A".repeat(1_000_000)))

        val oversized = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(RendezvousProtocol.MAX_SEALED_BYTES + 1))
        assertFalse(RendezvousProtocol.isPlausibleBlob(oversized))

        val atTheBound = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(RendezvousProtocol.MAX_SEALED_BYTES))
        assertTrue(RendezvousProtocol.isPlausibleBlob(atTheBound))
    }

    @Test
    fun aSidIsTwentyTwoCharactersOfBase64url() {
        val sid = ByteArray(PairingProtocol.SID_SIZE_BYTES) { it.toByte() }
        val encoded = RendezvousProtocol.encodeSid(sid)
        assertEquals(22, encoded.length)
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    // -----------------------------------------------------------------------------------------
    // The unauthenticated URL
    // -----------------------------------------------------------------------------------------

    /**
     * The server address arrives in the clear in QR1, so its shape is checked before anything is
     * sent to it.
     *
     * Each rejection below is a thing a QR code could otherwise make a phone do.
     */
    @Test
    fun onlyPlainHttpAndHttpsUrlsAreAccepted() {
        assertNotNull(RendezvousUrl.parse("http://192.168.1.20:8080"))
        assertNotNull(RendezvousUrl.parse("https://pair.example.test"))
        assertNotNull(RendezvousUrl.parse("https://pair.example.test/manana"))

        // A URL opener reached from a QR code is a way out of this app entirely.
        assertNull(RendezvousUrl.parse("file:///etc/passwd"))
        assertNull(RendezvousUrl.parse("javascript:alert(1)"))
        assertNull(RendezvousUrl.parse("manana://pair"))
        // A credential in a QR code, and the classic way to disguise a host in a string a person is
        // being asked to read.
        assertNull(RendezvousUrl.parse("https://evil.test@real.test/"))
        // Silently dropped when the path is appended, which is worse than refusing.
        assertNull(RendezvousUrl.parse("https://pair.example.test?next=x"))
        assertNull(RendezvousUrl.parse("https://pair.example.test#frag"))
        assertNull(RendezvousUrl.parse(""))
        assertNull(RendezvousUrl.parse("   "))
        assertNull(RendezvousUrl.parse("https://" + "a".repeat(RendezvousUrl.MAX_CHARS)))
    }

    /**
     * The bound is in bytes, not characters, because that is the unit QR1's `url` field uses.
     *
     * A host of multi-byte characters can pass a character-count check and still be a string
     * `ServerHint`'s constructor would refuse — with an exception, on a screen with nowhere to catch
     * it. Rejecting here turns that into an ordinary "that address will not work".
     */
    @Test
    fun aUrlThatIsShortInCharactersButLongInBytesIsRejected() {
        // Each of these is three UTF-8 bytes, so this is well under MAX_CHARS and well over it in
        // bytes. `ServerHint` would throw on the result.
        val wide = "中".repeat(ServerHint.MAX_URL_BYTES / 2)
        assertTrue(wide.length < RendezvousUrl.MAX_CHARS)
        assertNull(RendezvousUrl.parse("https://$wide.test"))
    }

    /** The host is what a user is shown and asked to confirm, so it includes a non-default port. */
    @Test
    fun theHostShownToTheUserCarriesThePort() {
        assertEquals("192.168.1.20:8080", RendezvousUrl.parse("http://192.168.1.20:8080/")!!.host)
        assertEquals("pair.example.test", RendezvousUrl.parse("https://pair.example.test/")!!.host)
        assertFalse(RendezvousUrl.parse("http://pair.example.test")!!.secure)
        assertTrue(RendezvousUrl.parse("https://pair.example.test")!!.secure)
    }

    /** Trailing slashes are normalised away, so the path this client appends is unambiguous. */
    @Test
    fun theBaseIsNormalised() {
        assertEquals("https://pair.example.test", RendezvousUrl.parse("https://pair.example.test/")!!.base)
        assertEquals("https://pair.example.test/api", RendezvousUrl.parse("https://pair.example.test/api/")!!.base)
        assertEquals("https://pair.example.test", RendezvousUrl.parse("  https://pair.example.test  ")!!.base)
    }

    // -----------------------------------------------------------------------------------------

    private fun newDevice(drop: FakeDrop) =
        NewDeviceRendezvous(drop, HkdfKeyDerivation, FakeClock(), server, deviceKey)

    /**
     * The `sid` a rendezvous is filed under.
     *
     * Recovered from QR1 rather than exposed by [NewDeviceRendezvous], because the class has no
     * reason to publish it: the only thing that ever needs it is its own client, and a test that
     * has to read the wire to find it is a test reading what the phone reads.
     */
    private fun sidOf(device: NewDeviceRendezvous): ByteArray {
        val frame = java.util.Base64.getUrlDecoder()
            .decode(device.offerCode.removePrefix(PairingProtocol.QR_PREFIX))
        return frame.copyOfRange(2, 2 + PairingProtocol.SID_SIZE_BYTES)
    }

    /**
     * Flip one bit of the GCM tag and re-encode.
     *
     * Done on the **decoded bytes**, not on a base64 character. An unpadded base64url string whose
     * length is not a multiple of four ends in a character carrying two or four bits that decode to
     * nothing, so "change the last character" changes the decoded frame only sometimes — which is a
     * test that passes at random. The tag is the last 16 bytes of the frame by construction.
     */
    private fun tamperWithTheTag(sealCode: String): String {
        val decoder = java.util.Base64.getUrlDecoder()
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val frame = decoder.decode(sealCode.removePrefix(PairingProtocol.QR_PREFIX))
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x01).toByte()
        return PairingProtocol.QR_PREFIX + encoder.encodeToString(frame)
    }

    /**
     * An in-memory rendezvous with the server's own rules: keyed on `sid`, first write wins, single
     * use.
     *
     * A fake of the *server's behaviour*, not of the client's — which is why the rules are
     * reproduced rather than stubbed out. `force` is the attacker's door: it puts a blob somewhere
     * the honest protocol never would, which is how the `sid` binding and the tag check get
     * exercised.
     */
    private class FakeDrop : RendezvousClient {
        private val rows = HashMap<String, String>()

        /** When set, every collect returns this instead of looking at [rows]. */
        var answer: CollectResult? = null

        override fun deposit(sid: ByteArray, sealCode: String): DepositResult {
            val key = RendezvousProtocol.encodeSid(sid)
            if (rows.containsKey(key)) return DepositResult.AlreadyDeposited
            rows[key] = RendezvousProtocol.toBlob(sealCode)
            return DepositResult.Deposited(expiresAt = 0L)
        }

        override fun collect(sid: ByteArray): CollectResult {
            answer?.let { return it }
            val blob = rows.remove(RendezvousProtocol.encodeSid(sid)) ?: return CollectResult.Pending
            return CollectResult.Collected(RendezvousProtocol.fromBlob(blob))
        }

        /** Park a blob regardless of what is already there. Only an attacker can do this. */
        fun force(sid: ByteArray, sealCode: String) {
            rows[RendezvousProtocol.encodeSid(sid)] = RendezvousProtocol.toBlob(sealCode)
        }
    }
}
