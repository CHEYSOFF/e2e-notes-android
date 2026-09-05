package my.cheysoff.feature_notes.ui.sketch

import my.cheysoff.core_domain.sketch.Point
import my.cheysoff.core_domain.sketch.Sketch
import my.cheysoff.core_domain.sketch.SketchLimits
import my.cheysoff.core_domain.sketch.Stroke
import my.cheysoff.core_domain.sketch.StrokeCodec
import my.cheysoff.core_domain.sketch.StrokeSimplifier
import kotlin.math.sqrt

/**
 * The pure state machine behind the sketch canvas: capturing a stroke gesture, undo/redo, and
 * stroke-level erase. It is constructed with the canvas dimensions -- the same integers
 * [my.cheysoff.core_domain.sketch.StrokeCodec] stores -- because it cannot produce a [Sketch]
 * without them, and it never becomes a different canvas size mid-session.
 *
 * Deliberately no Compose types anywhere in this file: it takes and returns plain numbers so it can
 * be driven from a unit test without a device, which is the difference between testing the drawing
 * logic and eyeballing it on a phone.
 *
 * A stroke is [StrokeSimplifier.simplify]d the moment it is completed, in [endStroke] -- not lazily
 * in [toSketch] -- for three reasons: undo then operates on the very strokes that are stored, so
 * undoing never silently changes the shape of the drawing; [endStroke]'s own [SketchLimits] check
 * sees the real stored size rather than the raw, unthinned capture; and the canvas renders the same
 * points during the session as it will after a reload, so a drawing never visibly shifts the moment
 * it is saved.
 *
 * [initialStrokes] seeds [committedStrokes] for the "reopen an existing drawing" flow -- without it,
 * this class could only ever start from a blank canvas. **Undo does not walk back past them**: they
 * are added straight to [committedStrokes] with no [HistoryEntry] pushed, so [undoStack] starts
 * empty even though [strokes] does not. Concretely, [undo] can take back only what THIS session
 * drew or erased; once those are exhausted it stops sitting exactly on the as-loaded drawing rather
 * than continuing on to an empty canvas. The alternative -- letting undo walk all the way back to
 * blank -- would put a whole previously-saved drawing one stray extra tap of Undo (followed by
 * Done) away from being silently discarded, which loses far more work than the alternative's own
 * cost: someone who genuinely wants to clear a loaded drawing can still erase every stroke by hand.
 * (Erasing an as-loaded stroke IS undoable, same as any other erase -- this boundary only concerns
 * the *load itself*, never a session's own edits to it.)
 */
class SketchCaptureState(
    private val width: Int,
    private val height: Int,
    initialStrokes: List<Stroke> = emptyList(),
) {

    /**
     * The outcome of [endStroke]: whether a gesture was even in progress ([NONE]), whether it was
     * committed ([ADDED]), or whether committing it would have pushed the sketch's encoded form past
     * [SketchLimits.MAX_ENCODED_BYTES] ([REJECTED_TOO_LARGE]) -- in which case nothing was committed
     * at all, so there is no undo entry for [redo] to ever reintroduce.
     */
    enum class EndStrokeResult { NONE, ADDED, REJECTED_TOO_LARGE }

    /** The colour a new stroke begins with; a caller (the toolbar) sets this before drawing. */
    var colorArgb: Long = DEFAULT_COLOR_ARGB

    /** The nib width, in canvas units, a new stroke begins with. */
    var strokeWidth: Int = DEFAULT_STROKE_WIDTH

    private val committedStrokes = mutableListOf<Stroke>().apply { addAll(initialStrokes) }
    private val undoStack = mutableListOf<HistoryEntry>()
    private val redoStack = mutableListOf<HistoryEntry>()

    private var activePoints: MutableList<Point>? = null
    private var activeColorArgb: Long = DEFAULT_COLOR_ARGB
    private var activeStrokeWidth: Int = DEFAULT_STROKE_WIDTH

    /** Completed strokes, simplified, in the order they were drawn. A defensive copy every read. */
    val strokes: List<Stroke>
        get() = committedStrokes.toList()

    /**
     * The raw (unsimplified) points of the stroke currently being drawn, for live rendering while a
     * gesture is in progress. Empty when no gesture is active. Simplification only ever happens at
     * [endStroke], so this is deliberately not part of [strokes] -- the two would otherwise disagree
     * about what a live stroke looks like the instant it finishes.
     */
    val activeStrokePoints: List<Point>
        get() = activePoints?.toList() ?: emptyList()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun beginStroke(x: Int, y: Int) {
        activeColorArgb = colorArgb
        activeStrokeWidth = strokeWidth
        activePoints = mutableListOf(clamp(x, y))
    }

    fun extendStroke(x: Int, y: Int) {
        activePoints?.add(clamp(x, y))
    }

    /**
     * Completes the in-progress gesture and, unless doing so would breach [SketchLimits], commits it.
     *
     * The size check happens *before* anything is added to [committedStrokes] or the undo stack --
     * refusing the commit outright, rather than committing it and relying on a caller to [undo] it
     * back out. A stroke that was undone would still occupy a slot in the undo/redo history, and
     * [redo] reinstates a history entry with no check of its own; committing-then-undoing an
     * oversized stroke would leave it one tap of Redo away from silently reappearing. Refusing the
     * commit means no such entry is ever created, so there is nothing for [redo] to reintroduce --
     * [redo] needs no special-casing, because every entry that ever reaches the undo stack was
     * already proven to fit.
     */
    fun endStroke(): EndStrokeResult {
        val points = activePoints ?: return EndStrokeResult.NONE
        activePoints = null

        val simplified = StrokeSimplifier.simplify(points, SIMPLIFY_EPSILON)
        val stroke = Stroke(activeColorArgb, activeStrokeWidth, simplified)

        if (!fitsWithinLimit(committedStrokes + stroke)) {
            return EndStrokeResult.REJECTED_TOO_LARGE
        }

        val index = committedStrokes.size
        committedStrokes.add(stroke)
        pushHistory(HistoryEntry(stroke, index, added = true))
        return EndStrokeResult.ADDED
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        if (entry.added) {
            committedStrokes.removeAt(entry.index)
        } else {
            committedStrokes.add(entry.index, entry.stroke)
        }
        redoStack.add(entry)
    }

    /**
     * Restores the most recently undone change. No size check here: every [HistoryEntry] on
     * [redoStack] was already validated against [SketchLimits] the moment it was first committed (in
     * [endStroke]), by an identical, deterministic encoding of an identical committed-strokes state
     * -- redo only ever replays a state this instance has already occupied, never a new one -- so
     * re-checking here would always agree with that original check and never change the outcome.
     */
    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        if (entry.added) {
            committedStrokes.add(entry.index, entry.stroke)
        } else {
            committedStrokes.removeAt(entry.index)
        }
        undoStack.add(entry)
    }

    private fun fitsWithinLimit(candidateStrokes: List<Stroke>): Boolean =
        SketchLimits.withinLimit(StrokeCodec.encode(Sketch(width, height, candidateStrokes)))

    /**
     * Stroke-level erase: touching within [ERASE_TOLERANCE] canvas units of the nearest point on a
     * stroke's path removes that whole stroke, not the pixels under the finger. Pixel erasing with a
     * fingertip is imprecise, and for a scribble the unit someone means to remove is the whole mark.
     * Like any other edit, this is undoable -- an accidental erase is not lost work.
     *
     * If more than one stroke is within tolerance, the nearest one is removed, matching what someone
     * pointing at a specific spot most likely means to erase.
     */
    fun eraseAt(x: Int, y: Int) {
        val point = clamp(x, y)

        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE
        committedStrokes.forEachIndexed { index, stroke ->
            val distance = distanceToStroke(point, stroke)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        if (bestIndex >= 0 && bestDistance <= ERASE_TOLERANCE) {
            val removed = committedStrokes.removeAt(bestIndex)
            pushHistory(HistoryEntry(removed, bestIndex, added = false))
        }
    }

    fun toSketch(): Sketch = Sketch(width, height, committedStrokes.toList())

    private fun pushHistory(entry: HistoryEntry) {
        undoStack.add(entry)
        // The universal undo/redo convention: a fresh edit after stepping back abandons the branch
        // that was undone, because there is no longer a single linear history to redo back into.
        redoStack.clear()
    }

    private fun clamp(x: Int, y: Int): Point = Point(x.coerceIn(0, width), y.coerceIn(0, height))

    private fun distanceToStroke(point: Point, stroke: Stroke): Double {
        val points = stroke.points
        if (points.size == 1) {
            return distance(point, points[0])
        }
        var min = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val d = distanceToSegment(point, points[i], points[i + 1])
            if (d < min) min = d
        }
        return min
    }

    private fun distance(p: Point, q: Point): Double {
        val dx = (p.x - q.x).toDouble()
        val dy = (p.y - q.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    private fun distanceToSegment(p: Point, a: Point, b: Point): Double {
        val abx = (b.x - a.x).toDouble()
        val aby = (b.y - a.y).toDouble()
        val apx = (p.x - a.x).toDouble()
        val apy = (p.y - a.y).toDouble()
        val abLenSquared = abx * abx + aby * aby
        if (abLenSquared == 0.0) return distance(p, a)

        val t = ((apx * abx + apy * aby) / abLenSquared).coerceIn(0.0, 1.0)
        val closestX = a.x + t * abx
        val closestY = a.y + t * aby
        val dx = p.x - closestX
        val dy = p.y - closestY
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * One undoable change to [committedStrokes]: either a stroke that was added at [index] (undo
     * removes it) or one that was removed from [index] (undo re-inserts it there). Encoding both
     * "draw" and "erase" as the same shape is what lets a single stack undo and redo either kind of
     * edit uniformly.
     */
    private data class HistoryEntry(val stroke: Stroke, val index: Int, val added: Boolean)

    companion object {
        /** Opaque black, the same default a fresh toolbar would show before the user picks a colour. */
        const val DEFAULT_COLOR_ARGB: Long = 0xFF000000L
        const val DEFAULT_STROKE_WIDTH: Int = 8

        // Small relative to the 4096-unit canvas: enough to swallow simplification's own tolerance
        // without smoothing away a real corner a user drew on purpose.
        private const val SIMPLIFY_EPSILON = 2

        // A touch point rarely lands exactly on the path it means to erase -- a fingertip is much
        // wider than the stroke underneath it, so this needs to be sized to a fingertip, not to the
        // stroke.
        //
        // Derivation of the old value's problem: the canvas long edge is 4096 units. On a phone
        // whose screen is ~2992 physical px tall, 24 units of canvas maps to 24/4096 * 2992 ~= 17.5
        // screen px -- about a third of a conventional 48dp touch target (48dp is comfortably over
        // 48 physical px on any density this app ships to). For a tool whose whole premise is a
        // fingertip, that barely responded.
        //
        // 48 canvas units maps to ~35 screen px on that same phone -- roughly doubling the old
        // radius puts it in the same ballpark as a real fingertip, while still being narrow enough
        // that erasing one stroke does not reliably catch a neighbour drawn a normal pen-width away.
        private const val ERASE_TOLERANCE = 48.0
    }
}
