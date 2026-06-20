package my.cheysoff.feature_notes.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.AppBlack
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.CatBlue
import my.cheysoff.core_ui.theme.CatCrimson
import my.cheysoff.core_ui.theme.CatGreen
import my.cheysoff.core_ui.theme.CatOchre
import my.cheysoff.core_ui.theme.CatPlum
import my.cheysoff.core_ui.theme.CatRust
import my.cheysoff.core_ui.theme.CatTeal
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey

/** Minimal folder shape the shared folder UI needs, decoupled from list/editor state types. */
data class FolderRef(val id: String, val name: String, val colorArgb: Long?)

/** The selectable folder colors (mirrors the category palette). */
fun folderSwatches(): List<Color> =
    listOf(AccentIndigo, CatBlue, CatTeal, CatGreen, CatOchre, CatRust, CatCrimson, CatPlum)

private fun Color.toArgbLong(): Long = toArgb().toLong()

/** Create (initial == null) or edit a folder: name field + color swatch row (+ "Auto" = null). */
@Composable
fun FolderEditDialog(
    initial: FolderRef?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, colorArgb: Long?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var colorArgb by remember { mutableStateOf(initial?.colorArgb) }
    val canSave = name.trim().isNotEmpty()

    AlertDialog(
        containerColor = SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New folder" else "Edit folder", color = TitleGrey) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppBlack)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = TitleGrey),
                        cursorBrush = SolidColor(AccentIndigo),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (name.isEmpty()) Text("Folder name", color = BodyGrey)
                            inner()
                        },
                    )
                }
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // "Auto" = no explicit color (null).
                    Swatch(color = null, selected = colorArgb == null) { colorArgb = null }
                    folderSwatches().forEach { c ->
                        val argb = c.toArgbLong()
                        Swatch(color = c, selected = colorArgb == argb) { colorArgb = argb }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onConfirm(name, colorArgb) }) {
                Text("Save", color = if (canSave) AccentIndigo else BodyGrey)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = BodyGrey) } },
    )
}

@Composable
private fun Swatch(color: Color?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color ?: SurfaceDark)
            .then(if (color == null) Modifier.border(1.dp, BodyGrey, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
        else if (color == null) Text("A", color = BodyGrey, fontWeight = FontWeight.Bold)
    }
}

/** Pick a folder for a note (or "None" to unfile). Used by the list move action and the editor pill. */
@Composable
fun FolderChooser(
    folders: List<FolderRef>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        containerColor = SurfaceDark,
        onDismissRequest = onDismiss,
        title = { Text("Move to folder", color = TitleGrey) },
        text = {
            Column {
                FolderRow(name = "None", color = null, selected = selectedId == null) { onSelect(null) }
                folders.forEach { f ->
                    FolderRow(
                        name = f.name,
                        color = f.colorArgb?.let { Color(it.toInt()) },
                        selected = selectedId == f.id,
                    ) { onSelect(f.id) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = BodyGrey) } },
    )
}

@Composable
private fun FolderRow(name: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color ?: BodyGrey),
        )
        Spacer(Modifier.width(12.dp))
        Text(name, color = if (selected) AccentIndigo else TitleGrey, modifier = Modifier.fillMaxWidth())
    }
}
