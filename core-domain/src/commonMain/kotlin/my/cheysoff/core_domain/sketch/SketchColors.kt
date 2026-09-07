package my.cheysoff.core_domain.sketch

import kotlin.math.abs

/**
 * The colour arithmetic behind the sketch canvas' picker, and the rule for what it remembers.
 *
 * Pure and platform-free on purpose. The picker itself is a `Canvas` full of gestures and shaders
 * that no unit test will ever touch, so everything that can be reasoned about — the conversion both
 * ways, and how the recent list changes — lives here where it can be, and the composable is left
 * with nothing but drawing.
 *
 * Colours are opaque ARGB in a `Long`, the same representation `Stroke.colorArgb` already stores
 * and `StrokeCodec` already writes as eight hex digits. That means an arbitrary mixed colour needs
 * no schema change, no payload change and no migration — it was always expressible; there was just
 * no way to choose one.
 */
object SketchColors {

    /** How many mixed colours the canvas offers back. Three fits the swatch row on a narrow phone. */
    const val MAX_RECENTS = 3

    /** Fully opaque alpha, as the high byte of an ARGB [Long]. */
    private const val OPAQUE = 0xFF000000L

    /**
     * [hue] in `[0, 360)`, [saturation] and [value] in `[0, 1]`, as an opaque ARGB [Long].
     *
     * Alpha is always full. A translucent stroke on this app's black canvas is very nearly an
     * invisible one, so the picker does not offer the choice rather than offering a way to draw
     * something that cannot be seen.
     *
     * Inputs are coerced rather than rejected: they arrive from a drag whose coordinates are
     * clamped to a box, and a hue of exactly 360 (the right edge) must read as 0 rather than throw.
     */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)

        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c

        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    /**
     * The inverse of [hsvToArgb], as `(hue, saturation, value)`.
     *
     * Needed because the picker opens on the colour already selected — including one of the six
     * presets — so that nudging an existing colour is possible and the cursor does not jump to some
     * unrelated default the moment the sheet appears. Alpha in [argb] is ignored.
     *
     * Grey and black have no meaningful hue; both report 0 rather than something arbitrary, so
     * re-opening the picker on the default grey pen puts the cursor at the left edge instead of
     * somewhere that moves between openings.
     */
    fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF).toFloat() / 255f
        val g = ((argb shr 8) and 0xFF).toFloat() / 255f
        val b = (argb and 0xFF).toFloat() / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        val saturation = if (max == 0f) 0f else delta / max
        return Triple(((hue % 360f) + 360f) % 360f, saturation, max)
    }

    /**
     * [recents] with [argb] promoted to the front, de-duplicated, capped at [MAX_RECENTS].
     *
     * Most-recent-first, and re-picking a colour already in the list **moves** it rather than
     * adding a second copy — otherwise using one colour repeatedly would fill every slot with it
     * and push the others out, which is the opposite of what a recent list is for.
     *
     * A colour that is already one of the fixed presets is still recorded. Excluding presets would
     * be a hidden rule the swatch row cannot express, and the duplicate costs one slot at worst.
     */
    fun withRecent(recents: List<Long>, argb: Long): List<Long> =
        (listOf(argb) + recents.filter { it != argb }).take(MAX_RECENTS)
}
