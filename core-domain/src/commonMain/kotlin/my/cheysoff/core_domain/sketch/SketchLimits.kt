package my.cheysoff.core_domain.sketch

/**
 * The capture-time size guard for a sketch's encoded form.
 *
 * The spec asks to refuse an oversized stroke set at capture time, "rather than discovering it at
 * push time, where the failure is a `413` the user cannot act on" -- so this is checked against the
 * exact text [StrokeCodec.encode] produces, before it is ever wrapped into a record and pushed.
 */
object SketchLimits {

    /**
     * A sealed envelope caps at 256 KiB (`ServerConfig.maxEnvelopeBytes`). This sits at a quarter of
     * that: the envelope carries more than just this text (encryption framing, the rest of the
     * record fields), so the guard needs real headroom under the server's own limit, not a value
     * that only clears it by chance depending on what else shares the envelope. 64 KiB is still very
     * generous for what it needs to hold -- a dense, already-simplified scribble encodes to
     * single-digit kilobytes -- so tripping this cap in practice means the input is genuinely
     * oversized, not that a normal drawing was punished for being detailed.
     */
    const val MAX_ENCODED_BYTES: Int = 64 * 1024

    fun withinLimit(encoded: String): Boolean = encoded.encodeToByteArray().size <= MAX_ENCODED_BYTES
}
