package my.cheysoff.feature_notes.ui.list

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import my.cheysoff.core_ui.theme.LocalRadii
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.feature_notes.LocalNoteDimensions
import my.cheysoff.feature_notes.R
import my.cheysoff.feature_notes.model.FolderPreviewUi
import my.cheysoff.feature_notes.model.NotePreviewUi
import my.cheysoff.feature_notes.model.NotesListScreenState


@Composable
fun NotesListScreen(state: NotesListScreenState) {
    val spacing = LocalSpacing.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyVerticalStaggeredGrid(
            modifier = Modifier
                .fillMaxSize(),
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + spacing.screenVertical,
                start = innerPadding.calculateStartPadding(LocalLayoutDirection.current) + spacing.screenHorizontal,
                end = innerPadding.calculateEndPadding(LocalLayoutDirection.current) + spacing.screenHorizontal,
                bottom = innerPadding.calculateBottomPadding() + spacing.screenVertical
            ),
            verticalItemSpacing = spacing.interItemSpacingVertical,
            horizontalArrangement = Arrangement.spacedBy(spacing.interItemSpacingHorizontal)
        ) {
            item(
                span = StaggeredGridItemSpan.FullLine,
                contentType = "folders_section"
            ) {
                FoldersSection(state.folderPreviews)
            }
            item(span = StaggeredGridItemSpan.FullLine) {
                Text(
                    text = "Recent notes",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            items(
                items = state.notePreviews,
                key = { it.id },
                contentType = { "note_preview" }
            ) {
                NotePreview(it)
            }
        }
    }
}

fun Modifier.fillWidthOfParent(parentPadding: Dp) = this.then( // todo reconsider using this hack
    layout { measurable, constraints ->

        val paddingPx = parentPadding.roundToPx()

        val expandedConstraints = constraints.copy(
            minWidth = constraints.minWidth + paddingPx * 4, // todo check magic number 4 instead of 2
            maxWidth = constraints.maxWidth + paddingPx * 4
        )

        val placeable = measurable.measure(expandedConstraints)

        layout(placeable.width, placeable.height) {
            // Shift back so content visually ignores parent padding
            placeable.placeRelative(
                x = -paddingPx,
                y = 0
            )
        }
    }
)

@Composable
fun FoldersSection(folderPreviews: List<FolderPreviewUi>) {
    val spacing = LocalSpacing.current

    val pages =
        remember(folderPreviews) {
            folderPreviews.chunked(3)
        }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pages.size }
    )

    val folderPainter =
        rememberVectorPainter(image = ImageVector.vectorResource(id = R.drawable.folder))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillWidthOfParent(spacing.screenHorizontal)
            .padding(start = spacing.screenHorizontal * 2), // todo check magic padding
        verticalArrangement = Arrangement.spacedBy(spacing.insideCardItemSpacing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "My Folders",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Filter by",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { /* TODO */ },
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        HorizontalPager(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(),
            state = pagerState,
            beyondViewportPageCount = 0,
        ) { page ->
            val pageItems = pages[page]

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                pageItems.forEach { folder ->
                    key(folder.id) {
                        FolderPreview(folder, folderPainter)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPreview(
    folderPreview: FolderPreviewUi,
    painter: Painter
) {
    val spacing = LocalSpacing.current
    val radii = LocalRadii.current
    val dimensions = LocalNoteDimensions.current

    Column(
        modifier = Modifier
            .width(dimensions.folderIconSize)
            .clip(RoundedCornerShape(radii.medium))
            .clickable { /* TODO */ }
            .padding(spacing.insideCardItemSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painter,
            contentDescription = "Folder icon",
            modifier = Modifier.size(dimensions.folderIconSize),
        )
        Text(
            text = folderPreview.name,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun NotePreview(notePreview: NotePreviewUi) {
    val spacing = LocalSpacing.current
    val dimensions = LocalNoteDimensions.current

    Card(
        modifier = Modifier.heightIn(max = dimensions.noteCardMaxHeight),
        colors = CardColors(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurface
        ),
        onClick = { /* TODO */ }
    ) {
        Column(
            modifier = Modifier.padding(spacing.insideCardItemSpacing),
            verticalArrangement = Arrangement.spacedBy(spacing.insideCardItemSpacing),
        ) {
            Text(
                text = notePreview.title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Start,
            )
            Text(
                text = notePreview.content,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
