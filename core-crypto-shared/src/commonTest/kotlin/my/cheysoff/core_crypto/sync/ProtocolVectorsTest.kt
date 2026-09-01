package my.cheysoff.core_crypto.sync

import my.cheysoff.core_crypto.HlcNode
import my.cheysoff.core_crypto.PassphraseCipher
import my.cheysoff.core_crypto.PinWrap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The cross-platform parity suite: every layer of the sync crypto, checked against the frozen
 * vectors in [ProtocolVectors].
 *
 * ## Read this on the first Mac build
 *
 * This is the test that decides whether the Apple crypto actuals are right. It runs on every
 * target the module has, so on the JVM it is a regression guard and on `iosSimulatorArm64` or
 * `macosArm64` it is the answer to the question the whole iOS port turns on: **can an iPhone read
 * a note that the Android phone or the desktop wrote?**
 *
 * If it passes, yes — every derived key, every record ID and every envelope matches byte for byte.
 * If it fails, the failing test names the layer, and because the layers are checked in dependency
 * order the first failure names the cause rather than a symptom:
 *
 *  - `HKDF`/`AccountRootKey` red   → `hmacSha256` is wrong.
 *  - `BlindedRecordId` red alone   → the HMAC is fine and the UTF-8 encoding or truncation is not.
 *  - `RecordEnvelope` red alone    → `aesGcmSeal`/`aesGcmOpen` is wrong, most likely the tag
 *                                    layout (see the seam's KDoc) or the associated data.
 *  - `PassphraseCipher` red alone  → PBKDF2, and see below: this one may be red for a reason that
 *                                    is not a bug.
 *
 * `docs/BUILDING-IOS.md` carries the same list with what to do about each.
 *
 * ## The one layer that is allowed to disagree
 *
 * [PassphraseCipher] derives from the user's PIN through the platform's own PBKDF2, and the
 * character-to-byte conversion inside it belongs to the JCA provider on the JVM. For an ASCII
 * password — which every PIN this app accepts is — they agree, and the vector below uses one. A
 * failure of `the PIN wrap opens` on Apple with everything else green would mean CommonCrypto and
 * the JCA provider disagree even on ASCII, which would be surprising and worth investigating, but
 * it is **not** an interop bug: a PIN wrap is written and read on one device and never travels.
 * Nothing else in this file has that excuse.
 */
class ProtocolVectorsTest {

    private val ark = hex(ProtocolVectors.ARK)

    // ---------------------------------------------------------------------------------------
    // Layer 1 — the key hierarchy
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the account keys derive from the ARK`() {
        val keys = AccountRootKey.derive(ark)
        assertEquals(ProtocolVectors.K_CONTENT, keys.kContent.toHex(), "K_content")
        assertEquals(ProtocolVectors.K_ID, keys.kId.toHex(), "K_id")
        assertEquals(ProtocolVectors.ACCOUNT_ID, keys.accountId.toHex(), "accountId")
    }

    @Test
    fun `the account handle renders to the same 22 characters`() {
        // The handle is a URL path segment and a QR payload field, so the *string* has to match
        // and not only the bytes. A base64 variant with padding or with `+/` would still decode to
        // the right value and still name a different account on the server.
        val keys = AccountRootKey.derive(ark)
        assertEquals(ProtocolVectors.ACCOUNT_ID_BASE64URL, Base64Url.encode(keys.accountId))
    }

    @Test
    fun `the HLC node pseudonym derives from the ARK and the device id`() {
        assertEquals(
            ProtocolVectors.HLC_NODE,
            HlcNode.derive(ark, ProtocolVectors.DEVICE_ID),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Layer 2 — record identity
    // ---------------------------------------------------------------------------------------

    @Test
    fun `blinded record ids match for both record types`() {
        val kId = hex(ProtocolVectors.K_ID)
        assertEquals(
            ProtocolVectors.BLINDED_NOTE_ID,
            BlindedRecordId.compute(kId, "note", ProtocolVectors.NOTE_UUID),
            "a note's blinded id",
        )
        assertEquals(
            ProtocolVectors.BLINDED_FOLDER_ID,
            BlindedRecordId.compute(kId, "folder", ProtocolVectors.FOLDER_UUID),
            "a folder's blinded id",
        )
    }

    @Test
    fun `a note and a folder sharing a uuid get unrelated ids`() {
        // Not a vector check but a property one, and it is the property `recType` is in the HMAC
        // message for: the server must not be able to tell that a note and a folder share an
        // underlying identifier.
        val kId = hex(ProtocolVectors.K_ID)
        assertNotEquals(
            BlindedRecordId.compute(kId, "note", ProtocolVectors.NOTE_UUID),
            BlindedRecordId.compute(kId, "folder", ProtocolVectors.NOTE_UUID),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Layer 3 — the record envelope
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the per-record key derives from K_content and the blinded id`() {
        // Asserted directly because it cannot be observed through seal/open: `blindedId` feeds
        // both this derivation AND the associated data, so removing it from either one alone
        // changes nothing a round-trip test can see.
        assertEquals(
            ProtocolVectors.PER_RECORD_KEY,
            RecordEnvelope.perRecordKeyBytes(
                hex(ProtocolVectors.K_CONTENT),
                ProtocolVectors.BLINDED_NOTE_ID,
            ).toHex(),
        )
    }

    @Test
    fun `the associated data is built the same way`() {
        // Also invisible through seal/open, for the same reason, and also a value both devices
        // must construct identically or nothing authenticates. `ver ‖ len ‖ blindedId`.
        assertEquals(
            ProtocolVectors.RECORD_ASSOCIATED_DATA,
            RecordEnvelope.associatedData(ProtocolVectors.BLINDED_NOTE_ID).toHex(),
        )
    }

    @Test
    fun `a committed envelope opens to its payload`() {
        // The whole stack in one assertion: HKDF, the per-record key, the associated data, AES-GCM
        // with tag verification, and the padding stripped back off. This envelope was sealed by
        // the JVM implementation; opening it here is exactly what an iPhone does to a record the
        // Android phone wrote.
        val opened = RecordEnvelope.open(
            kContent = hex(ProtocolVectors.K_CONTENT),
            blindedId = ProtocolVectors.BLINDED_NOTE_ID,
            envelope = hex(ProtocolVectors.RECORD_ENVELOPE),
        )
        assertContentEquals(ProtocolVectors.RECORD_PAYLOAD.encodeToByteArray(), opened)
    }

    @Test
    fun `a committed envelope offered under the wrong blinded id does not open`() {
        assertNull(
            RecordEnvelope.open(
                kContent = hex(ProtocolVectors.K_CONTENT),
                blindedId = ProtocolVectors.BLINDED_FOLDER_ID,
                envelope = hex(ProtocolVectors.RECORD_ENVELOPE),
            )
        )
    }

    @Test
    fun `a committed envelope with one flipped ciphertext byte does not open`() {
        // The tag-verification check, at the layer that matters rather than at the primitive. If
        // this passes on the JVM and fails on Apple, the Apple actual is returning unverified
        // plaintext — see `PlatformCrypto.apple.kt`, which is written specifically to avoid that.
        val envelope = hex(ProtocolVectors.RECORD_ENVELOPE)
        envelope[20] = (envelope[20].toInt() xor 0x01).toByte()
        assertNull(
            RecordEnvelope.open(
                kContent = hex(ProtocolVectors.K_CONTENT),
                blindedId = ProtocolVectors.BLINDED_NOTE_ID,
                envelope = envelope,
            )
        )
    }

    @Test
    fun `an envelope sealed here opens here`() {
        // The round trip, which the vector above deliberately does not cover: `seal` draws a random
        // nonce, so it cannot be pinned to a committed blob. Between the two, both directions of
        // the pair are exercised on every platform.
        val kContent = hex(ProtocolVectors.K_CONTENT)
        val payload = "a locally sealed body".encodeToByteArray()
        val sealed = RecordEnvelope.seal(kContent, ProtocolVectors.BLINDED_NOTE_ID, payload)
        assertContentEquals(
            payload,
            RecordEnvelope.open(kContent, ProtocolVectors.BLINDED_NOTE_ID, sealed),
        )
        assertEquals(
            SyncProtocol.PADDING_BUCKET_BYTES + 1 + SyncProtocol.NONCE_BYTES + SyncProtocol.TAG_BYTES,
            sealed.size,
            "a short payload must occupy exactly one padding bucket",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Layer 4 — the ARK wrap and the device label
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a committed ARK wrap unwraps to the ARK`() {
        val unwrapped = ArkCipher.unwrap(
            wrap = ArkWrap(
                iv = hex(ProtocolVectors.ARK_WRAP_IV),
                ciphertext = hex(ProtocolVectors.ARK_WRAP_CIPHERTEXT),
            ),
            passphrase = hex(ProtocolVectors.DB_PASSPHRASE),
        )
        assertContentEquals(ark, unwrapped)
    }

    @Test
    fun `an ARK wrap under the wrong passphrase returns null`() {
        val wrongPassphrase = hex(ProtocolVectors.DB_PASSPHRASE)
        wrongPassphrase[0] = (wrongPassphrase[0].toInt() xor 0xff).toByte()
        assertNull(
            ArkCipher.unwrap(
                wrap = ArkWrap(
                    iv = hex(ProtocolVectors.ARK_WRAP_IV),
                    ciphertext = hex(ProtocolVectors.ARK_WRAP_CIPHERTEXT),
                ),
                passphrase = wrongPassphrase,
            )
        )
    }

    @Test
    fun `the device label associated data is built the same way`() {
        assertEquals(
            ProtocolVectors.DEVICE_LABEL_ASSOCIATED_DATA,
            DeviceLabelCipher.associatedData(ProtocolVectors.DEVICE_PUBLIC_KEY_B64).toHex(),
        )
    }

    @Test
    fun `a committed sealed device label opens to its text`() {
        assertEquals(
            ProtocolVectors.DEVICE_LABEL,
            DeviceLabelCipher.open(
                ark = ark,
                devicePublicKeyB64 = ProtocolVectors.DEVICE_PUBLIC_KEY_B64,
                sealed = hex(ProtocolVectors.SEALED_DEVICE_LABEL),
            ),
        )
    }

    @Test
    fun `a sealed device label bound to another device's key does not open`() {
        assertNull(
            DeviceLabelCipher.open(
                ark = ark,
                devicePublicKeyB64 = ProtocolVectors.DEVICE_PUBLIC_KEY_B64 + "X",
                sealed = hex(ProtocolVectors.SEALED_DEVICE_LABEL),
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // Layer 5 — the PIN wrap. Local-only; see the class KDoc before treating a failure as interop.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a committed PIN wrap opens with the right PIN and not with a wrong one`() {
        val wrap = PinWrap(
            salt = hex(ProtocolVectors.PIN_WRAP_SALT),
            iv = hex(ProtocolVectors.PIN_WRAP_IV),
            ciphertext = hex(ProtocolVectors.PIN_WRAP_CIPHERTEXT),
            iterations = PassphraseCipher.ITERATIONS,
        )
        assertContentEquals(
            hex(ProtocolVectors.PASSPHRASE_PLAINTEXT),
            PassphraseCipher.unwrapWithPin(wrap, ProtocolVectors.PIN.toCharArray()),
        )
        assertNull(PassphraseCipher.unwrapWithPin(wrap, "999999".toCharArray()))
    }
}
