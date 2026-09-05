package my.cheysoff.core_domain.sync

/**
 * One record as the merge engine sees it: an identity, a row clock, the per-field clocks, and the
 * field values themselves.
 *
 * ## What this is and is not
 *
 * This is the **merge's unit**, not the wire format and not a database row. It deliberately knows
 * nothing about envelopes, blinded IDs, `seq` numbers, Room, or JSON: `Merge.merge` is a pure
 * function of two of these, and that purity is the whole reason the N-replica convergence harness
 * is a cheap JVM property test rather than an emulator matrix
 * (`docs/design/e2e-sync-open-questions.md` §3).
 *
 * The transport layer converts a decrypted payload into one of these on the way in, and converts
 * one of these into a payload on the way out. The persistence layer converts a `NoteEntity` or a
 * `FolderEntity` into one of these and back. Neither conversion belongs here.
 *
 * ## Sparse field clocks
 *
 * [fieldClocks] follows exactly the convention `FieldClocks` documents and the `fieldHlc` column
 * stores: **a field absent from the map is at [rowClock]**. Use [clockOf] rather than reading the
 * map directly, or a freshly created record — whose map is legitimately empty — will look as
 * though every one of its fields is at the zero clock.
 *
 * ## Dense field values
 *
 * [fields] is the other way round: it must contain an entry for **every** key in [type]'s field
 * set, because a merge has to be able to hand back a value for each one. A missing value is a
 * programming error rather than a "same as something else" shorthand, and [validate] says so.
 */
data class SyncRecord(
    /** Note or folder. Decides which field set and which merge rules apply. */
    val type: RecordType,
    /** The record's local UUID — `notes.id` or `folders.id`. Stable for the record's whole life. */
    val uuid: String,
    /**
     * The row clock: the clock of the most recent write to this record, from whichever device.
     *
     * It is the default for every field not named in [fieldClocks], and it is the value the
     * rollback guard in [Merge] compares.
     */
    val rowClock: Hlc,
    /**
     * Per-field clocks, **sparse** — absent means "at [rowClock]". See `FieldClocks`.
     *
     * Keys outside [type]'s field set are rejected by [validate]: an unrecognised key would read
     * as "at the row clock", i.e. silently newer than whatever it actually was.
     */
    val fieldClocks: Map<String, Hlc>,
    /** Every field of [type], with its value. Dense; see the class KDoc. */
    val fields: Map<String, FieldValue>,
) {

    /**
     * The clock of [field]: its own entry if it has one, otherwise [rowClock].
     *
     * The read side of the sparse convention, and the only correct way to ask "when was this field
     * last written".
     */
    fun clockOf(field: String): Hlc = fieldClocks[field] ?: rowClock

    /** The value of [field]. Throws if the record is missing it, which [validate] would have caught. */
    fun valueOf(field: String): FieldValue =
        fields[field] ?: error("record $uuid has no value for '$field'")

    /**
     * Throws if this record is malformed, and returns it otherwise so it can be used inline.
     *
     * Called on every input to [Merge.merge]. The merge is the last place a bad record can be
     * stopped before it is written to the database and pushed to every other device, and every
     * check here is one whose absence would show up later as data rather than as a crash:
     *
     *  - a value missing for a declared field would make the merge hand back a hole;
     *  - a clock or a value under a key this build does not know would be dropped on the next
     *    re-serialisation, which is the "silent field loss" hazard the architecture doc names;
     *  - an empty uuid is a record that cannot be filed.
     *
     * A field clock **greater than [rowClock]** is deliberately NOT rejected here. It should not
     * happen — every local write stamps the row clock at least as high as anything it touches —
     * but a record arriving from a peer running a different build is not this device's to
     * validate away, and [Merge] normalises it by raising the merged row clock instead. Refusing
     * the record would turn a peer's harmless quirk into a halted sync engine.
     */
    fun validate(): SyncRecord {
        require(uuid.isNotEmpty()) { "a record must have a uuid" }
        type.fields.forEach { field ->
            require(fields.containsKey(field)) { "record $uuid is missing field '$field'" }
            val expected = type.partCount(field)
            val actual = valueOf(field).parts.size
            require(actual == expected) {
                "record $uuid field '$field' has $actual parts, expected $expected"
            }
        }
        fields.keys.forEach { field ->
            require(field in type.fields) { "record $uuid has a value for unknown field '$field'" }
        }
        fieldClocks.keys.forEach { field ->
            require(field in type.fields) { "record $uuid has a clock for unknown field '$field'" }
        }
        return this
    }

    /**
     * This record with every field clock that equals [rowClock] dropped from the map.
     *
     * The map and the row clock together can express the same state in more than one way — a
     * field at the row clock may be written down or left implicit — and two devices that agreed
     * on the state but disagreed on the encoding would fail a byte-identity check while being
     * perfectly converged. Normalising to the implicit form on the way out of a merge removes
     * that difference, and it is the same form `FieldClocks.stamp` produces, so a merged record
     * and a locally written one are directly comparable.
     */
    fun normalized(): SyncRecord {
        val trimmed = fieldClocks.filterValues { it != rowClock }
        return if (trimmed.size == fieldClocks.size) this else copy(fieldClocks = trimmed)
    }
}

/**
 * The local row: a [SyncRecord] plus the two things only this device knows about it.
 *
 * Kept separate from [SyncRecord] on purpose. Everything in [SyncRecord] is shared state that
 * travels between devices and must be identical on all of them for the account to be converged;
 * everything here is bookkeeping that is *supposed* to differ per device. Putting them in one
 * class would make it very easy to write a convergence assertion that compares `dirty` and then
 * spends an afternoon explaining why two correctly converged replicas do not match.
 */
data class LocalRecord(
    /** The record itself, as stored. */
    val record: SyncRecord,
    /**
     * True when this device holds a version the server has not acknowledged.
     *
     * The merge reads it for exactly two decisions, and both are load-bearing:
     *
     *  1. **The rollback guard.** A remote clock below ours is ordinary business on a dirty row —
     *     it means we have a newer local edit — and evidence of a server that went backwards on a
     *     clean one.
     *  2. **Conflict copies.** A clean row holds nothing the user has not already published, so
     *     replacing its body discards nothing.
     */
    val dirty: Boolean,
    /**
     * The `content` clock of the last version this device and the server agreed on, or null if
     * that is not known.
     *
     * ## Why the merge needs this, and why it is allowed to be null
     *
     * A conflict copy should be written when **both** devices edited the body since their last
     * common ancestor, and an HLC is a *total* order — it cannot express concurrency, so it cannot
     * answer that question by itself. The ancestor has to be recorded, and this is the smallest
     * useful form of that record: one clock, for the one field conflict copies are about.
     *
     * With a baseline the merge is precise. Pin a note on this device (which leaves `content` at
     * its old, already-pushed clock) while the other device edits the body, and no conflict copy
     * is written, because this device's body is provably an ancestor of the incoming one. That is
     * exactly the "casual multi-device gesture" the whole field-level design exists to handle
     * losslessly.
     *
     * **Null means "no ancestor is recorded", and the merge then falls back to the conservative
     * rule the design docs propose** (`e2e-sync-phase3-plan.md` §6 and decision D7): a dirty row
     * whose body differs from the incoming one produces a conflict copy. That is safe — nothing is
     * discarded — but it over-produces: the pin-then-remote-edit case above gains a duplicate note
     * it did not need. The schema as of v7 carries no per-field synced clock, so a caller reading
     * straight from `notes` has nothing to put here yet; closing D7 means one more column and
     * passing it.
     *
     * Both modes are exercised by the convergence harness, and both converge.
     */
    val contentBaseline: Hlc?,
)

/**
 * A field's value: the one or more columns that are written, clocked and merged **together**.
 *
 * ## Why a list rather than a string
 *
 * Two pairs of columns in this schema are one value each, and the design documents say so in the
 * strongest terms available: `content` and `contentFormat` must never drift apart, because a body
 * read back with the wrong parser is silent corruption (`NoteDao.upsertNote`'s own KDoc), and
 * `isDeleted` and `deletedAt` are one tombstone, because a flag without its stamp is a note that
 * can never expire.
 *
 * `FieldClocks` already gives each pair a single clock. Giving each pair a single *value* is the
 * other half of the same idea, and it is the half that makes the rule structural: the merge takes
 * a field from one side or the other, so two columns inside one [FieldValue] physically cannot be
 * taken from different sides. A `Map<String, String>` would leave that rule to be re-remembered at
 * every call site.
 *
 * Parts are nullable because the columns are: `folderId`, `colorArgb` and `deletedAt` are all
 * nullable in the schema, and encoding null as `""` would make "no folder" and "a folder whose id
 * is the empty string" the same value.
 */
data class FieldValue(val parts: List<String?>) : Comparable<FieldValue> {

    /**
     * A total order over values, used **only** to break a tie between two identical clocks.
     *
     * That tie should be unreachable: two clocks are equal only when they have the same node,
     * millisecond and counter, which means they were minted by one call to one `HlcGenerator` —
     * one write, one value. But "should be unreachable" is not a convergence guarantee, and the
     * failure mode if it ever happens is the worst one this design has: two replicas each keeping
     * their own value forever, with neither able to tell it is wrong. So the merge breaks the tie
     * with this comparison rather than with "whichever side the code happened to check first".
     *
     * Nulls sort below every string, and the comparison is a plain code-point [String.compareTo]
     * for the same reason [Hlc.compareTo] uses one: a locale-sensitive comparison would be a
     * divergence bug that appears only on devices set to certain languages.
     */
    override fun compareTo(other: FieldValue): Int {
        val shared = minOf(parts.size, other.parts.size)
        for (i in 0 until shared) {
            val a = parts[i]
            val b = other.parts[i]
            if (a == null && b == null) continue
            if (a == null) return -1
            if (b == null) return 1
            val byPart = a.compareTo(b)
            if (byPart != 0) return byPart
        }
        return parts.size.compareTo(other.parts.size)
    }

    companion object {
        /** A value from its parts, in the order [RecordType] declares for the field. */
        fun of(vararg parts: String?): FieldValue = FieldValue(parts.toList())
    }
}

/**
 * The canonical text encoding of the non-text columns, in one place.
 *
 * [SyncRecord.fields] holds every value as text, because the merge treats values as **opaque** —
 * it compares them for equality and, in one unreachable-in-theory case, orders them; it never
 * parses one. That is what keeps the merge free of every column's type.
 *
 * The cost of opacity is that both ends have to agree on the spelling, and "true" versus "1"
 * versus "TRUE" would be a silent divergence: two devices would hold values they each considered
 * correct and neither would ever converge on the other's. So the spelling lives here, is used by
 * `ConflictCopies` (the one place the merge layer *writes* a value rather than passing one
 * through), and is what the record codec on either side of this layer must encode to.
 *
 * `1`/`0` matches how SQLite stores the booleans these fields come from, so a value round-tripping
 * through the database and back out to the wire keeps the same spelling.
 */
object SyncValues {

    /** A true boolean column. */
    const val TRUE = "1"

    /** A false boolean column. */
    const val FALSE = "0"

    /** [TRUE] or [FALSE]. */
    fun of(value: Boolean): String = if (value) TRUE else FALSE

    /**
     * True only for exactly [TRUE].
     *
     * Anything else — [FALSE], an empty string, a value written by a build that spelled it
     * differently — reads as false, which is the safe direction for every boolean in this schema:
     * an unreadable `isDeleted` shows the note rather than hiding it, and an unreadable `isPinned`
     * leaves it in Recent rather than promoting it.
     */
    fun toBoolean(value: String?): Boolean = value == TRUE
}

/**
 * The kinds of record the protocol carries, each with the field set it merges over.
 *
 * [wireKey] is the `recType` string that goes into the blinded-ID HMAC message
 * (`HMAC(K_id, recType ‖ ":" ‖ uuid)`) and into the sealed payload. **Changing one of these
 * strings changes every blinded ID of that type**, which the server would see as the whole
 * account being deleted and re-uploaded under new names; it is a protocol break, not a rename.
 */
enum class RecordType(val wireKey: String, val fields: Set<String>) {

    /** A note. The only type that has a body, and therefore the only one that can conflict-copy. */
    NOTE("note", FieldClocks.NOTE_FIELDS),

    /** A folder. No body, so [Merge] never writes a conflict copy for one. */
    FOLDER("folder", FieldClocks.FOLDER_FIELDS),

    /**
     * A hand-drawn sketch anchored to a block inside a note.
     *
     * No body in the [Merge] sense either — `strokes` is a plain LWW field like any other, not a
     * contested text a user typed live on two devices — so it does not get a conflict copy. Storage
     * is `RoomSyncStore`/`RecordRows`'s `SKETCH` branches, mirroring `FOLDER`'s for the same reason.
     */
    SKETCH("sketch", FieldClocks.SKETCH_FIELDS),

    /**
     * An image attached to a note.
     *
     * Structurally a [SKETCH]: a child of a note, anchored and ordered, tombstoned when its note
     * is. No body in the [Merge] sense -- `image` is a plain LWW field, not a text two people typed
     * into concurrently -- so no conflict copy.
     */
    ATTACHMENT("attachment", FieldClocks.ATTACHMENT_FIELDS);

    /**
     * How many columns [field] carries — see [FieldValue].
     *
     * Two for the two pairs that move together, four and three for an attachment's two binary
     * payloads and the dimensions that describe them, one for everything else. Checked by
     * `SyncRecord.validate`, so a caller that packs a `content` without its `contentFormat` is
     * told at the boundary rather than writing a half-value into the database.
     *
     * `image` and `thumb` are grouped for exactly the reason `content` is grouped with
     * `contentFormat`: the merge takes a whole [FieldValue] from one side or the other, so pixels
     * can never end up described by the other device's dimensions.
     */
    fun partCount(field: String): Int = when (field) {
        FieldClocks.CONTENT -> 2   // content, contentFormat
        FieldClocks.DELETED -> 2   // isDeleted, deletedAt
        FieldClocks.IMAGE -> 4     // bytes, mimeType, width, height
        FieldClocks.THUMB -> 3     // thumbBytes, thumbWidth, thumbHeight
        else -> 1
    }

    companion object {
        /** The type whose [wireKey] is [key], or null. Null rather than throwing: the input is a
         * decrypted payload from another device, and an unknown record type is a record to refuse,
         * not a reason to crash the sync engine. */
        fun fromWireKey(key: String): RecordType? = entries.firstOrNull { it.wireKey == key }
    }
}
