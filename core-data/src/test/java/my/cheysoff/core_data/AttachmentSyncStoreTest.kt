package my.cheysoff.core_data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.DIRTY_ATTACHMENT_PAGE
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.sync.RoomSyncStore
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.FieldValue
import my.cheysoff.core_domain.sync.Hlc
import my.cheysoff.core_domain.sync.RecordType
import my.cheysoff.core_domain.sync.SyncRecord
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.MergedWrite
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `RoomSyncStore`'s `ATTACHMENT` branches: a photograph across the wire and back, and the two
 * halves of the note-tombstone rule for attachments.
 *
 * `SketchDeletionTest` is the same file for sketches and is the fuller one — the cascade's local
 * half, restore, purge and Trash expiry all live there and all already cover the attachment table
 * (`RoomNotesRepository.deleteNote` cascades to both). What is *new* in this task is the sync
 * store's side of it, so that is what this file pins: the record round trip, and
 * `reconcileAttachmentAgainstNote` / `tombstoneLiveAttachmentsOf`.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentSyncStoreTest {

    private lateinit var database: NoteDatabase
    private lateinit var repository: RoomNotesRepository
    private lateinit var syncStore: RoomSyncStore

    private val node = "testnode"
    private val clock = SyncClock(node = { node })

    /** Every clock the store mints, in the order it minted them. See the observer test below. */
    private val observed = mutableListOf<Hlc>()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomNotesRepository(
            database.noteDao, database.folderDao, database.sketchDao, database.attachmentDao,
            database, clock,
        )
        syncStore = RoomSyncStore(
            database = database,
            noteDao = database.noteDao,
            folderDao = database.folderDao,
            sketchDao = database.sketchDao,
            attachmentDao = database.attachmentDao,
            syncStateDao = database.syncStateDao,
            accountId = "acct-1",
            clockObserver = ClockObserver {
                observed += it
                clock.observe(it)
            },
        )
    }

    @After
    fun tearDown() = database.close()

    // -- fixtures ------------------------------------------------------------------------------

    /** Contains 0x00 and 0xFF, the two bytes a careless base64 round trip loses. */
    private val pixels = byteArrayOf(0, -1, 1, -128, 127, 0, -1)

    private fun attachment(
        id: String,
        noteId: String = "n1",
        meta: String = "",
    ) = AttachmentData(
        id = id,
        noteId = noteId,
        anchor = 0,
        order = 0,
        mimeType = "image/webp",
        width = 800,
        height = 600,
        bytes = pixels,
        thumbWidth = 40,
        thumbHeight = 30,
        thumbBytes = byteArrayOf(7, -7),
        createdAt = 1_000L,
        updatedAt = 1_000L,
        meta = meta,
    )

    private fun hlc(ms: Long, counter: Int = 0) = Hlc(ms, counter, node)

    private fun attachmentRecord(
        id: String,
        noteId: String = "n1",
        rowClock: Hlc,
        isDeleted: String = "0",
    ) = SyncRecord(
        type = RecordType.ATTACHMENT,
        uuid = id,
        rowClock = rowClock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.NOTE_ID to FieldValue.of(noteId),
            FieldClocks.ANCHOR to FieldValue.of("0"),
            FieldClocks.ORDER to FieldValue.of("0"),
            // `AP8BgH8A_w` is Base64Url.encode(pixels).
            FieldClocks.IMAGE to FieldValue.of("AP8BgH8A_w", "image/webp", "800", "600"),
            FieldClocks.THUMB to FieldValue.of("B_k", "40", "30"),
            FieldClocks.UPDATED_AT to FieldValue.of("1000"),
            FieldClocks.DELETED to FieldValue.of(isDeleted, if (isDeleted == "1") "900" else null),
        ),
    )

    private fun merged(record: SyncRecord, remoteMeta: String? = null) = MergedWrite(
        record = record,
        dirty = false,
        seq = 10L,
        contentBaseline = null,
        conflictCopy = null,
        remoteCreatedAt = 1_000L,
        remoteMeta = remoteMeta,
    )

    private fun noteRecord(rowClock: Hlc, isDeleted: String, deletedAt: String?) = SyncRecord(
        type = RecordType.NOTE,
        uuid = "n1",
        rowClock = rowClock,
        fieldClocks = emptyMap(),
        fields = mapOf(
            FieldClocks.TITLE to FieldValue.of("Title"),
            FieldClocks.CONTENT to FieldValue.of("Body", "plain"),
            FieldClocks.CHECKLIST to FieldValue.of(""),
            FieldClocks.PINNED to FieldValue.of("0"),
            FieldClocks.FAVORITE to FieldValue.of("0"),
            FieldClocks.FOLDER to FieldValue.of(null),
            FieldClocks.UPDATED_AT to FieldValue.of("1000"),
            FieldClocks.DELETED to FieldValue.of(isDeleted, deletedAt),
        ),
    )

    // -- the record round trip -----------------------------------------------------------------

    /**
     * A locally saved attachment becomes a record and comes back the same row.
     *
     * The bytes are the point: they leave the table as `ByteArray`, cross `RecordRows` as base64url
     * text, and have to arrive back byte-identical. An encoder that dropped a trailing group, or a
     * decoder that stopped at the first `0x00`, would show up here and nowhere else in this suite.
     */
    @Test
    fun `an attachment round-trips through the record and back into the row`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1", meta = """{"caption":"newer build"}"""))

        val record = syncStore.load(RecordType.ATTACHMENT, "a1")!!.record
        syncStore.applyMerged(merged(record, remoteMeta = """{"caption":"newer build"}"""))

        val stored = database.attachmentDao.attachmentRow("a1")!!
        assertArrayEquals("full-size bytes must survive base64", pixels, stored.bytes)
        assertArrayEquals(byteArrayOf(7, -7), stored.thumbBytes)
        assertEquals("image/webp", stored.mimeType)
        assertEquals(800, stored.width)
        assertEquals(600, stored.height)
        assertEquals(40, stored.thumbWidth)
        assertEquals(30, stored.thumbHeight)
        assertEquals("""{"caption":"newer build"}""", stored.meta)
    }

    /**
     * `meta` is opaque and a merged write that carries none must not blank it.
     *
     * This build only ever writes `""`, so the value under test can only have come from a newer
     * one — which is exactly the case the column exists for, and exactly the case where erasing it
     * would be invisible here and permanent everywhere.
     */
    @Test
    fun `a merged write with no incoming meta keeps the row's own`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1", meta = "keep-me"))

        syncStore.applyMerged(merged(attachmentRecord("a1", rowClock = hlc(9_000)), remoteMeta = null))

        assertEquals("keep-me", database.attachmentDao.attachmentRow("a1")!!.meta)
    }

    /** The push page is bounded: a row carries a mebibyte and the codec makes a second copy of it. */
    @Test
    fun `dirtyRecords never returns more attachments than one page`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repeat(DIRTY_ATTACHMENT_PAGE + 3) { repository.saveAttachment(attachment("a$it")) }

        val attachments = syncStore.dirtyRecords().filter { it.record.type == RecordType.ATTACHMENT }

        assertEquals(DIRTY_ATTACHMENT_PAGE, attachments.size)
    }

    // -- reconciliation, both directions -------------------------------------------------------

    /**
     * A live attachment arriving under a note this device already knows is dead.
     *
     * Not reachable by any cascade: the deleting device may have had no attachment support at all
     * (the desktop is a permanent example), so no tombstone for this row was ever minted anywhere.
     * Stored live it would be an orphan — never rendered, never tombstoned, never reaped.
     */
    @Test
    fun `an attachment whose note is already deleted is tombstoned on arrival`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.deleteNote("n1")

        syncStore.applyMerged(merged(attachmentRecord("orphan", rowClock = hlc(5_000))))

        val stored = database.attachmentDao.attachmentRow("orphan")!!
        assertTrue("an attachment whose note is dead must not be stored live", stored.isDeleted)
        assertNotNull("it needs a tombstone stamp to ever be purgeable", stored.deletedAt)
        assertTrue("the correction must be pushed back so other devices converge", stored.dirty)
    }

    /** The mirror-image control: a live note leaves an arriving live attachment alone. */
    @Test
    fun `an attachment whose note is alive is stored live and clean`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))

        syncStore.applyMerged(merged(attachmentRecord("a1", rowClock = hlc(5_000))))

        val stored = database.attachmentDao.attachmentRow("a1")!!
        assertFalse(stored.isDeleted)
        assertFalse("nothing needed correcting, so nothing needed re-pushing", stored.dirty)
    }

    /**
     * The other direction: the note's tombstone arrives and this device already holds attachments
     * live under it.
     *
     * Only on the transition into deleted — a re-stamp on every merged note write would leave every
     * attachment on the account permanently dirty.
     */
    @Test
    fun `a note merging into deleted tombstones the attachments held live under it`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1"))
        repository.saveAttachment(attachment("a2"))

        syncStore.applyMerged(
            merged(noteRecord(rowClock = hlc(9_000), isDeleted = "1", deletedAt = "8888")),
        )

        listOf("a1", "a2").forEach { id ->
            val stored = database.attachmentDao.attachmentRow(id)!!
            assertTrue("$id must be tombstoned with its note", stored.isDeleted)
            assertEquals("the note's own instant, so restore's >= can find it", 8888L, stored.deletedAt)
            assertTrue("$id must be pushed, or the tombstone converges nowhere", stored.dirty)
        }
    }

    /**
     * Every clock those tombstones were minted at reached the observer, and they are distinct.
     *
     * The observer is what stops the *next* local write — a `restoreNote`, say — from being minted
     * below a tombstone this store just wrote. That restore's `UPDATE` has no clock guard, so it
     * would look perfectly successful here and leave the photograph silently dead on every other
     * device. Distinctness is the second half: two attachments starting from the same clock must
     * not land on the same one, or the merge is left resolving them with a lexical tiebreak.
     */
    @Test
    fun `every tombstone clock is fed to the observer and no two are equal`() = runTest {
        repository.saveNote(Note(id = "n1", title = "Title", content = "Body"))
        repository.saveAttachment(attachment("a1"))
        repository.saveAttachment(attachment("a2"))
        observed.clear()

        syncStore.applyMerged(
            merged(noteRecord(rowClock = hlc(9_000), isDeleted = "1", deletedAt = "8888")),
        )

        val written = listOf("a1", "a2").map { database.attachmentDao.attachmentRow(it)!!.rowHlc() }
        assertEquals("two attachments, two distinct clocks", 2, written.toSet().size)
        assertTrue(
            "every minted clock must reach the generator: $written vs $observed",
            observed.containsAll(written),
        )
    }
}
