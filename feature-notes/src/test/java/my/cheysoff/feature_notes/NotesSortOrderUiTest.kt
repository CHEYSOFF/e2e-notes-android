package my.cheysoff.feature_notes

import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.feature_notes.model.list.menuLabel
import my.cheysoff.feature_notes.model.list.pillLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesSortOrderUiTest {
    @Test fun `every order has a non-blank label of both kinds`() {
        NotesSortOrder.entries.forEach { order ->
            assertTrue(order.menuLabel.isNotBlank())
            assertTrue(order.pillLabel.isNotBlank())
        }
    }

    @Test fun `menu labels are distinct`() {
        val labels = NotesSortOrder.entries.map { it.menuLabel }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test fun `pill labels are distinct`() {
        // The pill only ever shows the short form, so two orders sharing one would make the
        // active order unreadable without opening the menu.
        val labels = NotesSortOrder.entries.map { it.pillLabel }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test fun `pill labels stay short enough for the chip row`() {
        NotesSortOrder.entries.forEach { order ->
            assertTrue(
                "pill label too long: ${order.pillLabel}",
                order.pillLabel.length <= 8,
            )
        }
    }
}
