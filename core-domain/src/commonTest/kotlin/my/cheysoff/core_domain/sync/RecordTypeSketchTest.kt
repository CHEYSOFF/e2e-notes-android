package my.cheysoff.core_domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RecordType.SKETCH]: the wire key, the field set, and the one thing every field of it must be
 * true of -- that it fits in a single column.
 *
 * The pairing between this field set and the payload's column set lives in
 * `core-sync-codec`'s `SketchRecordTypeTest`, since that is where both vocabularies -- clocked
 * field and payload column -- are visible together. This file only owns what `core-domain` alone
 * can state.
 */
class RecordTypeSketchTest {

    @Test
    fun `the wire key is sketch`() {
        assertEquals("sketch", RecordType.SKETCH.wireKey)
    }

    @Test
    fun `a sketch clocks noteId anchor order strokes and the two shared fields`() {
        assertEquals(
            setOf("noteId", "anchor", "order", "strokes", "updatedAt", "deleted"),
            RecordType.SKETCH.fields,
        )
    }

    @Test
    fun `none of the four sketch-only fields pairs two columns the way content or deleted do`() {
        // `updatedAt` and `deleted` are shared with NOTE_FIELDS and FOLDER_FIELDS and keep whatever
        // part count they already have there -- `deleted` is two, everything else here is one --
        // which is `RecordType.partCount`'s existing `else -> 1` branch, unchanged by this task.
        setOf("noteId", "anchor", "order", "strokes").forEach { field ->
            assertEquals(1, RecordType.SKETCH.partCount(field), "'$field' should be a single column")
        }
    }
}
