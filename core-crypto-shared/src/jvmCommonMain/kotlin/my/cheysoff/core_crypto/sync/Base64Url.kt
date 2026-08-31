package my.cheysoff.core_crypto.sync

/**
 * Base64url encoding without padding — RFC 4648 §5.
 *
 * Hand-rolled rather than delegating, because neither platform option fits:
 *
 *  - `android.util.Base64` is an Android framework class. In a plain `src/test` JVM run it is a
 *    stub whose methods throw (or silently return zero, if a module ever turns on
 *    `returnDefaultValues`), so anything built on it stops being unit-testable — the exact
 *    property this package exists to keep.
 *  - `java.util.Base64` is API 26, above this module's `minSdk 24`.
 *
 * Twenty lines of table lookup avoids both problems and has no configuration to get wrong: no
 * padding, no line wrapping, no locale.
 *
 * Only encoding is provided. Blinded record IDs travel as strings and are never decoded back to
 * bytes anywhere in the protocol — the 16 raw bytes are recomputed from `K_id` when they are
 * needed, they are not recovered from the string.
 *
 * Public rather than `internal` because `accountId` is rendered with it outside this module too:
 * the pairing bundle carries the account handle as a string, and it must be the same 22 characters
 * on both devices.
 */
object Base64Url {

    /** RFC 4648 §5 "URL and Filename safe" alphabet: `-` and `_` in place of `+` and `/`. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Encodes [bytes] as unpadded base64url.
     *
     * Output length is `ceil(bytes.size * 4 / 3)` characters — 22 for the 16-byte blinded IDs
     * this is used for.
     */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var index = 0
        while (index < bytes.size) {
            // Gather up to three bytes into a 24-bit group, most significant byte first. Bytes
            // past the end of the array contribute nothing, which is what makes the final group
            // emit 2 characters (1 remaining byte) or 3 characters (2 remaining bytes).
            val remaining = bytes.size - index
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (remaining > 1) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0
            val group = (b0 shl 16) or (b1 shl 8) or b2

            // 24 bits split into four 6-bit characters; drop the trailing ones that would encode
            // only padding bits.
            val charsInGroup = if (remaining > 2) 4 else remaining + 1
            for (position in 0 until charsInGroup) {
                out.append(ALPHABET[(group shr (18 - 6 * position)) and 0x3F])
            }
            index += 3
        }
        return out.toString()
    }
}
