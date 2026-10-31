package my.cheysoff.core_blocks

/**
 * Whether this build writes [my.cheysoff.core_domain.model.NoteContentFormat.BLOCKS] bodies. On:
 * both apps edit every note with the shared block editor, and a PLAIN or HTML note is written as
 * BLOCKS on its first edit.
 *
 * It was off while only one app could read, merge and edit block bodies: a phone writing BLOCKS
 * while the desktop still ran an older build would have handed it notes it could only refuse. The
 * two apps now ship together with it on, and neither keeps an editor for the old formats; the
 * repositories still read them, converting on open.
 *
 * What still reads it is the desktop's one-time vault backup (`BlocksBackup`), taken by the first
 * launch that can write BLOCKS, before anything is rewritten.
 */
object BlocksFormat {
    const val WRITE_ENABLED = true
}
