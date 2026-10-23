package my.cheysoff.core_blocks

import com.mohamedrejeb.ksoup.entities.KsoupEntities
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.NameUuid

/**
 * Turns a pre-blocks note (PLAIN text, or the HTML richeditor 1.0.0-rc14 and 1.1.0 wrote) into
 * blocks, so every existing note opens in the block editor with nothing lost.
 *
 * Deterministic on purpose: the phone and the desktop each convert the same note when they open it,
 * before either has saved, and the two results must be the same blocks with the same ids or the
 * first merge would see two unrelated documents. So ids come from the note id and the block's index
 * (never random), positions from a fixed sequence, and clocks stay [Hlc.ZERO] -- conversion is not
 * an edit, and stamping happens on the first real save.
 *
 * The HTML reader is a hand-written tolerant scanner rather than a parser library, in the shape of
 * `NoteBlocks.countHtmlBlocks`: bodies come from two library versions and from sync, and a
 * malformed one must degrade to "less structure", never to an exception or to lost text.
 */
object LegacyImport {

    fun toBlocks(noteId: String, content: String, format: NoteContentFormat, checklist: String): List<Block> {
        val drafts = ArrayList<Draft>()
        when (format) {
            // BLOCKS bodies are not this object's input; reading one as text at least loses nothing.
            NoteContentFormat.PLAIN, NoteContentFormat.BLOCKS -> importPlain(content, drafts)
            NoteContentFormat.HTML -> HtmlReader(drafts).read(content)
        }
        for ((text, done) in LegacyChecklist.parse(checklist)) {
            drafts += Draft(BlockType.CHECK, text, emptyList(), done)
        }
        var pos: String? = null
        return drafts.mapIndexed { i, d ->
            val p = FractionalIndex.between(pos, null)
            pos = p
            Block(
                id = NameUuid.v3("$noteId/legacy/$i".encodeToByteArray()),
                type = d.type,
                text = d.text,
                spans = d.spans,
                done = d.done,
                pos = p,
            )
        }
    }

    /** An empty body has no lines at all, rather than one empty line, so an empty note opens empty. */
    private fun importPlain(content: String, out: MutableList<Draft>) {
        if (content.isEmpty()) return
        for (line in content.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            out += Draft(BlockType.PARAGRAPH, line, emptyList(), false)
        }
    }

    private class Draft(val type: BlockType, val text: String, val spans: List<StyleSpan>, val done: Boolean)

    private class HtmlReader(private val out: MutableList<Draft>) {
        private val cur = StringBuilder()
        private var curType = BlockType.PARAGRAPH

        /** Inside an element that owns a block (`p`, `div`, `h1`..`h6`, `li`); text outside one is an implicit paragraph. */
        private var blockOpen = false

        /** Drafts this block has already emitted via `br`, so a trailing `br` does not add an empty line. */
        private var emitted = 0

        private val depth = HashMap<InlineStyle, Int>()
        private val start = HashMap<InlineStyle, Int>()
        private val spans = ArrayList<StyleSpan>()

        /** One entry per open `ul`/`ol`; true when ordered. */
        private val lists = ArrayList<Boolean>()

        fun read(html: String) {
            var i = 0
            while (i < html.length) {
                val lt = html.indexOf('<', i)
                if (lt < 0) {
                    text(html.substring(i))
                    break
                }
                if (lt > i) text(html.substring(i, lt))
                val next = html.getOrNull(lt + 1)
                if (next == null || !(next.isLetter() || next == '/' || next == '!')) {
                    // A bare '<' ("a < b") is text, not a tag.
                    text("<")
                    i = lt + 1
                    continue
                }
                if (html.startsWith("<!--", lt)) {
                    val end = html.indexOf("-->", lt + 4)
                    if (end >= 0) {
                        i = end + 3
                        continue
                    }
                }
                val close = html.indexOf('>', lt + 1)
                val nextLt = html.indexOf('<', lt + 1)
                if (close < 0 || (nextLt in 0 until close)) {
                    // No '>' before the next '<' (or at all): this is not a tag, so the '<' is text.
                    // Dropping it and what follows would lose words ("x<y", "<b then").
                    text("<")
                    i = lt + 1
                    continue
                }
                tag(html.substring(lt + 1, close).trim())
                i = close + 1
            }
            closeBlock()
            flushImplicit()
        }

        private fun tag(inner: String) {
            if (inner.startsWith("!")) return
            val closing = inner.startsWith("/")
            val name = tagName(if (closing) inner.substring(1) else inner)
            if (name.isEmpty()) return
            if (closing) {
                closeTag(name)
                return
            }
            if (name == "br") {
                lineBreak()
                return
            }
            openTag(name)
            if (inner.endsWith("/")) closeTag(name)
        }

        private fun openTag(name: String) {
            when (name) {
                "p", "div" -> openBlock(BlockType.PARAGRAPH)
                "h1" -> openBlock(BlockType.H1)
                "h2" -> openBlock(BlockType.H2)
                "h3", "h4", "h5", "h6" -> openBlock(BlockType.H3)
                "ul" -> { flushImplicit(); lists += false }
                "ol" -> { flushImplicit(); lists += true }
                "li" -> openBlock(if (lists.lastOrNull() == true) BlockType.NUMBER else BlockType.BULLET)
                else -> styleOf(name)?.let { openStyle(it) } // Any other tag: its text is kept, the tag is not.
            }
        }

        private fun closeTag(name: String) {
            when (name) {
                "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li" -> closeBlock()
                "ul", "ol" -> {
                    closeBlock()
                    flushImplicit()
                    if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
                }
                else -> styleOf(name)?.let { closeStyle(it) }
            }
        }

        private fun styleOf(name: String): InlineStyle? = when (name) {
            "b", "strong" -> InlineStyle.BOLD
            "i", "em" -> InlineStyle.ITALIC
            "u" -> InlineStyle.UNDERLINE
            "s", "strike", "del" -> InlineStyle.STRIKE
            else -> null
        }

        private fun openStyle(s: InlineStyle) {
            val d = (depth[s] ?: 0) + 1
            depth[s] = d
            if (d == 1) start[s] = cur.length
        }

        private fun closeStyle(s: InlineStyle) {
            val d = depth[s] ?: 0
            if (d == 0) return
            depth[s] = d - 1
            if (d == 1) emitSpan(s, cur.length)
        }

        private fun emitSpan(s: InlineStyle, end: Int) {
            val from = start[s] ?: return
            if (end > from) spans += StyleSpan(from, end, s)
        }

        private fun text(raw: String) {
            // Line breaks inside HTML text are whitespace, not structure; one inside a block's text
            // would break the one-line-per-block model the editor relies on.
            val t = KsoupEntities.decodeHtml(raw).replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
            if (t.isEmpty()) return
            if (!blockOpen && cur.isEmpty() && t.isBlank()) return // Indentation between elements.
            cur.append(t)
        }

        private fun lineBreak() {
            // Outside a block this ends the implicit paragraph, or is an empty line of its own.
            emit(force = true)
            emitted++
        }

        private fun openBlock(type: BlockType) {
            flushImplicit()
            blockOpen = true
            curType = type
            emitted = 0
        }

        private fun closeBlock() {
            if (!blockOpen) return
            if (cur.isNotEmpty() || emitted == 0) emit(force = true)
            blockOpen = false
            curType = BlockType.PARAGRAPH
            emitted = 0
        }

        /** Text that sat outside any block element becomes its own paragraph. */
        private fun flushImplicit() {
            if (cur.isNotEmpty()) emit(force = false)
        }

        /**
         * Emits the current line. Styles still open are closed at the line's end and reopened at 0
         * on the next, so a run that crosses a `br` is styled on both sides of it.
         */
        private fun emit(force: Boolean) {
            for ((s, d) in depth) if (d > 0) emitSpan(s, cur.length)
            if (force || cur.isNotEmpty()) out += Draft(curType, cur.toString(), normalised(spans), false)
            cur.setLength(0)
            spans.clear()
            for ((s, d) in depth) if (d > 0) start[s] = 0
        }

        /** Touching runs of one style are one run; sorted so equal input gives equal spans. */
        private fun normalised(list: List<StyleSpan>): List<StyleSpan> {
            val merged = ArrayList<StyleSpan>()
            for (s in list.sortedWith(compareBy({ it.style.ordinal }, { it.start }, { it.end }))) {
                val last = merged.lastOrNull()
                if (last != null && last.style == s.style && s.start <= last.end) {
                    merged[merged.lastIndex] = StyleSpan(last.start, maxOf(last.end, s.end), s.style)
                } else {
                    merged += s
                }
            }
            return merged.sortedWith(compareBy({ it.start }, { it.end }, { it.style.ordinal }))
        }

        private fun tagName(raw: String): String {
            val t = raw.trimStart()
            var end = 0
            while (end < t.length && t[end].isLetterOrDigit()) end++
            return t.substring(0, end).lowercase()
        }
    }
}
