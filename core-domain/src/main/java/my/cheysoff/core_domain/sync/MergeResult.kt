package my.cheysoff.core_domain.sync

/**
 * What [Merge.merge] decided.
 *
 * ## What the caller still owns
 *
 * Every branch here describes the record's **data**. None of them touches `lastSyncedSeq`, and
 * only [Applied.dirty] speaks about `dirty` — because those are transport bookkeeping and the
 * merge has never been told a `seq`. The rules for them are in `e2e-sync-phase3-plan.md` §3.2 and
 * they belong to the sync coordinator:
 *
 *  - a record that arrived from a pull at `seq` sets `lastSyncedSeq = seq`, whatever the merge
 *    decided, because this device has now seen that server version and its next push must be
 *    built on it;
 *  - a push that came back `ok` sets `lastSyncedSeq` to the new seq and clears `dirty` **only if
 *    the row has not moved since the envelope was sealed**;
 *  - a `409` carries the conflicting record inline and is fed straight back into [Merge.merge] as
 *    though it had been pulled. There is deliberately no second merge path for the conflict case.
 */
sealed interface MergeResult {

    /**
     * The merged record differs from what this device holds; write it.
     *
     * @param record the merged row, ready to go through `applyRemoteNote`/`applyRemoteFolder` —
     *   the full-row write path. It must **not** be written through `upsertNote`, whose conflict
     *   branch deliberately refuses `isFavorite`, `isDeleted` and `deletedAt` and would therefore
     *   silently drop a remote delete or a remote favourite.
     * @param dirty whether the merged row is something the server does not have. False means the
     *   merge took the remote record wholesale, so it is already published; true means the merged
     *   row is a genuine three-way result that only this device holds and the next push must send
     *   it. Computing this in the merge rather than at the call site is deliberate: it is a
     *   property of *what the merge decided*, and a caller guessing at it is how a merged edit
     *   ends up never being pushed.
     */
    data class Applied(val record: SyncRecord, val dirty: Boolean) : MergeResult

    /**
     * The merge is [Applied], **and** a body was about to be discarded, so it was written out as a
     * separate note instead.
     *
     * This is the mitigation for the highest-severity risk in the architecture doc — a merge bug
     * that propagates to every device in seconds with no undo. Nothing the user typed is ever
     * dropped; the loser becomes a new note that pushes on the next pass and appears everywhere.
     *
     * @param record the winner, under the original uuid, exactly as [Applied.record].
     * @param dirty as [Applied.dirty], for [record].
     * @param copy the loser, as a complete new note record with its own uuid. The caller inserts
     *   it with `dirty = true` and `lastSyncedSeq = 0` — it has never been on the server — and
     *   supplies the one column the merge does not model, `createdAt`; see `ConflictCopies`.
     *
     * **The caller must insert [copy] only if no record with that uuid exists yet.** The uuid is
     * derived from the original record and the losing body, so the same conflict resolved twice —
     * on this device by a re-delivered record, or on the other device by the mirror-image merge —
     * names the same copy. That is what makes the design docs' deduplication rule free rather than
     * a lookup, but it also means a blind overwrite would clobber a copy the user has since
     * edited.
     */
    data class ConflictCopy(
        val record: SyncRecord,
        val dirty: Boolean,
        val copy: SyncRecord,
    ) : MergeResult

    /**
     * The merge produced exactly what this device already holds. Write nothing.
     *
     * **This is the single most important property in the merge** and it is not a nicety.
     * `e2e-sync-phase3-plan.md` §3.3 lists three ordinary production events that re-deliver an
     * already-applied record — a crash between the merge commit and the cursor write, a dropped
     * push response, and the `409` a device takes against its own earlier write — and in all
     * three the correct outcome is that nothing happens at all. A merge that instead re-marked the
     * row dirty would push it again, and two devices doing that to each other never go quiet.
     *
     * `NoChange` compares the record's *data* — fields, field clocks and row clock — and also the
     * dirty flag the merge would have set. The caller still records `lastSyncedSeq`.
     */
    data object NoChange : MergeResult

    /**
     * The remote record was refused. Nothing is written, the row is untouched, and the caller must
     * not advance any bookkeeping past it.
     */
    data class Rejected(val reason: RejectReason) : MergeResult
}

/** Why [MergeResult.Rejected]. */
enum class RejectReason {

    /**
     * The remote record's clock is **older** than a clean local row's.
     *
     * The rollback defence, and — since the envelope's associated data was narrowed — the only one
     * there is at the record level. It is worth being exact about why, because the architecture
     * doc originally claimed otherwise and has been corrected in place:
     *
     * > A server restored from a backup, or a malicious one, can replay an *older authentic*
     * > envelope. **The AAD never defended against this and could not** — a replayed version is
     * > exactly the tuple the client sealed, so the tag verifies. What the binding stopped was the
     * > server *mislabelling* an envelope with another version's clock, an attack that no longer
     * > exists because there is no outer label left to mislabel.
     *
     * So the test is `!local.dirty && remote.rowClock < local.rowClock`. `dirty` is what makes it
     * meaningful: on a dirty row a lower remote clock is the ordinary "we hold a newer local edit"
     * case and the merge handles it field by field. On a **clean** row, this device's clock can
     * only have got ahead of the server's by the server going backwards, because a clean row is by
     * definition one the server has acknowledged.
     *
     * ## The blind spot, stated plainly
     *
     * **A record this device has never seen has no local clock to compare against.** An old
     * version of a record that is new to this device is therefore undetectable here, at the record
     * level, and no amount of care in this function changes that — there is nothing to compare.
     *
     * The whole-server case that produces it is covered one level up: `GET /v1/changes` answers
     * `409 cursor_ahead_of_server` when the client's cursor exceeds the server's high-water mark,
     * which is exactly what a restored-from-backup server looks like from outside. Both that and
     * this rejection must **halt the engine** and require an explicit user re-baseline
     * (`e2e-sync-phase3-plan.md` §8, F7). Neither may silently reset the cursor to 0: with clean
     * rows that is indistinguishable from "the account is empty", and the next pass would be a
     * mass delete.
     */
    ROLLBACK_SUSPECTED,

    /**
     * The remote record is not the same record as the local one — a different uuid, or the same
     * uuid under a different type.
     *
     * A caller bug rather than anything a server can cause: the record was looked up under one
     * identity and merged against another. Refused rather than merged, because merging two
     * different records' fields together would corrupt both.
     */
    IDENTITY_MISMATCH,
}
