package my.cheysoff.feature_notes.ui.single

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.ToolbarDark
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_ui.theme.folderAccentColor
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_ui.sketch.RenderedStroke
import my.cheysoff.core_ui.sketch.SketchRenderer
import my.cheysoff.feature_notes.model.single.SingleNoteIntent
import my.cheysoff.feature_notes.model.single.SingleNoteScreenState
import my.cheysoff.feature_notes.model.single.noteShareTitle
import my.cheysoff.feature_notes.ui.sketch.SketchCanvasScreen

/**
 * The note screen: the shared block editor ([BlockNoteScreen]), which holds the note's text,
 * checklists, pictures and drawings in one field and saves them as BLOCKS. A note stored as PLAIN or
 * HTML opens converted (`OpenDocument.assemble`) and is written as BLOCKS on its first edit.
 *
 * This file keeps the pieces [BlockNoteScreen] shares with the rest of the module: the top bar and
 * its overflow menu, clipboard and share, and small helpers.
 */
@Composable
fun SingleNoteScreen(
    state: SingleNoteScreenState,
    onIntent: (SingleNoteIntent) -> Unit,
    // The one seam this screen uses to read an attachment's full bytes -- see
    // `SingleNoteViewModel.attachment`'s own KDoc for why this is a direct suspend call rather
    // than another `SingleNoteIntent`: everything this screen otherwise holds is
    // `AttachmentPreview`, and routing a one-shot read through the same intent/state channel as
    // every mutation would mean parking up to 1 MiB of bytes in `SingleNoteScreenState` for the
    // one screen (the viewer) that ever needs them, rather than letting it ask for exactly the id
    // it is showing.
    loadAttachment: suspend (String) -> AttachmentData?,
    onColorMixed: (Long) -> Unit,
) {
    BlockNoteScreen(state, onIntent, loadAttachment, onColorMixed)
}


/**
 * Which sketch the full-screen canvas ([SketchCanvasScreen]) is currently editing, if any: a new
 * drawing, or an existing one opened by tapping its block. Held by [BlockNoteScreen], which shows
 * the canvas in place of the editor.
 */
internal sealed interface SketchEditTarget {
    data object New : SketchEditTarget
    data class Existing(val id: String, val sketch: Sketch) : SketchEditTarget
}

/** The same DrawScope calls [SketchCanvasScreen]'s own drawing surface uses for a [RenderedStroke]
 * -- [SketchRenderer] already did the one piece of geometry both places have to agree on (the
 * mapping and smoothing); this is just handing its output to `drawPath`/`drawCircle`. */
internal fun DrawScope.drawSketchStroke(rendered: RenderedStroke) {
    if (rendered.isDot) {
        drawCircle(color = rendered.color, radius = rendered.strokeWidthPx / 2f, center = rendered.dotCenter)
    } else {
        drawPath(
            path = rendered.path,
            color = rendered.color,
            style = DrawStyle(width = rendered.strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
internal fun EditorTopBar(
    isPinned: Boolean,
    isFavorite: Boolean,
    accent: Color,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
    onDuplicate: () -> Unit,
    onCopyText: () -> Unit,
    onShare: () -> Unit,
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
        TopIcon(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", TitleGrey) { onBack() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TopIcon(Icons.AutoMirrored.Filled.Undo, "Undo", if (canUndo) accent else BodyGrey, enabled = canUndo) { onUndo() }
            TopIcon(Icons.AutoMirrored.Filled.Redo, "Redo", if (canRedo) accent else BodyGrey, enabled = canRedo) { onRedo() }
            TopIcon(
                Icons.Outlined.PushPin,
                "Pin",
                if (isPinned) accent else BodyGrey,
            ) { onIntent(SingleNoteIntent.TogglePin) }
            TopIcon(
                if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                "Favorite",
                if (isFavorite) accent else BodyGrey,
            ) { onIntent(SingleNoteIntent.ToggleFavorite) }
            OverflowMenu(
                accent = accent,
                onDuplicate = onDuplicate,
                onCopyText = onCopyText,
                onShare = onShare,
                onIntent = onIntent,
            )
        }
    }
}

/**
 * The top bar's three-dot menu.
 *
 * Styled after the notes list's sort menu — same [SurfaceDark] ground, same 14.dp radius, same
 * [TitleGrey] labels — so the app has one menu look rather than two. It departs from that menu in
 * one place: the row icons take the note's [accent] (the folder color, indigo when unfiled),
 * because every other affordance in this editor already does.
 *
 * "Move to Trash" is the only route a user has to deleting a note; Trash then offers Restore and
 * Delete forever. It is separated from the rest by a divider and confirms before acting, because
 * it is the one row here that changes what is in the library rather than just copying it out.
 * The dialog says "Trash", not "delete", because that is what the intent does — the note keeps its
 * content and is restorable for [TrashPolicy.RETENTION_DAYS] days.
 *
 * Menu state is local because it is a property of this bar being on screen: the ViewModel is
 * retained across configuration changes and would otherwise re-open the menu after a rotation.
 */
@Composable
private fun OverflowMenu(
    accent: Color,
    onDuplicate: () -> Unit,
    onCopyText: () -> Unit,
    onShare: () -> Unit,
    onIntent: (SingleNoteIntent) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A DropdownMenu positions itself against the layout node it sits in, so it is wrapped in a
    // Box together with the button it belongs to. That is the anchoring pattern Material's own
    // docs use, and it is what lines the menu up under the three-dot icon.
    Box {
        TopIcon(Icons.Outlined.MoreVert, "More options", if (open) accent else BodyGrey) {
            open = true
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(14.dp),
        ) {
            OverflowItem(Icons.Outlined.FileCopy, "Duplicate", accent) { open = false; onDuplicate() }
            OverflowItem(Icons.Outlined.ContentCopy, "Copy text", accent) { open = false; onCopyText() }
            OverflowItem(Icons.Outlined.Share, "Share", accent) { open = false; onShare() }
            HorizontalDivider(color = ToolbarDark)
            // Tinted BodyGrey rather than the note's accent: this row is not one of the copy-out
            // actions above it and should not read as another of them.
            OverflowItem(Icons.Outlined.DeleteOutline, "Move to Trash", BodyGrey) {
                open = false
                confirmDelete = true
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { confirmDelete = false },
            title = { Text("Move to Trash?", color = TitleGrey) },
            text = {
                Text(
                    "You can restore it from Trash for the next ${TrashPolicy.RETENTION_DAYS} days.",
                    color = BodyGrey,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onIntent(SingleNoteIntent.DeleteNote)
                }) { Text("Move to Trash", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

@Composable
private fun OverflowItem(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = TitleGrey,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        // Null description on purpose: the label beside it already names the row, and a described
        // icon would have the action announced twice.
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
    )
}

/**
 * Puts [text] on the system clipboard.
 *
 * Android 13 (TIRAMISU) and up show their own confirmation UI for every clipboard write, so an app
 * toast there would be a second, redundant one — hence the version check. Below 13 nothing else
 * tells the user anything happened.
 */
internal fun copyNoteToClipboard(context: Context, text: String) {
    if (text.isEmpty()) {
        Toast.makeText(context, "Nothing to copy — this note is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Note", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

/** Hands the note to any app that accepts plain text, through the system share sheet. */
internal fun shareNote(context: Context, title: String, text: String) {
    if (text.isEmpty()) {
        Toast.makeText(context, "Nothing to share — this note is empty", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        // text/plain, and the body is plain text: a share that emitted the stored "<p>…</p>" would
        // paste raw markup into whatever the user picked.
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, noteShareTitle(title))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share note"))
}

@Composable
private fun TopIcon(icon: ImageVector, desc: String, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint)
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Editor accent = the folder's chosen color (or the hash fallback), or default indigo when it has no folder. */
internal fun editorAccent(folderId: String?, folders: List<Folder>): Color {
    if (folderId.isNullOrBlank()) return AccentIndigo
    val colorArgb = folders.find { it.id == folderId }?.colorArgb
    return folderAccentColor(folderId, colorArgb) ?: AccentIndigo
}

internal fun metaLine(updatedAt: Long, words: Int): String {
    val wordLabel = if (words == 1) "1 word" else "$words words"
    val rel = relativeTime(updatedAt)
    return if (rel.isEmpty()) wordLabel else "Edited $rel · $wordLabel"
}

/** Counts words without allocating a list/regex (single pass over the chars). */
internal fun countWords(text: String): Int {
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
