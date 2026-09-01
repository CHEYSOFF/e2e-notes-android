package my.cheysoff.desktop.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import my.cheysoff.desktop.ui.state.SearchState
import my.cheysoff.desktop.ui.theme.AppBlack
import my.cheysoff.desktop.ui.theme.BodyGrey
import my.cheysoff.desktop.ui.theme.IndigoTint
import my.cheysoff.desktop.ui.theme.MetaGrey
import my.cheysoff.desktop.ui.theme.OutlineDark
import my.cheysoff.desktop.ui.theme.PlaceholderGrey
import my.cheysoff.desktop.ui.theme.SurfaceDark
import my.cheysoff.desktop.ui.theme.TitleGrey
import my.cheysoff.desktop.ui.theme.ToolbarDark

/**
 * Ctrl+K search: a command palette floating over the whole window, not a third pane.
 *
 * The phone puts search in its own tab because there is nowhere else for it to go. On desktop the
 * whole point of the shortcut is that search is available without giving anything up — the list
 * and the note you were reading stay on screen behind the scrim, and choosing a result replaces
 * only the editor's contents.
 *
 * Keyboard-first by construction: the field takes focus on open, Up/Down move the highlight, Enter
 * opens, Escape closes. Those keys are handled by the window's preview handler (see DesktopApp) so
 * they work whether or not the text field has focus.
 */
@Composable
fun SearchPalette(
    search: SearchState,
    now: Long,
    onQueryChange: (String) -> Unit,
    onOpenHit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }

    val listState = rememberLazyListState()
    LaunchedEffect(search.highlighted, search.hits.size) {
        if (search.hits.isNotEmpty()) listState.animateScrollToItem(search.highlighted)
    }

    // Composed straight into the window's root Box rather than wrapped in a Popup. A desktop
    // Popup is a non-focusable overlay by default, and inside one the search field never received
    // a keystroke -- the palette opened and then swallowed everything typed into it. There is
    // nothing a Popup would buy here anyway: this is already the topmost node in the window.
    run {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // A scrim rather than a solid panel: the note underneath stays legible, which is
                // what makes the palette feel like it is over the app instead of replacing it.
                .background(AppBlack.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 110.dp)
                    .width(620.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ToolbarDark)
                    .border(1.dp, OutlineDark, RoundedCornerShape(14.dp))
                    // Swallow clicks so hitting the panel does not dismiss through the scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MetaGrey, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(11.dp))
                    val style = MaterialTheme.typography.bodyMedium
                    BasicTextField(
                        value = search.query,
                        onValueChange = onQueryChange,
                        textStyle = style.copy(color = TitleGrey),
                        cursorBrush = SolidColor(IndigoTint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                        decorationBox = { inner ->
                            if (search.query.isEmpty()) {
                                Text("Search all notes…", style = style, color = PlaceholderGrey)
                            }
                            inner()
                        },
                    )
                }

                if (search.query.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(OutlineDark))
                }

                when {
                    search.query.isBlank() -> Unit
                    search.hits.isEmpty() -> Text(
                        text = "No notes match “${search.query}”.",
                        color = BodyGrey,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 380.dp),
                        contentPadding = PaddingValues(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(
                            items = search.hits,
                            key = { _, hit -> hit.row.id },
                        ) { index, hit ->
                            SearchResultRow(
                                title = hit.title,
                                titleHighlights = hit.titleHighlights,
                                snippet = hit.snippet,
                                snippetHighlights = hit.snippetHighlights,
                                meta = relativeTime(hit.row.updatedAt, now),
                                isHighlighted = index == search.highlighted,
                                onClick = { onOpenHit(hit.row.id) },
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    KeyHint("↑ ↓")
                    Spacer(Modifier.width(6.dp))
                    Text("navigate", color = MetaGrey, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(14.dp))
                    KeyHint("Enter")
                    Spacer(Modifier.width(6.dp))
                    Text("open", color = MetaGrey, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(14.dp))
                    KeyHint("Esc")
                    Spacer(Modifier.width(6.dp))
                    Text("close", color = MetaGrey, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    title: String,
    titleHighlights: List<IntRange>,
    snippet: String,
    snippetHighlights: List<IntRange>,
    meta: String,
    isHighlighted: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    isHighlighted -> Color(0xFF23232B)
                    hovered -> Color(0xFF1D1D23)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(handCursor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = highlighted(title.ifBlank { "Untitled" }, titleHighlights),
                color = TitleGrey,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            if (meta.isNotEmpty()) {
                Text(text = meta, color = MetaGrey, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (snippet.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = highlighted(snippet, snippetHighlights),
                color = BodyGrey,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
