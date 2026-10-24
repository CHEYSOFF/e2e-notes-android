package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc

/**
 * The body half of saving a BLOCKS note, shared by the phone's and the desktop's repositories so
 * that the two cannot decide differently about the same save. Each repository still owns its own
 * storage, field clocks and [PictureLifecycle] writes; this decides only what the body becomes.
 *
 * The editor's body arrives with its clocks ignored (see [BlockStamping.stamp]); this stamps it
 * against the stored body and returns the text to store. The rules:
 *
 * - **A legacy stored body (PLAIN/HTML), or none, is no prior.** Every block is stamped with this
 *   save's clock: this is the note's first BLOCKS save, and nothing older exists to keep a clock
 *   from.
 * - **A stored BLOCKS body that does not decode is [Keep]: never overwritten.** It is most likely a
 *   newer build's format; the editor showed it read-only, and replacing it would destroy the
 *   content that build wrote. The caller keeps the stored body, its format and its checklist.
 * - **An empty editor body is the encoded empty document, never `""`.** A merge treats `""` as
 *   undecodable, so storing it would make the note look unreadable on every device.
 * - **An editor body that does not decode is [Keep] too**, when there is anything stored to keep.
 *   It is a bug upstream, and the stored body is the last good one. With nothing stored, the
 *   empty document is written instead, for the reason above.
 */
object BlocksSave {

    sealed interface Outcome

    /**
     * Store [content], which is `BlockCodec.encode(doc)`, and an empty checklist. [prior] is the
     * stored document [doc] was stamped against (null for a legacy or absent body), which
     * [PictureLifecycle] needs to tell a removed picture from one the editor never saw.
     */
    data class Write(val content: String, val doc: BlockDocument, val prior: BlockDocument?) : Outcome

    /** Leave the stored body (and checklist) as they are; [reason] is for the log. */
    data class Keep(val reason: String) : Outcome

    /**
     * The `note.content` of a BLOCKS save, built from the editor's blocks in visible order. **This
     * is the only way UI code may build that body.**
     *
     * The order has to survive the encoding, and only `pos` carries order: a [BlockDocument] sorts
     * its blocks by `(pos, id)`, so blocks encoded with blank or stale positions come back
     * re-sorted by id, and the save would silently reorder the note. This assigns provisional
     * increasing positions in list order. They mean nothing beyond that, because [prepare] stamps
     * the real ones and ignores these, and clocks are ignored the same way.
     */
    fun encodeForSave(visible: List<Block>): String {
        var previous: String? = null
        val ordered = visible.map { b ->
            val pos = FractionalIndex.between(previous, null)
            previous = pos
            b.copy(pos = pos)
        }
        return BlockCodec.encode(BlockDocument(ordered))
    }

    /**
     * @param priorFormat the stored note's format, or null if there is no stored note.
     * @param priorContent the stored note's body; ignored when [priorFormat] is null.
     * @param incoming the editor's body.
     * @param clock this save's clock, the same one the note row is stamped with.
     */
    fun prepare(priorFormat: NoteContentFormat?, priorContent: String, incoming: String, clock: Hlc): Outcome {
        val prior = if (priorFormat == NoteContentFormat.BLOCKS) {
            BlockCodec.decode(priorContent) ?: return Keep("the stored BLOCKS body does not decode")
        } else {
            null
        }
        val edited = when {
            incoming.isEmpty() -> emptyList()
            else -> BlockCodec.decode(incoming)?.visible()
                ?: if (priorFormat != null) return Keep("the editor's BLOCKS body does not decode") else emptyList()
        }
        val doc = BlockStamping.stamp(prior, edited, clock)
        return Write(BlockCodec.encode(doc), doc, prior)
    }
}
