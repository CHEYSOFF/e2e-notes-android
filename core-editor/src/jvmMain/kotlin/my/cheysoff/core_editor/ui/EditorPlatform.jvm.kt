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

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun ClipEntry.plainText(): String? {
    val t = asAwtTransferable ?: return null
    if (!t.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return t.getTransferData(DataFlavor.stringFlavor) as? String
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
