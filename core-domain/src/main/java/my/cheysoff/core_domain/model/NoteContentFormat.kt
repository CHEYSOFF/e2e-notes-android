package my.cheysoff.core_domain.model

/**
 * How a note's [Note.content] is encoded on disk.
 *
 * This used to be guessed at read time with a "does it contain something shaped like a tag?"
 * regex. That heuristic false-positived on ordinary prose ("Email John <john@example.com>",
 * "TODO <see attached spec>", "if a<b> then"): the HTML parser swallowed the pseudo-tag, the text
 * vanished from the editor, and the next keystroke persisted the truncated body. Silent,
 * permanent data loss. So the format is now recorded per row and never inferred again.
 */
enum class NoteContentFormat(val storageValue: String) {
    /** Literal text. Rendered and edited verbatim; "<" and ">" are just characters. */
    PLAIN("plain"),

    /** Rich-text HTML as produced by the editor's `toHtml()`. */
    HTML("html");

    companion object {
        /**
         * An unrecognised/corrupt column value degrades to [PLAIN] on purpose. The two failure
         * directions are not symmetric: showing HTML as plain text renders visible markup — ugly,
         * obvious, and fully recoverable — whereas parsing plain text as HTML destroys characters
         * silently and irreversibly. When in doubt, do the recoverable thing.
         */
        fun fromStorage(value: String): NoteContentFormat =
            entries.firstOrNull { it.storageValue == value } ?: PLAIN
    }
}
