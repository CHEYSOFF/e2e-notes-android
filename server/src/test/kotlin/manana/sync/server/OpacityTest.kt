package manana.sync.server

import io.ktor.client.statement.bodyAsText
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The property the whole design exists for: **the server never sees plaintext, and has no way to.**
 *
 * These tests seal a sentinel string the way the client's `RecordEnvelope` does -- AES-256-GCM with
 * a random nonce, `ver ‖ nonce ‖ ciphertext ‖ tag` -- push it, and then look for it in every
 * response body and in the raw bytes of the SQLite file on disk. The log file is checked the same
 * way by `LoggingTest`.
 */
class OpacityTest {

    private val sentinel = "PLAINTEXT-SENTINEL-buy-milk-and-call-the-dentist"

    /** Seals [plaintext] under a random key, in the client's envelope layout. */
    private fun seal(plaintext: String): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return byteArrayOf(1) + nonce + sealed
    }

    @Test
    fun noEndpointEverReturnsThePlaintextOfARecord() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        val envelope = seal(sentinel)

        val bodies = mutableListOf<String>()
        bodies += push(me.token, upsertItem(id, envelope, baseSeq = 0)).bodyAsText()
        bodies += push(me.token, upsertItem(id, seal("$sentinel v2"), baseSeq = 0)).bodyAsText()
        bodies += client.getAuth("/v1/changes?since=0", me.token).bodyAsText()
        bodies += client.getAuth("/v1/records/$id/history", me.token).bodyAsText()
        bodies += client.getAuth("/v1/devices", me.token).bodyAsText()
        bodies += client.getAuth("/healthz", null).bodyAsText()

        // The second push conflicts, so the conflicting envelope is returned inline. That path is
        // the one most likely to leak something by accident, which is why it is in this list.
        assertTrue(bodies.any { it.contains("conflict") }, "the conflict path was exercised")

        for (body in bodies) {
            assertFalse(body.contains(sentinel), "a response body contained the plaintext: $body")
            assertFalse(body.contains("buy-milk"), "a response body contained the plaintext")
        }
    }

    /** What goes in comes back out byte for byte -- and only ever as bytes. */
    @Test
    fun anEnvelopeRoundTripsUnmodified() = serverTest { harness ->
        val me = enrol(harness)
        val id = blindedId(1)
        val envelope = seal(sentinel)

        push(me.token, upsertItem(id, envelope, baseSeq = 0))
        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertContentEquals(envelope, B64.decodeOrNull(pull.records.single().envelope))
    }

    /**
     * The stored file itself. A server operator with root on the box gets the ciphertext and the
     * metadata around it; they do not get a note.
     */
    @Test
    fun theDatabaseFileContainsCiphertextAndNoPlaintext() {
        val dbPath = Files.createTempFile("manana-opacity", ".db")
        // SQLite creates the file itself; an existing empty one is fine, but start clean.
        dbPath.deleteIfExists()
        try {
            val envelope = seal(sentinel)
            serverTest(testConfig(databasePath = dbPath.toString())) { harness ->
                val me = enrol(harness)
                push(me.token, upsertItem(blindedId(1), envelope, baseSeq = 0))
                // The envelope must be on disk exactly as it arrived.
                val stored = harness.store.history(me.accountId, blindedId(1), 10).single()
                assertContentEquals(envelope, stored.envelope)
            }

            // The store closes with the test, which checkpoints WAL back into the main file; the
            // -wal file is read too so that this test does not depend on that having happened.
            val walPath = java.nio.file.Path.of("$dbPath-wal")
            val fileBytes = Files.readAllBytes(dbPath) +
                (if (Files.exists(walPath)) Files.readAllBytes(walPath) else ByteArray(0))
            assertTrue(indexOf(fileBytes, envelope) >= 0, "the ciphertext is not in the file")
            assertTrue(
                indexOf(fileBytes, sentinel.toByteArray(Charsets.UTF_8)) < 0,
                "the plaintext is in the database file",
            )
        } finally {
            dbPath.deleteIfExists()
            java.nio.file.Path.of("$dbPath-wal").deleteIfExists()
            java.nio.file.Path.of("$dbPath-shm").deleteIfExists()
        }
    }

    /**
     * The server has no code that opens an envelope. This is asserted the only way it can be
     * asserted from a test: a deliberately malformed envelope -- one that is not a valid
     * `ver ‖ nonce ‖ ct ‖ tag` at all -- is stored and returned exactly like any other, because
     * nothing on the server ever looks at its shape.
     *
     * If a future change added parsing or validation of the envelope, this test would start
     * failing, which is precisely the alarm it exists to raise.
     */
    @Test
    fun anEnvelopeThatIsNotAValidSealIsStillStoredAndReturned() = serverTest { harness ->
        val me = enrol(harness)
        val nonsense = byteArrayOf(99, 1, 2, 3)
        assertEquals(200, push(me.token, upsertItem(blindedId(1), nonsense, baseSeq = 0)).status.value)
        val pull: ChangesResponse = client.getAuth("/v1/changes?since=0", me.token).decode()
        assertContentEquals(nonsense, B64.decodeOrNull(pull.records.single().envelope))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
