package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * ## A note on the sync columns, which every write below now carries
 *
 * From v7 each row records a hybrid logical clock (`hlcMs`/`hlcCounter`/`hlcNode`), the per-field
 * clocks that clock covers (`fieldHlc`), and whether the server has seen this version (`dirty`,
 * `lastSyncedSeq`). Two rules govern them and both are load-bearing:
 *
 * 1. **Every write that changes anything bumps the row clock and sets `dirty = 1`** — including
 *    the three metadata gestures that deliberately do NOT touch `updatedAt`.
 * 2. **`updatedAt` keeps exactly the behaviour PR #32 gave it.** The two answer different
 *    questions: `updatedAt` is the user-visible "edited 2h ago" and the key `ORDER BY updatedAt
 *    DESC` sorts on, so pinning a note must not jump it to the top of a list the user did not ask
 *    to reorder; the HLC is how two devices agree on which write happened later, and a metadata
 *    gesture that left no clock behind would simply be lost on the next sync. Bumping one is not
 *    an argument for bumping the other, and the tension that made PR #32 look like a trade-off
 *    disappears once they are separate columns.
 *
 * The clock values are always supplied by the caller, never by SQL. `RoomNotesRepository` allocates
 * exactly one clock per user action from the single `HlcGenerator` — so the two halves of a folder
 * delete land at the same point in history — and computes the new `fieldHlc` through
 * `FieldClocks.stamp`, which needs the row's previous clocks and is therefore a read the SQL here
 * cannot do for itself.
 */
@Dao
interface NoteDao {
    // One @Query per user-selectable order (rather than a single @RawQuery) so Room keeps
    // verifying each statement against the schema at compile time.
    //
    // Every order ends in `id ASC`. Without it the ordering is not total: legacy rows carry
    // updatedAt/createdAt = 0 until their first post-migration save and therefore tie on both
    // timestamp keys, and two untitled notes tie on title. SQLite leaves the relative order of
    // tied rows unspecified, so those notes could visibly reshuffle between emissions of
    // otherwise-unchanged data. `id` is the primary key, hence unique, so appending it makes
    // each order deterministic and stable.
    //
    // Every one of them also carries `WHERE isDeleted = 0`. Delete is soft (see [softDeleteNote]),
    // so the row a user just sent to Trash is still sitting in this table; a query that forgets the
    // filter shows it in the notes list as if nothing happened. The Trash reads below are the ONLY
    // ones that select the other side of that flag.

    /** Recently edited: newest save first. Untouched legacy rows (updatedAt = 0) sort last. */
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY updatedAt DESC, createdAt DESC, id ASC")
    fun getNotesByUpdatedAt(): Flow<List<NoteEntity>>

    /** Newest created first. Untouched legacy rows (createdAt = 0) sort last. */
    @Query("SELECT * FROM notes WHERE isDeleted = 0 ORDER BY createdAt DESC, updatedAt DESC, id ASC")
    fun getNotesByCreatedAt(): Flow<List<NoteEntity>>

    /**
     * Title A–Z. NOCASE is the only case-insensitive collation SQLite offers without registering
     * a custom one, and it folds ASCII A–Z only: titles in Cyrillic, Greek, accented Latin or any
     * other script therefore sort by raw code point, which puts their uppercase and lowercase
     * letters in separate runs. That is a real limitation of this order, not a stylistic choice —
     * fixing it needs a custom collation (or an ICU-normalised sort column), which is out of scope.
     *
     * Untitled notes have an empty title, which would otherwise collate first and open the list
     * with a wall of blank cards; `(title = '') ASC` sinks that whole group to the bottom (0 before
     * 1) while leaving the titled notes’ relative order untouched.
     */
    @Query(
        "SELECT * FROM notes WHERE isDeleted = 0 " +
            "ORDER BY (title = '') ASC, title COLLATE NOCASE ASC, id ASC"
    )
    fun getNotesByTitle(): Flow<List<NoteEntity>>

    /**
     * Emits null for a soft-deleted note as well as for an unknown id, which is what keeps a note
     * in Trash out of the editor: SingleNoteViewModel filters nulls out of this flow, so a screen
     * pointed at a trashed id simply never loads.
     */
    @Query("SELECT * FROM notes WHERE id = :id AND isDeleted = 0")
    fun getNoteById(id: String): Flow<NoteEntity?>

    // ── The clock columns, read back so a write can stamp on top of them ───────────────────────

    /** The sync columns of one row, or null if there is no such row. See [RowClock]. */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM notes WHERE id = :id")
    suspend fun rowClock(id: String): RowClock?

    /**
     * The highest row clock in this table, or null if the table is empty.
     *
     * The durable high-water mark `HlcGenerator` is seeded from at the start of a session. In-memory
     * state alone is not enough: a process that restarts after the device clock has been wound back
     * would otherwise begin minting clocks *below* the ones already stored, and a row whose clock
     * went backwards loses to its own older version on the next sync. Every row carries the clock
     * it was last written at, so the maximum of them is the cheapest possible durable record of
     * "the highest clock this device has ever issued or seen".
     *
     * Tombstoned rows are deliberately included — a delete is a write like any other and its clock
     * still has to be beaten.
     */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM notes ORDER BY hlcMs DESC, hlcCounter DESC LIMIT 1")
    suspend fun highestRowClock(): RowClock?

    /**
     * Every row filed under [folderId], with its clocks and nothing else.
     *
     * Read in one statement by `clearFolder`'s caller so that unfiling a folder's notes costs one
     * query plus one update per row, rather than two per row. Not filtered by `isDeleted`, matching
     * [clearFolder] itself — see that method for why a trashed note still has to be unfiled.
     */
    @Query("SELECT id, hlcMs, hlcCounter, hlcNode, fieldHlc FROM notes WHERE folderId = :folderId")
    suspend fun rowClocksInFolder(folderId: String): List<IdentifiedRowClock>

    // ── Writes ────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes a note **in full**, sync columns included — the path a merged remote record takes,
     * and the only one that may set every column.
     *
     * ## Why this exists alongside [upsertNote]
     *
     * [upsertNote] is the editor's save, and its conflict branch deliberately refuses to write
     * `isFavorite`, `isDeleted` and `deletedAt`: the editor does not own those fields, so an
     * autosave racing a delete must not resurrect the note. That rule is right for a save and
     * exactly wrong for sync, because a remote record's entire purpose may be to carry one of
     * those three — a favourite toggled on the tablet, or a note deleted on the phone. Routed
     * through [upsertNote], a remote delete would be silently dropped and the note would come back
     * from the dead on every device.
     *
     * So the two paths are distinct on purpose and neither is a special case of the other. The
     * editor writes the fields it owns; a merge writes everything, because a merge has already
     * decided, field by field, what every column should be.
     *
     * `@Upsert` and NOT `@Insert(onConflict = REPLACE)`: REPLACE is a DELETE followed by an
     * INSERT, which would destroy `createdAt`, both tombstone columns and all six sync columns
     * before writing the new row. `@Upsert` inserts, or updates the existing row in place.
     *
     * The caller owns the sync columns of [note] and must fill them in deliberately — in
     * particular `dirty`, which is `true` when the merge produced something the server has not
     * seen (a genuine three-way merge result) and `false` when the remote record won outright and
     * `lastSyncedSeq` is being set to the seq it arrived at.
     */
    @Upsert
    suspend fun applyRemoteNote(note: NoteEntity)

    // ── What the sync engine reads and writes. See `RoomSyncStore`. ───────────────────────────

    /**
     * One row in full, **tombstones included**.
     *
     * Every other single-row read on this DAO carries `WHERE isDeleted = 0`, because a trashed note
     * must not appear in the editor or the list. This one must not: a tombstone is an ordinary
     * record to the sync engine — it is the only delete the protocol has — and a store that could
     * not see one would answer "this device has never heard of that record" for a note it deleted
     * ten seconds ago, and then accept the server's older, living copy back over it.
     */
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun noteRow(id: String): NoteEntity?

    /**
     * Every note the server has not acknowledged, **oldest row clock first**.
     *
     * The order is part of `SyncStore`'s contract rather than a detail: a device that has been
     * offline for a week pushes the week in the order it happened, and a deterministic order is
     * what makes a failing convergence seed replay identically. `id ASC` is the tie-break, and it
     * is total because `id` is the primary key.
     *
     * There is no companion "deleted records" query. A tombstone is an ordinary dirty row carrying
     * `isDeleted = 1`, so it is already here.
     */
    @Query("SELECT * FROM notes WHERE dirty = 1 ORDER BY hlcMs ASC, hlcCounter ASC, hlcNode ASC, id ASC")
    suspend fun dirtyNotes(): List<NoteEntity>

    /**
     * The two rules of §3.2, as **one** statement.
     *
     * They have to be one statement, and this is the place in the whole design where that is true
     * of SQL rather than of a comment:
     *
     *  1. **`dirty` is cleared only if the row has not moved.** The user can type into a note while
     *     its push is in flight. The `CASE` compares the row's clock now against the clock of the
     *     version that was actually sealed and sent; when they differ the row keeps `dirty = 1` and
     *     the next pass pushes the newer version. Clearing it unconditionally drops that edit
     *     forever, with no error and no way for anyone to notice.
     *  2. **`lastSyncedSeq` is written either way.** The server did accept the version that was
     *     sent, whatever has happened locally since, so the next push must be built on that `seq`.
     *     Skipping it when rule 1's guard fails makes every subsequent push send a stale `baseSeq`
     *     and take a guaranteed `409`, forever.
     *
     * Written as two statements — an `UPDATE … WHERE clock matches` followed by an `UPDATE … SET
     * lastSyncedSeq` — the pair is only correct if nothing runs between them, which is precisely
     * what no caller can promise and what a crash does not respect. As one `UPDATE` the atomicity
     * is the statement's, not the caller's discipline.
     *
     * The guard compares all three clock components, not the two the plan's example SQL names. The
     * row clock *is* `(ms, counter, node)`; comparing a prefix of it would let a row rewritten by
     * another node in the same millisecond and counter pass as unchanged, and the counter is
     * per-generator, so two nodes reaching the same `(ms, counter)` is not exotic.
     *
     * @param contentSyncedHlc the new content baseline, in `Hlc.toString()` form, or `''`.
     *   Unconditional: `Baselines.advance` has already taken the max, and the engine is the only
     *   writer of this column, so it cannot move backwards here.
     */
    @Query(
        """
        UPDATE notes SET
            lastSyncedSeq = :seq,
            contentSyncedHlc = :contentSyncedHlc,
            dirty = CASE
                WHEN hlcMs = :sealedMs AND hlcCounter = :sealedCounter AND hlcNode = :sealedNode
                THEN 0 ELSE dirty END
        WHERE id = :id
        """
    )
    suspend fun acknowledgeNotePush(
        id: String,
        seq: Long,
        sealedMs: Long,
        sealedCounter: Int,
        sealedNode: String,
        contentSyncedHlc: String,
    )

    /**
     * The merge decided nothing had to be written, but this device has now **seen** server version
     * [seq] and its next push must be built on it.
     *
     * Deliberately touches neither the row's data nor `dirty`. A row left at a stale
     * `lastSyncedSeq` takes a guaranteed `409` on every subsequent pass, forever; a row whose
     * `dirty` were cleared here would have a local edit silently declared published.
     */
    @Query("UPDATE notes SET lastSyncedSeq = :seq, contentSyncedHlc = :contentSyncedHlc WHERE id = :id")
    suspend fun recordNoteSeen(id: String, seq: Long, contentSyncedHlc: String)

    /**
     * Single-statement upsert (avoids a read on every autosave). A new note gets
     * createdAt = updatedAt = [timestamp] and isFavorite = false. An existing note keeps its
     * createdAt (initializing the legacy 0) AND its isFavorite — the editor/save path doesn't own
     * those fields, so they're never clobbered — while title/content/isPinned/folderId/updatedAt
     * are updated. (Toggling favorite, when added, should use a dedicated update.)
     *
     * contentFormat travels with content and is overwritten alongside it — the two must never
     * drift apart, or a body would be read back with the wrong parser.
     *
     * The tombstone columns follow the same "not ours to write" rule as isFavorite: a new row is
     * inserted alive, and the conflict branch leaves isDeleted/deletedAt exactly as it found them.
     * So a save that races a delete cannot resurrect the note — only [restoreNote] does that. That
     * same rule is what makes this the WRONG path for a merged remote record; see
     * [applyRemoteNote].
     *
     * The row clock, [fieldHlc] and `dirty = 1` are written in BOTH branches, because both are
     * changes this device made and has not pushed. `lastSyncedSeq` appears only in the insert
     * (at 0, "the server has no version of this record"): the conflict branch must leave it alone,
     * since it is still the baseline the next push is built on, and resetting it to 0 would tell
     * the server this already-uploaded record must not exist.
     */
    @Query(
        """
        INSERT INTO notes (id, title, content, contentFormat, checklist, isPinned, isFavorite, folderId, createdAt, updatedAt, isDeleted, deletedAt, hlcMs, hlcCounter, hlcNode, fieldHlc, dirty, lastSyncedSeq)
        VALUES (:id, :title, :content, :contentFormat, :checklist, :isPinned, 0, :folderId, :timestamp, :timestamp, 0, NULL, :hlcMs, :hlcCounter, :hlcNode, :fieldHlc, 1, 0)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            content = excluded.content,
            contentFormat = excluded.contentFormat,
            checklist = excluded.checklist,
            isPinned = excluded.isPinned,
            folderId = excluded.folderId,
            updatedAt = excluded.updatedAt,
            createdAt = CASE WHEN notes.createdAt = 0 THEN excluded.createdAt ELSE notes.createdAt END,
            hlcMs = excluded.hlcMs,
            hlcCounter = excluded.hlcCounter,
            hlcNode = excluded.hlcNode,
            fieldHlc = excluded.fieldHlc,
            dirty = 1
        """
    )
    suspend fun upsertNote(
        id: String,
        title: String,
        content: String,
        contentFormat: String,
        checklist: String,
        isPinned: Boolean,
        folderId: String?,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Sends a note to Trash. `AND isDeleted = 0` makes this idempotent in the direction that
     * matters: a second delete of an already-trashed note must not re-stamp deletedAt, which would
     * silently restart its 30-day retention.
     *
     * The guard covers the clock too, and that is the correct pairing: a statement that matches no
     * row changed nothing, so it must not mint a new version of the record either. "Bump the clock
     * on every write that changes anything" is a biconditional here.
     */
    @Query(
        "UPDATE notes SET isDeleted = 1, deletedAt = :timestamp, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :id AND isDeleted = 0"
    )
    suspend fun softDeleteNote(
        id: String,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Brings a note back out of Trash, clearing the stamp so its next delete starts a new window.
     *
     * Unlike [softDeleteNote] this carries no `isDeleted` guard, which is pre-existing behaviour
     * and is left alone deliberately: restoring a note that is not in Trash is already a no-op on
     * both columns. It does mean such a call re-stamps the clock and marks the row dirty for
     * nothing, which costs one redundant push and nothing else — the record it pushes is byte-for-
     * byte the one already on the server. Only the Trash screen reaches this.
     */
    @Query(
        "UPDATE notes SET isDeleted = 0, deletedAt = NULL, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :id"
    )
    suspend fun restoreNote(
        id: String,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /** The real DELETE. Irreversible — nothing else in the app removes a note row. */
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun purgeNote(id: String)

    /**
     * Notes in Trash, newest-deleted first. A row with no stamp sorts last (SQLite ranks NULL below
     * every value, so DESC puts it at the end) rather than jumping to the top.
     */
    @Query("SELECT * FROM notes WHERE isDeleted = 1 ORDER BY deletedAt DESC, id ASC")
    fun getDeletedNotes(): Flow<List<NoteEntity>>

    /**
     * Purges every trashed note stamped at or before [threshold], returning the number of rows
     * destroyed. The threshold comes from TrashPolicy.purgeThreshold(now).
     *
     * `deletedAt > 0` excludes rows with no usable stamp, matching TrashPolicy.isExpired: an
     * unstamped tombstone has no measurable age, so it is kept rather than guessed at. (`> 0` also
     * covers NULL, which fails every comparison, but both guards are written out so the intent
     * survives a future edit.)
     */
    @Query(
        "DELETE FROM notes " +
            "WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt > 0 AND deletedAt <= :threshold"
    )
    suspend fun purgeNotesDeletedBefore(threshold: Long): Int

    // ── The three metadata gestures ────────────────────────────────────────────────────────────
    //
    // None of the three touches updatedAt, and that is the whole point of PR #32: they are things
    // the user does TO a note rather than edits OF it, and re-stamping would jump the note to the
    // top of a newest-first list nobody asked to reorder.
    //
    // All three DO stamp the clock and set dirty. There is no tension between the two statements:
    // a pin that left no clock behind is a pin the other device never hears about, and an
    // updatedAt bumped to record it would be a lie about when the note was last edited.

    @Query(
        "UPDATE notes SET folderId = :folderId, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :noteId"
    )
    suspend fun setNoteFolder(
        noteId: String,
        folderId: String?,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    @Query(
        "UPDATE notes SET isFavorite = :isFavorite, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :noteId"
    )
    suspend fun setNoteFavorite(
        noteId: String,
        isFavorite: Boolean,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    @Query(
        "UPDATE notes SET isPinned = :isPinned, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :noteId"
    )
    suspend fun setNotePinned(
        noteId: String,
        isPinned: Boolean,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Unfiles ONE note as part of deleting the folder it was in.
     *
     * Unlike the three targeted metadata updates above, this one DOES bump updatedAt. Those three
     * are user gestures on a single note that must not reorder a newest-first list (PR #32); this
     * is a mass edit the user did not aim at any note, and leaving it traceless means the change is
     * invisible to anything that reasons about when a note last changed.
     *
     * ## Why this is per-note and no longer one statement
     *
     * Until v7 this was a single `UPDATE … WHERE folderId = :folderId`. It cannot stay that way,
     * because `fieldHlc` is a per-row value derived from *that row's* previous clocks, and SQL has
     * no way to compute it for many rows at once without either a read per row or an unbounded,
     * append-only encoding. So the caller reads every affected row's clocks in one
     * [rowClocksInFolder] query and then issues one of these per note, all inside a single
     * transaction.
     *
     * **The clock is allocated once for the whole sweep**, not once per note, and is shared with
     * the `softDeleteFolder` in the same transaction. That is the choice the design asks for by
     * name: unfiling a folder's notes and trashing the folder are one user action, so they belong
     * at one point in the account's history; advancing the counter per row would spread a single
     * gesture across N points and imply an ordering between notes that does not exist.
     *
     * Deliberately not filtered by isDeleted — [rowClocksInFolder] is not either. A note already in
     * Trash still carries this folderId, and leaving it pointing at a folder row that is itself
     * about to be purged would create a dangling reference the moment either one is restored.
     */
    @Query(
        "UPDATE notes SET folderId = NULL, updatedAt = :timestamp, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE id = :noteId"
    )
    suspend fun clearFolderForNote(
        noteId: String,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )
}
