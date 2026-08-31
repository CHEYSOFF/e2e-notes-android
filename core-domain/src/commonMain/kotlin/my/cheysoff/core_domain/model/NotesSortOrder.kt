package my.cheysoff.core_domain.model

/**
 * How the notes list is ordered. Chosen by the user and persisted; the list falls back to
 * [DEFAULT] when nothing has been stored yet.
 *
 * [key] — and not the enum constant's name — is what gets written to DataStore, so the constants
 * stay renameable without stranding an already-persisted preference.
 */
enum class NotesSortOrder(val key: String) {
    /** Most recently saved first (the app's original, and still default, order). */
    RECENTLY_EDITED("recently_edited"),

    /** Most recently created first. */
    NEWEST_CREATED("newest_created"),

    /** Title ascending, case-insensitive. */
    TITLE_ASC("title_asc");

    companion object {
        val DEFAULT = RECENTLY_EDITED

        /**
         * Stored [key] back to an order. Unknown values (a preference written by a newer build,
         * then downgraded) and a missing key both fall back to [DEFAULT] rather than throwing.
         */
        fun fromKey(key: String?): NotesSortOrder =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
