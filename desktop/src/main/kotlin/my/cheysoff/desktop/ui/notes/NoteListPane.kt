package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.cheysoff.desktop.ui.state.FolderChipUi
import my.cheysoff.desktop.ui.state.NoteListContent
import my.cheysoff.desktop.ui.state.NoteRowUi
import my.cheysoff.desktop.ui.theme.AccentIndigo
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.IndigoTint
import my.cheysoff.desktop.ui.theme.MetaGrey
import my.cheysoff.desktop.ui.theme.SurfaceDark
import my.cheysoff.desktop.ui.theme.TitleGrey
import my.cheysoff.desktop.ui.theme.UncategorizedEdge
import my.cheysoff.desktop.ui.theme.folderAccentColor

/**
 * The left pane: folder chips over a scrolling list, split into Pinned and Recent.
 *
 * It is a flat [LazyColumn] rather than the phone's staggered grid + swipeable pinned pager. The
 * pager exists on the phone because a pinned note has to earn a whole screen width; here the pane
 * is a fixed narrow column beside an editor, and a horizontally-swipeable widget inside a
 * vertically-scrolling sidebar is a gesture nobody makes with a mouse.
 */
@Composable
fun NoteListPane(
    content: NoteListContent,
    chips: List<FolderChipUi>,
    selectedFolderId: String?,
    selectedNoteId: String?,
    loaded: Boolean,
    now: Long,
    onSelectFolder: (String?) -> Unit,
    onSelectNote: (String) -> Unit,
    onNewNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        FolderChipRow(chips, selectedFolderId, onSelectFolder)

        if (loaded && content.isEmpty) {
            EmptyListMessage(hasFolderFilter = selectedFolderId != null, onNewNote = onNewNote)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (content.pinned.isNotEmpty()) {
                item(key = "label-pinned") { SectionLabel("Pinned") }
                items(content.pinned, key = { it.id }) { row ->
                    NoteRow(row, row.id == selectedNoteId, now) { onSelectNote(row.id) }
                }
            }
            if (content.recent.isNotEmpty()) {
                item(key = "label-recent") { SectionLabel("Recent") }
                items(content.recent, key = { it.id }) { row ->
                    NoteRow(row, row.id == selectedNoteId, now) { onSelectNote(row.id) }
                }
            }
        }
    }
}

@Composable
private fun FolderChipRow(
    chips: List<FolderChipUi>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            FolderChip(chip, selected = chip.id == selectedFolderId) { onSelect(chip.id) }
        }
    }
}

@Composable
private fun FolderChip(chip: FolderChipUi, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> AccentIndigo
        hovered -> Color(0xFF212127)
        else -> SurfaceDark
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .pointerHoverIcon(handCursor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = chip.name,
            color = if (selected) Color(0xFFE0DDF2) else Color(0xFF8A8A8A),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        )
        // The count is what a sidebar has room for and a phone chip strip does not. It also makes
        // an empty folder visibly empty instead of a chip that filters to a blank list.
        Spacer(Modifier.width(5.dp))
        Text(
            text = chip.count.toString(),
            color = if (selected) Color(0xFFB9B2E8) else MetaGrey,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * One note in the list.
 *
 * The two treatments are the ones from the Android note card, kept because they encode a rule the
 * app already made: a pinned or favorited note is drawn as a solid block of its folder colour,
 * everything else is a dark card with the colour showing along its left edge.
 */
@Composable
private fun NoteRow(row: NoteRowUi, selected: Boolean, now: Long, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = folderAccentColor(row.folderId, row.folderColorArgb)
    val filled = row.isPinned || row.isFavorite
    val shape = RoundedCornerShape(12.dp)

    val background = when {
        filled -> accent ?: AccentIndigo
        selected -> Color(0xFF202027)
        hovered -> Color(0xFF1B1B20)
        else -> SurfaceDark
    }
    val onFill = filled
    val titleColor = if (onFill) Color.White.copy(alpha = 0.94f) else TitleGrey
    val bodyColor = if (onFill) Color.White.copy(alpha = 0.6f) else BodyGrey
    val metaColor = if (onFill) Color.White.copy(alpha = 0.5f) else MetaGrey

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // Selection is a ring rather than a fill because a filled row already owns its
            // background; a "selected" colour would have nowhere to go on a pinned note.
            .then(if (selected) Modifier.border(1.5.dp, IndigoTint, shape) else Modifier)
            .background(background)
            .pointerHoverIcon(handCursor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!filled) {
                // The colour edge, tapering into the rounded corners exactly as on the phone card.
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(52.dp)
                        .background(accent ?: UncategorizedEdge)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (filled) 12.dp else 9.dp, top = 9.dp, end = 8.dp, bottom = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.isPinned) {
                        PinGlyph(tint = if (onFill) Color.White.copy(alpha = 0.75f) else IndigoTint)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = row.title.ifBlank { "Untitled" },
                        color = titleColor,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.snippet.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = row.snippet,
                        color = bodyColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = relativeTime(row.updatedAt, now)
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            color = metaColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                    if (row.checklistTotal > 0) {
                        Spacer(Modifier.width(10.dp))
                        ChecklistProgress(row.checklistDone, row.checklistTotal, onColor = onFill)
                    }
                }
            }
            // The `▸` from the approved sketch: which row the editor on the right is showing.
            if (selected) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (onFill) Color.White.copy(alpha = 0.8f) else IndigoTint,
                    modifier = Modifier.padding(end = 6.dp).size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyListMessage(hasFolderFilter: Boolean, onNewNote: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (hasFolderFilter) "Nothing filed here yet." else "No notes yet.",
            color = TitleGrey,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Press",
                color = BodyGrey,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(6.dp))
            KeyHint("Ctrl N", modifier = Modifier.pointerHoverIcon(handCursor).clickable(onClick = onNewNote))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "to start one.",
                color = BodyGrey,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
