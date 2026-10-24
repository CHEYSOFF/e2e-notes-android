package my.cheysoff.core_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockIds
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_blocks.StyleSpan

/**
 * A [BlockDocument] projected into one text field, one line per block, with every line's block id
 * kept stable through edits.
 *
 * Why ids matter: saves and merges work per block id, so if typing in a paragraph gave it a new id
 * the merge would see "deleted one block, added another" and a concurrent edit to that paragraph
 * on another device would be lost. The text field knows nothing about blocks, so the ids live
 * here, beside it, in [ids], and every edit must be reported through [onEdited] (or made with
 * [edit], which does that). An edit made straight through [state] leaves [ids] out of step with the
 * lines; [blocks] then fails loudly instead of saving blocks under the wrong ids.
 *
 * What loading does to content, on purpose:
 * - Private-use characters (U+E000-U+F8FF) are stripped from block text. They are this editor's
 *   [Markers], so a legacy note that happens to contain one would otherwise read back with a fake
 *   checkbox or picture. Such a note is visibly altered by one invisible-ish glyph; accepted.
 * - A `\n` inside block text (a corrupt-body fallback, or legacy text) splits the block into
 *   several lines rather than being dropped. The first line keeps the block's id and type; the rest
 *   are paragraphs with fresh ids. Spans are re-based onto each line.
 * - Spans are clamped to the text, widened so they never split a surrogate pair, and empty ones
 *   dropped. Decoding does not validate spans, so this is where bad ones stop.
 * - An id seen twice keeps only its first line: a second text block gets a fresh id, a second
 *   picture of the same record is dropped (it is the same picture). Duplicate ids would otherwise
 *   be silently renamed when the doc is stamped on save, desyncing editor and document.
 * - An empty document becomes one empty paragraph with a fresh id, so there is a line to type in.
 */
class EditorModel(initial: BlockDocument) {

    val state = TextFieldState()

    /** The block id of each line of [state], in order. Never holds the same id twice. */
    var ids: List<String> = emptyList()
        private set

    /** Picture and drawing lines currently shown: block id to record id. */
    val refs: Map<String, String>
        get() = ids.mapNotNull { id -> pictures[id]?.ref?.let { id to it } }.toMap()

    /**
     * Every picture/drawing id this model has known, shown or not. Kept after a picture leaves the
     * text so that pasting it back (or undoing its delete) restores the same block and record.
     */
    private val pictures = HashMap<String, PictureInfo>()

    /**
     * Picture ids that left the text, the next to be reused first. A marker line that appears
     * through an edit takes the first entry of its type: that is how a cut-and-pasted picture
     * keeps its id, because the field only sees an anonymous marker char arriving.
     */
    private val detached = ArrayDeque<String>()

    /** The blocks as loaded, so [blocks] can return unchanged ones untouched, `pos` and clocks included. */
    private val loaded = HashMap<String, Block>()

    init {
        load(initial)
    }

    /**
     * Replaces the text, styles and ids with [doc]. For the initial open only: it throws away the
     * cursor and undo history and forgets detached pictures. Live remote changes are applied as
     * targeted edits instead.
     */
    @OptIn(ExperimentalFoundationApi::class) // undoState: cleared so the built-in undo cannot step back past the load
    fun load(doc: BlockDocument) {
        pictures.clear()
        detached.clear()
        loaded.clear()
        val lineIds = mutableListOf<String>()
        val text = StringBuilder()
        val styles = mutableListOf<PlacedStyle>()
        val seen = HashSet<String>()
        fun startLine() {
            if (lineIds.isNotEmpty()) text.append('\n')
        }
        for (b in doc.visible()) {
            if (Markers.isPictureType(b.type)) {
                if (!seen.add(b.id)) continue
                startLine()
                text.append(Markers.of(b.type, false)!!)
                lineIds += b.id
                pictures[b.id] = PictureInfo(b.type, b.ref)
                loaded[b.id] = b
                continue
            }
            splitText(b.text, b.spans).forEachIndexed { i, piece ->
                val first = i == 0
                val id = if (first && seen.add(b.id)) b.id.also { loaded[it] = b } else BlockIds.random()
                startLine()
                if (first) Markers.of(b.type, b.done)?.let { text.append(it) }
                val base = text.length
                text.append(piece.text)
                placeStyles(piece.spans, base, styles)
                lineIds += id
            }
        }
        if (lineIds.isEmpty()) lineIds += BlockIds.random()
        state.edit {
            getSpanStyles(TextRange(0, length)).forEach { removeStyle(it) }
            getParagraphStyles(TextRange(0, length)).forEach { removeStyle(it) }
            replace(0, length, text)
            styles.forEach { addStyle(it.style, it.start, it.end) }
            selection = TextRange(0)
        }
        state.undoState.clearHistory()
        ids = lineIds
    }

    /**
     * The blocks shown, in line order, read back from [state]: line text without its marker or
     * any other private-use character, spans from the field's styles. A block that reads the same
     * as when it was loaded is returned as the loaded value, so a save that changed nothing
     * visible does not look like an edit. Spans always come back in [normalizeSpans] form, so a
     * loaded block with out-of-range or unordered spans comes back repaired (and counts as changed
     * once).
     */
    fun blocks(): List<Block> {
        val text = state.text
        val lines = lineRanges(text)
        check(lines.size == ids.size) {
            "EditorModel out of sync: ${lines.size} lines, ${ids.size} ids. Was the field edited without onEdited?"
        }
        return lines.mapIndexed { i, line ->
            val id = ids[i]
            val lineText = text.subSequence(line.start, line.end)
            val (type, done) = Markers.typeOf(lineText)
            val read = if (Markers.isPictureType(type)) {
                Block(id, type, ref = pictures[id]?.ref)
            } else {
                val (plain, index) = stripPrivateUse(lineText)
                val spans = state.textStyles.getSpanStyles(TextRange(line.start, line.end)).flatMap { r ->
                    val a = r.start.coerceIn(line.start, line.end) - line.start
                    val b = r.end.coerceIn(line.start, line.end) - line.start
                    if (a >= b) emptyList() else stylesOf(r.item).map { StyleSpan(index[a], index[b], it) }
                }
                Block(id, type, plain, normalizeSpans(plain, spans), done)
            }
            carryOver(read)
        }
    }

    private fun carryOver(read: Block): Block {
        val old = loaded[read.id] ?: return read
        val same = old.type == read.type && old.text == read.text && old.done == read.done &&
            old.ref == read.ref && old.spans == read.spans
        return if (same) old else old.copy(type = read.type, text = read.text, spans = read.spans, done = read.done, ref = read.ref)
    }

    /**
     * Makes a programmatic edit and keeps [ids] in step. `TextFieldState.edit` does not run the
     * field's InputTransformation (ruling R3), so editor commands and remote apply edit through
     * here rather than through [state] directly.
     */
    fun edit(block: TextFieldBuffer.() -> Unit) = state.edit {
        block()
        onEdited(this)
    }

    /**
     * Re-derives [ids] after [buffer]'s changes, from its original text, its change list and its
     * current text. Call it at the end of the field's InputTransformation, after any rule has
     * adjusted the buffer, and from nothing else that [edit] already covers.
     *
     * - Lines outside every changed range keep their ids.
     * - Within a changed range, the first resulting text line keeps the id of the first text line
     *   the change touched; further lines get fresh ids. So typing keeps an id, Enter keeps it on
     *   the first half, and joining two lines keeps the first one's.
     * - A picture/drawing line whose marker char is untouched keeps its id wherever the text
     *   around it moves it. A marker that is deleted parks its id; a marker that is inserted takes
     *   the most recently parked id of its type (see [stagePicture] for a new one).
     *
     * Adapted from the planned `onEdited(changes, before)`: the original text is needed to know
     * where the old lines were, and the buffer carries it alongside the changes, so it is passed
     * whole. The ids before the edit are this model's own [ids].
     */
    @OptIn(ExperimentalFoundationApi::class) // TextFieldBuffer.changes
    fun onEdited(buffer: TextFieldBuffer) {
        val list = buffer.changes
        if (list.changeCount == 0) return
        val changes = (0 until list.changeCount).map {
            val o = list.getOriginalRange(it)
            val r = list.getRange(it)
            Change(o.min, o.max, r.min, r.max)
        }.sortedBy { it.oStart }
        rederive(buffer.originalText, buffer.asCharSequence(), changes)
    }

    /**
     * Gives the next marker line of [type] that an edit inserts the id [id] and record [ref].
     * The insert-picture command calls this just before inserting the marker, since the field
     * itself cannot carry which record a marker char stands for.
     */
    fun stagePicture(id: String, type: BlockType, ref: String?) {
        require(Markers.isPictureType(type)) { "not a picture type: $type" }
        require(id !in ids) { "picture $id is already shown" }
        detached.remove(id)
        detached.addFirst(id)
        pictures[id] = PictureInfo(type, ref)
    }

    private fun rederive(old: CharSequence, new: CharSequence, changes: List<Change>) {
        val oldLines = lineRanges(old)
        check(oldLines.size == ids.size) {
            "EditorModel out of sync before an edit: ${oldLines.size} lines, ${ids.size} ids"
        }
        val newLines = lineRanges(new)
        // The last line starting at or before offset.
        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = oldLines.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (oldLines[mid].start <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }

        // Group changes into runs of old lines they touch. A change [a, b) touches the lines
        // holding a and b, so deleting a line break touches both lines it joined.
        val regions = mutableListOf<Region>()
        for (c in changes) {
            val first = lineOf(c.oStart)
            val last = lineOf(c.oEnd)
            val prev = regions.lastOrNull()
            if (prev != null && first <= prev.last) {
                prev.last = maxOf(prev.last, last)
                prev.delta += c.delta
            } else {
                regions += Region(first, last, c.delta)
            }
        }

        // Pictures in touched lines: is their marker char deleted, or does it survive (and where)?
        val leaving = mutableListOf<Pair<Int, String>>() // old line, id
        val survivors = LinkedHashMap<Int, Pair<Int, String>>() // new offset -> old line, id
        for (r in regions) for (k in r.first..r.last) {
            val id = ids[k]
            if (pictures[id] == null || pictureTypeOf(old, oldLines[k]) == null) continue
            val at = oldLines[k].start
            if (changes.any { it.oStart <= at && at < it.oEnd }) {
                leaving += k to id
            } else {
                survivors[at + changes.filter { it.oEnd <= at }.sumOf { it.delta }] = k to id
            }
        }

        val out = ArrayList<String>(newLines.size)
        val pending = mutableListOf<Pair<Int, BlockType>>() // index in out, marker type
        var oldLine = 0
        var shift = 0
        for (r in regions) {
            while (oldLine < r.first) out += ids[oldLine++]
            val end = oldLines[r.last].end + shift + r.delta
            var textId: String? = (r.first..r.last).map { ids[it] }.firstOrNull { pictures[it] == null }
            while (true) {
                val line = newLines[out.size]
                val pictureType = pictureTypeOf(new, line)
                if (pictureType != null) {
                    val survivor = survivors.remove(line.start)
                    if (survivor != null) {
                        out += survivor.second
                    } else {
                        pending += out.size to pictureType
                        out += ""
                    }
                } else {
                    out += textId ?: BlockIds.random()
                    textId = null
                }
                if (line.end >= end) break
            }
            oldLine = r.last + 1
            shift += r.delta
        }
        while (oldLine < ids.size) out += ids[oldLine++]
        check(out.size == newLines.size) { "id re-derivation produced ${out.size} ids for ${newLines.size} lines" }

        // A surviving marker that is no longer alone on its line has left as a picture too.
        leaving += survivors.values
        detached.addAll(0, leaving.sortedBy { it.first }.map { it.second })
        for ((index, type) in pending) {
            val reused = detached.firstOrNull { pictures[it]?.type == type }
            out[index] = if (reused != null) {
                detached.remove(reused)
                reused
            } else {
                // A marker nobody staged: keep it a picture so nothing is lost, with no record.
                BlockIds.random().also { pictures[it] = PictureInfo(type, null) }
            }
        }

        // Belt and braces: the rules above cannot produce a duplicate, but one would desync the
        // editor from the saved doc, so never let it through.
        val seen = HashSet<String>()
        for (i in out.indices) if (!seen.add(out[i])) out[i] = BlockIds.random()
        ids = out
    }
}

private class PictureInfo(val type: BlockType, val ref: String?)

private class Change(val oStart: Int, val oEnd: Int, val nStart: Int, val nEnd: Int) {
    val delta get() = (nEnd - nStart) - (oEnd - oStart)
}

private class Region(val first: Int, var last: Int, var delta: Int)

/** A line of the field: [start] inclusive, [end] exclusive, not counting its `\n`. */
private class Line(val start: Int, val end: Int)

private class Piece(val text: String, val spans: List<StyleSpan>)

private class PlacedStyle(val style: SpanStyle, val start: Int, val end: Int)

private fun lineRanges(text: CharSequence): List<Line> {
    val out = mutableListOf<Line>()
    var start = 0
    for (i in text.indices) if (text[i] == '\n') {
        out += Line(start, i)
        start = i + 1
    }
    out += Line(start, text.length)
    return out
}

/** The marker type if [line] is a picture/drawing line (exactly one marker char), else null. */
private fun pictureTypeOf(text: CharSequence, line: Line): BlockType? {
    if (line.end - line.start != 1) return null
    return when (text[line.start]) {
        Markers.PICTURE -> BlockType.PICTURE
        Markers.DRAWING -> BlockType.DRAWING
        else -> null
    }
}

/**
 * [text] without private-use characters, and for each offset of [text] (0..length) the matching
 * offset in the result, so spans can be carried across the removal.
 */
private fun stripPrivateUse(text: CharSequence): Pair<String, IntArray> {
    val index = IntArray(text.length + 1)
    val kept = StringBuilder(text.length)
    for (i in text.indices) {
        index[i] = kept.length
        if (!Markers.isPrivateUse(text[i])) kept.append(text[i])
    }
    index[text.length] = kept.length
    return kept.toString() to index
}

/** Block text cleaned for the field: private-use chars stripped, split at `\n`, spans re-based per line. */
private fun splitText(text: String, spans: List<StyleSpan>): List<Piece> {
    val (kept, index) = stripPrivateUse(text)
    val n = text.length
    val mapped = spans.mapNotNull { s ->
        val a = s.start.coerceIn(0, n)
        val b = s.end.coerceIn(0, n)
        if (a >= b) null else StyleSpan(index[a], index[b], s.style)
    }
    return lineRanges(kept).map { line ->
        val lineText = kept.substring(line.start, line.end)
        val lineSpans = mapped.mapNotNull { s ->
            val a = maxOf(s.start, line.start) - line.start
            val b = minOf(s.end, line.end) - line.start
            if (a < b) StyleSpan(a, b, s.style) else null
        }
        Piece(lineText, normalizeSpans(lineText, lineSpans))
    }
}

/**
 * One canonical form for a block's spans: clamped to [text], widened so no boundary falls between
 * the halves of a surrogate pair, overlapping or touching runs of a style merged, empty ones
 * dropped, ordered by start then style. Both loading and reading back use it, which is what makes
 * "unchanged" comparable.
 */
internal fun normalizeSpans(text: CharSequence, spans: List<StyleSpan>): List<StyleSpan> {
    val n = text.length
    fun splitsPair(i: Int) = i in 1 until n && text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()
    val out = mutableListOf<StyleSpan>()
    for (style in InlineStyle.entries) {
        val runs = spans.filter { it.style == style }.mapNotNull { s ->
            var a = s.start.coerceIn(0, n)
            var b = s.end.coerceIn(0, n)
            if (splitsPair(a)) a--
            if (splitsPair(b)) b++
            if (a < b) a to b else null
        }.sortedBy { it.first }
        var cur: Pair<Int, Int>? = null
        for (run in runs) {
            val c = cur
            cur = if (c != null && run.first <= c.second) {
                c.first to maxOf(c.second, run.second)
            } else {
                c?.let { out += StyleSpan(it.first, it.second, style) }
                run
            }
        }
        cur?.let { out += StyleSpan(it.first, it.second, style) }
    }
    return out.sortedWith(compareBy<StyleSpan> { it.start }.thenBy { it.style.ordinal })
}

private val BoldStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val ItalicStyle = SpanStyle(fontStyle = FontStyle.Italic)

/**
 * Adds [spans] (offsets into a line's text) to [out] as field styles starting at [base].
 *
 * Underline and strike are cut into segments with one combined decoration each, because a
 * span's `textDecoration` replaces an earlier one rather than adding to it: two overlapping spans
 * would show only one of the lines.
 */
private fun placeStyles(spans: List<StyleSpan>, base: Int, out: MutableList<PlacedStyle>) {
    for (s in spans) when (s.style) {
        InlineStyle.BOLD -> out += PlacedStyle(BoldStyle, base + s.start, base + s.end)
        InlineStyle.ITALIC -> out += PlacedStyle(ItalicStyle, base + s.start, base + s.end)
        else -> Unit
    }
    val deco = spans.filter { it.style == InlineStyle.UNDERLINE || it.style == InlineStyle.STRIKE }
    val cuts = deco.flatMap { listOf(it.start, it.end) }.distinct().sorted()
    for (i in 0 until cuts.size - 1) {
        val a = cuts[i]
        val b = cuts[i + 1]
        fun has(style: InlineStyle) = deco.any { it.style == style && it.start <= a && it.end >= b }
        val lines = listOfNotNull(
            TextDecoration.Underline.takeIf { has(InlineStyle.UNDERLINE) },
            TextDecoration.LineThrough.takeIf { has(InlineStyle.STRIKE) },
        )
        if (lines.isNotEmpty()) {
            out += PlacedStyle(SpanStyle(textDecoration = TextDecoration.combine(lines)), base + a, base + b)
        }
    }
}

/** The inline styles a field span stands for. Anything else it carries (colour, size) is not saved. */
private fun stylesOf(style: SpanStyle): List<InlineStyle> = buildList {
    val weight = style.fontWeight
    if (weight != null && weight >= FontWeight.Bold) add(InlineStyle.BOLD)
    if (style.fontStyle == FontStyle.Italic) add(InlineStyle.ITALIC)
    val deco = style.textDecoration
    if (deco != null && deco.contains(TextDecoration.Underline)) add(InlineStyle.UNDERLINE)
    if (deco != null && deco.contains(TextDecoration.LineThrough)) add(InlineStyle.STRIKE)
}
