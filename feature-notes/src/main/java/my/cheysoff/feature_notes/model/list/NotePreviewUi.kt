package my.cheysoff.feature_notes.model.list

import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotePreview

@Immutable
data class NotePreviewUi(
    val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val updatedAt: Long = 0L,
)

fun NotePreview.toUi() = NotePreviewUi(
    id = id,
    title = title,
    content = content,
)

fun Note.toUi() = NotePreviewUi(
    id = id,
    title = title,
    // content is stored as HTML (rich text); show a plain-text snippet in the list.
    content = htmlToPlainText(content),
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    updatedAt = updatedAt,
)

private fun htmlToPlainText(html: String): String =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
