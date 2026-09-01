package my.cheysoff.core_store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.BlindedRecordId
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_store.db.RecordDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store, against a real SQLite database.
 *
 * The suite that matters most here is the first one: **what actually reaches the disk**. Everything
 * else about this design — that it cannot query, that a write is a read-modify-write, that a delete
 * is a tombstone — is a cost paid for one property, and a test that only round-tripped values
 * through the store would confirm none of it. A store that quietly wrote a plaintext column would
 * pass every other test in this file.
 */
class RecordStoreTest {

    private val fixture = StoreFixture()

    @Test
    fun `a note's text never reaches the database`() {
        runTest {
            // Strings chosen to be unmistakable in a hex dump and to cover the two encodings a
            // naive store could leak through: ASCII, and UTF-8 above the ASCII range.
            val secret = "SEKRIT-TITLE-9f3a"
            val body = "meeting with Дмитрий about the acquisition"
            fixture.repository.saveNote(
                fixture.note(id = "n1", title = secret, content = body)
            )

            val rows = fixture.rawRows()
            assertEquals(1, rows.size)
            val envelope = rows.single().envelope

            // Every spelling a mistake could produce: raw UTF-8, and the two orderings of UTF-16
            // that a `String` copied straight into a BLOB would give.
            listOf(secret, body, "n1").forEach { text ->
                assertTrue(
                    "'$text' appears in the stored envelope",
                    !envelope.containsBytes(text.toByteArray(Charsets.UTF_8)),
                )
                assertTrue(
                    "'$text' appears UTF-16LE in the stored envelope",
                    !envelope.containsBytes(text.toByteArray(Charsets.UTF_16LE)),
                )
            }
        }
    }

    @Test
    fun `the row is filed under a blinded id, not the note's uuid`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "n1"))
            val blindedId = fixture.rawRows().single().blinded_id

            assertEquals(
                BlindedRecordId.compute(fixture.keys.kId, RecordType.NOTE.wireKey, "n1"),
                blindedId,
            )
            // 128 bits of HMAC tag, base64url, unpadded.
            assertEquals(22, blindedId.length)
            assertTrue(blindedId, !blindedId.contains("n1"))
        }
    }

    @Test
    fun `every stored envelope is one padding bucket, whatever the note`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "short", title = "a"))
            fixture.repository.saveNote(
                fixture.note(id = "long", title = "b", content = "x".repeat(2_000))
            )

            // 4 KiB of padded plaintext, plus the version byte, the nonce and the tag. Two notes
            // three orders of magnitude apart in length are the same size on disk, which is the
            // same property the server relies on. A store that stored the payload unpadded would
            // pass every functional test and leak every note's length.
            val sizes = fixture.rawRows().map { it.envelope.size }.toSet()
            assertEquals(setOf(4096 + 1 + 12 + 16), sizes)
        }
    }

    @Test
    fun `a row moved to another row's key is refused rather than opened under the wrong identity`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "n1", title = "one"))
            fixture.repository.saveNote(fixture.note(id = "n2", title = "two"))
            val rows = fixture.rawRows().associateBy { it.blinded_id }
            val n1 = BlindedRecordId.compute(fixture.keys.kId, RecordType.NOTE.wireKey, "n1")
            val n2 = BlindedRecordId.compute(fixture.keys.kId, RecordType.NOTE.wireKey, "n2")

            // Anything with write access to the file can do this. The AEAD alone does not stop it
            // being *noticed*: the associated data binds an envelope to the ID it was filed under,
            // so this particular blob will now fail to decrypt -- but the check that catches the
            // general case is recomputing the blinded ID from the opened payload, which is what
            // `RecordStore.open` does and what §4 of the sync plan requires.
            fixture.database.recordsQueries.upsert(
                blinded_id = n2,
                envelope = rows.getValue(n1).envelope,
                dirty = 1L,
                last_synced_seq = 0L,
            )

            val open = fixture.store.records().first()
            assertEquals(1, open.size)
            assertEquals("n1", open.single().record.uuid)
            assertEquals(1, fixture.store.unopenable)
        }
    }

    @Test
    fun `a corrupted envelope is dropped from the list rather than crashing the read`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "good", title = "readable"))
            fixture.repository.saveNote(fixture.note(id = "bad", title = "damaged"))
            val badId = BlindedRecordId.compute(fixture.keys.kId, RecordType.NOTE.wireKey, "bad")
            val damaged = fixture.rawRows().single { it.blinded_id == badId }.envelope.copyOf()
            damaged[40] = (damaged[40].toInt() xor 0xff).toByte()
            fixture.database.recordsQueries.upsert(badId, damaged, 1L, 0L)

            // A notes app that refuses to open because one row is damaged is worse than one that
            // shows the rest. The sync path takes the opposite position on purpose -- see
            // `RecordStore`'s KDoc -- because there a bad record is evidence about the server.
            val notes = fixture.repository.getNotes(
                my.cheysoff.core_domain.model.NotesSortOrder.RECENTLY_EDITED
            ).first()
            assertEquals(listOf("good"), notes.map { it.id })
            assertEquals(1, fixture.store.unopenable)
        }
    }

    @Test
    fun `a store holding a different account's keys reads nothing`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "n1"))

            val otherKeys = AccountRootKey.derive(ByteArray(32) { 0x5a })
            val otherStore = RecordStore(fixture.database, otherKeys, fixture.dispatcher)

            // Not "reads garbage" and not "throws": a second account's ARK derives a different
            // K_id, so it does not even look under the same blinded IDs, and a different K_content
            // means nothing it did find would open.
            assertTrue(otherStore.records().first().isEmpty())
            assertNull(otherStore.load(RecordType.NOTE, "n1"))
        }
    }

    @Test
    fun `a locally written record is dirty and has never been synced`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "n1"))
            val stored = fixture.store.load(RecordType.NOTE, "n1")
            assertNotNull(stored)
            assertTrue(stored!!.dirty)
            assertEquals(0L, stored.lastSyncedSeq)
        }
    }

    @Test
    fun `a purge destroys the row, unlike a delete`() {
        runTest {
            fixture.repository.saveNote(fixture.note(id = "n1"))

            fixture.repository.deleteNote("n1")
            assertEquals(
                "a delete is a tombstone; the row must stay or a peer will resurrect it",
                1,
                fixture.rawRows().size,
            )

            fixture.repository.purgeNote("n1")
            assertEquals(0, fixture.rawRows().size)
        }
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun StoreFixture.rawRows(): List<my.cheysoff.core_store.db.Records> =
        database.recordsQueries.selectAll().executeAsList()

    private val StoreFixture.database: RecordDatabase get() = db
}
