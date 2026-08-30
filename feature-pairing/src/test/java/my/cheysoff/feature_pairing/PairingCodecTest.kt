package my.cheysoff.feature_pairing

import my.cheysoff.feature_pairing.protocol.AccountBundle
import my.cheysoff.feature_pairing.protocol.P256
import my.cheysoff.feature_pairing.protocol.PairingCodec
import my.cheysoff.feature_pairing.protocol.PairingFailure
import my.cheysoff.feature_pairing.protocol.PairingProtocol
import my.cheysoff.feature_pairing.protocol.PairingWireException
import my.cheysoff.feature_pairing.protocol.ServerHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * The wire format, held to the byte layout documented on [PairingProtocol].
 *
 * These tests exist because that comment is a specification a second implementer is expected to
 * work from. If the code and the comment ever disagree, one of these fails.
 */
class PairingCodecTest {

    private val point = P256.encodePublicKey(P256.generateEphemeralKeyPair().public as ECPublicKey)
    private val sid = ByteArray(16) { it.toByte() }

    // -- offer (QR1) --------------------------------------------------------------------------

    @Test
    fun offerRoundTrips() {
        val hint = ServerHint("https://notes.example/", ByteArray(32) { (it * 7).toByte() })
        val text = PairingCodec.encodeOffer(sid, point, hint)
        val decoded = PairingCodec.decodeOffer(text)

        assertArrayEqualsBytes(sid, decoded.sid)
        assertArrayEqualsBytes(point, decoded.encodedEphemeralKey)
        assertEquals(hint, decoded.serverHint)
    }

    @Test
    fun offerRoundTripsWithNoServerHint() {
        val decoded = PairingCodec.decodeOffer(PairingCodec.encodeOffer(sid, point, ServerHint.NONE))
        assertEquals("", decoded.serverHint.url)
        assertNull(decoded.serverHint.spkiPinSha256)
    }

    /** The documented shape: `"MNP1:" || base64url-nopad(frame)`, and the frame's first bytes. */
    @Test
    fun offerMatchesTheDocumentedLayout() {
        val text = PairingCodec.encodeOffer(sid, point, ServerHint.NONE)
        assertTrue(text.startsWith("MNP1:"))

        val frame = Base64.getUrlDecoder().decode(text.removePrefix("MNP1:"))
        assertEquals("version byte", 0x01.toByte(), frame[0])
        assertEquals("kind byte: offer", 0x01.toByte(), frame[1])
        assertArrayEqualsBytes(sid, frame.copyOfRange(2, 18))
        assertEquals("point length prefix", 65.toByte(), frame[18])
        assertArrayEqualsBytes(point, frame.copyOfRange(19, 84))
        // urlLen = 0, pinLen = 0, and then nothing at all.
        assertEquals(0.toByte(), frame[84])
        assertEquals(0.toByte(), frame[85])
        assertEquals(0.toByte(), frame[86])
        assertEquals(87, frame.size)
    }

    /** Base64url, not base64: no `+`, no `/`, and no `=` padding. */
    @Test
    fun payloadUsesTheUrlSafeAlphabetWithoutPadding() {
        // Many random payloads, because whether padding would appear depends on the frame length.
        repeat(50) {
            val key = P256.encodePublicKey(P256.generateEphemeralKeyPair().public as ECPublicKey)
            val body = PairingCodec.encodeOffer(sid, key, ServerHint("a".repeat(it)))
                .removePrefix("MNP1:")
            assertTrue("no '+' in $body", '+' !in body)
            assertTrue("no '/' in $body", '/' !in body)
            assertTrue("no '=' in $body", '=' !in body)
        }
    }

    // -- seal (QR2) ---------------------------------------------------------------------------

    @Test
    fun sealRoundTrips() {
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val seal = ByteArray(64) { (it * 3).toByte() }
        val decoded = PairingCodec.decodeSeal(PairingCodec.encodeSeal(sid, point, nonce, seal))

        assertArrayEqualsBytes(sid, decoded.sid)
        assertArrayEqualsBytes(point, decoded.encodedEphemeralKey)
        assertArrayEqualsBytes(nonce, decoded.nonce)
        assertArrayEqualsBytes(seal, decoded.seal)
    }

    @Test
    fun sealMatchesTheDocumentedLayout() {
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val seal = ByteArray(64) { (it * 3).toByte() }
        val frame = Base64.getUrlDecoder()
            .decode(PairingCodec.encodeSeal(sid, point, nonce, seal).removePrefix("MNP1:"))

        assertEquals(0x01.toByte(), frame[0])
        assertEquals("kind byte: seal", 0x02.toByte(), frame[1])
        assertArrayEqualsBytes(sid, frame.copyOfRange(2, 18))
        assertEquals(65.toByte(), frame[18])
        assertArrayEqualsBytes(point, frame.copyOfRange(19, 84))
        assertArrayEqualsBytes(nonce, frame.copyOfRange(84, 96))
        assertEquals("sealLen high byte", 0.toByte(), frame[96])
        assertEquals("sealLen low byte", 64.toByte(), frame[97])
        assertEquals(98 + 64, frame.size)
    }

    /** A GCM output shorter than its own tag is malformed, not a tag failure. */
    @Test
    fun rejectsSealShorterThanAGcmTag() {
        val text = PairingCodec.encodeSeal(sid, point, ByteArray(12), ByteArray(16))
        assertFailure(PairingFailure.MALFORMED) { PairingCodec.decodeSeal(text) }
    }

    // -- rejections ---------------------------------------------------------------------------

    @Test
    fun rejectsSomethingThatIsNotAPairingCode() {
        assertFailure(PairingFailure.NOT_A_PAIRING_CODE) { PairingCodec.decodeOffer("") }
        assertFailure(PairingFailure.NOT_A_PAIRING_CODE) {
            PairingCodec.decodeOffer("https://example.com/some-other-qr")
        }
        assertFailure(PairingFailure.NOT_A_PAIRING_CODE) { PairingCodec.decodeOffer("MNP1:not base64!") }
    }

    @Test
    fun rejectsAnUnknownVersion() {
        val frame = Base64.getUrlDecoder()
            .decode(PairingCodec.encodeOffer(sid, point, ServerHint.NONE).removePrefix("MNP1:"))
        frame[0] = 0x02
        assertFailure(PairingFailure.UNSUPPORTED_VERSION) { PairingCodec.decodeOffer(rewrap(frame)) }
    }

    /** Feeding QR2 to the step that wants QR1 is a sequencing mistake, not a corrupt code. */
    @Test
    fun rejectsTheOtherKindOfCode() {
        val seal = PairingCodec.encodeSeal(sid, point, ByteArray(12), ByteArray(32))
        assertFailure(PairingFailure.WRONG_CODE_KIND) { PairingCodec.decodeOffer(seal) }

        val offer = PairingCodec.encodeOffer(sid, point, ServerHint.NONE)
        assertFailure(PairingFailure.WRONG_CODE_KIND) { PairingCodec.decodeSeal(offer) }
    }

    @Test
    fun rejectsATruncatedFrame() {
        val frame = Base64.getUrlDecoder()
            .decode(PairingCodec.encodeOffer(sid, point, ServerHint.NONE).removePrefix("MNP1:"))
        for (cut in listOf(1, 2, 10, 18, 19, 50, frame.size - 1)) {
            assertFailure(PairingFailure.MALFORMED, "cut at $cut") {
                PairingCodec.decodeOffer(rewrap(frame.copyOfRange(0, cut)))
            }
        }
    }

    /**
     * Trailing bytes are rejected rather than ignored.
     *
     * "Extra bytes are ignored" is how a parser becomes somewhere to hide things, and how two
     * implementations end up disagreeing about what a frame said.
     */
    @Test
    fun rejectsTrailingBytes() {
        val frame = Base64.getUrlDecoder()
            .decode(PairingCodec.encodeOffer(sid, point, ServerHint.NONE).removePrefix("MNP1:"))
        assertFailure(PairingFailure.MALFORMED) {
            PairingCodec.decodeOffer(rewrap(frame + byteArrayOf(0x42)))
        }
    }

    /** A length prefix that claims more than the frame holds must not read past the end. */
    @Test
    fun rejectsALengthPrefixThatOverrunsTheFrame() {
        val frame = Base64.getUrlDecoder()
            .decode(PairingCodec.encodeOffer(sid, point, ServerHint.NONE).removePrefix("MNP1:"))
        frame[18] = 0xFF.toByte() // claim a 255-byte ephemeral point in an 87-byte frame
        assertFailure(PairingFailure.MALFORMED) { PairingCodec.decodeOffer(rewrap(frame)) }
    }

    @Test
    fun rejectsAnSpkiPinThatIsNotASha256Digest() {
        val frame = Base64.getUrlDecoder()
            .decode(PairingCodec.encodeOffer(sid, point, ServerHint.NONE).removePrefix("MNP1:"))
        // pinLen = 4 with four bytes of pin appended: structurally valid, semantically wrong.
        frame[86] = 4
        assertFailure(PairingFailure.MALFORMED) {
            PairingCodec.decodeOffer(rewrap(frame + ByteArray(4)))
        }
    }

    // -- bundle -------------------------------------------------------------------------------

    @Test
    fun bundleRoundTrips() {
        val bundle = AccountBundle(
            ark = ByteArray(32) { (it * 5).toByte() },
            accountId = "GxK-9wq2",
            config = """{"url":"https://notes.example/"}""",
        )
        val decoded = PairingCodec.decodeBundle(PairingCodec.encodeBundle(bundle))
        assertArrayEqualsBytes(bundle.ark, decoded.ark)
        assertEquals(bundle.accountId, decoded.accountId)
        assertEquals(bundle.config, decoded.config)
    }

    @Test
    fun bundleRejectsAnUnknownVersion() {
        val encoded = PairingCodec.encodeBundle(
            AccountBundle(ByteArray(32), "id")
        ).also { it[0] = 0x09 }
        assertFailure(PairingFailure.UNSUPPORTED_VERSION) { PairingCodec.decodeBundle(encoded) }
    }

    /** Never printed in a crash report or a log line. */
    @Test
    fun bundleToStringHidesTheKeyMaterial() {
        val bundle = AccountBundle(ByteArray(32) { 0x41 }, "secret-account", "cfg")
        val printed = bundle.toString()
        assertTrue("must not print the ARK", "4141" !in printed)
        assertTrue("must not print the ARK", "AAAA" !in printed)
        assertTrue("must not print the account id", "secret-account" !in printed)
    }

    // -- helpers ------------------------------------------------------------------------------

    private fun rewrap(frame: ByteArray) =
        "MNP1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(frame)

    private fun assertFailure(expected: PairingFailure, note: String = "", block: () -> Unit) {
        try {
            block()
        } catch (e: PairingWireException) {
            assertEquals(note, expected, e.failure)
            return
        }
        throw AssertionError("expected $expected but nothing was thrown ($note)")
    }
}
