package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.ContentMerger
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc

/**
 * Merges two BLOCKS note bodies block by block, so a tick on the phone and typing on the laptop
 * both survive instead of one whole body beating the other.
 *
 * ## The rule, per block id
 *
 * - **Content** (type, text, spans, done, ref, size) comes from the block with the greater content clock `c`.
 * - **Position** comes from the greater position clock `pc`. Candidates are the live blocks *and*
 *   the tombstones, because a tombstone remembers where its block last was.
 * - **Tombstone** clock is the greater of the two.
 * - **Live** iff the winning `c` is greater than the tombstone clock. `pc` plays no part, so a
 *   delete beats a concurrent move, and a later text edit beats a delete.
 * - **Ties.** Equal `c`: compare the canonical JSON of the two blocks with `pos`/`pc` blanked, so
 *   the decision depends on content alone. Equal `pc`: compare the `pos` strings.
 *
 * ## Why it converges
 *
 * Per id the state is three registers: content `(c, content)`, position `(pc, pos)` and tombstone
 * clock. Each merges by `max` over a total order, and `max` is a join. The document is only a
 * compact form of that state:
 * - **Dead content is dropped.** It has `c <= tombstone`. Any content that could ever win against
 *   it must beat the tombstone and so beat it too, so dropping it changes no future result.
 * - **The position is never dropped.** It lives on the block while the block is live and on the
 *   tombstone while it is dead. Dropping it was the bug the regression test
 *   `a revived block keeps the newest move whatever the merge order` pins: a move newer than the
 *   delete was forgotten or kept depending on merge order.
 * - **A live block keeps its tombstone** (position blanked, since the block holds it). A third
 *   document's even older content can then still be judged against it.
 *
 * `BlockMergeConvergenceTest` checks commutativity, associativity and idempotence on real stamped
 * histories rather than trusting this paragraph.
 */
object BlockMerge : ContentMerger {

    private val FORMAT = NoteContentFormat.BLOCKS.storageValue

    /**
     * The merged body, or null unless both sides are BLOCKS bodies this build can decode. A legacy
     * body meeting a BLOCKS one can only be a pre-upgrade edit and keeps last-writer-wins plus a
     * conflict copy. An undecodable body is a newer build's, and merging it would rewrite what this
     * build cannot read.
     */
    override fun merge(local: FieldValue, remote: FieldValue): FieldValue? {
        if (local.parts.getOrNull(1) != FORMAT || remote.parts.getOrNull(1) != FORMAT) return null
        val a = local.parts[0]?.let(BlockCodec::decode) ?: return null
        val b = remote.parts[0]?.let(BlockCodec::decode) ?: return null
        return FieldValue.of(BlockCodec.encode(mergeDocs(a, b)), FORMAT)
    }

    fun mergeDocs(a: BlockDocument, b: BlockDocument): BlockDocument {
        // Grouped lists rather than maps keyed by id: a document holding an id twice (a quirk of
        // another build) then merges like two documents instead of losing one copy depending on
        // which came first.
        val blocksById = (a.blocks + b.blocks).groupBy { it.id }
        val tombsById = (a.gone + b.gone).groupBy { it.id }

        val blocks = ArrayList<Block>()
        val gone = ArrayList<Tombstone>()
        for (id in (blocksById.keys + tombsById.keys).sorted()) {
            val candidates = blocksById[id].orEmpty()
            val tombs = tombsById[id].orEmpty()

            val content = candidates.maxWithOrNull(CONTENT_ORDER)
            val tombClock = tombs.maxOfOrNull { it.clock }
            val position = (candidates.map { Position(it.posClock, it.pos) } + tombs.map { Position(it.posClock, it.pos) })
                .maxWith(POSITION_ORDER)

            val live = content != null && (tombClock == null || content.clock > tombClock)
            if (live) {
                blocks += content!!.copy(pos = position.pos, posClock = position.clock)
                if (tombClock != null) gone += Tombstone(id, tombClock)
            } else {
                // Not live means a tombstone exists: an id comes only from a block or a tombstone,
                // and a block without one is live.
                gone += Tombstone(id, tombClock!!, position.pos, position.clock)
            }
        }
        return BlockDocument(blocks, gone)
    }

    private data class Position(val clock: Hlc, val pos: String)

    private val POSITION_ORDER = compareBy<Position> { it.clock }.thenBy { it.pos }

    /** By content clock; on a tie, by the canonical encoding of the content alone. */
    private val CONTENT_ORDER = Comparator<Block> { x, y ->
        val byClock = x.clock.compareTo(y.clock)
        if (byClock != 0) byClock else contentKey(x).compareTo(contentKey(y))
    }

    private fun contentKey(b: Block): String =
        BlockCodec.encode(BlockDocument(listOf(b.copy(pos = "", posClock = Hlc.ZERO))))
}
