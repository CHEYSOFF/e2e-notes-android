package my.cheysoff.core_sync_net.wire

import my.cheysoff.core_crypto.sync.Base64Url as CoreBase64Url

/**
 * The base64 the wire uses.
 *
 * ## Encoding delegates; only decoding is new
 *
 * `core-crypto`'s [CoreBase64Url] is the project's one base64url **encoder** and this object calls
 * it rather than reimplementing it. That is not tidiness: this repository has already shipped two
 * implementations of one primitive that disagreed (the two HKDFs), and an encoder that differs from
 * `core-crypto`'s by one character would produce `accountId` strings that name a different account
 * than the one the record IDs were blinded for.
 *
 * A **decoder** genuinely does not exist there, and deliberately so -- [CoreBase64Url]'s own KDoc
 * says blinded record IDs are never decoded back to bytes, so it ships encode-only. The transport
 * has the opposite need: every public key, signature and sealed envelope the server hands back
 * arrives as base64url text and has to become bytes again. So the decoder lives here, next to the
 * only code that needs it.
 *
 * ## What is accepted
 *
 * Unpadded base64url (RFC 4648 §5) is what this protocol emits, and padded base64url is accepted
 * too, because the server accepts both (`server/.../Primitives.kt`: *"the server should accept
 * anything a reasonable client emits and re-emit one canonical form"*). The standard alphabet's
 * `+` and `/` are **rejected**: accepting them would mean two distinct strings decode to the same
 * bytes, and a blinded record ID is a database key.
 */
internal object Base64Codec {

    private const val URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private const val STANDARD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Reverse lookup for [URL_ALPHABET]; -1 for every character that is not in it. */
    private val URL_REVERSE = IntArray(128) { -1 }.also { table ->
        URL_ALPHABET.forEachIndexed { value, c -> table[c.code] = value }
    }

    /** Unpadded base64url, via `core-crypto`. */
    fun encodeUrl(bytes: ByteArray): String = CoreBase64Url.encode(bytes)

    /**
     * Decodes unpadded or padded base64url, or returns null.
     *
     * Never throws. A malformed field in a server response is a protocol error the caller reports
     * with context about *which* field it was, and an exception thrown from here would arrive
     * without that context.
     */
    fun decodeUrl(value: String): ByteArray? {
        // Padding carries no information -- it only rounds the text out to a multiple of four --
        // so it is dropped before decoding rather than validated. A string with padding in the
        // middle still fails below, because '=' is not in the alphabet.
        val body = value.trimEnd('=')
        // A group of one character encodes zero whole bytes, so a remainder of 1 is not a
        // truncated encoding of anything; it is not decodable and must not be silently rounded.
        if (body.length % 4 == 1) return null

        val out = ByteArray(body.length * 3 / 4)
        var outIndex = 0
        var accumulator = 0
        var bitsHeld = 0
        for (c in body) {
            val sextet = if (c.code < 128) URL_REVERSE[c.code] else -1
            if (sextet < 0) return null
            accumulator = (accumulator shl 6) or sextet
            bitsHeld += 6
            if (bitsHeld >= 8) {
                bitsHeld -= 8
                out[outIndex++] = ((accumulator shr bitsHeld) and 0xFF).toByte()
            }
        }
        // The 2 or 4 bits left over in a final partial group are padding bits and must be zero.
        // Non-zero ones mean two different strings decode to the same bytes, which is exactly the
        // malleability the `+`/`/` rejection above is about.
        if (bitsHeld > 0 && (accumulator and ((1 shl bitsHeld) - 1)) != 0) return null
        return out
    }

    /**
     * Standard base64 **with** padding -- RFC 4648 §4.
     *
     * Used for exactly one thing: OkHttp's `CertificatePinner` takes its pins as
     * `"sha256/<standard base64 of the SHA-256 of the DER SubjectPublicKeyInfo>"`, and the pairing
     * QR carries those 32 bytes raw. This is the adapter between the two and has no other caller.
     */
    fun encodeStandard(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val remaining = bytes.size - index
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (remaining > 1) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0
            val group = (b0 shl 16) or (b1 shl 8) or b2
            val charsInGroup = if (remaining > 2) 4 else remaining + 1
            for (position in 0 until charsInGroup) {
                out.append(STANDARD_ALPHABET[(group shr (18 - 6 * position)) and 0x3F])
            }
            repeat(4 - charsInGroup) { out.append('=') }
            index += 3
        }
        return out.toString()
    }
}
