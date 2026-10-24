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
 * the save path can read [base] whenever it saves.
 *
 * One controller per note, enforced twice, because offering note B's document to an editor
 * showing note A would apply B as a "synced change" of A, and undo would then re-insert A's
 * blocks into B:
 * - [rememberBlockEditorController] is keyed on the note id, so a new note gets a new controller:
 *   a fresh model, undo history, remote state and drag state.
 * - [BlockEditor] passes its `noteId` with every document ([receive]). A document of any note
 *   other than the one loaded is never offered to [RemoteApply]; it is loaded instead, which
 *   clears the undo history. So even a controller hoisted without the key cannot mix two notes.
 *
 * Every command here is a local edit: it is recorded for undo and reported through the editor's
 * `onChange`, exactly like typing.
 *
 * Main thread only, like [EditorModel].
 */
@Stable
class BlockEditorController internal constructor(
    /** The note this controller was made for; informational, [receive] decides by the note it is given. */
    val noteId: String,
    internal val now: () -> Long,
) {

    constructor(noteId: String) : this(noteId, ::currentTimeMillis)

    internal val model = EditorModel(BlockDocument(emptyList()))
    internal val history = BlockHistory(model, now)
    internal val remote = RemoteApply(model, now)

    init {
        history.onStepped = { localEdited(model.blocks()) }
    }

    /** The note whose document is loaded, or null before the first; the field is not shown before it. */
    internal var loadedNote: String? by mutableStateOf(null)
        private set

    internal val loaded: Boolean get() = loadedNote != null

    /** The [now] time of the last local edit, for [RemoteApply.tick]. Snapshot state: it keys the re-tick. */
    internal var lastLocalEditAt by mutableStateOf(Long.MIN_VALUE)
        private set

    /** The field's last layout that matched its text, with the ids it was made for; see [LaidOutText]. */
    internal var laidOut: LaidOutText? by mutableStateOf(null)
        private set

    internal val layout: TextLayoutResult? get() = laidOut?.layout

    /** From the field's `onTextLayout`: kept only if it is the layout of the text the model holds now. */
    internal fun laidOut(result: TextLayoutResult?) {
        if (result == null) return
        val text = model.state.text.toString()
        val ids = model.ids
        if (result.layoutInput.text.text != text || lineRanges(text).size != ids.size) return
        laidOut = LaidOutText(result, text, ids, model.refs)
    }

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

    /**
     * Opens [doc] if nothing, or another note, is loaded; else offers it as a synced change of the
     * loaded note and applies what it can now.
     */
    internal fun receive(noteId: String, doc: BlockDocument) {
        if (noteId != loadedNote) {
            // First document, or another note's: open it. noteLoaded -> EditorModel.load clears
            // the undo history and drops any pending document of the previous note.
            remote.noteLoaded(doc)
            loadedNote = noteId
            lastLocalEditAt = Long.MIN_VALUE
            laidOut = null
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

/**
 * A [BlockEditorController] for note [noteId]. A different [noteId] gives a new controller, with
 * nothing of the previous note's state or undo history.
 */
@Composable
fun rememberBlockEditorController(noteId: String): BlockEditorController =
    remember(noteId) { BlockEditorController(noteId) }

internal expect fun currentTimeMillis(): Long
