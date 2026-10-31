package my.cheysoff.core_blocks

import my.cheysoff.core_domain.attachment.sortAttachments
import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.AttachmentPreview
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.model.SketchData
import my.cheysoff.core_domain.sketch.sortSketches
import my.cheysoff.core_domain.sync.NameUuid

/**
 * Builds the document the editor opens for a note: the body, plus anything the note owns that the
 * body does not yet show.
 *
 * Three things can exist outside the body. A legacy note has no blocks at all; a note edited on a
 * pre-blocks build can have a checklist in its separate field; and a picture or sketch record can
 * have arrived (synced, or added by an older build) with no block pointing at it. Opening appends
 * all of them, with deterministic ids and positions so both devices build the same document, and
 * the next save writes them into the body -- until then nothing is written, so opening a note never
 * makes it dirty.
 */
object OpenDocument {

    fun assemble(note: Note, attachments: List<AttachmentData>, sketches: List<SketchData>): BlockDocument {
        val gone: List<Tombstone>
        val blocks = ArrayList<Block>()
        if (note.contentFormat == NoteContentFormat.BLOCKS) {
            val doc = BlockCodec.decode(note.content)
            if (doc == null) {
                // A body this build cannot read is shown, not dropped: an empty editor would look
                // like the note vanished.
                blocks += Block(
                    id = NameUuid.v3("${note.id}/unreadable".encodeToByteArray()),
                    type = BlockType.PARAGRAPH,
                    text = note.content,
                    pos = FractionalIndex.between(null, null),
                )
                gone = emptyList()
            } else {
                blocks += doc.blocks
                gone = doc.gone
            }
            val have = blocks.mapTo(HashSet()) { it.id }
            // Same ids every time, so a second open (or the other device) lands on the same blocks.
            LegacyChecklist.parse(note.checklist).forEachIndexed { i, (text, done) ->
                val id = NameUuid.v3("${note.id}/checklist/$i".encodeToByteArray())
                if (id in have) return@forEachIndexed
                blocks += Block(id, BlockType.CHECK, text = text, done = done, pos = nextPos(blocks))
            }
        } else {
            blocks += LegacyImport.toBlocks(note.id, note.content, note.contentFormat, note.checklist)
            gone = emptyList()
        }

        val shown = HashSet<String>()
        for (b in blocks) {
            shown += b.id
            if (b.type == BlockType.PICTURE || b.type == BlockType.DRAWING) b.ref?.let { shown += it }
        }
        for (a in pending(note.id, attachments, sketches)) {
            val id = if (a.picture) BlockIds.forPicture(a.id) else BlockIds.forDrawing(a.id)
            if (id in shown) continue
            blocks += Block(
                id = id,
                type = if (a.picture) BlockType.PICTURE else BlockType.DRAWING,
                ref = a.id,
                pos = nextPos(blocks),
            )
        }
        return BlockDocument(blocks, gone)
    }

    private class Pending(val id: String, val picture: Boolean)

    /**
     * The note's live drawings, then its live pictures, each in the order the apps show them today:
     * by [sortSketches] and [sortAttachments] (anchor, then id). Both functions are reused rather
     * than re-derived, because `order` is reserved and is populated (max + 1), so putting it in the
     * key would visibly reorder existing notes. Drawings come first because both the phone and the
     * desktop render the sketch section above the attachment section.
     */
    private fun pending(noteId: String, attachments: List<AttachmentData>, sketches: List<SketchData>): List<Pending> {
        val drawings = sortSketches(sketches.filter { it.noteId == noteId && !it.isDeleted })
        val live = attachments.filter { it.noteId == noteId && !it.isDeleted }
        val pictures = sortAttachments(live.map { it.toPreview() })
        return drawings.map { Pending(it.id, false) } + pictures.map { Pending(it.id, true) }
    }

    private fun AttachmentData.toPreview() = AttachmentPreview(
        id = id, noteId = noteId, anchor = anchor, order = order, mimeType = mimeType,
        width = width, height = height, thumbWidth = thumbWidth, thumbHeight = thumbHeight,
        thumbBytes = thumbBytes, createdAt = createdAt, updatedAt = updatedAt,
    )

    /** A position after every block so far; blocks are not sorted yet while being assembled. */
    private fun nextPos(blocks: List<Block>): String =
        FractionalIndex.between(blocks.maxOfOrNull { it.pos }?.takeIf { it.isNotEmpty() }, null)
}
