package my.cheysoff.desktop.ui.state

import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.StrokeCodec
import my.cheysoff.core_domain.sketch.sortSketches

/**
 * One row [SketchSection][my.cheysoff.desktop.ui.notes.SketchSection] renders: either a drawing
 * that decoded, or the placeholder for one that did not.
 *
 * Modelling the decode outcome as data -- rather than deciding inside the composable whether to
 * skip or draw a row -- is what makes "an undecodable sketch still shows up, as a placeholder"
 * testable without a window: [SketchDisplayTest] asserts on this list directly.
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
 * **Ordering:** [sortSketches] (`:core-domain`) -- the same function `SingleNoteViewModel
 * .sortSketches` on the phone delegates to. This used to be a copy of that rule, kept in step only
 * by two mirrored test suites; it is now the identical compiled function on both platforms, so
 * there is nothing left for the two devices to disagree about.
 *
 * **Decoding:** unlike the phone's `SketchSection` (which silently skips a sketch whose `strokes`
 * failed to decode, or whose decoded width/height is not positive), the desktop keeps it in the
 * list as [DisplaySketch.Undecodable] instead of dropping it. The record is still the user's data;
 * a silent drop would look exactly like the drawing was lost, and there is no way back for a
 * sketch (`TrashEntryKind` is `{NOTE, FOLDER}`) -- so it stays visible, and deletable, even though
 * this build cannot draw it.
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
