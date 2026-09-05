package my.cheysoff.core_data.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The default page size for [AttachmentDao.dirtyAttachments]. See that method's KDoc for why this
 * is a memory bound, not a paging convenience, and why eight is the number: eight rows at up to
 * [my.cheysoff.core_domain.attachment.AttachmentLimits.MAX_ATTACHMENT_BYTES] each is about 19 MiB
 * transient including the sync engine's base64 copy — comfortable on any device this app targets.
 */
internal const val DIRTY_ATTACHMENT_PAGE = 8

/**
 * See `NoteDao`'s KDoc on the sync columns for the rules every write here follows: bump the row
 * clock and set `dirty = 1` on every write that changes anything, and let `RoomNotesRepository` —
 * not SQL — decide what `fieldHlc` should say, because that needs the row's previous clocks.
 *
 * **No query that returns more than one row may select `bytes`.** Android's `CursorWindow` is
 * about 2 MB and a single attachment is up to 1 MiB, so a list of them selecting `bytes` fails as
 * soon as a note (or an account) has more than one or two — working in testing and failing at some
 * unpredictable row count in use. Exactly two methods here are the exceptions, and each says so in
 * its own KDoc: [attachmentRow] (one row, by primary key) and [dirtyAttachments] (bounded by a
 * `LIMIT`, not by scope — see that method's own KDoc for why a limit is the thing that makes it
 * safe rather than the row count happening to stay small). [activeAttachmentsForNote] and
 * [attachmentsDeletedAtForNote] used to be exceptions too; they no longer select `bytes` at all —
 * see [AttachmentClockRow]. Every other multi-row query, including [attachmentPreviewsByNoteId],
 * must carry `thumbBytes` (capped at 64 KiB) or no bytes at all — never `bytes`. If a new caller of
 * `bytes` appears, check it is not a list.
 */
@Dao
interface AttachmentDao {

    /**
     * The rail and every preview.
     *
     * **Selects no `bytes` column, and must never be changed to.** Android's `CursorWindow` is
     * about 2 MB and this returns every attachment on a note; one megabyte-sized column here fails
     * the whole query as soon as a note has two photographs. `thumbBytes` is capped at 64 KiB
     * precisely so that this query can carry it.
     */
    @Query(
        "SELECT uuid, noteId, anchor, sortOrder, mimeType, width, height, " +
            "thumbWidth, thumbHeight, thumbBytes, createdAt, updatedAt, isDeleted, deletedAt, meta " +
            "FROM attachments WHERE noteId = :noteId AND isDeleted = 0 " +
            "ORDER BY anchor ASC, sortOrder ASC, uuid ASC"
    )
    fun attachmentPreviewsByNoteId(noteId: String): Flow<List<AttachmentPreviewProjection>>

    /**
     * The single place in the codebase that reads a full attachment **by primary key, for the
     * viewer**.
     *
     * It has one more, entirely legitimate, caller: [RoomNotesRepository.saveAttachment]
     * [my.cheysoff.core_data.data.RoomNotesRepository.saveAttachment] fetches the prior row so it
     * can `contentEquals` the old and new `bytes` and decide whether `FieldClocks.IMAGE` actually
     * moved — that is the right way to make that decision, and it is a single row by id, not a
     * list, so it does not violate this class's rule. It does mean a save reads up to 1 MiB back
     * off disk to compare: fine at once-per-import, worth knowing if a caller ever starts saving on
     * a debounce. If a *third* caller of this appears, check it is not a list.
     */
    @Query("SELECT * FROM attachments WHERE uuid = :uuid")
    suspend fun attachmentRow(uuid: String): AttachmentEntity?

    /** The sync columns of one row, or null if there is no such row. See [RowClock]. */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM attachments WHERE uuid = :uuid")
    suspend fun rowClock(uuid: String): RowClock?

    /**
     * The highest row clock across every attachment, or null if the table is empty — the durable
     * seed `RoomNotesRepository` reads once per process. See `NoteDao.highestRowClock` for the
     * full argument; it applies here unchanged.
     */
    @Query("SELECT hlcMs, hlcCounter, hlcNode, fieldHlc FROM attachments ORDER BY hlcMs DESC, hlcCounter DESC LIMIT 1")
    suspend fun highestRowClock(): RowClock?

    /**
     * Writes an attachment **in full**, sync columns included — the path both a local save
     * ([RoomNotesRepository][my.cheysoff.core_data.data.RoomNotesRepository]) and a merged remote
     * record ([RoomSyncStore][my.cheysoff.core_data.data.sync.RoomSyncStore]) take. `@Upsert`
     * rather than `@Insert(onConflict = REPLACE)` for the same reason `NoteDao.applyRemoteNote`
     * gives: REPLACE deletes and reinserts, which would discard whatever the caller did not think
     * to pass — @Upsert updates the existing row in place instead. The caller owns every column,
     * including the clocks.
     *
     * Unlike notes and folders there is no separate "merged" write path here (no
     * `applyRemoteAttachment`): an attachment has no editor-owned partial-update statement to keep
     * distinct from it, exactly as [SketchDao.upsertSketch] explains for a sketch.
     */
    @Upsert
    suspend fun upsertAttachment(attachment: AttachmentEntity)

    // ── What the sync engine reads and writes. The mirror of NoteDao's/FolderDao's block. ──────

    /**
     * Dirty attachments, oldest clock first, **at most [limit] of them**.
     *
     * The limit is a memory bound, not a paging convenience, and it is why this may keep
     * `SELECT *` where the class KDoc otherwise forbids it. Each row carries up to
     * [my.cheysoff.core_domain.attachment.AttachmentLimits.MAX_ATTACHMENT_BYTES], and the sync
     * engine base64-encodes what this returns into a second live copy roughly a third larger
     * again — so an unbounded version of this query is the whole account's photo library in heap,
     * twice, inside one pass. On a fresh install, or right after a re-key, every row is `dirty = 1`
     * by the column default, so "scoped to nothing" is not a theoretical case.
     *
     * A pass that cannot send them all is not a problem: the rows stay dirty and the next pass
     * takes the next [limit]. Sync is periodic and repeats; running out of memory does not. Callers
     * should pass [DIRTY_ATTACHMENT_PAGE] unless they have a specific reason not to.
     */
    @Query("SELECT * FROM attachments WHERE dirty = 1 ORDER BY hlcMs ASC, hlcCounter ASC, hlcNode ASC, uuid ASC LIMIT :limit")
    suspend fun dirtyAttachments(limit: Int): List<AttachmentEntity>

    /**
     * §3.2's two rules as one statement — see `NoteDao.acknowledgeNotePush` for the argument in
     * full.
     *
     * An attachment has no `contentSyncedHlc`: it can never produce a conflict copy (only notes
     * have a body worth preserving that way — see `RoomSyncStore.applyMerged`), so
     * `Baselines.advance` has nothing to advance for one, exactly as for a sketch.
     */
    @Query(
        """
        UPDATE attachments SET
            lastSyncedSeq = :seq,
            dirty = CASE
                WHEN hlcMs = :sealedMs AND hlcCounter = :sealedCounter AND hlcNode = :sealedNode
                THEN 0 ELSE dirty END
        WHERE uuid = :uuid
        """
    )
    suspend fun acknowledgeAttachmentPush(
        uuid: String,
        seq: Long,
        sealedMs: Long,
        sealedCounter: Int,
        sealedNode: String,
    )

    /** `NoteDao.recordNoteSeen` for an attachment: the seq, and nothing else. */
    @Query("UPDATE attachments SET lastSyncedSeq = :seq WHERE uuid = :uuid")
    suspend fun recordAttachmentSeen(uuid: String, seq: Long)

    // ── Deletion by reconciliation, not cascade. See AttachmentEntity's KDoc and Task 7's sketch ──
    // ── analogue. ────────────────────────────────────────────────────────────────────────────

    /**
     * The id and clocks of every live attachment anchored under [noteId] — the set
     * `RoomNotesRepository.deleteNote` walks to tombstone them one by one, each with its own clock
     * bump, in the same transaction as the note's own tombstone.
     *
     * A projection, not [AttachmentEntity]: the caller (`RoomNotesRepository.deleteNote`) only ever
     * reads `uuid` and the clock columns off the result, to compute the fieldHlc each tombstone
     * should carry — never `bytes`. `restoreNote` on a note with twenty photos would otherwise read
     * about 20 MiB off disk just to look at clocks.
     */
    @Query("SELECT uuid, hlcMs, hlcCounter, hlcNode, fieldHlc FROM attachments WHERE noteId = :noteId AND isDeleted = 0")
    suspend fun activeAttachmentsForNote(noteId: String): List<AttachmentClockRow>

    /**
     * Sends one attachment to Trash. Mirrors `NoteDao.softDeleteNote` exactly, `isDeleted = 0`
     * guard included: a second delete of an already-trashed attachment must not re-stamp
     * `deletedAt` or mint a clock for a write that changed nothing.
     */
    @Query(
        "UPDATE attachments SET isDeleted = 1, deletedAt = :timestamp, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE uuid = :uuid AND isDeleted = 0"
    )
    suspend fun softDeleteAttachment(
        uuid: String,
        timestamp: Long,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )

    /**
     * Mirrors `NoteDao.purgeNotesDeletedBefore`: the hard DELETE for trashed attachments whose
     * retention window has passed. `RoomNotesRepository.purgeExpiredTrash` calls this alongside the
     * note, folder and sketch purges so a tombstoned attachment never outlives the note it was
     * tombstoned with.
     */
    @Query(
        "DELETE FROM attachments " +
            "WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt > 0 AND deletedAt <= :threshold"
    )
    suspend fun purgeAttachmentsDeletedBefore(threshold: Long): Int

    /**
     * The id and clocks of every attachment under [noteId] tombstoned at [deletedAt] or later —
     * the set `RoomNotesRepository.restoreNote` un-tombstones. See `SketchDao
     * .sketchesDeletedAtForNote` for why `>=` rather than `==`: the note's tombstone and each
     * attachment's tombstone are independently clocked records, so a note deleted concurrently on
     * two devices can merge to a note `deletedAt` earlier than an attachment tombstoned by that
     * very same event on the other device.
     *
     * A projection, not [AttachmentEntity], for the same reason as [activeAttachmentsForNote]: the
     * caller only reads `uuid` and the clock columns.
     */
    @Query(
        "SELECT uuid, hlcMs, hlcCounter, hlcNode, fieldHlc FROM attachments " +
            "WHERE noteId = :noteId AND isDeleted = 1 AND deletedAt >= :deletedAt"
    )
    suspend fun attachmentsDeletedAtForNote(noteId: String, deletedAt: Long): List<AttachmentClockRow>

    /**
     * The hard DELETE for every attachment under [noteId], live or tombstoned.
     * `RoomNotesRepository.purgeNote` calls this in the same transaction as the note's own row
     * delete. See `SketchDao.purgeSketchesForNote` for the full argument — it applies unchanged.
     */
    @Query("DELETE FROM attachments WHERE noteId = :noteId")
    suspend fun purgeAttachmentsForNote(noteId: String)

    /**
     * Brings one attachment back out of Trash. Mirrors `NoteDao.restoreNote`: clears the tombstone,
     * bumps the clock and marks the row dirty so the un-delete is pushed.
     */
    @Query(
        "UPDATE attachments SET isDeleted = 0, deletedAt = NULL, " +
            "hlcMs = :hlcMs, hlcCounter = :hlcCounter, hlcNode = :hlcNode, fieldHlc = :fieldHlc, dirty = 1 " +
            "WHERE uuid = :uuid"
    )
    suspend fun restoreAttachment(
        uuid: String,
        hlcMs: Long,
        hlcCounter: Int,
        hlcNode: String,
        fieldHlc: String,
    )
}
