package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncValues
import my.cheysoff.core_sync_engine.harness.Op
import my.cheysoff.core_sync_engine.harness.Schedule
import my.cheysoff.core_sync_engine.harness.Simulation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The convergence harness: N replicas over a fake transport, driven from seeded schedules.
 *
 * ## Why this file exists
 *
 * Merge correctness cannot be judged by reading. Every failure that matters here — a merge that is
 * not commutative, an apply that is not idempotent, an order dependence that only appears with
 * three replicas, a clock tie two devices break differently — reads as perfectly sensible code and
 * shows up only as two devices quietly holding different states, with neither able to tell which
 * of them is wrong. `e2e-sync-open-questions.md` §3 proposes exactly this simulation and costs it
 * at "days, not weeks **once merge is pure**"; `Merge` is pure, so here it is.
 *
 * ## What a green run does and does not prove
 *
 * It proves things about the *merge*. It proves nothing about real HTTP, TLS, certificate pinning,
 * `429` handling, Room and SQLCipher transaction semantics, the `Room` invalidation race
 * `SingleNoteViewModel` documents, the Android lifecycle, or the crypto. Those are named in §3 as
 * out of reach for a simulation and they belong to `:core-sync-net`, the instrumented tests, and
 * `core-crypto`'s known-answer vectors respectively.
 *
 * ## Seeds
 *
 * A property that only holds for the seeds someone happened to try is not a property, so the
 * sweeps below run hundreds. Every failure prints the seed, the schedule and every replica's final
 * state, so a counterexample replays exactly rather than being a mystery.
 */
class ConvergenceTest {

    // ── Convergence ────────────────────────────────────────────────────────────────────────────

    /**
     * **The headline property.** After the network settles, every replica holds byte-identical
     * state.
     *
     * Three replicas, because two hide bugs that three expose — with two devices almost any merge
     * converges by luck, since there is only one pair to agree about. Field-level LWW breaks at
     * three, where a record can reach a replica by two different routes carrying two different
     * partial merges.
     */
    @Test
    fun everyReplicaConvergesAfterQuiescence() {
        var conflictCopies = 0
        var casConflicts = 0
        forEachSeed(SEEDS) { seed ->
            val simulation = Simulation(seed = seed, replicaCount = 3)
            simulation.run(Schedule.random(seed, replicaCount = 3))
            simulation.quiesce()
            simulation.assertConverged()
            conflictCopies += simulation.conflictCopyCount()
            casConflicts += simulation.casConflictCount()
        }
        // A sweep that never exercised the interesting branches would pass for the wrong reason.
        assertTrue("the sweep never produced a conflict copy", conflictCopies > 0)
        assertTrue("the sweep never produced a rejected push", casConflicts > 0)
    }

    /** Two replicas — the easy case, kept because it is the one users actually have. */
    @Test
    fun twoReplicasConverge() {
        forEachSeed(SEEDS / 2) { seed ->
            val simulation = Simulation(seed = seed, replicaCount = 2)
            simulation.run(Schedule.random(seed, replicaCount = 2))
            simulation.quiesce()
            simulation.assertConverged()
        }
    }

    /** Four, because "three is where it breaks" is a claim worth stressing past its own boundary. */
    @Test
    fun fourReplicasConverge() {
        forEachSeed(SEEDS / 4) { seed ->
            val simulation = Simulation(seed = seed, replicaCount = 4)
            simulation.run(Schedule.random(seed, replicaCount = 4, noteCount = 3, opCount = 80))
            simulation.quiesce()
            simulation.assertConverged()
        }
    }

    /**
     * Convergence still holds with **no `content` baseline**, which is the schema as it stands.
     *
     * Decision D7 is open and the v7 columns carry no per-field synced clock, so a caller reading
     * straight from `notes` has nothing to give the merge and falls back to the conservative rule.
     * That rule writes more conflict copies than it needs to. What it must not do is diverge, and
     * this is where that is checked — separately, because "converges" and "converges without
     * noise" are two claims and only the first one is unconditional.
     */
    @Test
    fun convergenceHoldsWithoutContentBaselines() {
        forEachSeed(SEEDS / 2) { seed ->
            val simulation = Simulation(seed = seed, replicaCount = 3, useBaselines = false)
            simulation.run(Schedule.random(seed, replicaCount = 3))
            simulation.quiesce()
            simulation.assertConverged()
        }
    }

    /**
     * Every replica writing inside **one millisecond**.
     *
     * With the wall clock frozen, `HlcGenerator` falls back to its counter, and three replicas each
     * counting from the same starting point collide on `(ms, counter)` constantly — so the node
     * tie-breaker decides a large fraction of all merges rather than none of them. Real-world
     * testing essentially never reaches this; a coarse clock in a simulation reaches it on every
     * seed.
     *
     * If the tie-break were not deterministic and identical on every device, this is the test that
     * would fail, and it would fail as two replicas each certain the other's write lost.
     */
    @Test
    fun convergenceHoldsWhenEveryReplicaWritesInsideOneMillisecond() {
        forEachSeed(SEEDS / 2) { seed ->
            val simulation = Simulation(seed = seed, replicaCount = 3, clockStepMs = 0L)
            simulation.run(Schedule.random(seed, replicaCount = 3))
            simulation.quiesce()
            simulation.assertConverged()
        }
    }

    // ── Commutativity ──────────────────────────────────────────────────────────────────────────

    /**
     * The same set of writes, delivered in different orders, converges to the same result.
     *
     * The writes are fixed and only the *delivery* order varies — one run visits the replicas
     * forwards during quiescence, the other backwards — so the two runs differ in nothing but who
     * spoke first. A non-commutative merge shows up here as two runs settling on different states,
     * which in the field is two devices settling on different states with no way to tell which is
     * right.
     */
    @Test
    fun theSameWritesDeliveredInDifferentOrdersConvergeToTheSameResult() {
        forEachSeed(SEEDS / 2) { seed ->
            val schedule = Schedule.writesOnly(seed, replicaCount = 3)

            val forwards = Simulation(seed = seed, replicaCount = 3)
            forwards.run(schedule)
            forwards.quiesce()
            forwards.assertConverged()

            val backwards = Simulation(seed = seed, replicaCount = 3)
            backwards.run(schedule)
            // Same writes, opposite delivery order: the replicas exchange records in reverse.
            backwards.quiesceInReverse()
            backwards.assertConverged()

            assertEquals(
                "delivery order changed the outcome\n${forwards.describe()}\n${backwards.describe()}",
                forwards.replicas.first().snapshot(),
                backwards.replicas.first().snapshot(),
            )
        }
    }

    // ── Idempotence ────────────────────────────────────────────────────────────────────────────

    /**
     * Re-delivering every record a replica has already applied changes nothing.
     *
     * `e2e-sync-phase3-plan.md` §3.3 calls this the single most important property in the merge,
     * and it is reached in production by any crash between a merge commit and the cursor write, by
     * every retry after a dropped response, and by the `409` a device takes against its own
     * committed push. Here the whole account is replayed from seq 0 after quiescence.
     */
    @Test
    fun replayingEveryRecordAfterQuiescenceChangesNothing() {
        forEachSeed(SEEDS / 2) { seed ->
            val simulation = Simulation(seed = seed, replicaCount = 3)
            simulation.run(Schedule.random(seed, replicaCount = 3))
            simulation.quiesce()

            val before = simulation.replicas.map { it.snapshot() }
            val noChangeBefore = simulation.noChangeCount()

            simulation.replicas.forEach { it.replayEverything() }

            assertEquals(
                "a replay moved a replica's state\n${simulation.describe()}",
                before,
                simulation.replicas.map { it.snapshot() },
            )
            assertTrue(
                "the replay applied no records at all, so it proved nothing",
                simulation.noChangeCount() > noChangeBefore,
            )
            assertTrue(
                "the replay left rows dirty, so the next pass would push them forever",
                simulation.replicas.none { it.hasDirtyRows() },
            )
        }
    }

    // ── Determinism ────────────────────────────────────────────────────────────────────────────

    /**
     * The same seed twice produces the same run, exactly.
     *
     * A cheap and surprisingly sharp check: any nondeterminism in the merge — a tie broken by
     * iteration order, a set where a list was needed, a hash code leaking into a comparison —
     * makes this fail, and every one of those is a divergence bug on real devices rather than a
     * flaky test.
     */
    @Test
    fun aRunIsFullyDeterminedByItsSeed() {
        forEachSeed(SEEDS / 4) { seed ->
            val first = Simulation(seed = seed, replicaCount = 3)
            first.run(Schedule.random(seed, replicaCount = 3))
            first.quiesce()

            val second = Simulation(seed = seed, replicaCount = 3)
            second.run(Schedule.random(seed, replicaCount = 3))
            second.quiesce()

            assertEquals(first.describe(), second.describe())
        }
    }

    // ── Named scenarios ────────────────────────────────────────────────────────────────────────

    /**
     * Three replicas, each learning of the others in a different order — the case two replicas
     * cannot produce.
     *
     * Three casual metadata gestures on one shared note — a pin, a favourite and a filing — one
     * per device, none of them having seen the others. Every one touches a different field, so all
     * three must survive; and because each replica merges the other two in a different order, an
     * order-dependent merge would leave the three of them holding three different records.
     *
     * The gestures are deliberately the three that leave `updatedAt` alone (PR #32). They are also
     * the ones the editor's save does **not** write, which matters: `upsertNote` writes `isPinned`
     * and `folderId` from the editor's own in-memory note, so a save racing a pin is a different
     * scenario from three independent gestures, and mixing the two would make this test about the
     * app's write path rather than about the merge.
     */
    @Test
    fun threeReplicasThatLearnOfEachOtherInDifferentOrdersStillAgree() {
        val simulation = Simulation(seed = 1L, replicaCount = 3)
        val ops = listOf(
            // A shared starting point everyone agrees on.
            Op.SaveNote(0, 0, "original"),
            Op.Push(0),
            Op.Pull(1),
            Op.Pull(2),
            // Three concurrent writes to three different fields.
            Op.Pin(0, 0, true),
            Op.Favorite(1, 0, true),
            Op.SetFolder(2, 0, 0),
            // Delivered in a rotation, so no two replicas see the same order.
            Op.Push(0), Op.Pull(1), Op.Push(1), Op.Pull(2), Op.Push(2), Op.Pull(0),
        )
        simulation.run(Schedule(seed = 1L, ops = ops))
        simulation.quiesce()
        simulation.assertConverged()

        val note = simulation.replicas.first().snapshot().getValue("note:note-0")
        assertEquals("the shared body is untouched", "original", note.valueOf(FieldClocks.CONTENT).parts.first())
        assertEquals(SyncValues.TRUE, note.valueOf(FieldClocks.PINNED).parts.first())
        assertEquals(SyncValues.TRUE, note.valueOf(FieldClocks.FAVORITE).parts.first())
        assertEquals("folder-0", note.valueOf(FieldClocks.FOLDER).parts.first())
        assertEquals(
            "three writes to three fields must not have produced a conflict copy",
            0,
            simulation.conflictCopyCount(),
        )
    }

    /**
     * A rejected push is merged, not dropped — and the merge preserves the local edit rather than
     * the fetched one.
     *
     * Both replicas edit the same body while offline and both push. The second push takes a `409`
     * carrying the first's version inline, which goes through the same merge call as a pulled
     * record. One body wins the original record and the other becomes a conflict copy; nothing the
     * user typed is gone.
     */
    @Test
    fun aRejectedPushIsMergedAndBothBodiesSurvive() {
        val simulation = Simulation(seed = 2L, replicaCount = 2)
        simulation.run(
            Schedule(
                seed = 2L,
                ops = listOf(
                    Op.SaveNote(0, 0, "shared"),
                    Op.Push(0),
                    Op.Pull(1),
                    // Neither pulls before writing, so both are building on the shared version.
                    Op.SaveNote(0, 0, "typed on r0"),
                    Op.SaveNote(1, 0, "typed on r1"),
                    Op.Push(0),
                    Op.Push(1),   // this one is refused
                ),
            )
        )
        simulation.quiesce()
        simulation.assertConverged()

        assertTrue("the 409 path was never exercised", simulation.casConflictCount() > 0)
        val bodies = simulation.replicas.first().snapshot().values
            .filter { it.type == RecordType.NOTE }
            .map { it.valueOf(FieldClocks.CONTENT).parts.first() }
        assertTrue("r0's body was lost: $bodies", "typed on r0" in bodies)
        assertTrue("r1's body was lost: $bodies", "typed on r1" in bodies)
    }

    /**
     * A push the server committed and the client never heard about costs one extra `409` and
     * nothing else.
     *
     * The conflicting envelope handed back is this device's own, so the merge of a record against
     * itself has to be a no-op — and in particular must not look like a contested body and mint a
     * conflict copy of the note against itself.
     */
    @Test
    fun aPushWhoseAcknowledgementWasLostCostsOneExtraConflictAndNothingElse() {
        val simulation = Simulation(seed = 3L, replicaCount = 2)
        simulation.run(
            Schedule(
                seed = 3L,
                ops = listOf(
                    Op.SaveNote(0, 0, "written once"),
                    Op.CrashDuringPush(0),   // the server committed it; r0 never found out
                    // Push again without pulling first, which is what forces the `409`. A real
                    // pass pulls first and would meet its own record as an ordinary pulled
                    // record instead — that is the point of pulling first — but the conflict path
                    // is the one this test is about, so it is provoked deliberately.
                    Op.Push(0),
                ),
            )
        )
        simulation.quiesce()
        simulation.assertConverged()

        assertTrue("the echo did not produce the 409 it should have", simulation.casConflictCount() > 0)
        assertEquals(
            "a record merged against itself must never look contested",
            0,
            simulation.conflictCopyCount(),
        )
        assertEquals(
            1,
            simulation.replicas.first().snapshot().count { it.value.type == RecordType.NOTE },
        )
    }

    /**
     * A server that has gone backwards is refused, and the engine halts.
     *
     * The rollback guard, end to end: the server is snapshotted, more versions are written and
     * acknowledged, and then the snapshot is restored — a server recovered from a backup, which
     * replays *authentic* older envelopes that verify perfectly. The clean row that already holds
     * a newer version is the only thing that can notice.
     *
     * Halting is the required response (`e2e-sync-phase3-plan.md` §8, F7). Silently accepting the
     * old version, or resetting the cursor, is indistinguishable from "the account is empty" and
     * the next pass would be a mass delete.
     */
    @Test
    fun aServerRestoredFromABackupIsRefusedAndHaltsTheEngine() {
        val simulation = Simulation(seed = 4L, replicaCount = 2)
        val r0 = simulation.replicas[0]
        val r1 = simulation.replicas[1]

        r0.saveNote("note-0", "title", "version one")
        r0.push()
        r1.pull()
        val backup = simulation.server.backup()

        r0.saveNote("note-0", "title", "version two")
        r0.push()
        r1.pull()
        assertEquals("version two", r1.row(RecordType.NOTE, "note-0")!!.record.valueOf(FieldClocks.CONTENT).parts.first())
        assertEquals("r1 must be clean, or the guard would not apply", false, r1.row(RecordType.NOTE, "note-0")!!.dirty)

        // The operator restores yesterday's database. The old envelope is authentic; its tag
        // verifies; nothing about the ciphertext gives it away.
        simulation.server.restore(backup)
        // The cursor is rewound too, so the replica is actually offered the stale record rather
        // than simply seeing nothing new. A real client detects the whole-server case through
        // `409 cursor_ahead_of_server`; this test is about the record-level guard behind it.
        r1.replayEverything()

        assertTrue("the rolled-back record was accepted", r1.halted)
        assertEquals(
            "and the newer version is still what the device holds",
            "version two",
            r1.row(RecordType.NOTE, "note-0")!!.record.valueOf(FieldClocks.CONTENT).parts.first(),
        )
    }

    /**
     * With baselines, a pin does not cost the user a duplicate note; without them, it does.
     *
     * The same schedule run both ways, and the contrast is the honest measurement of what decision
     * D7 is worth. Both converge — that is the point of
     * [convergenceHoldsWithoutContentBaselines] — but only one of them is quiet.
     */
    @Test
    fun aBaselineIsWhatStopsAPinFromCostingADuplicateNote() {
        val ops = listOf(
            Op.SaveNote(0, 0, "shared body"),
            Op.Push(0),
            Op.Pull(1),
            // r0 only pins; its body is still the published one. r1 edits the body.
            Op.Pin(0, 0, true),
            Op.SaveNote(1, 0, "edited on r1"),
            Op.Push(1),
            Op.Pull(0),
        )
        val schedule = Schedule(seed = 5L, ops = ops)

        val withBaselines = Simulation(seed = 5L, replicaCount = 2, useBaselines = true)
        withBaselines.run(schedule)
        withBaselines.quiesce()
        withBaselines.assertConverged()

        val withoutBaselines = Simulation(seed = 5L, replicaCount = 2, useBaselines = false)
        withoutBaselines.run(schedule)
        withoutBaselines.quiesce()
        withoutBaselines.assertConverged()

        assertEquals(
            "a pin is not a contested body when the ancestor is known",
            0,
            withBaselines.conflictCopyCount(),
        )
        assertNotEquals(
            "without a baseline the merge cannot tell, and is conservative — that is decision D7",
            0,
            withoutBaselines.conflictCopyCount(),
        )
    }

    /**
     * A delete that loses to a newer edit is a restore, not a resurrection of a blank note.
     *
     * The resurrection class the architecture doc's Finding 1 is about needs a **hard** delete with
     * no tombstone. Because delete is soft, the deleting device still holds the body, so the note
     * that comes back has its text.
     */
    @Test
    fun aDeleteThatLosesToANewerEditComesBackWithItsBody() {
        val simulation = Simulation(seed = 6L, replicaCount = 2)
        simulation.run(
            Schedule(
                seed = 6L,
                ops = listOf(
                    Op.SaveNote(0, 0, "keep me"),
                    Op.Push(0),
                    Op.Pull(1),
                    Op.DeleteNote(1, 0),   // r1 trashes it
                    Op.RestoreNote(0, 0),  // r0 restores it, later
                    Op.Push(1),
                    Op.Pull(0),
                ),
            )
        )
        simulation.quiesce()
        simulation.assertConverged()

        val note = simulation.replicas.first().snapshot().getValue("note:note-0")
        assertEquals(SyncValues.FALSE, note.valueOf(FieldClocks.DELETED).parts.first())
        assertEquals("the restore brought the body with it", "keep me", note.valueOf(FieldClocks.CONTENT).parts.first())
    }

    private companion object {
        /**
         * How many seeds the sweeps run.
         *
         * The whole file is pure JVM — no Android, no Room, no coroutines — so a run costs
         * microseconds and this number can be a real one rather than a token. That cheapness is
         * the entire argument for keeping `Merge` pure, and it is why raising this number is the
         * right first move when a convergence bug is suspected.
         */
        const val SEEDS = 1_000

        /** Runs [body] for seeds `1..count`, naming the seed in any failure. */
        inline fun forEachSeed(count: Int, body: (Long) -> Unit) {
            for (seed in 1L..count) {
                try {
                    body(seed)
                } catch (failure: Throwable) {
                    throw AssertionError("seed $seed failed: ${failure.message}", failure)
                }
            }
        }
    }
}
