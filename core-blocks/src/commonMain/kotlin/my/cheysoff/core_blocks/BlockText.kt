package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat

/**
 * Plain-text readings of a block document, for everything that wants words rather than structure:
 * list snippets, search, "Copy text", share, and the empty-note checks.
 *
 * The editor marks a line's type with a private-use character (U+E000-U+F8FF) in its buffer, so
 * anything that leaks buffer text would put those markers on the clipboard. Block text itself can
 * also hold such characters, carried over from legacy content. [plain] strips them from the text
 * it emits and is the one place that decision lives; callers never filter again.
 */
object BlockText {

    private fun String.withoutPrivateUse(): String =
        if (none { it in ''..'' }) this else filterNot { it in ''..'' }

    /**
     * The blocks as readable lines joined by `\n`. Check items get a ballot box, bullets a dot and
     * numbered items "N. " counted per consecutive run, so a paragraph (or a picture) in between
     * restarts the count, as it does on screen. Pictures and drawings have no text and are omitted
     * entirely rather than leaving a blank line that was never typed.
     */
    fun plain(blocks: List<Block>): String {
        val lines = ArrayList<String>(blocks.size)
        var run = 0
        for (block in blocks) {
            if (block.type == BlockType.PICTURE || block.type == BlockType.DRAWING) {
                run = 0
                continue
            }
            val text = block.text.withoutPrivateUse()
            if (block.type == BlockType.NUMBER) run++ else run = 0
            lines += when (block.type) {
                BlockType.CHECK -> (if (block.done) "☑ " else "☐ ") + text
                BlockType.BULLET -> "• $text"
                BlockType.NUMBER -> "$run. $text"
                else -> text
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * True when the note shows nothing: no picture or drawing and every text blank. A picture-only
     * note is not empty, or discarding "blank" notes would delete someone's photo.
     */
    fun isEmpty(blocks: List<Block>): Boolean = blocks.all {
        it.type != BlockType.PICTURE && it.type != BlockType.DRAWING && it.text.isBlank()
    }

    /**
     * The plain text of a stored note body, or null when [format] is not BLOCKS so the caller keeps
     * its own PLAIN/HTML path. A body that does not decode comes back as its raw text, as PLAIN
     * would show it, minus private-use characters: a reader must never crash on, or silently hide,
     * a body it cannot parse, and its text may be copied or shared.
     */
    fun bodyPlain(content: String, format: NoteContentFormat): String? {
        if (format != NoteContentFormat.BLOCKS) return null
        val doc = BlockCodec.decode(content) ?: return content.withoutPrivateUse().trim()
        return plain(doc.visible()).trim()
    }

    /**
     * Whether a stored body is empty, or null for non-BLOCKS formats. The encoded JSON of an empty
     * document is never a blank string, so `content.isBlank()` cannot be used for BLOCKS. An
     * undecodable body counts as empty only if its raw text is blank.
     */
    fun bodyIsBlank(content: String, format: NoteContentFormat): Boolean? {
        if (format != NoteContentFormat.BLOCKS) return null
        val doc = BlockCodec.decode(content) ?: return content.isBlank()
        return isEmpty(doc.visible())
    }
}
