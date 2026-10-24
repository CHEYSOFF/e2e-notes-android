@file:Suppress("DEPRECATION")

package my.cheysoff.core_editor.ui

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard // deprecated alias on Android only
import my.cheysoff.core_blocks.Block
import my.cheysoff.core_blocks.BlockText
import my.cheysoff.core_editor.Markers
import my.cheysoff.core_editor.lineRanges

/**
 * The clipboard the editor's field sees: copy and cut write [BlockText.plain] of what was
 * selected, never the raw field text.
 *
 * The field's text holds a private-use marker at the start of every list, heading, picture and
 * drawing line ([Markers]). Its built-in copy would put those on the clipboard, where they show
 * as boxes in other apps and, pasted into an older build, as junk. Compose 1.12 has no copy hook
 * on `BasicTextField`, but every copy and cut of the field, on both platforms, goes through the
 * [Clipboard] from `LocalClipboard`: the keyboard shortcut, the desktop context menu and the
 * Android text toolbar all call `setClipEntry` on it. So [BlockEditor] provides this wrapper
 * around the real clipboard for its field, and it rewrites each entry the field writes.
 *
 * The rewrite works from the entry's own text, the raw selected text, rather than from the
 * field's selection: a cut deletes the selection right after writing the entry, and reading the
 * selection then would race it. Each selected line is read as a block from its marker (a line
 * whose start was not selected has no marker and reads as a paragraph), so a whole checklist line
 * copies as "☐ item", a numbered run is numbered, and a picture line copies as nothing.
 *
 * Reading ([getClipEntry], used by paste) passes straight through: paste is plain text, and
 * [my.cheysoff.core_editor.EditingRules] strips any private-use character pasted in.
 */
internal class EditorClipboard(private val delegate: Clipboard) : Clipboard {

    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val raw = clipEntry?.plainText()
        delegate.setClipEntry(if (raw == null) clipEntry else plainClipEntry(plainOf(raw)))
    }

    // Deprecated on Android in favour of platform extensions, but foundation still reads it (paste
    // availability), so it must be the real clipboard's.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override val nativeClipboard: NativeClipboard get() = delegate.nativeClipboard

    companion object {
        /** [raw] field text (markers and all) as the plain text the clipboard gets. */
        fun plainOf(raw: CharSequence): String = BlockText.plain(
            lineRanges(raw).map { line ->
                val lineText = raw.subSequence(line.start, line.end)
                val (type, done) = Markers.typeOf(lineText)
                // BlockText.plain strips any private-use char, the marker included.
                Block("", type, lineText.toString(), done = done)
            },
        )
    }
}

/** The plain text a clipboard entry carries, or null if it has none. */
internal expect suspend fun ClipEntry.plainText(): String?

/** A clipboard entry holding only [text]. */
internal expect fun plainClipEntry(text: String): ClipEntry
