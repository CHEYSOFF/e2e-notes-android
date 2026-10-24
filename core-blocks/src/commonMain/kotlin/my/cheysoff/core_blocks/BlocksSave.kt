package my.cheysoff.core_blocks

import my.cheysoff.core_domain.model.NoteContentFormat
import my.cheysoff.core_domain.sync.Hlc

/**
 * The body half of saving a BLOCKS note, shared by the phone's and the desktop's repositories so
 * that the two cannot decide differently about the same save. Each repository still owns its own
 * storage, field clocks and [PictureLifecycle] writes; this decides only what the body becomes.
 *
 * The editor's body arrives with its clocks ignored (see [BlockStamping.stamp]). It is stamped
 * against the editor's **base**, the document the editor last loaded or applied, and the result is
 * then merged into the stored body.
 *
 * **Why the base, not the stored body.** While the editor is open, sync can merge the other
 * device's blocks into the stored body. Stamping against that body would treat every block the
 * editor never saw as deleted by the user and tombstone it. A picture whose block and record
 * arrived together would then also lose its record, through [PictureLifecycle]. Stamping against
 * the base claims a clock only for what the user changed. The merge with the stored body then keeps
 * everything else:
 * - local edits carry this save's clock, the newest, and win;
 * - blocks the base never knew come from the stored body unchanged;
 * - remote edits to blocks the user did not touch win by their newer clocks.
 *
 * The rules:
 *
 * - **Base.** It is the decoded `blocksBase`. When none is given, or it does not decode, the base is
 *   the stored body: a decodable stored BLOCKS body, or no base at all for a legacy (PLAIN/HTML)
 *   body or no body. With no base, every block is stamped with this save's clock. That is the
 *   note's first BLOCKS save, and nothing older exists to keep a clock from.
 * - **A stored BLOCKS body that does not decode is [Keep]: never overwritten.** It is most likely a
 *   newer build's format. The editor showed it read-only, and replacing it would destroy the
 *   content that build wrote. The caller keeps the stored body, its format and its checklist.
 * - **An empty editor body is the encoded empty document, never `""`.** A merge treats `""` as
 *   undecodable, so storing it would make the note look unreadable on every device.
 * - **An editor body that does not decode is [Keep] too**, when there is anything stored to keep.
 *   It is a bug upstream, and the stored body is the last good one. With nothing stored, the empty
 *   document is written instead, for the reason above, and [Write.warning] says so for the log.
 */
object BlocksSave {

    sealed interface Outcome

    /**
     * Store [content], which is `BlockCodec.encode(doc)`, and an empty checklist.
     *
     * [base] is the document [doc] was stamped against (null for no base), which [PictureLifecycle]
     * needs to tell a removed picture from one the editor never saw. [warning] is non-null when
     * something was repaired rather than refused, for the caller's log.
     */
    data class Write(
        val content: String,
        val doc: BlockDocument,
        val base: BlockDocument?,
        val warning: String? = null,
    ) : Outcome

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
     * @param blocksBase the encoded document the editor last loaded or applied, or null to use the
     *   stored body as the base.
     */
    fun prepare(
        priorFormat: NoteContentFormat?,
        priorContent: String,
        incoming: String,
        clock: Hlc,
        blocksBase: String? = null,
    ): Outcome {
        val stored = if (priorFormat == NoteContentFormat.BLOCKS) {
            BlockCodec.decode(priorContent) ?: return Keep("the stored BLOCKS body does not decode")
        } else {
            null
        }
        var warning: String? = null
        val edited = when {
            incoming.isEmpty() -> emptyList()
            else -> BlockCodec.decode(incoming)?.visible() ?: run {
                if (priorFormat != null) return Keep("the editor's BLOCKS body does not decode")
                warning = "the editor's BLOCKS body does not decode; storing an empty document"
                emptyList()
            }
        }
        val base = blocksBase?.let(BlockCodec::decode) ?: stored
        val stamped = BlockStamping.stamp(base, edited, clock)
        val doc = if (stored != null) BlockMerge.mergeDocs(stamped, stored) else stamped
        return Write(BlockCodec.encode(doc), doc, base, warning)
    }
}
