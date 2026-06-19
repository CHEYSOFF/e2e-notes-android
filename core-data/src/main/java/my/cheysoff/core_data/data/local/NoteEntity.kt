package my.cheysoff.core_data.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Note

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val checklist: String = "",
    val isPinned: Boolean,
    val isFavorite: Boolean = false,
    val folderId: String?,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

fun NoteEntity.toDomain() = Note(
    id = id,
    title = title,
    content = content,
    checklist = checklist,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Note.toEntity() = NoteEntity(
    id = id,
    title = title,
    content = content,
    checklist = checklist,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
