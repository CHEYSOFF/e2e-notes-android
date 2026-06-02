package my.cheysoff.feature_notes

import my.cheysoff.feature_notes.model.single.ChecklistItem
import my.cheysoff.feature_notes.model.single.checklistProgress
import my.cheysoff.feature_notes.model.single.parseChecklist
import my.cheysoff.feature_notes.model.single.serializeChecklist
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistSerializationTest {

    private fun item(text: String, done: Boolean) =
        ChecklistItem(id = "ignored", text = text, isDone = done)

    /** Round-trip ignores ids (they're ephemeral); only text + done state must survive. */
    private fun List<ChecklistItem>.payload() = map { it.text to it.isDone }

    @Test
    fun `empty list serializes to empty string`() {
        assertEquals("", emptyList<ChecklistItem>().serializeChecklist())
    }

    @Test
    fun `empty string parses to empty list`() {
        assertEquals(emptyList<ChecklistItem>(), parseChecklist(""))
    }

    @Test
    fun `round-trips mixed done and undone items`() {
        val items = listOf(
            item("buy milk", false),
            item("walk the dog", true),
            item("", false),
        )
        val restored = parseChecklist(items.serializeChecklist())
        assertEquals(items.payload(), restored.payload())
    }

    @Test
    fun `preserves text that begins with a digit`() {
        // The leading done-flag char must not be confused with item text starting with 0 or 1.
        val items = listOf(item("0 is a valid start", false), item("1 too", true))
        val restored = parseChecklist(items.serializeChecklist())
        assertEquals(items.payload(), restored.payload())
    }

    @Test
    fun `newlines in item text are flattened so the format stays unambiguous`() {
        val restored = parseChecklist(listOf(item("line one\nline two", false)).serializeChecklist())
        assertEquals(listOf("line one line two" to false), restored.payload())
    }

    @Test
    fun `progress counts done and total without parsing items`() {
        val raw = listOf(
            item("a", true),
            item("b", false),
            item("c", true),
        ).serializeChecklist()
        assertEquals(2 to 3, checklistProgress(raw))
    }

    @Test
    fun `progress of empty blob is zero of zero`() {
        assertEquals(0 to 0, checklistProgress(""))
    }
}
