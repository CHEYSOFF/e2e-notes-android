package my.cheysoff.feature_notes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import my.cheysoff.core_domain.FolderPreview
import my.cheysoff.core_ui.theme.LocalSpacing
import my.cheysoff.feature_notes.R
import my.cheysoff.feature_notes.model.NotesListScreenState

@Composable
fun NotesListScreen(state: NotesListScreenState) {
    val spacing = LocalSpacing.current

    Surface(
        modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = spacing.screenVertical)
        ) {
            FoldersSection(state.folderPreviews)
        }
    }
}

@Composable
fun FoldersSection(folderPreviews: List<FolderPreview>) {
    val spacing = LocalSpacing.current

    Column(
        modifier = Modifier.fillMaxWidth(),
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

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.listItemSpacing),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal),
        ) {
            items(folderPreviews) { folderPreview ->
                Folder(folderPreview)
            }
        }
    }
}

@Composable
private fun Folder(folderPreview: FolderPreview) {
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

@Preview
@Composable
fun NotesListScreenPreview() {
    NotesListScreen(
        NotesListScreenState(
            folderPreviews = listOf(
                FolderPreview("Folder 1", 10),
                FolderPreview("Folder 2", 20),
                FolderPreview("Folder 3", 30),
                FolderPreview("Folder 3", 30),
                FolderPreview("Folder 3", 30),
                FolderPreview("Folder 3", 30),
                FolderPreview("Folder 3", 30),
                FolderPreview("Folder 3", 30),
                FolderPreview("Folder 3", 30),
            ), notePreviews = emptyList(), isLoading = false, error = null
        )
    )
}