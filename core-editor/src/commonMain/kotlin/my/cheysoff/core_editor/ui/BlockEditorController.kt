package my.cheysoff.core_editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextLayoutResult
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockIds
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_editor.BlockHistory
import my.cheysoff.core_editor.EditorCommands
import my.cheysoff.core_editor.EditorModel
import my.cheysoff.core_editor.Markers
import my.cheysoff.core_editor.RemoteApply
import my.cheysoff.core_editor.lineOf
import my.cheysoff.core_editor.lineRanges

/**
 * The handle a toolbar (and the hosting screen's save path) holds on one [BlockEditor].
 *
 * It owns the editor's state, not the composable, so the toolbar can sit outside the editor and
 * the save path can read [base] whenever it saves. One controller per open note: the hosting
 * screen keys it (and the [BlockEditor]) on the note id, since the first document a controller
 * sees is loaded and every later one is applied as a synced change of that same note.
 *
 * Every command here is a local edit: it is recorded for undo and reported through the editor's
 * `onChange`, exactly like typing.
 *
 * Main thread only, like [EditorModel].
 */
@Stable
class BlockEditorController internal constructor(internal val now: () -> Long) {

    constructor() : this(::currentTimeMillis)

    internal val model = EditorModel(BlockDocument(emptyList()))
    internal val history = BlockHistory(model, now)
    internal val remote = RemoteApply(model, now)

    init {
        history.onStepped = { localEdited(model.blocks()) }
    }

    /** Whether the first document has been loaded; the field is not shown before it is. */
    internal var loaded by mutableStateOf(false)
        private set

    /** The [now] time of the last local edit, for [RemoteApply.tick]. Snapshot state: it keys the re-tick. */
    internal var lastLocalEditAt by mutableStateOf(Long.MIN_VALUE)
        private set

    /** The field's latest layout, for the overlays. Its text is the field's text, offset for offset. */
    internal var layout: TextLayoutResult? by mutableStateOf(null)

    /** Set by [BlockEditor] to its current `onChange`. */
    internal var onChange: (List<Block>) -> Unit = {}

    /**
     * What the editor has caught up with from storage, to stamp saves against: pass
     * `BlockCodec.encode(controller.base)` as `blocksBase` on every save of this note (see
     * [RemoteApply.base] for why not the stored body).
     *
     * @throws IllegalStateException before the editor has loaded its first document.
     */
    val base: BlockDocument get() = remote.base

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    /**
     * The inline styles every selected character has (Word's "button lit" rule), or, for a caret,
     * the styles of the character before it on the same line. Markers never count.
     */
    val activeStyles: Set<InlineStyle>
        get() {
            val block = blockAtSelection() ?: return emptySet()
            val (line, block2) = block
            val sel = model.state.selection
            val textStart = line.first + if (Markers.isPrivateUse(model.state.text.getOrNull(line.first) ?: ' ')) 1 else 0
            val a = (sel.min - textStart).coerceIn(0, block2.text.length)
            val b = (sel.max - textStart).coerceIn(0, block2.text.length)
            return if (a == b) {
                if (a == 0) emptySet() else block2.spans.filter { it.start < a && it.end >= a }.mapTo(HashSet()) { it.style }
            } else {
                InlineStyle.entries.filterTo(HashSet()) { s -> covers(block2, s, a, b) }
            }
        }

    /** The type of the line the selection starts on. */
    val activeType: BlockType get() = blockAtSelection()?.second?.type ?: BlockType.PARAGRAPH

    fun toggle(style: InlineStyle) = local { EditorCommands.toggle(model, style) }

    fun setType(type: BlockType) = local { EditorCommands.setType(model, type) }

    fun clearFormatting() = local { EditorCommands.clearFormatting(model) }

    /** Puts picture (or drawing, by [kind]) record [recordId] on its own line at the cursor. */
    fun insertPicture(recordId: String, kind: BlockType) = local {
        val id = if (kind == BlockType.DRAWING) BlockIds.forDrawing(recordId) else BlockIds.forPicture(recordId)
        EditorCommands.insertPicture(model, id, kind)
    }

    // Not through local(): history.onStepped reports these, for the keyboard shortcuts too.
    fun undo() {
        if (loaded) history.undo()
    }

    fun redo() {
        if (loaded) history.redo()
    }

    /** Ticks or unticks the checklist item on line [line]. */
    internal fun toggleCheck(line: Int) = local { EditorCommands.toggleCheck(model, line) }

    /** Moves the picture on line [from] to gap [toGap]; see [EditorCommands.movePicture]. */
    internal fun movePicture(from: Int, toGap: Int) = local { EditorCommands.movePicture(model, from, toGap) }

    /** Opens [doc] if nothing is loaded yet, else offers it as a synced change and applies what it can now. */
    internal fun receive(doc: BlockDocument) {
        if (!loaded) {
            remote.noteLoaded(doc)
            loaded = true
        } else {
            remote.offer(doc)
            remote.tick(lastLocalEditAt)
        }
    }

    /** For the lazy re-tick after a pause. */
    internal fun retick() = remote.tick(lastLocalEditAt)

    /**
     * A local change was made (typing, already committed or about to be, with [after] as its
     * result; or a command). Stamps [lastLocalEditAt] and reports the blocks.
     */
    internal fun localEdited(after: List<Block>) {
        lastLocalEditAt = now()
        onChange(after)
    }

    /** Runs a command; if it changed the document, it is a local edit. */
    private fun local(command: () -> Unit) {
        if (!loaded) return // a toolbar press before the note is open has nothing to act on
        val before = model.blocks()
        command()
        val after = model.blocks()
        if (after != before) localEdited(after)
    }

    private fun blockAtSelection(): Pair<IntRange, Block>? {
        if (!loaded) return null
        val text = model.state.text
        val lines = lineRanges(text)
        val k = lineOf(lines, model.state.selection.min)
        val block = model.blocks().getOrNull(k) ?: return null
        val line = lines[k]
        return (line.start until line.end) to block
    }

    private fun covers(block: Block, style: InlineStyle, a: Int, b: Int): Boolean {
        var at = a
        while (at < b) {
            val run = block.spans.firstOrNull { it.style == style && it.start <= at && it.end > at } ?: return false
            at = run.end
        }
        return true
    }
}

/** A [BlockEditorController] that lives as long as the composition that remembers it. */
@Composable
fun rememberBlockEditorController(): BlockEditorController = remember { BlockEditorController() }

internal expect fun currentTimeMillis(): Long
