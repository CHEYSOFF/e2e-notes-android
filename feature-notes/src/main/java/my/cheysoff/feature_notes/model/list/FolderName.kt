package my.cheysoff.feature_notes.model.list

/** Normalizes a user-entered folder name: trimmed, or null if blank (used to reject empty names). */
fun normalizeFolderName(raw: String): String? = raw.trim().ifEmpty { null }
