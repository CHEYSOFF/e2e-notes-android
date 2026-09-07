package my.cheysoff.core_domain.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SketchColorsTest {

    // --- hsvToArgb -------------------------------------------------------------------------------

    @Test
    fun `full saturation and value at each primary hue`() {
        assertEquals(0xFFFF0000L, SketchColors.hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00L, SketchColors.hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FFL, SketchColors.hsvToArgb(240f, 1f, 1f))
    }

    @Test
    fun `zero saturation is grey at whatever the value says`() {
        assertEquals(0xFFFFFFFFL, SketchColors.hsvToArgb(200f, 0f, 1f))
        assertEquals(0xFF000000L, SketchColors.hsvToArgb(200f, 0f, 0f))
    }

    @Test
    fun `every colour is opaque`() {
        val alphas = listOf(0f, 90f, 180f, 270f, 359f).map { hue ->
            SketchColors.hsvToArgb(hue, 0.5f, 0.5f) ushr 24
        }

        assertEquals(listOf(0xFFL, 0xFFL, 0xFFL, 0xFFL, 0xFFL), alphas)
    }

    @Test
    fun `a hue of exactly 360 wraps to red rather than falling off the end`() {
        assertEquals(SketchColors.hsvToArgb(0f, 1f, 1f), SketchColors.hsvToArgb(360f, 1f, 1f))
    }

    @Test
    fun `out of range inputs are coerced rather than throwing`() {
        assertEquals(0xFFFF0000L, SketchColors.hsvToArgb(-360f, 2f, 2f))
        assertEquals(0xFF000000L, SketchColors.hsvToArgb(0f, -1f, -1f))
    }

    // --- argbToHsv -------------------------------------------------------------------------------

    @Test
    fun `hsv survives a round trip back to argb`() {
        val samples = listOf(0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL, 0xFF7F3FBFL, 0xFF123456L)

        samples.forEach { argb ->
            val (h, s, v) = SketchColors.argbToHsv(argb)
            assertEquals(argb, SketchColors.hsvToArgb(h, s, v), "round trip of ${argb.toString(16)}")
        }
    }

    @Test
    fun `grey reports no hue rather than an arbitrary one`() {
        val (hue, saturation, _) = SketchColors.argbToHsv(0xFF808080L)

        assertEquals(0f, hue)
        assertEquals(0f, saturation)
    }

    @Test
    fun `alpha in the input is ignored`() {
        assertEquals(SketchColors.argbToHsv(0xFFFF0000L), SketchColors.argbToHsv(0x00FF0000L))
    }

    // --- withRecent ------------------------------------------------------------------------------

    @Test
    fun `a new colour goes to the front`() {
        assertEquals(listOf(3L, 1L, 2L), SketchColors.withRecent(listOf(1L, 2L), 3L))
    }

    @Test
    fun `re-picking a remembered colour moves it rather than duplicating it`() {
        assertEquals(listOf(2L, 1L, 3L), SketchColors.withRecent(listOf(1L, 2L, 3L), 2L))
    }

    @Test
    fun `the list is capped and the oldest falls off`() {
        val result = SketchColors.withRecent(listOf(1L, 2L, 3L), 4L)

        assertEquals(SketchColors.MAX_RECENTS, result.size)
        assertEquals(listOf(4L, 1L, 2L), result)
        assertTrue(3L !in result)
    }

    @Test
    fun `recording onto an empty list gives one entry`() {
        assertEquals(listOf(7L), SketchColors.withRecent(emptyList(), 7L))
    }
}
