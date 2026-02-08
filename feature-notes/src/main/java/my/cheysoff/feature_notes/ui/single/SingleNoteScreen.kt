package my.cheysoff.feature_notes.ui.single

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.NotesTheme

@Composable
fun SingleNoteScreen() {
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { ScreenTopBar() },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        NoteEditor(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = spacing.screenHorizontal)
        )
    }
}

@Composable
fun NoteEditor(modifier: Modifier = Modifier) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Column(modifier = modifier) {
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
            .padding(horizontal = spacing.topBarHorizontal),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left
        BackButton()

        // Middle
        Row(verticalAlignment = Alignment.CenterVertically) {
            UndoButton()
            RedoButton()
        }

        // Right
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinButton()
            MoreButton()
        }
    }
}

@Composable
private fun BackButton() {
    TextButton(
        onClick = { /*TODO*/ },
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos, contentDescription = "Back")
        Text(
            text = "Back",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun UndoButton() {
    IconButton(
        onClick = { /*TODO*/ },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
    }
}

@Composable
private fun RedoButton() {
    IconButton(
        onClick = { /*TODO*/ },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
    }
}

@Composable
private fun PinButton() {
    IconButton(
        onClick = { /*TODO*/ },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(imageVector = Icons.Outlined.PushPin, contentDescription = "Pin")
    }
}

@Composable
private fun MoreButton() {
    IconButton(
        onClick = { /*TODO*/ },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "More")
    }
}

@Preview(showBackground = true)
@Composable
private fun SingleNoteScreenPreview() {
    NotesTheme {
        SingleNoteScreen()
    }
}
