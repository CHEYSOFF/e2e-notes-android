package my.cheysoff.core_crypto.platform

/**
 * AES-GCM assembled from a raw AES block cipher — NIST SP 800-38D.
 *
 * ## Why this file exists, which is not a comfortable answer
 *
 * Hand-written cryptographic construction is a bad default and this project has said so elsewhere.
 * It is here because the alternative on Apple is nothing at all.
 *
 * Kotlin/Native's `platform.CoreCrypto` bindings expose CommonCrypto's cipher modes as ECB, CBC,
 * CFB, CFB8, CTR, OFB and RC4. **There is no GCM.** Not `kCCModeGCM`, not `CCCryptorGCM`, not the
 * `CCCryptorGCMOneshot*` pair — none of the symbols are in the klib. That was checked by reading
 * the symbol table of
 * `~/.konan/…/klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.CommonCrypto`,
 * not inferred from a compile error, and `docs/BUILDING-IOS.md` records how to re-check it when the
 * bindings change.
 *
 * The options that leaves are:
 *
 *  - **CryptoKit**, which is Swift-only. Kotlin/Native reaches C and Objective-C, so it would need
 *    an Objective-C-visible Swift shim living in the Xcode project and injected down into this
 *    module — making the crypto something the *app* supplies. A library that cannot decrypt its own
 *    storage without the app handing it a cipher is a worse design than this file.
 *  - **A cinterop `.def`** re-declaring the deprecated `CCCryptorGCM`. That runs cinterop against
 *    the Apple SDK headers, which only exists on macOS, so it would give up the Apple klib
 *    cross-compilation that is currently the only way any of this code gets compiled at all. And
 *    `CCCryptorGCM` is the one whose decrypt path writes the computed tag out instead of checking
 *    it.
 *  - **This.** GHASH and the counter arithmetic in portable Kotlin, over an AES block cipher the
 *    platform provides.
 *
 * ## What makes it defensible
 *
 * The part that is hand-written is the part that can be *checked*, and the part that is hard is
 * left to the platform.
 *
 *  - AES itself is not here. [BlockCipher] is one ECB call into CommonCrypto on Apple, which is the
 *    hardware-accelerated, constant-time implementation Apple ships.
 *  - Everything in this file is **key-independent control flow**. The GF(2�128) multiply below
 *    branches on bits of the *hash subkey* rather than using a precomputed table, which is slower
 *    and is the variant that does not leak through cache timing.
 *  - It is `commonMain`, so it compiles and runs on the JVM, where `GaloisCounterModeTest` checks it
 *    against the published McGrew & Viega vectors **and** differentially against the JCA's own
 *    `AES/GCM/NoPadding` over hundreds of random inputs. That is a far stronger position than the
 *    Apple actuals would be in on their own: the composition is verified on a machine that has a
 *    reference implementation, and only "does CommonCrypto's AES-ECB encrypt a block correctly" is
 *    left to the first run on a Mac.
 *  - The JVM does **not** use it. `PlatformCrypto.jvmCommon.kt` still calls the JCA directly, so
 *    every envelope an Android device has ever written is still produced by the same provider code
 *    as before, and this file cannot regress it.
 *
 * ## Scope
 *
 * 96-bit nonces and 128-bit tags only, which is all this protocol has (`SyncProtocol.NONCE_BYTES`,
 * `TAG_BYTES`). A shorter or longer IV needs the GHASH-based `J0` derivation of SP 800-38D §7.1
 * step 2, which is deliberately not implemented rather than implemented untested.
 */
internal object GaloisCounterMode {

    /** AES in ECB, over a whole number of 16-byte blocks. The only thing the platform supplies. */
    internal fun interface BlockCipher {
        /**
         * Encrypts every 16-byte block of [input] independently.
         *
         * ECB is used **only** to produce a keystream and the hash subkey from values this file
         * controls, never over user data — which is the one context in which ECB is not a mistake.
         * A whole-buffer call rather than a per-block one because it is one platform call for a
         * record instead of two hundred and fifty-six.
         */
        fun encryptBlocks(input: ByteArray): ByteArray
    }

    private const val BLOCK_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    /**
     * The GF(2¹²⁸) reduction polynomial, `11100001 ‖ 0¹²⁰`, in the bit order GCM uses.
     *
     * GCM numbers bits from the most significant end, so the polynomial that is written
     * `x¹²⁸ + x⁷ + x² + x + 1` appears as `0xE1` in the *first* byte. Getting this convention
     * backwards produces a GHASH that is self-consistent and wrong, which is why this file is
     * checked against published vectors rather than against itself.
     */
    private const val REDUCTION_HIGH = -0x1F00000000000000L // 0xE100000000000000

    /** Encrypts and authenticates, returning `ciphertext ‖ tag`. */
    fun seal(
        cipher: BlockCipher,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        requireNonce(nonce)
        val subkey = hashSubkey(cipher)
        val ciphertext = counterCrypt(cipher, nonce, plaintext)
        val tag = tag(cipher, subkey, nonce, aad, ciphertext)

        val out = ByteArray(ciphertext.size + TAG_BYTES)
        ciphertext.copyInto(out)
        tag.copyInto(out, destinationOffset = ciphertext.size)
        return out
    }

    /** Verifies and decrypts `ciphertext ‖ tag`, or returns null if the tag does not match. */
    fun open(
        cipher: BlockCipher,
        nonce: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray? {
        requireNonce(nonce)
        if (sealed.size < TAG_BYTES) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_BYTES)
        val received = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)

        val subkey = hashSubkey(cipher)
        val expected = tag(cipher, subkey, nonce, aad, ciphertext)
        // The tag is compared BEFORE anything is decrypted, and in constant time. Returning
        // plaintext that has not been authenticated turns this into AES-CTR with no integrity at
        // all; comparing with `contentEquals` would leak, through timing, how many leading bytes of
        // a forged tag were right, which is enough to forge one byte at a time.
        if (!constantTimeEquals(expected, received)) return null
        return counterCrypt(cipher, nonce, ciphertext)
    }

    private fun requireNonce(nonce: ByteArray) {
        require(nonce.size == NONCE_BYTES) {
            "this implementation supports 96-bit nonces only, was ${nonce.size} bytes"
        }
    }

    /** `H = E_K(0¹²⁸)` — SP 800-38D §7.1 step 1. */
    private fun hashSubkey(cipher: BlockCipher): Pair<Long, Long> {
        val h = cipher.encryptBlocks(ByteArray(BLOCK_BYTES))
        require(h.size == BLOCK_BYTES) { "the block cipher returned ${h.size} bytes for one block" }
        return h.readLong(0) to h.readLong(8)
    }

    /**
     * `GCTR(inc32(J0), input)` — the encryption half, which is its own inverse.
     *
     * With a 96-bit nonce, `J0 = IV ‖ 0³¹ ‖ 1` and the data blocks start at `J0 + 1`, so the first
     * counter here is 2. Starting at 1 instead would reuse the block that masks the tag as
     * keystream, which is a total break; it is also exactly the off-by-one the published vectors
     * catch immediately.
     *
     * The whole keystream is produced in a single [BlockCipher.encryptBlocks] call. ECB encrypts
     * each block independently, so a buffer of consecutive counter blocks encrypts to the
     * concatenated keystream — one platform call per record rather than one per sixteen bytes.
     */
    private fun counterCrypt(cipher: BlockCipher, nonce: ByteArray, input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val blocks = (input.size + BLOCK_BYTES - 1) / BLOCK_BYTES
        val counters = ByteArray(blocks * BLOCK_BYTES)
        for (index in 0 until blocks) {
            val offset = index * BLOCK_BYTES
            nonce.copyInto(counters, destinationOffset = offset)
            writeCounter(counters, offset + NONCE_BYTES, index + 2)
        }
        val keystream = cipher.encryptBlocks(counters)

        val out = ByteArray(input.size)
        for (index in input.indices) {
            out[index] = (input[index].toInt() xor keystream[index].toInt()).toByte()
        }
        return out
    }

    /**
     * `T = MSB₁₂₈(GCTR(J0, GHASH_H(A ‖ 0* ‖ C ‖ 0* ‖ [len(A)]₆₄ ‖ [len(C)]₆₄)))`.
     *
     * The two length fields are in **bits**, not bytes, and they are what stops an attacker moving
     * bytes between the associated data and the ciphertext while keeping the hash the same. Both
     * halves are zero-padded to a block boundary first, and the padding is not counted.
     */
    private fun tag(
        cipher: BlockCipher,
        subkey: Pair<Long, Long>,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        var high = 0L
        var low = 0L

        fun absorb(data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val remaining = data.size - offset
                if (remaining >= BLOCK_BYTES) {
                    high = high xor data.readLong(offset)
                    low = low xor data.readLong(offset + 8)
                } else {
                    // The short final block is zero-padded to a full block. Building the padded
                    // block explicitly rather than masking keeps this obviously the same operation
                    // as the full-block case.
                    val padded = ByteArray(BLOCK_BYTES)
                    data.copyInto(padded, destinationOffset = 0, startIndex = offset)
                    high = high xor padded.readLong(0)
                    low = low xor padded.readLong(8)
                }
                val product = multiply(high, low, subkey.first, subkey.second)
                high = product.first
                low = product.second
                offset += BLOCK_BYTES
            }
        }

        absorb(aad)
        absorb(ciphertext)

        high = high xor (aad.size.toLong() * 8)
        low = low xor (ciphertext.size.toLong() * 8)
        val product = multiply(high, low, subkey.first, subkey.second)

        val hash = ByteArray(BLOCK_BYTES)
        hash.writeLong(0, product.first)
        hash.writeLong(8, product.second)

        // `J0` itself, counter 1, masks the hash. This is the block `counterCrypt` must never
        // reuse -- see its KDoc.
        val mask = ByteArray(BLOCK_BYTES)
        nonce.copyInto(mask)
        writeCounter(mask, NONCE_BYTES, 1)
        val encryptedMask = cipher.encryptBlocks(mask)

        return ByteArray(TAG_BYTES) { index ->
            (hash[index].toInt() xor encryptedMask[index].toInt()).toByte()
        }
    }

    /**
     * Multiplication in GF(2¹²⁸) — SP 800-38D §6.3, the shift-and-add algorithm.
     *
     * Deliberately the table-free version. A 4-bit or 8-bit table would be several times faster and
     * would index memory with bits derived from the hash subkey, which is the cache-timing side
     * channel that has produced real GHASH key-recovery attacks. This runs 128 fixed iterations
     * with no data-dependent memory access; the branch on a bit of `x` selects between XOR-ing and
     * not, which on any implementation of interest here is not the leak the table is.
     */
    private fun multiply(xHigh: Long, xLow: Long, yHigh: Long, yLow: Long): Pair<Long, Long> {
        var zHigh = 0L
        var zLow = 0L
        var vHigh = yHigh
        var vLow = yLow

        for (bit in 0 until 128) {
            // GCM numbers bits from the most significant end of the block.
            val set = if (bit < 64) {
                (xHigh ushr (63 - bit)) and 1L
            } else {
                (xLow ushr (127 - bit)) and 1L
            }
            if (set == 1L) {
                zHigh = zHigh xor vHigh
                zLow = zLow xor vLow
            }
            val carry = vLow and 1L
            vLow = (vLow ushr 1) or (vHigh shl 63)
            vHigh = vHigh ushr 1
            // The reduction touches only the high word: the polynomial's non-zero bits are all in
            // the first byte.
            if (carry == 1L) vHigh = vHigh xor REDUCTION_HIGH
        }
        return zHigh to zLow
    }

    /** The 32-bit big-endian block counter that follows a 96-bit nonce. */
    private fun writeCounter(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun ByteArray.readLong(offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
        }
        return value
    }

    private fun ByteArray.writeLong(offset: Int, value: Long) {
        for (index in 0 until 8) {
            this[offset + index] = (value ushr (56 - index * 8)).toByte()
        }
    }

    /** Compares in time that depends on the length only, never on where the first difference is. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }
}
