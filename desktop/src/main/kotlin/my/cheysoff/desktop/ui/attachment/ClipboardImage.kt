package my.cheysoff.desktop.ui.attachment

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.imageio.ImageIO

/**
 * The image Ctrl+V should attach, or null when the paste is text and belongs to the editor.
 *
 * **Text wins when the clipboard holds both.** Word, Excel and most browsers put a bitmap rendering
 * of copied text on the clipboard next to the text itself; treating that as an image paste would
 * turn every copied paragraph into a picture. A screenshot (Win+Shift+S, PrtScn), "Copy image" in a
 * browser and a file copied in Explorer carry no text, and those are the pastes this is for.
 *
 * Reads the AWT clipboard directly: Compose's own clipboard on desktop only exposes text.
 */
fun clipboardImage(): Image? {
    val contents: Transferable = try {
        Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
    } catch (e: IllegalStateException) {
        // Another process holds the clipboard open; let the ordinary paste have a go instead.
        return null
    } ?: return null
    return imageFrom(contents)
}

internal fun imageFrom(contents: Transferable): Image? = try {
    when {
        contents.isDataFlavorSupported(DataFlavor.stringFlavor) &&
            !(contents.getTransferData(DataFlavor.stringFlavor) as? String).isNullOrBlank() -> null

        contents.isDataFlavorSupported(DataFlavor.imageFlavor) ->
            contents.getTransferData(DataFlavor.imageFlavor) as? Image

        contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
            (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                ?.filterIsInstance<File>()
                ?.firstNotNullOfOrNull { file -> runCatching { ImageIO.read(file) }.getOrNull() }

        else -> null
    }
} catch (e: Exception) {
    // UnsupportedFlavorException / IOException: the owner changed what it offers between the
    // check and the read. Not an image paste, then.
    null
}
