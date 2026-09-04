package my.cheysoff.core_sync_codec

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * [RecordPayload] to `SyncRecord` and back — the wire vocabulary to the merge's vocabulary.
 *
 * ## Two vocabularies, on purpose
 *
 * A payload is keyed by **column** and is what crosses the network; a `SyncRecord` is keyed by
 * **clocked field** and is what `Merge` reasons about. They differ in exactly two places, and both
 * are the same idea: `content` carries `contentFormat` with it and `deleted` carries `deletedAt`
 * with it, because in each pair the two columns are one value and merging them apart is silent
 * corruption. `FieldValue` makes that structural — the merge takes a whole `FieldValue` from one
 * side or the other, so the two halves physically cannot come from different devices — and this
 * file is where the two columns are packed into one and unpacked again.
 *
 * ## `createdAt` crosses the wire and does not reach the merge
 *
 * The payload carries `createdAt`, because §5.1 says so and because the desktop writes it.
 * `SyncRecord` does not: `FieldClocks.NOTE_FIELDS` excludes it deliberately, on the grounds that no
 * write path moves it and it therefore has no history of its own to keep. So this conversion is
 * lossy in one direction, and the loss is real rather than theoretical — a device receiving a note
 * for the first time has no `createdAt` from the record and must supply one.
 *
 * The convention it supplies is the one `ConflictCopies` already chose for the same problem: the
 * record's `updatedAt`. See `RoomSyncStore.applyMerged`, which is where it happens, and
 * `docs/design/e2e-sync-phase3-plan.md` §5.1, whose field list includes `createdAt` and which is
 * therefore the place to start if this is ever closed properly (by giving `createdAt` a clock and
 * a place in `RecordType.fields`).
 */
object SyncRecords {

    /**
     * The payload for [record], with [createdAt] — the one column the merge does not model —
     * supplied by the caller.
     */
    fun toPayload(record: SyncRecord, createdAt: Long): RecordPayload {
        // Normalised on the way out, so a clock that merely equals the row clock is written
        // implicitly rather than as an entry. Two devices agreeing on a record's state while
        // disagreeing on which of the two legal encodings to use would compare unequal on every
        // byte while being perfectly converged.
        val normalized = record.validate().normalized()
        val columns = LinkedHashMap<String, String?>(PayloadFields.columnsOf(normalized.type).size)
        for (column in PayloadFields.columnsOf(normalized.type)) {
            columns[column] = when (column) {
                PayloadFields.CREATED_AT -> createdAt.toString()
                else -> {
                    val (field, index) = COLUMN_TO_FIELD.getValue(column)
                    normalized.valueOf(field).parts[index]
                }
            }
        }
        return RecordPayload(
            recType = normalized.type,
            uuid = normalized.uuid,
            rowClock = normalized.rowClock,
            fields = columns,
            clocks = normalized.fieldClocks,
        )
    }

    /**
     * The merge's view of [payload], or null if it is not a well-formed record.
     *
     * Null rather than a throw: the bytes came off the network, and the caller's job is to count a
     * record it cannot use and carry on rather than to crash a sync pass. `decode` has already
     * checked the payload's shape, so the only thing left for `validate` to catch is a
     * disagreement between this build's field set and the one that wrote the record.
     */
    fun fromPayload(payload: RecordPayload): SyncRecord? {
        // Refused rather than defaulted. `createdAt` and `updatedAt` are what the notes list sorts
        // on, and substituting 0 for one that will not parse would silently move a note to the end
        // of the list forever, on every device, with nothing anywhere saying why. A record this
        // build cannot read is one to count and skip.
        for (column in NUMERIC_COLUMNS) {
            val text = payload.fields[column] ?: continue
            if (text.toLongOrNull() == null) return null
        }
        val fields = LinkedHashMap<String, FieldValue>(payload.recType.fields.size)
        for (field in payload.recType.fields) {
            val columns = FIELD_TO_COLUMNS[field] ?: return null
            fields[field] = FieldValue(columns.map { payload.fields[it] })
        }
        return try {
            SyncRecord(
                type = payload.recType,
                uuid = payload.uuid,
                rowClock = payload.rowClock,
                fieldClocks = payload.clocks,
                fields = fields,
            ).validate().normalized()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Which columns make up each clocked field, in the order `RecordType.partCount` counts them.
     *
     * The order is load-bearing twice over: `FieldValue.parts[0]` is the body and `parts[1]` its
     * format, and every consumer — the codec, the Room rows, `ConflictCopies` — indexes on that.
     */
    private val FIELD_TO_COLUMNS: Map<String, List<String>> = mapOf(
        FieldClocks.TITLE to listOf(PayloadFields.TITLE),
        FieldClocks.CONTENT to listOf(PayloadFields.CONTENT, PayloadFields.CONTENT_FORMAT),
        FieldClocks.CHECKLIST to listOf(PayloadFields.CHECKLIST),
        FieldClocks.PINNED to listOf(PayloadFields.IS_PINNED),
        FieldClocks.FAVORITE to listOf(PayloadFields.IS_FAVORITE),
        FieldClocks.FOLDER to listOf(PayloadFields.FOLDER_ID),
        FieldClocks.UPDATED_AT to listOf(PayloadFields.UPDATED_AT),
        FieldClocks.DELETED to listOf(PayloadFields.IS_DELETED, PayloadFields.DELETED_AT),
        FieldClocks.NAME to listOf(PayloadFields.NAME),
        FieldClocks.COLOR to listOf(PayloadFields.COLOR_ARGB),
        FieldClocks.NOTE_ID to listOf(PayloadFields.NOTE_ID),
        FieldClocks.ANCHOR to listOf(PayloadFields.ANCHOR),
        FieldClocks.ORDER to listOf(PayloadFields.ORDER),
        FieldClocks.STROKES to listOf(PayloadFields.STROKES),
    )

    /**
     * Which columns make up [field], or null if no [RecordType] clocks a field by that name.
     *
     * An `internal` accessor over [FIELD_TO_COLUMNS] rather than making the map itself public: the
     * map's value type (a `List<String>`, ordered, meant to be indexed into) is this file's own
     * implementation detail, and a caller outside the module has no business depending on it.
     */
    internal fun columnsFor(field: String): List<String>? = FIELD_TO_COLUMNS[field]

    /**
     * The columns that must parse as a number, checked on the way in.
     *
     * A null is legal for the two nullable ones and is not a parse failure; only a non-null value
     * that is not a number is.
     */
    private val NUMERIC_COLUMNS = listOf(
        PayloadFields.CREATED_AT, PayloadFields.UPDATED_AT, PayloadFields.DELETED_AT,
        PayloadFields.COLOR_ARGB,
    )

    /** [FIELD_TO_COLUMNS] inverted: column to (field, index within the field's value). */
    private val COLUMN_TO_FIELD: Map<String, Pair<String, Int>> =
        FIELD_TO_COLUMNS.flatMap { (field, columns) ->
            columns.mapIndexed { index, column -> column to (field to index) }
        }.toMap()

    /**
     * Every column of every record type is either `createdAt` or covered by exactly one field.
     *
     * Checked here rather than in a test because the two maps above and `PayloadFields` are three
     * lists of the same strings, and a column added to one and not the others would otherwise
     * surface as a `NoSuchElementException` on the first record of that type to be pushed — which
     * is to say, in production, on the user's data.
     */
    init {
        RecordType.entries.forEach { type ->
            val covered = type.fields.flatMap { FIELD_TO_COLUMNS.getValue(it) }.toSet()
            val expected = PayloadFields.columnsOf(type) - PayloadFields.CREATED_AT
            check(covered == expected) { "$type: columns $covered do not cover $expected" }
        }
    }
}
