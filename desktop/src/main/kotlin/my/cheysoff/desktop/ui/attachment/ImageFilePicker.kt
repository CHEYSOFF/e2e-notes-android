package my.cheysoff.desktop.ui.attachment

import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.io.File
import javax.imageio.ImageIO

/**
 * Asks for an image file with the OS's own open dialog and reads it, or returns null when the user
 * cancelled or the file is not an image ImageIO can read.
 *
 * Behind the block editor's Picture button, which needs a source other than the clipboard. The
 * image then takes the same path as a pasted one (`DesktopImageImporter`, the attachment record,
 * the picture block), so a picked file and a pasted screenshot cannot end up stored differently.
 * AWT's [FileDialog] rather than Swing's chooser: it is the native Windows/macOS dialog.
 */
fun pickImageFile(): Image? {
    val dialog = FileDialog(null as Frame?, "Insert picture", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
        // Windows ignores FilenameFilter; a wildcard pattern is what its dialog filters by.
        file = IMAGE_EXTENSIONS.joinToString(";") { "*.$it" }
        isVisible = true // modal: returns once the user has chosen or cancelled
    }
    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return runCatching { ImageIO.read(File(directory, name)) }.getOrNull()
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")
