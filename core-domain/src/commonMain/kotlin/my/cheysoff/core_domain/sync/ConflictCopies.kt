package my.cheysoff.core_domain.sync


/**
 * How a losing body becomes a note of its own.
 *
 * When two devices both edited a note's body since their last common ancestor, one of them has to
 * win the original record — the record's identity on the server has to stay stable or the other
 * device can never converge on it — and the other body is written out as a new note. Nothing the
 * user typed is discarded. That is the whole point, and it is the mitigation for the highest
 * severity risk the architecture doc names: a merge bug that reaches every device in seconds with
 * no undo.
 *
 * ## Everything about the copy is derived, and that is the load-bearing part
 *
 * The design docs describe the copy as having "a fresh UUID" and a title carrying the device label
 * and the local time — `"Title (conflict — Pixel 7, 30 Aug 14:32)"` — plus a rule to deduplicate
 * against `(uuid, loserContentHlc)` so two devices do not each write a copy of the other's loser.
 *
 * **This implementation derives the copy instead, and the deduplication then costs nothing**,
 * because both devices independently compute the *same* record:
 *
 *  - the uuid is [idFor], a deterministic function of the original uuid and the loser's content
 *    clock, so the two devices' copies collide on the server by construction and the second one
 *    to push simply merges into the first;
 *  - the row clock is the loser's content clock, so the two copies are also clock-identical;
 *  - the title suffix is a constant, [CONFLICT_SUFFIX], and **not** the device label and local
 *    time the docs suggest. Those are the two values that are guaranteed to differ between the two
 *    devices computing this copy — a device label is per device by definition and a formatted local
 *    time is per timezone — so putting either into a **synced** field would make the two copies
 *    differ in the one column the user reads, permanently, with each device certain it was right.
 *
 * Provenance is not lost by that choice; it is just not stored in the title. The copy's row clock
 * *is* the loser's clock, so it carries both the minting node and the millisecond, and a UI that
 * wants to render "from your other device, 30 Aug 14:32" can format that locally without either
 * device having to agree with the other about how.
 *
 * ## What the copy carries, and what it deliberately does not
 *
 * Title, body (with its format) and checklist — the things the user typed. **Not** the pin, the
 * favourite or the folder: a duplicate note appearing pinned at the top of the list is worse than
 * one appearing in Recent. Not the tombstone either; a copy is always created alive.
 *
 * `createdAt` is the one column a note row needs that this layer does not model — it is not an
 * independently clocked field (`FieldClocks.NOTE_FIELDS` explains why: no write path can move it).
 * The caller supplies it when it turns this record into a row, and the right value is the copy's
 * `updatedAt`: the copy came into existence carrying that body, and dating it to the merge instead
 * would put a note in the user's Recent list stamped with a moment they were not editing.
 */
object ConflictCopies {

    /**
     * Appended to the loser's title.
     *
     * Deliberately free of any per-device or per-timezone detail — see the class KDoc. It is also
     * plain text rather than markup, because `title` is not HTML.
     */
    const val CONFLICT_SUFFIX = " (conflict copy)"

    /**
     * Namespace string mixed into [idFor], so that the derivation cannot collide with any other
     * deterministic identifier this app might later derive from a note uuid.
     *
     * Changing it changes every conflict copy's identity, which for already-created copies means
     * nothing at all — they are ordinary notes by then, with their ids already stored — but for a
     * conflict being resolved concurrently on two devices at that moment it would mean the two
     * devices minting different ids and the account gaining two copies instead of one. It is a
     * protocol constant in practice; treat it as one.
     */
    const val ID_NAMESPACE = "manana/sync/v1/conflict-copy"

    /**
     * The uuid of the conflict copy that preserves [loserContentClock] of record [sourceUuid].
     *
     * Deterministic and identical on every device, which is what makes the docs' deduplication
     * rule automatic rather than a lookup: the two devices resolving the same conflict produce the
     * same id, so the copies are the same record and the server's compare-and-set folds them
     * together on the second push.
     *
     * The clock is part of the input because a note can be conflicted more than once, and each
     * distinct losing body deserves its own copy. Two merges that discard the *same* body produce
     * the same id and therefore the same single copy, which is exactly the idempotence the sync
     * loop needs — a re-delivered record must not grow a second duplicate.
     *
     * The derivation is MD5-based, and that is fine here and nowhere else: this is a
     * naming function, not a security one. Nothing is authenticated by this id, an attacker who
     * could choose it gains only the ability to name a note in an account they would already have
     * to hold the ARK to read, and the property actually required is determinism across devices,
     * which MD5 has. It is used because it is in the JDK and produces a value shaped exactly like
     * every other note id in this app. It is computed by [NameUuid] rather than by the JDK so
     * that a non-JVM device derives the same id; see the note there, and `NameUuidParityTest`,
     * which pins the two against each other.
     */
    fun idFor(sourceUuid: String, loserContentClock: Hlc): String {
        val name = "$ID_NAMESPACE|$sourceUuid|$loserContentClock"
        return NameUuid.v3(name.encodeToByteArray())
    }

    /**
     * Builds the copy record for the body [loser] holds.
     *
     * [loser] is whichever side lost the `content` field — it may be the local record or the
     * remote one, and the function does not care which, because both devices have to build the
     * same copy from the same loser for the two of them to converge.
     *
     * The result's `fieldClocks` is empty, which under the sparse convention means "every field is
     * at the row clock". That is literally true of this record: it did not exist a moment ago and
     * every one of its fields is being written now, in one go. It is also the same encoding
     * `FieldClocks.stamp` produces for a newly created row, so a copy and a hand-made note are
     * byte-comparable.
     */
    fun copyOf(loser: SyncRecord, loserContentClock: Hlc): SyncRecord {
        require(loser.type == RecordType.NOTE) {
            "only notes have a body, so only notes produce conflict copies; was ${loser.type}"
        }
        val title = loser.valueOf(FieldClocks.TITLE).parts.firstOrNull().orEmpty()
        return SyncRecord(
            type = RecordType.NOTE,
            uuid = idFor(loser.uuid, loserContentClock),
            // The loser's own content clock, so both devices' copies are clock-identical as well
            // as value-identical. It is a clock from the past, which is harmless: no other device
            // has ever seen this uuid, so there is nothing for it to lose a comparison against.
            rowClock = loserContentClock,
            fieldClocks = emptyMap(),
            fields = mapOf(
                FieldClocks.TITLE to FieldValue.of(title + CONFLICT_SUFFIX),
                // Body and format together, as one value, exactly as they were on the loser.
                FieldClocks.CONTENT to loser.valueOf(FieldClocks.CONTENT),
                FieldClocks.CHECKLIST to loser.valueOf(FieldClocks.CHECKLIST),
                // Not pinned, not favourited, not filed: a duplicate note at the top of the list
                // is worse than one in Recent.
                FieldClocks.PINNED to FieldValue.of(SyncValues.FALSE),
                FieldClocks.FAVORITE to FieldValue.of(SyncValues.FALSE),
                FieldClocks.FOLDER to FieldValue.of(null),
                // The loser's own edit time, so the copy sits next to the note it came from
                // rather than jumping to the top of a newest-first list.
                FieldClocks.UPDATED_AT to loser.valueOf(FieldClocks.UPDATED_AT),
                // Alive. A copy exists to be read.
                FieldClocks.DELETED to FieldValue.of(SyncValues.FALSE, null),
            ),
        ).validate()
    }
}
