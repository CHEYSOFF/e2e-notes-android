package my.cheysoff.core_sync_net

import my.cheysoff.core_sync_net.auth.SignedMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The canonical signed-message encoding, against the specification in `server/README.md`.
 *
 * These are golden-byte tests rather than round-trip tests, because there is nothing to round-trip:
 * the encoding is one-way and the only thing that makes it correct is that the *server* builds the
 * same bytes. A test that encoded and decoded with this file's own code would pass under any
 * definition of the format, including a wrong one.
 *
 * The real cross-check is `SyncServerContractTest`, which enrols against the running server. What
 * these tests add is a named failure at the point of the mistake: if the domain string, the field
 * order, or the length prefixing changes, this fails immediately and says which, rather than
 * failing as `401 bad_signature` from a server three layers away.
 */
class SignedMessageTest {

    @Test
    fun `the encoding is length-prefixed with a big-endian uint16 per field`() {
        // lp("manana/sync/v1/sig") | lp("claim") | lp("A") | lp("B") | lp("1")
        val encoded = SignedMessage.claim("A", "B", 1L)

        val expected = lengthPrefixed("manana/sync/v1/sig") +
            lengthPrefixed("claim") +
            lengthPrefixed("A") +
            lengthPrefixed("B") +
            lengthPrefixed("1")

        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `the domain string comes first and is itself prefixed`() {
        val encoded = SignedMessage.session("acc", "dev", "chal")

        assertEquals(0, encoded[0].toInt())
        assertEquals("manana/sync/v1/sig".length, encoded[1].toInt())
        assertEquals(
            "manana/sync/v1/sig",
            String(encoded, 2, "manana/sync/v1/sig".length, Charsets.UTF_8),
        )
    }

    /**
     * The property the length prefixes exist for. Without them, `("authorize", "AB", "C")` and
     * `("authorize", "A", "BC")` are the same bytes, so a signature authorising one public key
     * would verify as a signature authorising a different one.
     */
    @Test
    fun `adjacent fields cannot be re-split into a different message`() {
        assertNotEquals(
            SignedMessage.authorize("AB", "C", 1L).toList(),
            SignedMessage.authorize("A", "BC", 1L).toList(),
        )
    }

    /**
     * The purpose is its own field so that a signature made to open a session can never be
     * presented as a signature that enrols a device.
     */
    @Test
    fun `two purposes over the same fields produce different messages`() {
        assertNotEquals(
            SignedMessage.encode("claim", "a", "b", "c").toList(),
            SignedMessage.encode("authorize", "a", "b", "c").toList(),
        )
    }

    /**
     * `ts` is a decimal string inside the signed message and a JSON number in the body. The server
     * rebuilds the message with `ts.toString()`, so any other rendering here -- padded, grouped,
     * scientific -- signs bytes the server never reconstructs.
     */
    @Test
    fun `the timestamp is signed as a plain decimal string`() {
        val encoded = SignedMessage.claim("acc", "key", 1_700_000_000_000L)
        assertArrayEquals(
            lengthPrefixed("1700000000000"),
            encoded.copyOfRange(encoded.size - 2 - "1700000000000".length, encoded.size),
        )
    }

    @Test
    fun `a multi-byte field is prefixed with its UTF-8 length, not its character count`() {
        val label = "Вова" // 4 characters, 8 UTF-8 bytes
        val encoded = SignedMessage.encode("claim", label)
        val prefixOffset = 2 + "manana/sync/v1/sig".length + 2 + "claim".length
        val declared = (encoded[prefixOffset].toInt() and 0xFF shl 8) or
            (encoded[prefixOffset + 1].toInt() and 0xFF)
        assertEquals(8, declared)
    }

    private fun lengthPrefixed(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(
            ((bytes.size ushr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte(),
        ) + bytes
    }
}
