package my.cheysoff.core_domain.sketch

/**
 * One point, in canvas units — never pixels.
 *
 * The canvas is an integer grid whose long edge is always 4096, so a drawing renders identically on
 * a phone and a 27-inch monitor and the stored form does not depend on the device that drew it.
 */
data class Point(val x: Int, val y: Int)

/** One continuous mark: a colour, a nib width in canvas units, and the path it took. */
data class Stroke(val colorArgb: Long, val width: Int, val points: List<Point>)

/**
 * A whole drawing. [width] and [height] are the canvas it was drawn on, so a renderer can letterbox
 * it into whatever space it has rather than distorting it.
 */
data class Sketch(val width: Int, val height: Int, val strokes: List<Stroke>)
