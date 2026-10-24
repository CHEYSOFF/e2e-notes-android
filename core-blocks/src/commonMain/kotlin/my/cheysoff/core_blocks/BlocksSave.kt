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

    /** Store [content], which is `BlockCodec.encode(doc)`, and an empty checklist. */
    data class Write(val content: String, val doc: BlockDocument) : Outcome

    /** Leave the stored body (and checklist) as they are; [reason] is for the log. */
    data class Keep(val reason: String) : Outcome

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
        return Write(BlockCodec.encode(doc), doc)
    }
}
