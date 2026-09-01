package my.cheysoff.core_sync_codec

import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope layer: seal, open, and the three checks §4 of the phase-3 plan puts on this class.
 *
 * The crypto itself is `RecordEnvelope`'s and `BlindedRecordId`'s and is tested in
 * `:core-crypto-shared`. What is tested here is the wiring plus the one check neither of them can
 * make alone — that the record's own idea of what it is agrees with the name it arrived under.
 */
class RecordCodecTest {

    private val keys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val otherKeys = AccountRootKey.derive(AccountRootKey.generateArk())
    private val codec = RecordCodec(keys)

    private val record = SyncRecord(
        type = RecordType.NOTE,
        uuid = "note-1",
        rowClock = Hlc(1_756_612_345_678L, 3, "a1b2c3d4"),
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("Groceries"),
            FieldClocks.CONTENT to FieldValue.of("<p>milk</p>", "html"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of("0"),
            FieldClocks.FAVORITE to FieldValue.of("0"),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("200"),
            FieldClocks.DELETED to FieldValue.of("0", null),
        ),
    )

    private fun payload() = SyncRecords.toPayload(record, createdAt = 100L)

    @Test
    fun `a sealed record opens back to the record that went in`() {
        val sealed = codec.seal(payload())
        val opened = codec.open(sealed.blindedId, sealed.envelope)
        assertEquals(record, SyncRecords.fromPayload((opened as OpenResult.Ok).payload))
    }

    /**
     * The whole point of the blinded id: the server files the record under a name that does not
     * name it. Two records of the same account get different labels, and the same record gets the
     * same label every time or the server would see one note as an unbounded stream of new ones.
     */
    @Test
    fun `the blinded id is stable for a record and different between records`() {
        val first = codec.seal(payload()).blindedId
        assertEquals(first, codec.seal(payload()).blindedId)
        assertNotEquals(first, codec.blindedIdOf(RecordType.FOLDER.wireKey, "note-1"))
        assertNotEquals(first, codec.blindedIdOf(RecordType.NOTE.wireKey, "note-2"))
    }

    /** A record belonging to another account is exactly the F1 case: it will not open, and that
     * is all this device can say about it. */
    @Test
    fun `another account's envelope is unreadable`() {
        val sealed = RecordCodec(otherKeys).seal(payload())
        assertEquals(OpenResult.Unreadable, codec.open(sealed.blindedId, sealed.envelope))
    }

    @Test
    fun `a tampered envelope is unreadable rather than partially trusted`() {
        val sealed = codec.seal(payload())
        val damaged = sealed.envelope.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(OpenResult.Unreadable, codec.open(sealed.blindedId, damaged))
    }

    /**
     * §4's identity check. An envelope re-filed under another record's label decrypts — the
     * per-record key and the associated data both come from the label, so a *correctly* re-sealed
     * blob verifies — and the payload then says it is a different record. Nothing but recomputing
     * the HMAC over the opened `(recType, uuid)` catches it, which is why that recomputation is the
     * only reason `recType` and `uuid` were allowed to move inside the envelope.
     */
    @Test
    fun `a payload filed under another record's label is mislabelled, not accepted`() {
        val impostor = SyncRecords.toPayload(record.copy(uuid = "note-2"), createdAt = 100L)
        val wrongLabel = codec.blindedIdOf(RecordType.NOTE.wireKey, "note-1")
        // Sealed by hand under the wrong label: this is what a client bug produces, and a server
        // cannot produce it at all without K_id.
        val envelope = my.cheysoff.core_crypto.sync.RecordEnvelope.seal(
            keys.kContent, wrongLabel, RecordPayloadCodec.encode(impostor),
        )
        assertEquals(OpenResult.Mislabelled, codec.open(wrongLabel, envelope))
    }

    @Test
    fun `a payload from a newer build is reported as a version, not as damage`() {
        val text = RecordPayloadCodec.encode(payload()).decodeToString().replaceFirst("\"v\":1", "\"v\":9")
        val blindedId = codec.blindedIdOf(payload())
        val envelope = my.cheysoff.core_crypto.sync.RecordEnvelope.seal(
            keys.kContent, blindedId, text.encodeToByteArray(),
        )
        val result = codec.open(blindedId, envelope)
        assertTrue("was $result", result is OpenResult.UnsupportedVersion)
        assertEquals(9, (result as OpenResult.UnsupportedVersion).payloadVersion)
    }

    /**
     * A record padded to a bucket boundary hides its length from the operator. The check is that
     * sealing is doing it at all — a note of a few bytes and a note of a few hundred must not be
     * distinguishable by the size of the blob they produce.
     */
    @Test
    fun `two notes of very different lengths seal to the same size`() {
        val short = codec.seal(SyncRecords.toPayload(record, 100L)).envelope.size
        val longer = codec.seal(
            SyncRecords.toPayload(
                record.copy(fields = record.fields + (FieldClocks.TITLE to FieldValue.of("x".repeat(500)))),
                100L,
            ),
        ).envelope.size
        assertEquals(short, longer)
    }
}
