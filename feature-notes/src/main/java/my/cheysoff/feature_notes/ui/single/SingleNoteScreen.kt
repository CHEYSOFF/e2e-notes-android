package my.cheysoff.feature_notes.ui.single

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import my.cheysoff.core_ui.theme.LocalRadii
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.NotesTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleNoteScreen() {
    val spacing = LocalSpacing.current
    val focusManager = LocalFocusManager.current
    val isImeVisible = WindowInsets.isImeVisible

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible) {
            focusManager.clearFocus()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = { ScreenTopBar() },
        bottomBar = { ScreenBottomBar() },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        NoteEditor(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = spacing.screenHorizontal)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                }
        )
    }
}

@Composable
fun NoteEditor(modifier: Modifier = Modifier) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier.focusable()
    ) {
        TextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Title",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            },
            textStyle = MaterialTheme.typography.titleMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        TextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Note",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            )
        )
    }
}

@Composable
private fun ScreenTopBar() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left
        BackButton()

        // Middle
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteIconButton(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "Undo",
                onClick = { /*TODO*/ }
            )
            NoteIconButton(
                icon = Icons.AutoMirrored.Filled.Redo,
                contentDescription = "Redo",
                onClick = { /*TODO*/ }
            )
        }

        // Right
        Row(verticalAlignment = Alignment.CenterVertically) {
            NoteIconButton(
                icon = Icons.Outlined.PushPin,
                contentDescription = "Pin",
                onClick = { /*TODO*/ }
            )
            NoteIconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "More",
                onClick = { /*TODO*/ }
            )
        }
    }
}

@Composable
private fun ScreenBottomBar() {
    val spacing = LocalSpacing.current
    val radii = LocalRadii.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = spacing.screenHorizontal,
                vertical = spacing.buttonContentPadding
            ),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(radii.max)
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteIconButton(
                icon = Icons.Outlined.TextFields,
                contentDescription = "Typography",
                onClick = { /*TODO*/ }
            )
            NoteIconButton(
                icon = Icons.AutoMirrored.Outlined.List,
                contentDescription = "List",
                onClick = { /*TODO*/ }
            )
            NoteIconButton(
                icon = Icons.Outlined.StrikethroughS,
                contentDescription = "Strikethrough",
                onClick = { /*TODO*/ }
            )
            NoteIconButton(
                icon = Icons.Outlined.FormatUnderlined,
                contentDescription = "Underline",
                onClick = { /*TODO*/ }
            )
            NoteIconButton(
                icon = Icons.Outlined.FormatItalic,
                contentDescription = "Italic",
                onClick = { /*TODO*/ }
            )
        }
    }
}

@Composable
private fun NoteIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint)
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun BackButton() {
    TextButton(
        onClick = { /*TODO*/ }, colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos, contentDescription = "Back")
        Text(
            text = "Back", style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SingleNoteScreenPreview() {
    NotesTheme {
        SingleNoteScreen()
    }
}
