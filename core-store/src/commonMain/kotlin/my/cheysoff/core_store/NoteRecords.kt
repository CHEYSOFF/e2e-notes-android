package my.cheysoff.core_store

import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues

/**
 * The translation between what the user edits — a [Note] or a [Folder] — and what is stored and
 * synced, a [SyncRecord].
 *
 * ## Why this is a file and not a method on `Note`
 *
 * Because it is a **lossy, opinionated** mapping in one direction and a tolerant one in the other,
 * and both halves need explaining. Going out, every value becomes text in a canonical spelling that
 * two devices must agree on to the byte — `SyncValues` exists for exactly that reason and says so.
 * Coming back, a value written by a build that spelled something differently must degrade to
 * something usable rather than crash a notes list: an unparseable `updatedAt` becomes `0` and an
 * unrecognised `contentFormat` becomes `PLAIN`, which is the direction `NoteContentFormat` already
 * argues for at length (showing HTML as text is ugly and recoverable; parsing text as HTML destroys
 * it).
 *
 * Putting that on the model classes would put merge-protocol spellings in `:core-domain`'s public
 * API, where the next person to add a field would not be looking for them.
 *
 * ## `createdAt` is not in the record
 *
 * It is carried beside it, as `RecordPayload`'s top-level `created`, because `FieldClocks` excludes
 * it from a note's field set deliberately — it is written once and no write path can move it, so it
 * has no history to merge and `SyncRecord.validate()` refuses it inside `fields`. Every function
 * here therefore takes or returns it separately. See `RecordPayload`'s KDoc.
 */
internal object NoteRecords {

    /**
     * [note] as a record stamped at [rowClock], with [fieldClocks] for whatever was written
     * earlier than that.
     *
     * The caller owns the clocks; this function only decides the *spelling* of the values.
     */
    fun toRecord(note: Note, rowClock: Hlc, fieldClocks: Map<String, Hlc>): SyncRecord = SyncRecord(
        type = RecordType.NOTE,
        uuid = note.id,
        rowClock = rowClock,
        fieldClocks = fieldClocks,
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of(note.title),
            // One value, two columns, one clock. They must never be taken from different sides of
            // a merge: a body read with the wrong parser is silent corruption.
            FieldClocks.CONTENT to FieldValue.of(note.content, note.contentFormat.storageValue),
            FieldClocks.CHECKLIST to FieldValue.of(note.checklist),
            FieldClocks.PINNED to FieldValue.of(SyncValues.of(note.isPinned)),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.of(note.isFavorite)),
            FieldClocks.FOLDER to FieldValue.of(note.folderId),
            FieldClocks.UPDATED_AT to FieldValue.of(note.updatedAt.toString()),
            // The tombstone: a flag without its stamp is a note that can never expire out of Trash.
            FieldClocks.DELETED to FieldValue.of(
                SyncValues.of(note.isDeleted),
                note.deletedAt?.toString(),
            ),
        ),
    ).validate()

    fun toNote(record: SyncRecord, createdAt: Long): Note {
        require(record.type == RecordType.NOTE) { "not a note record" }
        val content = record.valueOf(FieldClocks.CONTENT).parts
        val deleted = record.valueOf(FieldClocks.DELETED).parts
        return Note(
            id = record.uuid,
            title = record.text(FieldClocks.TITLE),
            content = content.getOrNull(0).orEmpty(),
            contentFormat = NoteContentFormat.fromStorage(content.getOrNull(1).orEmpty()),
            checklist = record.text(FieldClocks.CHECKLIST),
            isPinned = SyncValues.toBoolean(record.text(FieldClocks.PINNED)),
            isFavorite = SyncValues.toBoolean(record.text(FieldClocks.FAVORITE)),
            // Nullable on purpose: "" is a folder id, null is no folder. `FieldValue` keeps them
            // apart all the way down to the JSON, and this is where that pays for itself.
            folderId = record.valueOf(FieldClocks.FOLDER).parts.firstOrNull(),
            createdAt = createdAt,
            updatedAt = record.epochMillis(FieldClocks.UPDATED_AT),
            isDeleted = SyncValues.toBoolean(deleted.getOrNull(0)),
            deletedAt = deleted.getOrNull(1)?.toLongOrNull(),
        )
    }

    fun toRecord(folder: Folder, rowClock: Hlc, fieldClocks: Map<String, Hlc>): SyncRecord =
        SyncRecord(
            type = RecordType.FOLDER,
            uuid = folder.id,
            rowClock = rowClock,
            fieldClocks = fieldClocks,
            fields = mapOf(
                FieldClocks.NAME to FieldValue.of(folder.name),
                FieldClocks.COLOR to FieldValue.of(folder.colorArgb?.toString()),
                FieldClocks.UPDATED_AT to FieldValue.of(folder.updatedAt.toString()),
                FieldClocks.DELETED to FieldValue.of(
                    SyncValues.of(folder.isDeleted),
                    folder.deletedAt?.toString(),
                ),
            ),
        ).validate()

    fun toFolder(record: SyncRecord, createdAt: Long): Folder {
        require(record.type == RecordType.FOLDER) { "not a folder record" }
        val deleted = record.valueOf(FieldClocks.DELETED).parts
        return Folder(
            id = record.uuid,
            name = record.text(FieldClocks.NAME),
            // Null means "no colour chosen", and the UI derives one from the id. An unparseable
            // value means the same thing rather than crashing: the worst outcome is a folder that
            // is the wrong colour for one render.
            colorArgb = record.valueOf(FieldClocks.COLOR).parts.firstOrNull()?.toLongOrNull(),
            createdAt = createdAt,
            updatedAt = record.epochMillis(FieldClocks.UPDATED_AT),
            isDeleted = SyncValues.toBoolean(deleted.getOrNull(0)),
            deletedAt = deleted.getOrNull(1)?.toLongOrNull(),
        )
    }

    /**
     * The fields of [note] whose values differ from [previous]'s, for `FieldClocks.stamp`.
     *
     * This is what makes the store field-level rather than record-level. Stamping every field on
     * every save would make a pin gesture look like an edit of the body, and the merge would then
     * let the pin overwrite a newer remote body — which is precisely the case field-level LWW
     * exists to get right.
     *
     * Compared on the **record** rather than on the model, so the comparison uses the same
     * canonical spellings the merge will, and a field that round-trips to the same text is
     * correctly seen as untouched.
     */
    fun changedFields(previous: SyncRecord?, next: SyncRecord): Set<String> {
        if (previous == null) return next.type.fields
        return next.type.fields.filterTo(LinkedHashSet()) { field ->
            previous.fields[field] != next.fields[field]
        }
    }

    private fun SyncRecord.text(field: String): String =
        valueOf(field).parts.firstOrNull().orEmpty()

    /**
     * A timestamp column, or `0` if it will not parse.
     *
     * `0` is the same sentinel the v5-to-v6 Room migration backfilled for rows that predate the
     * column, so it is a value the rest of the app already handles rather than a new one invented
     * here.
     */
    private fun SyncRecord.epochMillis(field: String): Long =
        valueOf(field).parts.firstOrNull()?.toLongOrNull() ?: 0L
}
