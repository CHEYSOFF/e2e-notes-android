package my.cheysoff.core_data.data.sync

import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.HlcGenerator

/**
 * One instant, in both of the clocks a write has to record.
 *
 * They are taken together and handed around together on purpose. `updatedAt` and the HLC answer
 * different questions — "when did the user last edit this" versus "where does this write sit in
 * the account's history" — but they describe *the same write*, and two separate calls to two
 * separate clocks would eventually describe different ones. That is the failure the injection seam
 * exists to make impossible.
 */
data class SyncStamp(
    /**
     * The wall clock, for `updatedAt`/`deletedAt` and nothing else.
     *
     * Raw `System.currentTimeMillis()`, unsmoothed. It is user-visible time and it should read as
     * the device's own idea of the time, wrong or not; `TrashPolicy` already handles a stamp that
     * turns out to be nonsense. Ordering is not its job.
     */
    val wallMs: Long,
    /** The hybrid logical clock, for the row clock and the field clocks. Never goes backwards. */
    val hlc: Hlc,
)

/**
 * The one place a write gets its clocks — the seam that replaced the bare
 * `System.currentTimeMillis()` calls inside `RoomNotesRepository`.
 *
 * Thin on purpose: it owns a single [HlcGenerator] and reads the wall clock. The monotonicity
 * argument lives in [HlcGenerator], where it is pure and unit-tested; what this class adds is
 * (a) the pairing of the two clocks into one [SyncStamp] and (b) somewhere for Hilt to inject a
 * `@Singleton` so that every write in the process mints from the same generator. Two generators
 * would each keep their own counter and could issue the same clock twice, which is the one thing
 * the whole design cannot tolerate.
 *
 * @param node supplies the per-account node pseudonym — in production
 *   `SecureUnlockManager::hlcNode`, which is `""` while locked or before this device has an
 *   account key. See `HlcNode` for why it must not be anything device-identifying.
 * @param wallClock the wall clock, injectable so tests can drive a hostile one.
 */
class SyncClock(
    node: () -> String,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val generator = HlcGenerator(node)

    /** The clocks for one write. Every call returns an [SyncStamp.hlc] strictly greater than the last. */
    fun next(): SyncStamp {
        val wallMs = wallClock()
        return SyncStamp(wallMs = wallMs, hlc = generator.next(wallMs))
    }

    /**
     * Folds a clock this process did not mint into the generator's state.
     *
     * Two callers: the repository's session seed (the highest clock already in the database, so a
     * restart under a rewound device clock cannot re-issue clocks below it) and, later, the merge
     * engine for every clock arriving from another device.
     */
    fun observe(seen: Hlc) = generator.observe(seen)
}
