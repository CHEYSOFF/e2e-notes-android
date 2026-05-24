package my.cheysoff.core_data.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Folder

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long?,
)

fun FolderEntity.toDomain() = Folder(
    id = id,
    name = name,
    colorArgb = colorArgb,
)

fun Folder.toEntity() = FolderEntity(
    id = id,
    name = name,
    colorArgb = colorArgb,
)
