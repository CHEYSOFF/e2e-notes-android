package my.cheysoff.desktop.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The desktop's copy of the checklist storage format, which the list rows (`checklistProgress`) and
 * the draft (`parseChecklist`/`serializeChecklist`) still read and write for legacy checklist
 * fields. Byte-compatible with the phone's; modelled on its `ChecklistSerializationTest`.
 */
class DesktopChecklistTest {

    private fun item(text: String, done: Boolean) = DesktopChecklistItem(id = "ignored", text = text, isDone = done)

    /** Ids are ephemeral; only text and done state must survive. */
    private fun List<DesktopChecklistItem>.payload() = map { it.text to it.isDone }

    @Test
    fun `empty list and empty string round-trip`() {
        assertEquals("", emptyList<DesktopChecklistItem>().serializeChecklist())
        assertEquals(emptyList<DesktopChecklistItem>(), parseChecklist(""))
    }

    @Test
    fun `round-trips mixed items, including text that begins with a digit`() {
        val items = listOf(item("buy milk", false), item("walk the dog", true), item("", false), item("0 is text", false), item("1 too", true))
        assertEquals("0buy milk\n1walk the dog\n0\n00 is text\n11 too", items.serializeChecklist())
        assertEquals(items.payload(), parseChecklist(items.serializeChecklist()).payload())
    }

    @Test
    fun `a newline in an item is flattened so the format stays one item per line`() {
        assertEquals("line one line two", normalizeChecklistText("line one\nline two"))
        val raw = listOf(item("line one\nline two", false)).serializeChecklist()
        assertEquals("0line one line two", raw)
        assertEquals(listOf("line one line two" to false), parseChecklist(raw).payload())
    }

    @Test
    fun `progress counts done and total off the blob`() {
        assertEquals(2 to 3, checklistProgress(listOf(item("a", true), item("b", false), item("c", true)).serializeChecklist()))
        assertEquals(0 to 0, checklistProgress(""))
        assertEquals(0 to 1, checklistProgress("0"))
    }
}
