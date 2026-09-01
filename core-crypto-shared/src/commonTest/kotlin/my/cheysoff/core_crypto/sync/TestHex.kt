package my.cheysoff.core_crypto.sync

/**
 * Hex helpers shared by the sync crypto tests.
 *
 * Published crypto test vectors are hex, and comparing hex strings rather than `ByteArray`s makes
 * a failure readable: a test prints "expected 077709… but was 3cb25f…" instead of "arrays first
 * differed at element [0]".
 *
 * In `commonTest` rather than `jvmTest`, because the vector suites that use it now run on every
 * target the module has — including the Apple ones, where the JVM's `String.format` does not
 * exist. `jvmTest` depends on `commonTest`, so the JUnit suites that used this from here before
 * the move still see exactly these two functions.
 */
internal fun hex(text: String): ByteArray {
    val cleaned = text.filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0) { "hex string must have an even number of digits" }
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

/**
 * Lowercase hex, hand-rolled for the same reason [my.cheysoff.core_crypto.HlcNode] hand-rolls its
 * own: `"%02x".format(…)` is `java.lang.String.format` and does not exist off the JVM.
 */
internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value ushr 4])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
