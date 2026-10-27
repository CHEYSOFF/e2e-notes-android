package my.cheysoff.core_editor.ui

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The editor field's text toolbar / context menu: only actions that cannot leak the field's
 * private-use markers ([my.cheysoff.core_editor.Markers]) to another app.
 *
 * Copy and Cut go through [EditorClipboard], which rewrites what they write. Paste and Select all
 * send nothing out; Autofill only brings text in, which [my.cheysoff.core_editor.EditingRules]
 * strips. Every other item Compose 1.12 adds to a text field's menu sends the RAW selection
 * (markers included) straight to another app, bypassing the clipboard:
 * - Android "process text" items (Translate, and any app registered for `ACTION_PROCESS_TEXT`),
 *   which start that app with the selected field text in an Intent;
 * - Android smart-selection items (text classification: open link, call, share, ...), which act
 *   on the classified raw text.
 * Compose offers no hook to change the text those items send, so they are filtered out with the
 * supported `filterTextContextMenuComponents` API. In their place the platform may add its own
 * items that send the selection's plain text ([editorMenuPlatformItems]): Android re-adds the
 * process-text apps, each fed [EditorClipboard.plainOf] of the selection.
 */
@Composable
internal fun Modifier.editorTextMenu(selectedPlain: () -> String): Modifier =
    filterTextContextMenuComponents(::keepInEditorMenu).editorMenuPlatformItems(selectedPlain)

/** Whether [component] may appear in the editor field's menu; see [editorTextMenu]. */
internal fun keepInEditorMenu(component: TextContextMenuComponent): Boolean =
    component === TextContextMenuSeparator || isEditorMenuKey(component.key)

/** True for the menu keys of actions that send no raw field text out; see [editorTextMenu]. */
internal fun isEditorMenuKey(key: Any): Boolean =
    key is EditorPlainTextKey || key in safeKeys

private val safeKeys: Set<Any> = setOf(
    TextContextMenuKeys.CutKey,
    TextContextMenuKeys.CopyKey,
    TextContextMenuKeys.PasteKey,
    TextContextMenuKeys.SelectAllKey,
    TextContextMenuKeys.AutofillKey,
)

/** The key of an item the editor adds itself, which sends only [EditorClipboard.plainOf] text. */
internal data class EditorPlainTextKey(val name: String)

/**
 * The selection of [raw] (field text, markers and all) as the plain text an outside app may get:
 * the same reading Copy uses ([EditorClipboard.plainOf]).
 */
internal fun selectedPlainText(raw: CharSequence, start: Int, end: Int): String {
    val a = minOf(start, end).coerceIn(0, raw.length)
    val b = maxOf(start, end).coerceIn(0, raw.length)
    return EditorClipboard.plainOf(raw.subSequence(a, b))
}

/**
 * Items the platform adds to the editor menu in place of the filtered ones. Each must be keyed
 * with [EditorPlainTextKey] (or the filter drops it) and send only [selectedPlain].
 */
@Composable
internal expect fun Modifier.editorMenuPlatformItems(selectedPlain: () -> String): Modifier
