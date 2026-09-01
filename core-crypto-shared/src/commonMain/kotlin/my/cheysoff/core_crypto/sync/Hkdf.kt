package my.cheysoff.core_crypto.sync

import my.cheysoff.core_crypto.platform.hmacSha256

/**
 * HKDF-SHA256 — RFC 5869 extract-and-expand key derivation.
 *
 * Hand-rolled over one HMAC primitive on purpose. The alternatives all cost more than the thirty
 * lines below: `android.security.keystore` has no HKDF, Tink/BouncyCastle would be a new
 * dependency, `javax.crypto.KDF` is Java 24 and JVM-only, and CommonCrypto has no HKDF at all.
 * Doing it here keeps ONE HKDF for every platform -- which matters more than it sounds, because
 * this repository has already shipped two that disagreed.
 *
 * A wrong HKDF is invisible: it still produces 32 pseudo-random-looking bytes, still round-trips
 * against itself, and only shows up as "the other device cannot decrypt anything" much later. That
 * is why `HkdfTest` checks the RFC's own PRK and OKM values rather than self-consistency.
 *
 * Never logs key material.
 */
object Hkdf {

    /** Output length of SHA-256, called `HashLen` in RFC 5869. */
    const val HASH_LEN = 32

    /** RFC 5869 §2.3 caps the expand output at 255 * HashLen bytes; the counter is one byte. */
    const val MAX_OUTPUT_BYTES = 255 * HASH_LEN

    /**
     * RFC 5869 §2.2 — `HKDF-Extract(salt, IKM) -> PRK`.
     *
     * The salt is the HMAC *key* and the input keying material is the HMAC *message*; getting
     * those two the wrong way round is the classic HKDF bug and produces plausible-looking
     * garbage. Per the RFC, a null or empty [salt] is replaced by [HASH_LEN] zero bytes.
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(key = effectiveSalt, message = ikm)
    }

    /**
     * RFC 5869 §2.3 — `HKDF-Expand(PRK, info, L) -> OKM`.
     *
     * `T(0) = empty`, `T(i) = HMAC(PRK, T(i-1) ‖ info ‖ byte(i))`, output is the first [length]
     * bytes of `T(1) ‖ T(2) ‖ …`. The counter starts at 1, not 0, and `T(i-1)` is the *previous
     * block* rather than the running output — both are easy to get subtly wrong and both are
     * pinned by the multi-block A.2 vector in the tests.
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "HKDF output length must be positive" }
        require(length <= MAX_OUTPUT_BYTES) { "HKDF output length must be at most $MAX_OUTPUT_BYTES" }

        val okm = ByteArray(length)
        var previousBlock = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            // The JVM version of this loop fed the MAC incrementally (`update` three times, then
            // `doFinal`). The seam offers one-shot HMAC only -- deliberately, so that a streaming
            // variant cannot become a second way to compute the same value -- so the three pieces
            // are concatenated here instead. Same bytes into the same PRF; the RFC 5869 vectors in
            // `HkdfTest` are what say so, including the multi-block A.2 case this loop exists for.
            val block = ByteArray(previousBlock.size + info.size + 1)
            previousBlock.copyInto(block)
            info.copyInto(block, destinationOffset = previousBlock.size)
            block[block.size - 1] = counter.toByte()
            previousBlock = hmacSha256(key = prk, message = block)

            val toCopy = minOf(previousBlock.size, length - written)
            previousBlock.copyInto(okm, destinationOffset = written, endIndex = toCopy)
            written += toCopy
            counter++
        }
        // The last block is usually only partially consumed; the unused half is still key material.
        previousBlock.fill(0)
        return okm
    }

    /**
     * The full `HKDF(salt, IKM, info, L)` — [extract] followed by [expand].
     *
     * The intermediate PRK is zeroed before returning; the caller owns [ikm] and the returned OKM.
     */
    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = extract(salt, ikm)
        try {
            return expand(prk, info, length)
        } finally {
            prk.fill(0)
        }
    }
}
