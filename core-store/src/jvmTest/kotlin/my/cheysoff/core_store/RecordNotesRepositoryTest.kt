package my.cheysoff.core_store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.model.TrashPolicy
import my.cheysoff.core_domain.sync.FieldClocks
import my.cheysoff.core_domain.sync.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NotesRepository`, as implemented over sealed records.
 *
 * ## What this suite is checking
 *
 * Two different things, and it is worth keeping them apart.
 *
 * **The interface's semantics.** Trash hides a note from the list but keeps its row; the editor
 * cannot open a deleted note; deleting a folder unfiles its notes and restoring it does not re-file
 * them; expiry is decided by the `now` the caller passes. Every one of these is stated in
 * `NotesRepository`'s KDoc, is relied on by screens that were written against the Android
 * implementation, and would be a silently different app if this implementation drifted.
 *
 * **The field-level clocks.** These are invisible from the interface and are the reason this store
 * exists at all. A gesture that pins a note must stamp `isPinned` and leave `content` at the clock
 * it had, or a merge will let the pin overwrite a newer body from another device. Nothing in the UI
 * can observe that; only a test at this layer can.
 */
class RecordNotesRepositoryTest {

    private val fixture = StoreFixture()
    private val repository get() = fixture.repository

    private suspend fun notes(order: NotesSortOrder = NotesSortOrder.RECENTLY_EDITED) =
        repository.getNotes(order).first()

    private suspend fun clockOf(id: String, field: String) =
        fixture.store.load(RecordType.NOTE, id)!!.record.clockOf(field)

    // -------------------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------------------

    @Test
    fun `a saved note comes back with every field intact`() = runTest {
        val original = fixture.note("n1", title = "Groceries", content = "<p>milk</p>")
            .copy(contentFormat = NoteContentFormat.HTML, checklist = "a|1", isPinned = true)
        repository.saveNote(original)

        val back = repository.getNoteById("n1").first()!!
        assertEquals(original.title, back.title)
        assertEquals(original.content, back.content)
        assertEquals(NoteContentFormat.HTML, back.contentFormat)
        assertEquals(original.checklist, back.checklist)
        assertTrue(back.isPinned)
        assertEquals(original.createdAt, back.createdAt)
        assertEquals(original.updatedAt, back.updatedAt)
    }

    @Test
    fun `a note with no folder keeps a null folder, not an empty string`() = runTest {
        repository.saveNote(fixture.note("n1", folderId = null))
        assertNull(repository.getNoteById("n1").first()!!.folderId)

        repository.saveNote(fixture.note("n2", folderId = ""))
        assertEquals("", repository.getNoteById("n2").first()!!.folderId)
    }

    @Test
    fun `recently edited puts the newest first`() = runTest {
        repository.saveNote(fixture.note("old"))
        fixture.tick()
        repository.saveNote(fixture.note("new"))
        assertEquals(listOf("new", "old"), notes().map { it.id })
    }

    @Test
    fun `title order is case-insensitive`() = runTest {
        repository.saveNote(fixture.note("b", title = "banana"))
        repository.saveNote(fixture.note("a", title = "Apple"))
        repository.saveNote(fixture.note("c", title = "cherry"))
        assertEquals(
            listOf("a", "b", "c"),
            notes(NotesSortOrder.TITLE_ASC).map { it.id },
        )
    }

    @Test
    fun `two notes saved in the same millisecond still sort deterministically`() = runTest {
        // Without the id tie-break the two would swap between emissions of the same list, which
        // reads to a user as the list flickering.
        repository.saveNote(fixture.note("zzz"))
        repository.saveNote(fixture.note("aaa"))
        assertEquals(notes().map { it.id }, notes().map { it.id })
        assertEquals(listOf("aaa", "zzz"), notes().map { it.id })
    }

    // -------------------------------------------------------------------------------------
    // Trash
    // -------------------------------------------------------------------------------------

    @Test
    fun `a deleted note leaves the list, enters Trash, and cannot be opened`() = runTest {
        repository.saveNote(fixture.note("n1"))
        fixture.tick()
        repository.deleteNote("n1")

        assertTrue(notes().isEmpty())
        assertEquals(listOf("n1"), repository.getDeletedNotes().first().map { it.id })
        assertNull(
            "the editor must not open a note the user threw away",
            repository.getNoteById("n1").first(),
        )
    }

    @Test
    fun `restore is lossless`() = runTest {
        repository.saveNote(
            fixture.note("n1", title = "keep me", content = "body", isPinned = true)
        )
        repository.deleteNote("n1")
        repository.restoreNote("n1")

        val back = repository.getNoteById("n1").first()!!
        assertEquals("keep me", back.title)
        assertEquals("body", back.content)
        assertTrue(back.isPinned)
        assertFalse(back.isDeleted)
        assertNull(back.deletedAt)
    }

    @Test
    fun `a gesture that changes nothing writes nothing at all`() = runTest {
        // Not merely "the note looks the same afterwards". The row must be byte-for-byte
        // untouched, because a write advances the row clock and sets `dirty` -- so a no-op restore
        // that still wrote would queue a version for the server whose only content is a newer
        // clock, and on the receiving device that clock would beat a real concurrent edit.
        repository.saveNote(fixture.note("n1", isPinned = true))
        val before = fixture.store.load(RecordType.NOTE, "n1")!!.record
        fixture.tick()

        repository.restoreNote("n1")
        repository.setNotePinned("n1", true)
        repository.setNoteFolder("n1", null)

        assertEquals(before, fixture.store.load(RecordType.NOTE, "n1")!!.record)
    }

    @Test
    fun `deleting a note that is already in Trash does not restart its retention`() = runTest {
        repository.saveNote(fixture.note("n1"))
        repository.deleteNote("n1")
        val deletedAt = repository.getDeletedNotes().first().single().deletedAt

        fixture.tick(TrashPolicy.RETENTION_MILLIS / 2)
        repository.deleteNote("n1")

        assertEquals(deletedAt, repository.getDeletedNotes().first().single().deletedAt)
    }

    @Test
    fun `Trash is newest-deleted first`() = runTest {
        repository.saveNote(fixture.note("first"))
        repository.saveNote(fixture.note("second"))
        repository.deleteNote("first")
        fixture.tick()
        repository.deleteNote("second")
        assertEquals(
            listOf("second", "first"),
            repository.getDeletedNotes().first().map { it.id },
        )
    }

    @Test
    fun `expired Trash is purged and unexpired Trash is not`() = runTest {
        repository.saveNote(fixture.note("stale"))
        repository.saveNote(fixture.note("fresh"))
        repository.deleteNote("stale")
        fixture.tick(TrashPolicy.RETENTION_MILLIS)
        repository.deleteNote("fresh")

        val purged = repository.purgeExpiredTrash(now = fixture.wallMillis)
        assertEquals(1, purged)
        assertEquals(listOf("fresh"), repository.getDeletedNotes().first().map { it.id })
        // The row is gone, not tombstoned again.
        assertNull(fixture.store.load(RecordType.NOTE, "stale"))
    }

    @Test
    fun `purging nothing reports nothing`() = runTest {
        repository.saveNote(fixture.note("n1"))
        assertEquals(0, repository.purgeExpiredTrash(now = fixture.wallMillis))
    }

    @Test
    fun `a purge is irreversible and immediate`() = runTest {
        repository.saveNote(fixture.note("n1"))
        repository.purgeNote("n1")
        assertNull(repository.getNoteById("n1").first())
        assertTrue(repository.getDeletedNotes().first().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Folders
    // -------------------------------------------------------------------------------------

    @Test
    fun `folders come back in case-insensitive name order`() = runTest {
        repository.saveFolder(fixture.folder("b", name = "work"))
        repository.saveFolder(fixture.folder("a", name = "Admin"))
        assertEquals(listOf("a", "b"), repository.getFolders().first().map { it.id })
    }

    @Test
    fun `deleting a folder unfiles its notes in the same write`() = runTest {
        repository.saveFolder(fixture.folder("f1"))
        repository.saveNote(fixture.note("in", folderId = "f1"))
        repository.saveNote(fixture.note("out", folderId = "f2"))
        fixture.tick()

        repository.deleteFolder("f1")

        assertTrue(repository.getFolders().first().isEmpty())
        assertEquals(listOf("f1"), repository.getDeletedFolders().first().map { it.id })
        assertNull(repository.getNoteById("in").first()!!.folderId)
        assertEquals(
            "a note in another folder must not be touched",
            "f2",
            repository.getNoteById("out").first()!!.folderId,
        )
    }

    @Test
    fun `a restored folder comes back empty`() = runTest {
        // Stated in `NotesRepository.restoreFolder`'s KDoc and worth a test, because it is the kind
        // of behaviour a future change would "fix" without noticing that nothing records which
        // notes were in the folder.
        repository.saveFolder(fixture.folder("f1"))
        repository.saveNote(fixture.note("n1", folderId = "f1"))
        repository.deleteFolder("f1")
        repository.restoreFolder("f1")

        assertEquals(listOf("f1"), repository.getFolders().first().map { it.id })
        assertNull(repository.getNoteById("n1").first()!!.folderId)
    }

    @Test
    fun `deleting a folder that does not exist does nothing`() = runTest {
        repository.saveNote(fixture.note("n1", folderId = "ghost"))
        repository.deleteFolder("ghost")
        assertEquals("ghost", repository.getNoteById("n1").first()!!.folderId)
    }

    // -------------------------------------------------------------------------------------
    // Metadata gestures
    // -------------------------------------------------------------------------------------

    @Test
    fun `pin, favourite and folder gestures each take effect`() = runTest {
        repository.saveNote(fixture.note("n1"))
        repository.setNotePinned("n1", true)
        repository.setNoteFavorite("n1", true)
        repository.setNoteFolder("n1", "f1")

        val note = repository.getNoteById("n1").first()!!
        assertTrue(note.isPinned)
        assertTrue(note.isFavorite)
        assertEquals("f1", note.folderId)
    }

    @Test
    fun `a gesture on a note that is not there is silently ignored`() = runTest {
        // A UI gesture on a row the Trash sweep purged a moment ago is a race, not a crash.
        repository.setNotePinned("ghost", true)
        repository.setNoteFolder("ghost", "f1")
        assertTrue(notes().isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // Field clocks — invisible from the interface, and the reason the store is shaped this way
    // -------------------------------------------------------------------------------------

    @Test
    fun `a new note has no field clocks, because every field is at the row clock`() = runTest {
        repository.saveNote(fixture.note("n1"))
        val record = fixture.store.load(RecordType.NOTE, "n1")!!.record
        assertTrue(
            "a freshly created record's map is legitimately empty",
            record.fieldClocks.isEmpty(),
        )
    }

    @Test
    fun `pinning a note stamps isPinned and leaves content where it was`() = runTest {
        repository.saveNote(fixture.note("n1"))
        val contentClockBefore = clockOf("n1", FieldClocks.CONTENT)
        fixture.tick()

        repository.setNotePinned("n1", true)

        // The whole argument for field-level clocks in one assertion. If pinning stamped `content`
        // too, a merge would treat the pin as a newer body and let it overwrite a real edit made on
        // another device in between.
        assertEquals(contentClockBefore, clockOf("n1", FieldClocks.CONTENT))
        assertNotEquals(contentClockBefore, clockOf("n1", FieldClocks.PINNED))
        assertTrue(clockOf("n1", FieldClocks.PINNED) > contentClockBefore)
    }

    @Test
    fun `editing the body stamps content and leaves the pin where it was`() = runTest {
        repository.saveNote(fixture.note("n1"))
        repository.setNotePinned("n1", true)
        val pinClock = clockOf("n1", FieldClocks.PINNED)
        fixture.tick()

        val note = repository.getNoteById("n1").first()!!
        repository.saveNote(note.copy(content = "a real edit", updatedAt = fixture.wallMillis))

        assertEquals(pinClock, clockOf("n1", FieldClocks.PINNED))
        assertTrue(clockOf("n1", FieldClocks.CONTENT) > pinClock)
    }

    @Test
    fun `saving a note unchanged moves the row clock and no field clock`() = runTest {
        repository.saveNote(fixture.note("n1"))
        val before = fixture.store.load(RecordType.NOTE, "n1")!!.record
        fixture.tick()
        repository.saveNote(repository.getNoteById("n1").first()!!)
        val after = fixture.store.load(RecordType.NOTE, "n1")!!.record

        assertTrue("the row clock always advances on a write", after.rowClock > before.rowClock)
        // Every field is now behind the row clock, so every field must be written down: the sparse
        // convention says an absent field is AT the row clock, and these no longer are.
        after.type.fields.forEach { field ->
            assertEquals(
                "'$field' was not written and must keep its old clock",
                before.rowClock,
                after.clockOf(field),
            )
        }
    }

    @Test
    fun `deleting a note stamps the tombstone and nothing else`() = runTest {
        repository.saveNote(fixture.note("n1"))
        val titleClock = clockOf("n1", FieldClocks.TITLE)
        fixture.tick()
        repository.deleteNote("n1")

        assertEquals(titleClock, clockOf("n1", FieldClocks.TITLE))
        assertTrue(clockOf("n1", FieldClocks.DELETED) > titleClock)
    }

    @Test
    fun `createdAt survives a rewrite that tries to change it`() = runTest {
        // `created` has no clock and cannot be merged, so the stored value wins. A stale editor
        // state that carried an old `createdAt` must not be able to rewrite history.
        repository.saveNote(fixture.note("n1"))
        val created = repository.getNoteById("n1").first()!!.createdAt
        fixture.tick()
        repository.saveNote(fixture.note("n1", title = "changed").copy(createdAt = 1L))
        assertEquals(created, repository.getNoteById("n1").first()!!.createdAt)
    }
}
