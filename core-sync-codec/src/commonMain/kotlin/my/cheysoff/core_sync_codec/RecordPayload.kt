package my.cheysoff.core_sync_codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncValues

/**
 * The plaintext inside a record envelope, as `docs/design/e2e-sync-phase3-plan.md` §5.1 specifies
 * it.
 *
 * ```
 * { "v": 1, "recType": "note", "uuid": "...", "hlc": "ms-counter-node",
 *   "fields": { ... }, "clocks": { ... }, "del": false, "serializer": 1 }
 * ```
 *
 * [fields] is keyed by **column** name and is dense: every column of the record type is present.
 * [clocks] is keyed by **[FieldClocks]** name and is sparse: a field absent from it is at [rowClock],
 * which is the convention `FieldClocks` documents and the phone's `fieldHlc` column stores.
 *
 * ## One implementation, two apps
 *
 * This file and [RecordPayloadCodec] were written on the desktop app first, as
 * `desktop/store/RecordPayloadCodec.kt`, and moved here unchanged when the phone needed them. They
 * are in a shared module rather than copied because a note written on the phone has to be readable
 * on the desktop **byte for byte**: two implementations of one format is the failure this project
 * keeps meeting, and it is the one that produces a note nobody can open rather than a test failure.
 * `RecordPayloadWireFormatTest` pins the exact bytes so that a change to either end is a red test
 * rather than an unreadable account.
 */
data class RecordPayload(
    val recType: RecordType,
    val uuid: String,
    val rowClock: Hlc,
    /** Column name to value. Null is a real value — `folderId`, `colorArgb` and `deletedAt` are. */
    val fields: Map<String, String?>,
    /** [FieldClocks] key to that field's clock. Sparse; absent means [rowClock]. */
    val clocks: Map<String, Hlc>,
) {
    /** The value of [column], or null if it is null. Throws if the column is not in this type. */
    fun field(column: String): String? {
        require(fields.containsKey(column)) { "record $uuid has no column '$column'" }
        return fields[column]
    }
}

/** What [RecordPayloadCodec.decode] made of some bytes. */
sealed interface PayloadResult {
    data class Ok(val payload: RecordPayload) : PayloadResult

    /**
     * The payload declares a `v` or a `serializer` this build does not implement.
     *
     * Distinct from [Malformed] because the reactions differ: malformed is a damaged record, while
     * this is a record written by a newer build, and the plan is explicit that the engine must halt
     * rather than round-trip it — re-serialising a payload whose unknown parts were dropped is how
     * a newer device's fields get silently deleted (§5.1, and the architecture doc's risk table).
     */
    data class UnsupportedVersion(val payloadVersion: Int, val serializerVersion: Int) : PayloadResult

    /**
     * The payload named a `recType` this build does not implement.
     *
     * Deliberately **not** [Malformed]. A malformed record is one this build should have been able
     * to read and could not, which is evidence of corruption or a wrong key and is why the engine
     * refuses to page past it. This is the opposite: the record decrypted, authenticated and parsed
     * far enough to say what it is, and this build simply has no representation for it. Nothing is
     * wrong, and treating it as damage freezes the cursor of a device whose only fault is being a
     * version behind.
     */
    data class UnknownType(val wireKey: String) : PayloadResult

    /** Not a payload this build can parse at all. [reason] is for a bug report, not for the user. */
    data class Malformed(val reason: String) : PayloadResult
}

/**
 * Encodes and decodes [RecordPayload].
 *
 * ## Why hand-built JSON objects rather than `@Serializable`
 *
 * No serialization compiler plugin is applied anywhere in this build, and this file is the reason
 * one is not needed. Building the object key by key also makes two properties explicit that a
 * generated serializer would leave implicit and therefore changeable by accident: the key order is
 * fixed (so the same record encodes to the same bytes on every run, which is what makes a
 * round-trip test an equality test), and every unknown key is a decode failure rather than a
 * silently ignored one.
 *
 * That last part is the plan's §5.1 rule — *decode with `ignoreUnknownKeys = false` and refuse the
 * record* — and it is the single most important line in this file. A tolerant decoder plus a
 * re-serialisation is how an older build deletes a newer build's fields while reporting success.
 */
object RecordPayloadCodec {

    /** Payload schema version. Bumping it is a protocol change; see [PayloadResult.UnsupportedVersion]. */
    const val PAYLOAD_VERSION = 1

    /**
     * Content-serializer version — how `content` is encoded when `contentFormat` is `html`.
     *
     * Version 1 is `richeditor-compose` 1.1.0's `toHtml()`. This is not decoration: rc14 and 1.1.0
     * escape text differently (see the `richeditor` entry in `gradle/libs.versions.toml`), so a
     * body written by one and parsed by the other round-trips but does not compare equal. A device
     * that cannot render version N must refuse the record rather than re-save it at version 1.
     */
    const val SERIALIZER_VERSION = 1

    private const val KEY_VERSION = "v"
    private const val KEY_REC_TYPE = "recType"
    private const val KEY_UUID = "uuid"
    private const val KEY_HLC = "hlc"
    private const val KEY_FIELDS = "fields"
    private const val KEY_CLOCKS = "clocks"
    private const val KEY_DELETED = "del"
    private const val KEY_SERIALIZER = "serializer"

    private val TOP_LEVEL_KEYS = setOf(
        KEY_VERSION, KEY_REC_TYPE, KEY_UUID, KEY_HLC, KEY_FIELDS, KEY_CLOCKS, KEY_DELETED,
        KEY_SERIALIZER,
    )

    private val json = Json

    /** Encodes [payload] to the bytes that go inside a `RecordEnvelope`. */
    fun encode(payload: RecordPayload): ByteArray {
        val columns = PayloadFields.columnsOf(payload.recType)
        require(payload.fields.keys == columns) {
            "a ${payload.recType.wireKey} payload must carry exactly $columns, had ${payload.fields.keys}"
        }
        payload.clocks.keys.forEach { key ->
            require(key in payload.recType.fields) { "'$key' is not a clocked field of ${payload.recType}" }
        }

        val obj = buildJsonObject {
            put(KEY_VERSION, JsonPrimitive(PAYLOAD_VERSION))
            put(KEY_REC_TYPE, JsonPrimitive(payload.recType.wireKey))
            put(KEY_UUID, JsonPrimitive(payload.uuid))
            put(KEY_HLC, JsonPrimitive(payload.rowClock.toString()))
            put(
                KEY_FIELDS,
                buildJsonObject {
                    // Iterated in the type's declared column order rather than the map's, so the
                    // bytes do not depend on how the map happened to be built.
                    columns.forEach { column ->
                        val value = payload.fields[column]
                        put(column, if (value == null) JsonNull else JsonPrimitive(value))
                    }
                },
            )
            put(
                KEY_CLOCKS,
                buildJsonObject {
                    payload.recType.fields.forEach { field ->
                        payload.clocks[field]?.let { put(field, JsonPrimitive(it.toString())) }
                    }
                },
            )
            // `del` duplicates the `isDeleted` column, which is how §5.1 writes it: the plan lists
            // `isDeleted`/`deletedAt` among the fields AND carries a top-level `del`. Rather than
            // pick one and diverge from the plan, both are written and `decode` refuses a payload
            // where they disagree -- so the redundancy is a consistency check instead of a second
            // source of truth.
            put(KEY_DELETED, JsonPrimitive(isDeleted(payload)))
            put(KEY_SERIALIZER, JsonPrimitive(SERIALIZER_VERSION))
        }
        return json.encodeToString(JsonObject.serializer(), obj).encodeToByteArray()
    }

    /** Parses payload bytes. Every failure is a value, not an exception; see [PayloadResult]. */
    fun decode(bytes: ByteArray): PayloadResult = try {
        decodeOrThrow(bytes)
    } catch (e: Exception) {
        // These bytes have already passed GCM tag verification, so they are bytes this account
        // wrote -- a malformed one is a bug on some device, not an attacker. It is still not worth
        // crashing over, and the caller counts and surfaces it.
        PayloadResult.Malformed(e.message ?: e::class.simpleName.orEmpty())
    }

    private fun decodeOrThrow(bytes: ByteArray): PayloadResult {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject

        val unknown = root.keys - TOP_LEVEL_KEYS
        if (unknown.isNotEmpty()) {
            return PayloadResult.Malformed("unknown payload keys: ${unknown.sorted()}")
        }

        val version = root.getValue(KEY_VERSION).jsonPrimitive.int
        val serializer = root.getValue(KEY_SERIALIZER).jsonPrimitive.int
        if (version != PAYLOAD_VERSION || serializer != SERIALIZER_VERSION) {
            return PayloadResult.UnsupportedVersion(version, serializer)
        }

        val recTypeKey = root.getValue(KEY_REC_TYPE).jsonPrimitive.content
        val recType = RecordType.fromWireKey(recTypeKey)
            ?: return PayloadResult.UnknownType(recTypeKey)
        val uuid = root.getValue(KEY_UUID).jsonPrimitive.content
        if (uuid.isEmpty()) return PayloadResult.Malformed("empty uuid")
        val rowClock = Hlc.parse(root.getValue(KEY_HLC).jsonPrimitive.content)
            ?: return PayloadResult.Malformed("unparseable row clock")

        val columns = PayloadFields.columnsOf(recType)
        val fieldsObject = root.getValue(KEY_FIELDS).jsonObject
        if (fieldsObject.keys != columns) {
            return PayloadResult.Malformed(
                "a ${recType.wireKey} payload must carry exactly $columns, had ${fieldsObject.keys}",
            )
        }
        val fields = columns.associateWith { column ->
            val element = fieldsObject.getValue(column)
            if (element is JsonNull) null else element.jsonPrimitive.content
        }

        val clocksObject = root.getValue(KEY_CLOCKS).jsonObject
        val unknownClocks = clocksObject.keys - recType.fields
        if (unknownClocks.isNotEmpty()) {
            return PayloadResult.Malformed("unknown clock fields: ${unknownClocks.sorted()}")
        }
        val clocks = mutableMapOf<String, Hlc>()
        for ((field, element) in clocksObject) {
            val clock = Hlc.parse(element.jsonPrimitive.content)
                ?: return PayloadResult.Malformed("unparseable clock for '$field'")
            clocks[field] = clock
        }

        val payload = RecordPayload(recType, uuid, rowClock, fields, clocks)
        if (root.getValue(KEY_DELETED).jsonPrimitive.boolean != isDeleted(payload)) {
            return PayloadResult.Malformed("'del' disagrees with the isDeleted column")
        }
        return PayloadResult.Ok(payload)
    }

    private fun isDeleted(payload: RecordPayload): Boolean =
        payload.field(PayloadFields.IS_DELETED) == SyncValues.TRUE
}
