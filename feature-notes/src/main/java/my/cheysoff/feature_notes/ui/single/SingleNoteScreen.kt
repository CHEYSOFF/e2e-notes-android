package my.cheysoff.feature_notes.ui.single

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) focusManager.clearFocus()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = { EditorTopBar(isPinned = state.isPinned, accent = accent, onIntent = onIntent) },
        floatingActionButton = { FormattingToolbar(accent = accent) },
        floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
        containerColor = AppBlack,
    ) { paddingValues ->
        NoteEditor(
            state = state,
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
    accent: Color,
    onIntent: (SingleNoteIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val sw = LocalConfiguration.current.screenWidthDp
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scrollState = rememberScrollState()
    val contentFocus = remember { FocusRequester() }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Light,
        fontSize = (sw * 0.072f).sp,
        lineHeight = (sw * 0.08f).sp,
    )
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (sw * 0.042f).sp)

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                contentFocus.requestFocus()
                keyboardController?.show()
            }
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

        // Meta line: relative edit time + live word count.
        Text(
            text = metaLine(state.updatedAt, state.content),
            color = Color(0xFF5E5E62),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.03f).sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
        )

        BasicTextField(
            value = state.content,
            onValueChange = { onIntent(SingleNoteIntent.ContentChanged(it)) },
            textStyle = bodyStyle.copy(color = Color(0xFFB9B9BD)),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(contentFocus),
            decorationBox = { inner ->
                if (state.content.isEmpty()) {
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
            TopIcon(Icons.AutoMirrored.Filled.Undo, "Undo", BodyGrey) { /* TODO (Phase 6) */ }
            TopIcon(Icons.AutoMirrored.Filled.Redo, "Redo", BodyGrey) { /* TODO (Phase 6) */ }
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
private fun FormattingToolbar(accent: Color) {
    var showStyles by remember { mutableStateOf(false) }
    var selectedStyle by remember { mutableStateOf("Body") }
    val inactive = Color(0xFF9A9A9E)
    val border = Color(0xFF24242C)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showStyles) {
            StylePopover(accent = accent, selected = selectedStyle) {
                selectedStyle = it
                showStyles = false
            }
            Spacer(Modifier.height(10.dp))
        }
        // Fixed floating pill above the keyboard.
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
            ToolIcon(Icons.Outlined.FormatBold, "Bold", inactive) { /* TODO (Phase 6) */ }
            ToolIcon(Icons.Outlined.FormatItalic, "Italic", inactive) { /* TODO (Phase 6) */ }
            ToolIcon(Icons.AutoMirrored.Outlined.FormatListBulleted, "List", inactive) { /* TODO (Phase 6) */ }
            ToolIcon(Icons.Outlined.Checklist, "Checklist", inactive) { /* TODO (Phase 7) */ }
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
private fun StylePopover(accent: Color, selected: String, onSelect: (String) -> Unit) {
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
            val isSelected = label == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(label) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) accent else TitleGrey,
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

private fun metaLine(updatedAt: Long, content: String): String {
    val words = content.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val wordLabel = if (words == 1) "1 word" else "$words words"
    val rel = relativeTime(updatedAt)
    return if (rel.isEmpty()) wordLabel else "Edited $rel · $wordLabel"
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
