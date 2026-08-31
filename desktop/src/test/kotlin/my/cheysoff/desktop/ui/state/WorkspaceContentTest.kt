package my.cheysoff.desktop.ui.state

import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceContentTest {

    private val folders = listOf(
        Folder(id = "work", name = "Work", colorArgb = 0xFF112233),
        Folder(id = "home", name = "Personal"),
    )

    private fun note(
        id: String,
        title: String = id,
        folderId: String? = null,
        pinned: Boolean = false,
        content: String = "",
        format: NoteContentFormat = NoteContentFormat.PLAIN,
        checklist: String = "",
    ) = Note(
        id = id,
        title = title,
        content = content,
        contentFormat = format,
        checklist = checklist,
        isPinned = pinned,
        folderId = folderId,
    )

    @Test
    fun `a row takes its accent from the folder's explicit colour`() {
        val rows = listOf(note("a", folderId = "work")).toRows(folders)
        assertEquals(0xFF112233, rows.single().folderColorArgb)
    }

    @Test
    fun `a folder with no colour leaves the row to derive one`() {
        val rows = listOf(note("a", folderId = "home")).toRows(folders)
        assertNull(rows.single().folderColorArgb)
    }

    @Test
    fun `a row filed into a folder that no longer exists does not carry a stale colour`() {
        val rows = listOf(note("a", folderId = "deleted-folder")).toRows(folders)
        assertNull(rows.single().folderColorArgb)
    }

    @Test
    fun `html bodies reach the row as plain text and plain bodies reach it verbatim`() {
        val rows = listOf(
            note("html", content = "<p>He<b>llo</b></p>", format = NoteContentFormat.HTML),
            note("plain", content = "a < b", format = NoteContentFormat.PLAIN),
        ).toRows(folders)
        assertEquals("Hello", rows[0].snippet)
        assertEquals("a < b", rows[1].snippet)
    }

    @Test
    fun `checklist progress is counted onto the row`() {
        val rows = listOf(note("a", checklist = "1done\n0todo\n0todo")).toRows(folders)
        assertEquals(1, rows.single().checklistDone)
        assertEquals(3, rows.single().checklistTotal)
    }

    @Test
    fun `chips count notes per folder and keep empty folders`() {
        val rows = listOf(
            note("a", folderId = "work"),
            note("b", folderId = "work"),
            note("c"),
        ).toRows(folders)

        val chips = buildFolderChips(folders, rows)

        assertEquals(listOf(null, "work", "home"), chips.map { it.id })
        assertEquals(3, chips[0].count) // All
        assertEquals(2, chips[1].count) // Work
        assertEquals(0, chips[2].count) // Personal, empty but still offered
    }

    @Test
    fun `the folder filter narrows the list`() {
        val rows = listOf(note("a", folderId = "work"), note("b", folderId = "home")).toRows(folders)

        val filtered = buildListContent(rows, selectedFolderId = "work")

        assertEquals(listOf("a"), filtered.all.map { it.id })
    }

    @Test
    fun `a pinned note appears in Pinned and not again in Recent`() {
        val rows = listOf(note("p", pinned = true), note("r")).toRows(folders)

        val content = buildListContent(rows, selectedFolderId = null)

        assertEquals(listOf("p"), content.pinned.map { it.id })
        assertEquals(listOf("r"), content.recent.map { it.id })
    }

    @Test
    fun `the incoming sort order survives the split`() {
        // Rows arrive already ordered by the repository; nothing here may re-sort them.
        val rows = listOf(note("z"), note("a"), note("m")).toRows(folders)

        val content = buildListContent(rows, selectedFolderId = null)

        assertEquals(listOf("z", "a", "m"), content.recent.map { it.id })
    }

    @Test
    fun `an unfiled note is hidden by any folder filter`() {
        val rows = listOf(note("loose")).toRows(folders)
        assertTrue(buildListContent(rows, selectedFolderId = "work").isEmpty)
    }

    @Test
    fun `selection is kept when the note is still visible`() {
        val content = buildListContent(listOf(note("a"), note("b")).toRows(folders), null)
        assertEquals("b", resolveSelection(content, current = "b"))
    }

    @Test
    fun `selection falls to the first row when the selected note disappears`() {
        val content = buildListContent(listOf(note("a"), note("b")).toRows(folders), null)
        assertEquals("a", resolveSelection(content, current = "deleted"))
    }

    @Test
    fun `selection prefers a pinned note because Pinned is the first section`() {
        val rows = listOf(note("r"), note("p", pinned = true)).toRows(folders)
        val content = buildListContent(rows, null)
        assertEquals("p", resolveSelection(content, current = null))
    }

    @Test
    fun `selection is null only when there is nothing to select`() {
        assertNull(resolveSelection(NoteListContent(), current = "anything"))
    }
}
