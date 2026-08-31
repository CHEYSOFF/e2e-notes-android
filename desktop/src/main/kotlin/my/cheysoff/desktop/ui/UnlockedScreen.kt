package my.cheysoff.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.repository.NotesRepository
import java.util.UUID

/**
 * The unlocked window — **a placeholder, and the seam the two-pane UI replaces.**
 *
 * Everything below this line in the module is finished work; this screen is not, and it is here for
 * one reason: a foundation that has never been driven end to end through a real UI has not been
 * shown to work. It creates notes, lists them from the repository's `Flow`, and locks the vault,
 * which is exactly the set of operations that proves the record store round-trips through
 * `RecordEnvelope` and back.
 *
 * What the UI agent inherits: a [NotesRepository]. Not a desktop-shaped variant of one, not a
 * wrapper — the same interface `RoomNotesRepository` implements and every existing ViewModel is
 * written against. `getNotes(sortOrder)` is a hot `Flow` backed by an in-memory snapshot, so
 * collecting it from several composables costs nothing.
 */
@Composable
fun UnlockedScreen(
    repository: NotesRepository,
    message: String?,
    credentialStoreName: String?,
    isRemembered: Boolean,
    rememberFailed: Boolean,
    onRemember: () -> Unit,
    onForget: () -> Unit,
    onLock: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val notes: Flow<List<Note>> = remember(repository) {
        repository.getNotes(NotesSortOrder.RECENTLY_EDITED)
    }
    val visible by notes.collectAsState(initial = emptyList())
    var remembered by remember { mutableStateOf(isRemembered) }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Your notes",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    "${visible.size} note(s), each sealed on disk exactly as it would be sent to a server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MananaColors.BodyGrey,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveNote(
                                Note(
                                    id = UUID.randomUUID().toString(),
                                    title = "Note ${visible.size + 1}",
                                    content = "Written on the desktop.",
                                ),
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MananaColors.AccentIndigo),
                ) {
                    Text("New note")
                }
                TextButton(onClick = onLock) { Text("Lock", color = MananaColors.IndigoTint) }
            }
        }

        if (message != null) {
            Text(message, color = MananaColors.Warning, style = MaterialTheme.typography.bodySmall)
        }

        if (credentialStoreName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = remembered,
                    onCheckedChange = { checked ->
                        remembered = checked
                        if (checked) onRemember() else onForget()
                    },
                )
                Text(
                    "Remember this vault in $credentialStoreName",
                    color = MananaColors.BodyGrey,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (rememberFailed) {
                // The honest failure path. The vault is untouched either way; what changes is
                // whether the user is expecting to need the passphrase next launch.
                Text(
                    "$credentialStoreName refused to store the key. You will be asked for your " +
                        "passphrase next time — keep it.",
                    color = MananaColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider(color = MananaColors.Outline)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { note ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MananaColors.Surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            note.title.ifEmpty { "Untitled" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MananaColors.TitleGrey,
                        )
                        Text(
                            note.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MananaColors.BodyGrey,
                        )
                    }
                }
            }
        }
    }
}
