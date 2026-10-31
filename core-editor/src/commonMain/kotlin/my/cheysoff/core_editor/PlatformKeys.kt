package my.cheysoff.core_editor

/**
 * Whether the text field's own key mapping treats Alt+Backspace as undo on this platform. It does
 * on Windows/Linux desktop (Compose's Windows key mapping, a Windows convention). On macOS
 * Alt+Backspace deletes the previous word and on Android it is not undo, so there
 * [blockHistoryKeys] must leave the key to the field.
 */
internal expect val altBackspaceIsUndo: Boolean
