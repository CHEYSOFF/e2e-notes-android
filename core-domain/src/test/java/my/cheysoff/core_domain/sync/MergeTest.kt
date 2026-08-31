package my.cheysoff.core_domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge rules, one test per rule, each named after the rule it defends.
 *
 * These are the tests a mutation of [Merge] has to fail. `ConvergenceTest` proves the *emergent*
 * properties — that N replicas exchanging records in any order end up in the same place — and this
 * file proves the *stated* ones, the sentences in `docs/design/e2e-sync-phase3-plan.md` §5 and §6
 * that a reader of the design would expect to hold. Both are needed: a merge can converge on the
 * wrong answer perfectly consistently.
 */
class MergeTest {

    // ── Field-level, not row-level ─────────────────────────────────────────────────────────────

    /**
     * The headline rule. Pin on one device, edit the body on the other, keep both.
     *
     * Whole-row last-writer-wins would take the higher row clock and throw the other side's write
     * away entirely — and these two gestures are exactly what people do casually across two
     * devices, which is why the design is field-level in the first place.
     */
    @Test
    fun aRemotePinAndALocalBodyEditBothSurvive() {
        // Local edited the body at clock 20; its pin is still at the shared clock 10.
        val localRecord = note(
            rowClock = hlc(20),
            fieldClocks = mapOf(
                FieldClocks.PINNED to hlc(10),
                FieldClocks.FAVORITE to hlc(10),
                FieldClocks.FOLDER to hlc(10),
                FieldClocks.DELETED to hlc(10),
                FieldClocks.TITLE to hlc(10),
                FieldClocks.CHECKLIST to hlc(10),
            ),
            content = "local body",
            updatedAt = 20L,
        )
        // Remote pinned at clock 30 — a later row clock, but it never touched the body.
        val remote = note(
            rowClock = hlc(30),
            fieldClocks = mapOf(
                FieldClocks.CONTENT to hlc(10),
                FieldClocks.UPDATED_AT to hlc(10),
                FieldClocks.TITLE to hlc(10),
                FieldClocks.CHECKLIST to hlc(10),
                FieldClocks.FAVORITE to hlc(10),
                FieldClocks.FOLDER to hlc(10),
                FieldClocks.DELETED to hlc(10),
            ),
            content = "shared body",
            pinned = true,
            updatedAt = 10L,
        )

        // The baseline says the shared body at clock 10 is what both sides last agreed on, so the
        // remote's body is this device's own ancestor and there is nothing contested here — see
        // [aBaselineStopsAPinFromLookingLikeAContestedBody] for the same distinction in reverse.
        val localRow = local(localRecord, dirty = true, contentBaseline = hlc(10))
        val merged = (Merge.merge(localRow, remote) as MergeResult.Applied).record

        assertEquals("the local body wins its own field", "local body", merged.text(FieldClocks.CONTENT))
        assertEquals("the remote pin wins its own field", SyncValues.TRUE, merged.text(FieldClocks.PINNED))
    }

    /** `content` and `contentFormat` are one value and cannot be taken from different sides. */
    @Test
    fun contentAndContentFormatAlwaysComeFromTheSameSide() {
        val localRecord = note(rowClock = hlc(10), content = "plain body", contentFormat = "plain")
        val remote = note(rowClock = hlc(20), content = "<p>rich</p>", contentFormat = "html")

        val merged = (Merge.merge(local(localRecord), remote) as MergeResult.Applied).record

        assertEquals("<p>rich</p>", merged.text(FieldClocks.CONTENT))
        assertEquals("the format travelled with the body", "html", merged.text2(FieldClocks.CONTENT))
    }

    // ── updatedAt follows content ──────────────────────────────────────────────────────────────

    /**
     * When the remote body wins, its `updatedAt` comes with it.
     *
     * `updatedAt` is what `ORDER BY updatedAt DESC` sorts on and what the user reads as "edited
     * 2h ago". A device that took a body but kept its own edit time would show the same note at a
     * different position from the device that wrote it — forever, and in the one field the user
     * actually looks at.
     */
    @Test
    fun updatedAtFollowsTheWinningContent() {
        val localRecord = note(rowClock = hlc(10), content = "old", updatedAt = 1_000L)
        val remote = note(rowClock = hlc(20), content = "new", updatedAt = 2_000L)

        val merged = (Merge.merge(local(localRecord), remote) as MergeResult.Applied).record

        assertEquals("new", merged.text(FieldClocks.CONTENT))
        assertEquals("2000", merged.text(FieldClocks.UPDATED_AT))
    }

    /**
     * The same rule, in the case that separates "follows content" from "is an ordinary field":
     * a record whose `updatedAt` clock is explicitly *older* than its own `content` clock.
     *
     * Ordinary max-by-own-clock would keep the local `updatedAt` here, because 15 beats 5 — and
     * the note would then be displayed with a time that belongs to a body it no longer holds.
     */
    @Test
    fun updatedAtIsTakenFromTheContentWinnerEvenWhenItsOwnClockIsLower() {
        val localRecord = note(
            rowClock = hlc(15),
            fieldClocks = mapOf(FieldClocks.CONTENT to hlc(10)),
            content = "old",
            updatedAt = 1_000L,
        )
        val remote = note(
            rowClock = hlc(20),
            fieldClocks = mapOf(FieldClocks.UPDATED_AT to hlc(5)),
            content = "new",
            updatedAt = 2_000L,
        )

        val merged = (Merge.merge(local(localRecord), remote) as MergeResult.Applied).record

        assertEquals("new", merged.text(FieldClocks.CONTENT))
        assertEquals(
            "the remote body's own edit time came with it, despite a lower updatedAt clock",
            "2000",
            merged.text(FieldClocks.UPDATED_AT),
        )
    }

    /**
     * And the other direction: `updatedAt` is still a field of its own, so a bump that did not
     * touch the body is not discarded.
     *
     * `clearFolder` is the write that does this — it unfiles a note during a folder delete and
     * bumps `updatedAt` while leaving `content` alone. If the content winner decided `updatedAt`
     * outright, that bump would vanish on the next merge and the two devices would once again
     * disagree about where the note belongs in the list.
     */
    @Test
    fun anUpdatedAtBumpThatDidNotTouchTheBodyIsNotDiscarded() {
        // Local: body edited at 10; then a clearFolder at 30 bumped updatedAt and folderId only.
        val localRecord = note(
            rowClock = hlc(30),
            fieldClocks = mapOf(
                FieldClocks.CONTENT to hlc(10),
                FieldClocks.TITLE to hlc(10),
                FieldClocks.CHECKLIST to hlc(10),
                FieldClocks.PINNED to hlc(10),
                FieldClocks.FAVORITE to hlc(10),
                FieldClocks.DELETED to hlc(10),
            ),
            content = "shared body",
            folder = null,
            updatedAt = 3_000L,
        )
        // Remote: body edited at 20, and its updatedAt went with it.
        val remote = note(
            rowClock = hlc(20),
            fieldClocks = mapOf(
                FieldClocks.TITLE to hlc(10),
                FieldClocks.CHECKLIST to hlc(10),
                FieldClocks.PINNED to hlc(10),
                FieldClocks.FAVORITE to hlc(10),
                FieldClocks.FOLDER to hlc(10),
                FieldClocks.DELETED to hlc(10),
            ),
            content = "remote body",
            folder = "work",
            updatedAt = 2_000L,
        )

        val merged = Merge.merge(local(localRecord, dirty = true), remote).mergedRecord(localRecord)

        assertEquals("the remote body won its field", "remote body", merged.text(FieldClocks.CONTENT))
        assertEquals(
            "but the later clearFolder bump won updatedAt on its own clock",
            "3000",
            merged.text(FieldClocks.UPDATED_AT),
        )
    }

    // ── Deletion is an ordinary field ──────────────────────────────────────────────────────────

    /** A remote delete beats an older local edit, and carries its stamp with it. */
    @Test
    fun aRemoteDeleteBeatsAnOlderLocalEdit() {
        val localRecord = note(rowClock = hlc(10), content = "body")
        val remote = note(rowClock = hlc(20), content = "body", deleted = true, deletedAt = 9_000L)

        val merged = (Merge.merge(local(localRecord), remote) as MergeResult.Applied).record

        assertEquals(SyncValues.TRUE, merged.text(FieldClocks.DELETED))
        assertEquals("the stamp travelled with the flag", "9000", merged.text2(FieldClocks.DELETED))
    }

    /**
     * And loses to a newer one, because it is an ordinary field and nothing else.
     *
     * The delete is *soft*, so the deleting device still holds the body — which is what makes a
     * resurrection here a genuine undelete rather than the blank note the architecture doc's
     * Finding 1 is about.
     */
    @Test
    fun anOlderRemoteDeleteLosesToANewerLocalRestore() {
        // The restore is at clock 30 and is the newest thing this device did.
        val localRecord = note(
            rowClock = hlc(30),
            fieldClocks = mapOf(FieldClocks.CONTENT to hlc(10), FieldClocks.TITLE to hlc(10)),
            content = "body",
            deleted = false,
        )
        // The remote's delete is older (20) even though the record itself is newer (40): its title
        // moved afterwards. The row clock is not what decides a field — that is the whole point.
        val remote = note(
            rowClock = hlc(40),
            fieldClocks = mapOf(
                FieldClocks.CONTENT to hlc(10),
                FieldClocks.CHECKLIST to hlc(10),
                FieldClocks.PINNED to hlc(10),
                FieldClocks.FAVORITE to hlc(10),
                FieldClocks.FOLDER to hlc(10),
                FieldClocks.UPDATED_AT to hlc(10),
                FieldClocks.DELETED to hlc(20),
            ),
            title = "Renamed remotely",
            content = "body",
            deleted = true,
            deletedAt = 9_000L,
        )

        val merged = (Merge.merge(local(localRecord, dirty = true), remote) as MergeResult.Applied).record

        assertEquals("Renamed remotely", merged.text(FieldClocks.TITLE))
        assertEquals(SyncValues.FALSE, merged.text(FieldClocks.DELETED))
        assertEquals("the stamp was cleared with the flag", null, merged.text2(FieldClocks.DELETED))
        assertEquals("and the body is still there, so this is a restore", "body", merged.text(FieldClocks.CONTENT))
    }

    // ── The rollback guard ─────────────────────────────────────────────────────────────────────

    /**
     * A clean row refuses a record older than itself.
     *
     * This is the whole rollback defence at the record level, now that the envelope's associated
     * data has been narrowed and there is no outer clock to compare an inner one against. A clean
     * row is one the server has acknowledged, so its clock can only be ahead of the server's if
     * the server went backwards.
     */
    @Test
    fun aCleanRowRefusesAnOlderRemoteRecord() {
        val localRecord = note(rowClock = hlc(50), content = "current")
        val remote = note(rowClock = hlc(20), content = "restored from a backup")

        val result = Merge.merge(local(localRecord, dirty = false), remote)

        assertEquals(MergeResult.Rejected(RejectReason.ROLLBACK_SUSPECTED), result)
    }

    /**
     * A **dirty** row accepts the same record and merges it field by field.
     *
     * The `dirty` half of the guard is what keeps it from firing on the ordinary case: a lower
     * remote clock on a row this device has edited means "we hold a newer local edit", which is
     * the merge's normal business, not evidence of anything.
     */
    @Test
    fun aDirtyRowAcceptsAnOlderRemoteRecordAsAnOrdinaryMerge() {
        val localRecord = note(rowClock = hlc(50), content = "local edit")
        val remote = note(rowClock = hlc(20), content = "older")

        val result = Merge.merge(local(localRecord, dirty = true), remote)

        assertFalse("a dirty row must not be treated as a rollback", result is MergeResult.Rejected)
        assertEquals(
            "and the newer local body is what the field-by-field merge keeps",
            "local edit",
            result.mergedRecord(localRecord).text(FieldClocks.CONTENT),
        )
    }

    /**
     * The guard's blind spot, written down as a test so it cannot be quietly assumed away.
     *
     * **A record this device has never seen has no local clock to compare against.** An old
     * version of an unknown record is therefore accepted here, and no change to this function can
     * fix that — there is nothing to compare it to. The whole-server case that produces it is
     * caught one level up by `409 cursor_ahead_of_server`; both must halt the engine
     * (`e2e-sync-phase3-plan.md` §8, F7).
     */
    @Test
    fun aRecordThisDeviceHasNeverSeenHasNoRollbackDefence() {
        val ancient = note(rowClock = hlc(1), content = "a version from a restored backup")

        val result = Merge.merge(local = null, remote = ancient)

        assertEquals(
            "documented blind spot: first sight is always accepted",
            MergeResult.Applied(record = ancient, dirty = false),
            result,
        )
    }

    // ── Conflict copies ────────────────────────────────────────────────────────────────────────

    /** A body about to be discarded is written out instead. Nothing the user typed is lost. */
    @Test
    fun aContestedBodyIsPreservedAsAConflictCopy() {
        val localRecord = note(rowClock = hlc(10), title = "Shopping", content = "milk")
        val remote = note(rowClock = hlc(20), title = "Shopping", content = "eggs")

        val result = Merge.merge(local(localRecord, dirty = true), remote) as MergeResult.ConflictCopy

        assertEquals("the higher clock keeps the original record", "eggs", result.record.text(FieldClocks.CONTENT))
        assertEquals("the loser survives as its own note", "milk", result.copy.text(FieldClocks.CONTENT))
        assertEquals("Shopping" + ConflictCopies.CONFLICT_SUFFIX, result.copy.text(FieldClocks.TITLE))
        assertNotEquals("the copy is a new record", localRecord.uuid, result.copy.uuid)
    }

    /**
     * Both devices resolving the same conflict build the **same** copy.
     *
     * This is what makes the design docs' "deduplicate on `(uuid, loserContentHlc)`" rule free.
     * If the copy carried a fresh random uuid — or a device label, or a locally formatted time —
     * the two devices would each publish a different copy and the account would gain two
     * duplicates per conflict instead of one, with no way to fold them together afterwards.
     */
    @Test
    fun bothDevicesBuildTheIdenticalConflictCopy() {
        val a = note(rowClock = hlc(10), title = "Shopping", content = "milk")
        val b = note(rowClock = hlc(20), title = "Shopping", content = "eggs")

        // Device A merges B's record; device B merges A's record. Mirror images of one conflict.
        val onA = Merge.merge(local(a, dirty = true), b) as MergeResult.ConflictCopy
        val onB = Merge.merge(local(b, dirty = true), a) as MergeResult.ConflictCopy

        assertEquals(onA.copy, onB.copy)
        assertEquals("and they agree on the winner too", onA.record, onB.record)
    }

    /**
     * The copy carries the text and none of the metadata.
     *
     * A duplicate note appearing pinned at the top of the list is worse than one appearing in
     * Recent, so the pin, the favourite and the folder are deliberately dropped.
     */
    @Test
    fun aConflictCopyCarriesTheTextAndNotTheMetadata() {
        val localRecord = note(
            rowClock = hlc(10),
            content = "milk",
            checklist = "milk\neggs",
            pinned = true,
            favorite = true,
            folder = "work",
            updatedAt = 1_000L,
        )
        val remote = note(rowClock = hlc(20), content = "eggs")

        val copy = (Merge.merge(local(localRecord, dirty = true), remote) as MergeResult.ConflictCopy).copy

        assertEquals("milk", copy.text(FieldClocks.CONTENT))
        assertEquals("milk\neggs", copy.text(FieldClocks.CHECKLIST))
        assertEquals(SyncValues.FALSE, copy.text(FieldClocks.PINNED))
        assertEquals(SyncValues.FALSE, copy.text(FieldClocks.FAVORITE))
        assertEquals(null, copy.text(FieldClocks.FOLDER))
        assertEquals("the loser's own edit time, so it sorts next to the note it came from", "1000", copy.text(FieldClocks.UPDATED_AT))
        assertEquals("a copy is always created alive", SyncValues.FALSE, copy.text(FieldClocks.DELETED))
    }

    /** A clean row holds nothing unpublished, so nothing it loses needs preserving. */
    @Test
    fun aCleanRowNeverProducesAConflictCopy() {
        val localRecord = note(rowClock = hlc(10), content = "milk")
        val remote = note(rowClock = hlc(20), content = "eggs")

        val result = Merge.merge(local(localRecord, dirty = false), remote)

        assertTrue("a clean row's body is the server's own, so it is an ancestor", result is MergeResult.Applied)
    }

    /** Two devices that typed the same thing have nothing to preserve. */
    @Test
    fun anIdenticalBodyNeverProducesAConflictCopy() {
        val localRecord = note(rowClock = hlc(10), content = "milk")
        val remote = note(rowClock = hlc(20), content = "milk")

        val result = Merge.merge(local(localRecord, dirty = true), remote)

        assertTrue(result is MergeResult.Applied)
    }

    /**
     * With a baseline, a pin does not look like a contested body.
     *
     * This is the case that separates the precise rule from the conservative one. The local row is
     * dirty — the user pinned it — and its body differs from the incoming one, so the conservative
     * rule (`e2e-sync-phase3-plan.md` decision D7) would write a copy of a body that is provably
     * the *ancestor* of the incoming one. The baseline says so, and no copy is written.
     */
    @Test
    fun aBaselineStopsAPinFromLookingLikeAContestedBody() {
        // The body at clock 10 is the one this device last agreed with the server on; the pin at
        // clock 30 is what made the row dirty.
        val localRecord = note(
            rowClock = hlc(30),
            fieldClocks = mapOf(FieldClocks.CONTENT to hlc(10), FieldClocks.UPDATED_AT to hlc(10)),
            content = "shared body",
            pinned = true,
        )
        val remote = note(rowClock = hlc(40), content = "the other device edited the body")

        val withBaseline = Merge.merge(local(localRecord, dirty = true, contentBaseline = hlc(10)), remote)
        val withoutBaseline = Merge.merge(local(localRecord, dirty = true, contentBaseline = null), remote)

        assertTrue("the ancestor is not text to preserve", withBaseline is MergeResult.Applied)
        assertTrue(
            "and without a baseline the merge is conservative instead — safe, but noisy (D7)",
            withoutBaseline is MergeResult.ConflictCopy,
        )
    }

    // ── Idempotence, and the dirty flag ────────────────────────────────────────────────────────

    /**
     * Re-applying an already-applied record changes nothing.
     *
     * The single most important property in the merge. Three ordinary production events re-deliver
     * a record — a crash between the merge commit and the cursor write, a dropped push response,
     * and the `409` a device takes against its own earlier write — and in all three the correct
     * outcome is that nothing happens at all.
     */
    @Test
    fun reapplyingAnAlreadyAppliedRecordIsNoChange() {
        val localRecord = note(rowClock = hlc(10), content = "old")
        val remote = note(rowClock = hlc(20), content = "new")

        val first = Merge.merge(local(localRecord), remote) as MergeResult.Applied
        val second = Merge.merge(local(first.record, dirty = first.dirty), remote)

        assertEquals(MergeResult.NoChange, second)
    }

    /** Merging a record with itself is a no-op, whatever it holds. */
    @Test
    fun mergingARecordWithItselfIsNoChange() {
        val record = note(rowClock = hlc(10), content = "body", pinned = true, favorite = true)

        assertEquals(MergeResult.NoChange, Merge.merge(local(record, dirty = false), record))
    }

    /** The merged row is clean exactly when the remote won outright — the server already has it. */
    @Test
    fun theMergedRowIsCleanWhenTheRemoteWonOutright() {
        val localRecord = note(rowClock = hlc(10), content = "old")
        val remote = note(rowClock = hlc(20), content = "new")

        val result = Merge.merge(local(localRecord), remote) as MergeResult.Applied

        assertFalse("nothing local survived, so there is nothing to push", result.dirty)
    }

    /** And dirty when it did not, because only this device holds the merged version. */
    @Test
    fun theMergedRowIsDirtyWhenALocalFieldSurvived() {
        val localRecord = note(
            rowClock = hlc(30),
            fieldClocks = mapOf(FieldClocks.CONTENT to hlc(10), FieldClocks.UPDATED_AT to hlc(10)),
            content = "shared",
            pinned = true,
        )
        // Newer overall (40), but only because its title moved; everything else is older.
        val remote = note(
            rowClock = hlc(40),
            fieldClocks = mapOf(
                FieldClocks.CONTENT to hlc(10),
                FieldClocks.CHECKLIST to hlc(10),
                FieldClocks.PINNED to hlc(10),
                FieldClocks.FAVORITE to hlc(10),
                FieldClocks.FOLDER to hlc(10),
                FieldClocks.UPDATED_AT to hlc(10),
                FieldClocks.DELETED to hlc(10),
            ),
            content = "shared",
            title = "Remote title",
        )

        val result = Merge.merge(local(localRecord, dirty = true), remote) as MergeResult.Applied

        assertTrue("the local pin is not on the server yet", result.dirty)
        assertEquals(SyncValues.TRUE, result.record.text(FieldClocks.PINNED))
        assertEquals("Remote title", result.record.text(FieldClocks.TITLE))
    }

    /**
     * The dropped-push-response case: the data does not move, but `dirty` has to clear.
     *
     * `e2e-sync-phase3-plan.md` §3.3 — the server committed the push and the response never
     * arrived, so the next pass takes a `409` carrying this device's own envelope back. The record
     * is byte-identical, so nothing is written to its columns; but if the merge reported
     * `NoChange` here the row would stay dirty and be pushed again, forever.
     */
    @Test
    fun aRecordThatCameBackAsItsOwnEchoClearsDirtyWithoutChangingTheData() {
        val record = note(rowClock = hlc(20), content = "body")

        val result = Merge.merge(local(record, dirty = true), record) as MergeResult.Applied

        assertEquals("no column moved", record, result.record)
        assertFalse("but the server demonstrably has it now", result.dirty)
    }

    // ── HLC ties ───────────────────────────────────────────────────────────────────────────────

    /**
     * Two devices writing in the same millisecond are separated by the node, and both devices
     * reach the same answer.
     *
     * Without a deterministic tiebreak this is the failure that never shows up in manual testing
     * and never goes away: two replicas each conclude the other's write lost, and neither is
     * "wrong". A random schedule with a coarse clock hits it constantly, which is why the harness
     * runs one.
     */
    @Test
    fun anHlcTieIsBrokenByNodeIdenticallyOnBothDevices() {
        val fromA = note(rowClock = Hlc(100, 0, "aaaa"), content = "written on A")
        val fromB = note(rowClock = Hlc(100, 0, "bbbb"), content = "written on B")

        val onA = Merge.merge(local(fromA, dirty = true), fromB)
        val onB = Merge.merge(local(fromB, dirty = true), fromA)

        val bodyOnA = (onA as MergeResult.ConflictCopy).record.text(FieldClocks.CONTENT)
        val bodyOnB = (onB as MergeResult.ConflictCopy).record.text(FieldClocks.CONTENT)
        assertEquals("both devices must agree on who won", bodyOnA, bodyOnB)
        assertEquals("and the greater node is the winner", "written on B", bodyOnA)
        assertEquals("and neither device loses the other's text", onA.copy, onB.copy)
    }

    /**
     * The unreachable case, pinned anyway: identical clocks with different values.
     *
     * It should not happen — one clock reading is one write on one device — but if it ever does,
     * the two devices must still agree, so the tie falls through to a total order over the values
     * themselves rather than to whichever side the code checked first.
     */
    @Test
    fun identicalClocksWithDifferentValuesStillResolveIdentically() {
        val clock = Hlc(100, 0, "aaaa")
        val one = note(rowClock = clock, title = "alpha")
        val other = note(rowClock = clock, title = "beta")

        val oneWay = Merge.merge(local(one, dirty = true), other)
        val otherWay = Merge.merge(local(other, dirty = true), one)

        val titleOne = oneWay.mergedRecord(one).text(FieldClocks.TITLE)
        val titleOther = otherWay.mergedRecord(other).text(FieldClocks.TITLE)
        assertEquals("beta", titleOne)
        assertEquals(titleOne, titleOther)
    }

    // ── Guards ─────────────────────────────────────────────────────────────────────────────────

    /** Two different records must never be merged into each other. */
    @Test
    fun mergingTwoDifferentRecordsIsRejected() {
        val result = Merge.merge(local(note(uuid = "n1")), note(uuid = "n2"))

        assertEquals(MergeResult.Rejected(RejectReason.IDENTITY_MISMATCH), result)
    }

    /** Folders merge by the same rules, and never produce conflict copies — they have no body. */
    @Test
    fun aFolderMergesFieldByFieldAndNeverConflictCopies() {
        val localRecord = folder(rowClock = hlc(30), name = "Work", colorArgb = 1L)
        val remote = folder(
            rowClock = hlc(40),
            fieldClocks = mapOf(FieldClocks.NAME to hlc(10)),
            name = "Old name",
            colorArgb = 2L,
        )

        val result = Merge.merge(local(localRecord, dirty = true), remote) as MergeResult.Applied

        assertEquals("the newer name won", "Work", result.record.text(FieldClocks.NAME))
        assertEquals("the newer colour won", "2", result.record.text(FieldClocks.COLOR))
    }

    /** The merge is commutative on the pair, which is the unit-level form of convergence. */
    @Test
    fun mergingIsCommutativeOnAPair() {
        val a = note(
            rowClock = hlc(30),
            fieldClocks = mapOf(FieldClocks.CONTENT to hlc(30), FieldClocks.PINNED to hlc(5)),
            content = "a body",
            title = "A",
            updatedAt = 30L,
        )
        val b = note(
            rowClock = hlc(40),
            fieldClocks = mapOf(FieldClocks.CONTENT to hlc(5), FieldClocks.TITLE to hlc(5)),
            content = "shared",
            pinned = true,
            updatedAt = 5L,
        )

        val ab = (Merge.merge(local(a, dirty = true), b) as MergeResult.ConflictCopy)
        val ba = (Merge.merge(local(b, dirty = true), a) as MergeResult.ConflictCopy)

        assertEquals(ab.record, ba.record)
        assertEquals(ab.copy, ba.copy)
    }
}
