package my.cheysoff.core_blocks

import my.cheysoff.core_domain.sync.Hlc
import kotlin.random.Random

/**
 * What a block is. [wire] is the value written to JSON as `t` and is part of the sync format:
 * renaming a constant is free, changing a wire string orphans every note already written.
 */
enum class BlockType(val wire: String) {
    PARAGRAPH("p"),
    H1("h1"),
    H2("h2"),
    H3("h3"),
    BULLET("bullet"),
    NUMBER("number"),
    CHECK("check"),
    PICTURE("picture"),
    DRAWING("drawing"),
}

/** An inline style over a run of a block's text. [wire] is the JSON form, with the same caveat as [BlockType.wire]. */
enum class InlineStyle(val wire: String) {
    BOLD("b"),
    ITALIC("i"),
    UNDERLINE("u"),
    STRIKE("s"),
}

/**
 * A styled run of a block's text: [start] inclusive, [end] exclusive, both UTF-16 code-unit
 * offsets. UTF-16 because that is what `String` and Compose text fields index by on both
 * platforms, so the offsets mean the same thing everywhere without conversion -- the price is that
 * an editor must never place a boundary between the halves of a surrogate pair.
 */
data class StyleSpan(val start: Int, val end: Int, val style: InlineStyle)

/**
 * One paragraph, list item, picture or drawing.
 *
 * Two clocks, not one, so that a move and an edit made on different devices both survive:
 * [clock] covers the content (type, text, spans, done, ref) and [posClock] covers [pos] alone.
 * [pos] is a fractional-index string; blocks are ordered by `(pos, id)`.
 */
data class Block(
    val id: String,
    val type: BlockType,
    val text: String = "",
    val spans: List<StyleSpan> = emptyList(),
    val done: Boolean = false,
    /** The attachment or sketch record id, for [BlockType.PICTURE] and [BlockType.DRAWING]. */
    val ref: String? = null,
    val pos: String = "",
    val clock: Hlc = Hlc.ZERO,
    val posClock: Hlc = Hlc.ZERO,
)

/** A deleted block's id and the clock of the delete; a block is live only while its content clock is newer. */
data class Tombstone(val id: String, val clock: Hlc)

/**
 * A note body: live blocks plus tombstones for blocks deleted somewhere.
 *
 * [blocks] is sorted by `(pos, id)` on construction so equal documents are equal values no matter
 * how they were assembled, which is what lets the canonical encoding and `==` agree. Not a data
 * class for that reason: a data class would expose the unsorted input through `copy`.
 */
class BlockDocument(blocks: List<Block>, val gone: List<Tombstone> = emptyList()) {

    val blocks: List<Block> = blocks.sortedWith(compareBy<Block> { it.pos }.thenBy { it.id })

    /** The blocks to show. Tombstoned blocks are not held in [blocks] at all, so this is [blocks]. */
    fun visible(): List<Block> = blocks

    /**
     * Whether [other] reads the same to a person: same blocks in the same order with the same
     * content. Clocks, [Block.pos] strings and [gone] are bookkeeping, and comparing them would
     * make a save that changed nothing visible look like an edit.
     */
    fun sameVisibleContent(other: BlockDocument): Boolean =
        blocks.size == other.blocks.size && blocks.indices.all { i ->
            val a = blocks[i]
            val b = other.blocks[i]
            a.id == b.id && a.type == b.type && a.text == b.text && a.spans == b.spans &&
                a.done == b.done && a.ref == b.ref
        }

    override fun equals(other: Any?): Boolean =
        other is BlockDocument && blocks == other.blocks && gone == other.gone

    override fun hashCode(): Int = 31 * blocks.hashCode() + gone.hashCode()

    override fun toString(): String = "BlockDocument(blocks=$blocks, gone=$gone)"
}

object BlockIds {
    /** A fresh 128-bit random id, hex. Collisions are not a practical concern at this size. */
    fun random(): String =
        Random.Default.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * Picture and drawing blocks take their id from the record they show, so "at most one block
     * per record" holds by construction: two devices that both add the record produce the same id
     * and merge into one block.
     */
    fun forPicture(recordId: String) = "pic:$recordId"

    fun forDrawing(recordId: String) = "drw:$recordId"
}
