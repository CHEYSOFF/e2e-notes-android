package my.cheysoff.core_editor

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionRulesTest {

    private val text = "Intro\n${Markers.CHECK_OPEN}buy milk\n${Markers.PICTURE}\n${Markers.BULLET}eggs"
    private val check = 6 // the checklist line's marker

    @Test
    fun `a selection starting on a checklist marker starts after it`() {
        assertEquals(TextRange(check + 1, check + 4), selectionPastMarker(text, TextRange(check, check + 4)))
    }

    @Test
    fun `a backwards selection keeps its direction`() {
        assertEquals(TextRange(check + 4, check + 1), selectionPastMarker(text, TextRange(check + 4, check)))
    }

    @Test
    fun `a bullet marker is skipped the same way`() {
        val bullet = text.lastIndexOf(Markers.BULLET)
        assertEquals(TextRange(bullet + 1, text.length), selectionPastMarker(text, TextRange(bullet, text.length)))
    }

    @Test
    fun `a selection over several lines keeps the marker`() {
        assertNull(selectionPastMarker(text, TextRange(check, text.length)))
    }

    @Test
    fun `cursors, text selections and pictures are left alone`() {
        assertNull(selectionPastMarker(text, TextRange(check)))
        assertNull(selectionPastMarker(text, TextRange(0, 3)))
        assertNull(selectionPastMarker(text, TextRange(check + 1, check + 4)))
        val picture = text.indexOf(Markers.PICTURE)
        assertNull(selectionPastMarker(text, TextRange(picture, picture + 1)))
    }
}
