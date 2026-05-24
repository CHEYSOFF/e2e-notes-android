package my.cheysoff.feature_notes.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.UncategorizedEdge
import my.cheysoff.core_ui.theme.colorForCategory
import my.cheysoff.feature_notes.model.list.BottomBarItem
import my.cheysoff.feature_notes.model.list.FolderPreviewUi
import my.cheysoff.feature_notes.model.list.HeaderLineUi
import my.cheysoff.feature_notes.model.list.NotePreviewUi
import my.cheysoff.feature_notes.model.list.NotesListIntent
import my.cheysoff.feature_notes.model.list.NotesListScreenState

@Composable
fun NotesListScreen(
    state: NotesListScreenState,
    onIntent: (NotesListIntent) -> Unit
) {
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onIntent(NotesListIntent.AddNoteClicked) },
                shape = CircleShape,
                containerColor = AccentIndigo,
                contentColor = Color(0xFFE8E6F5),
                modifier = Modifier
                    .size(spacing.fabSize)
                    .offset(y = spacing.fabOverlapOffset)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add note",
                    modifier = Modifier.size(spacing.fabIconSize)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        bottomBar = { FloatingNavBar(state.selectedBottomBarItem, onIntent) }
    ) { innerPadding ->
        LazyVerticalStaggeredGrid(
            modifier = Modifier.fillMaxSize(),
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + spacing.screenVertical,
                start = innerPadding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current) + spacing.screenHorizontal,
                end = innerPadding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current) + spacing.screenHorizontal,
                bottom = innerPadding.calculateBottomPadding() + spacing.screenVertical
            ),
            verticalItemSpacing = spacing.interItemSpacingVertical,
            horizontalArrangement = Arrangement.spacedBy(spacing.interItemSpacingHorizontal)
        ) {
            item(span = StaggeredGridItemSpan.FullLine, contentType = "header") {
                HeaderLine(state.headerLine)
            }
            item(span = StaggeredGridItemSpan.FullLine, contentType = "chips") {
                FolderChips(
                    folders = state.folderPreviews,
                    selectedFolderId = state.selectedFolderId,
                    onAllClick = { state.selectedFolderId?.let { onIntent(NotesListIntent.FolderClicked(it)) } },
                    onFolderClick = { onIntent(NotesListIntent.FolderClicked(it)) },
                )
            }

            if (state.pinnedPreviews.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, contentType = "pinned_label") {
                    SectionLabel("Pinned")
                }
                item(span = StaggeredGridItemSpan.FullLine, contentType = "pinned_pager") {
                    PinnedPager(state.pinnedPreviews) { onIntent(NotesListIntent.NoteClicked(it)) }
                }
            }

            if (state.notePreviews.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, contentType = "recent_label") {
                    SectionLabel("Recent")
                }
            }
            items(
                items = state.notePreviews,
                key = { it.id },
                contentType = { "note" }
            ) { note ->
                NoteCard(note) { onIntent(NotesListIntent.NoteClicked(note.id)) }
            }
        }
    }
}

@Composable
private fun HeaderLine(header: HeaderLineUi?) {
    val sw = LocalConfiguration.current.screenWidthDp
    if (header == null) {
        Text(
            text = "Mañana",
            color = Color(0xFF888888),
            fontWeight = FontWeight.Bold,
            fontSize = (sw * 0.04f).sp,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        return
    }
    val headerSize = (sw * 0.092f).sp
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Light)) {
                append(header.prefix)
            }
            append("\n")
            withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) {
                append(header.accent)
            }
        },
        style = MaterialTheme.typography.titleLarge.copy(
            fontSize = headerSize,
            lineHeight = headerSize * 1.05f,
            letterSpacing = (-0.6).sp,
        ),
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun FolderChips(
    folders: List<FolderPreviewUi>,
    selectedFolderId: String?,
    onAllClick: () -> Unit,
    onFolderClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Chip(text = "All", selected = selectedFolderId == null, onClick = onAllClick)
        folders.forEach { folder ->
            Chip(
                text = folder.name,
                selected = selectedFolderId == folder.id,
                onClick = { onFolderClick(folder.id) },
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text,
        color = if (selected) Color(0xFFE0DDF2) else Color(0xFF8A8A8A),
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = (sw * 0.038f).sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) AccentIndigo else SurfaceDark)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    val sw = LocalConfiguration.current.screenWidthDp
    Text(
        text = text.uppercase(),
        color = Color(0xFF5E5E62),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = (sw * 0.032f).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 10.dp),
    )
}

@Composable
private fun PinnedPager(pinned: List<NotePreviewUi>, onClick: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pinned.size })
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 10.dp,
            contentPadding = PaddingValues(end = if (pinned.size > 1) 34.dp else 0.dp),
        ) { page ->
            PinnedCard(pinned[page]) { onClick(pinned[page].id) }
        }
        if (pinned.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pinned.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 2.5.dp)
                            .height(6.dp)
                            .width(if (active) 16.dp else 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (active) IndigoTint else Color(0xFF2E2E34))
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedCard(note: NotePreviewUi, onClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    val color = noteColor(note.folderId) ?: AccentIndigo
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        onClick = onClick,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = (sw * 0.05f).sp, fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note.content,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.036f).sp, lineHeight = (sw * 0.05f).sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = relativeTime(note.updatedAt)
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = meta,
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (sw * 0.028f).sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(14.dp),
            )
        }
    }
}

@Composable
private fun NoteCard(note: NotePreviewUi, onClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    val titleSize = (sw * 0.043f).sp
    val bodySize = (sw * 0.034f).sp
    val bodyLine = (sw * 0.046f).sp
    val base = noteColor(note.folderId)
    val filled = note.isPinned || note.isFavorite

    if (filled) {
        val color = base ?: AccentIndigo
        Card(
            modifier = Modifier.heightIn(max = 300.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = color),
            onClick = onClick,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = titleSize, fontWeight = FontWeight.Medium),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = note.content,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = bodySize, lineHeight = bodyLine),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        val edge = base ?: UncategorizedEdge
        Card(
            modifier = Modifier.heightIn(max = 300.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            onClick = onClick,
        ) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(edge)
                )
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        color = TitleGrey,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = titleSize, fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = note.content,
                        color = BodyGrey,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = bodySize, lineHeight = bodyLine),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingNavBar(
    selected: BottomBarItem,
    onIntent: (NotesListIntent) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(SurfaceDark),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            NavIcon(Icons.Default.Description, selected == BottomBarItem.ALL_NOTES) {
                onIntent(NotesListIntent.AllNotesClicked)
            }
            NavIcon(Icons.Default.Search, selected == BottomBarItem.SEARCH) {
                onIntent(NotesListIntent.SearchClicked)
            }
            Spacer(Modifier.width(56.dp)) // gap for the centered FAB
            NavIcon(Icons.Default.CalendarToday, selected == BottomBarItem.CALENDAR) {
                onIntent(NotesListIntent.CalendarClicked)
            }
            NavIcon(Icons.Default.Person, selected == BottomBarItem.PROFILE) {
                onIntent(NotesListIntent.ProfileClicked)
            }
        }
    }
}

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) IndigoTint else Color(0xFF5E5E5E),
        )
    }
}

/** Category color for a note, or null when it has no folder (caller picks the neutral/accent fallback). */
private fun noteColor(folderId: String?): Color? =
    if (folderId.isNullOrBlank()) null else colorForCategory(folderId)

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
