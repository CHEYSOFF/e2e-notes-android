package my.cheysoff.core_domain

import my.cheysoff.core_domain.model.NotesSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesSortOrderTest {
    @Test fun `every key round-trips`() {
        NotesSortOrder.entries.forEach { order ->
            assertEquals(order, NotesSortOrder.fromKey(order.key))
        }
    }

    @Test fun `missing preference falls back to the default`() {
        assertEquals(NotesSortOrder.DEFAULT, NotesSortOrder.fromKey(null))
    }

    @Test fun `unknown key falls back to the default`() {
        // e.g. a preference written by a newer build and then downgraded.
        assertEquals(NotesSortOrder.DEFAULT, NotesSortOrder.fromKey("sorted_by_vibes"))
        assertEquals(NotesSortOrder.DEFAULT, NotesSortOrder.fromKey(""))
    }

    @Test fun `keys are distinct so no two orders can collide in storage`() {
        val keys = NotesSortOrder.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `keys are decoupled from the constant names`() {
        // The persisted form must survive a rename of the enum constants; if these ever become
        // the same string, a rename would silently reset everyone's stored preference.
        assertTrue(NotesSortOrder.entries.none { it.key == it.name })
    }

    @Test fun `default is recently edited`() {
        assertEquals(NotesSortOrder.RECENTLY_EDITED, NotesSortOrder.DEFAULT)
    }
}
