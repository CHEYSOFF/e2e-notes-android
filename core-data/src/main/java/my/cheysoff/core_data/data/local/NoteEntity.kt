package my.cheysoff.core_data.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NoteContentFormat

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    // Stored as the raw wire string rather than the enum so no Room TypeConverter is needed and
    // the column stays readable/greppable in a DB dump. Defaults to "plain" to match the column
    // default the v4 -> v5 migration installs.
    val contentFormat: String = NoteContentFormat.PLAIN.storageValue,
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
    contentFormat = NoteContentFormat.fromStorage(contentFormat),
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
    contentFormat = contentFormat.storageValue,
    checklist = checklist,
    isPinned = isPinned,
    isFavorite = isFavorite,
    folderId = folderId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
