package my.cheysoff.desktop.store

import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPayloadCodecTest {

    private val clock = Hlc(1_756_612_345_678L, 3, "a1b2c3d4")

    private fun noteRow(
        note: Note = Note(
            id = "note-1",
            title = "Groceries",
            content = "<p>milk</p>",
            contentFormat = NoteContentFormat.HTML,
            checklist = "[x] one",
            isPinned = true,
            isFavorite = true,
            folderId = "folder-9",
            createdAt = 100,
            updatedAt = 200,
        ),
        clocks: Map<String, Hlc> = mapOf(FieldClocks.TITLE to Hlc(1_756_612_000_000L, 0, "beef")),
    ) = NoteRow(note, clock, clocks)

    // -------------------------------------------------------------------------------------------
    // Round trips
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every note field survives a round trip`() {
        val original = noteRow()
        val encoded = RecordPayloadCodec.encode(NoteRecords.toPayload(original))
        val decoded = RecordPayloadCodec.decode(encoded) as PayloadResult.Ok
        val row = NoteRecords.fromPayload(decoded.payload)!!

        assertEquals(original.note, row.note)
        assertEquals(original.rowClock, row.rowClock)
        assertEquals(original.clocks, row.clocks)
    }

    @Test
    fun `a trashed note keeps its tombstone through a round trip`() {
        val original = noteRow(
            note = Note(id = "n", title = "t", content = "c", isDeleted = true, deletedAt = 999),
        )
        val row = NoteRecords.fromPayload(
            (RecordPayloadCodec.decode(RecordPayloadCodec.encode(NoteRecords.toPayload(original))) as PayloadResult.Ok).payload,
        )!!
        assertTrue(row.note.isDeleted)
        assertEquals(999L, row.note.deletedAt)
    }

    @Test
    fun `null columns stay null rather than becoming empty strings`() {
        val original = noteRow(
            note = Note(id = "n", title = "t", content = "c", folderId = null, deletedAt = null),
        )
        val row = NoteRecords.fromPayload(
            (RecordPayloadCodec.decode(RecordPayloadCodec.encode(NoteRecords.toPayload(original))) as PayloadResult.Ok).payload,
        )!!
        assertNull(row.note.folderId)
        assertNull(row.note.deletedAt)
    }

    @Test
    fun `every folder field survives a round trip`() {
        val original = FolderRow(
            folder = Folder("f1", "Work", colorArgb = 0xFF112233L, createdAt = 5, updatedAt = 6),
            rowClock = clock,
            clocks = mapOf(FieldClocks.NAME to Hlc(1, 0, "aa")),
        )
        val payload = FolderRecords.toPayload(original)
        val decoded = RecordPayloadCodec.decode(RecordPayloadCodec.encode(payload)) as PayloadResult.Ok

        assertEquals(original, FolderRecords.fromPayload(decoded.payload))
    }

    /**
     * The bytes must not depend on how the map was built: a round trip that re-encodes has to
     * produce the same blob, or nothing downstream can compare two versions of a record.
     */
    @Test
    fun `encoding is byte-stable across a decode and re-encode`() {
        val first = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
        val decoded = (RecordPayloadCodec.decode(first) as PayloadResult.Ok).payload
        assertArrayEquals(first, RecordPayloadCodec.encode(decoded))
    }

    // -------------------------------------------------------------------------------------------
    // Strictness — the "silent field loss" rule
    // -------------------------------------------------------------------------------------------

    /**
     * §5.1's rule: decode strictly and refuse the record. A tolerant decoder plus a re-serialise is
     * how an older build silently deletes a newer build's fields while reporting success.
     */
    @Test
    fun `a payload with an unknown top-level key is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("{", "{\"newFieldFromTheFuture\":1,")
        val result = RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8))
        assertTrue("was $result", result is PayloadResult.Malformed)
    }

    @Test
    fun `a payload with an unknown column is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"title\"", "\"subtitle\"")
        assertTrue(RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8)) is PayloadResult.Malformed)
    }

    @Test
    fun `a payload with a clock for an unknown field is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"clocks\":{\"title\"", "\"clocks\":{\"headline\"")
        assertTrue(RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8)) is PayloadResult.Malformed)
    }

    @Test
    fun `a newer payload version is refused as a version, not as damage`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"v\":1", "\"v\":2")
        val result = RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8))
        assertTrue("was $result", result is PayloadResult.UnsupportedVersion)
        assertEquals(2, (result as PayloadResult.UnsupportedVersion).payloadVersion)
    }

    /**
     * A body written by a different `richeditor` escapes text differently. Refusing rather than
     * re-saving is what stops this build from rewriting it at version 1 and mangling it.
     */
    @Test
    fun `a newer content-serializer version is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"serializer\":1", "\"serializer\":2")
        assertTrue(RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8)) is PayloadResult.UnsupportedVersion)
    }

    /**
     * `del` and the `isDeleted` column are the same fact written twice — §5.1 carries both. The
     * duplication is only safe as a cross-check; a payload where they disagree is one whose author
     * had a different idea of which one wins.
     */
    @Test
    fun `a payload whose del disagrees with isDeleted is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"del\":false", "\"del\":true")
        assertTrue(RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8)) is PayloadResult.Malformed)
    }

    @Test
    fun `a payload with an unparseable clock is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"hlc\":\"", "\"hlc\":\"nonsense")
        assertTrue(RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8)) is PayloadResult.Malformed)
    }

    @Test
    fun `an unknown record type is refused`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow()))
            .toString(Charsets.UTF_8)
            .replaceFirst("\"recType\":\"note\"", "\"recType\":\"attachment\"")
        assertTrue(RecordPayloadCodec.decode(text.toByteArray(Charsets.UTF_8)) is PayloadResult.Malformed)
    }

    @Test
    fun `garbage decodes to Malformed rather than throwing`() {
        assertTrue(RecordPayloadCodec.decode(ByteArray(0)) is PayloadResult.Malformed)
        assertTrue(RecordPayloadCodec.decode("not json".toByteArray()) is PayloadResult.Malformed)
    }

    // -------------------------------------------------------------------------------------------
    // Field mapping
    // -------------------------------------------------------------------------------------------

    /**
     * `createdAt` and `updatedAt` are what the notes list sorts on. Substituting a default for an
     * unreadable one would silently move a note to the end of the list forever, so the record is
     * refused instead.
     */
    @Test
    fun `a non-numeric timestamp makes the record unreadable rather than defaulting`() {
        val payload = NoteRecords.toPayload(noteRow()).let {
            it.copy(fields = it.fields + (PayloadFields.UPDATED_AT to "yesterday"))
        }
        assertNull(NoteRecords.fromPayload(payload))
    }

    /**
     * `contentFormat` is the exception, and `NoteContentFormat.fromStorage` argues the case:
     * rendering HTML as text is ugly and recoverable, parsing text as HTML destroys characters.
     */
    @Test
    fun `an unrecognised content format degrades to PLAIN`() {
        val payload = NoteRecords.toPayload(noteRow()).let {
            it.copy(fields = it.fields + (PayloadFields.CONTENT_FORMAT to "markdown"))
        }
        assertEquals(NoteContentFormat.PLAIN, NoteRecords.fromPayload(payload)!!.note.contentFormat)
    }

    /**
     * Booleans travel as SQLite's own spelling. A device that wrote `"true"` would hold a value it
     * considered correct that no other device would ever converge on.
     */
    @Test
    fun `booleans are spelled as 1 and 0`() {
        val text = RecordPayloadCodec.encode(NoteRecords.toPayload(noteRow())).toString(Charsets.UTF_8)
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
}
