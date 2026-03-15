package my.cheysoff.core_data.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Note

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val isPinned: Boolean,
    val folderId: String?
)

fun NoteEntity.toDomain() = Note(
    id = id,
    title = title,
    content = content,
    isPinned = isPinned,
    folderId = folderId
)

fun Note.toEntity() = NoteEntity(
    id = id,
    title = title,
    content = content,
    isPinned = isPinned,
    folderId = folderId
)
