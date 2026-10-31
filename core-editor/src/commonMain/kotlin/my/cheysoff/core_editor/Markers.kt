package my.cheysoff.core_editor

import my.cheysoff.core_blocks.BlockType

/**
 * The characters that stand for a line's block type inside the editor's single text field.
 *
 * The whole note is one `BasicTextField` so selection can run across paragraphs, checkboxes and
 * pictures. A line's type therefore has to live in the text itself, as its first character, where
 * it moves with every edit for free; the editor draws checkboxes, bullets and pictures over these
 * characters. Private-use code points (U+E000-U+F8FF) are used because no real text contains them,
 * which is also why pasted text and loaded block text are stripped of that whole range: a stray
 * one would read back as a fake checkbox or picture.
 *
 * A plain paragraph has no marker. A picture or drawing line is exactly its marker and nothing
 * else.
 */
object Markers {
    const val CHECK_OPEN = '\uE000'
    const val CHECK_DONE = '\uE001'
    const val BULLET = '\uE002'
    const val NUMBER = '\uE003'
    const val PICTURE = '\uE004'
    const val DRAWING = '\uE005'
    const val H1 = '\uE011'
    const val H2 = '\uE012'
    const val H3 = '\uE013'

    /** The marker that starts a line of [type]; `null` for a paragraph, which has none. */
    fun of(type: BlockType, done: Boolean): Char? = when (type) {
        BlockType.PARAGRAPH -> null
        BlockType.H1 -> H1
        BlockType.H2 -> H2
        BlockType.H3 -> H3
        BlockType.BULLET -> BULLET
        BlockType.NUMBER -> NUMBER
        BlockType.CHECK -> if (done) CHECK_DONE else CHECK_OPEN
        BlockType.PICTURE -> PICTURE
        BlockType.DRAWING -> DRAWING
    }

    /**
     * The block type and done flag of [line] (one line of the field, without its `\n`).
     *
     * A picture or drawing marker followed by anything is *not* a picture: the editing rules keep
     * those lines alone, so such a line can only be a transient state mid-edit, and calling it a
     * paragraph means its text is never lost. Its marker is dropped like any other stray
     * private-use character when the line is read back.
     */
    fun typeOf(line: CharSequence): Pair<BlockType, Boolean> {
        if (line.isEmpty()) return BlockType.PARAGRAPH to false
        return when (line[0]) {
            CHECK_OPEN -> BlockType.CHECK to false
            CHECK_DONE -> BlockType.CHECK to true
            BULLET -> BlockType.BULLET to false
            NUMBER -> BlockType.NUMBER to false
            H1 -> BlockType.H1 to false
            H2 -> BlockType.H2 to false
            H3 -> BlockType.H3 to false
            PICTURE -> (if (line.length == 1) BlockType.PICTURE else BlockType.PARAGRAPH) to false
            DRAWING -> (if (line.length == 1) BlockType.DRAWING else BlockType.PARAGRAPH) to false
            else -> BlockType.PARAGRAPH to false
        }
    }

    fun isPrivateUse(c: Char): Boolean = c in '\uE000'..'\uF8FF'

    /** Whether [type] is shown as a whole line drawn over a single marker. */
    internal fun isPictureType(type: BlockType) = type == BlockType.PICTURE || type == BlockType.DRAWING
}
