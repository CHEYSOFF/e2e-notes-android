package my.cheysoff.core_editor.ui

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import my.cheysoff.core_editor.Markers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [editorTextMenu]: only actions that cannot send the field's raw text (markers and all) out. */
class EditorTextMenuTest {

    @Test
    fun `clipboard and selection actions stay in the menu`() {
        for (key in listOf(
            TextContextMenuKeys.CutKey,
            TextContextMenuKeys.CopyKey,
            TextContextMenuKeys.PasteKey,
            TextContextMenuKeys.SelectAllKey,
            TextContextMenuKeys.AutofillKey,
        )) {
            assertTrue(isEditorMenuKey(key), "$key")
        }
        assertTrue(keepInEditorMenu(TextContextMenuSeparator))
    }

    @Test
    fun `any other action is dropped unless the editor added it with plain text`() {
        // Process-text (Translate) items are keyed by ProcessTextKey(id), smart-selection items by
        // their classification; both are just other keys here.
        assertFalse(isEditorMenuKey(Any()))
        assertFalse(isEditorMenuKey("process:com.google.android.apps.translate"))
        assertFalse(isEditorMenuKey(42))
        assertTrue(isEditorMenuKey(EditorPlainTextKey("process:com.example/.Translate")))
    }

    @Test
    fun `the text sent to another app is the plain reading of the selection`() {
        val raw = "${Markers.CHECK_OPEN}milk\n${Markers.BULLET}note\n${Markers.PICTURE}\nend"
        val sent = selectedPlainText(raw, 0, raw.length)
        assertEquals("☐ milk\n• note\nend", sent)
        assertTrue(sent.none { Markers.isPrivateUse(it) })
        // A backwards selection and out-of-range ends are read the same way.
        assertEquals("ilk", selectedPlainText(raw, 5, 2))
        assertEquals(sent, selectedPlainText(raw, -3, raw.length + 9))
    }
}
