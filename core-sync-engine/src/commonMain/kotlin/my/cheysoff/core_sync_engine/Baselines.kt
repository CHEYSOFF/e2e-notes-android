package my.cheysoff.core_sync_engine

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord

/**
 * How `LocalRecord.contentBaseline` moves: the one piece of merge state the engine owns.
 *
 * The merge cannot maintain this itself. A baseline is "the `content` clock of the newest version
 * this device and the server have **agreed on**", and agreement is a transport event — a push the
 * server accepted, or a version the server handed over — which the merge is deliberately never told
 * about. So the rule lives here, next to the loop that knows when an agreement happened.
 */
object Baselines {

    /**
     * The baseline after this device and the server have agreed on [agreed].
     *
     * **Monotonic.** It only ever moves forward, because it marks a point in history below which
     * this device's body is certainly not a new edit — and a baseline that went backwards would
     * un-know an ancestor and start producing conflict copies for edits that were never contested.
     * `max` rather than "the newest wins" is what makes that true when records arrive out of order,
     * which they do on any pass that took a `409`.
     *
     * Returns null for a folder. Folders have no body, so they never conflict-copy and a baseline
     * for one would be a value nothing reads.
     */
    fun advance(previous: Hlc?, agreed: SyncRecord): Hlc? {
        if (agreed.type != RecordType.NOTE) return null
        val seen = agreed.clockOf(FieldClocks.CONTENT)
        return if (previous == null || seen > previous) seen else previous
    }
}
