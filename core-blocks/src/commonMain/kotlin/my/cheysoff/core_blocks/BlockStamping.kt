package my.cheysoff.core_blocks

import my.cheysoff.core_domain.sync.Hlc

/**
 * Turns what the editor shows into a [BlockDocument] with per-block clocks, on every save.
 *
 * The editor knows only visible order and content, so the clocks are decided here by comparing
 * with the previously saved document. Stamping only what actually changed is the whole point: a
 * block stamped on every save would win every merge, and two devices editing different paragraphs
 * would clobber each other.
 */
object BlockStamping {

    /**
     * @param prior the last saved document, or null for a note's first BLOCKS save.
     * @param editedBlocks the editor's blocks in visible order. Their `clock`, `posClock` and `pos` are
     *   ignored; only identity and content matter. Duplicate ids are repaired, see below.
     * @param clock the save's clock, stamped on everything that changed.
     */
    fun stamp(prior: BlockDocument?, editedBlocks: List<Block>, clock: Hlc): BlockDocument {
        // A throwing save would lose the user's typing, so a duplicated id is repaired rather than
        // rejected: the first occurrence keeps it, later ones become new blocks.
        val seen = HashSet<String>()
        val edited = editedBlocks.map { if (seen.add(it.id)) it else it.copy(id = BlockIds.random()) }
        val priorById = prior?.blocks.orEmpty().associateBy { it.id }
        val editedIds = edited.map { it.id }.toSet()

        // Only blocks that were live before and are still present can keep their position. A
        // tombstoned id that reappears (undo) is treated as new: its content clock must beat the
        // tombstone anyway, and a fresh position is stamped with it.
        val kept = longestStablePositions(edited, priorById)

        val finalPos = arrayOfNulls<String>(edited.size)
        val result = ArrayList<Block>(edited.size)
        for ((i, b) in edited.withIndex()) {
            val old = priorById[b.id]
            val moved = i !in kept
            if (moved) {
                // Left to right, so the lower neighbour is already final; the upper bound is the
                // next block that keeps its position (every block in between is also moving).
                val lower = if (i > 0) finalPos[i - 1] else null
                val upper = (i + 1 until edited.size).firstOrNull { it in kept }
                    ?.let { priorById.getValue(edited[it].id).pos }
                finalPos[i] = FractionalIndex.between(lower, upper)
            } else {
                finalPos[i] = old!!.pos
            }
            val contentChanged = old == null || !sameContent(old, b)
            result += if (contentChanged) {
                Block(
                    id = b.id, type = b.type, text = b.text, spans = b.spans, done = b.done, ref = b.ref,
                    pos = finalPos[i]!!, clock = clock, posClock = if (moved) clock else old?.posClock ?: clock,
                )
            } else {
                // Start from the prior block so an unchanged one is identical, span order included.
                old.copy(pos = finalPos[i]!!, posClock = if (moved) clock else old.posClock)
            }
        }

        val deleted = priorById.keys.filter { it !in editedIds }.map { Tombstone(it, clock) }
        val carried = prior?.gone.orEmpty().filter { it.id !in editedIds }
        // Sorted by id: BlockDocument.equals compares tombstones as an ordered list.
        return BlockDocument(result, (carried + deleted).sortedBy { it.id })
    }

    /** Spans compare as sets: their order carries no meaning, so reordering alone is not an edit. */
    private fun sameContent(a: Block, b: Block) =
        a.type == b.type && a.text == b.text && a.done == b.done && a.ref == b.ref &&
            a.spans.toSet() == b.spans.toSet()

    /**
     * Indices of [edited] whose prior positions form the longest strictly increasing run, i.e. the
     * largest set of blocks that need not move. Everything else was moved (or is new) and gets a
     * fresh position. Strict, because two blocks sharing a `pos` (concurrent inserts into one gap)
     * leave no room to insert between them, so one of them must move.
     */
    private fun longestStablePositions(edited: List<Block>, priorById: Map<String, Block>): Set<Int> {
        val candidates = edited.indices.filter { priorById[edited[it].id]?.pos?.let(FractionalIndex::isValid) == true }
        fun posAt(c: Int) = priorById.getValue(edited[candidates[c]].id).pos
        // Patience sorting: tails[k] is the candidate ending the best run of length k + 1.
        val tails = ArrayList<Int>()
        val previous = IntArray(candidates.size) { -1 }
        for (c in candidates.indices) {
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (posAt(tails[mid]) < posAt(c)) lo = mid + 1 else hi = mid
            }
            if (lo > 0) previous[c] = tails[lo - 1]
            if (lo == tails.size) tails += c else tails[lo] = c
        }
        val kept = HashSet<Int>()
        var c = tails.lastOrNull() ?: -1
        while (c >= 0) {
            kept += candidates[c]
            c = previous[c]
        }
        return kept
    }
}
