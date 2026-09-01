package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.RecordType

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
