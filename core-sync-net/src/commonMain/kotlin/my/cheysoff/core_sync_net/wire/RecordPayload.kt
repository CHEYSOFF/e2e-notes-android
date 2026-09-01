package my.cheysoff.core_sync_net.wire

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * The sealed record payload: the bytes inside a `RecordEnvelope`, and the only representation of a
 * note that ever leaves a device or rests on a disk.
 *
 * This is `docs/design/e2e-sync-phase3-plan.md` §5.1, implemented. The plan sketches it as a
 * `@Serializable data class`; the shape here is the same and the encoding is this module's own
 * [JsonWriter]/[JsonReader] for the reasons [JsonValue]'s file already gives at length — chiefly
 * that this wire is strict in ways a generated serializer is not, and that a second JSON
 * implementation in this repository is exactly the failure it has already shipped once.
 *
 * ```
 * { "v": 1, "recType": "note", "uuid": "...", "hlc": "ms-counter-node", "created": 0,
 *   "fields":  { "title": "...", "content": "...", "contentFormat": "...", ... },
 *   "clocks":  { "content": "ms-counter-node", ... },
 *   "del": false, "serializer": 1 }
 * ```
 *
 * ## Why it lives in `:core-sync-net` and not in `:core-data`
 *
 * The plan puts `RecordCodec` in `:core-data`, which was the right place when `:core-data` was the
 * only module holding both the keys and the storage. It is the wrong place now: `:core-data` is
 * Android-only, and this payload is what an Apple device stores at rest as well as what it sends.
 * A copy on each platform is the two-implementations problem again, one layer above the two HKDFs.
 *
 * `:core-sync-net` is where it goes instead because it is where every ingredient already is — the
 * one JSON implementation, `SyncWire`'s field-name discipline, `:core-domain`'s `SyncRecord`, and
 * `:core-crypto-shared`'s envelope — and because the payload is a wire format, which is what this
 * module is for. Android adopting it later is a dependency edge it already has.
 *
 * ## Two decisions this file had to make, which the plan does not settle
 *
 * **1. `created` is a top-level field, not a clocked one.** The plan's §5.1 lists `createdAt` among
 * a note's field names, but `FieldClocks.NOTE_FIELDS` deliberately excludes it — and is right to:
 * `createdAt` is written once by the insert that creates the row and no write path can move it, so
 * it has no history to merge. `SyncRecord.validate()` therefore refuses it inside `fields`. It
 * still has to travel, or a note restored on a second device loses the day it was written. So it
 * sits beside `uuid` and `hlc`: carried, authenticated, never merged. On a conflict the survivor's
 * `created` is the one that was already there, which is the only answer a value with no clock can
 * give.
 *
 * **2. A null column and an absent one are different.** `folderId`, `colorArgb` and `deletedAt` are
 * nullable, and encoding null as `""` would make "no folder" and "a folder whose id is the empty
 * string" the same value — the point `FieldValue` makes about its own parts. So a null column is
 * JSON `null`, and a column **missing** from `fields` is a malformed payload rather than a null.
 *
 * Both are protocol decisions and both are reviewable: they change what an Android build must
 * write when it adopts this codec.
 *
 * ## Strictness
 *
 * Every failure returns [PayloadFault] rather than throwing, because a payload arrives from another
 * device and a malformed one is a record to refuse, not a reason to crash the sync engine — the
 * same position `RecordType.fromWireKey` takes. §8's F2 is honoured literally: a `v` this build
 * does not know is refused rather than decoded on a best-effort basis, because an older build that
 * re-serialised a newer payload would silently drop the fields it did not understand and push the
 * loss to every other device.
 */
object RecordPayload {

    /** The payload schema version this build writes and is the only one it accepts. */
    const val VERSION = 1

    /**
     * `contentSerializerVersion` — how a note body is spelled inside `content`.
     *
     * Carried and checked but not yet acted on: there is one serializer. It is here because §5.5
     * puts it here and because adding it later would mean every existing payload lacking it.
     */
    const val SERIALIZER_VERSION = 1

    private const val KEY_VERSION = "v"
    private const val KEY_REC_TYPE = "recType"
    private const val KEY_UUID = "uuid"
    private const val KEY_HLC = "hlc"
    private const val KEY_CREATED = "created"
    private const val KEY_FIELDS = "fields"
    private const val KEY_CLOCKS = "clocks"
    private const val KEY_DELETED = "del"
    private const val KEY_SERIALIZER = "serializer"

    /**
     * The columns each clocked field carries, in order.
     *
     * `SyncRecord` keeps `content`+`contentFormat` and `isDeleted`+`deletedAt` as one [FieldValue]
     * each, because a merge must take both halves from the same side or produce a body read with
     * the wrong parser and a tombstone with no timestamp. The payload spells the columns out
     * individually, so this table is the one place the pairing is written down, and
     * [RecordType.partCount] is what keeps it honest.
     */
    private val COLUMNS: Map<String, List<String>> = mapOf(
        FieldClocks.CONTENT to listOf("content", "contentFormat"),
        FieldClocks.DELETED to listOf("isDeleted", "deletedAt"),
    )

    private fun columnsOf(field: String): List<String> = COLUMNS[field] ?: listOf(field)

    /** Why a payload could not be read. Every one of these means "refuse this record". */
    enum class PayloadFault {
        /** Not JSON, or not a JSON object. */
        MALFORMED,

        /** A `v` this build does not know. §8 F2: halt rather than round-trip a lossy decode. */
        UNSUPPORTED_VERSION,

        /** A `recType` outside [RecordType]. */
        UNKNOWN_RECORD_TYPE,

        /** A required key is missing, of the wrong JSON type, or an `hlc` will not parse. */
        MALFORMED_FIELD,
    }

    /** A payload that was read, or the reason it was not. */
    sealed interface Decoded {
        class Ok(val record: SyncRecord, val createdAt: Long) : Decoded
        class Failed(val fault: PayloadFault) : Decoded
    }

    /**
     * Serialises [record] and [createdAt] into payload bytes, ready to be sealed.
     *
     * The field order is `NOTE_FIELDS`/`FOLDER_FIELDS` order rather than map order, so two devices
     * holding the same record produce the same bytes. Nothing in the protocol requires that — the
     * envelope is authenticated, not signed, and no one compares two devices' ciphertexts — but a
     * byte-stable encoding is what makes a payload diffable in a test, and it costs an ordered
     * iteration.
     *
     * [record] is validated first. A record that fails validation is a programming error on this
     * device, and sealing it would push the error to every other one.
     */
    fun encode(record: SyncRecord, createdAt: Long): ByteArray {
        record.validate()
        val fields = record.type.fields
        return JsonWriter().obj {
            field(KEY_VERSION, VERSION.toLong())
            field(KEY_REC_TYPE, record.type.wireKey)
            field(KEY_UUID, record.uuid)
            field(KEY_HLC, record.rowClock.toString())
            field(KEY_CREATED, createdAt)
            nullableStringMapField(KEY_FIELDS, buildColumnMap(record, fields))
            // Sparse by construction: `normalized()` drops every clock equal to the row clock, and
            // the reader restores them from it. The two halves of that convention must agree or a
            // freshly created record — whose map is legitimately empty — reads as having every
            // field at the zero clock.
            stringMapField(
                KEY_CLOCKS,
                record.normalized().fieldClocks
                    .entries
                    .sortedBy { fields.orderOf(it.key) }
                    .associate { (key, clock) -> key to clock.toString() },
            )
            field(KEY_DELETED, isTombstone(record))
            field(KEY_SERIALIZER, SERIALIZER_VERSION.toLong())
        }.toBytes()
    }

    /**
     * Reads payload bytes back.
     *
     * The returned record is **not** checked against the blinded ID it arrived under; that is §4's
     * third check and it belongs to the caller, which is the only place `K_id` exists. See
     * `RecordCodec`'s KDoc in the plan and `SyncTransport`'s, which draw the same boundary.
     */
    fun decode(bytes: ByteArray): Decoded {
        val root = try {
            JsonReader.parse(bytes.decodeToString())
        } catch (_: JsonParseException) {
            return Decoded.Failed(PayloadFault.MALFORMED)
        }
        val obj = (root as? JsonValue.Obj) ?: return Decoded.Failed(PayloadFault.MALFORMED)

        val version = obj.longOrNull(KEY_VERSION) ?: return fail(PayloadFault.MALFORMED_FIELD)
        // Checked before anything else is read, because every other check below assumes this
        // build's understanding of the shape.
        if (version != VERSION.toLong()) return fail(PayloadFault.UNSUPPORTED_VERSION)
        val serializer = obj.longOrNull(KEY_SERIALIZER) ?: return fail(PayloadFault.MALFORMED_FIELD)
        if (serializer != SERIALIZER_VERSION.toLong()) return fail(PayloadFault.UNSUPPORTED_VERSION)

        val typeKey = obj.stringOrNull(KEY_REC_TYPE) ?: return fail(PayloadFault.MALFORMED_FIELD)
        val type = RecordType.fromWireKey(typeKey) ?: return fail(PayloadFault.UNKNOWN_RECORD_TYPE)

        val uuid = obj.stringOrNull(KEY_UUID) ?: return fail(PayloadFault.MALFORMED_FIELD)
        if (uuid.isEmpty()) return fail(PayloadFault.MALFORMED_FIELD)
        val rowClock = obj.stringOrNull(KEY_HLC)?.let { Hlc.parse(it) }
            ?: return fail(PayloadFault.MALFORMED_FIELD)
        val createdAt = obj.longOrNull(KEY_CREATED) ?: return fail(PayloadFault.MALFORMED_FIELD)

        val columns = (obj.fields[KEY_FIELDS] as? JsonValue.Obj)
            ?: return fail(PayloadFault.MALFORMED_FIELD)
        val values = LinkedHashMap<String, FieldValue>()
        for (field in type.fields) {
            val parts = ArrayList<String?>(2)
            for (column in columnsOf(field)) {
                // Absent is malformed, null is null. A tolerant reader that turned a missing column
                // into null would turn a truncated payload into a note that quietly lost its
                // folder, which the merge would then propagate as a real edit.
                val cell = columns.fields[column] ?: return fail(PayloadFault.MALFORMED_FIELD)
                parts += when (cell) {
                    is JsonValue.Null -> null
                    is JsonValue.Str -> cell.value
                    else -> return fail(PayloadFault.MALFORMED_FIELD)
                }
            }
            values[field] = FieldValue(parts)
        }

        val clockEntries = (obj.fields[KEY_CLOCKS] as? JsonValue.Obj)
            ?: return fail(PayloadFault.MALFORMED_FIELD)
        val clocks = LinkedHashMap<String, Hlc>()
        for ((key, value) in clockEntries.fields) {
            // A clock under a key this build does not know is refused rather than dropped: an
            // unrecognised key reads as "at the row clock", i.e. silently newer than it was, which
            // is the silent-field-loss hazard `SyncRecord.validate` names.
            if (key !in type.fields) return fail(PayloadFault.MALFORMED_FIELD)
            val text = (value as? JsonValue.Str)?.value ?: return fail(PayloadFault.MALFORMED_FIELD)
            clocks[key] = Hlc.parse(text) ?: return fail(PayloadFault.MALFORMED_FIELD)
        }

        val record = SyncRecord(
            type = type,
            uuid = uuid,
            rowClock = rowClock,
            fieldClocks = clocks,
            fields = values,
        )
        return try {
            Decoded.Ok(record.validate(), createdAt)
        } catch (_: IllegalArgumentException) {
            // Everything `validate` checks is checked above too, so this is unreachable today. It
            // is here because `validate` is the boundary the merge trusts, and a future field added
            // to `RecordType` would otherwise slip past this reader and be caught by a crash inside
            // the merge instead of by a refused record here.
            fail(PayloadFault.MALFORMED_FIELD)
        }
    }

    /**
     * `del`, the tombstone flag.
     *
     * Redundant with `fields["isDeleted"]` and carried anyway, because §5.1 specifies it and
     * because it is the field a future server-side retention sweep would need without the ARK.
     * Derived rather than stored so the two cannot disagree.
     */
    private fun isTombstone(record: SyncRecord): Boolean =
        record.valueOf(FieldClocks.DELETED).parts.firstOrNull() ==
            my.cheysoff.core_domain.sync.SyncValues.TRUE

    private fun buildColumnMap(record: SyncRecord, fields: Set<String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (field in fields) {
            val value = record.valueOf(field)
            columnsOf(field).forEachIndexed { index, column -> out[column] = value.parts[index] }
        }
        return out
    }

    private fun fail(fault: PayloadFault): Decoded = Decoded.Failed(fault)

    private fun Set<String>.orderOf(key: String): Int = indexOfFirst { it == key }

    private fun JsonValue.Obj.stringOrNull(key: String): String? =
        (fields[key] as? JsonValue.Str)?.value

    private fun JsonValue.Obj.longOrNull(key: String): Long? =
        (fields[key] as? JsonValue.Num)?.raw?.toLongOrNull()
}
