package my.cheysoff.core_domain.sketch

import my.cheysoff.core_domain.model.SketchData

/**
 * One row a note's sketch list renders: either a drawing that decoded, or the placeholder for one
 * that did not.
 *
 * Modelling the decode outcome as data -- rather than deciding inside a composable whether to skip
 * or draw a row -- is what makes "an undecodable sketch still shows up, as a placeholder" testable
 * without a window on either platform.
 *
 * [id] lives on the interface itself, not just on each case, so the delete button's intent can
 * read it regardless of which case a row turned out to be.
 */
sealed interface DisplaySketch {
    /** The sketch's own id, e.g. for the delete button's intent regardless of which case this is. */
    val id: String

    data class Drawing(override val id: String, val sketch: Sketch) : DisplaySketch
    data class Undecodable(override val id: String) : DisplaySketch
}

/**
 * Sketches for one note, ordered and decoded for display.
 *
 * **Ordering:** [sortSketches] -- the one order rule both platforms render sketches in.
 *
 * **Decoding:** a sketch whose `strokes` fails to decode, or whose decoded width/height is not
 * positive, is kept in the list as [DisplaySketch.Undecodable] rather than dropped. The record is
 * still the user's data; a silent drop looks exactly like the drawing was lost, and there is no
 * way back for a sketch (`TrashEntryKind` is `{NOTE, FOLDER}` -- there is no sketch Trash) -- so it
 * stays visible, and deletable, even on a build that cannot draw it.
 *
 * Shared by both platforms for the same reason [sortSketches] itself is: an earlier version kept
 * this decode-to-display rule desktop-only, so the phone's `SketchSection` silently skipped an
 * undecodable sketch instead of showing this same placeholder -- invisible AND undeletable on a
 * phone-only vault. One compiled function used by both removes the possibility of that drift
 * happening again, rather than merely detecting it after the fact.
 */
fun sketchesForDisplay(sketches: List<SketchData>): List<DisplaySketch> =
    sortSketches(sketches).map { data ->
        val decoded = StrokeCodec.decode(data.strokes)
        if (decoded != null && decoded.width > 0 && decoded.height > 0) {
            DisplaySketch.Drawing(data.id, decoded)
        } else {
            DisplaySketch.Undecodable(data.id)
        }
    }
