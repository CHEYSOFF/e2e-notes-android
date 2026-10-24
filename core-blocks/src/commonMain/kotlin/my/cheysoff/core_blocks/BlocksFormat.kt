package my.cheysoff.core_blocks

/**
 * Whether this build's editors write [my.cheysoff.core_domain.model.NoteContentFormat.BLOCKS].
 *
 * Off until both apps can read, merge and edit block bodies: a phone that wrote BLOCKS while the
 * desktop still ran an older build would hand it notes it can only refuse. Only the UI reads this.
 * The repositories store a BLOCKS save whatever it says, because a note that is already BLOCKS (it
 * arrived by sync from a device with the flag on) must still save correctly, and because their
 * tests call `saveNote` with BLOCKS directly.
 */
object BlocksFormat {
    const val WRITE_ENABLED = false
}
