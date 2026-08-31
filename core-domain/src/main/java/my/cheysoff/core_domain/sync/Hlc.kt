package my.cheysoff.core_domain.sync

/**
 * One reading of a hybrid logical clock: a wall-clock millisecond, a logical counter that breaks
 * ties inside that millisecond, and the node that minted it.
 *
 * ## Why not just a timestamp
 *
 * Sync orders two devices' writes against each other, and `System.currentTimeMillis()` cannot do
 * that job alone. It is user-settable (the same property `LockoutPolicy.remainingMillis` and
 * `TrashPolicy.isExpired` already defend against), it has millisecond granularity so two writes
 * can genuinely tie, and two devices' clocks are never exactly equal. An HLC keeps the physical
 * part — so the ordering still means roughly "later in real time", which is what a human expects
 * of a note — while [counter] and [node] make it a *total* order that no clock change can invert.
 * [HlcGenerator] is what guarantees the monotonicity; this class is only the reading.
 *
 * ## Ordering
 *
 * `(ms, counter, node)` compared in that order, exactly as the merge rule needs: newest physical
 * time wins, then the higher counter, then — for two devices that wrote in the same millisecond
 * with the same counter — the lexicographically greater node. That last step is arbitrary but it
 * is *deterministic and identical on every device*, which is the only property that matters: it
 * is what stops two replicas from each deciding that the other's write lost.
 *
 * ## [node] is plaintext to the sync server
 *
 * The row clock travels **outside** the encrypted envelope, because a client has to read it to
 * order records before it can decrypt them. Everything else about a note is opaque to the
 * operator; this string is not. It must therefore never be a device identifier — see `HlcNode`
 * in `core-crypto`, which is the only place a node is minted and which documents the derivation
 * and the threat in full.
 *
 * ## Wire form
 *
 * [toString] is `"$ms-$counter-$node"`, the canonical form the record envelope's associated data
 * is built from. [parse] is its exact inverse. The two are pinned together by `HlcTest`; a
 * disagreement between them would produce records that decrypt on the device that wrote them and
 * nowhere else.
 */
data class Hlc(
    /** Physical component: milliseconds since the epoch, as observed by the minting device. */
    val ms: Long,
    /** Logical component: how many clocks this node has already issued for [ms]. */
    val counter: Int,
    /**
     * The minting node's per-account pseudonym, or `""` on a device that has no account key yet.
     *
     * Empty is a legitimate value, not a missing one: a device that has never paired has no ARK
     * to derive a pseudonym from, and inventing a local identifier would hand the operator a
     * device-specific string the moment those rows were first pushed. See `HlcNode`.
     */
    val node: String,
) : Comparable<Hlc> {

    init {
        require(ms >= 0L) { "Hlc.ms must not be negative, was $ms" }
        require(counter >= 0) { "Hlc.counter must not be negative, was $counter" }
        require(!node.contains(SEPARATOR)) { "Hlc.node must not contain '$SEPARATOR', was '$node'" }
    }

    /**
     * `(ms, counter, node)` lexicographically.
     *
     * The node comparison is a plain [String.compareTo] — code-point order, no locale, no
     * collator. A locale-sensitive comparison would be a divergence bug that appears only on
     * devices set to certain languages, which is close to the worst failure mode this file could
     * have. Node strings are hex (see `HlcNode`), so code-point order is also the obvious one.
     */
    override fun compareTo(other: Hlc): Int {
        val byMs = ms.compareTo(other.ms)
        if (byMs != 0) return byMs
        val byCounter = counter.compareTo(other.counter)
        if (byCounter != 0) return byCounter
        return node.compareTo(other.node)
    }

    /** The canonical wire form, `"$ms-$counter-$node"`. Parsed back by [parse]. */
    override fun toString(): String = "$ms$SEPARATOR$counter$SEPARATOR$node"

    companion object {
        /**
         * Field separator in the wire form.
         *
         * `-` is safe on both sides of the split: [ms] and [counter] are non-negative so neither
         * ever contains one, and the node alphabet is hex. The [init] block enforces the node half
         * of that, so a future node encoding cannot silently break [parse].
         */
        const val SEPARATOR = "-"

        /**
         * The zero clock: what a row carries before anything has stamped it, and what the
         * `hlcMs = 0, hlcCounter = 0, hlcNode = ''` column defaults installed by MIGRATION_6_7
         * read back as.
         *
         * It compares below every real clock, which is the direction that keeps a migrated row
         * from beating a genuine remote edit it knows nothing about.
         */
        val ZERO = Hlc(ms = 0L, counter = 0, node = "")

        /**
         * Parses [text] in the [toString] form, or returns null if it is not one.
         *
         * Null rather than an exception, and null on every malformed shape, because the inputs are
         * a database column and a server response: both can hold something this build did not
         * write, and neither is worth crashing over. The caller decides what an unreadable clock
         * means — for a stored row it means "treat as unstamped", for a remote record it means
         * "refuse the record".
         *
         * Exactly the strings [toString] can produce are accepted, and nothing else — in
         * particular a third field containing a further separator is rejected rather than kept
         * whole, so that [parse] can never build an [Hlc] whose own constructor would refuse it.
         * `parse(x.toString()) == x` for every constructible `x`, and that is the property the
         * envelope's associated data rests on.
         */
        fun parse(text: String): Hlc? {
            val firstDash = text.indexOf(SEPARATOR)
            if (firstDash <= 0) return null
            val secondDash = text.indexOf(SEPARATOR, firstDash + 1)
            if (secondDash < 0) return null
            val ms = text.substring(0, firstDash).toLongOrNull() ?: return null
            val counter = text.substring(firstDash + 1, secondDash).toIntOrNull() ?: return null
            if (ms < 0L || counter < 0) return null
            val node = text.substring(secondDash + 1)
            if (node.contains(SEPARATOR)) return null
            return Hlc(ms = ms, counter = counter, node = node)
        }
    }
}
