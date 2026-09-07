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
    const val NOTE_ID = "noteId"
    const val ANCHOR = "anchor"
    const val ORDER = "order"
    const val STROKES = "strokes"
    const val BYTES = "bytes"
    const val MIME_TYPE = "mimeType"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val THUMB_BYTES = "thumbBytes"
    const val THUMB_WIDTH = "thumbWidth"
    const val THUMB_HEIGHT = "thumbHeight"

    // An event's columns. See EVENT_COLUMNS, which is frozen.
    const val START_AT = "startAt"
    const val END_AT = "endAt"
    const val START_LOCAL = "startLocal"
    const val END_LOCAL = "endLocal"
    const val TIME_ZONE = "timeZone"
    const val ALL_DAY = "allDay"
    const val LOCATION = "location"
    const val DESCRIPTION = "description"
    const val DESCRIPTION_FORMAT = "descriptionFormat"
    const val REMINDERS = "reminders"
    const val RECURRENCE = "recurrence"
    const val LINKED_NOTE_ID = "linkedNoteId"
    const val SHARE_TO_GOOGLE = "shareToGoogle"

    /**
     * The reserved opaque column, carried by [ATTACHMENT_COLUMNS] and [EVENT_COLUMNS]. **Never
     * parsed, validated, normalised or defaulted by this build** -- carried verbatim in both
     * directions.
     *
     * It is in [ATTACHMENT_COLUMNS] from the very first shipped `attachment` record because
     * "later" is the one option this protocol does not offer: [RecordPayloadCodec] requires a
     * payload's key set to equal [columnsOf] **exactly**, so a column added to a record type after
     * it ships decodes as `Malformed` on every device still running the older build, freezing that
     * device's cursor and halting its whole account after five such records. See
     * `AttachmentData.meta` for what a later build is expected to put in here (a caption, alt
     * text, an original filename) without ever touching this set again.
     *
     * Deliberately **outside** `FieldClocks.ATTACHMENT_FIELDS`: it merges at the row clock rather
     * than carrying a clock of its own, which is exactly the precedent [CREATED_AT] already sets.
     */
    const val META = "meta"

    /** A note's eleven columns, in the order they are serialised. */
    val NOTE_COLUMNS: Set<String> = linkedSetOf(
        TITLE, CONTENT, CONTENT_FORMAT, CHECKLIST, IS_PINNED, IS_FAVORITE, FOLDER_ID,
        CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    /** A folder's six. */
    val FOLDER_COLUMNS: Set<String> = linkedSetOf(
        NAME, COLOR_ARGB, CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    /**
     * A sketch's eight columns, in the order they are serialised.
     *
     * `createdAt` rides along the same way it does for [NOTE_COLUMNS]: on the wire and in
     * [columnsOf], but excluded from `FieldClocks.SKETCH_FIELDS` because no write path ever moves
     * it once a sketch is created.
     */
    val SKETCH_COLUMNS: Set<String> = linkedSetOf(
        NOTE_ID, ANCHOR, ORDER, STROKES, CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    /**
     * An attachment's fifteen columns, in the order they are serialised.
     *
     * Mirrors [SKETCH_COLUMNS] with `strokes` expanded into the two binary payloads and the
     * dimensions that describe them, plus [META]. `createdAt` rides along the same way it does
     * everywhere else: on the wire and in [columnsOf], but excluded from
     * `FieldClocks.ATTACHMENT_FIELDS` because no write path ever moves it. [META] is excluded from
     * that field set too, for the different reason its own KDoc gives.
     *
     * **This set is frozen.** Adding a column to it after the first `attachment` record ships
     * halts the account of every device still on the older build -- see [META].
     */
    val ATTACHMENT_COLUMNS: Set<String> = linkedSetOf(
        NOTE_ID, ANCHOR, ORDER, BYTES, MIME_TYPE, WIDTH, HEIGHT,
        THUMB_BYTES, THUMB_WIDTH, THUMB_HEIGHT, META,
        CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    /**
     * A calendar event's twenty columns, in the order they are serialised.
     *
     * **This set is frozen.** Adding a column to it after the first `event` record ships halts the
     * account of every device still on the older build — see [META].
     *
     * Three of these carry no value this build ever writes, and are here anyway because "later" is
     * the one option this protocol does not offer:
     *
     *  - [RECURRENCE] — recurrence is deferred, but a repeat rule is certain to be wanted, and
     *    without the column a future build could not express one without breaking every older
     *    device. A record arriving with a non-empty value renders as a single occurrence here and
     *    is refused for editing, so this build can never blank someone else's repeat rule.
     *  - [REMINDERS] — likewise for notifications.
     *  - [DESCRIPTION_FORMAT] — always `plain` today. Google Calendar descriptions *are* HTML, so
     *    the moment anything is imported there has to be somewhere to say which parser a body
     *    wants. A body read with the wrong one is what `FieldClocks` calls silent corruption.
     *
     * [START_AT] and [END_AT] are the only columns any record type requires to be present **and
     * non-null**: an event with no start has no place on a calendar, and defaulting a missing one
     * to `0` would file it under 1 Jan 1970 on every device, permanently. See `SyncRecords`.
     */
    val EVENT_COLUMNS: Set<String> = linkedSetOf(
        TITLE, START_AT, END_AT, START_LOCAL, END_LOCAL, TIME_ZONE, ALL_DAY,
        LOCATION, DESCRIPTION, DESCRIPTION_FORMAT, REMINDERS, RECURRENCE,
        LINKED_NOTE_ID, SHARE_TO_GOOGLE, COLOR_ARGB, META,
        CREATED_AT, UPDATED_AT, IS_DELETED, DELETED_AT,
    )

    fun columnsOf(type: RecordType): Set<String> = when (type) {
        RecordType.NOTE -> NOTE_COLUMNS
        RecordType.FOLDER -> FOLDER_COLUMNS
        RecordType.SKETCH -> SKETCH_COLUMNS
        RecordType.ATTACHMENT -> ATTACHMENT_COLUMNS
        RecordType.EVENT -> EVENT_COLUMNS
    }
}
