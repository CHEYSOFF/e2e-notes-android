package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payload codec's own rules: round trips, and the strictness that stops an older build from
 * silently deleting a newer one's fields.
 *
 * The cases mirror `desktop/store/RecordPayloadCodecTest.kt` — the same format, the same fixture,
 * the same refusals — so that the two ends of the protocol are checked against the same list while
 * they remain two files. The byte-level pin between them is `RecordPayloadWireFormatTest`.
 */
class RecordPayloadCodecTest {

    private val clock = Hlc(1_756_612_345_678L, 3, "a1b2c3d4")

    private fun note(
        fields: Map<String, FieldValue> = mapOf(
            FieldClocks.TITLE to FieldValue.of("Groceries"),
            FieldClocks.CONTENT to FieldValue.of("<p>milk</p>", "html"),
            FieldClocks.CHECKLIST to FieldValue.of("[x] one"),
            FieldClocks.PINNED to FieldValue.of("1"),
            FieldClocks.FAVORITE to FieldValue.of("1"),
            FieldClocks.FOLDER to FieldValue.of("folder-9"),
            FieldClocks.UPDATED_AT to FieldValue.of("200"),
            FieldClocks.DELETED to FieldValue.of("0", null),
        ),
        clocks: Map<String, Hlc> = mapOf(FieldClocks.TITLE to Hlc(1_756_612_000_000L, 0, "beef")),
    ) = SyncRecord(RecordType.NOTE, "note-1", clock, clocks, fields)

    private fun encoded(record: SyncRecord = note(), createdAt: Long = 100L): String =
        RecordPayloadCodec.encode(SyncRecords.toPayload(record, createdAt)).decodeToString()

    private fun decode(text: String): PayloadResult =
        RecordPayloadCodec.decode(text.encodeToByteArray())

    // -- round trips -----------------------------------------------------------------------------

    @Test
    fun `every note field survives a round trip`() {
        val original = note()
        val payload = (decode(encoded(original)) as PayloadResult.Ok).payload
        assertEquals(original, SyncRecords.fromPayload(payload))
    }

    @Test
    fun `a trashed note keeps its tombstone through a round trip`() {
        val original = note(
            fields = note().fields + (FieldClocks.DELETED to FieldValue.of("1", "999")),
        )
        val payload = (decode(encoded(original)) as PayloadResult.Ok).payload
        assertEquals(FieldValue.of("1", "999"), SyncRecords.fromPayload(payload)!!.valueOf(FieldClocks.DELETED))
    }

    /**
     * `folderId`, `colorArgb` and `deletedAt` are nullable columns, and encoding null as `""` would
     * make "no folder" and "a folder whose id is the empty string" the same value.
     */
    @Test
    fun `null columns stay null rather than becoming empty strings`() {
        val original = note(fields = note().fields + (FieldClocks.FOLDER to FieldValue.of(null)))
        val payload = (decode(encoded(original)) as PayloadResult.Ok).payload
        assertNull(SyncRecords.fromPayload(payload)!!.valueOf(FieldClocks.FOLDER).parts[0])
        assertTrue(encoded(original).contains("\"folderId\":null"))
    }

    @Test
    fun `every folder field survives a round trip`() {
        val original = SyncRecord(
            type = RecordType.FOLDER,
            uuid = "f1",
            rowClock = clock,
            fieldClocks = mapOf(FieldClocks.NAME to Hlc(1, 0, "aa")),
            fields = mapOf(
                FieldClocks.NAME to FieldValue.of("Work"),
                FieldClocks.COLOR to FieldValue.of("4278255360"),
                FieldClocks.UPDATED_AT to FieldValue.of("6"),
                FieldClocks.DELETED to FieldValue.of("0", null),
            ),
        )
        val payload = (decode(encoded(original, createdAt = 5L)) as PayloadResult.Ok).payload
        assertEquals(original, SyncRecords.fromPayload(payload))
    }

    /**
     * The bytes must not depend on how the map was built: a round trip that re-encodes has to
     * produce the same blob, or nothing downstream can compare two versions of a record.
     */
    @Test
    fun `encoding is byte-stable across a decode and re-encode`() {
        val first = RecordPayloadCodec.encode(SyncRecords.toPayload(note(), 100L))
        val decoded = (RecordPayloadCodec.decode(first) as PayloadResult.Ok).payload
        assertArrayEquals(first, RecordPayloadCodec.encode(decoded))
    }

    // -- strictness: the "silent field loss" rule ------------------------------------------------

    @Test
    fun `a payload with an unknown top-level key is refused`() {
        val text = encoded().replaceFirst("{", "{\"newFieldFromTheFuture\":1,")
        assertTrue("was ${decode(text)}", decode(text) is PayloadResult.Malformed)
    }

    @Test
    fun `a payload with an unknown column is refused`() {
        val text = encoded().replaceFirst("\"title\"", "\"subtitle\"")
        assertTrue(decode(text) is PayloadResult.Malformed)
    }

    @Test
    fun `a payload with a clock for an unknown field is refused`() {
        val text = encoded().replaceFirst("\"clocks\":{\"title\"", "\"clocks\":{\"headline\"")
        assertTrue(decode(text) is PayloadResult.Malformed)
    }

    @Test
    fun `a newer payload version is refused as a version, not as damage`() {
        val result = decode(encoded().replaceFirst("\"v\":1", "\"v\":2"))
        assertTrue("was $result", result is PayloadResult.UnsupportedVersion)
        assertEquals(2, (result as PayloadResult.UnsupportedVersion).payloadVersion)
    }

    /**
     * A body written by a different `richeditor` escapes text differently. Refusing rather than
     * re-saving is what stops this build from rewriting it at version 1 and mangling it.
     */
    @Test
    fun `a newer content-serializer version is refused`() {
        val text = encoded().replaceFirst("\"serializer\":1", "\"serializer\":2")
        assertTrue(decode(text) is PayloadResult.UnsupportedVersion)
    }

    /**
     * `del` and the `isDeleted` column are the same fact written twice — §5.1 carries both. The
     * duplication is only safe as a cross-check; a payload where they disagree is one whose author
     * had a different idea of which one wins.
     */
    @Test
    fun `a payload whose del disagrees with isDeleted is refused`() {
        val text = encoded().replaceFirst("\"del\":false", "\"del\":true")
        assertTrue(decode(text) is PayloadResult.Malformed)
    }

    @Test
    fun `a payload with an unparseable clock is refused`() {
        val text = encoded().replaceFirst("\"hlc\":\"", "\"hlc\":\"nonsense")
        assertTrue(decode(text) is PayloadResult.Malformed)
    }

    @Test
    fun `an unknown record type is refused`() {
        val text = encoded().replaceFirst("\"recType\":\"note\"", "\"recType\":\"attachment\"")
        assertTrue(decode(text) is PayloadResult.Malformed)
    }

    @Test
    fun `garbage decodes to Malformed rather than throwing`() {
        assertTrue(RecordPayloadCodec.decode(ByteArray(0)) is PayloadResult.Malformed)
        assertTrue(decode("not json") is PayloadResult.Malformed)
    }

    // -- field mapping ---------------------------------------------------------------------------

    /**
     * Booleans travel as SQLite's own spelling. A device that wrote `"true"` would hold a value it
     * considered correct that no other device would ever converge on.
     */
    @Test
    fun `booleans are spelled as 1 and 0`() {
        val text = encoded()
        assertTrue(text.contains("\"isPinned\":\"1\""))
        assertTrue(text.contains("\"isDeleted\":\"0\""))
    }

    @Test
    fun `the payload carries exactly the columns the plan names`() {
        assertEquals(
            setOf(
                "title", "content", "contentFormat", "checklist", "isPinned", "isFavorite",
                "folderId", "createdAt", "updatedAt", "isDeleted", "deletedAt",
            ),
            PayloadFields.columnsOf(RecordType.NOTE),
        )
        assertEquals(
            setOf("name", "colorArgb", "createdAt", "updatedAt", "isDeleted", "deletedAt"),
            PayloadFields.columnsOf(RecordType.FOLDER),
        )
    }

    /**
     * `content` and `contentFormat` are one value with one clock, and the merge takes a whole
     * `FieldValue` from one side or the other. Pinning the part order here is what stops a body
     * from being paired with the other device's format.
     */
    @Test
    fun `content carries its format in the second part`() {
        val payload = (decode(encoded()) as PayloadResult.Ok).payload
        val content = SyncRecords.fromPayload(payload)!!.valueOf(FieldClocks.CONTENT)
        assertEquals(listOf("<p>milk</p>", "html"), content.parts)
    }

    /**
     * A clock equal to the row clock is written implicitly, never as an entry — the sparse
     * convention `FieldClocks` documents. Two devices agreeing on the state while disagreeing on
     * the encoding would fail every byte comparison while being perfectly converged.
     */
    @Test
    fun `a field clock equal to the row clock is not written down`() {
        val text = encoded(note(clocks = mapOf(FieldClocks.TITLE to clock)))
        assertTrue(text, text.contains("\"clocks\":{}"))
    }
}
