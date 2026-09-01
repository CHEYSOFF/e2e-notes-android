package my.cheysoff.ios.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.ios.theme.Manana

/**
 * The note editor: a title and a body.
 *
 * ## The rich-text gap, which is the largest single gap in this port
 *
 * The Android editor is `richeditor-compose`: bold, italic, headings, bullet lists, and a floating
 * toolbar. Its bodies are stored as HTML, with `contentFormat = HTML` recorded on the row so it is
 * never guessed -- `NoteContentFormat` explains, at length, the data loss that guessing caused.
 *
 * This editor is plain text. And that creates a hazard that a compiler cannot catch and that
 * matters more than the missing formatting: **a note written on Android and edited here would have
 * its markup shown as literal text and saved back as literal text**, turning `<b>hello</b>` into
 * six visible characters and destroying the formatting for every device on the account.
 *
 * So it does not do that. A note whose `contentFormat` is HTML is shown **read-only**, with a line
 * saying why. Refusing to edit is a bad experience; silently flattening someone's formatted note
 * across all their devices is data loss, and `NoteContentFormat`'s own KDoc argues for exactly this
 * trade -- when in doubt, do the recoverable thing.
 *
 * `richeditor-compose` does publish iOS artifacts, so closing this properly is a real and
 * reasonably short piece of work for someone with a simulator. It is the first thing
 * `docs/BUILDING-IOS.md` lists under what to build next, and until it is done this app is a
 * second-class editor on an account that already has notes.
 *
 * ## Saving
 *
 * On leaving, not on every keystroke. Each save is a full read-modify-write with a decrypt, a
 * re-seal and an HLC stamp -- see `RecordNotesRepository` -- so a save per keystroke would be a
 * needless amount of AES and, worse, a row clock that advances on every character, which the merge
 * would read as a stream of edits rather than one.
 */
@Composable
fun EditorScreen(
    note: Note,
    onSave: (Note) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.content) }
    val readOnly = note.contentFormat == NoteContentFormat.HTML

    // `rememberUpdatedState` so the effect's cleanup sees the LAST text rather than the text as it
    // was when the effect was set up -- the classic stale-capture bug, and here it would silently
    // discard everything the user typed.
    val latest by rememberUpdatedState(note.copy(title = title, content = body))
    DisposableEffect(note.id) {
        onDispose {
            // Nothing is written for a note that was opened and not changed, and nothing at all for
            // a blank new note -- `NotesRepository.purgeNote` documents that a note created blank
            // and left blank must never reach Trash, and the simplest way to honour that is to
            // never create it.
            val unchanged = latest.title == note.title && latest.content == note.content
            val blank = latest.title.isBlank() && latest.content.isBlank()
            if (!unchanged && !blank) onSave(latest)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // Without this the keyboard covers the caret. It is also the single most likely thing
            // on this screen to be subtly wrong on a real device, because `imePadding` and iOS's
            // keyboard-avoidance have to agree about who is moving the view.
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Back",
                color = Manana.IndigoTint,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onClose),
            )
            Text(
                text = "Delete",
                color = Manana.BodyGrey,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onDelete),
            )
        }

        BasicTextField(
            value = title,
            onValueChange = { title = it },
            readOnly = readOnly,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(
                color = Manana.WelcomeGrey,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
            ),
            cursorBrush = SolidColor(Manana.IndigoTint),
            decorationBox = { inner ->
                if (title.isEmpty()) {
                    Text("Title", color = Manana.EncryptedNoteGrey, fontSize = 28.sp)
                }
                inner()
            },
        )

        if (readOnly) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "This note was written with the rich-text editor. Editing it here would " +
                    "flatten its formatting on every device, so it is read-only for now.",
                color = Manana.IndigoTint,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = body,
            onValueChange = { body = it },
            readOnly = readOnly,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            textStyle = TextStyle(color = Manana.TitleGrey, fontSize = 16.sp),
            cursorBrush = SolidColor(Manana.IndigoTint),
        )
    }
}
