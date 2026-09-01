package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact bytes of a record payload, written down.
 *
 * ## Why a golden vector rather than a round-trip test
 *
 * A round trip proves this file agrees with itself. What has to be true is that it agrees with the
 * **other** implementation of the same format — `desktop/store/RecordPayloadCodec.kt`, which was
 * written first and which this module was moved out of. Two codecs that each round-trip perfectly
 * and disagree about one key's spelling produce an account where a note written on the phone cannot
 * be opened on the laptop, and the symptom is `RecordFault.UNREADABLE` on a record that decrypted
 * fine.
 *
 * The fixture below is pinned byte for byte by `RecordPayloadWireContractTest` on the desktop side,
 * so a drift in either implementation turns one of the two tests red and names it. (That
 * cross-pinning did not exist when this comment first claimed it did; it does now.) The intent is that the desktop's copy of
 * the codec is **deleted** in favour of this module when `desktop-integration` lands — one
 * implementation is better than two pinned to each other — and until it does, this string is the
 * contract between them.
 *
 * Changing any byte of [GOLDEN] is a protocol break, not a test update. Every record already on a
 * server was written in the old form.
 */
class RecordPayloadWireFormatTest {

    private companion object {

        /**
         * One note, sealed. Compact JSON, keys in the order [RecordPayloadCodec] writes them,
         * columns in `PayloadFields.NOTE_COLUMNS` order, clocks in `FieldClocks.NOTE_FIELDS` order.
         */
        const val GOLDEN: String =
            """{"v":1,"recType":"note","uuid":"note-1","hlc":"1756612345678-3-a1b2c3d4",""" +
                """"fields":{"title":"Groceries","content":"<p>milk</p>","contentFormat":"html",""" +
                """"checklist":"[x] one","isPinned":"1","isFavorite":"1","folderId":"folder-9",""" +
                """"createdAt":"100","updatedAt":"200","isDeleted":"0","deletedAt":null},""" +
                """"clocks":{"title":"1756612000000-0-beef"},"del":false,"serializer":1}"""

        val ROW_CLOCK = Hlc(1_756_612_345_678L, 3, "a1b2c3d4")
    }

    private val record = SyncRecord(
        type = RecordType.NOTE,
        uuid = "note-1",
        rowClock = ROW_CLOCK,
        fieldClocks = mapOf(FieldClocks.TITLE to Hlc(1_756_612_000_000L, 0, "beef")),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("Groceries"),
            FieldClocks.CONTENT to FieldValue.of("<p>milk</p>", "html"),
            FieldClocks.CHECKLIST to FieldValue.of("[x] one"),
            FieldClocks.PINNED to FieldValue.of("1"),
            FieldClocks.FAVORITE to FieldValue.of("1"),
            FieldClocks.FOLDER to FieldValue.of("folder-9"),
            FieldClocks.UPDATED_AT to FieldValue.of("200"),
            FieldClocks.DELETED to FieldValue.of("0", null),
        ),
    )

    @Test
    fun `a note payload encodes to exactly the agreed bytes`() {
        val encoded = RecordPayloadCodec.encode(SyncRecords.toPayload(record, createdAt = 100L))
        assertEquals(GOLDEN, encoded.decodeToString())
    }

    @Test
    fun `the agreed bytes decode back to the same record`() {
        val decoded = RecordPayloadCodec.decode(GOLDEN.encodeToByteArray())
        val payload = (decoded as PayloadResult.Ok).payload
        assertEquals(record, SyncRecords.fromPayload(payload))
    }

    /**
     * `createdAt` is on the wire and is not in the merge's vocabulary — see [SyncRecords]. It is
     * pinned here because the column's presence is what the desktop's decoder requires: a payload
     * missing it is refused outright, so dropping it from the encoder would make every note this
     * device writes unreadable there.
     */
    @Test
    fun `createdAt travels even though the merge does not model it`() {
        val payload = (RecordPayloadCodec.decode(GOLDEN.encodeToByteArray()) as PayloadResult.Ok).payload
        assertEquals("100", payload.field(PayloadFields.CREATED_AT))
    }

    @Test
    fun `a folder payload encodes to exactly the agreed bytes`() {
        val folder = SyncRecord(
            type = RecordType.FOLDER,
            uuid = "folder-9",
            rowClock = ROW_CLOCK,
            fieldClocks = emptyMap(),
            fields = mapOf(
                FieldClocks.NAME to FieldValue.of("Work"),
                FieldClocks.COLOR to FieldValue.of("4279383091"),
                FieldClocks.UPDATED_AT to FieldValue.of("6"),
                FieldClocks.DELETED to FieldValue.of("1", "7"),
            ),
        )
        assertEquals(
            """{"v":1,"recType":"folder","uuid":"folder-9","hlc":"1756612345678-3-a1b2c3d4",""" +
                """"fields":{"name":"Work","colorArgb":"4279383091","createdAt":"5","updatedAt":"6",""" +
                """"isDeleted":"1","deletedAt":"7"},"clocks":{},"del":true,"serializer":1}""",
            RecordPayloadCodec.encode(SyncRecords.toPayload(folder, createdAt = 5L)).decodeToString(),
        )
    }
}
