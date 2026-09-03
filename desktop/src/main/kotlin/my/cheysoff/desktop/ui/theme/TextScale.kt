package my.cheysoff.desktop.ui.theme

import java.util.prefs.Preferences

/**
 * How large the app's text is, as a multiplier on the type scale.
 *
 * A desktop is read at arm's length on a display whose size and pixel density the app cannot know,
 * so there is no single right answer — a 14sp body is comfortable on a 13-inch laptop and small on
 * a 27-inch monitor two feet away. The type scale itself stays as designed: every step here
 * multiplies it, so the proportions between a title, a body and a caption never change.
 *
 * [COMFORTABLE] is the default rather than [COMPACT], because the sizes inherited from the Android
 * scale read small on a desktop and that was the first thing anyone said about them.
 */
enum class TextScale(val factor: Float, val label: String) {

    /** The scale exactly as designed. */
    COMPACT(1.0f, "S"),

    /** The default. */
    COMFORTABLE(1.15f, "M"),

    LARGE(1.3f, "L"),

    LARGEST(1.5f, "XL");

    /** The next step, wrapping — the title-bar control cycles rather than opening a menu. */
    fun next(): TextScale = entries[(ordinal + 1) % entries.size]

    // Ctrl +/- clamp rather than wrap. Wrapping is right for a button whose label says where you
    // are, and wrong for a keystroke pressed while looking at the text: holding Ctrl+- until it
    // reads well should stop at the smallest, not silently jump to the largest.
    fun larger(): TextScale = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]

    fun smaller(): TextScale = entries[(ordinal - 1).coerceAtLeast(0)]

    companion object {

        val DEFAULT = COMFORTABLE

        private const val KEY = "text.scale"

        /**
         * Stored beside the window geometry, and for the same reason given there: it is one small
         * value with no privacy weight, and a file of our own would be a file to migrate.
         *
         * Every access is wrapped, because a locked-down or corrupted preferences store must
         * degrade to the default rather than stop the app from starting. A person who cannot read
         * their notes because a font size could not be loaded would be entitled to be annoyed.
         */
        private val prefs: Preferences? = runCatching {
            Preferences.userRoot().node("my/cheysoff/manana/desktop")
        }.getOrNull()

        fun load(): TextScale = parse(runCatching { prefs?.get(KEY, null) }.getOrNull())

        /**
         * Separated from [load] so it can be tested without writing to the real preferences store,
         * which is the one the running app reads -- a test that exercised it would quietly change
         * the text size of the installed copy.
         *
         * Anything unrecognised falls back rather than throwing: a value written by a future
         * version, or a step later renamed, must cost the reader their preference and nothing more.
         */
        fun parse(stored: String?): TextScale =
            entries.firstOrNull { it.name == stored } ?: DEFAULT

        fun save(scale: TextScale) {
            runCatching { prefs?.put(KEY, scale.name) }
        }
    }
}
