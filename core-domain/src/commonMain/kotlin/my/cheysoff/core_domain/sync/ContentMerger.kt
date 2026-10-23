package my.cheysoff.core_domain.sync

/**
 * A finer merge for a note body than "take the side with the greater clock".
 *
 * [Merge] knows nothing about what is inside a body; that is its strength, and a format that can
 * do better (BLOCKS, merged block by block in `core-blocks`) plugs in here instead of teaching the
 * merge a format. Values are the `content` field's [FieldValue], whose parts are
 * `[content, contentFormat]`.
 *
 * ## The contract an implementation must keep
 *
 * Whatever it returns becomes the merged body at clock `max(local, remote)`. For the sync merge to
 * stay convergent, the bodies it accepts must form a join: commutative, associative and idempotent,
 * so N devices exchanging records in any order end in the same body. Return **null** for any pair
 * it does not fully understand (another format, a body it cannot decode). Null means "not mine",
 * and the field falls back to plain last-writer-wins and a conflict copy, exactly as before.
 */
fun interface ContentMerger {

    /** The merged body, or null to leave this pair to last-writer-wins. */
    fun merge(local: FieldValue, remote: FieldValue): FieldValue?

    companion object {
        /** Declines every pair: today's behaviour, and the default everywhere. */
        val NONE = ContentMerger { _, _ -> null }
    }
}
