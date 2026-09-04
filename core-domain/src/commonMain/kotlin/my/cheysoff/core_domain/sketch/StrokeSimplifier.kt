package my.cheysoff.core_domain.sketch

/**
 * Ramer-Douglas-Peucker line simplification, done entirely in integers.
 *
 * A finger emits a point per touch event -- hundreds per stroke -- so storing a capture raw makes a
 * scribble roughly ten times larger than it needs to be. This thins it down to the points that
 * actually carry its shape, keeping only the ones a straight line between their neighbours would
 * miss by more than [simplify]'s `epsilon`.
 *
 * The perpendicular-distance test is the one place floating point normally enters RDP (it needs a
 * `sqrt` to turn a cross product into a distance), and that is exactly the thing this must not do:
 * `StrokeCodec` stores integers because Kotlin/JVM and Kotlin/Native do not format floats
 * identically, and a float anywhere in this comparison could make the two platforms disagree about
 * which points a stroke kept. The distance test avoids it by comparing squared quantities instead of
 * distances themselves:
 *
 * For a candidate point `P` against the segment `A-B`, the perpendicular distance is
 * `|cross| / sqrt(segLenSquared)`, where `cross` is the 2D cross product of `(B-A)` and `(P-A)` and
 * `segLenSquared = |B-A|^2`. The comparison `distance > epsilon` is therefore equivalent to
 * `|cross| / sqrt(segLenSquared) > epsilon`, and since both sides are non-negative, squaring
 * preserves the inequality: `cross^2 > epsilon^2 * segLenSquared`. That is an exact comparison of two
 * `Long`s, never a `sqrt` or a division.
 *
 * If `A` and `B` coincide (`segLenSquared == 0`, e.g. a stroke that loops back on itself), there is no
 * line to project onto, so the test falls back to squared point-to-`A` distance compared against
 * `epsilon^2` directly.
 */
object StrokeSimplifier {

    fun simplify(points: List<Point>, epsilon: Int): List<Point> {
        if (points.size <= 2) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true

        val epsilonSquared = epsilon.toLong() * epsilon.toLong()

        // An explicit stack instead of recursion: a pathological input (e.g. a long, jagged stroke
        // where almost every point survives) can recurse to a depth proportional to its point
        // count, and hundreds of touch events per stroke is exactly the case this exists to handle.
        val stack = ArrayDeque<IntRange>()
        stack.addLast(0..(points.size - 1))

        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val start = range.first
            val end = range.last
            if (end - start < 2) continue // no point strictly between start and end

            val a = points[start]
            val b = points[end]
            val abx = (b.x - a.x).toLong()
            val aby = (b.y - a.y).toLong()
            val segLenSquared = abx * abx + aby * aby

            var maxMetric = -1L
            var maxIndex = -1
            for (i in (start + 1) until end) {
                val p = points[i]
                val apx = (p.x - a.x).toLong()
                val apy = (p.y - a.y).toLong()
                val metric = if (segLenSquared == 0L) {
                    apx * apx + apy * apy
                } else {
                    val cross = abx * apy - aby * apx
                    cross * cross
                }
                if (metric > maxMetric) {
                    maxMetric = metric
                    maxIndex = i
                }
            }

            val threshold = if (segLenSquared == 0L) epsilonSquared else epsilonSquared * segLenSquared
            if (maxMetric > threshold) {
                keep[maxIndex] = true
                stack.addLast(start..maxIndex)
                stack.addLast(maxIndex..end)
            }
        }

        return points.filterIndexed { index, _ -> keep[index] }
    }
}
