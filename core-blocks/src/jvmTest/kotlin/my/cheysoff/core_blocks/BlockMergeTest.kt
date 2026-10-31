package my.cheysoff.core_blocks

import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.LocalRecord
import my.cheysoff.core_domain.sync.Merge
import my.cheysoff.core_domain.sync.MergeResult
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_domain.sync.SyncValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge rule from the plan's "Decisions", one test per sentence of it. The emergent property
 * (any exchange order converges) is [BlockMergeConvergenceTest]'s job.
 */
class BlockMergeTest {

    private fun hlc(ms: Long, node: String = "base") = Hlc(ms, 0, node)

    private val t0 = hlc(100)

    /** The document both devices start from: a check item and two paragraphs, all stamped at [t0]. */
    private fun base(): BlockDocument = BlockStamping.stamp(
        null,
        listOf(
            Block("chk", BlockType.CHECK, "milk"),
            Block("p1", BlockType.PARAGRAPH, "hello"),
            Block("p2", BlockType.PARAGRAPH, "world"),
        ),
        t0,
    )

    /** One device's save: [edit] the visible blocks of [prior], then stamp at [clock]. */
    private fun edit(prior: BlockDocument, clock: Hlc, edit: (List<Block>) -> List<Block>) =
        BlockStamping.stamp(prior, edit(prior.blocks), clock)

    private fun BlockDocument.block(id: String) = blocks.single { it.id == id }

    @Test
    fun `tick on one side and typing on the other both survive`() {
        val base = base()
        val ticked = edit(base, hlc(200, "A")) { bs -> bs.map { if (it.id == "chk") it.copy(done = true) else it } }
        val typed = edit(base, hlc(210, "B")) { bs -> bs.map { if (it.id == "p1") it.copy(text = "hello there") else it } }

        val merged = BlockMerge.mergeDocs(ticked, typed)

        assertTrue(merged.block("chk").done)
        assertEquals("hello there", merged.block("p1").text)
        assertEquals(merged, BlockMerge.mergeDocs(typed, ticked))
    }

    @Test
    fun `same block edited on both sides takes the newer clock, no conflict copy`() {
        val base = base()
        val older = edit(base, hlc(200, "A")) { bs -> bs.map { if (it.id == "p1") it.copy(text = "from A") else it } }
        val newer = edit(base, hlc(300, "B")) { bs -> bs.map { if (it.id == "p1") it.copy(text = "from B") else it } }
        val localRow = LocalRecord(record = noteRecord(older, contentClock = hlc(200, "A")), dirty = true, contentBaseline = null)
        val remote = noteRecord(newer, contentClock = hlc(300, "B"))

        val result = Merge.merge(localRow, remote, BlockMerge)

        val applied = result as? MergeResult.Applied ?: error("expected Applied, was $result")
        val body = assertNotNull(BlockCodec.decode(applied.record.valueOf(FieldClocks.CONTENT).parts[0]!!))
        assertEquals("from B", body.block("p1").text)
        assertEquals(hlc(300, "B"), applied.record.clockOf(FieldClocks.CONTENT))
    }

    @Test
    fun `delete beats a concurrent move`() {
        val base = base()
        // The move is even the later write: liveness ignores the position clock.
        val moved = edit(base, hlc(300, "A")) { bs -> listOf(bs[2], bs[0], bs[1]) }
        val deleted = edit(base, hlc(200, "B")) { bs -> bs.filter { it.id != "p2" } }

        val merged = BlockMerge.mergeDocs(moved, deleted)

        assertEquals(listOf("chk", "p1"), merged.blocks.map { it.id })
        val tomb = merged.gone.single { it.id == "p2" }
        assertEquals(hlc(200, "B"), tomb.clock)
        // The dead block's newest position is kept, so a later revival elsewhere agrees on it.
        assertEquals(hlc(300, "A"), tomb.posClock)
        assertEquals(moved.block("p2").pos, tomb.pos)
    }

    @Test
    fun `delete loses to a later text edit`() {
        val base = base()
        val deleted = edit(base, hlc(200, "A")) { bs -> bs.filter { it.id != "p2" } }
        val edited = edit(base, hlc(300, "B")) { bs -> bs.map { if (it.id == "p2") it.copy(text = "world!") else it } }

        val merged = BlockMerge.mergeDocs(deleted, edited)

        assertEquals("world!", merged.block("p2").text)
        // The tombstone stays next to the live block, so a merge with a third copy is still a join.
        assertEquals(hlc(200, "A"), merged.gone.single { it.id == "p2" }.clock)
        assertEquals(merged, BlockMerge.mergeDocs(edited, deleted))
    }

    @Test
    fun `two sides inserting the same picture record produce one block`() {
        val base = base()
        val pic = Block(BlockIds.forPicture("r1"), BlockType.PICTURE, ref = "r1")
        val onA = edit(base, hlc(200, "A")) { bs -> bs + pic }
        val onB = edit(base, hlc(210, "B")) { bs -> listOf(pic) + bs }

        val merged = BlockMerge.mergeDocs(onA, onB)

        assertEquals(1, merged.blocks.count { it.ref == "r1" })
        assertEquals(4, merged.blocks.size)
    }

    @Test
    fun `legacy versus blocks falls back to LWW`() {
        val blocks = FieldValue.of(BlockCodec.encode(base()), "blocks")

        assertNull(BlockMerge.merge(FieldValue.of("<p>hi</p>", "html"), blocks))
        assertNull(BlockMerge.merge(blocks, FieldValue.of("hi", "plain")))
        assertNull(BlockMerge.merge(blocks, FieldValue.of("{not json", "blocks")), "an undecodable body is not ours either")
        assertNotNull(BlockMerge.merge(blocks, blocks))
    }

    /**
     * The counterexample that showed tombstones must carry a position: without it, the move on A is
     * forgotten when A first learns of C's delete, and the revived block's position depends on the
     * order the three documents met.
     */
    @Test
    fun `a revived block keeps the newest move whatever the merge order`() {
        val base = base()
        val movedOnA = edit(base, hlc(900, "A")) { bs -> listOf(bs[2], bs[0], bs[1]) }
        val deletedOnC = edit(base, hlc(700, "C")) { bs -> bs.filter { it.id != "p2" } }
        val editedOnB = edit(base, hlc(800, "B")) { bs -> bs.map { if (it.id == "p2") it.copy(text = "world!") else it } }

        val leftFirst = BlockMerge.mergeDocs(BlockMerge.mergeDocs(movedOnA, deletedOnC), editedOnB)
        val rightFirst = BlockMerge.mergeDocs(movedOnA, BlockMerge.mergeDocs(deletedOnC, editedOnB))

        assertEquals(BlockCodec.encode(rightFirst), BlockCodec.encode(leftFirst))
        assertEquals(movedOnA.block("p2").pos, leftFirst.block("p2").pos)
        assertEquals("world!", leftFirst.block("p2").text)
    }

    @Test
    fun `equal content clocks are decided by content alone`() {
        val c = hlc(500, "X")
        val one = BlockDocument(listOf(Block("x", BlockType.PARAGRAPH, "aaa", pos = "z", clock = c, posClock = hlc(1))))
        val two = BlockDocument(listOf(Block("x", BlockType.PARAGRAPH, "bbb", pos = "a", clock = c, posClock = hlc(2))))

        val merged = BlockMerge.mergeDocs(one, two)

        assertEquals("bbb", merged.block("x").text)
        assertEquals("a", merged.block("x").pos)
        assertEquals(merged, BlockMerge.mergeDocs(two, one))
    }

    /** A complete note record holding [doc] as its BLOCKS body, every field at [contentClock]. */
    private fun noteRecord(doc: BlockDocument, contentClock: Hlc): SyncRecord = SyncRecord(
        type = RecordType.NOTE,
        uuid = "n1",
        rowClock = contentClock,
        fieldClocks = RecordType.NOTE.fields.associateWith { if (it == FieldClocks.CONTENT || it == FieldClocks.UPDATED_AT) contentClock else t0 },
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("t"),
            FieldClocks.CONTENT to FieldValue.of(BlockCodec.encode(doc), "blocks"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of(SyncValues.of(false)),
            FieldClocks.FAVORITE to FieldValue.of(SyncValues.of(false)),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of(contentClock.ms.toString()),
            FieldClocks.DELETED to FieldValue.of(SyncValues.of(false), null),
        ),
    ).validate()
}
