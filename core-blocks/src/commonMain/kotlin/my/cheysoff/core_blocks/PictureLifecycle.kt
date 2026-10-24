package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.AttachmentData
import my.cheysoff.core_domain.model.SketchData

/**
 * Keeps a note's picture and drawing records in step with the blocks that show them, on save.
 *
 * In a BLOCKS note the body is the one place that says which pictures the note has: deleting a
 * picture is deleting its block, and Undo is putting the block back. So the records follow the
 * saved document, under two rules:
 *
 * - **Delete** a live record only if the *prior* stored document knew it and the saved one does not
 *   reference it. A record the prior document never knew is never deleted. That covers a record
 *   that synced in after the editor opened (the editor could not have shown it, so its absence from
 *   the save says nothing) and an older build's unreferenced record. [OpenDocument.assemble] keeps
 *   appending such records, and a later save writes them in as blocks. Deleting on mere absence
 *   instead would make a sync arriving mid-edit destroy the picture it brought.
 * - **Undelete** a soft-deleted record that the saved document references. That is how Undo of a
 *   picture delete brings the image back, on this device and, through the record's own clock, on
 *   the others.
 *
 * A referenced id with no record at all is left alone: the record has not synced yet, and the block
 * has to survive until it does (it shows a placeholder meanwhile).
 *
 * "The prior document knew it" means a live block referenced it, **or** the prior document holds a
 * tombstone for its `pic:`/`drw:` block id ([BlockIds]). A tombstone is a picture someone deleted
 * (possibly on another device, arriving by merge while the record itself did not change). Leaving
 * that record live would have [OpenDocument.assemble] append it again as a new block, which would
 * bring back a picture the user deleted.
 *
 * **A legacy or absent prior** (the note's first BLOCKS save) has no document to ask. Every live
 * record of the note at save time is treated as known, because `assemble` put each of them into the
 * editor. The residual window is a record that syncs in between opening a legacy note and its first
 * save. It is not in the editor, so that first save deletes it. The window closes with the first
 * save, and the delete is a soft one: the record is tombstoned, not purged.
 *
 * A PICTURE block's [Block.ref] names an attachment and a DRAWING block's names a sketch, never the
 * other way round. A ref of the wrong type references nothing.
 */
object PictureLifecycle {

    /** Record ids (not block ids) to soft-delete and to undelete. */
    data class Changes(val delete: Set<String>, val undelete: Set<String>)

    /** What reconciliation needs to know about one record: which it is and whether it is trashed. */
    data class RecordState(val id: String, val isDeleted: Boolean)

    /**
     * @param prior the stored document this save replaces, or null when the stored body is legacy
     *   or there is none (see the class KDoc).
     */
    fun reconcile(
        prior: BlockDocument?,
        saved: BlockDocument,
        attachmentsOfNote: List<AttachmentData>,
        sketchesOfNote: List<SketchData>,
    ): Changes = reconcileStates(
        prior,
        saved,
        pictures = attachmentsOfNote.map { RecordState(it.id, it.isDeleted) },
        drawings = sketchesOfNote.map { RecordState(it.id, it.isDeleted) },
    )

    /**
     * The same, over just the ids and tombstone flags. The phone uses this one so that a save does
     * not have to read every photograph's bytes off disk to learn whether it is deleted.
     */
    fun reconcileStates(
        prior: BlockDocument?,
        saved: BlockDocument,
        pictures: List<RecordState>,
        drawings: List<RecordState>,
    ): Changes {
        val delete = HashSet<String>()
        val undelete = HashSet<String>()
        fun walk(records: List<RecordState>, type: BlockType, blockId: (String) -> String) {
            val shown = saved.blocks.refsOf(type)
            val known = prior?.let { p ->
                p.blocks.refsOf(type) + p.gone.mapTo(HashSet()) { it.id }.let { goneIds ->
                    records.filter { blockId(it.id) in goneIds }.map { it.id }
                }
            }
            for (r in records) {
                val referenced = r.id in shown
                // known == null: legacy/absent prior, every live record was in the editor.
                if (!r.isDeleted && !referenced && (known == null || r.id in known)) delete += r.id
                if (r.isDeleted && referenced) undelete += r.id
            }
        }
        walk(pictures, BlockType.PICTURE, BlockIds::forPicture)
        walk(drawings, BlockType.DRAWING, BlockIds::forDrawing)
        return Changes(delete, undelete)
    }

    private fun List<Block>.refsOf(type: BlockType): Set<String> =
        filter { it.type == type }.mapNotNullTo(HashSet()) { it.ref }
}
