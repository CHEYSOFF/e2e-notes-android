package my.cheysoff.core_data.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    // Stored as the raw wire string rather than the enum so no Room TypeConverter is needed and
    // the column stays readable/greppable in a DB dump. Defaults to "plain" to match the column
    // default the v4 -> v5 migration installs.
    val contentFormat: String = NoteContentFormat.PLAIN.storageValue,
    val checklist: String = "",
    val isPinned: Boolean,
    val isFavorite: Boolean = false,
    val folderId: String?,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    // Trash tombstone, added in v6. isDeleted defaults to false to match the column default the
    // v5 -> v6 migration installs, so every pre-existing note stays visible.
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,

    // ── Sync bookkeeping, added in v7. None of it is ever read by the UI. ───────────────────────
    //
    // Every default below is declared with @ColumnInfo(defaultValue = …) as well as in Kotlin,
    // which is what makes MIGRATION_6_7's DDL checkable rather than merely reviewable: Room
    // compares a column's declared default against the real table on every open, so a migration
    // that installed the wrong one fails loudly at startup instead of silently. That matters most
    // for `dirty`; see its comment.
    /**
     * Row clock, physical component — see [Hlc]. Bumped by EVERY write that changes anything,
     * including the three metadata gestures that deliberately leave `updatedAt` alone.
     *
     * 0 means "never stamped": a row that predates v7, or one written by a build that has not
     * learned to stamp it. It compares below every real clock, which is the safe direction.
     */
    @ColumnInfo(defaultValue = "0")
    val hlcMs: Long = 0L,

    /** Row clock, logical component. Breaks ties inside one millisecond — see [Hlc]. */
    @ColumnInfo(defaultValue = "0")
    val hlcCounter: Int = 0,

    /**
     * Row clock, node component: the per-account pseudonym of the device that minted it.
     *
     * Empty on a device with no account key, which is a real value rather than a missing one —
     * `HlcNode` explains why publishing a local fallback here would be worse than publishing
     * nothing.
     */
    @ColumnInfo(defaultValue = "''")
    val hlcNode: String = "",

    /**
     * Per-field clocks, serialised — see `FieldClocks`, which owns the format and the rule.
     *
     * Empty means "every field is at the row clock", which is exactly true of a newly created
     * note and of every row migrated into v7.
     */
    @ColumnInfo(defaultValue = "''")
    val fieldHlc: String = "",

    /**
     * True when this row holds changes the server has not acknowledged.
     *
     * **The default is 1, and it is 1 in the Kotlin default, in the column default, and in
     * MIGRATION_6_7. Changing any of them to 0 is data loss, not a tidy-up.** A row that has never
     * been pushed is by definition unsynced, so every row on disk at migration time is dirty. A
     * default of 0 would declare the user's entire pre-sync library already uploaded, and the
     * first pull would then reconcile a full local library against an empty server — which the
     * merge engine correctly reads as "every note was deleted elsewhere".
     */
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,

    /**
     * The server `seq` of the version this device last agreed with, sent back as `baseSeq` so the
     * server can reject a push built on a stale view.
     *
     * 0 means "this record has never been on the server", which the server reads as "must not
     * exist" — so it is also the value that makes a first push safe.
     */
    @ColumnInfo(defaultValue = "0")
    val lastSyncedSeq: Long = 0L,
) {
    /** The row clock as one value. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)
}

fun NoteEntity.toDomain() = Note(
    id = id,
    title = title,
    content = content,
    contentFormat = NoteContentFormat.fromStorage(contentFormat),
    checklist = checklist,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)

/**
 * The domain model back to an entity — a LOSSY conversion, and never a write path.
 *
 * [Note] carries nothing about sync (deliberately: the UI has no business knowing when a row was
 * last pushed), so every sync column here lands at its Kotlin default — a zero clock, no field
 * clocks, `dirty = true` and `lastSyncedSeq = 0`. Writing the result of this function to the
 * database would therefore erase the row's place in the account's history and tell the next push
 * that a synced record has never been seen by the server.
 *
 * Nothing in production calls it; it exists as the inverse of [toDomain] for the mapping test.
 * The full-row write path is [NoteDao.applyRemoteNote], which takes an entity a merge built with
 * its sync columns filled in on purpose.
 */
fun Note.toEntity() = NoteEntity(
    id = id,
    title = title,
    content = content,
    contentFormat = contentFormat.storageValue,
    checklist = checklist,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)
