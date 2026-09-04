package my.cheysoff.core_domain.sync

/**
 * The per-field clocks a row carries, and the rule that keeps them up to date — the contents of
 * the `fieldHlc` column on `notes` and `folders`.
 *
 * ## Why a row clock is not enough
 *
 * A row-level clock answers "which device wrote this row last", and that is all a record-level
 * last-writer-wins merge needs. It is also exactly what makes record-level LWW lossy: pin a note
 * on the phone, edit its body on the tablet, and one of the two writes is thrown away even though
 * they touched nothing in common. Those casual metadata gestures are precisely what people do on
 * two devices, which is why the design is field-level.
 *
 * So each row records, per field, when that field was last written. The merge engine then takes
 * each field from whichever side has the greater clock for it, independently.
 *
 * ## The representation, and its one convention
 *
 * `fieldHlc` is a semicolon-separated list of `field=<hlc>` entries, e.g.
 *
 * ```
 * title=1756612345678-0-a1b2c3d4;isFavorite=1756600000000-3-a1b2c3d4
 * ```
 *
 * and **a field absent from the list is at the row clock**. That convention is what keeps the
 * column small and what makes the empty string — the value MIGRATION_6_7 installs on every
 * pre-existing row, and the value a freshly created row carries — mean the honest thing: "every
 * field of this row was written at the row clock, together, in one go". A brand-new note is
 * exactly that.
 *
 * The consequence is that [stamp] writes down the fields it did **not** touch, not the ones it
 * did: after a write, the touched fields are at the new row clock and therefore implicit, and the
 * untouched ones are the ones whose older clocks would otherwise be forgotten. Forgetting them is
 * not a cosmetic loss — it would claim the whole row is as new as its most recent metadata
 * toggle, and the next merge would use that claim to discard a genuinely newer remote body.
 *
 * ## Fields that move together get one entry
 *
 * `content` and `contentFormat` share the [CONTENT] key, and `isDeleted` and `deletedAt` share
 * [DELETED], because in both pairs the two columns are one value: a body read back with the wrong
 * parser is silent corruption (`NoteDao.upsertNote` says so in its own KDoc), and a tombstone
 * flag without its stamp is a note that can never expire. Merging them independently is exactly
 * how they would come apart.
 *
 * ## Not a merge engine
 *
 * This file stores and retrieves clocks. Deciding what to do when two clocks disagree — including
 * the "`updatedAt` follows `content`" rule and conflict copies — belongs to the merge engine and
 * is deliberately not here.
 */
object FieldClocks {

    // ---------------------------------------------------------------------------------------
    // Canonical field keys.
    //
    // These strings end up inside a database column and, later, inside the encrypted payload the
    // other device parses. Renaming one is a data migration, not a refactor: every stored row
    // would keep the old key, which the new build would not recognise, and every unrecognised key
    // reads as "at the row clock" — i.e. silently newer than it is.
    // ---------------------------------------------------------------------------------------

    /** `notes.title`. */
    const val TITLE = "title"

    /** `notes.content` AND `notes.contentFormat` — one value, one clock. */
    const val CONTENT = "content"

    /** `notes.checklist`. Merges as a whole blob; checklist items have no stable identity. */
    const val CHECKLIST = "checklist"

    /** `notes.isPinned`. */
    const val PINNED = "isPinned"

    /** `notes.isFavorite`. */
    const val FAVORITE = "isFavorite"

    /** `notes.folderId`. */
    const val FOLDER = "folderId"

    /** `updatedAt` on either table — the user-visible "edited" time, clocked in its own right. */
    const val UPDATED_AT = "updatedAt"

    /** `isDeleted` AND `deletedAt` on either table — the tombstone, one value, one clock. */
    const val DELETED = "deleted"

    /** `folders.name`. */
    const val NAME = "name"

    /** `folders.colorArgb`. */
    const val COLOR = "colorArgb"

    /** `sketches.noteId` — the note a sketch belongs to. */
    const val NOTE_ID = "noteId"

    /** `sketches.anchor` — the block index the sketch is pinned under. */
    const val ANCHOR = "anchor"

    /** `sketches.order` — position among sketches sharing one [ANCHOR]. */
    const val ORDER = "order"

    /** `sketches.strokes` — the encoded drawing. See `StrokeCodec`. */
    const val STROKES = "strokes"

    /**
     * Every independently clocked field of a note, in the order they are serialised.
     *
     * `id` and `createdAt` are absent on purpose. `id` is the identity of the record and cannot
     * change. `createdAt` is written once, by the insert that creates the row, and after that only
     * ever initialised from the legacy `0` sentinel — no write path in either DAO can move it, so
     * it has no history of its own to keep and stays at the row clock.
     */
    val NOTE_FIELDS: Set<String> = linkedSetOf(
        TITLE, CONTENT, CHECKLIST, PINNED, FAVORITE, FOLDER, UPDATED_AT, DELETED,
    )

    /** Every independently clocked field of a folder, in the order they are serialised. */
    val FOLDER_FIELDS: Set<String> = linkedSetOf(NAME, COLOR, UPDATED_AT, DELETED)

    /**
     * Every independently clocked field of a sketch, in the order they are serialised.
     *
     * `id` is absent for the same reason `NOTE_FIELDS` omits it: it is the record's identity, not a
     * value that changes. There is no `createdAt` companion left out here the way there is for a
     * note — a sketch has no `createdAt` clock either, by the same "no write path ever moves it"
     * argument `PayloadFields.SKETCH_COLUMNS`'s KDoc restates on the payload side.
     */
    val SKETCH_FIELDS: Set<String> = linkedSetOf(NOTE_ID, ANCHOR, ORDER, STROKES, UPDATED_AT, DELETED)

    /** Separator between entries. Neither a field key nor a hex node contains one. */
    private const val ENTRY_SEPARATOR = ";"

    /** Separator between a field key and its clock. */
    private const val KEY_SEPARATOR = "="

    /**
     * Reads a serialised map back.
     *
     * Every kind of damage — an entry with no `=`, an unparseable clock, a duplicate key, a stray
     * empty segment — is dropped rather than thrown on, and a dropped entry degrades to "this
     * field is at the row clock", which is the same thing an older build's row says. The column is
     * written only by [stamp], so damage means either a downgrade or a bug; neither is worth
     * crashing a note editor over, and the merge engine's own ordering rules still hold on
     * whatever survives.
     *
     * On a duplicate key the FIRST entry wins, matching [serialize], which never writes one.
     */
    fun parse(serialized: String): Map<String, Hlc> {
        if (serialized.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Hlc>()
        for (entry in serialized.split(ENTRY_SEPARATOR)) {
            if (entry.isEmpty()) continue
            val split = entry.indexOf(KEY_SEPARATOR)
            if (split <= 0) continue
            val key = entry.substring(0, split)
            if (out.containsKey(key)) continue
            val clock = Hlc.parse(entry.substring(split + 1)) ?: continue
            out[key] = clock
        }
        return out
    }

    /**
     * Writes a map out in [parse]'s form.
     *
     * Iteration order is the caller's, and every caller here iterates [NOTE_FIELDS] or
     * [FOLDER_FIELDS], so the column is byte-stable for a given set of clocks. That is worth
     * having: it makes the value diffable in a database dump and comparable in a test without a
     * parse step.
     */
    fun serialize(clocks: Map<String, Hlc>): String {
        clocks.keys.forEach { key ->
            require(key.isNotEmpty()) { "a field key must not be empty" }
            require(!key.contains(ENTRY_SEPARATOR) && !key.contains(KEY_SEPARATOR)) {
                "a field key must not contain '$ENTRY_SEPARATOR' or '$KEY_SEPARATOR', was '$key'"
            }
        }
        clocks.values.forEach { clock ->
            require(!clock.node.contains(ENTRY_SEPARATOR) && !clock.node.contains(KEY_SEPARATOR)) {
                "an Hlc node must not contain '$ENTRY_SEPARATOR' or '$KEY_SEPARATOR'"
            }
        }
        return clocks.entries.joinToString(ENTRY_SEPARATOR) { (key, clock) -> "$key$KEY_SEPARATOR$clock" }
    }

    /**
     * The clock of one field: its own entry if it has one, otherwise [rowClock].
     *
     * This is the read side of the convention documented at the top of the file, and the only
     * correct way to ask "when was this field last written".
     */
    fun clockOf(field: String, serialized: String, rowClock: Hlc): Hlc =
        parse(serialized)[field] ?: rowClock

    /**
     * The new `fieldHlc` for a row whose [touched] fields have just been written at [newClock].
     *
     * @param previousSerialized the row's `fieldHlc` before this write; `""` for a row that does
     *   not exist yet.
     * @param previousRowClock the row's clock before this write, or **null if the row is being
     *   created by this write**. Null is the whole reason this parameter is nullable rather than
     *   defaulting to [Hlc.ZERO]: a new row has every field at [newClock], which the convention
     *   spells `""`, whereas a pre-existing row at the zero clock has untouched fields that really
     *   are at the zero clock and must be written down as such.
     * @param allFields [NOTE_FIELDS] or [FOLDER_FIELDS].
     * @param touched the fields this write changed. Keys outside [allFields] are a programming
     *   error and are rejected, because a typo'd key would be silently ignored and the field it
     *   meant would keep an old clock while its value changed — a merge would then quietly
     *   overwrite the new value with a stale remote one.
     */
    fun stamp(
        previousSerialized: String,
        previousRowClock: Hlc?,
        allFields: Set<String>,
        touched: Set<String>,
        newClock: Hlc,
    ): String {
        touched.forEach { field ->
            require(field in allFields) { "'$field' is not one of $allFields" }
        }
        // A row that did not exist a moment ago has no field older than this write.
        if (previousRowClock == null) return ""

        val previous = parse(previousSerialized)
        val next = LinkedHashMap<String, Hlc>()
        for (field in allFields) {
            if (field in touched) continue          // now at the row clock, so implicit
            val clock = previous[field] ?: previousRowClock
            if (clock == newClock) continue         // already at the row clock, so implicit
            next[field] = clock
        }
        return serialize(next)
    }
}
