package my.cheysoff.core_domain.attachment

/**
 * Every number the import path is allowed to know, in one place.
 *
 * The cap is the one that matters. 1 MiB of encoded image base64s to 1,398,102 bytes, which fits a
 * 2 MiB envelope with room to spare, and a 1 MiB row plus its overhead fits under Android's ~2 MB
 * `CursorWindow` -- but only just, and only one row at a time, which is why no query that returns
 * more than one row may select the bytes at all (`docs/design/image-attachments.md` §5).
 */
object AttachmentLimits {
    const val MAX_ATTACHMENT_BYTES = 1_048_576
    const val THUMB_LONG_EDGE = 320
    const val THUMB_QUALITY = 70

    /**
     * A thumbnail this size is read by every list query, so it is the number that decides whether
     * scrolling a folder of photo notes is fast. At 320 px and q70 a photograph lands well under it;
     * the cap exists so that a pathological image cannot quietly make the rail expensive.
     */
    const val MAX_THUMB_BYTES = 65_536

    /** Everything is re-encoded to JPEG (spec §3). Stored per row so a second format is additive. */
    const val MIME_JPEG = "image/jpeg"
}
