package my.cheysoff.feature_notes.ui.single

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import java.util.UUID
import kotlinx.coroutines.flow.drop
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark
import my.cheysoff.core_ui.theme.colorForCategory
import my.cheysoff.feature_notes.model.looksLikeHtml
import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleNoteScreen(
    state: SingleNoteScreenState,
    onIntent: (SingleNoteIntent) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val isImeVisible = WindowInsets.isImeVisible
    val accent = editorAccent(state.folderId)
    val richTextState = rememberRichTextState()
    // Id of a checklist item that should grab focus once it appears (set when an item is added,
    // or when one above is removed). Hoisted here so the toolbar FAB and the section can both set it.
    var focusItemId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) focusManager.clearFocus()
    }

    // Seed the editor from the stored content once the note loads, then push every subsequent
    // (user) change back as HTML. drop(1) skips the emission caused by seeding, so merely opening a
    // note doesn't trigger a save; a later edit that reverts to the original content is a fresh
    // emission and is still forwarded. The note always exists before this screen opens (it is
    // created/saved before navigation), so isLoaded reliably flips and edits are never dropped.
    //
    // Stored content is HTML for rich-editor notes, but legacy notes are raw plain text; feeding
    // such text to setHtml would parse stray "<"/">" as tags and drop characters, so plain text
    // goes through setText instead.
    LaunchedEffect(state.isLoaded) {
        if (state.isLoaded) {
            richTextState.config.listIndent = 18
            if (state.content.looksLikeHtml()) {
                richTextState.setHtml(state.content)
            } else {
                richTextState.setText(state.content)
            }
            snapshotFlow { richTextState.annotatedString }
                .drop(1)
                .collect { onIntent(SingleNoteIntent.ContentChanged(richTextState.toHtml())) }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = { EditorTopBar(isPinned = state.isPinned, accent = accent, onIntent = onIntent) },
        floatingActionButton = {
            FormattingToolbar(
                richTextState = richTextState,
                accent = accent,
                onAddChecklistItem = {
                    val id = UUID.randomUUID().toString()
                    focusItemId = id
                    onIntent(SingleNoteIntent.ChecklistItemAdded(id, null))
                },
            )
        },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = AppBlack,
    ) { paddingValues ->
        NoteEditor(
            state = state,
            richTextState = richTextState,
            accent = accent,
            focusItemId = focusItemId,
            onSetFocusItem = { focusItemId = it },
            onIntent = onIntent,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        )
    }
}

@Composable
private fun NoteEditor(
    state: SingleNoteScreenState,
    richTextState: RichTextState,
    accent: Color,
    focusItemId: String?,
    onSetFocusItem: (String?) -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val sw = LocalConfiguration.current.screenWidthDp
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Normal,
        fontSize = (sw * 0.088f).sp,
        lineHeight = (sw * 0.098f).sp,
    )
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (sw * 0.042f).sp)
    // Word count over the plain text (not the HTML), recomputed only when the text changes.
    val wordCount = remember(richTextState.annotatedString) {
        countWords(richTextState.annotatedString.text)
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = spacing.screenHorizontal),
    ) {
        BasicTextField(
            value = state.title,
            onValueChange = { onIntent(SingleNoteIntent.TitleChanged(it)) },
            textStyle = titleStyle.copy(color = TitleGrey),
            cursorBrush = SolidColor(accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (state.title.isEmpty()) {
                    Text("Title", style = titleStyle, color = Color(0xFF4A4A50))
                }
                inner()
            },
        )

        Text(
            text = metaLine(state.updatedAt, wordCount),
            color = Color(0xFF5E5E62),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.03f).sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )

        BasicRichTextEditor(
            state = richTextState,
            textStyle = bodyStyle.copy(color = Color(0xFFB9B9BD)),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (richTextState.annotatedString.isEmpty()) {
                    Text("Start writing…", style = bodyStyle, color = Color(0xFF4A4A50))
                }
                inner()
            },
        )

        ChecklistSection(
            items = state.checklist,
            accent = accent,
            textStyle = bodyStyle,
            focusItemId = focusItemId,
            onSetFocusItem = onSetFocusItem,
            onIntent = onIntent,
        )

        Spacer(modifier = Modifier.height(140.dp))
    }
}

@Composable
private fun ChecklistSection(
    items: List<ChecklistItem>,
    accent: Color,
    textStyle: TextStyle,
    focusItemId: String?,
    onSetFocusItem: (String?) -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
) {
    if (items.isEmpty()) return

    Column(modifier = Modifier.padding(top = 22.dp)) {
        items.forEachIndexed { index, item ->
            val requester = remember(item.id) { FocusRequester() }
            // When this item is the pending focus target, grab focus once and clear the request.
            LaunchedEffect(focusItemId) {
                if (focusItemId == item.id) {
                    requester.requestFocus()
                    onSetFocusItem(null)
                }
            }
            val rowStyle = textStyle.copy(
                color = if (item.isDone) Color(0xFF5E5E62) else Color(0xFFB9B9BD),
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .then(
                            if (item.isDone) Modifier.background(accent)
                            else Modifier.border(2.dp, Color(0xFF44444C), CircleShape)
                        )
                        .clickable { onIntent(SingleNoteIntent.ChecklistItemToggled(item.id)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Done",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = item.text,
                    onValueChange = { onIntent(SingleNoteIntent.ChecklistItemTextChanged(item.id, it)) },
                    textStyle = rowStyle,
                    cursorBrush = SolidColor(accent),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = {
                        val id = UUID.randomUUID().toString()
                        onSetFocusItem(id)
                        onIntent(SingleNoteIntent.ChecklistItemAdded(id, item.id))
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requester)
                        // Backspace on an empty item removes it and moves focus to the item above.
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && item.text.isEmpty()) {
                                items.getOrNull(index - 1)?.let { onSetFocusItem(it.id) }
                                onIntent(SingleNoteIntent.ChecklistItemRemoved(item.id))
                                true
                            } else {
                                false
                            }
                        },
                    decorationBox = { inner ->
                        if (item.text.isEmpty()) {
                            Text("List item", style = rowStyle, color = Color(0xFF4A4A50))
                        }
                        inner()
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    isPinned: Boolean,
    accent: Color,
    onIntent: (SingleNoteIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal - 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopIcon(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", TitleGrey) {
            onIntent(SingleNoteIntent.BackClicked)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TopIcon(Icons.AutoMirrored.Filled.Undo, "Undo", BodyGrey) { /* TODO: undo history */ }
            TopIcon(Icons.AutoMirrored.Filled.Redo, "Redo", BodyGrey) { /* TODO: redo history */ }
            TopIcon(
                Icons.Outlined.PushPin,
                "Pin",
                if (isPinned) accent else BodyGrey,
            ) { onIntent(SingleNoteIntent.TogglePin) }
            TopIcon(Icons.Outlined.MoreVert, "More", BodyGrey) { onIntent(SingleNoteIntent.MoreClicked) }
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint)
    }
}

@Composable
private fun FormattingToolbar(
    richTextState: RichTextState,
    accent: Color,
    onAddChecklistItem: () -> Unit,
) {
    var showStyles by remember { mutableStateOf(false) }
    val inactive = Color(0xFF9A9A9E)
    val border = Color(0xFF24242C)

    val current = richTextState.currentSpanStyle
    val isBold = (current.fontWeight?.weight ?: 0) >= FontWeight.Bold.weight
    val isItalic = current.fontStyle == FontStyle.Italic
    val isList = richTextState.isUnorderedList
    val activeHeading = richTextState.currentHeadingStyle

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showStyles) {
            StylePopover(accent = accent, active = activeHeading) { level ->
                richTextState.setHeadingStyle(level)
                showStyles = false
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier
                .shadow(12.dp, RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .background(ToolbarDark)
                .border(1.dp, border, RoundedCornerShape(percent = 50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(Icons.Outlined.TextFields, "Text style", if (showStyles) accent else inactive) {
                showStyles = !showStyles
            }
            ToolIcon(Icons.Outlined.FormatBold, "Bold", if (isBold) accent else inactive) {
                richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            ToolIcon(Icons.Outlined.FormatItalic, "Italic", if (isItalic) accent else inactive) {
                richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            ToolIcon(Icons.AutoMirrored.Outlined.FormatListBulleted, "List", if (isList) accent else inactive) {
                richTextState.toggleUnorderedList()
            }
            ToolIcon(Icons.Outlined.Checklist, "Checklist", inactive) { onAddChecklistItem() }
        }
    }
}

/**
 * Heading levels offered by the "Aa" menu, applied as native paragraph headings
 * (rc14 `setHeadingStyle`): exclusive per paragraph and persisted as semantic <h1>..<h3> HTML.
 * The third value is a compressed preview size for the menu row. H4–H6 are intentionally
 * omitted — H4 renders ~like H3 and H5/H6 fall below body size.
 */
private val headingOptions = listOf(
    Triple("H1", HeadingStyle.H1, 19.sp),
    Triple("H2", HeadingStyle.H2, 16.sp),
    Triple("H3", HeadingStyle.H3, 14.sp),
    Triple("Body", HeadingStyle.Normal, 13.sp),
)

@Composable
private fun ToolIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StylePopover(accent: Color, active: HeadingStyle, onSelect: (HeadingStyle) -> Unit) {
    Column(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(ToolbarDark)
            .border(1.dp, Color(0xFF24242C), RoundedCornerShape(16.dp))
            .width(170.dp)
            .padding(6.dp),
    ) {
        headingOptions.forEach { (label, level, previewSize) ->
            val isActive = level == active
            Text(
                text = label,
                color = if (isActive) accent else TitleGrey,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = previewSize,
                    fontWeight = if (level != HeadingStyle.Normal) FontWeight.Bold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (isActive) Modifier.background(accent.copy(alpha = 0.15f)) else Modifier)
                    .clickable { onSelect(level) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** Editor accent = the note's category color, or the default indigo when it has no folder. */
private fun editorAccent(folderId: String?): Color =
    if (folderId.isNullOrBlank()) AccentIndigo else colorForCategory(folderId)

private fun metaLine(updatedAt: Long, words: Int): String {
    val wordLabel = if (words == 1) "1 word" else "$words words"
    val rel = relativeTime(updatedAt)
    return if (rel.isEmpty()) wordLabel else "Edited $rel · $wordLabel"
}

/** Counts words without allocating a list/regex (single pass over the chars). */
private fun countWords(text: String): Int {
    var count = 0
    var inWord = false
    for (c in text) {
        if (c.isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
}

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return ""
    val min = (System.currentTimeMillis() - ts) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> "${min / 1440}d ago"
    }
}
