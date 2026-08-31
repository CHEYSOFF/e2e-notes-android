package my.cheysoff.desktop.ui.preview

import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat

/**
 * A believable note library for running the desktop UI before there is a real store behind it.
 *
 * The content is chosen to exercise the cases that break layouts rather than to look tidy: a
 * checklist note that is partly done, a note long enough to need truncation, a one-line note, an
 * untitled note, a note in no folder, and folders with wildly different name lengths. The bodies
 * are HTML written the way richeditor writes it, so the preview stripper and the editor's parser
 * are both doing real work in every screenshot.
 */
object SampleLibrary {

    /**
     * The sample library is anchored to the moment the app starts, not to a fixed date.
     *
     * Every timestamp below is an offset from this, so the list shows "22 min ago" and "2 d ago"
     * rather than a wall of "just now" (a fixed epoch drifts, and once it is in the past by more
     * than a week every note collapses into the same date bucket). Nothing depends on it being
     * reproducible — the unit tests build their own notes and never touch this object.
     */
    val EPOCH: Long = System.currentTimeMillis()

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR


    val folders: List<Folder> = listOf(
        Folder(id = "folder-work", name = "Work", createdAt = EPOCH - 40 * DAY, updatedAt = EPOCH - 40 * DAY),
        Folder(id = "folder-personal", name = "Personal", createdAt = EPOCH - 40 * DAY, updatedAt = EPOCH - 40 * DAY),
        Folder(id = "folder-reading", name = "Reading", createdAt = EPOCH - 30 * DAY, updatedAt = EPOCH - 30 * DAY),
    )

    val notes: List<Note> = listOf(
        Note(
            id = "note-groceries",
            title = "Groceries",
            content = "<p>Milk, eggs, coffee. The good coffee, not the one from the corner shop.</p>",
            contentFormat = NoteContentFormat.HTML,
            checklist = "1pick up parcel\n0book dentist\n0return the drill",
            isPinned = true,
            folderId = "folder-personal",
            createdAt = EPOCH - 3 * DAY,
            updatedAt = EPOCH - 22 * MINUTE,
        ),
        Note(
            id = "note-standup",
            title = "Standup notes",
            content = "<p><b>Blocked:</b> waiting on the pairing QR spec.</p>" +
                "<p>Shipped the trash retention pass. Next: field-level merge rules, then the " +
                "desktop shell.</p>",
            contentFormat = NoteContentFormat.HTML,
            isPinned = true,
            folderId = "folder-work",
            createdAt = EPOCH - 9 * DAY,
            updatedAt = EPOCH - 3 * HOUR,
        ),
        Note(
            id = "note-ideas",
            title = "Ideas",
            content = "<p>A notes app that never sees your notes. Keys never leave the device; the " +
                "server stores ciphertext it cannot open, and pairing happens over a QR code you " +
                "photograph once. If the server is breached, the attacker gets noise.</p>" +
                "<p>Open question: what does recovery look like when someone loses every paired " +
                "device at once?</p>",
            contentFormat = NoteContentFormat.HTML,
            isFavorite = true,
            folderId = "folder-work",
            createdAt = EPOCH - 14 * DAY,
            updatedAt = EPOCH - 26 * HOUR,
        ),
        Note(
            id = "note-book-list",
            title = "Book list",
            content = "<ul><li>Seeing Like a State</li><li>The Design of Everyday Things</li>" +
                "<li>Piranesi</li></ul>",
            contentFormat = NoteContentFormat.HTML,
            checklist = "1Seeing Like a State\n1Piranesi\n0The Design of Everyday Things\n0Station Eleven",
            folderId = "folder-reading",
            createdAt = EPOCH - 60 * DAY,
            updatedAt = EPOCH - 2 * DAY,
        ),
        Note(
            id = "note-flat",
            title = "Flat viewing — Thursday",
            content = "<p>18:30, ask about the boiler and whether the rent includes the parking " +
                "space.</p>",
            contentFormat = NoteContentFormat.HTML,
            folderId = "folder-personal",
            createdAt = EPOCH - 5 * DAY,
            updatedAt = EPOCH - 4 * DAY,
        ),
        Note(
            id = "note-untitled",
            // Deliberately untitled: the list has to fall back to "Untitled" and stay legible.
            title = "",
            content = "<p>call back about the invoice</p>",
            contentFormat = NoteContentFormat.HTML,
            createdAt = EPOCH - 6 * DAY,
            updatedAt = EPOCH - 6 * DAY,
        ),
        Note(
            id = "note-plain",
            title = "Wifi",
            // PLAIN, and containing angle brackets, so the preview must NOT run it through a
            // parser. This is the exact shape that used to be silently truncated on Android.
            content = "guest network <ask reception> — rotates monthly",
            contentFormat = NoteContentFormat.PLAIN,
            createdAt = EPOCH - 20 * DAY,
            updatedAt = EPOCH - 11 * DAY,
        ),
    )
}
