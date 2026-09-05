package my.cheysoff.core_domain.attachment

/** A width and a height in pixels. `commonMain` has no `IntSize`. */
data class PixelSize(val width: Int, val height: Int)

/** One rung: encode at [quality] after fitting the image inside [longEdge]. */
data class EncodeStep(val longEdge: Int, val quality: Int)

/**
 * The order in which the importer tries to get an image under [AttachmentLimits.MAX_ATTACHMENT_BYTES].
 *
 * Quality first, dimensions second, and that order is the whole point: a photograph at 1600 px and
 * q55 is more useful in a note than the same photograph at 784 px and q85, because what people
 * attach photographs of -- receipts, whiteboards, screenshots of text -- is legible at resolution
 * and illegible without it. The dimension rungs exist for inputs the quality ladder cannot save.
 *
 * This object decides; it never encodes. That split is what lets every rung be tested without a
 * bitmap, and it is why the platform importer is a loop around [next] rather than a ladder of its
 * own.
 */
object ImportLadder {
    const val MAX_LONG_EDGE = 1600
    private val QUALITIES = listOf(85, 75, 65, 55)
    /**
     * The three long-edge rungs, **derived** from [MAX_LONG_EDGE] rather than written out.
     *
     * Spelling the first one as a literal would let it drift from the constant that names it, and
     * nothing would fail -- the ladder would simply start at a size no caller asked for. Each rung
     * is 70% of the one above: 1600, 1120, 784.
     */
    private val LONG_EDGES =
        listOf(MAX_LONG_EDGE, MAX_LONG_EDGE * 7 / 10, MAX_LONG_EDGE * 49 / 100)

    /** Every rung, in order. Dimensions outermost, quality innermost. */
    val STEPS: List<EncodeStep> =
        LONG_EDGES.flatMap { edge -> QUALITIES.map { q -> EncodeStep(edge, q) } }

    /** The rung after [step], or null when the ladder is spent and the import must be refused. */
    fun next(step: EncodeStep): EncodeStep? =
        STEPS.getOrNull(STEPS.indexOf(step) + 1).takeIf { STEPS.contains(step) }

    /**
     * [srcWidth] by [srcHeight] fitted inside a [longEdge] box, preserving the aspect ratio.
     *
     * Never upscales: an image already smaller than the box is returned at its own size, because
     * re-encoding a small image larger costs bytes and adds no pixels. Neither edge is ever zero --
     * a 40000x3 panorama rounds to 1 rather than to 0, and a zero-height bitmap is a crash in the
     * decoder rather than a bad-looking image.
     */
    fun fit(srcWidth: Int, srcHeight: Int, longEdge: Int): PixelSize {
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= longEdge) return PixelSize(srcWidth, srcHeight)
        val scale = longEdge.toDouble() / longest
        return PixelSize(
            width = maxOf(1, (srcWidth * scale).toInt()),
            height = maxOf(1, (srcHeight * scale).toInt()),
        )
    }
}
