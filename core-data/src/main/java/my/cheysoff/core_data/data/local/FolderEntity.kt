package my.cheysoff.core_data.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import my.cheysoff.core_domain.model.Folder
import my.cheysoff.core_domain.sync.Hlc

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

    // Sync bookkeeping, added in v7. Identical in name, type, default and meaning to the six on
    // NoteEntity — read those comments; in particular `dirty` defaults to 1 there for a reason
    // that applies just as literally to a folder.
    @ColumnInfo(defaultValue = "0")
    val hlcMs: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val hlcCounter: Int = 0,
    @ColumnInfo(defaultValue = "''")
    val hlcNode: String = "",
    @ColumnInfo(defaultValue = "''")
    val fieldHlc: String = "",
    @ColumnInfo(defaultValue = "1")
    val dirty: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val lastSyncedSeq: Long = 0L,
) {
    /** The row clock as one value. */
    fun rowHlc(): Hlc = Hlc(ms = hlcMs, counter = hlcCounter, node = hlcNode)

    /** The clock columns alone — see `NoteEntity.clocks`. */
    fun clocks(): RowClock = RowClock(hlcMs, hlcCounter, hlcNode, fieldHlc)
}

fun FolderEntity.toDomain() = Folder(
    id = id,
    name = name,
    colorArgb = colorArgb,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)

/** Lossy in exactly the way `Note.toEntity` is, and not a write path. See that function. */
fun Folder.toEntity() = FolderEntity(
    id = id,
    name = name,
    colorArgb = colorArgb,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
)
