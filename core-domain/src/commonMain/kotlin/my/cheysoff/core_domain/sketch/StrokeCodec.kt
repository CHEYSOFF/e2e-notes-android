package my.cheysoff.core_domain.sketch

/**
 * Encodes and decodes the stored form of a [Sketch].
 *
 * Format (version 1): `V|WxH|color,width:x,y;dx,dy;dx,dy|color,width:x,y|...`
 *
 * - `V` is the format version. Only `1` is understood; anything else refuses to decode rather than
 *   guessing at a layout it was never taught.
 * - `WxH` is the canvas size in canvas units (never device pixels).
 * - Each `|`-separated segment after that is one stroke: an 8-digit lowercase ARGB hex with no
 *   `0x` prefix, a comma, the nib width, a colon, then `;`-separated points. The first point of a
 *   stroke is absolute; every point after it is a delta from the previous point, because a long
 *   stroke's points cluster tightly and storing them as deltas keeps the encoding small.
 *
 * Every number here is an [Int] or a [Long] formatted in base 10 or base 16 -- never a
 * floating-point value. Kotlin/JVM and Kotlin/Native do not format floats identically, so a float
 * anywhere in this output would make the phone and the desktop disagree about bytes that mean the
 * same picture, and this encoding exists specifically so that never happens.
 */
object StrokeCodec {

    private val dimsPattern = Regex("^(\\d+)x(\\d+)$")
    private val colorPattern = Regex("^[0-9a-f]{8}$")

    fun encode(sketch: Sketch): String {
        val header = "1|${sketch.width}x${sketch.height}"
        val strokes = sketch.strokes.joinToString("|") { encodeStroke(it) }
        return if (strokes.isEmpty()) header else "$header|$strokes"
    }

    fun decode(text: String): Sketch? {
        return try {
            decodeOrThrow(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeOrThrow(text: String): Sketch? {
        val parts = text.split("|")
        if (parts.size < 2) return null
        if (parts[0] != "1") return null

        val dims = dimsPattern.matchEntire(parts[1]) ?: return null
        val width = dims.groupValues[1].toIntOrNull() ?: return null
        val height = dims.groupValues[2].toIntOrNull() ?: return null

        val strokes = ArrayList<Stroke>(parts.size - 2)
        for (strokeText in parts.drop(2)) {
            val stroke = decodeStroke(strokeText) ?: return null
            strokes.add(stroke)
        }
        return Sketch(width, height, strokes)
    }

    private fun encodeStroke(stroke: Stroke): String {
        // Masked to the low 32 bits before formatting: `Color.toArgb()` returns a negative `Int`
        // for any alpha >= 0x80 (i.e. most opaque colours), and widening that to this field's
        // `Long` sign-extends it. Without the mask, `toString(16)` on that value emits a leading
        // `-` or more than 8 digits, and `decode`'s 8-digit colour check refuses it -- silently
        // losing a sketch that was never malformed, only mis-encoded. Do not remove this mask.
        val colorHex = (stroke.colorArgb and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        val points = StringBuilder()
        var prevX = 0
        var prevY = 0
        stroke.points.forEachIndexed { index, point ->
            if (index > 0) points.append(';')
            if (index == 0) {
                points.append(point.x).append(',').append(point.y)
            } else {
                points.append(point.x - prevX).append(',').append(point.y - prevY)
            }
            prevX = point.x
            prevY = point.y
        }
        return "$colorHex,${stroke.width}:$points"
    }

    private fun decodeStroke(text: String): Stroke? {
        val colonIndex = text.indexOf(':')
        if (colonIndex < 0) return null
        val header = text.substring(0, colonIndex)
        val pointsText = text.substring(colonIndex + 1)

        val commaIndex = header.indexOf(',')
        if (commaIndex < 0) return null
        val colorHex = header.substring(0, commaIndex)
        val widthText = header.substring(commaIndex + 1)

        if (!colorPattern.matches(colorHex)) return null
        val colorArgb = colorHex.toLongOrNull(16) ?: return null
        val width = widthText.toIntOrNull() ?: return null

        if (pointsText.isEmpty()) return null
        val segments = pointsText.split(";")
        if (segments.any { it.isEmpty() }) return null

        val points = ArrayList<Point>(segments.size)
        var x = 0
        var y = 0
        segments.forEachIndexed { index, segment ->
            val coords = segment.split(",")
            if (coords.size != 2) return null
            val a = coords[0].toIntOrNull() ?: return null
            val b = coords[1].toIntOrNull() ?: return null
            if (index == 0) {
                x = a
                y = b
            } else {
                x += a
                y += b
            }
            points.add(Point(x, y))
        }
        if (points.isEmpty()) return null

        return Stroke(colorArgb, width, points)
    }
}
