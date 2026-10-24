package my.cheysoff.core_editor

internal actual val altBackspaceIsUndo: Boolean =
    !System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")
