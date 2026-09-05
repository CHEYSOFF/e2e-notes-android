package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.DIRTY_ATTACHMENT_PAGE
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.TrashPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 3: attachments are a child record, like sketches — see `docs/design/image-attachments.md`
 * §5/§6 and `SketchDeletionTest`, which this file mirrors. A note's deletion tombstones its
 * attachments as separate records with their own tombstones, never through `ON DELETE CASCADE`;
 * restoring the note restores exactly the attachments its own deletion tombstoned; purging the
 * note removes its attachment rows outright.
 *
 * Uses [RoomNotesRepository] throughout, not [my.cheysoff.core_data.data.local.AttachmentDao]
 * directly, for the same reason `SketchDeletionTest` does: a test that seeds through the DAO while
 * claiming to exercise "the real path" hides exactly the kind of dead seam sync bugs live in.
 */
@RunWith(RobolectricTestRunner::class)
class RoomNotesRepositoryAttachmentTest {

    private lateinit var database: NoteDatabase
    private lateinit var repository: RoomNotesRepository

    private val node = "testnode"
    private val clock = SyncClock(node = { node })

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomNotesRepository(
            database.noteDao, database.folderDao, database.sketchDao, database.attachmentDao, database, clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -- fixtures ----------------------------------------------------------------------------

    private fun attachment(
        id: String,
        noteId: String,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ) = AttachmentData(
        id = id,
        noteId = noteId,
        anchor = 0,
        order = 0,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        bytes = bytes,
        thumbWidth = 20,
        thumbHeight = 20,
        thumbBytes = byteArrayOf(9, 9),
        createdAt = 1_000L,
        updatedAt = 1_000L,
    )

    // -- saveAttachment / attachment / attachmentsOf, the basic round trip -------------------

    @Test
    fun `an attachment saved through the repository is readable back with its bytes intact`() = runTest {
        repository.saveAttachment(attachment("a1", "n1", bytes = byteArrayOf(5, 6, 7)))

        val stored = repository.attachment("a1")
        assertNotNull(stored)
        assertTrue(byteArrayOf(5, 6, 7).contentEquals(stored!!.bytes))
        assertEquals("n1", stored.noteId)
        assertTrue(
            "a freshly saved attachment must be dirty so it is pushed",
            database.attachmentDao.attachmentRow("a1")!!.dirty,
        )
    }

    /**
     * `meta` is an opaque escape hatch this build never writes anything but `""` into (see
     * `AttachmentData.meta`'s KDoc), but the round trip through the entity, the migration column
     * and the preview projection must not silently drop it -- a future build depends on this build
     * carrying it verbatim.
     */
    @Test
    fun `meta defaults to empty and round-trips through save, read and the preview projection`() = runTest {
        repository.saveAttachment(attachment("a1", "n1"))

        assertEquals("", repository.attachment("a1")!!.meta)
        assertEquals("", repository.attachmentsOf("n1").first().single().meta)
    }

    /**
     * A local edit must not blank a `meta` a newer build wrote.
     *
     * The row is seeded through the DAO on purpose, breaking this file's usual
     * seed-through-the-repository rule: `saveAttachment` deliberately has no path that writes a
     * caller-supplied `meta` (see its KDoc), so the repository cannot produce this state and a
     * newer build is the only thing that can. What is under test is the *edit*, which does go
     * through the real seam.
     *
     * The `AttachmentData` handed to [RoomNotesRepository.saveAttachment] carries `meta = ""`,
     * which is exactly what an editor screen constructing one from what it has in hand will pass.
     * Honouring that field would blank the caption here and push the blank to every device.
     */
    @Test
    fun `saving over a row written by a newer build keeps that build's meta`() = runTest {
        repository.saveAttachment(attachment("a1", "n1"))
        val seeded = database.attachmentDao.attachmentRow("a1")!!
        database.attachmentDao.upsertAttachment(seeded.copy(meta = "{\"caption\":\"newer build\"}"))

        repository.saveAttachment(attachment("a1", "n1", bytes = byteArrayOf(4, 5, 6)))

        val stored = database.attachmentDao.attachmentRow("a1")!!
        assertEquals("{\"caption\":\"newer build\"}", stored.meta)
        assertTrue("the edit itself must still land", byteArrayOf(4, 5, 6).contentEquals(stored.bytes))
    }

    // -- fix round 1, H1: dirtyAttachments is a memory bound, not a paging convenience --------

    /**
     * With more dirty rows than [DIRTY_ATTACHMENT_PAGE], the query must return **exactly** that
     * many -- not fewer, not all of them -- and the oldest clocks first, so a second call (the
     * next sync pass) makes progress instead of returning the same page forever.
     */
    @Test
    fun `dirtyAttachments returns at most the page size, oldest clock first`() = runTest {
        val ids = (0..DIRTY_ATTACHMENT_PAGE).map { "a$it" } // one more than the page size
        ids.forEach { id -> repository.saveAttachment(attachment(id, "n1")) }

        val page = database.attachmentDao.dirtyAttachments(DIRTY_ATTACHMENT_PAGE)

        assertEquals(
            "a bound that returns fewer or more than the page size is not the bound the KDoc promises",
            DIRTY_ATTACHMENT_PAGE,
            page.size,
        )
        for (i in 0 until page.size - 1) {
            assertTrue(
                "must be oldest-clock-first, or a repeated call cannot make progress through the backlog",
                page[i].rowHlc() <= page[i + 1].rowHlc(),
            )
        }
        assertFalse(
            "the page must be the OLDEST rows -- the most recently saved row must be left for the next pass",
            page.map { it.uuid }.contains(ids.last()),
        )
    }

    @Test
    fun `attachmentsOf lists previews for the note in anchor then sortOrder then id order`() = runTest {
        repository.saveAttachment(attachment("z", "n1").copy(anchor = 0, order = 1))
        repository.saveAttachment(attachment("a", "n1").copy(anchor = 0, order = 0))
        repository.saveAttachment(attachment("m", "n2"))

        val previews = repository.attachmentsOf("n1").first()

        assertEquals(listOf("a", "z"), previews.map { it.id })
    }

    // -- 1. deleting a note tombstones its attachments, by reconciliation not cascade --------

    @Test
    fun `deleting a note tombstones its attachments`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1", "n1"))
        repository.saveAttachment(attachment("a2", "n1"))
        val a1Before = database.attachmentDao.attachmentRow("a1")!!
        val a2Before = database.attachmentDao.attachmentRow("a2")!!
        val before = System.currentTimeMillis()

        repository.deleteNote("n1")

        val a1After = database.attachmentDao.attachmentRow("a1")!!
        val a2After = database.attachmentDao.attachmentRow("a2")!!

        assertTrue("a1 must be tombstoned", a1After.isDeleted)
        assertTrue("a2 must be tombstoned", a2After.isDeleted)
        assertTrue("a1's tombstone must carry a real stamp", a1After.deletedAt!! >= before)
        assertTrue("a2's tombstone must carry a real stamp", a2After.deletedAt!! >= before)
        assertTrue("a1 must be dirty so its tombstone is pushed", a1After.dirty)
        assertTrue("a2 must be dirty so its tombstone is pushed", a2After.dirty)

        assertTrue("a1 must have moved forward in the account's history", a1After.rowHlc() > a1Before.rowHlc())
        assertTrue("a2 must have moved forward in the account's history", a2After.rowHlc() > a2Before.rowHlc())

        // "Its own clock bump" -- each attachment's tombstone is a distinct write, not one clock
        // shared across the whole cascade the way deleteFolder shares one across many notes.
        assertNotEquals(
            "each attachment's tombstone must be its own clock, not a shared stamp",
            a1After.rowHlc(),
            a2After.rowHlc(),
        )

        assertTrue(
            "a tombstoned attachment must not render",
            repository.attachmentsOf("n1").first().isEmpty(),
        )

        val note = database.noteDao.noteRow("n1")!!
        assertTrue("the note itself is deleted too", note.isDeleted)

        // Every attachment tombstoned BY THIS DELETION shares the note's own wall-clock deletedAt
        // -- it is one deletion event -- which is exactly what restoreNote uses to tell "tombstoned
        // by this delete" apart from "already deleted beforehand".
        assertEquals(
            "an attachment tombstoned by this note's deletion must carry the note's own deletedAt",
            note.deletedAt,
            a1After.deletedAt,
        )
        assertEquals(
            "an attachment tombstoned by this note's deletion must carry the note's own deletedAt",
            note.deletedAt,
            a2After.deletedAt,
        )
    }

    @Test
    fun `deleting a note leaves another note's attachments alone`() = runTest {
        repository.saveNote(Note(id = "n1", title = "One", content = "Body"))
        repository.saveNote(Note(id = "n2", title = "Two", content = "Body"))
        repository.saveAttachment(attachment("a1", "n1"))
        repository.saveAttachment(attachment("a2", "n2"))

        repository.deleteNote("n1")

        assertTrue(database.attachmentDao.attachmentRow("a1")!!.isDeleted)
        assertFalse("n2's attachment must be untouched", database.attachmentDao.attachmentRow("a2")!!.isDeleted)
    }

    // -- 2. restoreNote un-tombstones exactly the attachments ITS OWN deletion tombstoned ----

    @Test
    fun `restoring a note restores the attachments it tombstoned`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1", "n1"))
        repository.saveAttachment(attachment("a2", "n1"))
        repository.deleteNote("n1")

        repository.restoreNote("n1")

        val a1After = database.attachmentDao.attachmentRow("a1")!!
        val a2After = database.attachmentDao.attachmentRow("a2")!!
        assertFalse("a1 must come back live", a1After.isDeleted)
        assertFalse("a2 must come back live", a2After.isDeleted)
        assertNull(a1After.deletedAt)
        assertNull(a2After.deletedAt)
        assertTrue("a1 must be dirty so the restore is pushed", a1After.dirty)
        assertTrue("a2 must be dirty so the restore is pushed", a2After.dirty)
        assertTrue(
            "both attachments render again",
            repository.attachmentsOf("n1").first().map { it.id }.containsAll(listOf("a1", "a2")),
        )
    }

    /**
     * The test that matters: nothing distinguishes "tombstoned by this note's deletion" from "the
     * user deleted this attachment individually, earlier" except the exact `deletedAt` match the
     * restore relies on. Getting this wrong resurrects a photo the user deliberately deleted.
     */
    @Test
    fun `restoring a note leaves an attachment deleted before it was`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("individually-deleted", "n1"))
        repository.saveAttachment(attachment("cascaded", "n1"))

        // Seeded directly at a fixed, deliberately old instant -- rather than through
        // `repository.deleteAttachment`, which stamps the real wall clock and could otherwise land
        // in the same millisecond as `deleteNote` below, making the two tombstones coincide by
        // sheer timing luck and this test's own precondition assertion flaky.
        val individuallyDeletedAt = 500L
        database.attachmentDao.softDeleteAttachment(
            uuid = "individually-deleted",
            timestamp = individuallyDeletedAt,
            hlcMs = individuallyDeletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        repository.deleteNote("n1")
        val cascadedDeletedAt = database.attachmentDao.attachmentRow("cascaded")!!.deletedAt
        // Precondition: the two tombstones must NOT coincide, or this test would prove nothing.
        assertNotEquals(individuallyDeletedAt, cascadedDeletedAt)

        repository.restoreNote("n1")

        assertTrue(
            "the individually-deleted attachment must stay deleted",
            database.attachmentDao.attachmentRow("individually-deleted")!!.isDeleted,
        )
        assertEquals(
            "its tombstone's own timestamp must be untouched by the note's restore",
            individuallyDeletedAt,
            database.attachmentDao.attachmentRow("individually-deleted")!!.deletedAt,
        )
        assertFalse(
            "the attachment tombstoned by the note's own deletion must come back",
            database.attachmentDao.attachmentRow("cascaded")!!.isDeleted,
        )
        assertEquals(listOf("cascaded"), repository.attachmentsOf("n1").first().map { it.id })
    }

    /**
     * The concurrent-merge shape: the note's tombstone and each attachment's tombstone are
     * independently clocked records, so a note deleted concurrently on two devices can merge to a
     * note `deletedAt` that differs from an attachment tombstoned by the very same event on the
     * other device. `>=`, not `==`, is what keeps that attachment from being stranded in Trash.
     */
    @Test
    fun `restoring a note restores an attachment whose merged deletedAt is later than the note's`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1", "n1"))

        val noteDeletedAt = 1_000L
        val attachmentDeletedAt = 1_500L // later: as if the attachment's own DELETED field won the merge
        database.noteDao.softDeleteNote(
            id = "n1",
            timestamp = noteDeletedAt,
            hlcMs = noteDeletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )
        database.attachmentDao.softDeleteAttachment(
            uuid = "a1",
            timestamp = attachmentDeletedAt,
            hlcMs = attachmentDeletedAt,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        repository.restoreNote("n1")

        assertFalse(
            "an attachment tombstoned at or after the note's own deletedAt must come back with it",
            database.attachmentDao.attachmentRow("a1")!!.isDeleted,
        )
        assertNull(database.attachmentDao.attachmentRow("a1")!!.deletedAt)
    }

    // -- 3. purgeNote's hard delete removes attachment rows -----------------------------------

    @Test
    fun `purging a note removes its attachment rows`() = runTest {
        repository.saveNote(Note(id = "n1", title = "", content = ""))
        repository.saveAttachment(attachment("a1", "n1"))
        repository.saveAttachment(attachment("a2", "n1"))

        repository.purgeNote("n1")

        assertNull("a live attachment anchored to a purged note must not survive it", database.attachmentDao.attachmentRow("a1"))
        assertNull("neither must a second one", database.attachmentDao.attachmentRow("a2"))
    }

    @Test
    fun `purging a note removes an already-tombstoned attachment under the same note too`() = runTest {
        repository.saveNote(Note(id = "n1", title = "", content = ""))
        repository.saveAttachment(attachment("a1", "n1"))
        repository.deleteAttachment("a1")
        assertTrue(
            "precondition: the attachment is tombstoned, not live",
            database.attachmentDao.attachmentRow("a1")!!.isDeleted,
        )

        repository.purgeNote("n1")

        assertNull(
            "a tombstoned attachment must not outlive the note it was purged with",
            database.attachmentDao.attachmentRow("a1"),
        )
    }

    @Test
    fun `purging a note leaves another note's attachment rows`() = runTest {
        repository.saveNote(Note(id = "n1", title = "", content = ""))
        repository.saveNote(Note(id = "n2", title = "", content = ""))
        repository.saveAttachment(attachment("a1", "n1"))
        repository.saveAttachment(attachment("a2", "n2"))

        repository.purgeNote("n1")

        assertNull(database.attachmentDao.attachmentRow("a1"))
        assertNotNull("n2's attachment must be untouched", database.attachmentDao.attachmentRow("a2"))
    }

    // -- deleteAttachment: the single-attachment repository seam -----------------------------

    @Test
    fun `deleteAttachment soft-deletes and stamps its own timestamp and clock`() = runTest {
        repository.saveAttachment(attachment("a1", "n1"))
        val before = System.currentTimeMillis()

        repository.deleteAttachment("a1")

        val stored = database.attachmentDao.attachmentRow("a1")!!
        assertTrue(stored.isDeleted)
        assertTrue("deletedAt must be stamped by the repository, not left to the caller", stored.deletedAt!! >= before)
        assertTrue("a delete must mark the row dirty so it is pushed", stored.dirty)
        assertTrue(
            "a deleted attachment must not render",
            repository.attachmentsOf("n1").first().isEmpty(),
        )
    }

    @Test
    fun `deleteAttachment is idempotent - a second delete does not re-stamp deletedAt`() = runTest {
        repository.saveAttachment(attachment("a1", "n1"))
        repository.deleteAttachment("a1")
        val firstDeletedAt = database.attachmentDao.attachmentRow("a1")!!.deletedAt

        repository.deleteAttachment("a1")

        assertEquals(
            "a second delete of an already-trashed attachment must not restart its retention window",
            firstDeletedAt,
            database.attachmentDao.attachmentRow("a1")!!.deletedAt,
        )
    }

    // -- purging expired trash must take attachments with it ----------------------------------

    @Test
    fun `purgeExpiredTrash purges a tombstoned attachment past retention, alongside its note`() = runTest {
        val now = 100L * TrashPolicy.RETENTION_MILLIS
        val expired = now - TrashPolicy.RETENTION_MILLIS - 1

        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1", "n1"))
        database.attachmentDao.softDeleteAttachment(
            uuid = "a1",
            timestamp = expired,
            hlcMs = expired,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        val purged = repository.purgeExpiredTrash(now)

        assertEquals("exactly the one expired attachment tombstone", 1, purged)
        assertNull("the attachment row must be gone", database.attachmentDao.attachmentRow("a1"))
    }

    @Test
    fun `purgeExpiredTrash keeps an attachment tombstone still inside the retention window`() = runTest {
        val now = 100L * TrashPolicy.RETENTION_MILLIS
        val fresh = now - TrashPolicy.RETENTION_MILLIS + 1

        repository.saveAttachment(attachment("a1", "n1"))
        database.attachmentDao.softDeleteAttachment(
            uuid = "a1",
            timestamp = fresh,
            hlcMs = fresh,
            hlcCounter = 0,
            hlcNode = node,
            fieldHlc = "",
        )

        val purged = repository.purgeExpiredTrash(now)

        assertEquals(0, purged)
        assertNotNull("a fresh tombstone must survive the purge", database.attachmentDao.attachmentRow("a1"))
    }
}
