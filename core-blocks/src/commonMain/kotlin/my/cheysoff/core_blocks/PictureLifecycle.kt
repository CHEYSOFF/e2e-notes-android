package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.SketchData

/**
 * Keeps a note's picture and drawing records in step with the blocks that show them, on save.
 *
 * In a BLOCKS note the body is the one place that says which pictures the note has: deleting a
 * picture is deleting its block, and Undo is putting the block back. So the records follow the
 * saved document rather than the other way round:
 *
 * - a live record of the note that no block references is soft-deleted. This is safe because
 *   [OpenDocument.assemble] put every live record into the document the editor opened, so a
 *   record missing from the saved document is one the user removed;
 * - a soft-deleted record that a block references again is undeleted. That is how Undo of a
 *   picture delete brings the image back, on this device and, through the record's own clock, on
 *   the others;
 * - a referenced id with no record at all is left alone. Its record has not synced yet, and the
 *   block has to survive until it does (it shows a placeholder meanwhile).
 *
 * Only live blocks count as references. A tombstoned `pic:`/`drw:` id in [BlockDocument.gone] is a
 * picture the user deleted, so its record is deleted even if something else had kept it live.
 *
 * A PICTURE block's [Block.ref] names an attachment and a DRAWING block's names a sketch, never
 * the other way round; a ref of the wrong type references nothing.
 */
object PictureLifecycle {

    /** Record ids (not block ids) to soft-delete and to undelete. */
    data class Changes(val delete: Set<String>, val undelete: Set<String>)

    /** What reconciliation needs to know about one record: which it is and whether it is trashed. */
    data class RecordState(val id: String, val isDeleted: Boolean)

    fun reconcile(
        saved: BlockDocument,
        attachmentsOfNote: List<AttachmentData>,
        sketchesOfNote: List<SketchData>,
    ): Changes = reconcileStates(
        saved,
        pictures = attachmentsOfNote.map { RecordState(it.id, it.isDeleted) },
        drawings = sketchesOfNote.map { RecordState(it.id, it.isDeleted) },
    )

    /**
     * The same, over just the ids and tombstone flags. The phone uses this one so that a save does
     * not have to read every photograph's bytes off disk to learn whether it is deleted.
     */
    fun reconcileStates(saved: BlockDocument, pictures: List<RecordState>, drawings: List<RecordState>): Changes {
        val shownPictures = saved.blocks.refsOf(BlockType.PICTURE)
        val shownDrawings = saved.blocks.refsOf(BlockType.DRAWING)
        val delete = HashSet<String>()
        val undelete = HashSet<String>()
        fun walk(records: List<RecordState>, shown: Set<String>) = records.forEach {
            val referenced = it.id in shown
            if (!it.isDeleted && !referenced) delete += it.id
            if (it.isDeleted && referenced) undelete += it.id
        }
        walk(pictures, shownPictures)
        walk(drawings, shownDrawings)
        return Changes(delete, undelete)
    }

    private fun List<Block>.refsOf(type: BlockType): Set<String> =
        filter { it.type == type }.mapNotNullTo(HashSet()) { it.ref }
}
