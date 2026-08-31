package my.cheysoff.core_data.data.local

import my.cheysoff.core_domain.sync.Hlc

/**
 * Just the clock columns of one row — the projection every write path reads before it stamps.
 *
 * A write has to know two things about the row it is about to change: the clock the row is at now,
 * and the per-field clocks it is carrying. It does **not** need the note's body, and reading one
 * would be a real cost on the editor's autosave path, where the content can be megabytes. Hence a
 * projection rather than `SELECT *`.
 *
 * Shared by `notes` and `folders`; the six sync columns are identical on both tables.
 */
data class RowClock(
    val hlcMs: Long,
    val hlcCounter: Int,
    val hlcNode: String,
    val fieldHlc: String,
) {
    /** The row clock as one value. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)
}

/**
 * [RowClock] plus the row's id, for the one write that has to stamp many rows at once
 * (`clearFolder`): the ids come back with the clocks in a single read rather than one query per
 * note.
 */
data class IdentifiedRowClock(
    val id: String,
    val hlcMs: Long,
    val hlcCounter: Int,
    val hlcNode: String,
    val fieldHlc: String,
) {
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)

    fun clocks(): RowClock = RowClock(hlcMs, hlcCounter, hlcNode, fieldHlc)
}
