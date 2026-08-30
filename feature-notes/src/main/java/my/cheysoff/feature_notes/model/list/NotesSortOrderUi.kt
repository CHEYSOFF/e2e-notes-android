package my.cheysoff.feature_notes.model.list

import my.cheysoff.core_domain.model.NotesSortOrder

/** Full name of the order, as listed in the sort menu. */
val NotesSortOrder.menuLabel: String
    get() = when (this) {
        NotesSortOrder.RECENTLY_EDITED -> "Recently edited"
        NotesSortOrder.NEWEST_CREATED -> "Newest created"
        NotesSortOrder.TITLE_ASC -> "Title A–Z"
    }

/**
 * Short name for the collapsed pill. The pill sits at the end of the folder-chip row and must not
 * crowd it, so each order gets a one-word form rather than its full [menuLabel].
 */
val NotesSortOrder.pillLabel: String
    get() = when (this) {
        NotesSortOrder.RECENTLY_EDITED -> "Edited"
        NotesSortOrder.NEWEST_CREATED -> "Created"
        NotesSortOrder.TITLE_ASC -> "A–Z"
    }
