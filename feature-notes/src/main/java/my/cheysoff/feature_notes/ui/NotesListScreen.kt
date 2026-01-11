package my.cheysoff.feature_notes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import my.cheysoff.core_domain.FolderPreview
import my.cheysoff.core_domain.NotePreview
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.feature_notes.R
import my.cheysoff.feature_notes.model.NotesListScreenState

@Composable
fun NotesListScreen(state: NotesListScreenState) {
    val spacing = LocalSpacing.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyVerticalStaggeredGrid(
            modifier = Modifier
                .fillMaxSize(),
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(
                top = spacing.screenVertical,
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal
            ),
            verticalItemSpacing = spacing.interItemSpacingVertical,
            horizontalArrangement = Arrangement.spacedBy(spacing.interItemSpacingHorizontal)
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
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
            items(state.notePreviews) {
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
fun FoldersSection(folderPreviews: List<FolderPreview>) {
    val spacing = LocalSpacing.current

    val pages =
        remember(folderPreviews) { // todo check optimizations of pager and check on r8 release build
            folderPreviews.chunked(3)
        }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { pages.size }
    )

    Column(
        modifier = Modifier
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
            modifier = Modifier.fillMaxWidth(),
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
                        FolderPreview(folder)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderPreview(folderPreview: FolderPreview) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.folder),
            contentDescription = "Folder icon",
            modifier = Modifier.size(100.dp), // todo add sizes in theme
        ) // todo show notes amount in the folder on top of the icon (bottom-right)
        Text(
            text = folderPreview.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clickable { /* TODO */ },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun NotePreview(notePreview: NotePreview) {
    val spacing = LocalSpacing.current

    Card(
        modifier = Modifier.heightIn(max = 300.dp),
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

@Preview
@Composable
fun NotesListScreenPreview() {
    NotesListScreen(
        NotesListScreenState(
            folderPreviews = listOf(
                FolderPreview(name = "Folder 1", notesAmount = 10),
                FolderPreview(name = "Folder 2", notesAmount = 20),
                FolderPreview(name = "Folder 3", notesAmount = 30),
                FolderPreview(name = "Folder 4", notesAmount = 40),
                FolderPreview(name = "Folder 5", notesAmount = 50),
                FolderPreview(name = "Folder 6", notesAmount = 60),
                FolderPreview(name = "Folder 7", notesAmount = 70),
                FolderPreview(name = "Folder 8", notesAmount = 80),
                FolderPreview(name = "Folder 9", notesAmount = 90),
            ),
            notePreviews = listOf(
                NotePreview(
                    "Meeting Notes",
                    "Discussed Q4 roadmap, action items assigned. Need to follow up with the design team about the new mockups and finalize the project timeline."
                ),
                NotePreview(
                    "Shopping List",
                    "Milk, bread, eggs, cheese, apples, bananas, chicken breast, pasta sauce, and a large bag of coffee beans."
                ),
                NotePreview(
                    "Book Ideas",
                    "A sci-fi novel about a colony on Mars facing a mysterious plague. A thriller about a detective who can hear the last thoughts of murder victims."
                ),
                NotePreview(
                    "Vacation Plans",
                    "Research flights to Hawaii for September. Look into hotel and car rental prices. Make a list of must-see attractions and restaurants to try."
                ),
                NotePreview(
                    "Home Improvement",
                    "Repaint the living room, fix the leaky faucet in the kitchen, and organize the garage. Get quotes from contractors for a new deck."
                ),
                NotePreview(
                    "Fitness Goals",
                    "Run a 5k by the end of the summer. Go to the gym three times a week. Try a new yoga class. Remember to stretch daily."
                ),
                NotePreview(
                    "App Ideas",
                    "A language learning app that uses AI to create personalized lesson plans. A social media platform for sharing and discovering new recipes."
                ),
                NotePreview(
                    "Journal Entry",
                    "Feeling grateful for a productive day. The weather was beautiful, and I had a great conversation with an old friend. Excited for what tomorrow will bring."
                ),
            ),
            isLoading = false,
            error = null
        )
    )
}