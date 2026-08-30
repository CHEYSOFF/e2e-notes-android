package my.cheysoff.core_data.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Folder

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long?,
    // All four added in v6. Before it this table had no timestamps at all, so a folder that
    // predates the migration and has not been saved since carries createdAt = updatedAt = 0 —
    // the same "unset" sentinel notes use, and for the same reason: the migration cannot know when
    // the folder was made and refuses to guess.
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
)

fun FolderEntity.toDomain() = Folder(
    id = id,
    name = name,
    colorArgb = colorArgb,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)

fun Folder.toEntity() = FolderEntity(
    id = id,
    name = name,
    colorArgb = colorArgb,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)
