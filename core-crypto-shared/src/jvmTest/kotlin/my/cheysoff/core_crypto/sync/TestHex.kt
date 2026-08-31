package my.cheysoff.core_crypto.sync

/**
 * Hex helpers shared by the sync crypto tests.
 *
 * Published crypto test vectors are hex, and comparing hex strings rather than `ByteArray`s makes
 * a failure readable: JUnit prints "expected 077709… but was 3cb25f…" instead of "arrays first
 * differed at element [0]".
 */
internal fun hex(text: String): ByteArray {
    val cleaned = text.filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0) { "hex string must have an even number of digits" }
    return ByteArray(cleaned.length / 2) { index ->
        cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
