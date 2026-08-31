package my.cheysoff.desktop.store

import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncValues

/**
 * The column names inside a record payload's `fields` map, from
 * `docs/design/e2e-sync-phase3-plan.md` §5.1.
 *
 * These are **column** names, not [FieldClocks] keys, and the two sets deliberately differ. A clock
 * covers a *value*, and two of this schema's values span two columns each — `content` with
 * `contentFormat`, `isDeleted` with `deletedAt` — so `clocks` has eight note entries where `fields`
 * has eleven. Collapsing them into one vocabulary is exactly how `contentFormat` would end up
 * merged away from the `content` it describes, which `NoteDao.upsertNote` and `FieldClocks` both
 * call silent corruption.
 *
 * Renaming any string here is a protocol break: a payload written with the old name decodes as a
 * payload with an unknown key, which [RecordPayloadCodec] refuses.
 */
object PayloadFields {

    const val TITLE = "title"
    const val CONTENT = "content"
    const val CONTENT_FORMAT = "contentFormat"
    const val CHECKLIST = "checklist"
    const val IS_PINNED = "isPinned"
    const val IS_FAVORITE = "isFavorite"
    const val FOLDER_ID = "folderId"
    const val CREATED_AT = "createdAt"
    const val UPDATED_AT = "updatedAt"
    const val IS_DELETED = "isDeleted"
    const val DELETED_AT = "deletedAt"
    const val NAME = "name"
    const val COLOR_ARGB = "colorArgb"

    /** A note's eleven columns, in the order they are serialised. */
    val NOTE_COLUMNS: Set<String> = linkedSetOf(
        TITLE, CONTENT, CONTENT_FORMAT, CHECKLIST, IS_PINNED, IS_FAVORITE, FOLDER_ID,
        CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    /** A folder's six. */
    val FOLDER_COLUMNS: Set<String> = linkedSetOf(
        NAME, COLOR_ARGB, CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    fun columnsOf(type: RecordType): Set<String> = when (type) {
        RecordType.NOTE -> NOTE_COLUMNS
        RecordType.FOLDER -> FOLDER_COLUMNS
    }
}

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
