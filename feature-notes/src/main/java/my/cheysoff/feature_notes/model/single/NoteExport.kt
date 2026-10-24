package my.cheysoff.feature_notes.model.single

import my.cheysoff.core_blocks.BlockText
import my.cheysoff.core_blocks.LegacyImport
import my.cheysoff.core_domain.model.NoteContentFormat

/**
 * How a done / not-done checklist row is written when a note leaves the app as text. Plain ASCII
 * brackets rather than ballot-box characters, because the destination is arbitrary — an SMS field,
 * a terminal, someone else's mail client — and a box that renders as a tofu square is worse than
 * one that renders as two brackets everywhere.
 */
private const val DONE_MARKER = "[x] "
private const val TODO_MARKER = "[ ] "

/** Blank line between the title, the body and the checklist. */
private const val BLOCK_SEPARATOR = "\n\n"

/**
 * The exact text an editor "Copy text" or "Share" action hands to the system: title, body and
 * checklist, separated by blank lines.
 *
 * [plainBody] must already BE plain text — this function does no markup handling of any kind. The
 * overload below, which takes a stored body, produces it.
 *
 * Empty parts are dropped rather than emitted as blank blocks, so a note with only a checklist
 * shares as just the checklist. Checklist rows whose text is blank are dropped too: the editor
 * leaves an empty row behind after the checklist button is tapped, and "[ ]" on its own is noise to
 * whoever receives the note. A note with nothing in it at all returns "".
 */
fun buildNoteShareText(
    title: String,
    plainBody: String,
    checklist: List<ChecklistItem>,
): String {
    val blocks = mutableListOf<String>()

    title.trim().takeIf { it.isNotEmpty() }?.let { blocks += it }
    plainBody.trim().takeIf { it.isNotEmpty() }?.let { blocks += it }

    val rows = checklist
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { (if (it.isDone) DONE_MARKER else TODO_MARKER) + it.text.trim() }
    if (rows.isNotEmpty()) blocks += rows

    return blocks.joinToString(BLOCK_SEPARATOR)
}

/**
 * [buildNoteShareText] for a stored body in any format.
 *
 * A BLOCKS body is JSON, so it is read through [BlockText] instead of being handed over raw. An
 * HTML body (written by the pre-blocks editor) is read the way the block editor opens it,
 * [LegacyImport] then [BlockText.plain], so a share never carries markup; a PLAIN body is already
 * text. A leftover legacy checklist is still exported, but a row the body already shows as a check
 * line is dropped so an item that was converted into the body but not yet cleared from the field
 * does not appear twice.
 */
fun buildNoteShareText(
    title: String,
    content: String,
    format: NoteContentFormat,
    checklist: List<ChecklistItem>,
): String {
    val blocksBody = BlockText.bodyPlain(content, format)
        ?: when (format) {
            NoteContentFormat.HTML -> BlockText.plain(LegacyImport.toBlocks("", content, format, "")).trim()
            else -> return buildNoteShareText(title, content, checklist)
        }
    val bodyLines = blocksBody.lines().toSet()
    val leftover = checklist.filter {
        val text = it.text.trim()
        "☐ $text" !in bodyLines && "☑ $text" !in bodyLines
    }
    return buildNoteShareText(title, blocksBody, leftover)
}

/**
 * A one-line name for the note, for the places a share target wants a subject rather than the body
 * (mail clients, mostly). Falls back to a fixed label when the note is untitled — an empty subject
 * line is worse than a generic one.
 */
fun noteShareTitle(title: String): String = title.trim().ifEmpty { "Untitled note" }
