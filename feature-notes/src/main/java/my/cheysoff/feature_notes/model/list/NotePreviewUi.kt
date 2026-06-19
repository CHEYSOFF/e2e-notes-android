package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotePreview
import my.cheysoff.feature_notes.model.looksLikeHtml
import my.cheysoff.feature_notes.model.single.checklistProgress

@Immutable
data class NotePreviewUi(
    val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val updatedAt: Long = 0L,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
)

fun NotePreview.toUi() = NotePreviewUi(
    id = id,
    title = title,
    content = content,
)

fun Note.toUi(): NotePreviewUi {
    val (done, total) = checklistProgress(checklist)
    return NotePreviewUi(
        id = id,
        title = title,
        // content is stored as HTML for rich-text notes; show a plain-text snippet in the list.
        // Legacy plain-text notes are shown as-is so stray "<"/">" aren't parsed away.
        content = previewSnippet(content),
        isPinned = isPinned,
        isFavorite = isFavorite,
        folderId = folderId,
        updatedAt = updatedAt,
        checklistDone = done,
        checklistTotal = total,
    )
}

private fun previewSnippet(content: String): String =
    if (content.looksLikeHtml()) htmlToPlainText(content) else content.trim()

private fun htmlToPlainText(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
