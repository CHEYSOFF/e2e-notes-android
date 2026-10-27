package my.cheysoff.core_editor.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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

/**
 * Android: the process-text apps (Translate and the like) that [editorTextMenu] filtered out,
 * re-added so each gets the selection's plain text instead of the raw field text. Read-only
 * (`EXTRA_PROCESS_TEXT_READONLY`): a result would come back as raw text to splice into the
 * field, bypassing the editor's rules. Same query as Compose's own items, so the same apps show.
 */
@Composable
internal actual fun Modifier.editorMenuPlatformItems(selectedPlain: () -> String): Modifier {
    val context = LocalContext.current
    return appendTextContextMenuComponents {
        val text = selectedPlain()
        if (text.isBlank()) return@appendTextContextMenuComponents
        val activities = try {
            context.packageManager.queryIntentActivities(processTextIntent(), 0)
        } catch (e: RuntimeException) {
            emptyList()
        }
        val usable = activities.filter { it.activityInfo.packageName == context.packageName || it.activityInfo.exported }
        if (usable.isEmpty()) return@appendTextContextMenuComponents
        separator()
        usable.forEach { info ->
            val component = info.activityInfo
            item(
                key = EditorPlainTextKey("process:${component.packageName}/${component.name}"),
                label = info.loadLabel(context.packageManager).toString(),
            ) {
                val intent = processTextIntent()
                    .setClassName(component.packageName, component.name)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, selectedPlain())
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (e: RuntimeException) {
                    // The app went away or refuses; nothing was sent.
                }
                close()
            }
        }
        separator()
    }
}

private fun processTextIntent(): Intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
