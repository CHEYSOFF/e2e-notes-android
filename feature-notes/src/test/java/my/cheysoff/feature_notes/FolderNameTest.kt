package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.list.normalizeFolderName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderNameTest {
    @Test fun `trims surrounding whitespace`() {
        assertEquals("Work", normalizeFolderName("  Work  "))
    }

    @Test fun `blank or empty returns null`() {
        assertNull(normalizeFolderName(""))
        assertNull(normalizeFolderName("   "))
    }

    @Test fun `keeps internal spacing`() {
        assertEquals("To Do", normalizeFolderName("To Do"))
    }
}
