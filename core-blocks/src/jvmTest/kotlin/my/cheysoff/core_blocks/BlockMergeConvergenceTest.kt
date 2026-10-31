package my.cheysoff.core_blocks

import my.cheysoff.core_domain.sync.Hlc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The block merge's version of `ConvergenceTest`: seeded random histories over three replicas that
 * edit one note body and exchange it in random order. Every edit goes through [BlockStamping] with
 * the replica's own clock, so the documents merged here are the ones a device would really write.
 *
 * Two claims, checked separately because either can hold without the other:
 * - after the replicas have gossiped to quiescence they hold byte-identical encodings;
 * - [BlockMerge.mergeDocs] is commutative, associative and idempotent on documents sampled from
 *   those histories. That is what makes the first claim true for every exchange order, not just
 *   the ones this test happened to try.
 */
class BlockMergeConvergenceTest {

    private class Replica(val node: String, var doc: BlockDocument) {
        var ms = 0L

        /** Like an HLC: past everything this replica has seen. */
        fun tick(): Hlc {
            val seen = (doc.blocks.flatMap { listOf(it.clock, it.posClock) } + doc.gone.flatMap { listOf(it.clock, it.posClock) })
                .maxOfOrNull { it.ms } ?: 0L
            ms = maxOf(ms, seen) + 1
            return Hlc(ms, 0, node)
        }
    }

    @Test
    fun `random histories converge and the merge is a join`() {
        val rng = Random(20261024)
        repeat(HISTORIES) { history ->
            val base = BlockStamping.stamp(
                null,
                (0 until 4).map { Block("b$it", BlockType.PARAGRAPH, "t$it") },
                Hlc(1, 0, "base"),
            )
            val replicas = listOf("A", "B", "C").map { Replica(it, base) }
            val seen = ArrayList<BlockDocument>()
            seen += base

            repeat(STEPS) {
                val r = replicas[rng.nextInt(replicas.size)]
                if (rng.nextInt(3) == 0) {
                    val from = replicas[rng.nextInt(replicas.size)]
                    r.doc = BlockMerge.mergeDocs(r.doc, from.doc)
                } else {
                    r.doc = BlockStamping.stamp(r.doc, randomEdit(rng, r.doc), r.tick())
                }
                seen += r.doc
            }

            // Gossip to quiescence: two full rounds suffice for three replicas, a third proves it.
            repeat(3) { for (r in replicas) for (o in replicas) r.doc = BlockMerge.mergeDocs(r.doc, o.doc) }
            val encodings = replicas.map { BlockCodec.encode(it.doc) }.toSet()
            assertEquals(1, encodings.size, "history $history diverged: $encodings")

            repeat(SAMPLED_TRIPLES) {
                val a = seen[rng.nextInt(seen.size)]
                val b = seen[rng.nextInt(seen.size)]
                val c = seen[rng.nextInt(seen.size)]
                fun m(x: BlockDocument, y: BlockDocument) = BlockMerge.mergeDocs(x, y)
                fun e(x: BlockDocument) = BlockCodec.encode(x)
                assertEquals(e(a), e(m(a, a)), "history $history: not idempotent on a written document")
                assertEquals(e(m(a, b)), e(m(b, a)), "history $history: not commutative")
                assertEquals(e(m(m(a, b), c)), e(m(a, m(b, c))), "history $history: not associative")
                val ab = m(a, b)
                assertEquals(e(ab), e(m(ab, ab)), "history $history: not idempotent")
                assertEquals(e(ab), e(m(ab, a)), "history $history: does not absorb an input")
            }
        }
    }

    /** One local edit: the editor's visible blocks after the user did something. */
    private fun randomEdit(rng: Random, doc: BlockDocument): List<Block> {
        val bs = doc.blocks.toMutableList()
        when (rng.nextInt(7)) {
            0 -> if (bs.isNotEmpty()) { // type
                val i = rng.nextInt(bs.size)
                bs[i] = bs[i].copy(text = bs[i].text + "abc"[rng.nextInt(3)])
            }
            1 -> if (bs.isNotEmpty()) { // tick or retype
                val i = rng.nextInt(bs.size)
                bs[i] = bs[i].copy(type = BlockType.CHECK, done = !bs[i].done)
            }
            2 -> if (bs.size > 1) { // move
                val b = bs.removeAt(rng.nextInt(bs.size))
                bs.add(rng.nextInt(bs.size + 1), b)
            }
            3 -> if (bs.isNotEmpty()) bs.removeAt(rng.nextInt(bs.size)) // delete
            4 -> bs.add(rng.nextInt(bs.size + 1), Block("n${rng.nextInt(1_000_000)}", BlockType.PARAGRAPH, "new"))
            5 -> { // insert a picture; few record ids, so two replicas often add the same one
                val ref = "r${rng.nextInt(3)}"
                val id = BlockIds.forPicture(ref)
                if (bs.none { it.id == id }) bs.add(rng.nextInt(bs.size + 1), Block(id, BlockType.PICTURE, ref = ref))
            }
            6 -> { // undo a delete: a tombstoned id comes back
                val dead = doc.gone.filter { t -> bs.none { it.id == t.id } }
                if (dead.isNotEmpty()) {
                    bs.add(rng.nextInt(bs.size + 1), Block(dead[rng.nextInt(dead.size)].id, BlockType.PARAGRAPH, "back"))
                }
            }
        }
        return bs
    }

    private companion object {
        const val HISTORIES = 500
        const val STEPS = 30
        const val SAMPLED_TRIPLES = 20
    }
}
