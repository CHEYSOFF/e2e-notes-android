package my.cheysoff.desktop.store

import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.BlindedRecordId
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordCodecTest {

    private val keys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val other = AccountRootKey.derive(AccountRootKey.generateArk())
    private val codec = RecordCodec(keys)

    private fun payload(id: String = "note-1", title: String = "Groceries") =
        NoteRecords.toPayload(
            NoteRow(
                note = Note(id = id, title = title, content = "milk, eggs"),
                rowClock = Hlc(1_756_612_345_678L, 0, "abcd"),
                clocks = emptyMap(),
            ),
        )

    @Test
    fun `a sealed record opens back to the same payload`() {
        val sealed = codec.seal(payload())
        val result = codec.open(sealed.blindedId, sealed.envelope)

        assertTrue("was $result", result is OpenResult.Ok)
        assertEquals(payload(), (result as OpenResult.Ok).payload)
    }

    /**
     * The whole point of the store's shape: what lands on disk is what would land on the server.
     * If this ever fails, the desktop is storing plaintext under a filename that looks encrypted.
     */
    @Test
    fun `the envelope contains no plaintext from the note`() {
        val sealed = codec.seal(payload(title = "a very distinctive title"))
        val asText = String(sealed.envelope, Charsets.ISO_8859_1)

        assertFalse(asText.contains("a very distinctive title"))
        assertFalse(asText.contains("milk, eggs"))
        assertFalse(asText.contains("note-1"))
    }

    /** The blinded ID is the record's only visible label, and it must not be the UUID. */
    @Test
    fun `the blinded id is not the note uuid and is derived from K_id`() {
        val blindedId = codec.blindedIdOf(payload())
        assertNotEquals("note-1", blindedId)
        assertEquals(BlindedRecordId.compute(keys.kId, "note", "note-1"), blindedId)
        assertNotEquals(RecordCodec(other).blindedIdOf(payload()), blindedId)
    }

    @Test
    fun `a note and a folder with the same uuid get unrelated labels`() {
        val noteId = BlindedRecordId.compute(keys.kId, RecordType.NOTE.wireKey, "shared")
        val folderId = BlindedRecordId.compute(keys.kId, RecordType.FOLDER.wireKey, "shared")
        assertNotEquals(noteId, folderId)
    }

    @Test
    fun `another account cannot open this account's record`() {
        val sealed = codec.seal(payload())
        assertEquals(OpenResult.Unreadable, RecordCodec(other).open(sealed.blindedId, sealed.envelope))
    }

    @Test
    fun `a flipped ciphertext bit is refused`() {
        val sealed = codec.seal(payload())
        sealed.envelope[sealed.envelope.size - 1] =
            (sealed.envelope[sealed.envelope.size - 1] + 1).toByte()
        assertEquals(OpenResult.Unreadable, codec.open(sealed.blindedId, sealed.envelope))
    }

    @Test
    fun `an envelope offered under a different label is refused`() {
        val sealed = codec.seal(payload())
        val otherLabel = codec.blindedIdOf(payload(id = "note-2"))
        assertEquals(OpenResult.Unreadable, codec.open(otherLabel, sealed.envelope))
    }

    @Test
    fun `a truncated envelope is refused`() {
        val sealed = codec.seal(payload())
        assertEquals(
            OpenResult.Unreadable,
            codec.open(sealed.blindedId, sealed.envelope.copyOf(8)),
        )
    }

    /**
     * §4's check on `RecordCodec`: recompute the blinded ID from the *opened* payload and refuse
     * the record unless it matches the label it arrived under. This is what restores the binding
     * that moving `recType` and `uuid` inside the envelope gave up — and it extends it to `uuid`,
     * which the associated data never covered.
     *
     * Constructed the only way it can be: seal a payload for one identity, then hand it back under
     * that label with the identity inside changed. That is impossible without `K_content`, which is
     * why the real failure it guards against is a client bug rather than a server.
     */
    @Test
    fun `a payload whose identity does not match its label is Mislabelled, not Ok`() {
        // Seal a payload for note-2 under note-1's label by going around the codec's own pairing.
        val labelOfOne = codec.blindedIdOf(payload(id = "note-1"))
        val bytes = RecordPayloadCodec.encode(payload(id = "note-2"))
        val envelope = my.cheysoff.core_crypto.sync.RecordEnvelope.seal(
            keys.kContent,
            labelOfOne,
            bytes,
        )

        assertEquals(OpenResult.Mislabelled, codec.open(labelOfOne, envelope))
    }

    @Test
    fun `two seals of the same payload differ, because the nonce is random`() {
        val first = codec.seal(payload())
        val second = codec.seal(payload())
        assertEquals(first.blindedId, second.blindedId)
        assertFalse(first.envelope.contentEquals(second.envelope))
    }

    /**
     * Padding hides note length: an empty note and a short one occupy the same number of 4 KiB
     * buckets, so the stored size reveals a bucket index rather than a byte count.
     */
    @Test
    fun `a short note and an empty one seal to the same length`() {
        val empty = codec.seal(payload(title = ""))
        val short = codec.seal(payload(title = "a somewhat longer title than nothing"))
        assertEquals(empty.envelope.size, short.envelope.size)
    }
}
