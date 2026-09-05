package my.cheysoff.desktop.ui.state

import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.StrokeCodec

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
 * **Ordering:** by [SketchData.anchor], ties broken by [SketchData.id] -- the exact rule
 * `SingleNoteViewModel.sortSketches` implements on the phone (see that function's own KDoc for why
 * `order`, not `anchor`, is NOT the primary key). It is duplicated here rather than shared: that
 * function lives in `:feature-notes`, an Android-only module the desktop cannot depend on, and
 * hoisting it into `:core-domain` was outside this task's scope (see task 6's brief, which scopes
 * the work to the desktop's note pane and `RecordNotesRepository`). The drift this leaves possible
 * -- the two platforms disagreeing about where a drawing sits, with no test on either side alone
 * able to catch it -- is what [SketchDisplayTest] exists to pin down on this side; a change to
 * either function without the matching change to the other is a change one of the two tests (this
 * one, or `SingleNoteMergeTest`'s `sortSketches` cases) should start failing to notice.
 *
 * **Decoding:** unlike the phone's `SketchSection` (which silently skips a sketch whose `strokes`
 * failed to decode, or whose decoded width/height is not positive), the desktop keeps it in the
 * list as [DisplaySketch.Undecodable] instead of dropping it. The record is still the user's data;
 * a silent drop would look exactly like the drawing was lost, and there is no way back for a
 * sketch (`TrashEntryKind` is `{NOTE, FOLDER}`) -- so it stays visible, and deletable, even though
 * this build cannot draw it.
 */
fun sketchesForDisplay(sketches: List<SketchData>): List<DisplaySketch> =
    sketches.sortedWith(compareBy({ it.anchor }, { it.id })).map { data ->
        val decoded = StrokeCodec.decode(data.strokes)
        if (decoded != null && decoded.width > 0 && decoded.height > 0) {
            DisplaySketch.Drawing(data.id, decoded)
        } else {
            DisplaySketch.Undecodable(data.id)
        }
    }
