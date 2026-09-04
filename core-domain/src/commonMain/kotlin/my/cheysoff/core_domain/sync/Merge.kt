package my.cheysoff.core_domain.sync

/**
 * Field-level last-writer-wins over per-field hybrid logical clocks: the correctness core of sync.
 *
 * ## Pure, on purpose
 *
 * No Android, no Room, no coroutines, no clock of its own, no I/O. [merge] is a function of its two
 * arguments and nothing else. That is not architectural taste — `e2e-sync-open-questions.md` §3
 * shows that the N-replica convergence harness is a cheap JVM property test **if and only if** this
 * is a pure function, and an emulator matrix otherwise. `ConvergenceTest` is that harness, and it
 * runs hundreds of seeded schedules in the time one emulator takes to boot.
 *
 * ## The rules, and why each one exists
 *
 * **1. Field-level, not row-level.** Each field is taken from whichever side has the greater clock
 * for it, independently. Pin a note on the phone while editing its body on the tablet and both
 * survive; whole-row LWW would throw one of them away, and those casual metadata gestures are
 * exactly what people do across two devices.
 *
 * **2. `updatedAt` follows `content`.** `updatedAt` is the field the UI sorts on and the one the
 * user actually reads, so a body that arrives without the time it was written produces two devices
 * showing the same notes in a different order forever. See [mergeUpdatedAt] for how that is done
 * without breaking convergence.
 *
 * **3. Deletion is an ordinary field.** No special case: the tombstone is one clocked value like
 * any other. Because the delete is *soft*, the deleting device still holds the body, so a
 * resurrection is a genuine undelete rather than a blank note.
 *
 * **4. A contested body is never discarded.** When both devices edited the body since their last
 * common ancestor, the higher clock keeps the original record — its identity on the server has to
 * stay stable — and the loser is written out as a new note. See `ConflictCopies`.
 *
 * **5. A clean row does not accept an older record.** The rollback guard;
 * see [RejectReason.ROLLBACK_SUSPECTED], which also states its blind spot.
 *
 * ## Why the result is convergent
 *
 * Every field is `max` over a total order (`(ms, counter, node)`, with value order as an
 * unreachable last resort), the row clock is `max` over the same order, and `max` is commutative,
 * associative and idempotent. So the merge is a join on a semilattice, which is the standard —
 * and the only — reason to believe N replicas exchanging records in any order end up in the same
 * place. The harness is what checks that the implementation actually is one.
 */
object Merge {

    /**
     * Merges [remote] into [local] and says what to write.
     *
     * @param local the row this device holds, or **null if this device has never seen this
     *   record**. Null is a normal case, not an error: it is every record's first arrival. It is
     *   also the case the rollback guard cannot cover, because there is no local clock to compare
     *   against — see [RejectReason.ROLLBACK_SUSPECTED].
     * @param remote the record that arrived, from a pull or inline in a `409`. The two are the
     *   same thing to this function and deliberately share one code path; a second, subtly
     *   different merge for the conflict case is how the two drift apart.
     */
    fun merge(local: LocalRecord?, remote: SyncRecord): MergeResult {
        remote.validate()

        if (local == null) {
            // First sight. Nothing to merge against, so the record is taken exactly as it came —
            // and it is NOT dirty, because it is the server's own copy that just arrived.
            return MergeResult.Applied(record = remote.normalized(), dirty = false)
        }
        local.record.validate()

        if (local.record.uuid != remote.uuid || local.record.type != remote.type) {
            return MergeResult.Rejected(RejectReason.IDENTITY_MISMATCH)
        }

        // The rollback guard, before any field is looked at. A clean row is one the server has
        // acknowledged, so the only way its clock can be ahead of the server's is that the server
        // went backwards. On a dirty row the same comparison means "we hold a newer local edit",
        // which is ordinary business and is what the field loop below is for.
        if (!local.dirty && remote.rowClock < local.record.rowClock) {
            return MergeResult.Rejected(RejectReason.ROLLBACK_SUSPECTED)
        }

        val type = remote.type
        val mergedFields = LinkedHashMap<String, FieldValue>(type.fields.size)
        val mergedClocks = LinkedHashMap<String, Hlc>(type.fields.size)

        for (field in type.fields) {
            // updatedAt is decided after the loop, because on a note it is not decided by its own
            // clock alone. Every other field is a plain max.
            if (field == FieldClocks.UPDATED_AT) continue
            val (clock, value) = takeGreater(field, local.record, remote)
            mergedClocks[field] = clock
            mergedFields[field] = value
        }

        val (updatedAtClock, updatedAtValue) = mergeUpdatedAt(local.record, remote)
        mergedClocks[FieldClocks.UPDATED_AT] = updatedAtClock
        mergedFields[FieldClocks.UPDATED_AT] = updatedAtValue

        // The merged row clock is the highest clock anywhere in the merged record.
        //
        // `max(localRow, remoteRow)` alone would be enough for well-formed inputs — every local
        // write stamps the row clock at least as high as any field it touches. It is not enough
        // for a record from a peer running a different build, where a field clock could exceed
        // its own row clock; raising the row clock to cover it keeps the sparse encoding honest
        // (a field is dropped from the map only when it really is at the row clock) instead of
        // refusing a record over a quirk that costs nothing to absorb.
        var rowClock = maxOf(local.record.rowClock, remote.rowClock)
        for (clock in mergedClocks.values) if (clock > rowClock) rowClock = clock

        val merged = SyncRecord(
            type = type,
            uuid = remote.uuid,
            rowClock = rowClock,
            // Ordered by the type's field set rather than by insertion, so two records holding the
            // same clocks serialise identically. `FieldClocks.serialize` relies on the caller's
            // iteration order for exactly the same reason.
            fieldClocks = type.fields.mapNotNull { field ->
                mergedClocks[field]?.let { field to it }
            }.toMap(),
            fields = type.fields.associateWith { field -> mergedFields.getValue(field) },
        ).normalized()

        // Does the server already hold this? It does exactly when the merge came out identical to
        // the record that arrived — every field taken from the remote side, no local contribution
        // left. Anything else is a version only this device has, and the next push must send it.
        val dirty = merged != remote.normalized()

        val copy = conflictCopy(local, remote)
        if (copy != null) {
            return MergeResult.ConflictCopy(record = merged, dirty = dirty, copy = copy)
        }

        // Nothing to write. Note that this compares the dirty flag too: a row whose data is
        // unchanged but which has just been proved to be on the server (the dropped-push-response
        // case in `e2e-sync-phase3-plan.md` §3.3) still has to be written, or it stays dirty and
        // is pushed forever.
        if (merged == local.record.normalized() && dirty == local.dirty) return MergeResult.NoChange

        return MergeResult.Applied(record = merged, dirty = dirty)
    }

    /**
     * The `(clock, value)` for one field: whichever side's clock is greater.
     *
     * On **equal clocks** the values are compared instead and the greater one is taken. That
     * branch should be unreachable — two clocks are equal only when they carry the same node,
     * millisecond and counter, which means one call to one `HlcGenerator`, which means one write
     * and one value — but "unreachable" is not a convergence guarantee, and the consequence of
     * getting it wrong is the worst failure this design has: two replicas each keeping their own
     * value forever with neither able to tell it is wrong. Deciding it by a total order over the
     * values costs one comparison and makes the outcome identical on both devices even if the
     * invariant is one day broken. See [FieldValue.compareTo].
     */
    private fun takeGreater(field: String, local: SyncRecord, remote: SyncRecord): Pair<Hlc, FieldValue> {
        val localClock = local.clockOf(field)
        val remoteClock = remote.clockOf(field)
        val localValue = local.valueOf(field)
        val remoteValue = remote.valueOf(field)
        val byClock = localClock.compareTo(remoteClock)
        val winner = when {
            byClock > 0 -> localValue
            byClock < 0 -> remoteValue
            else -> if (localValue >= remoteValue) localValue else remoteValue
        }
        return maxOf(localClock, remoteClock) to winner
    }

    /**
     * `updatedAt`, merged so that **it follows the body**.
     *
     * ## The rule
     *
     * A side's `updatedAt` is offered at the greater of two clocks: its own, and that side's
     * `content` clock. Whichever side offers the higher one wins, and its `updatedAt` is taken.
     *
     * ## Why it is not simply "the winner of `content` decides"
     *
     * Because `updatedAt` genuinely does move without the body. `clearFolder` unfiles a note
     * during a folder delete and bumps `updatedAt` while leaving `content` alone — the one mass
     * edit in the app that is not aimed at any particular note. If the content winner decided
     * `updatedAt` outright, a device that had done that would lose the bump on the next merge and
     * the two devices would show the note at different positions in a newest-first list. Which is
     * the exact failure this rule exists to prevent, arrived at from the other direction.
     *
     * ## Why it is not simply "an ordinary field"
     *
     * Under well-behaved writers it would be: `RoomNotesRepository.SAVE_NOTE_FIELDS` stamps
     * `content` and `updatedAt` at the same clock, so ordinary max-by-own-clock already keeps them
     * together and the invariant `updatedAtClock >= contentClock` holds on every row this app
     * writes. Taking the content clock into account as well costs nothing when that invariant
     * holds and is what keeps the rule true when it does not — a record from a peer whose build
     * stamped them separately, or a future write path that forgets. The rule is stated in the
     * design docs as a rule, so it is implemented as one rather than left as an emergent property
     * of two other files agreeing with each other.
     *
     * ## Why it still converges
     *
     * The offered clock is `max(ownUpdatedAtClock, ownContentClock)`, and the merged record stores
     * exactly that as its `updatedAt` clock while its merged `content` clock is `max` of the two
     * content clocks — so the merged record's own offer is the same value again. The rule is
     * therefore idempotent and order-independent, which is what a join needs. `ConvergenceTest`
     * checks that claim rather than trusting this paragraph.
     *
     * On a folder there is no `content`, so this degenerates to an ordinary max-by-own-clock.
     */
    private fun mergeUpdatedAt(local: SyncRecord, remote: SyncRecord): Pair<Hlc, FieldValue> {
        val companion = updatedAtCompanion(local.type)
        val localClock = effectiveUpdatedAtClock(local, companion)
        val remoteClock = effectiveUpdatedAtClock(remote, companion)
        val localValue = local.valueOf(FieldClocks.UPDATED_AT)
        val remoteValue = remote.valueOf(FieldClocks.UPDATED_AT)
        val byClock = localClock.compareTo(remoteClock)
        val winner = when {
            byClock > 0 -> localValue
            byClock < 0 -> remoteValue
            // Same unreachable-in-theory tie as [takeGreater], broken the same deterministic way.
            else -> if (localValue >= remoteValue) localValue else remoteValue
        }
        return maxOf(localClock, remoteClock) to winner
    }

    /**
     * The field whose clock `updatedAt` is dragged along by, or null if the type has none.
     *
     * A note's `updatedAt` describes when its body was last edited, so `content` is what it
     * follows. A folder has no body — its `updatedAt` moves when its name or colour is saved, and
     * both of those are its own independently clocked fields, so there is nothing to bind it to
     * and it merges on its own clock.
     */
    private fun updatedAtCompanion(type: RecordType): String? = when (type) {
        RecordType.NOTE -> FieldClocks.CONTENT
        RecordType.FOLDER -> null
        // `strokes` is a sketch's user-facing payload, the same role `content` plays for a note --
        // so it is the companion, not `null`. `noteId`/`anchor`/`order` are positional bookkeeping,
        // structurally like a note's `folderId`, which deliberately does NOT drag `updatedAt`
        // along. This matters once a later release re-stamps `anchor` when text above a drawing is
        // edited (plan 3): without this companion, two devices could disagree on a sketch's
        // `updatedAt` -- and therefore its sort order -- after nothing but a text reflow.
        RecordType.SKETCH -> FieldClocks.STROKES
    }

    /** `max(the field's own clock, the companion field's clock)`. See [mergeUpdatedAt]. */
    private fun effectiveUpdatedAtClock(record: SyncRecord, companion: String?): Hlc {
        val own = record.clockOf(FieldClocks.UPDATED_AT)
        if (companion == null) return own
        return maxOf(own, record.clockOf(companion))
    }

    /**
     * The conflict copy this merge owes, or null if it owes none.
     *
     * ## The four conditions, and what each one rules out
     *
     * 1. **The record is a note.** Only notes have a body; a folder's name losing a merge is not
     *    text the user will miss.
     * 2. **The local row is dirty.** A clean row holds only what the server already has, so its
     *    body cannot be a concurrent edit — it is an ancestor of whatever is arriving, and
     *    discarding an ancestor is what a merge is for.
     * 3. **The two bodies actually differ, and the losing one is not empty.** Two devices that
     *    typed the same thing have nothing to preserve, and a copy would be a duplicate of a note
     *    the user can already see. This is also what makes the dropped-push-response case quiet: a
     *    device that takes a `409` against its own committed write is handed back a body identical
     *    to its own. An **empty** losing body is the same argument in its strongest form — the
     *    rule is "never silently discard a user's text", and an empty body is not text. Without
     *    that exemption a note that exists on two devices before either has typed into it — the
     *    blank row `NotesListViewModel.createNewNote` persists, or a note first touched by a pin —
     *    produces a duplicate containing nothing at all.
     * 4. **Neither body is the known common ancestor.** This is [LocalRecord.contentBaseline]'s
     *    entire job. With a baseline the test is precise; without one it is skipped, and the merge
     *    falls back to the conservative rule the design docs propose (decision D7) — which never
     *    loses text but does produce a copy in cases where one side's body was in fact the
     *    ancestor. Read [LocalRecord.contentBaseline] before assuming null is fine.
     *
     * ## Which side loses
     *
     * The side with the **lower** content clock, whichever it is. The rule is symmetric on
     * purpose: both devices resolve the same conflict, and if each preserved "the other one" they
     * would preserve different bodies and neither would end up holding both. Because the copy is
     * derived entirely from the loser (`ConflictCopies`), the two devices produce the same record,
     * push it to the same blinded id, and the account gains exactly one duplicate.
     */
    private fun conflictCopy(local: LocalRecord, remote: SyncRecord): SyncRecord? {
        if (remote.type != RecordType.NOTE) return null
        if (!local.dirty) return null

        val localClock = local.record.clockOf(FieldClocks.CONTENT)
        val remoteClock = remote.clockOf(FieldClocks.CONTENT)
        if (localClock == remoteClock) return null

        val localBody = local.record.valueOf(FieldClocks.CONTENT)
        val remoteBody = remote.valueOf(FieldClocks.CONTENT)
        if (localBody == remoteBody) return null

        val baseline = local.contentBaseline
        if (baseline != null && (localClock <= baseline || remoteClock <= baseline)) return null

        val loser = if (localClock > remoteClock) remote else local.record
        val loserClock = if (localClock > remoteClock) remoteClock else localClock
        if (isEmptyBody(loser.valueOf(FieldClocks.CONTENT))) return null

        return ConflictCopies.copyOf(loser = loser, loserContentClock = loserClock)
    }

    /**
     * True when a body holds nothing worth keeping.
     *
     * Only the literal empty string, deliberately. The body is HTML and this function does not
     * parse it, so `"<p></p>"` — which renders as nothing — is not recognised here and does get a
     * copy. That is the safe direction: the cost of a copy that turns out to be visually blank is
     * one extra note in Recent, and the cost of a false positive is a body the user typed, gone.
     * Widening this test to anything that requires understanding the markup would trade the second
     * risk for the first, which is the wrong way round.
     */
    private fun isEmptyBody(body: FieldValue): Boolean = body.parts.firstOrNull().isNullOrEmpty()
}
