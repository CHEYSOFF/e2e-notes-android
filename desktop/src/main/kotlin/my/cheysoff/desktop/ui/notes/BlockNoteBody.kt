package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_blocks.InlineStyle
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.sketch.DisplaySketch
import my.cheysoff.core_editor.ui.BlockEditor
import my.cheysoff.core_editor.ui.BlockEditorController
import my.cheysoff.core_editor.ui.LocalPictureSelected
import my.cheysoff.desktop.ui.attachment.DesktopImportResult
import my.cheysoff.desktop.ui.attachment.decodeAttachmentImage
import my.cheysoff.desktop.ui.state.EditorDraft
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.EditorBodyGrey
import my.cheysoff.desktop.ui.theme.MetaGrey
import my.cheysoff.desktop.ui.theme.PlaceholderGrey

/**
 * The note body: a toolbar over the shared [BlockEditor], which holds the text,
 * checklists, pictures and drawings of the note in one field.
 *
 * - The editor gets [EditorDraft.id] and [EditorDraft.blocks] from the same draft, so the note id
 *   and its document always arrive together (see [EditorDraft.blocks]).
 * - Every local edit goes to [onBlocksChange] with the controller's base read at that moment; the
 *   model debounces and saves (see `NotesWorkspaceModel.onBlocksChanged` for why the base is read
 *   here and not at save time).
 * - A body that does not decode is shown read-only ([UnreadableBody]) and never handed to the
 *   editor, so nothing can be typed into it or saved over it.
 */
@Composable
internal fun BlockNoteBody(
    draft: EditorDraft,
    controller: BlockEditorController,
    accent: Color,
    attachments: List<AttachmentData>,
    sketches: List<DisplaySketch>,
    pasteFailure: DesktopImportResult?,
    onDismissPasteFailure: () -> Unit,
    onBlocksChange: (noteId: String, blocks: List<Block>, base: BlockDocument) -> Unit,
    onInsertPicture: () -> Unit,
    /** A click on a picture: opens the full-screen viewer on its record. */
    onOpenPicture: (AttachmentData) -> Unit,
) {
    val unreadable = draft.unreadableBody
    if (unreadable != null) {
        UnreadableBody(unreadable)
        return
    }
    val document = draft.blocks
    if (document == null) {
        // The first assembly of this note is a store read away; an empty field here would accept
        // typing that belongs to no document.
        Text("Opening…", color = PlaceholderGrey, style = MaterialTheme.typography.bodyMedium)
        return
    }

    BlockToolbar(controller, accent, onInsertPicture)
    PasteFailureLine(pasteFailure, onDismissPasteFailure)
    Spacer(Modifier.height(6.dp))

    val noteId = draft.id
    BlockEditor(
        noteId = noteId,
        document = document,
        onChange = { blocks -> onBlocksChange(noteId, blocks, controller.base) },
        pictureContent = { recordId, kind -> NotePicture(recordId, kind, attachments, sketches, onOpenPicture) },
        modifier = Modifier.fillMaxWidth(),
        controller = controller,
        selectionAccent = accent,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = EditorBodyGrey),
        pictureHeight = 400.dp,
        placeholder = { Text("Start writing…", style = MaterialTheme.typography.bodyMedium, color = PlaceholderGrey) },
    )
}

/**
 * Bold, Italic, Strikethrough, H1-H3, Bullet, Checklist, Picture, Clear formatting, Undo, Redo.
 *
 * Fixed under the rule, not floating like the phone's (a desktop window has no keyboard to dodge). The
 * lit state comes from the controller's snapshot state ([BlockEditorController.activeStyles],
 * [BlockEditorController.activeType], [BlockEditorController.canUndo]/[canRedo][BlockEditorController.canRedo]),
 * so it follows the caret without the toolbar polling anything. The type buttons toggle: pressing
 * H1 on an H1 line returns it to a paragraph (see `EditorCommands.setType`).
 */
@Composable
private fun BlockToolbar(controller: BlockEditorController, accent: Color, onInsertPicture: () -> Unit) {
    val styles = controller.activeStyles
    val type = controller.activeType
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        ToolButton("B", InlineStyle.BOLD in styles, accent, weight = FontWeight.Bold) { controller.toggle(InlineStyle.BOLD) }
        ToolButton("I", InlineStyle.ITALIC in styles, accent, italic = true) { controller.toggle(InlineStyle.ITALIC) }
        ToolButton("S", InlineStyle.STRIKE in styles, accent, strike = true) { controller.toggle(InlineStyle.STRIKE) }
        Spacer(Modifier.width(6.dp))
        ToolButton("H1", type == BlockType.H1, accent) { controller.setType(BlockType.H1) }
        ToolButton("H2", type == BlockType.H2, accent) { controller.setType(BlockType.H2) }
        ToolButton("H3", type == BlockType.H3, accent) { controller.setType(BlockType.H3) }
        Spacer(Modifier.width(6.dp))
        ToolButton("• List", type == BlockType.BULLET, accent) { controller.setType(BlockType.BULLET) }
        ToolButton("☐ Checklist", type == BlockType.CHECK, accent) { controller.setType(BlockType.CHECK) }
        ToolButton("Picture", false, accent, onClick = onInsertPicture)
        Spacer(Modifier.width(6.dp))
        ToolButton("Clear formatting", false, accent) { controller.clearFormatting() }
        Spacer(Modifier.width(6.dp))
        ToolButton("Undo", false, accent, enabled = controller.canUndo) { controller.undo() }
        ToolButton("Redo", false, accent, enabled = controller.canRedo) { controller.redo() }
    }
}

@Composable
private fun ToolButton(
    label: String,
    active: Boolean,
    accent: Color,
    weight: FontWeight = FontWeight.Medium,
    italic: Boolean = false,
    strike: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = when {
            !enabled -> PlaceholderGrey
            active -> Color.White
            else -> BodyGrey
        },
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent else Color.Transparent)
            .then(if (enabled) Modifier.pointerHoverIcon(handCursor).clickable(onClick = onClick) else Modifier)
            .widthIn(min = 22.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * What a picture or drawing block shows, looked up by record id in the open note's live
 * attachments and sketches. A record not here yet (still syncing, or soft-deleted until the next
 * save undeletes it) or one that fails to decode is a placeholder; the block itself is the
 * editor's and stays, and is saved as it is.
 */
@Composable
private fun NotePicture(
    recordId: String,
    kind: BlockType,
    attachments: List<AttachmentData>,
    sketches: List<DisplaySketch>,
    onOpen: (AttachmentData) -> Unit,
) {
    // A photo sits at the left at its own shape, rounded, on no backdrop; a drawing (a canvas of its
    // own) and the placeholders keep the dark card.
    val card = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C1C22))
    if (kind == BlockType.DRAWING) {
        Box(card, contentAlignment = Alignment.Center) {
            when (val row = sketches.firstOrNull { it.id == recordId }) {
                is DisplaySketch.Drawing -> SketchCanvas(row.sketch, Modifier.fillMaxSize())
                is DisplaySketch.Undecodable -> PicturePlaceholder("Can't display this drawing")
                null -> PicturePlaceholder("Loading drawing…")
            }
        }
        return
    }
    val attachment = attachments.firstOrNull { it.id == recordId }
    if (attachment == null) {
        Box(card, contentAlignment = Alignment.Center) { PicturePlaceholder("Loading picture…") }
        return
    }
    // Keyed on a content hash, not the arrays: ByteArray equality is identity, and every
    // recomposition would otherwise decode the picture again.
    val bitmap = remember(attachment.id, attachment.bytes.contentHashCode(), attachment.thumbBytes.contentHashCode()) {
        decodeAttachmentImage(attachment.bytes) ?: decodeAttachmentImage(attachment.thumbBytes)
    }
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
        Box(card.openOnClick(attachment, onOpen), contentAlignment = Alignment.Center) {
            PicturePlaceholder("Can't display this picture")
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val height = minOf(maxHeight, maxWidth / ratio)
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(height * ratio, height).clip(RoundedCornerShape(14.dp)).openOnClick(attachment, onOpen),
        )
    }
}

/**
 * Calls [onOpen] on a click that stays a click, on a picture already selected: the first click
 * selects it (showing its resize handle), the next opens it. Deliberately not `clickable`/`detectTapGestures`:
 * those consume the press, and the editor's own overlay around this content needs it too -- its tap
 * selects the picture (so Delete acts on it) and its drag moves it. This only watches: it fires when
 * the press is released without the drag having consumed the movement
 * ([waitForUpOrCancellation] returns null once anything consumed a change), so moving a picture
 * never opens the viewer.
 */
@Composable
private fun Modifier.openOnClick(attachment: AttachmentData, onOpen: (AttachmentData) -> Unit): Modifier {
    val selected by rememberUpdatedState(LocalPictureSelected.current)
    return pointerInput(attachment.id) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // Read at the press: the editor's own tap selects the picture on release.
            val wasSelected = selected
            if (waitForUpOrCancellation() != null && wasSelected) onOpen(attachment)
        }
    }
}

/**
 * Whether this controller has opened its note. [BlockEditorController.base] is documented to throw
 * until then, and that is the only public signal of it; a picture inserted before it would have no
 * editor to land in.
 */
internal fun BlockEditorController.isLoaded(): Boolean = runCatching { base }.isSuccess

/**
 * Whether an image may be attached to [draft] as a picture block now: the body is editable (not
 * [EditorDraft.unreadableBody]), its document is assembled, and the editor has loaded it. Otherwise
 * the paste is refused before any record is written.
 */
internal fun BlockEditorController.canPlacePicture(draft: EditorDraft): Boolean =
    draft.unreadableBody == null && draft.blocks != null && noteId == draft.id && isLoaded()

@Composable
private fun PicturePlaceholder(text: String) {
    Text(text, color = BodyGrey, style = MaterialTheme.typography.bodySmall)
}

/**
 * A BLOCKS body this build cannot decode: the raw text, selectable but not editable, under a
 * notice. Never offered to the editor and never saved; see [EditorDraft.unreadableBody].
 */
@Composable
private fun UnreadableBody(raw: String) {
    Text(
        text = "This note was saved by a newer version of Manana and is shown read-only. Update the app to edit it.",
        color = MetaGrey,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    SelectionContainer {
        Text(text = raw, color = EditorBodyGrey, style = MaterialTheme.typography.bodyMedium)
    }
}
