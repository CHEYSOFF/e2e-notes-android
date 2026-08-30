package my.cheysoff.core_data

import my.cheysoff.core_data.data.local.NoteEntity
import my.cheysoff.core_data.data.local.toDomain
import my.cheysoff.core_data.data.local.toEntity
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteContentFormatMappingTest {

    @Test
    fun `storage values round-trip through the enum`() {
        for (format in NoteContentFormat.entries) {
            assertEquals(format, NoteContentFormat.fromStorage(format.storageValue))
        }
    }

    @Test
    fun `unknown storage values degrade to plain`() {
        // Rendering markup is recoverable; parsing prose as HTML is not. Anything we don't
        // recognise must land on the recoverable side.
        assertEquals(NoteContentFormat.PLAIN, NoteContentFormat.fromStorage(""))
        assertEquals(NoteContentFormat.PLAIN, NoteContentFormat.fromStorage("markdown"))
        assertEquals(NoteContentFormat.PLAIN, NoteContentFormat.fromStorage("HTML"))
    }

    @Test
    fun `entity to domain and back preserves the format`() {
        val plain = NoteEntity(
            id = "1",
            title = "t",
            content = "Email John <john@example.com> about Q3",
            contentFormat = NoteContentFormat.PLAIN.storageValue,
            isPinned = false,
            folderId = null,
        )
        assertEquals(NoteContentFormat.PLAIN, plain.toDomain().contentFormat)
        assertEquals(plain, plain.toDomain().toEntity())

        val html = plain.copy(
            id = "2",
            content = "<p>hello</p>",
            contentFormat = NoteContentFormat.HTML.storageValue,
        )
        assertEquals(NoteContentFormat.HTML, html.toDomain().contentFormat)
        assertEquals(html, html.toDomain().toEntity())
    }

    @Test
    fun `new notes default to plain`() {
        // An empty body reads identically either way, and the editor promotes the row to HTML on
        // its first write — so the default never has to be guessed at.
        assertEquals(NoteContentFormat.PLAIN, Note(id = "1", title = "", content = "").contentFormat)
        assertEquals(
            NoteContentFormat.PLAIN.storageValue,
            NoteEntity(id = "1", title = "", content = "", isPinned = false, folderId = null).contentFormat,
        )
    }
}
