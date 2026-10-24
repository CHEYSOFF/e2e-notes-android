package my.cheysoff.core_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import my.cheysoff.core_domain.sync.NameUuid

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
 * Main thread only. [ids] and the picture table are snapshot state, so they are observable and
 * roll back with a discarded snapshot together with the text, but nothing here is synchronised.
 *
 * What loading does to content, on purpose:
 * - Private-use characters (U+E000-U+F8FF) are stripped from block text. They are this editor's
 *   [Markers], so a legacy note that happens to contain one would otherwise read back with a fake
 *   checkbox or picture. Such a note is visibly altered by one invisible-ish glyph; accepted.
 * - A `\n` inside block text (a corrupt-body fallback, or legacy text) splits the block into
 *   several lines rather than being dropped. The first line keeps the block's id and type; the rest
 *   are paragraphs. Spans are re-based onto each line.
 * - Spans are clamped to the text, widened so they never split a surrogate pair, and empty ones
 *   dropped. Decoding does not validate spans, so this is where bad ones stop.
 * - An id seen twice keeps only its first line: a second text block gets a new id, a second
 *   picture of the same record is dropped (it is the same picture). Duplicate ids would otherwise
 *   be silently renamed when the doc is stamped on save, desyncing editor and document.
 * - An empty document becomes one empty paragraph, so there is a line to type in.
 *
 * Every id minted on load is name-based ([NameUuid.v3]), not random: two devices opening the same
 * body must produce the same blocks, or each would save its own copy of the split lines and the
 * merge would show both.
 */
class EditorModel(initial: BlockDocument) {

    val state = TextFieldState()

    /** The block id of each line of [state], in order. Never holds the same id twice. */
    var ids: List<String> by mutableStateOf(emptyList())
        private set

    /** Picture and drawing lines currently shown: block id to record id. */
    val refs: Map<String, String>
        get() = pictures.mapNotNull { (id, info) -> info.ref?.let { id to it } }.toMap()

    /** The picture/drawing lines currently shown, by block id. A line id is a picture iff it is here. */
    private var pictures: Map<String, PictureInfo> by mutableStateOf(emptyMap())

    /** Ids for the text lines the next edit creates, in order; see [stageText]. */
    private val stagedText = ArrayDeque<String>()

    /** Ids for the picture/drawing markers the next edit inserts; see [stagePicture]. */
    private val stagedPictures = ArrayDeque<Pair<String, PictureInfo>>()

    /** The blocks as loaded, so [blocks] can return unchanged ones untouched, `pos` and clocks included. */
    private val loaded = HashMap<String, Block>()

    /**
     * The undo history attached to this model, if any. [EditingRules] and [EditorCommands] find it
     * here to report each local change, so a command or a field set up without passing the
     * history along cannot silently skip recording. [EditorModel.edit] itself never records: remote
     * apply and undo/redo edit through it too, and neither may become an undo entry.
     */
    internal var history: BlockHistory? = null

    init {
        load(initial)
    }

    /**
     * Replaces the text, styles and ids with [doc]. For the initial open only: it throws away the
     * cursor and undo history. Live remote changes are applied as targeted edits instead.
     */
    @OptIn(ExperimentalFoundationApi::class) // undoState: cleared so the built-in undo cannot step back past the load
    fun load(doc: BlockDocument) {
        history?.clear()
        stagedText.clear()
        stagedPictures.clear()
        loaded.clear()
        val lineIds = mutableListOf<String>()
        val shown = LinkedHashMap<String, PictureInfo>()
        val text = StringBuilder()
        val styles = mutableListOf<PlacedStyle>()
        for (line in project(doc)) {
            val b = line.block
            if (lineIds.isNotEmpty()) text.append('\n')
            if (Markers.isPictureType(b.type)) shown[b.id] = PictureInfo(b.type, b.ref)
            val content = lineContent(b)
            text.append(content)
            placeStyles(b.spans, text.length - b.text.length, styles)
            lineIds += b.id
            line.source?.let { loaded[b.id] = it }
        }
        state.edit {
            getSpanStyles(TextRange(0, length)).forEach { removeStyle(it) }
            getParagraphStyles(TextRange(0, length)).forEach { removeStyle(it) }
            replace(0, length, text)
            styles.forEach { addStyle(it.style, it.start, it.end) }
            selection = TextRange(0)
        }
        state.undoState.clearHistory()
        pictures = shown
        ids = lineIds
    }

    /**
     * The editor lines [doc] loads as, each as the [Block] that [blocks] would read back from it,
     * with every normalisation of loading applied (see the class doc): private-use characters
     * stripped, text split at `\n` under name-based ids, spans normalised, duplicate ids resolved,
     * `done` kept only on checklist items, an empty document as one empty paragraph.
     *
     * [load] builds the field from this, and [RemoteApply] diffs incoming documents with it, so a
     * synced block reaches the field exactly as it would on opening the note, and a block that
     * reads the same is recognised as unchanged.
     */
    internal fun project(doc: BlockDocument): List<ProjectedLine> {
        val out = mutableListOf<ProjectedLine>()
        val seen = HashSet<String>()
        val dupCount = HashMap<String, Int>()
        for (b in doc.visible()) {
            if (Markers.isPictureType(b.type)) {
                if (!seen.add(b.id)) continue
                out += ProjectedLine(Block(b.id, b.type, ref = b.ref), b, b.id)
                continue
            }
            val first = seen.add(b.id)
            val blockId = if (first) {
                b.id
            } else {
                val n = (dupCount[b.id] ?: 0) + 1
                dupCount[b.id] = n
                nameId("${b.id}/dup/$n").also { seen += it }
            }
            splitText(b.text, b.spans).forEachIndexed { i, piece ->
                val line = if (i == 0) {
                    Block(blockId, b.type, piece.text, piece.spans, done = b.type == BlockType.CHECK && b.done)
                } else {
                    Block(nameId("$blockId/line/$i").also { seen += it }, BlockType.PARAGRAPH, piece.text, piece.spans)
                }
                out += ProjectedLine(line, if (i == 0 && first) b else null, b.id)
            }
        }
        if (out.isEmpty()) out += ProjectedLine(Block(nameId("empty-document"), BlockType.PARAGRAPH), null, null)
        return out
    }

    /**
     * The blocks shown, in line order, read back from [state]: line text without its marker or
     * any other private-use character, spans from the field's styles. A block that reads the same
     * as when it was loaded is returned as the loaded value, so a save that changed nothing
     * visible does not look like an edit. Spans always come back in [normalizeSpans] form, so a
     * loaded block with out-of-range or unordered spans comes back repaired (and counts as changed
     * once).
     *
     * A lone marker line that is not a known picture (a stray marker char left alone by an edit)
     * reads as an empty paragraph: there is no record to show, and inventing a picture without one
     * would save a broken block.
     */
    fun blocks(): List<Block> = read(state.text) { range ->
        state.textStyles.getSpanStyles(range).map { PlacedStyle(it.item, it.start, it.end) }
    }

    /**
     * [blocks] as [buffer] holds them, for the moment between [onEdited] and the commit of a user
     * edit, when [ids] already describe the buffer but [state] still shows the old text. The undo
     * history needs the "after" of an edit right there, inside the InputTransformation.
     */
    internal fun blocksOf(buffer: TextFieldBuffer): List<Block> = read(buffer.asCharSequence()) { range ->
        with(buffer) { getSpanStyles(range).map { PlacedStyle(it.spanStyle, it.textRange.min, it.textRange.max) } }
    }

    private fun read(text: CharSequence, spanStyles: (TextRange) -> List<PlacedStyle>): List<Block> {
        val lines = lineRanges(text)
        check(lines.size == ids.size) {
            "EditorModel out of sync: ${lines.size} lines, ${ids.size} ids. Was the field edited without onEdited?"
        }
        val shown = pictures
        return lines.mapIndexed { i, line ->
            val id = ids[i]
            val lineText = text.subSequence(line.start, line.end)
            val (type, done) = Markers.typeOf(lineText)
            val picture = shown[id]
            val read = if (picture != null && Markers.isPictureType(type)) {
                Block(id, picture.type, ref = picture.ref)
            } else {
                val (plain, index) = stripPrivateUse(lineText)
                val spans = spanStyles(TextRange(line.start, line.end)).flatMap { r ->
                    val a = r.start.coerceIn(line.start, line.end) - line.start
                    val b = r.end.coerceIn(line.start, line.end) - line.start
                    if (a >= b) emptyList() else stylesOf(r.style).map { StyleSpan(index[a], index[b], it) }
                }
                val textType = if (Markers.isPictureType(type)) BlockType.PARAGRAPH else type
                Block(id, textType, plain, normalizeSpans(plain, spans), done)
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
     * here rather than through [state] directly. Stage ids for the lines the edit creates with
     * [stageText] / [stagePicture] first, exactly as many as the edit creates: a marker with no
     * id, or a staged id left unused, means the caller's idea of the edit and the edit differ, and
     * throws [IllegalStateException] after reverting the edit, so text and ids never disagree.
     *
     * The field's own undo history is cleared afterwards. `TextFieldState.edit` records a platform
     * undo entry, and replaying one would change the text without [onEdited] (see [BlockHistory]).
     */
    @OptIn(ExperimentalFoundationApi::class) // undoState
    fun edit(block: TextFieldBuffer.() -> Unit) {
        state.edit {
            block()
            onEdited(this, fromUser = false)
        }
        state.undoState.clearHistory()
    }

    /**
     * The next edit's newly created text lines take [id], in staging order, instead of a fresh
     * random id. For undo re-inserting a deleted block (Task 11) and remote apply inserting a
     * remote block (Task 12). A created line is one that inherits no old line's id: a line
     * inserted whole, or the second half of a split.
     *
     * Staging lasts for one edit, so a stale id can never land on a line the user creates later.
     * A programmatic edit that leaves a staged id unused throws (see [edit]).
     *
     * @throws IllegalArgumentException if [id] is already shown or already staged.
     */
    fun stageText(id: String) {
        requireFree(id)
        stagedText.addLast(id)
    }

    /**
     * The next edit's inserted marker of [type] takes [id] and shows record [ref]. The field
     * cannot carry which record a marker char stands for, so whoever inserts one (the
     * insert-picture command, internal paste, undo, remote apply) stages it first. Staged ids are
     * used before ids of pictures removed in the same edit, and last one edit, like [stageText].
     *
     * @throws IllegalArgumentException if [type] is not a picture type, or [id] is already shown
     *   or already staged.
     */
    fun stagePicture(id: String, type: BlockType, ref: String) {
        require(Markers.isPictureType(type)) { "not a picture type: $type" }
        requireFree(id)
        stagedPictures.addLast(id to PictureInfo(type, ref))
    }

    private fun requireFree(id: String) {
        require(id !in ids) { "block $id is already shown" }
        require(id !in stagedText && stagedPictures.none { it.first == id }) { "block $id is already staged" }
    }

    /**
     * Re-derives [ids] after [buffer]'s changes, from its original text, its change list and its
     * current text. Call it at the end of the field's InputTransformation, after any rule has
     * adjusted the buffer; [edit] calls it for programmatic edits.
     *
     * - Lines outside every changed range keep their ids.
     * - Within a changed range, an old text line's id follows its text: it goes to the new line
     *   holding its first unchanged non-marker character, or, if none survives, to the first new
     *   text line not already taken. So typing keeps an id, Enter keeps it on the first half,
     *   Enter at the very start keeps it on the text (not on the new empty line above), joining
     *   keeps the first line's, and an IME replacement across a line break keeps both.
     * - An old line with no surviving char (empty, marker only, or all of it deleted) keeps its id
     *   on the new line at its surviving boundary, i.e. one that does not start inside inserted
     *   text, if one is free; else on the first free new line (retyping a whole line). So Enter
     *   at the start of an empty checklist item keeps the id on the item, and text inserted in
     *   front of an empty line does not take the empty line's id.
     * - Remaining new text lines, made entirely of inserted text, take [stageText] ids, then
     *   fresh random ids.
     * - A picture/drawing line whose marker char is untouched keeps its id wherever the text
     *   around it moves it. An inserted marker takes a staged id of its type, else the id of a
     *   picture of its type removed in this same edit (a move). Never one of the other type.
     *
     * An inserted marker alone on its line with neither has no record, and inventing a picture
     * without one would save a broken block. What happens then depends on [fromUser]:
     * - `true` (the field's InputTransformation): the marker char is deleted from [buffer]. User
     *   input can carry one, through IME commitText, drag-and-drop, autofill or Unicode entry,
     *   and none of those may crash the editor. The line it stood on stays, empty.
     * - `false` (programmatic, via [edit]): it is a programming error. [buffer] is reverted and
     *   [IllegalStateException] thrown; the same happens if a staged id goes unused.
     *
     * Adapted from the planned `onEdited(changes, before)`: the original text is needed to know
     * where the old lines were, and the buffer carries it alongside the changes, so it is passed
     * whole. The ids before the edit are this model's own [ids].
     */
    fun onEdited(buffer: TextFieldBuffer, fromUser: Boolean) {
        try {
            var derived = derive(buffer) ?: return
            if (derived.unplaced.isNotEmpty()) {
                if (!fromUser) {
                    buffer.revertAllChanges()
                    throw IllegalStateException(
                        "a picture/drawing marker was inserted with no staged id; stage it with stagePicture first",
                    )
                }
                derived.unplaced.sortedDescending().forEach { buffer.replace(it, it + 1, "") }
                derived = derive(buffer) ?: return
                check(derived.unplaced.isEmpty()) { "markers left after removing unplaced ones" }
            }
            if (!fromUser && derived.unusedStaged > 0) {
                buffer.revertAllChanges()
                throw IllegalStateException("${derived.unusedStaged} staged id(s) not used by the edit")
            }
            pictures = derived.pictures
            ids = derived.ids
        } finally {
            stagedText.clear()
            stagedPictures.clear()
        }
    }

    /** [rederive] for [buffer]'s current changes; null if there are none. Reverts [buffer] on a bug. */
    @OptIn(ExperimentalFoundationApi::class) // TextFieldBuffer.changes
    private fun derive(buffer: TextFieldBuffer): Derived? {
        val list = buffer.changes
        if (list.changeCount == 0) return null
        val changes = (0 until list.changeCount).map {
            val o = list.getOriginalRange(it)
            val r = list.getRange(it)
            Change(o.min, o.max, r.min, r.max)
        }.sortedBy { it.oStart }
        return try {
            rederive(buffer.originalText, buffer.asCharSequence(), changes)
        } catch (e: IllegalStateException) {
            buffer.revertAllChanges()
            throw e
        }
    }

    private fun rederive(
        old: CharSequence,
        new: CharSequence,
        changes: List<Change>,
    ): Derived {
        // Copies: the user path may run this twice, and must find staging intact the second time.
        val stagedText = ArrayDeque(stagedText)
        val stagedPictures = ArrayDeque(stagedPictures)
        val oldLines = lineRanges(old)
        check(oldLines.size == ids.size) {
            "EditorModel out of sync before an edit: ${oldLines.size} lines, ${ids.size} ids"
        }
        val newLines = lineRanges(new)
        val shown = pictures
        fun inOld(x: Int) = changes.any { it.oStart <= x && x < it.oEnd }
        fun inserted(x: Int) = changes.any { it.nStart <= x && x < it.nEnd }
        fun toNew(x: Int) = x + changes.filter { it.oEnd <= x }.sumOf { it.delta }
        fun isPictureLine(k: Int) = shown[ids[k]] != null && pictureTypeOf(old, oldLines[k]) != null

        // Group changes into runs of old lines they touch. A change [a, b) touches the lines
        // holding a and b, so deleting a line break touches both lines it joined.
        val regions = mutableListOf<Region>()
        for (c in changes) {
            val first = lineOf(oldLines, c.oStart)
            val last = lineOf(oldLines, c.oEnd)
            val prev = regions.lastOrNull()
            if (prev != null && first <= prev.last) {
                prev.last = maxOf(prev.last, last)
                prev.delta += c.delta
            } else {
                regions += Region(first, last, c.delta)
            }
        }

        val out = arrayOfNulls<String>(newLines.size)
        val newPictures = LinkedHashMap<String, PictureInfo>()
        val removed = mutableListOf<String>() // pictures leaving in this edit, in document order
        val pending = mutableListOf<Pair<Int, BlockType>>() // new line, type of its inserted marker
        var oldLine = 0
        var newLine = 0
        var shift = 0
        fun keep(k: Int) {
            out[newLine] = ids[k]
            shown[ids[k]]?.let { newPictures[ids[k]] = it }
            newLine++
        }
        for (r in regions) {
            while (oldLine < r.first) keep(oldLine++)
            val nf = newLine
            val nl = lineOf(newLines, oldLines[r.last].end + shift + r.delta)
            val region = nf..nl

            // Pictures: an untouched marker keeps its id if it is still alone on a line.
            for (k in r.first..r.last) {
                if (!isPictureLine(k)) continue
                val at = oldLines[k].start
                val j = if (inOld(at)) -1 else lineOf(newLines, toNew(at))
                if (j >= 0 && pictureTypeOf(new, newLines[j]) != null) {
                    out[j] = ids[k]
                    newPictures[ids[k]] = shown.getValue(ids[k])
                } else {
                    removed += ids[k]
                }
            }
            for (j in region) {
                if (out[j] != null) continue
                val type = pictureTypeOf(new, newLines[j]) ?: continue
                if (inserted(newLines[j].start)) pending += j to type
            }
            val isPending = pending.map { it.first }.toSet()
            fun free(j: Int) = out[j] == null && j !in isPending

            // Text: the id follows the line's first surviving non-marker char.
            val orphans = mutableListOf<Int>()
            for (k in r.first..r.last) {
                if (isPictureLine(k)) continue
                val line = oldLines[k]
                val x = (line.start until line.end).firstOrNull { !Markers.isPrivateUse(old[it]) && !inOld(it) }
                if (x == null) {
                    orphans += k
                    continue
                }
                val j = lineOf(newLines, toNew(x))
                if (free(j)) out[j] = ids[k] // else: joined into a line that already has an id
            }
            for (k in orphans) {
                val j = region.firstOrNull { free(it) && !inserted(newLines[it].start) }
                    ?: region.firstOrNull { free(it) }
                if (j != null) out[j] = ids[k]
            }
            for (j in region) if (free(j)) out[j] = stagedText.removeFirstOrNull() ?: BlockIds.random()

            newLine = nl + 1
            oldLine = r.last + 1
            shift += r.delta
        }
        while (oldLine < ids.size) keep(oldLine++)
        check(newLine == newLines.size) { "id re-derivation covered $newLine of ${newLines.size} lines" }

        val unplaced = mutableListOf<Int>()
        for ((j, type) in pending) {
            val staged = stagedPictures.firstOrNull { it.second.type == type }
            if (staged != null) {
                stagedPictures.remove(staged)
                out[j] = staged.first
                newPictures[staged.first] = staged.second
                continue
            }
            val moved = removed.firstOrNull { shown.getValue(it).type == type }
            if (moved == null) {
                unplaced += newLines[j].start
                continue
            }
            removed.remove(moved)
            out[j] = moved
            newPictures[moved] = shown.getValue(moved)
        }

        // Unplaced lines hold "": such a result is never committed (onEdited removes or throws).
        val result = out.map { it ?: "" }
        val placed = result.filter { it.isNotEmpty() }
        check(placed.size == placed.toSet().size) { "duplicate block ids after an edit: $result" }
        return Derived(result, newPictures, unplaced, stagedText.size + stagedPictures.size)
    }
}

private fun nameId(name: String) = NameUuid.v3(name.encodeToByteArray())

/** The index of the last line of [lines] starting at or before [offset]. */
internal fun lineOf(lines: List<Line>, offset: Int): Int {
    var lo = 0
    var hi = lines.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (lines[mid].start <= offset) lo = mid else hi = mid - 1
    }
    return lo
}

/** What an edit does to ids, before it is committed. */
private class Derived(
    val ids: List<String>,
    val pictures: Map<String, PictureInfo>,
    /** Start offsets of inserted marker lines that got no id. */
    val unplaced: List<Int>,
    val unusedStaged: Int,
)

private class PictureInfo(val type: BlockType, val ref: String?)

/**
 * One line as [EditorModel.project] makes it. [source] is the block as given, for the line that
 * keeps its id; [originId] is the id of the block the line came from (null for the line of an
 * empty document), shared by every line a block was split into.
 */
internal class ProjectedLine(val block: Block, val source: Block?, val originId: String?)

private class Change(val oStart: Int, val oEnd: Int, val nStart: Int, val nEnd: Int) {
    val delta get() = (nEnd - nStart) - (oEnd - oStart)
}

private class Region(val first: Int, var last: Int, var delta: Int)

/** A line of the field: [start] inclusive, [end] exclusive, not counting its `\n`. */
internal class Line(val start: Int, val end: Int)

private class Piece(val text: String, val spans: List<StyleSpan>)

internal class PlacedStyle(val style: SpanStyle, val start: Int, val end: Int)

internal fun lineRanges(text: CharSequence): List<Line> {
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
internal fun pictureTypeOf(text: CharSequence, line: Line): BlockType? {
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
internal fun placeStyles(spans: List<StyleSpan>, base: Int, out: MutableList<PlacedStyle>) {
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
internal fun stylesOf(style: SpanStyle): List<InlineStyle> = buildList {
    val weight = style.fontWeight
    if (weight != null && weight >= FontWeight.Bold) add(InlineStyle.BOLD)
    if (style.fontStyle == FontStyle.Italic) add(InlineStyle.ITALIC)
    val deco = style.textDecoration
    if (deco != null && deco.contains(TextDecoration.Underline)) add(InlineStyle.UNDERLINE)
    if (deco != null && deco.contains(TextDecoration.LineThrough)) add(InlineStyle.STRIKE)
}
