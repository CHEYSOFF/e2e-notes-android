package my.cheysoff.core_editor.ui

import android.content.ClipData
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.toClipEntry

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual suspend fun ClipEntry.plainText(): String? {
    val clip = clipData
    if (clip.itemCount == 0) return null
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("").ifEmpty { null }
}

/** Plain text only (`newPlainText`), so no HTML item can carry the markers along. */
internal actual fun plainClipEntry(text: String): ClipEntry = ClipData.newPlainText("", text).toClipEntry()

/**
 * Android: a long press, then drag. A plain drag on a picture must keep scrolling the note, as
 * it does everywhere else on a touch screen.
 */
internal actual fun Modifier.pictureDragGesture(
    key: Any,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = onStart,
        onDragEnd = onEnd,
        onDragCancel = onCancel,
    ) { change, amount ->
        change.consume()
        onDrag(amount)
    }
}
