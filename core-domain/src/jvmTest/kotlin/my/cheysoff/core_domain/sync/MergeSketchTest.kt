package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The controller ruling `Merge.updatedAtCompanion` makes for `RecordType.SKETCH`: `strokes` is the
 * companion, mirroring how a note's `updatedAt` follows `content`.
 *
 * ## Why `strokes`, not `null`
 *
 * The companion exists to stop `updatedAt` lagging the record's user-facing payload when a
 * metadata-only write bumps one and not the other. For a sketch, `strokes` is that payload, while
 * `noteId`/`anchor`/`order` are positional bookkeeping, structurally like a note's `folderId` —
 * which deliberately does NOT drag `updatedAt`. It matters concretely once a later release
 * re-stamps `anchor` when text above a drawing is edited: without the companion, two devices could
 * disagree about a sketch's `updatedAt` — and therefore its sort order — after nothing but a text
 * reflow.
 *
 * These two tests mirror `MergeTest.updatedAtFollowsTheWinningContent` and
 * `updatedAtIsTakenFromTheContentWinnerEvenWhenItsOwnClockIsLower` exactly, with `strokes` standing
 * in for `content`.
 */
class MergeSketchTest {

    @Test
    fun updatedAtFollowsTheWinningStrokes() {
        val localRecord = sketch(rowClock = hlc(10), strokes = "old-strokes", updatedAt = 1_000L)
        val remote = sketch(rowClock = hlc(20), strokes = "new-strokes", updatedAt = 2_000L)

        val merged = (Merge.merge(local(localRecord), remote) as MergeResult.Applied).record

        assertEquals("new-strokes", merged.text(FieldClocks.STROKES))
        assertEquals("2000", merged.text(FieldClocks.UPDATED_AT))
    }

    /**
     * The case that separates "follows strokes" from "is an ordinary field": a record whose
     * `updatedAt` clock is explicitly *older* than its own `strokes` clock. Ordinary
     * max-by-own-clock would keep the local `updatedAt` here, because 15 beats 5 -- and the sketch
     * would then be displayed (once plan 3 surfaces that) with a time that belongs to a drawing it
     * no longer holds.
     */
    @Test
    fun updatedAtIsTakenFromTheStrokesWinnerEvenWhenItsOwnClockIsLower() {
        val localRecord = sketch(
            rowClock = hlc(15),
            fieldClocks = mapOf(FieldClocks.STROKES to hlc(10)),
            strokes = "old-strokes",
            updatedAt = 1_000L,
        )
        val remote = sketch(
            rowClock = hlc(20),
            fieldClocks = mapOf(FieldClocks.UPDATED_AT to hlc(5)),
            strokes = "new-strokes",
            updatedAt = 2_000L,
        )

        val merged = (Merge.merge(local(localRecord), remote) as MergeResult.Applied).record

        assertEquals("new-strokes", merged.text(FieldClocks.STROKES))
        assertEquals(
            "the remote drawing's own edit time came with it, despite a lower updatedAt clock",
            "2000",
            merged.text(FieldClocks.UPDATED_AT),
        )
    }

    /**
     * The other direction: an `anchor` re-stamp (the positional bookkeeping a text reflow above the
     * drawing will trigger, per plan 3) must NOT drag `updatedAt` along with it, the same way a
     * note's `folderId` does not. If `anchor` were the companion instead of `strokes`, this would
     * fail.
     */
    @Test
    fun anAnchorRestampDoesNotDragUpdatedAtAlong() {
        val localRecord = sketch(
            rowClock = hlc(30),
            fieldClocks = mapOf(FieldClocks.STROKES to hlc(10), FieldClocks.NOTE_ID to hlc(10)),
            anchor = 3,
            strokes = "shared-strokes",
            updatedAt = 1_000L,
        )
        val remote = sketch(
            rowClock = hlc(20),
            fieldClocks = mapOf(FieldClocks.ANCHOR to hlc(10), FieldClocks.NOTE_ID to hlc(10)),
            anchor = 1,
            strokes = "shared-strokes",
            updatedAt = 2_000L,
        )

        val merged = (Merge.merge(local(localRecord, dirty = true), remote)).mergedRecord(localRecord)

        assertEquals("the later anchor re-stamp won its own field", "3", merged.text(FieldClocks.ANCHOR))
        assertEquals(
            "but updatedAt is unaffected by anchor and stays on its own/strokes clock",
            "1000",
            merged.text(FieldClocks.UPDATED_AT),
        )
    }
}
