package my.cheysoff.core_domain.sketch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored form of a drawing.
 *
 * Byte-stability is the property that matters most here and is the least obvious. Every device
 * decodes a sketch and re-encodes it whenever it merges or re-saves; if that round trip differed by
 * one character, every sketch on the account would go dirty at once and be pushed again. The same
 * failure already threatens this codebase through `richeditor`'s HTML round trip, which is why the
 * encoding below emits no floats at all — Kotlin/JVM and Kotlin/Native do not format them
 * identically, and the desktop and the phone would disagree about bytes that mean the same picture.
 */
class StrokeCodecTest {

    private val sample = Sketch(
        width = 3277,
        height = 4096,
        strokes = listOf(
            Stroke(colorArgb = 0xff2c1ab0, width = 24, points = listOf(Point(0, 0), Point(120, 40), Point(370, 130))),
            Stroke(colorArgb = 0xffdcdcdc, width = 48, points = listOf(Point(900, 300), Point(880, 420))),
        ),
    )

    /** The golden fixture. If this changes, every stored sketch is re-encoded — see the class doc. */
    private val goldenText =
        "1|3277x4096|ff2c1ab0,24:0,0;120,40;250,90|ffdcdcdc,48:900,300;-20,120"

    @Test
    fun `encodes to the pinned format`() {
        assertEquals(goldenText, StrokeCodec.encode(sample))
    }

    @Test
    fun `decodes the pinned format back to the same drawing`() {
        assertEquals(sample, StrokeCodec.decode(goldenText))
    }

    @Test
    fun `re-encoding a decoded sketch is byte-identical`() {
        val once = StrokeCodec.encode(sample)
        val twice = StrokeCodec.encode(StrokeCodec.decode(once)!!)
        assertEquals(once, twice, "a round trip must not move a single byte")
    }

    @Test
    fun `points after the first are stored as deltas`() {
        // 120,40 -> 370,130 is +250,+90. Storing absolutes would roughly double a long stroke.
        assertTrue(StrokeCodec.encode(sample).contains("250,90"))
    }

    @Test
    fun `an empty sketch round-trips`() {
        val empty = Sketch(width = 3277, height = 4096, strokes = emptyList())
        assertEquals(empty, StrokeCodec.decode(StrokeCodec.encode(empty)))
    }

    @Test
    fun `nothing unparseable throws`() {
        // Decode is fed bytes that came off a network from another device. A malformed sketch is
        // one record to refuse, never a reason to take down a sync pass.
        // Each fixture below must use a valid 8-digit colour (ff0000 is only 6) so that the colour
        // check does not refuse it before the branch the fixture is named for is ever reached.
        listOf(
            "", "1", "1|", "1|3277", "1|axb|ff0000,1:0,0", "1|1x1|nothex,1:0,0",
            "1|1x1|ff0000ff,x:0,0", "1|1x1|ff0000ff,1:0", "1|1x1|ff0000ff,1:a,b",
            "2|1x1|ff0000,1:0,0", "1|1x1|ff0000ff,1:0,0;",
        ).forEach { assertNull(StrokeCodec.decode(it), "should not decode: <$it>") }
    }

    @Test
    fun `a future version is refused rather than guessed at`() {
        assertNull(StrokeCodec.decode("2|1x1|ff0000,1:0,0"))
    }

    /**
     * `Color.toArgb()` returns a negative `Int` for any alpha >= 0x80 -- which is to say, for any
     * opaque colour, which is most of them. Widening that `Int` to the `Long` this codec stores
     * sign-extends it, so an opaque black comes in as a large negative `Long`, not as
     * `0xFF000000`. `encode` must mask back to the low 32 bits before formatting it as hex, or the
     * text it emits fails `decode`'s own 8-digit check and the sketch that was just drawn cannot be
     * read back.
     */
    @Test
    fun `an opaque colour the way Compose would produce it round-trips`() {
        val opaqueBlackAsComposeWouldReturnIt: Int = 0xFF000000.toInt() // -16777216
        val colorArgb: Long = opaqueBlackAsComposeWouldReturnIt.toLong()

        val sketch = Sketch(
            width = 100,
            height = 100,
            strokes = listOf(Stroke(colorArgb = colorArgb, width = 4, points = listOf(Point(0, 0)))),
        )

        val encoded = StrokeCodec.encode(sketch)
        val decoded = StrokeCodec.decode(encoded)

        assertTrue(encoded.contains("ff000000"), "expected the masked 8-digit colour in: $encoded")
        assertEquals(0xFF000000L, decoded?.strokes?.get(0)?.colorArgb, "the colour must survive the round trip")
    }
}
