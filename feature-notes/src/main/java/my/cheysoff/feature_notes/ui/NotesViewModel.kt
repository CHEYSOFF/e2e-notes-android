package my.cheysoff.feature_notes.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import my.cheysoff.core_domain.FolderPreview
import my.cheysoff.core_domain.NotePreview
import my.cheysoff.feature_notes.model.NotesListScreenState
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(
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
    val state = _state.asStateFlow()
}
