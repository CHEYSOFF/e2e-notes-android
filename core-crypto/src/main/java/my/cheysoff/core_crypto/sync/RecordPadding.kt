package my.cheysoff.core_crypto.sync

/**
 * Pads record plaintext up to a multiple of [SyncProtocol.PADDING_BUCKET_BYTES] before it is
 * sealed, and strips the padding after it is opened.
 *
 * AES-GCM is a stream cipher mode: ciphertext length equals plaintext length exactly. Without
 * padding the server — and anyone watching the server — reads the size of every note to the byte,
 * which is enough to tell a three-item shopping list from a long diary entry, to watch a note grow
 * as it is written, and to fingerprint a note across accounts by its size history. Bucketing
 * collapses all of that to "which 256-byte bucket", so every note between two boundaries looks
 * identical.
 *
 * ```
 * padded := len(4B, big-endian) ‖ plaintext ‖ zero filler
 * ```
 *
 * The length prefix lives *inside* the plaintext, so it is encrypted along with everything else;
 * the server sees only the bucket count. Storing the length explicitly rather than using a
 * self-describing scheme is what makes the round trip exact for content that itself ends in zero
 * bytes — a trailing-zero-stripping scheme would silently truncate those.
 *
 * PKCS#7-style padding is not usable at this block size: it encodes the pad length in the pad
 * bytes themselves, which cannot express the 256 possible pad lengths a 256-byte bucket needs.
 *
 * Pure Kotlin, no crypto, no state.
 */
object RecordPadding {

    /** Bytes of big-endian length prefix. Four allows any plaintext the app can hold in memory. */
    private const val LENGTH_PREFIX_BYTES = 4

    /**
     * Pads [plaintext] to the next bucket boundary.
     *
     * The output is always at least one full bucket, so even empty content occupies
     * [SyncProtocol.PADDING_BUCKET_BYTES] bytes — an empty record and a 200-byte record are the
     * same size on the wire. Content that leaves no room for the prefix in its current bucket
     * moves up to the next one; the prefix is counted, so `bucket - 4` bytes of content is the
     * largest payload that still fits a single bucket.
     */
    fun pad(plaintext: ByteArray): ByteArray {
        val total = LENGTH_PREFIX_BYTES + plaintext.size
        val bucket = SyncProtocol.PADDING_BUCKET_BYTES
        // Round `total` up to the next multiple of `bucket`. An exact multiple stays put; this is
        // integer ceiling division, not "always add a bucket".
        val paddedSize = ((total + bucket - 1) / bucket) * bucket

        val padded = ByteArray(paddedSize)
        padded[0] = (plaintext.size ushr 24).toByte()
        padded[1] = (plaintext.size ushr 16).toByte()
        padded[2] = (plaintext.size ushr 8).toByte()
        padded[3] = plaintext.size.toByte()
        plaintext.copyInto(padded, destinationOffset = LENGTH_PREFIX_BYTES)
        // Everything after the copied plaintext is already zero — `ByteArray` starts zeroed.
        return padded
    }

    /**
     * Recovers the original plaintext from [padded], or returns null if [padded] is not a
     * well-formed padded block.
     *
     * This runs on data that has *already* passed GCM tag verification, so a malformed block here
     * means a bug rather than an attacker. It still returns null rather than throwing an
     * index-out-of-bounds: a length field that does not fit is exactly the shape of the bug this
     * check exists to catch, and callers already have a null path for "could not open".
     */
    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < LENGTH_PREFIX_BYTES) return null

        val length =
            ((padded[0].toInt() and 0xFF) shl 24) or
                ((padded[1].toInt() and 0xFF) shl 16) or
                ((padded[2].toInt() and 0xFF) shl 8) or
                (padded[3].toInt() and 0xFF)

        // `length` is read as a signed Int, so a corrupted high bit yields a negative value; and a
        // length longer than the block itself cannot be real either.
        if (length < 0 || length > padded.size - LENGTH_PREFIX_BYTES) return null

        return padded.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + length)
    }
}
