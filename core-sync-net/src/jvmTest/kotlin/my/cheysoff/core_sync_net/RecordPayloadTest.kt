package my.cheysoff.core_sync_net

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues
import my.cheysoff.core_sync_net.wire.RecordPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record payload, both directions.
 *
 * ## Why round-tripping is not enough, and what else is here
 *
 * A codec tested only against itself passes whatever it does consistently. Two properties matter
 * more than the round trip and are checked separately:
 *
 *  - **The exact bytes.** One test pins the whole serialised form. That is the value another
 *    device parses and another *platform* will one day write, so a change to it is a change every
 *    device has to agree to; a byte-level assertion is the only kind that notices.
 *  - **What is refused.** §8's F2 says a payload version this build does not know must be refused
 *    rather than decoded on a best-effort basis, because an older build that re-serialised a newer
 *    payload silently drops the fields it did not understand and pushes the loss to every device.
 *    Half this file is that: the things that must NOT decode.
 */
class RecordPayloadTest {

    private val clock = Hlc(ms = 1_700_000_000_000L, counter = 3, node = "1debb84e8005f0f9")
    private val olderClock = Hlc(ms = 1_600_000_000_000L, counter = 0, node = "1debb84e8005f0f9")

    private fun note(
        rowClock: Hlc = clock,
        fieldClocks: Map<String, Hlc> = emptyMap(),
        folderId: String? = "folder-1",
        deletedAt: String? = null,
    ) = SyncRecord(
        type = RecordType.NOTE,
        uuid = "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
        rowClock = rowClock,
        fieldClocks = fieldClocks,
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("Groceries"),
            FieldClocks.CONTENT to FieldValue.of("<p>milk</p>", "HTML"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of(SyncValues.TRUE),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.FALSE),
            FieldClocks.FOLDER to FieldValue.of(folderId),
            FieldClocks.UPDATED_AT to FieldValue.of("1700000000000"),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, deletedAt),
        ),
    )

    private fun folder() = SyncRecord(
        type = RecordType.FOLDER,
        uuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        rowClock = clock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.NAME to FieldValue.of("Shopping"),
            FieldClocks.COLOR to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("1700000000000"),
            FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
        ),
    )

    private fun decoded(bytes: ByteArray): RecordPayload.Decoded.Ok =
        RecordPayload.decode(bytes) as RecordPayload.Decoded.Ok

    private fun faultOf(json: String): RecordPayload.PayloadFault =
        (RecordPayload.decode(json.encodeToByteArray()) as RecordPayload.Decoded.Failed).fault

    // -------------------------------------------------------------------------------------
    // The bytes
    // -------------------------------------------------------------------------------------

    @Test
    fun `a note serialises to exactly this`() {
        // Pinned deliberately. This is the value that crosses to another device and, once the iOS
        // store lands, the value that rests on a disk; changing it is a protocol change and should
        // read as one in a diff rather than being absorbed by a round-trip test.
        assertEquals(
            """{"v":1,"recType":"note","uuid":"1b4e28ba-2fa1-11d2-883f-0016d3cca427",""" +
                """"hlc":"1700000000000-3-1debb84e8005f0f9","created":1690000000000,""" +
                """"fields":{"title":"Groceries","content":"<p>milk</p>","contentFormat":"HTML",""" +
                """"checklist":"","isPinned":"1","isFavorite":"0","folderId":"folder-1",""" +
                """"updatedAt":"1700000000000","isDeleted":"0","deletedAt":null},""" +
                """"clocks":{},"del":false,"serializer":1}""",
            RecordPayload.encode(note(), createdAt = 1_690_000_000_000L).decodeToString(),
        )
    }

    @Test
    fun `field clocks are written sparsely, and in field order`() {
        // The sparse convention: a clock equal to the row clock is left implicit and restored from
        // it on the way back. Writing them all out would be a different encoding of the same state,
        // and two devices that agreed on the state would then disagree on the bytes.
        val json = RecordPayload.encode(
            note(
                fieldClocks = mapOf(
                    FieldClocks.DELETED to olderClock,
                    FieldClocks.TITLE to olderClock,
                    FieldClocks.PINNED to clock,
                )
            ),
            createdAt = 0L,
        ).decodeToString()

        assertTrue(
            json,
            json.contains(
                """"clocks":{"title":"1600000000000-0-1debb84e8005f0f9",""" +
                    """"deleted":"1600000000000-0-1debb84e8005f0f9"}"""
            ),
        )
    }

    @Test
    fun `a folder serialises its own field set and no note fields`() {
        val json = RecordPayload.encode(folder(), createdAt = 0L).decodeToString()
        assertTrue(json, json.contains(""""recType":"folder""""))
        assertTrue(json, json.contains(""""name":"Shopping""""))
        assertTrue(json, json.contains(""""colorArgb":null"""))
        assertTrue(json, !json.contains("title"))
    }

    @Test
    fun `the tombstone flag is derived from isDeleted rather than stored twice`() {
        val tombstone = SyncRecord(
            type = RecordType.NOTE,
            uuid = note().uuid,
            rowClock = clock,
            fieldClocks = emptyMap(),
            fields = note().fields + (
                FieldClocks.DELETED to FieldValue.of(SyncValues.TRUE, "1700000000001")
                ),
        )
        assertTrue(RecordPayload.encode(tombstone, 0L).decodeToString().contains(""""del":true"""))
        assertTrue(RecordPayload.encode(note(), 0L).decodeToString().contains(""""del":false"""))
    }

    // -------------------------------------------------------------------------------------
    // Round trips
    // -------------------------------------------------------------------------------------

    @Test
    fun `a note round-trips, including its created time`() {
        val original = note()
        val result = decoded(RecordPayload.encode(original, createdAt = 1_690_000_000_000L))
        assertEquals(original, result.record)
        assertEquals(1_690_000_000_000L, result.createdAt)
    }

    @Test
    fun `a folder round-trips`() {
        assertEquals(folder(), decoded(RecordPayload.encode(folder(), 0L)).record)
    }

    @Test
    fun `sparse field clocks come back sparse and resolve to the row clock`() {
        val original = note(fieldClocks = mapOf(FieldClocks.CONTENT to olderClock))
        val back = decoded(RecordPayload.encode(original, 0L)).record
        assertEquals(olderClock, back.clockOf(FieldClocks.CONTENT))
        assertEquals(clock, back.clockOf(FieldClocks.TITLE))
    }

    @Test
    fun `a field clock equal to the row clock is dropped and restored, not lost`() {
        // `normalized()` drops it on the way out; `clockOf` restores it on the way in. The record
        // that comes back is not byte-identical in its map and IS equal in meaning, which is the
        // whole point of the convention.
        val original = note(fieldClocks = mapOf(FieldClocks.TITLE to clock))
        val back = decoded(RecordPayload.encode(original, 0L)).record
        assertTrue(back.fieldClocks.isEmpty())
        assertEquals(clock, back.clockOf(FieldClocks.TITLE))
    }

    @Test
    fun `a null column survives as null and not as an empty string`() {
        // "no folder" and "a folder whose id is the empty string" must stay different values, or a
        // merge picks one for the other and the note moves.
        val back = decoded(RecordPayload.encode(note(folderId = null), 0L)).record
        assertEquals(listOf<String?>(null), back.valueOf(FieldClocks.FOLDER).parts)
        val empty = decoded(RecordPayload.encode(note(folderId = ""), 0L)).record
        assertEquals(listOf<String?>(""), empty.valueOf(FieldClocks.FOLDER).parts)
        assertNotEquals(back.valueOf(FieldClocks.FOLDER), empty.valueOf(FieldClocks.FOLDER))
    }

    @Test
    fun `text that needs escaping survives`() {
        // Note bodies are HTML and note titles are whatever the user typed. A payload is JSON, so
        // quotes, backslashes, newlines and non-ASCII all have to come back unchanged -- and a
        // control character has to be escaped rather than emitted raw, or the payload another
        // device receives is not JSON at all.
        val awkward = SyncRecord(
            type = RecordType.NOTE,
            uuid = note().uuid,
            rowClock = clock,
            fieldClocks = emptyMap(),
            fields = note().fields + mapOf(
                FieldClocks.TITLE to FieldValue.of("say \"hi\"\\  \n Привет 🙂"),
                FieldClocks.CONTENT to FieldValue.of("<p>a &amp; b</p>", "HTML"),
            ),
        )
        assertEquals(awkward, decoded(RecordPayload.encode(awkward, 0L)).record)
    }

    // -------------------------------------------------------------------------------------
    // What must be refused
    // -------------------------------------------------------------------------------------

    @Test
    fun `a payload version this build does not know is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString().replace(""""v":1""", """"v":2""")
        assertEquals(RecordPayload.PayloadFault.UNSUPPORTED_VERSION, faultOf(json))
    }

    @Test
    fun `a content serializer version this build does not know is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""serializer":1""", """"serializer":9""")
        assertEquals(RecordPayload.PayloadFault.UNSUPPORTED_VERSION, faultOf(json))
    }

    @Test
    fun `an unknown record type is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""recType":"note"""", """"recType":"attachment"""")
        assertEquals(RecordPayload.PayloadFault.UNKNOWN_RECORD_TYPE, faultOf(json))
    }

    @Test
    fun `a missing column is refused rather than read as null`() {
        // The difference that matters: a truncated payload must not become a note that quietly
        // lost its folder, because the merge would then propagate that as a real edit.
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""folderId":"folder-1",""", "")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `a clock under a field name this build does not know is refused`() {
        // Not dropped. An unrecognised key reads as "at the row clock", i.e. silently newer than it
        // was, which is the silent-field-loss hazard `SyncRecord.validate` names.
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""clocks":{}""", """"clocks":{"colour":"1-0-x"}""")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `a note's clock under a folder field name is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""clocks":{}""", """"clocks":{"name":"1-0-x"}""")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `an unparseable clock is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""clocks":{}""", """"clocks":{"title":"not-a-clock"}""")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `an unparseable row clock is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""hlc":"1700000000000-3-1debb84e8005f0f9"""", """"hlc":"nope"""")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `an empty uuid is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""uuid":"1b4e28ba-2fa1-11d2-883f-0016d3cca427"""", """"uuid":""""")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `a numeric field where a string belongs is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString()
            .replace(""""updatedAt":"1700000000000"""", """"updatedAt":1700000000000""")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `a missing created time is refused`() {
        val json = RecordPayload.encode(note(), 0L).decodeToString().replace(""""created":0,""", "")
        assertEquals(RecordPayload.PayloadFault.MALFORMED_FIELD, faultOf(json))
    }

    @Test
    fun `bytes that are not JSON are refused`() {
        assertEquals(RecordPayload.PayloadFault.MALFORMED, faultOf("not json at all"))
        assertEquals(RecordPayload.PayloadFault.MALFORMED, faultOf(""))
        assertEquals(RecordPayload.PayloadFault.MALFORMED, faultOf("[1,2,3]"))
    }

    @Test
    fun `nothing decodes to a record that would fail validation`() {
        // The invariant the merge depends on: whatever comes out of this codec is a record
        // `Merge.merge` can be handed. Every refusal above exists to keep it true.
        val ok = RecordPayload.decode(RecordPayload.encode(note(), 0L)) as RecordPayload.Decoded.Ok
        assertEquals(ok.record, ok.record.validate())
        assertNull((RecordPayload.decode("{}".encodeToByteArray()) as? RecordPayload.Decoded.Ok))
    }
}
