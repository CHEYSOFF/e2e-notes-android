package my.cheysoff.core_crypto.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blinded record IDs — the label the server files a record under, and the reason the raw note UUID
 * never leaves the device.
 *
 * The three properties that matter, in order of how much damage their absence would do:
 *
 *  1. **Stable** across calls, processes and devices — otherwise every sync creates a new record
 *     instead of updating one, and the account fills with duplicates.
 *  2. **Separated by `recType`** — otherwise a note and a folder sharing a UUID collide.
 *  3. **Separated by `K_id`** — otherwise the same UUID produces the same label on two different
 *     accounts, which is a cross-account correlation handle the server operator gets for free.
 */
class BlindedRecordIdTest {

    private val kId = ByteArray(32) { it.toByte() }
    private val otherKId = ByteArray(32) { (it + 1).toByte() }
    private val uuid = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    @Test
    fun `the same inputs give the same id across calls`() {
        val first = BlindedRecordId.compute(kId, "note", uuid)
        val second = BlindedRecordId.compute(kId, "note", uuid)

        assertEquals(first, second)
    }

    @Test
    fun `different recTypes give different ids for the same uuid`() {
        val note = BlindedRecordId.compute(kId, "note", uuid)
        val folder = BlindedRecordId.compute(kId, "folder", uuid)

        assertNotEquals(note, folder)
    }

    @Test
    fun `a different K_id gives a different id for the same record`() {
        val mine = BlindedRecordId.compute(kId, "note", uuid)
        val theirs = BlindedRecordId.compute(otherKId, "note", uuid)

        assertNotEquals(mine, theirs)
    }

    @Test
    fun `different uuids give different ids`() {
        val first = BlindedRecordId.compute(kId, "note", uuid)
        val second = BlindedRecordId.compute(kId, "note", "00000000-0000-0000-0000-000000000000")

        assertNotEquals(first, second)
    }

    @Test
    fun `the id does not contain the uuid`() {
        // The headline privacy claim, asserted directly rather than inferred from "it is an HMAC".
        val id = BlindedRecordId.compute(kId, "note", uuid)

        assertTrue(!id.contains(uuid))
        assertTrue(!id.contains(uuid.substring(0, 8)))
    }

    @Test
    fun `the id is 22 unpadded base64url characters`() {
        // 16 bytes encode to 22 base64url characters with no `=` padding, and the alphabet must
        // stay URL-safe because this value becomes a path segment on the server.
        val id = BlindedRecordId.compute(kId, "note", uuid)

        assertEquals(22, id.length)
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `the recType separator prevents a boundary collision`() {
        // Without the ":" separator, ("note", "x1") and ("not", "ex1") would hash the same message
        // and collide. This asserts the separator is really in the message.
        val first = BlindedRecordId.compute(kId, "note", "x1")
        val second = BlindedRecordId.compute(kId, "not", "ex1")

        assertNotEquals(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty K_id is rejected`() {
        BlindedRecordId.compute(ByteArray(0), "note", uuid)
    }
}
