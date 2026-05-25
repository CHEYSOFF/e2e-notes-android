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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlinx.coroutines.flow.drop
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark
import my.cheysoff.core_ui.theme.colorForCategory
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

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) focusManager.clearFocus()
    }

    // Initialize the editor from the stored HTML once the note is loaded, then push every
    // subsequent (user) change back as HTML. drop(1) skips the emission caused by setHtml,
    // so merely opening a note doesn't trigger a save.
    LaunchedEffect(state.isLoaded) {
        if (state.isLoaded) {
            richTextState.setHtml(state.content)
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
        floatingActionButton = { FormattingToolbar(richTextState = richTextState, accent = accent) },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = AppBlack,
    ) { paddingValues ->
        NoteEditor(
            state = state,
            richTextState = richTextState,
            accent = accent,
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
    onIntent: (SingleNoteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val sw = LocalConfiguration.current.screenWidthDp
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Light,
        fontSize = (sw * 0.072f).sp,
        lineHeight = (sw * 0.08f).sp,
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

        Spacer(modifier = Modifier.height(140.dp))
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
private fun FormattingToolbar(richTextState: RichTextState, accent: Color) {
    var showStyles by remember { mutableStateOf(false) }
    val inactive = Color(0xFF9A9A9E)
    val border = Color(0xFF24242C)

    val current = richTextState.currentSpanStyle
    val isBold = (current.fontWeight?.weight ?: 0) >= FontWeight.Bold.weight
    val isItalic = current.fontStyle == FontStyle.Italic
    val isList = richTextState.isUnorderedList

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showStyles) {
            StylePopover(accent = accent) { style ->
                applyTextStyle(richTextState, style)
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
            ToolIcon(Icons.Outlined.Checklist, "Checklist", inactive) { /* TODO (Phase 7) */ }
        }
    }
}

private val TitleSpan = SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
private val HeadingSpan = SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)

/** Best-effort heading styles via span size+weight. "Body" clears the heading spans. */
private fun applyTextStyle(state: RichTextState, label: String) {
    when (label) {
        "Title" -> state.toggleSpanStyle(TitleSpan)
        "Heading" -> state.toggleSpanStyle(HeadingSpan)
        "Body" -> {
            state.removeSpanStyle(TitleSpan)
            state.removeSpanStyle(HeadingSpan)
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StylePopover(accent: Color, onSelect: (String) -> Unit) {
    val options = listOf("Title" to "800", "Heading" to "700", "Body" to "400")
    Column(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(ToolbarDark)
            .border(1.dp, Color(0xFF24242C), RoundedCornerShape(16.dp))
            .width(170.dp)
            .padding(6.dp),
    ) {
        options.forEach { (label, weight) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelect(label) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = TitleGrey,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = weight,
                    color = Color(0xFF5E5E62),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                )
            }
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
