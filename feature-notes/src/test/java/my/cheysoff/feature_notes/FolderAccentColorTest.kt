package my.cheysoff.feature_notes

import androidx.compose.ui.graphics.Color
import my.cheysoff.core_ui.theme.CategoryColors
import my.cheysoff.core_ui.theme.colorForCategory
import my.cheysoff.core_ui.theme.folderAccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderAccentColorTest {

    @Test
    fun `no folder yields null so caller picks the neutral fallback`() {
        assertNull(folderAccentColor(null, null))
        assertNull(folderAccentColor(null, 0xFF15695EL))
        assertNull(folderAccentColor("", 0xFF15695EL))
        assertNull(folderAccentColor("   ", null))
    }

    @Test
    fun `explicit folder color wins`() {
        val teal = 0xFF15695EL
        assertEquals(Color(teal.toInt()), folderAccentColor("work", teal))
    }

    @Test
    fun `null folder color falls back to the deterministic hash palette`() {
        val resolved = folderAccentColor("work", null)
        assertEquals(colorForCategory("work"), resolved)
        // The fallback for a real (non-blank) folder is always a palette color.
        assertTrue(resolved in CategoryColors)
    }
}
