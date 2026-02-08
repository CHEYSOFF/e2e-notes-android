package my.cheysoff.feature_notes.ui.list

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.cheysoff.core_domain.FolderPreview
import my.cheysoff.core_domain.NotePreview
import my.cheysoff.feature_notes.model.NotesListScreenState
import my.cheysoff.feature_notes.model.toUi
import javax.inject.Inject

@HiltViewModel
class NotesListViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(
        NotesListScreenState(
            folderPreviews = List(10) { index ->
                FolderPreview(
                    name = "Folder ${index + 1}",
                    notesAmount = (index + 1) * 10
                ).toUi()
            },
            notePreviews = listOf(
                NotePreview(
                    "Meeting Notes",
                    "Discussed Q4 roadmap, action items assigned. Need to follow up with the design team about the new mockups and finalize the project timeline."
                ).toUi(),
                NotePreview(
                    "Shopping List",
                    "Milk, bread, eggs, cheese, apples, bananas, chicken breast, pasta sauce, and a large bag of coffee beans."
                ).toUi(),
                NotePreview(
                    "Book Ideas",
                    "A sci-fi novel about a colony on Mars facing a mysterious plague. A thriller about a detective who can hear the last thoughts of murder victims."
                ).toUi(),
                NotePreview(
                    "Vacation Plans",
                    "Research flights to Hawaii for September. Look into hotel and car rental prices. Make a list of must-see attractions and restaurants to try."
                ).toUi(),
                NotePreview(
                    "Home Improvement",
                    "Repaint the living room, fix the leaky faucet in the kitchen, and organize the garage. Get quotes from contractors for a new deck."
                ).toUi(),
                NotePreview(
                    "Fitness Goals",
                    "Run a 5k by the end of the summer. Go to the gym three times a week. Try a new yoga class. Remember to stretch daily."
                ).toUi(),
                NotePreview(
                    "App Ideas",
                    "A language learning app that uses AI to create personalized lesson plans. A social media platform for sharing and discovering new recipes."
                ).toUi(),
                NotePreview(
                    "Journal Entry",
                    "Feeling grateful for a productive day. The weather was beautiful, and I had a great conversation with an old friend. Excited for what tomorrow will bring."
                ).toUi(),
            ),
            isLoading = false,
            error = null
        )
    )
    val state = _state.asStateFlow()
}
