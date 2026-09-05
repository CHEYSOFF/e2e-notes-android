package my.cheysoff.core_domain.sketch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capture-time size guard. The spec asks to refuse an oversized stroke set at capture time,
 * "rather than discovering it at push time, where the failure is a `413` the user cannot act on" --
 * so this is checked against the same encoded text [StrokeCodec] would push, well before it ever
 * reaches the sealed envelope's own, much larger cap.
 */
class SketchLimitsTest {

    private fun scribble(strokeCount: Int, pointsPerStroke: Int): Sketch {
        val strokes = (0 until strokeCount).map { s ->
            val points = (0 until pointsPerStroke).map { p -> Point((s * 3 + p) % 4096, (p * 7) % 4096) }
            Stroke(colorArgb = 0xff112233, width = 8, points = points)
        }
        return Sketch(width = 4096, height = 4096, strokes = strokes)
    }

    @Test
    fun `a realistic dense scribble is within the limit`() {
        // A busy but plausible sketch: several dozen strokes, already thinned by the simplifier to
        // a few dozen points each. This is the single-digit-KB case the limit is meant to admit.
        val encoded = StrokeCodec.encode(scribble(strokeCount = 40, pointsPerStroke = 40))

        assertTrue(SketchLimits.withinLimit(encoded))
    }

    @Test
    fun `an encoding past the cap is refused`() {
        val huge = "x".repeat(SketchLimits.MAX_ENCODED_BYTES + 1)

        assertFalse(SketchLimits.withinLimit(huge))
    }

    @Test
    fun `an encoding exactly at the cap is accepted`() {
        val exact = "x".repeat(SketchLimits.MAX_ENCODED_BYTES)

        assertTrue(SketchLimits.withinLimit(exact))
    }

    @Test
    fun `the limit sits comfortably under the sealed envelope cap`() {
        // ServerConfig.maxEnvelopeBytes is 256 KiB; the envelope carries more than just this text
        // (encryption framing, the rest of the record), so the guard here must leave real headroom.
        val envelopeCap = 256 * 1024
        assertTrue(SketchLimits.MAX_ENCODED_BYTES < envelopeCap / 2)
    }
}
