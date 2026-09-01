package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncValues

/**
 * A note as this device holds it: the domain object plus the clocks that decide a merge.
 *
 * The clocks are kept beside the note rather than inside it because [Note] is the type the UI
 * renders and the editor edits, and it has no business carrying an HLC. This is the same split
 * `SyncRecord`/`LocalRecord` makes in `:core-domain`, one layer down.
 */
data class NoteRow(val note: Note, val rowClock: Hlc, val clocks: Map<String, Hlc>)

/** The same for a folder. */
data class FolderRow(val folder: Folder, val rowClock: Hlc, val clocks: Map<String, Hlc>)

/**
 * Converts between a [NoteRow] and the payload that goes inside an envelope.
 *
 * Every value crosses as text, because that is what the merge engine treats records as — opaque
 * strings it compares and, in one unreachable-in-theory case, orders. The spelling of a boolean
 * comes from [SyncValues] rather than from `Boolean.toString()`: `"1"`/`"0"` is what SQLite stores
 * and what `ConflictCopies` writes, and a device that spelled it `"true"` would hold a value it
 * considered correct that no other device would ever converge on.
 */
object NoteRecords {

    fun toPayload(row: NoteRow): RecordPayload = RecordPayload(
        recType = RecordType.NOTE,
        uuid = row.note.id,
        rowClock = row.rowClock,
        fields = mapOf(
            PayloadFields.TITLE to row.note.title,
            PayloadFields.CONTENT to row.note.content,
            PayloadFields.CONTENT_FORMAT to row.note.contentFormat.storageValue,
            PayloadFields.CHECKLIST to row.note.checklist,
            PayloadFields.IS_PINNED to SyncValues.of(row.note.isPinned),
            PayloadFields.IS_FAVORITE to SyncValues.of(row.note.isFavorite),
            PayloadFields.FOLDER_ID to row.note.folderId,
            PayloadFields.CREATED_AT to row.note.createdAt.toString(),
            PayloadFields.UPDATED_AT to row.note.updatedAt.toString(),
            PayloadFields.IS_DELETED to SyncValues.of(row.note.isDeleted),
            PayloadFields.DELETED_AT to row.note.deletedAt?.toString(),
        ),
        clocks = row.clocks,
    )

    /**
     * Rebuilds a row from a payload, or returns null if a numeric column is not a number.
     *
     * Null rather than a default: `createdAt` and `updatedAt` are what the notes list sorts on, and
     * substituting 0 for an unreadable one would silently move a note to the end of the list
     * forever. A record that cannot be read is counted and left alone — see
     * [RecordNotesRepository]. `contentFormat` is the exception and degrades to `PLAIN`, because
     * [NoteContentFormat.fromStorage] already argues that case: rendering HTML as text is ugly and
     * recoverable, parsing text as HTML destroys characters.
     */
    fun fromPayload(payload: RecordPayload): NoteRow? {
        val createdAt = payload.field(PayloadFields.CREATED_AT)?.toLongOrNull() ?: return null
        val updatedAt = payload.field(PayloadFields.UPDATED_AT)?.toLongOrNull() ?: return null
        val deletedAtText = payload.field(PayloadFields.DELETED_AT)
        val deletedAt = if (deletedAtText == null) null else deletedAtText.toLongOrNull() ?: return null
        return NoteRow(
            note = Note(
                id = payload.uuid,
                title = payload.field(PayloadFields.TITLE).orEmpty(),
                content = payload.field(PayloadFields.CONTENT).orEmpty(),
                contentFormat = NoteContentFormat.fromStorage(
                    payload.field(PayloadFields.CONTENT_FORMAT).orEmpty(),
                ),
                checklist = payload.field(PayloadFields.CHECKLIST).orEmpty(),
                isPinned = SyncValues.toBoolean(payload.field(PayloadFields.IS_PINNED)),
                isFavorite = SyncValues.toBoolean(payload.field(PayloadFields.IS_FAVORITE)),
                folderId = payload.field(PayloadFields.FOLDER_ID),
                createdAt = createdAt,
                updatedAt = updatedAt,
                isDeleted = SyncValues.toBoolean(payload.field(PayloadFields.IS_DELETED)),
                deletedAt = deletedAt,
            ),
            rowClock = payload.rowClock,
            clocks = payload.clocks,
        )
    }
}

/** The folder half of [NoteRecords]. */
object FolderRecords {

    fun toPayload(row: FolderRow): RecordPayload = RecordPayload(
        recType = RecordType.FOLDER,
        uuid = row.folder.id,
        rowClock = row.rowClock,
        fields = mapOf(
            PayloadFields.NAME to row.folder.name,
            PayloadFields.COLOR_ARGB to row.folder.colorArgb?.toString(),
            PayloadFields.CREATED_AT to row.folder.createdAt.toString(),
            PayloadFields.UPDATED_AT to row.folder.updatedAt.toString(),
            PayloadFields.IS_DELETED to SyncValues.of(row.folder.isDeleted),
            PayloadFields.DELETED_AT to row.folder.deletedAt?.toString(),
        ),
        clocks = row.clocks,
    )

    fun fromPayload(payload: RecordPayload): FolderRow? {
        val createdAt = payload.field(PayloadFields.CREATED_AT)?.toLongOrNull() ?: return null
        val updatedAt = payload.field(PayloadFields.UPDATED_AT)?.toLongOrNull() ?: return null
        val colorText = payload.field(PayloadFields.COLOR_ARGB)
        val colorArgb = if (colorText == null) null else colorText.toLongOrNull() ?: return null
        val deletedAtText = payload.field(PayloadFields.DELETED_AT)
        val deletedAt = if (deletedAtText == null) null else deletedAtText.toLongOrNull() ?: return null
        return FolderRow(
            folder = Folder(
                id = payload.uuid,
                name = payload.field(PayloadFields.NAME).orEmpty(),
                colorArgb = colorArgb,
                createdAt = createdAt,
                updatedAt = updatedAt,
                isDeleted = SyncValues.toBoolean(payload.field(PayloadFields.IS_DELETED)),
                deletedAt = deletedAt,
            ),
            rowClock = payload.rowClock,
            clocks = payload.clocks,
        )
    }
}
