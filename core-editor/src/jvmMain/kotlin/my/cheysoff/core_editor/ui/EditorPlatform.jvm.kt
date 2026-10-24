package my.cheysoff.core_editor.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/** The entry's string flavour, or null if it has none or will not give it up. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun ClipEntry.plainText(): String? {
    val t = asAwtTransferable ?: return null
    if (!t.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    // A transferable may still refuse at read time; the caller then writes an empty entry.
    return try {
        t.getTransferData(DataFlavor.stringFlavor) as? String
    } catch (e: UnsupportedFlavorException) {
        null
    } catch (e: IOException) {
        null
    }
}

/** A [StringSelection]: plain text only, so no styled or HTML flavour can carry the markers along. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))

/** Desktop: a mouse press-drag, as in the spike. */
internal actual fun Modifier.pictureDragGesture(
    key: Any,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(key) {
    detectDragGestures(
        onDragStart = onStart,
        onDragEnd = onEnd,
        onDragCancel = onCancel,
    ) { change, amount ->
        change.consume()
        onDrag(amount)
    }
}
