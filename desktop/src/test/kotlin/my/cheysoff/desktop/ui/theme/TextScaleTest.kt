package my.cheysoff.desktop.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text-size setting: how it steps, and what it does to the type scale.
 *
 * Nothing here touches [java.util.prefs.Preferences]. That store is the one the installed app
 * reads, so a test that wrote to it would change the running copy's text size — which is why
 * `parse` exists separately from `load`.
 */
class TextScaleTest {

    @Test
    fun `the button cycles through every step and comes back`() {
        val seen = generateSequence(TextScale.DEFAULT) { it.next() }
            .take(TextScale.entries.size + 1)
            .toList()

        assertEquals("cycling must visit every step", TextScale.entries.toSet(), seen.dropLast(1).toSet())
        assertEquals("and return to where it started", seen.first(), seen.last())
    }

    @Test
    fun `the keyboard steps clamp instead of wrapping`() {
        // Holding Ctrl+- past the smallest must stay there. Wrapping would jump a reader who is
        // shrinking the text to the largest size on screen, which is the opposite of the ask.
        var shrinking = TextScale.DEFAULT
        repeat(10) { shrinking = shrinking.smaller() }
        assertEquals(TextScale.entries.first(), shrinking)

        var growing = TextScale.DEFAULT
        repeat(10) { growing = growing.larger() }
        assertEquals(TextScale.entries.last(), growing)
    }

    @Test
    fun `an unreadable stored value falls back to the default`() {
        assertEquals(TextScale.DEFAULT, TextScale.parse(null))
        assertEquals(TextScale.DEFAULT, TextScale.parse(""))
        assertEquals(TextScale.DEFAULT, TextScale.parse("HUGE"))
        assertEquals(TextScale.DEFAULT, TextScale.parse("comfortable"))
        assertEquals(TextScale.LARGE, TextScale.parse("LARGE"))
    }

    @Test
    fun `the default is larger than the scale as designed`() {
        // The phone-derived sizes read small on a monitor, which is the whole reason this exists.
        // If someone ever "tidies" DEFAULT back to COMPACT, that regression should fail here.
        assertTrue(TextScale.DEFAULT.factor > TextScale.COMPACT.factor)
    }

    @Test
    fun `scaling moves every role and keeps their proportions`() {
        val small = desktopTypography(TextScale.COMPACT.factor)
        val large = desktopTypography(TextScale.LARGEST.factor)

        val roles = listOf(
            "titleLarge" to Pair(small.titleLarge, large.titleLarge),
            "titleMedium" to Pair(small.titleMedium, large.titleMedium),
            "titleSmall" to Pair(small.titleSmall, large.titleSmall),
            "bodyMedium" to Pair(small.bodyMedium, large.bodyMedium),
            "bodySmall" to Pair(small.bodySmall, large.bodySmall),
            "labelSmall" to Pair(small.labelSmall, large.labelSmall),
        )
        val ratio = TextScale.LARGEST.factor / TextScale.COMPACT.factor
        roles.forEach { (name, styles) ->
            val (a, b) = styles
            assertEquals(
                "$name must scale by exactly the ratio between the two steps",
                a.fontSize.value * ratio,
                b.fontSize.value,
                0.01f,
            )
        }

        // The hierarchy is the identity: a title must stay a title's distance above body text.
        assertEquals(
            "the title-to-body ratio must survive scaling",
            small.titleLarge.fontSize.value / small.bodyMedium.fontSize.value,
            large.titleLarge.fontSize.value / large.bodyMedium.fontSize.value,
            0.01f,
        )
    }

    @Test
    fun `line height scales with the text it wraps`() {
        // Scaling the glyphs and not the leading is how large text ends up overlapping itself.
        val small = desktopTypography(TextScale.COMPACT.factor).bodyMedium
        val large = desktopTypography(TextScale.LARGEST.factor).bodyMedium

        assertEquals(
            small.lineHeight.value / small.fontSize.value,
            large.lineHeight.value / large.fontSize.value,
            0.01f,
        )
    }
}
