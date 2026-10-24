package my.cheysoff.core_editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange

/**
 * The Word-like rules of the shared editor, as the field's [InputTransformation].
 *
 * Every rule reads the buffer's change list ([TextFieldBuffer.changes]) and never a key event.
 * A soft keyboard such as Gboard sends no key events for Enter or Backspace. It sends
 * `commitText("\n")` and `deleteSurroundingText`, which reach this transformation only as
 * changed ranges. A hardware key goes through the same path, so the two behave the same.
 *
 * In order, on each user edit:
 * 1. **Strip private-use characters (U+E000-U+F8FF) from what was inserted.** They are the
 *    editor's [Markers]. A pasted, dropped, autofilled or IME-committed one would otherwise become
 *    a fake checkbox or picture. Every insertion is stripped, not only paste, because the
 *    transformation cannot tell those sources apart. Programmatic edits ([EditorModel.edit]) do
 *    not come through here, so they keep the markers they insert. Each change is first trimmed of
 *    the text it re-inserts unchanged. An IME re-committing a composing region that includes an
 *    existing marker therefore keeps the marker.
 * 2. **Enter on a list line** (checklist, bullet, number):
 *    - On an item with text, it continues the list. The new line gets the same marker, unticked,
 *      and that holds for a split mid-item too.
 *    - On an empty item, it exits the list. The line break is dropped and the line becomes an
 *      empty paragraph.
 *    - Right after the marker of an item with text, it puts an empty item above (Word). The
 *      text keeps its marker and its id.
 *    On a heading, as in Word, Enter at its end starts a paragraph. Anywhere else in it, Enter
 *    gives two headings, so Enter right after the marker leaves the text a heading.
 * 3. **Deleting the line break next to a picture deletes the picture line.** This is Backspace at
 *    the start of the line below a picture, or Delete at the end of the line above it. Merging the
 *    text into the picture line instead would make it a paragraph and lose the picture.
 * 4. **A picture stays alone on its line.**
 *    - Text typed before or after its marker moves to a new line after it.
 *    - Surviving text that a wider delete joined to it is split off and keeps its side.
 *    Typing must never silently destroy a picture, and no rule may drop the user's text.
 * 5. **A list or heading marker left mid-line by a join is deleted.** For example, Backspace at
 *    the very start of an item merges it into the line above. The marker would be invisible
 *    there, but a later Enter in front of it would bring the item back.
 *
 * Deleting an item's own marker (Backspace with the caret right after the checkbox) needs no rule.
 * The line is simply a paragraph from then on, and [EditorModel.onEdited] keeps its id on the text.
 *
 * Finally [EditorModel.onEdited] runs, last, so the ids see the text as the rules left it.
 */
@OptIn(ExperimentalFoundationApi::class) // TextFieldBuffer.changes
class EditingRules(private val model: EditorModel) : InputTransformation {

    override fun TextFieldBuffer.transformInput() {
        val caret = Caret(selection)
        stripPrivateUse(caret)
        edits().singleOrNull()?.let { if (!enter(it, caret)) joinAtPicture(it, caret) }
        isolatePictures(caret)
        dropStrayMarkers(caret)
        selection = caret.range
        model.onEdited(this, fromUser = true)
    }

    private fun TextFieldBuffer.stripPrivateUse(caret: Caret) {
        for (e in edits().sortedByDescending { it.nStart }) {
            for (i in e.nEnd - 1 downTo e.nStart) {
                if (Markers.isPrivateUse(asCharSequence()[i])) edit(i, i + 1, "", caret)
            }
        }
    }

    /** Rule 2. [e] must insert exactly one `\n`, as its last char, and delete no line break. */
    private fun TextFieldBuffer.enter(e: Edit, caret: Caret): Boolean {
        if (e.nEnd == e.nStart || (e.oStart until e.oEnd).any { originalText[it] == '\n' }) return false
        val text = asCharSequence()
        val n = e.nEnd - 1
        if (text[n] != '\n' || (e.nStart until n).any { text[it] == '\n' }) return false
        val a = lineStart(text, n)
        if (a == n) return false
        val marker = text[a]
        val itemEmpty = n == a + 1
        val restEmpty = n + 1 == lineEnd(text, n + 1)
        if (marker in HeadingMarkers) {
            // Splitting a heading keeps both halves headings. Enter at its end gives a paragraph,
            // which needs nothing done.
            if (restEmpty) return false
            edit(n + 1, n + 1, marker.toString(), caret)
            caret.set(n + 2)
            return true
        }
        if (marker !in ListMarkers) return false
        val unticked = if (marker == Markers.CHECK_DONE) Markers.CHECK_OPEN else marker
        when {
            itemEmpty && restEmpty -> {
                edit(n, n + 1, "", caret)
                edit(a, a + 1, "", caret)
                caret.set(a)
            }
            itemEmpty -> {
                // Enter right after the marker: the marker stays with the text, and the new line
                // above it gets one too.
                edit(n + 1, n + 1, marker.toString(), caret)
                if (unticked != marker) edit(a, a + 1, unticked.toString(), caret)
                caret.set(n + 2)
            }
            else -> {
                edit(n + 1, n + 1, unticked.toString(), caret)
                caret.set(n + 2)
            }
        }
        return true
    }

    /** Rule 3. [e] must be the deletion of exactly one `\n`. */
    private fun TextFieldBuffer.joinAtPicture(e: Edit, caret: Caret) {
        if (e.nEnd != e.nStart || e.oEnd - e.oStart != 1 || originalText[e.oStart] != '\n') return
        val old = originalText
        val o = e.oStart
        val n = e.nStart
        val above = o >= 1 && isPictureChar(old[o - 1]) && (o == 1 || old[o - 2] == '\n')
        val below = o + 1 < old.length && isPictureChar(old[o + 1]) && (o + 2 == old.length || old[o + 2] == '\n')
        when {
            above -> edit(n - 1, n, "", caret)
            below -> edit(n, n + 1, "", caret)
        }
    }

    /** Rule 4, on the lines this edit touched, right to left so earlier offsets stay valid. */
    private fun TextFieldBuffer.isolatePictures(caret: Caret) {
        val edits = edits()
        fun inserted(x: Int) = edits.any { x >= it.nStart && x < it.nEnd }
        for (span in touchedLines(edits).asReversed()) {
            var i = span.last
            while (i >= span.first) {
                val text = asCharSequence()
                if (i >= text.length || !isPictureChar(text[i])) {
                    i--
                    continue
                }
                val ls = lineStart(text, i)
                val le = lineEnd(text, i)
                if (le - ls == 1) {
                    i--
                    continue
                }
                var segStart = i
                while (segStart > ls && !isPictureChar(text[segStart - 1])) segStart--
                val hasAfter = i + 1 < le
                when {
                    segStart == i -> if (hasAfter) edit(i + 1, i + 1, "\n", caret)
                    (segStart until i).all(::inserted) -> {
                        // Typed in front of the marker: move it to a new line after the picture.
                        val typed = text.subSequence(segStart, i).toString()
                        val before = caret.copy()
                        edit(segStart, i, "", caret)
                        edit(segStart + 1, segStart + 1, "\n$typed", caret)
                        caret.movedTo(before, segStart, i, segStart + 2)
                    }
                    else -> {
                        if (hasAfter) edit(i + 1, i + 1, "\n", caret)
                        edit(i, i, "\n", caret)
                    }
                }
                i = segStart - 1
            }
        }
    }

    /** Rule 5. */
    private fun TextFieldBuffer.dropStrayMarkers(caret: Caret) {
        for (span in touchedLines(edits()).asReversed()) {
            for (i in span.last downTo span.first) {
                val text = asCharSequence()
                if (i >= text.length || i == 0 || text[i - 1] == '\n') continue
                if (Markers.isPrivateUse(text[i]) && !isPictureChar(text[i])) edit(i, i + 1, "", caret)
            }
        }
    }

    /**
     * The buffer's changes in current coordinates, each trimmed of a common prefix and suffix of
     * the old and new text. The change list records an IME `replace` of a whole composing region
     * as one change, though most of it is often re-inserted unchanged.
     */
    private fun TextFieldBuffer.edits(): List<Edit> {
        val list = changes
        val old = originalText
        val now = asCharSequence()
        return (0 until list.changeCount).mapNotNull {
            val o = list.getOriginalRange(it)
            val r = list.getRange(it)
            var oStart = o.min
            var oEnd = o.max
            var nStart = r.min
            var nEnd = r.max
            while (oStart < oEnd && nStart < nEnd && old[oStart] == now[nStart]) {
                oStart++
                nStart++
            }
            while (oStart < oEnd && nStart < nEnd && old[oEnd - 1] == now[nEnd - 1]) {
                oEnd--
                nEnd--
            }
            if (oStart == oEnd && nStart == nEnd) null else Edit(oStart, oEnd, nStart, nEnd)
        }
    }

    /** The current-text offsets of the whole lines [edits] touched, merged, in order. */
    private fun TextFieldBuffer.touchedLines(edits: List<Edit>): List<IntRange> {
        val text = asCharSequence()
        val out = mutableListOf<IntRange>()
        for (e in edits.sortedBy { it.nStart }) {
            val span = lineStart(text, e.nStart)..lineEnd(text, e.nEnd)
            val prev = out.lastOrNull()
            if (prev != null && span.first <= prev.last + 1) {
                out[out.lastIndex] = prev.first..maxOf(prev.last, span.last)
            } else {
                out += span
            }
        }
        return out
    }

    /** `replace`, keeping [caret] on the same text. */
    private fun TextFieldBuffer.edit(start: Int, end: Int, with: String, caret: Caret) {
        replace(start, end, with)
        caret.replaced(start, end, with.length)
    }

    private class Edit(val oStart: Int, val oEnd: Int, val nStart: Int, val nEnd: Int)

    /**
     * The selection, carried through the rules' own edits. The buffer is not relied on to adjust
     * it. Each rule says where the caret should end up, and the field shows exactly that.
     */
    private class Caret(var start: Int, var end: Int) {
        constructor(r: TextRange) : this(r.start, r.end)

        val range get() = TextRange(start, end)

        fun copy() = Caret(start, end)

        fun set(at: Int) {
            start = at
            end = at
        }

        /** Before or at an insertion stays put; inside a deleted range goes to its start. */
        fun replaced(a: Int, b: Int, length: Int) {
            fun map(p: Int) = when {
                p <= a -> p
                p >= b -> p + length - (b - a)
                else -> a
            }
            start = map(start)
            end = map(end)
        }

        /** An end that was in [from, to] before a move now follows the moved text to [at]. */
        fun movedTo(before: Caret, from: Int, to: Int, at: Int) {
            if (before.start in from..to) start = at + (before.start - from)
            if (before.end in from..to) end = at + (before.end - from)
        }
    }

    private companion object {
        val ListMarkers = setOf(Markers.CHECK_OPEN, Markers.CHECK_DONE, Markers.BULLET, Markers.NUMBER)
        val HeadingMarkers = setOf(Markers.H1, Markers.H2, Markers.H3)

        fun isPictureChar(c: Char) = c == Markers.PICTURE || c == Markers.DRAWING

        fun lineStart(text: CharSequence, at: Int): Int {
            var i = at
            while (i > 0 && text[i - 1] != '\n') i--
            return i
        }

        fun lineEnd(text: CharSequence, at: Int): Int {
            var i = at
            while (i < text.length && text[i] != '\n') i++
            return i
        }
    }
}
