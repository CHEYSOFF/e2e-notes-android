package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.desktop.ui.state.EditorDraft
import my.cheysoff.desktop.ui.state.SaveStatus
import my.cheysoff.desktop.ui.theme.AccentIndigo
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.EditorBodyGrey
import my.cheysoff.desktop.ui.theme.IndigoTint
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
 */
@OptIn(FlowPreview::class)
@Composable
fun NoteEditorPane(
    draft: EditorDraft?,
    folders: List<Folder>,
    saveStatus: SaveStatus,
    now: Long,
    titleFocus: FocusRequester,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onSetFolder: (String?) -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onAddChecklistItem: (after: String?) -> String,
    onChecklistTextChange: (String, String) -> Unit,
    onToggleChecklistItem: (String) -> Unit,
    onRemoveChecklistItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (draft == null) {
        EditorEmptyState(modifier)
        return
    }

    val accent = folderAccentColor(draft.folderId, folders.firstOrNull { it.id == draft.folderId }?.colorArgb)
        ?: AccentIndigo
    val richTextState = rememberRichTextState()
    var pendingChecklistFocus by remember { mutableStateOf<String?>(null) }

    // Keyed on the note id, so switching notes reloads the body and switching back does not.
    // Within one note the editor owns its text: the model never pushes `content` back down here,
    // which is what stops an autosave echo from resetting the cursor mid-word.
    LaunchedEffect(draft.id) {
        if (draft.contentFormat == NoteContentFormat.HTML) richTextState.setHtml(draft.content)
        else richTextState.setText(draft.content)

        snapshotFlow { richTextState.annotatedString }
            .drop(1) // the emission caused by the load above is not a user edit
            // toHtml() walks and re-serialises the whole document, so it is not something to do on
            // every keystroke of a long note. 120ms is below the threshold where the list's
            // snippet visibly lags the typing, and collapses a burst of typing into one pass.
            .debounce(120)
            .collect { onContentChange(richTextState.toHtml()) }
    }

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

        Column(
            modifier = Modifier
                .fillMaxSize()
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

            FormattingToolbar(richTextState, accent)

            Spacer(Modifier.height(6.dp))

            val bodyStyle = MaterialTheme.typography.bodyMedium
            BasicRichTextEditor(
                state = richTextState,
                textStyle = bodyStyle.copy(color = EditorBodyGrey),
                cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (richTextState.annotatedString.isEmpty()) {
                        Text("Start writing…", style = bodyStyle, color = PlaceholderGrey)
                    }
                    inner()
                },
            )

            ChecklistSection(
                draft = draft,
                accent = accent,
                pendingFocus = pendingChecklistFocus,
                onFocusHandled = { pendingChecklistFocus = null },
                onAdd = { after -> pendingChecklistFocus = onAddChecklistItem(after) },
                onTextChange = onChecklistTextChange,
                onToggle = onToggleChecklistItem,
                onRemove = onRemoveChecklistItem,
            )

            Spacer(Modifier.height(60.dp))
        }
    }
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

/**
 * A fixed toolbar under the rule, not the phone's floating draggable one.
 *
 * The floating toolbar exists because a phone's on-screen keyboard covers the bottom half of the
 * note and the toolbar has to get out of its way. A desktop window has no such obstruction, and a
 * draggable panel that the user has to reposition is a chore rather than a feature.
 */
@Composable
private fun FormattingToolbar(
    state: com.mohamedrejeb.richeditor.model.RichTextState,
    accent: Color,
) {
    val heading = state.currentHeadingStyle
    val span = state.currentSpanStyle
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        listOf(
            "H1" to HeadingStyle.H1,
            "H2" to HeadingStyle.H2,
            "H3" to HeadingStyle.H3,
            "Body" to HeadingStyle.Normal,
        ).forEach { (label, level) ->
            ToolbarChip(
                label = label,
                active = heading == level,
                accent = accent,
                onClick = { state.setHeadingStyle(level) },
            )
        }
        Spacer(Modifier.width(6.dp))
        ToolbarChip(
            label = "B",
            active = span.fontWeight == FontWeight.Bold,
            accent = accent,
            weight = FontWeight.Bold,
            onClick = { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
        )
        ToolbarChip(
            label = "I",
            active = span.fontStyle == FontStyle.Italic,
            accent = accent,
            italic = true,
            onClick = { state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) },
        )
        PillIconButton(
            icon = Icons.AutoMirrored.Filled.List,
            description = "Bulleted list",
            tint = BodyGrey,
            background = Color.Transparent,
            onClick = { state.toggleUnorderedList() },
        )
    }
}

@Composable
private fun ToolbarChip(
    label: String,
    active: Boolean,
    accent: Color,
    weight: FontWeight = FontWeight.Medium,
    italic: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (active) Color.White else BodyGrey,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) accent else Color.Transparent)
            .pointerHoverIcon(handCursor)
            .clickable(onClick = onClick)
            .widthIn(min = 22.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun ChecklistSection(
    draft: EditorDraft,
    accent: Color,
    pendingFocus: String?,
    onFocusHandled: () -> Unit,
    onAdd: (after: String?) -> Unit,
    onTextChange: (String, String) -> Unit,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 18.dp)) {
        draft.checklist.forEach { item ->
            val requester = remember(item.id) { FocusRequester() }
            LaunchedEffect(pendingFocus) {
                if (pendingFocus == item.id) {
                    requester.requestFocus()
                    onFocusHandled()
                }
            }
            val rowStyle = MaterialTheme.typography.bodyMedium.copy(
                color = if (item.isDone) MetaGrey else EditorBodyGrey,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .then(
                            if (item.isDone) Modifier.background(accent)
                            else Modifier.border(1.5.dp, Color(0xFF44444C), CircleShape)
                        )
                        .pointerHoverIcon(handCursor)
                        .clickable(onClickLabel = "Toggle checklist item") { onToggle(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.isDone) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = item.text,
                    onValueChange = { onTextChange(item.id, it) },
                    textStyle = rowStyle,
                    cursorBrush = SolidColor(accent),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                // Enter opens the next item, the way a list behaves everywhere else.
                                event.key == Key.Enter -> { onAdd(item.id); true }
                                // Backspace on an empty row deletes it and moves focus up, so a
                                // list can be shortened without reaching for the mouse.
                                event.key == Key.Backspace && item.text.isEmpty() -> {
                                    onRemove(item.id)
                                    true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { inner ->
                        if (item.text.isEmpty()) Text("List item", style = rowStyle, color = PlaceholderGrey)
                        inner()
                    },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(percent = 50))
                .pointerHoverIcon(handCursor)
                .clickable(onClickLabel = "Add checklist item") { onAdd(draft.checklist.lastOrNull()?.id) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MetaGrey, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = if (draft.checklist.isEmpty()) "Add a checklist" else "Add item",
                color = MetaGrey,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

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
