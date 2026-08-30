package my.cheysoff.feature_notes.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_ui.theme.AccentIndigo
import my.cheysoff.core_ui.theme.BodyGrey
import my.cheysoff.core_ui.theme.IndigoTint
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.core_ui.theme.OutlineDark
import my.cheysoff.core_ui.theme.SurfaceDark
import my.cheysoff.core_ui.theme.TitleGrey
import my.cheysoff.core_ui.theme.UncategorizedEdge
import my.cheysoff.core_ui.theme.folderAccentColor
import my.cheysoff.feature_notes.model.trash.TrashEntryKind
import my.cheysoff.feature_notes.model.trash.TrashEntryUi
import my.cheysoff.feature_notes.model.trash.TrashIntent
import my.cheysoff.feature_notes.model.trash.TrashScreenState

/**
 * Trash: deleted notes and folders, newest first, each with Restore and Delete forever.
 *
 * Visually a sibling of the notes list — black canvas, the same two-line header, the same
 * edge-accented SurfaceDark card as an unpinned note — but deliberately quieter: no FAB, no nav
 * bar, no filled cards. Nothing here is a place to work, so nothing competes for attention.
 */
@Composable
fun TrashScreen(
    state: TrashScreenState,
    onIntent: (TrashIntent) -> Unit,
) {
    val spacing = LocalSpacing.current

    // The row awaiting a "delete forever" confirmation. Held here rather than in the ViewModel: it
    // is a property of this screen being open, and it must not survive the row disappearing.
    var purgeTarget by remember { mutableStateOf<TrashEntryUi?>(null) }

    BackHandler { onIntent(TrashIntent.BackClicked) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TrashTopBar(onBack = { onIntent(TrashIntent.BackClicked) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + spacing.l,
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.interItemSpacingVertical),
        ) {
            item(contentType = "header") { TrashHeader() }

            // isLoading is checked before the empty state so "Trash is empty" is never shown as a
            // guess: until the first database emission arrives, neither branch is rendered.
            if (!state.isLoading && state.entries.isEmpty()) {
                item(contentType = "empty") { EmptyTrash() }
            }

            items(
                items = state.entries,
                key = { "${it.kind}:${it.id}" },
                contentType = { "entry" },
            ) { entry ->
                TrashCard(
                    entry = entry,
                    onRestore = { onIntent(TrashIntent.Restore(entry.id, entry.kind)) },
                    onDeleteForever = { purgeTarget = entry },
                )
            }
        }
    }

    purgeTarget?.let { entry ->
        val what = if (entry.kind == TrashEntryKind.FOLDER) "folder" else "note"
        AlertDialog(
            containerColor = SurfaceDark,
            onDismissRequest = { purgeTarget = null },
            title = { Text("Delete forever?", color = TitleGrey) },
            text = {
                Text(
                    "\"${entry.title.ifBlank { "Untitled" }}\" will be erased. " +
                        "This $what cannot be recovered afterwards.",
                    color = BodyGrey,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(TrashIntent.DeleteForever(entry.id, entry.kind))
                    purgeTarget = null
                }) { Text("Delete", color = AccentIndigo) }
            },
            dismissButton = {
                TextButton(onClick = { purgeTarget = null }) { Text("Cancel", color = BodyGrey) }
            },
        )
    }
}

/** Back arrow only, matching the editor's top bar rather than introducing a titled app bar. */
@Composable
private fun TrashTopBar(onBack: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = spacing.screenHorizontal - 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos,
                contentDescription = "Back",
                tint = TitleGrey,
            )
        }
    }
}

/** The notes list's header treatment, reused so Trash reads as the same app rather than a dialog. */
@Composable
private fun TrashHeader() {
    val sw = LocalConfiguration.current.screenWidthDp
    val headerSize = (sw * 0.092f).sp
    Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 18.dp)) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TitleGrey, fontWeight = FontWeight.Light)) {
                    append("Deleted")
                }
                append("\n")
                withStyle(SpanStyle(color = IndigoTint, fontWeight = FontWeight.Medium)) {
                    append("items.")
                }
            },
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = headerSize,
                lineHeight = headerSize * 1.05f,
                letterSpacing = (-0.6).sp,
            ),
        )
        Text(
            text = "Erased automatically after ${TrashPolicy.RETENTION_DAYS} days.",
            color = Color(0xFF6A6A70),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = (sw * 0.036f).sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun EmptyTrash() {
    val sw = LocalConfiguration.current.screenWidthDp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = null,
            tint = UncategorizedEdge,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Nothing in Trash",
            color = TitleGrey,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = (sw * 0.045f).sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Deleted notes and folders wait here before they go.",
            color = BodyGrey,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (sw * 0.034f).sp),
        )
    }
}

/**
 * One Trash row, built as the notes list's *unpinned* card: a colored rounded box behind and the
 * SurfaceDark card shifted 5.dp right, so the accent peeks along the left edge. Trash never uses
 * the filled variant — a full-color card is the list's signal for pinned/favorited, and a deleted
 * note should not be the loudest thing on any screen.
 */
@Composable
private fun TrashCard(
    entry: TrashEntryUi,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val sw = LocalConfiguration.current.screenWidthDp
    val edge = folderAccentColor(entry.folderId, entry.folderColorArgb) ?: UncategorizedEdge
    val shape = RoundedCornerShape(18.dp)

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(edge)
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 5.dp),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        ) {
            Column(modifier = Modifier.padding(start = 13.dp, top = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Text(
                    text = entry.title.ifBlank { "Untitled" },
                    color = TitleGrey,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (sw * 0.043f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.snippet.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = entry.snippet,
                        color = BodyGrey,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = (sw * 0.034f).sp,
                            lineHeight = (sw * 0.046f).sp,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = metaLine(entry),
                    color = Color(0xFF6A6A70),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (sw * 0.030f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPill("Restore", IndigoTint, onRestore)
                    ActionPill("Delete forever", BodyGrey, onDeleteForever)
                }
            }
        }
    }
}

/**
 * A pill-shaped text action. Outlined rather than filled because the card is already SurfaceDark —
 * a filled pill on it would be invisible.
 */
@Composable
private fun ActionPill(text: String, tint: Color, onClick: () -> Unit) {
    val sw = LocalConfiguration.current.screenWidthDp
    val shape = RoundedCornerShape(percent = 50)
    Text(
        text = text,
        color = tint,
        style = MaterialTheme.typography.bodySmall.copy(
            fontSize = (sw * 0.034f).sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier
            .clip(shape)
            .border(1.dp, OutlineDark, shape)
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * "Note · deleted 3d ago · 27 days left".
 *
 * The days-left half is dropped entirely when [TrashEntryUi.daysRemaining] is null, which happens
 * only for a tombstone TrashPolicy cannot age (no stamp). Showing "0 days left" there would be a
 * countdown to something that will not happen — such a row is never purged.
 */
private fun metaLine(entry: TrashEntryUi): String {
    val kind = if (entry.kind == TrashEntryKind.FOLDER) "Folder" else "Note"
    val parts = mutableListOf(kind)
    deletedAgo(entry.deletedAt)?.let { parts += it }
    entry.daysRemaining?.let { days ->
        parts += when (days) {
            0 -> "expired"
            1 -> "1 day left"
            else -> "$days days left"
        }
    }
    return parts.joinToString(" · ")
}

/**
 * "deleted 3d ago", or null when there is no usable stamp. Mirrors the notes list's relativeTime.
 * A stamp in the future (the clock moved) reads as "just now" rather than as a negative age.
 */
private fun deletedAgo(deletedAt: Long?): String? {
    if (deletedAt == null || deletedAt <= 0L) return null
    val min = ((System.currentTimeMillis() - deletedAt) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "deleted just now"
        min < 60 -> "deleted ${min}m ago"
        min < 1440 -> "deleted ${min / 60}h ago"
        else -> "deleted ${min / 1440}d ago"
    }
}
