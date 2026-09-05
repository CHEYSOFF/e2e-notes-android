package my.cheysoff.core_domain.attachment

import my.cheysoff.core_domain.model.AttachmentPreview

/**
 * Attachments in the one order both platforms render them in: by [AttachmentPreview.anchor], ties
 * broken by [AttachmentPreview.id]. Mirrors `sortSketches` exactly -- see that function's own KDoc
 * for the full reasoning, restated briefly here.
 *
 * Deliberately NOT [AttachmentPreview.order] at any position in the key: `order` is scoped
 * per-anchor for a future inline layout that neither platform implements yet, and putting it in the
 * sort key now would encode a layout that does not exist.
 *
 * Both the phone and the desktop call this one function rather than each carrying its own copy, for
 * the same reason `sortSketches` is shared: two copies pinned together only by mirrored tests can
 * drift, and if they did, the same note would list its photographs in a different order on each of
 * the user's two devices, with no test on either platform alone able to see it.
 *
 * The DAO's SQL `ORDER BY` (see the `attachments` table) is not the display order -- it exists only
 * so the query result is deterministic. The display order is applied here, in Kotlin, on both
 * platforms, exactly as the sketch path does it. Do not "fix" one to match the other.
 *
 * Takes previews rather than full [my.cheysoff.core_domain.model.AttachmentData] because the rail is
 * the only caller and the rail never loads full-size bytes.
 */
fun sortAttachments(attachments: List<AttachmentPreview>): List<AttachmentPreview> =
    attachments.sortedWith(compareBy({ it.anchor }, { it.id }))
