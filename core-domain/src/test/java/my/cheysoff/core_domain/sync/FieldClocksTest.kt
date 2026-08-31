package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FieldClocks]: the format, and the one rule that keeps a write from claiming more than it wrote.
 *
 * The rule is small and its failure mode is not. `fieldHlc` is read as "these fields are OLDER
 * than the row clock; everything else is AT it", so a write that forgets to write down an
 * untouched field's clock promotes that field to the moment of the write — and the next merge uses
 * the promotion to discard the other device's genuinely newer value. Nothing anywhere reports it.
 */
class FieldClocksTest {

    private val old = Hlc(1_000L, 0, "a")
    private val older = Hlc(500L, 0, "a")
    private val new = Hlc(2_000L, 0, "b")

    // ── Format ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `serialize and parse round trip`() {
        val clocks = mapOf(FieldClocks.TITLE to old, FieldClocks.CONTENT to older)
        assertEquals(clocks, FieldClocks.parse(FieldClocks.serialize(clocks)))
    }

    @Test
    fun `the serialised form is the documented one`() {
        assertEquals(
            "title=1000-0-a;content=500-0-a",
            FieldClocks.serialize(mapOf(FieldClocks.TITLE to old, FieldClocks.CONTENT to older)),
        )
    }

    @Test
    fun `an empty map is the empty string, and back`() {
        assertEquals("", FieldClocks.serialize(emptyMap()))
        assertEquals(emptyMap<String, Hlc>(), FieldClocks.parse(""))
    }

    @Test
    fun `parse drops damage instead of throwing`() {
        // The column is only ever written by stamp(), so damage means a downgrade or a bug. A
        // dropped entry degrades to "this field is at the row clock", which is what an older
        // build's row says anyway — and is a great deal better than crashing the editor.
        val parsed = FieldClocks.parse("title=1000-0-a;garbage;=1000-0-a;content=nonsense;name=500-0-a")
        assertEquals(mapOf(FieldClocks.TITLE to old, FieldClocks.NAME to older), parsed)
    }

    @Test
    fun `parse takes the first of a duplicated key`() {
        assertEquals(
            mapOf(FieldClocks.TITLE to old),
            FieldClocks.parse("title=1000-0-a;title=2000-0-b"),
        )
    }

    // ── clockOf ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a field with no entry is at the row clock`() {
        val serialized = FieldClocks.serialize(mapOf(FieldClocks.TITLE to older))
        assertEquals(older, FieldClocks.clockOf(FieldClocks.TITLE, serialized, new))
        assertEquals(new, FieldClocks.clockOf(FieldClocks.CONTENT, serialized, new))
    }

    // ── stamp ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a row that did not exist has no field older than the write that created it`() {
        assertEquals(
            "",
            FieldClocks.stamp(
                previousSerialized = "",
                previousRowClock = null,
                allFields = FieldClocks.NOTE_FIELDS,
                touched = setOf(FieldClocks.TITLE),
                newClock = new,
            ),
        )
    }

    @Test
    fun `an untouched field keeps the row clock it was written at`() {
        val stamped = FieldClocks.stamp(
            previousSerialized = "",
            previousRowClock = old,
            allFields = FieldClocks.NOTE_FIELDS,
            touched = setOf(FieldClocks.PINNED),
            newClock = new,
        )

        val clocks = FieldClocks.parse(stamped)
        assertNull("the touched field is at the row clock and must stay implicit", clocks[FieldClocks.PINNED])
        // Every other clocked field was at the previous row clock and has to say so, or it would
        // read as "written at `new`" and beat a remote edit it never saw.
        (FieldClocks.NOTE_FIELDS - FieldClocks.PINNED).forEach {
            assertEquals("$it lost its clock", old, clocks[it])
        }
    }

    @Test
    fun `an untouched field keeps its own older entry rather than the row clock`() {
        val previous = FieldClocks.serialize(mapOf(FieldClocks.FAVORITE to older))

        val clocks = FieldClocks.parse(
            FieldClocks.stamp(
                previousSerialized = previous,
                previousRowClock = old,
                allFields = FieldClocks.NOTE_FIELDS,
                touched = setOf(FieldClocks.TITLE),
                newClock = new,
            )
        )

        assertEquals("an entry older than the row clock was flattened onto it", older, clocks[FieldClocks.FAVORITE])
        assertEquals(old, clocks[FieldClocks.CONTENT])
        assertNull(clocks[FieldClocks.TITLE])
    }

    @Test
    fun `repeated writes do not grow the column without bound`() {
        // The representation is a map over a fixed field set, not an append log, so a note edited
        // ten thousand times carries the same handful of entries as one edited twice. An
        // append-only encoding would be simpler to write in SQL and would put megabytes of clock
        // history in front of every note body.
        var serialized = ""
        var rowClock = old
        repeat(10_000) { i ->
            val next = Hlc(2_000L + i, 0, "a")
            serialized = FieldClocks.stamp(
                previousSerialized = serialized,
                previousRowClock = rowClock,
                allFields = FieldClocks.NOTE_FIELDS,
                touched = setOf(FieldClocks.TITLE, FieldClocks.CONTENT, FieldClocks.UPDATED_AT),
                newClock = next,
            )
            rowClock = next
        }
        assertEquals(FieldClocks.NOTE_FIELDS.size - 3, FieldClocks.parse(serialized).size)
        assertTrue("fieldHlc grew unbounded: ${serialized.length} chars", serialized.length < 400)
    }

    @Test
    fun `touching every field collapses back to the empty string`() {
        assertEquals(
            "",
            FieldClocks.stamp(
                previousSerialized = FieldClocks.serialize(mapOf(FieldClocks.TITLE to older)),
                previousRowClock = old,
                allFields = FieldClocks.NOTE_FIELDS,
                touched = FieldClocks.NOTE_FIELDS,
                newClock = new,
            ),
        )
    }

    @Test
    fun `folder rows use the folder field set`() {
        val clocks = FieldClocks.parse(
            FieldClocks.stamp(
                previousSerialized = "",
                previousRowClock = old,
                allFields = FieldClocks.FOLDER_FIELDS,
                touched = setOf(FieldClocks.NAME),
                newClock = new,
            )
        )
        assertEquals(setOf(FieldClocks.COLOR, FieldClocks.UPDATED_AT, FieldClocks.DELETED), clocks.keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stamping a field that is not in the field set is rejected`() {
        // Silently ignoring it would be the dangerous behaviour: the column would change while its
        // clock stayed put, and a merge would then overwrite the new value with a stale remote one.
        FieldClocks.stamp(
            previousSerialized = "",
            previousRowClock = old,
            allFields = FieldClocks.FOLDER_FIELDS,
            touched = setOf(FieldClocks.TITLE),
            newClock = new,
        )
    }

    @Test
    fun `the note and folder field sets carry exactly the mergeable columns`() {
        // Pinned here rather than left implicit because adding a column to either entity without
        // adding it to these sets gives it no clock of its own, forever and silently.
        assertEquals(
            setOf("title", "content", "checklist", "isPinned", "isFavorite", "folderId", "updatedAt", "deleted"),
            FieldClocks.NOTE_FIELDS,
        )
        assertEquals(setOf("name", "colorArgb", "updatedAt", "deleted"), FieldClocks.FOLDER_FIELDS)
    }
}
