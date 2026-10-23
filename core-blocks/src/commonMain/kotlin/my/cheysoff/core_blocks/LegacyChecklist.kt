package my.cheysoff.core_blocks

/**
 * The checklist storage format, parsed in one place. Phone and desktop each used to carry their own
 * copy of this loop; two copies pinned together only by mirrored tests can drift, and a drift would
 * make the same checklist read differently on the user's two devices.
 *
 * Format: one item per line, first character `1`/`0` for done/undone, the rest of the line is the
 * text. An empty string is an empty list. Anything but a leading `1` reads as not done, so a line
 * from a future format is shown rather than dropped.
 */
object LegacyChecklist {
    fun parse(raw: String): List<Pair<String, Boolean>> =
        if (raw.isEmpty()) emptyList()
        else raw.split("\n").map { line -> line.drop(1) to (line.firstOrNull() == '1') }
}
