package my.cheysoff.core_sync_engine.harness

import kotlin.random.Random

/**
 * One operation in a simulated run.
 *
 * The set is deliberately the one `e2e-sync-open-questions.md` §3 proposes — edits, metadata
 * gestures, deletes, restores, pushes, pulls, a crash during a push and a clock skew — because
 * each of them is a thing a real device does and each can break the merge in its own way.
 *
 * Every op is a `data class` with a readable [toString], and a [Schedule] prints as its ops one per
 * line. That is the whole reproducibility story: a failing seed prints the exact sequence that
 * produced it, and a reader can replay it by eye before replaying it in a debugger.
 */
sealed interface Op {

    /** Index into the simulation's replica list. */
    val replica: Int

    /** The editor's save. */
    data class SaveNote(override val replica: Int, val note: Int, val body: String) : Op

    /** Pin or unpin — a metadata gesture that leaves `updatedAt` alone. */
    data class Pin(override val replica: Int, val note: Int, val value: Boolean) : Op

    /** Favourite or unfavourite. Also leaves `updatedAt` alone. */
    data class Favorite(override val replica: Int, val note: Int, val value: Boolean) : Op

    /** File into a folder, or unfile. Also leaves `updatedAt` alone. */
    data class SetFolder(override val replica: Int, val note: Int, val folder: Int?) : Op

    /** Unfile during a folder delete — the one mass edit that bumps `updatedAt`. */
    data class ClearFolder(override val replica: Int, val note: Int) : Op

    /** Move to Trash. Soft, so the body survives. */
    data class DeleteNote(override val replica: Int, val note: Int) : Op

    /** Bring back out of Trash. */
    data class RestoreNote(override val replica: Int, val note: Int) : Op

    /** Create or rename a folder. */
    data class SaveFolder(override val replica: Int, val folder: Int, val label: String) : Op

    /** Trash a folder. */
    data class DeleteFolder(override val replica: Int, val folder: Int) : Op

    /**
     * Draws (or redraws) the one sketch anchored under a note — `RoomSketchesRepository.saveSketch`.
     * One sketch per note slot, keyed off [note] the same way every other note-scoped op is, so two
     * replicas racing to edit "the sketch on note 2" collide exactly the way two replicas racing on
     * its title do.
     */
    data class SaveSketch(override val replica: Int, val note: Int, val strokes: String) : Op

    /** Apply everything the server has that this replica has not seen. */
    data class Pull(override val replica: Int) : Op

    /** Send this replica's dirty rows. */
    data class Push(override val replica: Int) : Op

    /**
     * A push whose `ok` is thrown away after the server committed it — process death between the
     * two. The row stays dirty against a stale `baseSeq`, so the next push takes a `409` carrying
     * this device's own envelope straight back to it.
     */
    data class CrashDuringPush(override val replica: Int) : Op

    /**
     * Move this replica's wall clock, possibly backwards.
     *
     * Backwards is the case worth having: it is a manual change, an NTP correction or a dual-boot,
     * and it is what the HLC's counter exists for. A merge that trusted wall time would start
     * losing writes here.
     */
    data class SkewClock(override val replica: Int, val deltaMs: Long) : Op
}

/**
 * A deterministic list of operations, and the seed that produced it.
 *
 * A property that only holds for the seeds someone happened to try is not a property, so the tests
 * run hundreds of these; and a counterexample that cannot be replayed is a mystery rather than a
 * bug, so [toString] prints the whole thing.
 */
data class Schedule(val seed: Long, val ops: List<Op>) {

    override fun toString(): String = buildString {
        append("Schedule(seed=").append(seed).append(", ").append(ops.size).appendLine(" ops)")
        ops.forEachIndexed { index, op -> append("  ").append(index).append(": ").appendLine(op) }
    }

    companion object {

        /**
         * A random schedule from [seed].
         *
         * The weighting is not uniform and the shape of it is the point. Writes outnumber syncs
         * roughly two to one, so replicas routinely accumulate several unpushed changes before
         * exchanging any — which is what produces genuinely divergent states rather than a
         * ping-pong that could never disagree. `Pull` is drawn slightly more often than `Push` for
         * the same reason the real loop pulls first.
         *
         * [noteCount] is small on purpose. Three replicas contending over four notes produce far
         * more interesting interleavings than the same replicas each editing their own hundred.
         */
        fun random(
            seed: Long,
            replicaCount: Int,
            noteCount: Int = 4,
            folderCount: Int = 2,
            opCount: Int = 60,
        ): Schedule {
            val random = Random(seed)
            val ops = ArrayList<Op>(opCount)
            repeat(opCount) { step ->
                val replica = random.nextInt(replicaCount)
                val note = random.nextInt(noteCount)
                val folder = random.nextInt(folderCount)
                ops += when (random.nextInt(23)) {
                    0, 1, 2, 3 -> Op.SaveNote(replica, note, "body-r$replica-s$step")
                    4, 5 -> Op.Pin(replica, note, random.nextBoolean())
                    6 -> Op.Favorite(replica, note, random.nextBoolean())
                    7 -> Op.SetFolder(replica, note, if (random.nextBoolean()) folder else null)
                    8 -> Op.ClearFolder(replica, note)
                    9 -> Op.DeleteNote(replica, note)
                    10 -> Op.RestoreNote(replica, note)
                    11 -> Op.SaveFolder(replica, folder, "folder-r$replica-s$step")
                    12 -> Op.DeleteFolder(replica, folder)
                    13, 14, 15, 16 -> Op.Pull(replica)
                    17, 18 -> Op.Push(replica)
                    19 -> Op.CrashDuringPush(replica)
                    20, 21 -> Op.SaveSketch(replica, note, "stroke-r$replica-s$step")
                    // Half of these move the clock backwards, which is the case the HLC's counter
                    // exists for and the one a wall-clock ordering would lose writes on.
                    else -> Op.SkewClock(replica, random.nextLong(-500L, 500L))
                }
            }
            return Schedule(seed, ops)
        }

        /**
         * A schedule of writes only, with no sync ops at all.
         *
         * Used by the commutativity property: the writes are fixed, and the *delivery* order is
         * what the test varies. Mixing sync ops into the schedule would make two runs differ in
         * what each replica knew when it wrote, which is a different experiment.
         */
        fun writesOnly(
            seed: Long,
            replicaCount: Int,
            noteCount: Int = 3,
            opCount: Int = 24,
        ): Schedule {
            val random = Random(seed)
            val ops = ArrayList<Op>(opCount)
            repeat(opCount) { step ->
                val replica = random.nextInt(replicaCount)
                val note = random.nextInt(noteCount)
                ops += when (random.nextInt(8)) {
                    0, 1, 2 -> Op.SaveNote(replica, note, "body-r$replica-s$step")
                    3, 4 -> Op.Pin(replica, note, random.nextBoolean())
                    5 -> Op.Favorite(replica, note, random.nextBoolean())
                    6 -> Op.DeleteNote(replica, note)
                    else -> Op.RestoreNote(replica, note)
                }
            }
            return Schedule(seed, ops)
        }
    }
}
