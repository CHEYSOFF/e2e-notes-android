package my.cheysoff.core_blocks

import my.cheysoff.core_domain.sync.Hlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockStampingTest {

    private fun hlc(ms: Long) = Hlc(ms, 0, "n1")

    private val t0 = hlc(100)
    private val t1 = hlc(200)

    private fun para(id: String, text: String, pos: String) =
        Block(id, BlockType.PARAGRAPH, text, pos = pos, clock = t0, posClock = t0)

    private fun prior() = BlockDocument(
        listOf(
            para("a", "one", "a"),
            Block("pic:r1", BlockType.PICTURE, ref = "r1", pos = "b", clock = t0, posClock = t0),
            para("c", "three", "c"),
            para("d", "four", "d"),
        ),
    )

    @Test
    fun `typing in one paragraph stamps only that block`() {
        val prior = prior()
        val edited = prior.blocks.map { if (it.id == "c") it.copy(text = "three!") else it }

        val doc = BlockStamping.stamp(prior, edited, t1)

        assertEquals(listOf("a", "pic:r1", "c", "d"), doc.blocks.map { it.id })
        for (b in doc.blocks) {
            val old = prior.blocks.first { it.id == b.id }
            assertEquals(old.pos, b.pos)
            assertEquals(t0, b.posClock)
            assertEquals(if (b.id == "c") t1 else t0, b.clock, b.id)
        }
        assertEquals("three!", doc.blocks.first { it.id == "c" }.text)
    }

    @Test
    fun `span order alone is not an edit`() {
        val spans = listOf(StyleSpan(0, 1, InlineStyle.BOLD), StyleSpan(1, 2, InlineStyle.ITALIC))
        val prior = BlockDocument(listOf(para("a", "xy", "a").copy(spans = spans)))
        val doc = BlockStamping.stamp(prior, listOf(prior.blocks[0].copy(spans = spans.reversed())), t1)
        assertEquals(t0, doc.blocks[0].clock)
    }

    @Test
    fun `moving a picture stamps only its posClock and pos`() {
        val prior = prior()
        val byId = prior.blocks.associateBy { it.id }
        val edited = listOf("a", "c", "d", "pic:r1").map { byId.getValue(it) }

        val doc = BlockStamping.stamp(prior, edited, t1)

        assertEquals(listOf("a", "c", "d", "pic:r1"), doc.blocks.map { it.id })
        val pic = doc.blocks.last()
        assertTrue(pic.pos > "d", pic.pos)
        assertEquals(t1, pic.posClock)
        assertEquals(t0, pic.clock)
        for (b in doc.blocks.dropLast(1)) {
            assertEquals(byId.getValue(b.id), b, "${b.id} must be untouched")
        }
    }

    @Test
    fun `inserting a block gives it a position between its neighbours`() {
        val prior = prior()
        val fresh = Block("new", BlockType.PARAGRAPH, "hi")
        val edited = listOf(prior.blocks[0], fresh) + prior.blocks.drop(1)

        val doc = BlockStamping.stamp(prior, edited, t1)

        val added = doc.blocks.first { it.id == "new" }
        assertTrue("a" < added.pos && added.pos < "b", added.pos)
        assertEquals(t1, added.clock)
        assertEquals(t1, added.posClock)
        assertEquals(listOf("a", "new", "pic:r1", "c", "d"), doc.blocks.map { it.id })
    }

    @Test
    fun `deleting a block leaves a tombstone at the save clock`() {
        val prior = prior()
        val edited = prior.blocks.filter { it.id != "c" }

        val doc = BlockStamping.stamp(prior, edited, t1)

        assertEquals(listOf("a", "pic:r1", "d"), doc.blocks.map { it.id })
        assertEquals(listOf(Tombstone("c", t1)), doc.gone)
    }

    @Test
    fun `re-adding a tombstoned id revives it with the save clock`() {
        val tomb = Tombstone("c", t0)
        val prior = BlockDocument(listOf(para("a", "one", "a")), listOf(tomb, Tombstone("z", t0)))
        val edited = listOf(prior.blocks[0], Block("c", BlockType.PARAGRAPH, "back"))

        val doc = BlockStamping.stamp(prior, edited, t1)

        val revived = doc.blocks.first { it.id == "c" }
        assertEquals(t1, revived.clock)
        assertEquals(t1, revived.posClock)
        assertTrue(revived.pos > "a")
        assertEquals(listOf(Tombstone("z", t0)), doc.gone)
    }

    @Test
    fun `prior tombstones are kept and sorted by id`() {
        val prior = BlockDocument(listOf(para("a", "one", "a")), listOf(Tombstone("y", t0), Tombstone("b", t0)))
        val doc = BlockStamping.stamp(prior, prior.blocks, t1)
        assertEquals(listOf(Tombstone("b", t0), Tombstone("y", t0)), doc.gone)
    }

    @Test
    fun `first save of a legacy converted doc stamps every block`() {
        val edited = listOf(
            Block("x", BlockType.PARAGRAPH, "one"),
            Block("y", BlockType.H1, "two"),
            Block("z", BlockType.CHECK, "three"),
        )

        val doc = BlockStamping.stamp(null, edited, t1)

        assertEquals(listOf("x", "y", "z"), doc.blocks.map { it.id })
        assertTrue(doc.blocks.all { it.clock == t1 && it.posClock == t1 && it.pos.isNotEmpty() })
        assertEquals(3, doc.blocks.map { it.pos }.toSet().size)
        assertTrue(doc.gone.isEmpty())
    }

    @Test
    fun `an unchanged save reproduces the prior document`() {
        val prior = prior()
        assertEquals(prior, BlockStamping.stamp(prior, prior.blocks, t1))
    }
}
