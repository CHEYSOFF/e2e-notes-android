package my.cheysoff.core_editor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_editor.EditingRules
import my.cheysoff.core_editor.RemoteApply
import my.cheysoff.core_editor.blockHistoryKeys
import my.cheysoff.core_editor.selectionPastMarker

/** Test tag of the editor's text field. */
const val BlockEditorFieldTag = "block-editor-field"

/** Test tag around the placeholder shown while the note body is empty. */
const val BlockEditorPlaceholderTag = "block-editor-placeholder"

/** The default accent: Manana's violet. Both apps pass their theme's primary instead. */
val DefaultEditorAccent = Color(0xFF7C5CFF)

/**
 * The note editor shared by both apps: the whole note in one `BasicTextField`, so a selection
 * runs across paragraphs, checklists and pictures, with checkboxes, bullets, numbers, pictures
 * and drawings drawn over their marker lines ([BlockOverlays]).
 *
 * - [document] is the latest stored version of note [noteId]. The first one is opened; every
 *   later emission is a synced change, offered to [RemoteApply], which applies it around the
 *   user's typing. Re-emit freely: an echo of our own save makes no edit. A document for another
 *   [noteId] is opened afresh, never offered (see [BlockEditorController]).
 * - [onChange] gets the visible blocks after each LOCAL edit (typing, IME, paste, cut, a toolbar
 *   command, a tick, a picture drag, undo/redo), never after a synced change was applied, so a
 *   save it triggers is always the user's. Debounce in the caller; save against
 *   [BlockEditorController.base].
 * - [pictureContent] draws a picture or drawing record into the space its line takes. It must
 *   cope with a record it cannot resolve (not synced yet, failed to decode) by drawing a
 *   placeholder; the block itself stays and is saved as it is.
 * - [pictureAspect] gives a record's shape, width over height, or null when unknown. A picture's
 *   box takes that shape, as large as fits the field's width and [pictureHeight], so the frame and
 *   resize controls sit on the picture; an unknown shape takes the whole width.
 * - [pictureSizing] is how a selected picture or drawing is resized: a corner handle to drag
 *   (mouse) or a row of size buttons (touch). Either way the size syncs as part of the block.
 * - [selectionAccent] colours the selection handles and cursor, and at 30 % alpha the selection
 *   background; pass the app theme's primary.
 *
 * The field has no scrolling of its own and grows with its text: put it in a scrolling column.
 * Undo/redo is the editor's per-block history ([my.cheysoff.core_editor.BlockHistory]), on the
 * usual keys and on [BlockEditorController.undo]/[BlockEditorController.redo].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BlockEditor(
    noteId: String,
    document: BlockDocument,
    onChange: (List<Block>) -> Unit,
    pictureContent: @Composable (recordId: String, kind: BlockType) -> Unit,
    modifier: Modifier = Modifier,
    controller: BlockEditorController = rememberBlockEditorController(noteId),
    selectionAccent: Color = DefaultEditorAccent,
    textStyle: TextStyle = TextStyle(color = Color(0xFF1F1D2B), fontSize = 16.sp),
    pictureHeight: Dp = 200.dp,
    placeholder: (@Composable () -> Unit)? = null,
    pictureSizing: PictureSizing = PictureSizing.Handle,
    pictureAspect: (recordId: String, kind: BlockType) -> Float? = { _, _ -> null },
) {
    SideEffect { controller.onChange = onChange }

    // Every emission: the first of a note opens it, the rest are synced changes of it.
    LaunchedEffect(controller, noteId, document) { controller.receive(noteId, document) }

    // A selection on one list line starts at its text, never under the checkbox; see selectionPastMarker.
    LaunchedEffect(controller) {
        snapshotFlow { controller.model.state.selection }.collect { sel ->
            val state = controller.model.state
            selectionPastMarker(state.text, sel)?.let { state.edit { selection = it } }
        }
    }

    // A change held back for the user's typing is retried once the pause has passed, and again
    // after each later local edit; never in a loop. Changes held because both sides edited a
    // block wait for the next emission or edit (see RemoteApply).
    val pending = controller.remote.hasPending
    val lastEdit = controller.lastLocalEditAt
    LaunchedEffect(controller, pending, lastEdit) {
        if (!pending) return@LaunchedEffect
        val wait = if (lastEdit == Long.MIN_VALUE) 0L else lastEdit + RemoteApply.QUIET_MS - controller.now()
        delay(wait.coerceAtLeast(0L) + 1L)
        controller.retick()
    }

    // Until this note's document is in, show nothing: a field still holding another note's text
    // would let typing (and its onChange) land on the wrong note.
    if (controller.loadedNote != noteId) return

    val density = LocalDensity.current
    val textColor = textStyle.color.takeIf { it != Color.Unspecified } ?: Color.Black
    val bodySize = if (textStyle.fontSize.isUnspecified) 16.sp else textStyle.fontSize
    // Keyed on sizes, shapes and the field's width, so a resize (or a photo arriving) lays the
    // field out again with the picture's line at its new height. The width is the last layout's:
    // until there is one, a picture line takes the full height.
    val sizes = controller.model.pictureSizes
    val refs = controller.model.refs
    val aspects = controller.model.ids.filter { it.startsWith("pic:") || it.startsWith("drw:") }.associateWith { id ->
        pictureAspect(refs[id] ?: id.substringAfter(':'), if (id.startsWith("drw:")) BlockType.DRAWING else BlockType.PICTURE)
    }
    val fieldWidth = controller.laidOut?.layout?.size?.width?.let { with(density) { it.toDp().value } } ?: 0f
    val styling = remember(bodySize, pictureHeight, density.fontScale, textColor, sizes, aspects, fieldWidth) {
        EditorStyling(bodySize, pictureHeight, density.fontScale, textColor.copy(alpha = 0.45f)) { k ->
            val id = controller.model.ids.getOrNull(k)
            pictureBox(fieldWidth, pictureHeight.value, aspects[id], sizes[id]).second.dp
        }
    }
    val input = remember(controller) { EditingRules(controller.model).then(ReportLocalEdits(controller)) }
    val drag = remember(controller, controller.loadedNote) { PictureDrag() }
    val outerClipboard = LocalClipboard.current
    val clipboard = remember(outerClipboard) { editorClipboard(outerClipboard) }
    // Read at the moment a menu item is built or clicked, never captured: the selection moves.
    val selectedPlain = remember(controller) {
        {
            val state = controller.model.state
            selectedPlainText(state.text, state.selection.start, state.selection.end)
        }
    }
    val selectionColors = remember(selectionAccent) {
        TextSelectionColors(handleColor = selectionAccent, backgroundColor = selectionAccent.copy(alpha = 0.3f))
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors, LocalClipboard provides clipboard) {
        Box(modifier) {
            BasicTextField(
                state = controller.model.state,
                modifier = Modifier.fillMaxWidth().blockHistoryKeys(controller.history)
                    .editorTextMenu(selectedPlain)
                    .testTag(BlockEditorFieldTag),
                inputTransformation = input,
                outputTransformation = styling,
                // No fixed line height: it would squash a picture's line to a text line's height.
                // Lines are spaced by EditorStyling's line-break gap instead.
                textStyle = textStyle.copy(lineHeight = TextUnit.Unspecified),
                cursorBrush = SolidColor(selectionAccent),
                onTextLayout = { result -> controller.laidOut(result()) },
                decorator = { inner ->
                    // A new or emptied note: the hint sits under the cursor until the first character.
                    if (placeholder != null && controller.model.state.text.isEmpty()) {
                        Box(Modifier.testTag(BlockEditorPlaceholderTag)) { placeholder() }
                    }
                    inner()
                },
            )
            controller.laidOut?.let { view ->
                BlockOverlays(controller, view, drag, selectionAccent, textColor, pictureSizing, aspects, pictureContent)
            }
            drag.slot?.let { DropIndicator(it, selectionAccent) }
        }
    }
}

/**
 * Last in the field's input chain, after [EditingRules]: a user edit that changed the text is a
 * local edit, reported with the blocks as the buffer now holds them (the field commits exactly
 * that right after).
 */
@OptIn(ExperimentalFoundationApi::class) // TextFieldBuffer.changes
private class ReportLocalEdits(private val controller: BlockEditorController) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount > 0) controller.localEdited(controller.model.blocksOf(this))
    }
}
