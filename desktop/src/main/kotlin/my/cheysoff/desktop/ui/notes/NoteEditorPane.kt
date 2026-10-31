package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockDocument
import my.cheysoff.core_blocks.BlockType
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.sketch.DisplaySketch
import my.cheysoff.desktop.ui.attachment.DesktopImportResult
import my.cheysoff.desktop.ui.attachment.clipboardImage
import my.cheysoff.desktop.ui.attachment.pickImageFile
import my.cheysoff.core_editor.ui.BlockEditorController
import java.awt.Image
import my.cheysoff.desktop.ui.state.EditorDraft
import my.cheysoff.desktop.ui.state.SaveStatus
import my.cheysoff.desktop.ui.theme.AccentIndigo
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.MetaGrey
import my.cheysoff.desktop.ui.theme.OutlineDark
import my.cheysoff.desktop.ui.theme.PlaceholderGrey
import my.cheysoff.desktop.ui.theme.SurfaceDark
import my.cheysoff.desktop.ui.theme.TitleGrey
import my.cheysoff.desktop.ui.theme.folderAccentColor

/**
 * The right pane: title, body and checklist for the selected note, with no navigation of its own.
 *
 * Saving is automatic and there is no Save button; the only affordance is the status line next to
 * the title, which is deliberately quiet. Ctrl+S is wired to a flush so that the reflex does
 * something rather than nothing, but it is never the thing that makes an edit durable.
 *
 * The body is the shared block editor ([BlockNoteBody]): text, checklists, pictures and drawings in
 * one field, saved as BLOCKS through [onBlocksChange]. A note stored as PLAIN or HTML opens converted
 * (`OpenDocument.assemble`) and is written as BLOCKS on its first edit.
 */
@Composable
fun NoteEditorPane(
    draft: EditorDraft?,
    folders: List<Folder>,
    saveStatus: SaveStatus,
    now: Long,
    titleFocus: FocusRequester,
    onTitleChange: (String) -> Unit,
    onSetFolder: (String?) -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    sketches: List<DisplaySketch>,
    attachments: List<AttachmentData>,
    onAttachmentTapped: (AttachmentData) -> Unit,
    /**
     * An image to attach. [onAttached] must be called with the saved record's id, once the record
     * is written, to place its picture block at the caret.
     */
    onPasteImage: (image: Image, onAttached: (recordId: String) -> Unit) -> Unit,
    pasteFailure: DesktopImportResult?,
    onDismissPasteFailure: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The block editor's controller for [draft]'s note (`rememberBlockEditorController(draft.id)`),
     * null only when no note is open. Hoisted to the screen because the full-screen viewer, drawn
     * there, deletes a picture through it (see `deletePictureFromViewer`).
     */
    blockController: BlockEditorController?,
    /** A local edit in the block editor: the note it belongs to, its blocks, and the editor's base. */
    onBlocksChange: (noteId: String, blocks: List<Block>, base: BlockDocument) -> Unit = { _, _, _ -> },
) {
    if (draft == null || blockController == null) {
        EditorEmptyState(modifier)
        return
    }

    val accent = folderAccentColor(draft.folderId, folders.firstOrNull { it.id == draft.folderId }?.colorArgb)
        ?: AccentIndigo
    val placePicture = { recordId: String -> blockController.insertPicture(recordId, BlockType.PICTURE) }

    val scroll = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        EditorActionBar(
            draft = draft,
            folders = folders,
            accent = accent,
            saveStatus = saveStatus,
            now = now,
            onSetFolder = onSetFolder,
            onTogglePin = onTogglePin,
            onToggleFavorite = onToggleFavorite,
            onDelete = onDelete,
        )

        // The scrollbar is a sibling of the scrolling Column, not inside it: a child of a
        // vertically-scrolling container scrolls away with the content it is supposed to be
        // describing.
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Ctrl+V of an image anywhere in the note attaches it. Checked before the focused
                // field sees the key; when the clipboard holds text, the event is left alone and the
                // field pastes as usual (see clipboardImage for which wins when it holds both).
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isPaste()) return@onPreviewKeyEvent false
                    val image = clipboardImage() ?: return@onPreviewKeyEvent false
                    // Refused (and swallowed: there is nothing for the image to go into) while the
                    // note is read-only or its editor has not loaded; see canPlacePicture.
                    if (!blockController.canPlacePicture(draft)) return@onPreviewKeyEvent true
                    onPasteImage(image, placePicture)
                    true
                }
                .verticalScroll(scroll)
                .padding(horizontal = 34.dp),
        ) {
            val titleStyle = MaterialTheme.typography.titleLarge
            BasicTextField(
                value = draft.title,
                onValueChange = onTitleChange,
                textStyle = titleStyle.copy(color = TitleGrey),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                decorationBox = { inner ->
                    if (draft.title.isEmpty()) Text("Title", style = titleStyle, color = PlaceholderGrey)
                    inner()
                },
            )

            Spacer(Modifier.height(10.dp))
            // The `────` rule from the approved sketch. It is the only horizontal line in the
            // window, which is what makes it read as "title above, note below" rather than chrome.
            HorizontalDivider(color = OutlineDark)
            Spacer(Modifier.height(6.dp))

            BlockNoteBody(
                draft = draft,
                controller = blockController,
                accent = accent,
                attachments = attachments,
                sketches = sketches,
                pasteFailure = pasteFailure,
                onDismissPasteFailure = onDismissPasteFailure,
                onBlocksChange = onBlocksChange,
                onInsertPicture = {
                    if (blockController.canPlacePicture(draft)) pickImageFile()?.let { onPasteImage(it, placePicture) }
                },
                onOpenPicture = onAttachmentTapped,
            )

            Spacer(Modifier.height(60.dp))
        }

            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** Why the last image paste attached nothing; click to dismiss. */
@Composable
internal fun PasteFailureLine(pasteFailure: DesktopImportResult?, onDismiss: () -> Unit) {
    if (pasteFailure == null) return
    Text(
        text = when (pasteFailure) {
            DesktopImportResult.TooLarge -> "That image is too large to attach."
            else -> "Couldn't read an image from the clipboard."
        } + "  ✕",
        color = MetaGrey,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .pointerHoverIcon(handCursor)
            .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun EditorActionBar(
    draft: EditorDraft,
    folders: List<Folder>,
    accent: Color,
    saveStatus: SaveStatus,
    now: Long,
    onSetFolder: (String?) -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    var folderMenuOpen by remember { mutableStateOf(false) }
    val currentFolder = folders.firstOrNull { it.id == draft.folderId }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 34.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color(0xFF1C1C22))
                    .pointerHoverIcon(handCursor)
                    .clickable { folderMenuOpen = true }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = currentFolder?.name ?: "No folder",
                    color = if (currentFolder != null) TitleGrey else BodyGrey,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                )
            }
            DropdownMenu(expanded = folderMenuOpen, onDismissRequest = { folderMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No folder", style = MaterialTheme.typography.bodySmall) },
                    onClick = { folderMenuOpen = false; onSetFolder(null) },
                )
                folders.forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folder.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = { folderMenuOpen = false; onSetFolder(folder.id) },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = saveLine(saveStatus, draft.updatedAt, now),
            color = MetaGrey,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(12.dp))

        // Pin is drawn, not iconified, so the toggle and the list row show the same mark.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (draft.isPinned) accent else SurfaceDark)
                .pointerHoverIcon(handCursor)
                .clickable(onClickLabel = if (draft.isPinned) "Unpin note" else "Pin note", onClick = onTogglePin)
                .padding(9.dp),
        ) {
            PinGlyph(tint = if (draft.isPinned) Color.White else BodyGrey)
        }
        Spacer(Modifier.width(6.dp))
        PillIconButton(
            icon = Icons.Default.Star,
            description = if (draft.isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (draft.isFavorite) Color.White else BodyGrey,
            background = if (draft.isFavorite) accent else SurfaceDark,
            onClick = onToggleFavorite,
        )
        Spacer(Modifier.width(6.dp))
        PillIconButton(
            icon = Icons.Default.Delete,
            description = "Move note to trash",
            tint = BodyGrey,
            onClick = onDelete,
        )
    }
}

/** Ctrl+V, and Shift+Insert -- the older paste chord Windows still honours everywhere. */
private fun KeyEvent.isPaste(): Boolean =
    (key == Key.V && (isCtrlPressed || isMetaPressed)) || (key == Key.Insert && isShiftPressed)

@Composable
private fun EditorEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nothing open.",
            color = TitleGrey,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(14.dp))
        // The list is centred as a block, but its rows are start-aligned inside it: centring each
        // row on its own shifts the key column left and right with the length of its label.
        Column(horizontalAlignment = Alignment.Start) {
            ShortcutLine("Ctrl N", "new note")
            ShortcutLine("Ctrl K", "search")
            ShortcutLine("Ctrl B", "toggle the sidebar")
        }
    }
}

@Composable
private fun ShortcutLine(keys: String, what: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        // Fixed-width key column so the three labels line up, but no wider than the widest hint —
        // a weighted spacer pushed the labels to the far side of an arbitrary box and read as two
        // unrelated columns.
        Box(modifier = Modifier.width(62.dp)) { KeyHint(keys) }
        Spacer(Modifier.width(12.dp))
        Text(text = what, color = BodyGrey, style = MaterialTheme.typography.bodySmall)
    }
}

/** "Saving…" while a write is queued, otherwise when the note was last written. */
private fun saveLine(status: SaveStatus, updatedAt: Long, now: Long): String = when (status) {
    SaveStatus.Pending -> "Saving…"
    is SaveStatus.Saved -> "Saved"
    SaveStatus.Idle -> relativeTime(updatedAt, now).let { if (it.isEmpty()) "" else "Edited $it" }
}
