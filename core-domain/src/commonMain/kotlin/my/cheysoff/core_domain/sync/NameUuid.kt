package my.cheysoff.core_domain.sync

/**
 * A version-3 (MD5, name-based) UUID, computed in pure Kotlin so that every platform derives the
 * same one.
 *
 * ## Why this exists rather than `java.util.UUID.nameUUIDFromBytes`
 *
 * [ConflictCopies.idFor] names a conflict copy from the record it preserves, and **both devices
 * resolving the same conflict must land on the same id**. If they do not, each publishes its own
 * copy, neither recognises the other's, and the account grows a duplicate on every sync forever.
 * That makes this function's output part of the sync wire contract, exactly like the envelope
 * format -- and a contract cannot be delegated to whichever implementation the host platform
 * happens to ship.
 *
 * On the JVM `nameUUIDFromBytes` is right there and is what this code used. An Apple or native
 * target has no such function, and reaching for the platform's own MD5 there would mean two
 * implementations whose only job is to agree with each other. So there is one implementation, in
 * common code, and `NameUuidParityTest` pins it against the JDK's over thousands of random inputs
 * -- which is what makes the change from the JDK version provably behaviour-preserving rather than
 * merely intended to be.
 *
 * ## On MD5
 *
 * MD5 is broken for every security purpose and is not used for one here; see the note on
 * [ConflictCopies.idFor]. The property required is determinism across devices, which MD5 has, and
 * RFC 4122 specifies MD5 for version-3 UUIDs, so matching it is what "a v3 UUID" means.
 */
internal object NameUuid {

    /** The RFC 4122 version-3 UUID of [name], formatted 8-4-4-4-12. */
    fun v3(name: ByteArray): String {
        val md = Md5.digest(name)
        // Version 3 in the high nibble of byte 6, IETF variant in the top bits of byte 8.
        md[6] = ((md[6].toInt() and 0x0f) or 0x30).toByte()
        md[8] = ((md[8].toInt() and 0x3f) or 0x80).toByte()
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val b = md[i].toInt() and 0xff
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * MD5 (RFC 1321), pure Kotlin. Used only by [NameUuid]; see the warning there before reaching for
 * it anywhere else.
 */
internal object Md5 {

    // Per-round left-rotation amounts.
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    // RFC 1321's K table, written out rather than computed from `sin`. The spec defines these as
    // the integer parts of `abs(sin(i)) * 2^32`, and computing them would make a wire-visible
    // constant depend on the host's floating-point sin -- a needless platform dependency in the
    // one place this file exists to remove them.
    private val K = intArrayOf(
        0xd76aa478L.toInt(), 0xe8c7b756L.toInt(), 0x242070dbL.toInt(), 0xc1bdceeeL.toInt(),
        0xf57c0fafL.toInt(), 0x4787c62aL.toInt(), 0xa8304613L.toInt(), 0xfd469501L.toInt(),
        0x698098d8L.toInt(), 0x8b44f7afL.toInt(), 0xffff5bb1L.toInt(), 0x895cd7beL.toInt(),
        0x6b901122L.toInt(), 0xfd987193L.toInt(), 0xa679438eL.toInt(), 0x49b40821L.toInt(),
        0xf61e2562L.toInt(), 0xc040b340L.toInt(), 0x265e5a51L.toInt(), 0xe9b6c7aaL.toInt(),
        0xd62f105dL.toInt(), 0x02441453L.toInt(), 0xd8a1e681L.toInt(), 0xe7d3fbc8L.toInt(),
        0x21e1cde6L.toInt(), 0xc33707d6L.toInt(), 0xf4d50d87L.toInt(), 0x455a14edL.toInt(),
        0xa9e3e905L.toInt(), 0xfcefa3f8L.toInt(), 0x676f02d9L.toInt(), 0x8d2a4c8aL.toInt(),
        0xfffa3942L.toInt(), 0x8771f681L.toInt(), 0x6d9d6122L.toInt(), 0xfde5380cL.toInt(),
        0xa4beea44L.toInt(), 0x4bdecfa9L.toInt(), 0xf6bb4b60L.toInt(), 0xbebfbc70L.toInt(),
        0x289b7ec6L.toInt(), 0xeaa127faL.toInt(), 0xd4ef3085L.toInt(), 0x04881d05L.toInt(),
        0xd9d4d039L.toInt(), 0xe6db99e5L.toInt(), 0x1fa27cf8L.toInt(), 0xc4ac5665L.toInt(),
        0xf4292244L.toInt(), 0x432aff97L.toInt(), 0xab9423a7L.toInt(), 0xfc93a039L.toInt(),
        0x655b59c3L.toInt(), 0x8f0ccc92L.toInt(), 0xffeff47dL.toInt(), 0x85845dd1L.toInt(),
        0x6fa87e4fL.toInt(), 0xfe2ce6e0L.toInt(), 0xa3014314L.toInt(), 0x4e0811a1L.toInt(),
        0xf7537e82L.toInt(), 0xbd3af235L.toInt(), 0x2ad7d2bbL.toInt(), 0xeb86d391L.toInt(),
    )

    fun digest(input: ByteArray): ByteArray {
        // Pad to a multiple of 64: one 0x80 byte, then zeros, then the original bit length as a
        // little-endian 64-bit value in the last eight bytes.
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 8) / 64 + 1) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = ((bitLength ushr (8 * i)) and 0xff).toByte()
        }

        var a0 = 0x67452301
        var b0 = 0xefcdab89L.toInt()
        var c0 = 0x98badcfeL.toInt()
        var d0 = 0x10325476

        val m = IntArray(16)
        var chunk = 0
        while (chunk < padded.size) {
            for (j in 0 until 16) {
                val o = chunk + j * 4
                m[j] = (padded[o].toInt() and 0xff) or
                    ((padded[o + 1].toInt() and 0xff) shl 8) or
                    ((padded[o + 2].toInt() and 0xff) shl 16) or
                    ((padded[o + 3].toInt() and 0xff) shl 24)
            }

            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val tmp = d
                d = c
                c = b
                b += (a + f + K[i] + m[g]).rotateLeft(S[i])
                a = tmp
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
            chunk += 64
        }

        val out = ByteArray(16)
        for ((i, word) in intArrayOf(a0, b0, c0, d0).withIndex()) {
            for (j in 0 until 4) out[i * 4 + j] = ((word ushr (8 * j)) and 0xff).toByte()
        }
        return out
    }
}
