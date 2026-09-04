package my.cheysoff.core_domain.sync

/**
 * Builders for the merge tests and the convergence harness.
 *
 * Every parameter has a default, so a test names only the fields it is actually about and a reader
 * can tell at a glance which two values the test is contrasting. That matters more here than
 * usual: a note record has eight clocked fields and a merge test that spelled all of them out
 * would bury its own point.
 */

/** An [Hlc], with the two components a test usually does not care about defaulted. */
fun hlc(ms: Long, counter: Int = 0, node: String = "a"): Hlc = Hlc(ms, counter, node)

/** A complete, valid note record. */
fun note(
    uuid: String = "n1",
    rowClock: Hlc = hlc(1),
    fieldClocks: Map<String, Hlc> = emptyMap(),
    title: String = "",
    content: String = "",
    contentFormat: String = "plain",
    checklist: String = "",
    pinned: Boolean = false,
    favorite: Boolean = false,
    folder: String? = null,
    updatedAt: Long = 0L,
    deleted: Boolean = false,
    deletedAt: Long? = null,
): SyncRecord = SyncRecord(
    type = RecordType.NOTE,
    uuid = uuid,
    rowClock = rowClock,
    fieldClocks = fieldClocks,
    fields = mapOf(
        FieldClocks.TITLE to FieldValue.of(title),
        FieldClocks.CONTENT to FieldValue.of(content, contentFormat),
        FieldClocks.CHECKLIST to FieldValue.of(checklist),
        FieldClocks.PINNED to FieldValue.of(SyncValues.of(pinned)),
        FieldClocks.FAVORITE to FieldValue.of(SyncValues.of(favorite)),
        FieldClocks.FOLDER to FieldValue.of(folder),
        FieldClocks.UPDATED_AT to FieldValue.of(updatedAt.toString()),
        FieldClocks.DELETED to FieldValue.of(SyncValues.of(deleted), deletedAt?.toString()),
    ),
).validate()

/** A complete, valid folder record. */
fun folder(
    uuid: String = "f1",
    rowClock: Hlc = hlc(1),
    fieldClocks: Map<String, Hlc> = emptyMap(),
    name: String = "",
    colorArgb: Long? = null,
    updatedAt: Long = 0L,
    deleted: Boolean = false,
    deletedAt: Long? = null,
): SyncRecord = SyncRecord(
    type = RecordType.FOLDER,
    uuid = uuid,
    rowClock = rowClock,
    fieldClocks = fieldClocks,
    fields = mapOf(
        FieldClocks.NAME to FieldValue.of(name),
        FieldClocks.COLOR to FieldValue.of(colorArgb?.toString()),
        FieldClocks.UPDATED_AT to FieldValue.of(updatedAt.toString()),
        FieldClocks.DELETED to FieldValue.of(SyncValues.of(deleted), deletedAt?.toString()),
    ),
).validate()

/** A complete, valid sketch record. */
fun sketch(
    uuid: String = "s1",
    rowClock: Hlc = hlc(1),
    fieldClocks: Map<String, Hlc> = emptyMap(),
    noteId: String = "n1",
    anchor: Int = 0,
    order: Int = 0,
    strokes: String = "",
    updatedAt: Long = 0L,
    deleted: Boolean = false,
    deletedAt: Long? = null,
): SyncRecord = SyncRecord(
    type = RecordType.SKETCH,
    uuid = uuid,
    rowClock = rowClock,
    fieldClocks = fieldClocks,
    fields = mapOf(
        FieldClocks.NOTE_ID to FieldValue.of(noteId),
        FieldClocks.ANCHOR to FieldValue.of(anchor.toString()),
        FieldClocks.ORDER to FieldValue.of(order.toString()),
        FieldClocks.STROKES to FieldValue.of(strokes),
        FieldClocks.UPDATED_AT to FieldValue.of(updatedAt.toString()),
        FieldClocks.DELETED to FieldValue.of(SyncValues.of(deleted), deletedAt?.toString()),
    ),
).validate()

/** The local row wrapper, with the bookkeeping most tests do not vary. */
fun local(
    record: SyncRecord,
    dirty: Boolean = false,
    contentBaseline: Hlc? = null,
): LocalRecord = LocalRecord(record = record, dirty = dirty, contentBaseline = contentBaseline)

/** The single-part value of [field], for the assertions that read one column back. */
fun SyncRecord.text(field: String): String? = valueOf(field).parts.first()

/** The second part of a two-column value — `contentFormat`, or `deletedAt`. */
fun SyncRecord.text2(field: String): String? = valueOf(field).parts[1]

/**
 * The merged record a result implies, whichever branch it took.
 *
 * `NoChange` means "the merge produced what you already hold", so the merged record is [fallback]
 * — normalised, because that is the form [Merge] returns and a test comparing the two should not
 * fail on whether a field clock was written down or left implicit.
 *
 * A test that is *about* which branch fired should still say so explicitly; this is for the ones
 * that are about a field's value and would otherwise be rewritten every time an unrelated rule
 * changed which branch a fixture happens to land in.
 */
fun MergeResult.mergedRecord(fallback: SyncRecord): SyncRecord = when (this) {
    is MergeResult.Applied -> record
    is MergeResult.ConflictCopy -> record
    MergeResult.NoChange -> fallback.normalized()
    is MergeResult.Rejected -> error("expected a merge, was rejected: $reason")
}
