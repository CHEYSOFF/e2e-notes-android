package my.cheysoff.core_domain.sketch

/**
 * One quadratic Bezier segment of a smoothed stroke: [controlX]/[controlY] pulls the curve toward
 * the source vertex it replaces, and [endX]/[endY] is the point the curve actually passes through.
 * Coordinates are in whatever space the input [Point]s were already in -- a renderer maps each of
 * them separately through [CanvasFit.toTarget] before handing them to its own path-building calls.
 */
data class QuadraticSegment(val controlX: Double, val controlY: Double, val endX: Double, val endY: Double)

/**
 * Turns a stored polyline -- the RDP-thinned points [StrokeCodec] persists -- into the control
 * points a `quadraticBezierTo` sequence needs so a stroke reads as a drawn line rather than a chain
 * of straight segments.
 *
 * The construction is the standard "smooth curve through a polyline" trick: each interior source
 * point becomes a control point, and the curve's own path passes through the midpoint between it
 * and its neighbour rather than through the source point itself. The final segment is the
 * exception -- it must still end exactly at the stroke's last point, not at a midpoint that does
 * not exist beyond it, so its control and end coincide there.
 *
 * A two-point stroke is the other special case: using the midpoint of the two points as the single
 * control point makes the quadratic degenerate to the exact straight line between them (a quadratic
 * Bezier with `control == (start + end) / 2` reduces algebraically to linear interpolation) --
 * which is what a plain two-point stroke should look like, not an arbitrary curve.
 */
object StrokeSmoothing {

    fun segments(points: List<Point>): List<QuadraticSegment> {
        val n = points.size
        if (n < 2) return emptyList()

        if (n == 2) {
            val a = points[0]
            val b = points[1]
            return listOf(QuadraticSegment(midX(a, b), midY(a, b), b.x.toDouble(), b.y.toDouble()))
        }

        val result = ArrayList<QuadraticSegment>(n - 1)
        for (i in 1 until n - 1) {
            val p = points[i]
            val next = points[i + 1]
            result.add(QuadraticSegment(p.x.toDouble(), p.y.toDouble(), midX(p, next), midY(p, next)))
        }
        val last = points[n - 1]
        result.add(QuadraticSegment(last.x.toDouble(), last.y.toDouble(), last.x.toDouble(), last.y.toDouble()))
        return result
    }

    private fun midX(a: Point, b: Point): Double = (a.x + b.x) / 2.0
    private fun midY(a: Point, b: Point): Double = (a.y + b.y) / 2.0
}
