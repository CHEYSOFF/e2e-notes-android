package manana.sync.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * A time source. `System.currentTimeMillis()` is never called directly anywhere else in the
 * server, so every deadline the server enforces -- signature freshness, session expiry, challenge
 * expiry, rate-limit refill -- can be driven forwards by a test without sleeping.
 */
fun interface Clock {
    fun nowMillis(): Long
}

/** The production clock. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Base64url without padding -- RFC 4648 §5, the same encoding the Android client's
 * `core-crypto/.../sync/Base64Url.kt` produces.
 *
 * `java.util.Base64`'s URL decoder accepts input both with and without trailing `=`, and rejects
 * the standard alphabet's `+` and `/`. That asymmetry is deliberate here: the server should accept
 * anything a reasonable client emits and re-emit one canonical form.
 */
object B64 {
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Decodes [value], or returns null if it is not valid base64url. Never throws. */
    fun decodeOrNull(value: String): ByteArray? = try {
        decoder.decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Decodes [value] and requires exactly [size] bytes. Null on either failure. */
    fun decodeExactly(value: String, size: Int): ByteArray? =
        decodeOrNull(value)?.takeIf { it.size == size }
}

/** 16 cryptographically random bytes as base64url -- device IDs and session challenges. */
object Ids {
    private val random = SecureRandom()

    fun random(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return B64.encode(bytes)
    }
}

/**
 * SHA-256, hex-encoded.
 *
 * Used for two things and nothing else: the replay cache key (a digest of the canonical signed
 * message) and the at-rest form of a session token. Storing tokens digested is what keeps the
 * README's claim honest -- a full read of `sync.db` yields no credential that can be presented to
 * this server, because the bearer token itself is only ever in the client's memory and in the
 * `Authorization` header of a live request.
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
