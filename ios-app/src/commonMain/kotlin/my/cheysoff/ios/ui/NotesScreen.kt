package my.cheysoff.ios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.ios.theme.Manana

/**
 * The notes list.
 *
 * ## How much of the Android list this is, and how much it is not
 *
 * The Android home screen has a random header line, folder chips, a swipeable pinned pager, a
 * Recent grid and checklist progress dots. This has a title and a list of cards.
 *
 * That is a deliberate floor rather than a first draft of the same design. Every one of those five
 * pieces is a layout that has to be *looked at* to be got right — a pager's fling, a grid's item
 * ratio, a chip row's overflow — and none of them can be looked at from a machine with no
 * simulator. Building them blind would produce five things that compile and none that are right,
 * and would make it harder to tell which parts of this branch had been seen working. What is here
 * is the part whose correctness does not depend on how it looks: the data flows, a tap opens a
 * note, and an empty library says so.
 *
 * `docs/BUILDING-IOS.md` lists the Android features this screen does not have, so that the gap is a
 * list rather than a surprise.
 */
@Composable
fun NotesScreen(
    notes: List<Note>,
    onOpen: (Note) -> Unit,
    onNew: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 24.dp,
                // Room for the floating button, so the last card is not permanently under it.
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        text = "Mañana",
                        color = Manana.WelcomeGrey,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        text = if (notes.isEmpty()) {
                            "Nothing yet."
                        } else {
                            "${notes.size} ${if (notes.size == 1) "note" else "notes"}, encrypted."
                        },
                        color = Manana.EncryptedNoteGrey,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            items(notes, key = { it.id }) { note ->
                NoteCard(note = note, onClick = { onOpen(note) })
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp)
                .size(60.dp)
                .clip(CircleShape)
                .background(Manana.AccentIndigo)
                .clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", color = Manana.WelcomeGrey, fontSize = 30.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (note.isPinned) Manana.AccentIndigo else Manana.Surface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            text = note.title.ifBlank { "Untitled" },
            color = Manana.TitleGrey,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val preview = note.preview()
        if (preview.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = preview,
                color = Manana.BodyGrey,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A card's second line.
 *
 * An HTML body is stripped of its tags rather than rendered, and crudely: this is a preview, and a
 * two-line snippet showing `<p>` is worse than one showing slightly wrong spacing. The **editor**
 * is where the format matters, and `EditorScreen` says what it does about it -- which is the more
 * serious version of this same gap.
 */
private fun Note.preview(): String {
    val text = when (contentFormat) {
        NoteContentFormat.PLAIN -> content
        NoteContentFormat.HTML -> content.replace(TAG, " ")
    }
    return text.replace(WHITESPACE, " ").trim()
}

private val TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
