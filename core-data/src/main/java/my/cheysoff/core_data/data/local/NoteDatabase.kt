package my.cheysoff.core_data.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NoteEntity::class, FolderEntity::class], version = 4, exportSchema = false)
abstract class NoteDatabase : RoomDatabase() {
    abstract val noteDao: NoteDao
    abstract val folderDao: FolderDao

    companion object {
        // v1 -> v2: add isFavorite + createdAt/updatedAt. Additive, so existing notes survive.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 -> v3: add the folders table. Must match Room's expected schema for FolderEntity.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` " +
                        "(`id` TEXT NOT NULL, `name` TEXT NOT NULL, `colorArgb` INTEGER, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        // v3 -> v4: add the serialized checklist blob. Additive, so existing notes survive.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN checklist TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
