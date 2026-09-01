package my.cheysoff.core_crypto.sync

import my.cheysoff.core_crypto.HlcNode
import my.cheysoff.core_crypto.PassphraseCipher
import my.cheysoff.core_crypto.platform.aesGcmSeal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Recomputes every value in [ProtocolVectors] from the JVM implementation and checks it still
 * matches — and writes a freshly generated copy of that file into `build/` either way, so that
 * regenerating it is a copy rather than a transcription.
 *
 * ## What this test is for, and what it is not for
 *
 * It is the **generator** for the committed vectors, and it is a **protocol-break alarm**. Those
 * are the same test run in two directions.
 *
 * It is emphatically NOT the test that says the vectors are correct. It cannot be: it computes the
 * expected answers with the same code it then checks them against, so it would pass just as
 * happily if this project's HKDF were wrong. Correctness of the primitives comes from published
 * vectors in `PlatformCryptoKnownAnswerTest`, `HkdfTest` and `AesGcmKnownAnswerTest`; correctness
 * of the composition comes from those primitives being right and the composition being one
 * implementation.
 *
 * ## When this test goes red
 *
 * **A red result here is a protocol break, not a stale fixture.** Every constant in
 * [ProtocolVectors] is a value some already-shipped device has stored or will recompute:
 * `accountId` names the account on the server, `blindedId` names each record, and the envelope is
 * a real one that a real key opens. If the code no longer produces them, then a build carrying
 * that change cannot read what an installed build wrote. Regenerating the file makes the test
 * green and does nothing whatever about that.
 *
 * So: read the diff first. If the change was intended — a genuine `.../v2/...` protocol revision
 * with a migration behind it — copy the generated file over the committed one, exactly as it is
 * written, from the path the failure message names. If it was not intended, the fix is in the
 * code, not here.
 *
 * ## Why it lives in `jvmTest`
 *
 * Because it writes a file, and file IO is not available in `commonTest`. Nothing else about it is
 * JVM-specific: the vectors it produces are consumed by `ProtocolVectorsTest`, which is
 * `commonTest` and therefore runs on the Apple targets too. That split is the whole design — the
 * JVM is where the answers are minted, every platform is where they are checked.
 */
class ProtocolVectorsAreFrozenTest {

    @Test
    fun `every committed vector is still what this build computes`() {
        val computed = compute()
        val generatedFile = writeGeneratedFile(computed)

        val committed = committedValues()
        val mismatches = computed.keys.filter { committed[it] != computed[it] }
        if (mismatches.isNotEmpty()) {
            val detail = mismatches.joinToString("\n") { key ->
                "  $key\n    committed: ${committed[key]}\n    computed:  ${computed[key]}"
            }
            throw AssertionError(
                "\nThe protocol vectors no longer match what this build computes.\n\n" +
                    "THIS IS A PROTOCOL BREAK unless you meant it. Every value below is one an\n" +
                    "installed device has already stored or will recompute; a build that produces\n" +
                    "different ones cannot read what an installed build wrote.\n\n" +
                    detail + "\n\n" +
                    "If the change WAS intended, copy the regenerated file over the committed one:\n" +
                    "  ${generatedFile.absolutePath}\n" +
                    "  -> core-crypto-shared/src/commonTest/kotlin/my/cheysoff/core_crypto/sync/" +
                    "ProtocolVectors.kt\n"
            )
        }
        // Guards the other direction: a key added to the generator and never committed would
        // otherwise pass silently, and the Apple run would then check fewer things than it looks
        // like it checks.
        assertEquals(
            "the generator and the committed file must describe the same set of vectors",
            computed.keys.sorted(),
            committed.keys.sorted(),
        )
    }

    /**
     * The inputs, fixed forever.
     *
     * They are arbitrary but they are not random: every one of them is written down here so that
     * the vectors are reproducible from this file alone. An input drawn from `SecureRandom` would
     * make the whole suite unrepeatable.
     */
    private object Inputs {
        val ark = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        const val DEVICE_ID = "Zm9ydHktdHdvLWJ5dGVzLWlk"
        const val NOTE_UUID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
        const val FOLDER_UUID = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        const val RECORD_PAYLOAD = "{\"t\":\"note\",\"u\":\"1b4e28ba\",\"c\":\"hello \\u00e9\"}"
        val dbPassphrase = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")
        const val DEVICE_PUBLIC_KEY_B64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"
        const val DEVICE_LABEL = "Vova's Pixel 7"
        const val PIN = "246813"
        val passphrasePlaintext = hex("0f1e2d3c4b5a69788796a5b4c3d2e1f0")

        /** Fixed stand-ins for the values the ciphers would otherwise draw from the CSPRNG. */
        val recordNonce = hex("cafebabefacedbaddecaf888")
        val arkWrapIv = hex("101112131415161718191a1b")
        val labelNonce = hex("202122232425262728292a2b")
        val pinSalt = hex("303132333435363738393a3b3c3d3e3f")
        val pinIv = hex("404142434445464748494a4b")
    }

    /**
     * Every vector, keyed by the constant name it is committed under.
     *
     * The three sealed blobs are built by calling [aesGcmSeal] with a **fixed** nonce rather than
     * by calling `seal()`, which would draw a random one and make the output unrepeatable. That
     * loses nothing that matters: `seal` and `open` are the same key derivation, the same
     * associated data and the same cipher, so a committed blob that `open` recovers exercises
     * every part of the pair except the RNG — and the RNG is checked separately, in
     * `PlatformCryptoKnownAnswerTest`. The `open` direction is also the one that matters for
     * interop: it is what a device does to something another device wrote.
     */
    private fun compute(): Map<String, String> {
        val keys = AccountRootKey.derive(Inputs.ark)
        val blindedNoteId = BlindedRecordId.compute(keys.kId, "note", Inputs.NOTE_UUID)
        val blindedFolderId = BlindedRecordId.compute(keys.kId, "folder", Inputs.FOLDER_UUID)
        val perRecordKey = RecordEnvelope.perRecordKeyBytes(keys.kContent, blindedNoteId)

        val payload = Inputs.RECORD_PAYLOAD.encodeToByteArray()
        val recordEnvelope = ByteArray(1) { SyncProtocol.ENVELOPE_VERSION } +
            Inputs.recordNonce +
            aesGcmSeal(
                key = perRecordKey,
                nonce = Inputs.recordNonce,
                aad = RecordEnvelope.associatedData(blindedNoteId),
                plaintext = RecordPadding.pad(payload),
            )

        val arkWrapKey = Hkdf.derive(
            ikm = Inputs.dbPassphrase,
            salt = null,
            info = SyncProtocol.INFO_ARK_WRAP.encodeToByteArray(),
            length = SyncProtocol.DERIVED_KEY_BYTES,
        )
        val arkWrapCiphertext = aesGcmSeal(
            key = arkWrapKey,
            nonce = Inputs.arkWrapIv,
            aad = null,
            plaintext = Inputs.ark,
        )

        val labelKey = Hkdf.derive(
            ikm = Inputs.ark,
            salt = null,
            info = SyncProtocol.INFO_DEVICE_LABEL.encodeToByteArray(),
            length = SyncProtocol.DERIVED_KEY_BYTES,
        )
        val labelText = Inputs.DEVICE_LABEL.encodeToByteArray()
        val labelPlaintext = ByteArray(SyncProtocol.DEVICE_LABEL_PLAINTEXT_BYTES).also {
            it[0] = (labelText.size ushr 8).toByte()
            it[1] = labelText.size.toByte()
            labelText.copyInto(it, destinationOffset = 2)
        }
        val sealedLabel = ByteArray(1) { SyncProtocol.DEVICE_LABEL_VERSION } +
            Inputs.labelNonce +
            aesGcmSeal(
                key = labelKey,
                nonce = Inputs.labelNonce,
                aad = DeviceLabelCipher.associatedData(Inputs.DEVICE_PUBLIC_KEY_B64),
                plaintext = labelPlaintext,
            )

        val pinKey = my.cheysoff.core_crypto.platform.pbkdf2HmacSha256(
            password = Inputs.PIN.toCharArray(),
            salt = Inputs.pinSalt,
            iterations = PassphraseCipher.ITERATIONS,
            keyBytes = 32,
        )
        val pinCiphertext = aesGcmSeal(
            key = pinKey,
            nonce = Inputs.pinIv,
            aad = null,
            plaintext = Inputs.passphrasePlaintext,
        )

        return linkedMapOf(
            "ARK" to Inputs.ark.toHex(),
            "DEVICE_ID" to Inputs.DEVICE_ID,
            "K_CONTENT" to keys.kContent.toHex(),
            "K_ID" to keys.kId.toHex(),
            "ACCOUNT_ID" to keys.accountId.toHex(),
            "ACCOUNT_ID_BASE64URL" to Base64Url.encode(keys.accountId),
            "HLC_NODE" to HlcNode.derive(Inputs.ark, Inputs.DEVICE_ID),
            "NOTE_UUID" to Inputs.NOTE_UUID,
            "BLINDED_NOTE_ID" to blindedNoteId,
            "FOLDER_UUID" to Inputs.FOLDER_UUID,
            "BLINDED_FOLDER_ID" to blindedFolderId,
            "PER_RECORD_KEY" to perRecordKey.toHex(),
            "RECORD_ASSOCIATED_DATA" to RecordEnvelope.associatedData(blindedNoteId).toHex(),
            "RECORD_PAYLOAD" to Inputs.RECORD_PAYLOAD,
            "RECORD_ENVELOPE" to recordEnvelope.toHex(),
            "DB_PASSPHRASE" to Inputs.dbPassphrase.toHex(),
            "ARK_WRAP_IV" to Inputs.arkWrapIv.toHex(),
            "ARK_WRAP_CIPHERTEXT" to arkWrapCiphertext.toHex(),
            "DEVICE_PUBLIC_KEY_B64" to Inputs.DEVICE_PUBLIC_KEY_B64,
            "DEVICE_LABEL" to Inputs.DEVICE_LABEL,
            "DEVICE_LABEL_ASSOCIATED_DATA" to
                DeviceLabelCipher.associatedData(Inputs.DEVICE_PUBLIC_KEY_B64).toHex(),
            "SEALED_DEVICE_LABEL" to sealedLabel.toHex(),
            "PIN" to Inputs.PIN,
            "PIN_WRAP_SALT" to Inputs.pinSalt.toHex(),
            "PIN_WRAP_IV" to Inputs.pinIv.toHex(),
            "PIN_WRAP_CIPHERTEXT" to pinCiphertext.toHex(),
            "PASSPHRASE_PLAINTEXT" to Inputs.passphrasePlaintext.toHex(),
        )
    }

    /**
     * Reads the committed constants back out of [ProtocolVectors] by name.
     *
     * Listed by hand rather than read reflectively. Reflection would drift silently if a constant
     * were renamed, and the whole point of this file is that drift is loud.
     */
    private fun committedValues(): Map<String, String> = linkedMapOf(
        "ARK" to ProtocolVectors.ARK,
        "DEVICE_ID" to ProtocolVectors.DEVICE_ID,
        "K_CONTENT" to ProtocolVectors.K_CONTENT,
        "K_ID" to ProtocolVectors.K_ID,
        "ACCOUNT_ID" to ProtocolVectors.ACCOUNT_ID,
        "ACCOUNT_ID_BASE64URL" to ProtocolVectors.ACCOUNT_ID_BASE64URL,
        "HLC_NODE" to ProtocolVectors.HLC_NODE,
        "NOTE_UUID" to ProtocolVectors.NOTE_UUID,
        "BLINDED_NOTE_ID" to ProtocolVectors.BLINDED_NOTE_ID,
        "FOLDER_UUID" to ProtocolVectors.FOLDER_UUID,
        "BLINDED_FOLDER_ID" to ProtocolVectors.BLINDED_FOLDER_ID,
        "PER_RECORD_KEY" to ProtocolVectors.PER_RECORD_KEY,
        "RECORD_ASSOCIATED_DATA" to ProtocolVectors.RECORD_ASSOCIATED_DATA,
        "RECORD_PAYLOAD" to ProtocolVectors.RECORD_PAYLOAD,
        "RECORD_ENVELOPE" to ProtocolVectors.RECORD_ENVELOPE.filterNot { it.isWhitespace() },
        "DB_PASSPHRASE" to ProtocolVectors.DB_PASSPHRASE,
        "ARK_WRAP_IV" to ProtocolVectors.ARK_WRAP_IV,
        "ARK_WRAP_CIPHERTEXT" to ProtocolVectors.ARK_WRAP_CIPHERTEXT,
        "DEVICE_PUBLIC_KEY_B64" to ProtocolVectors.DEVICE_PUBLIC_KEY_B64,
        "DEVICE_LABEL" to ProtocolVectors.DEVICE_LABEL,
        "DEVICE_LABEL_ASSOCIATED_DATA" to ProtocolVectors.DEVICE_LABEL_ASSOCIATED_DATA,
        "SEALED_DEVICE_LABEL" to ProtocolVectors.SEALED_DEVICE_LABEL,
        "PIN" to ProtocolVectors.PIN,
        "PIN_WRAP_SALT" to ProtocolVectors.PIN_WRAP_SALT,
        "PIN_WRAP_IV" to ProtocolVectors.PIN_WRAP_IV,
        "PIN_WRAP_CIPHERTEXT" to ProtocolVectors.PIN_WRAP_CIPHERTEXT,
        "PASSPHRASE_PLAINTEXT" to ProtocolVectors.PASSPHRASE_PLAINTEXT,
    )

    /** Emits the committable `ProtocolVectors.kt`, whether or not anything changed. */
    private fun writeGeneratedFile(values: Map<String, String>): File {
        val out = File("build/generated-protocol-vectors/ProtocolVectors.kt")
        out.parentFile.mkdirs()
        val body = values.entries.joinToString("\n\n") { (name, value) ->
            if (name == "RECORD_ENVELOPE") {
                // 4,125 bytes on one line is unreadable and unreviewable. `hex()` strips
                // whitespace, so the wrapped form parses to the same bytes.
                val wrapped = value.chunked(96).joinToString("\n") { "        $it" }
                "    const val $name: String = \"\"\"\n$wrapped\n    \"\"\""
            } else {
                // Escaped, because one vector is a JSON payload and therefore full of quotes.
                // Its non-ASCII character is left as itself: the file is written and read as
                // UTF-8, and a literal `é` in the fixture is a better test of the UTF-8 path
                // through `encodeToByteArray` than an escape that the compiler resolves.
                val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                "    const val $name: String = \"$escaped\""
            }
        }
        out.writeText(HEADER + body + "\n}\n")
        return out
    }

    private companion object {
        /**
         * Kept in this file rather than beside the constants so that a regenerated file is
         * byte-identical to a hand-copied one — the whole file, header included, is generator
         * output.
         */
        val HEADER = """
            package my.cheysoff.core_crypto.sync

            /**
             * Frozen protocol vectors: what this project's crypto produces for one fixed set of
             * inputs.
             *
             * GENERATED. Do not edit by hand. `ProtocolVectorsAreFrozenTest` (in `jvmTest`)
             * regenerates this file into `core-crypto-shared/build/generated-protocol-vectors/`
             * every time it runs, and fails when what it computes stops matching what is here.
             *
             * ## What these are for
             *
             * `ProtocolVectorsTest`, in `commonTest`, checks the crypto against them on **every**
             * target. On the JVM that is a regression guard. On an Apple target it is the answer
             * to the only question that matters about the Apple crypto actuals: does an iPhone
             * derive the same keys, compute the same record IDs and open the same envelopes as the
             * phone and the laptop? If it does not, an iPhone cannot read a note the others wrote,
             * and the failure presents as data corruption rather than as a crypto mismatch, which
             * is why this file exists at all.
             *
             * ## What they are NOT
             *
             * They are not evidence that the crypto is *correct*. They were produced by the same
             * implementation they test. Correctness is pinned separately, by published vectors
             * from RFC 4231, RFC 5869, RFC 7914 and the McGrew & Viega GCM paper — see
             * `PlatformCryptoKnownAnswerTest`, `HkdfTest` and `AesGcmKnownAnswerTest`. These two
             * kinds of vector answer different questions and neither substitutes for the other.
             *
             * ## Changing this file
             *
             * Every value here is one that an installed device has stored or will recompute.
             * Regenerating them to make a red test green does not fix anything — it removes the
             * alarm and leaves the break. See `ProtocolVectorsAreFrozenTest`'s KDoc.
             */
            internal object ProtocolVectors {

        """.trimIndent()
    }
}
