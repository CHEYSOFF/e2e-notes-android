package my.cheysoff.core_data.data.sync

import my.cheysoff.core_data.data.local.FolderEntity
import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues

/**
 * A Room row to the merge's `SyncRecord` and back.
 *
 * ## Why this is not `NoteEntity.toDomain()`
 *
 * `Note` is what the UI renders; `SyncRecord` is what two devices argue about. They carry almost
 * the same columns and are not the same object, and the difference is the whole point: a
 * `SyncRecord` carries the row clock and the per-field clocks and does **not** carry `dirty`,
 * `lastSyncedSeq` or `contentSyncedHlc` — those are per-device bookkeeping that is *supposed* to
 * differ between two converged replicas, and putting them in the shared type is how a convergence
 * assertion comes to compare them.
 *
 * ## Every value crosses as text
 *
 * The merge treats values as opaque: it compares them, and in one unreachable-in-theory case orders
 * them. It never parses one. So the spelling has to be agreed, and it is agreed in `SyncValues` —
 * `"1"`/`"0"` for a boolean, matching how SQLite stores the column, so a value round-tripping
 * through the database and out to the wire keeps the same spelling on every device.
 */
internal object RecordRows {

    fun toRecord(note: NoteEntity): SyncRecord = SyncRecord(
        type = RecordType.NOTE,
        uuid = note.id,
        rowClock = note.rowHlc(),
        fieldClocks = FieldClocks.parse(note.fieldHlc),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of(note.title),
            // Two columns, one value, one clock: a body read back with the wrong parser is silent
            // corruption, and `FieldValue` is what makes taking them from different sides
            // impossible rather than merely discouraged.
            FieldClocks.CONTENT to FieldValue.of(note.content, note.contentFormat),
            FieldClocks.CHECKLIST to FieldValue.of(note.checklist),
            FieldClocks.PINNED to FieldValue.of(SyncValues.of(note.isPinned)),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.of(note.isFavorite)),
            FieldClocks.FOLDER to FieldValue.of(note.folderId),
            FieldClocks.UPDATED_AT to FieldValue.of(note.updatedAt.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.of(note.isDeleted), note.deletedAt?.toString()),
        ),
    ).normalized()

    fun toRecord(folder: FolderEntity): SyncRecord = SyncRecord(
        type = RecordType.FOLDER,
        uuid = folder.id,
        rowClock = folder.rowHlc(),
        fieldClocks = FieldClocks.parse(folder.fieldHlc),
        fields = mapOf(
            FieldClocks.NAME to FieldValue.of(folder.name),
            FieldClocks.COLOR to FieldValue.of(folder.colorArgb?.toString()),
            FieldClocks.UPDATED_AT to FieldValue.of(folder.updatedAt.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.of(folder.isDeleted), folder.deletedAt?.toString()),
        ),
    ).normalized()

    /**
     * The row a merged [record] should become.
     *
     * @param createdAt the one column the merge does not model. See [createdAtFor], which is where
     *   the value comes from and where the reasoning is.
     */
    fun toNoteEntity(
        record: SyncRecord,
        createdAt: Long,
        dirty: Boolean,
        lastSyncedSeq: Long,
        contentBaseline: Hlc?,
    ): NoteEntity {
        val normalized = record.normalized()
        val content = normalized.valueOf(FieldClocks.CONTENT)
        val deleted = normalized.valueOf(FieldClocks.DELETED)
        return NoteEntity(
            id = normalized.uuid,
            title = normalized.valueOf(FieldClocks.TITLE).parts[0].orEmpty(),
            content = content.parts[0].orEmpty(),
            contentFormat = content.parts[1].orEmpty(),
            checklist = normalized.valueOf(FieldClocks.CHECKLIST).parts[0].orEmpty(),
            isPinned = SyncValues.toBoolean(normalized.valueOf(FieldClocks.PINNED).parts[0]),
            isFavorite = SyncValues.toBoolean(normalized.valueOf(FieldClocks.FAVORITE).parts[0]),
            folderId = normalized.valueOf(FieldClocks.FOLDER).parts[0],
            createdAt = createdAt,
            // A non-numeric timestamp cannot reach here: `SyncRecords.fromPayload` refuses the
            // record at the boundary rather than defaulting it, precisely so that this fallback is
            // unreachable instead of quietly moving a note to the end of the user's list.
            updatedAt = normalized.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L,
            isDeleted = SyncValues.toBoolean(deleted.parts[0]),
            deletedAt = deleted.parts[1]?.toLongOrNull(),
            hlcMs = normalized.rowClock.ms,
            hlcCounter = normalized.rowClock.counter,
            hlcNode = normalized.rowClock.node,
            fieldHlc = FieldClocks.serialize(
                RecordType.NOTE.fields.mapNotNull { f -> normalized.fieldClocks[f]?.let { f to it } }.toMap(),
            ),
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
            contentSyncedHlc = contentBaseline?.toString().orEmpty(),
        )
    }

    fun toFolderEntity(
        record: SyncRecord,
        createdAt: Long,
        dirty: Boolean,
        lastSyncedSeq: Long,
    ): FolderEntity {
        val normalized = record.normalized()
        val deleted = normalized.valueOf(FieldClocks.DELETED)
        return FolderEntity(
            id = normalized.uuid,
            name = normalized.valueOf(FieldClocks.NAME).parts[0].orEmpty(),
            colorArgb = normalized.valueOf(FieldClocks.COLOR).parts[0]?.toLongOrNull(),
            createdAt = createdAt,
            updatedAt = normalized.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L,
            isDeleted = SyncValues.toBoolean(deleted.parts[0]),
            deletedAt = deleted.parts[1]?.toLongOrNull(),
            hlcMs = normalized.rowClock.ms,
            hlcCounter = normalized.rowClock.counter,
            hlcNode = normalized.rowClock.node,
            fieldHlc = FieldClocks.serialize(
                RecordType.FOLDER.fields.mapNotNull { f -> normalized.fieldClocks[f]?.let { f to it } }.toMap(),
            ),
            dirty = dirty,
            lastSyncedSeq = lastSyncedSeq,
        )
    }

    /**
     * The `createdAt` to write for a merged record: the row's own if this device already has one,
     * and otherwise the record's `updatedAt`.
     *
     * ## Why this is not read off the record
     *
     * `createdAt` is a column the merge does not model. `FieldClocks.NOTE_FIELDS` excludes it on
     * the grounds that no write path moves it, so it has no history of its own — which is true, and
     * which also means a `SyncRecord` has nowhere to carry it. The payload *does* carry it (§5.1
     * lists it, and the desktop writes it), but it is dropped at the `SyncRecords.fromPayload`
     * boundary and never reaches a store.
     *
     * So a device seeing a record for the first time has to choose a value, and this is the choice
     * `ConflictCopies` already made for the identical problem: **the record's `updatedAt`**. A note
     * that arrives here came into existence carrying that body at that time, and dating it to the
     * moment of the merge instead would put it in the user's Recent list stamped with a moment they
     * were not editing.
     *
     * ## What it costs, honestly
     *
     * A note created on one device and edited before it reaches a second one has a later
     * `createdAt` on the second. The two devices then disagree about the "newest created" order —
     * the same class of visible divergence the plan's §5.3 refuses to accept for `updatedAt`. The
     * fix is not here: it is to give `createdAt` a clock and a place in `RecordType.fields`, so
     * that it merges like every other value. That is a change to `:core-domain` and to the merge's
     * field set, and it is recorded as owed rather than made silently here.
     *
     * An existing row keeps what it has, always. Nothing may move a `createdAt` that is already
     * set — it is the one column in this schema with no history to fall back on.
     */
    fun createdAtFor(existing: Long?, record: SyncRecord): Long =
        if (existing != null && existing != 0L) existing
        else record.valueOf(FieldClocks.UPDATED_AT).parts[0]?.toLongOrNull() ?: 0L
}
