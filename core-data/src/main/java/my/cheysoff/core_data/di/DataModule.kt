package my.cheysoff.core_data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import my.cheysoff.core_crypto.EncryptionManager
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_data.data.DataStoreSettingsRepository
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.repository.SettingsRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindNotesRepository(
        roomNotesRepository: RoomNotesRepository
    ): NotesRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        dataStoreSettingsRepository: DataStoreSettingsRepository
    ): SettingsRepository

    companion object {
        @Provides
        @Singleton
        fun provideNoteDatabase(
            @ApplicationContext context: Context,
            secureUnlockManager: SecureUnlockManager
        ): NoteDatabase {
            // The DB can only be opened AFTER the user authenticates: the passphrase is recovered
            // (PIN- or biometric-unwrapped) into SecureUnlockManager and held in memory only while
            // unlocked. Hilt builds this @Singleton lazily and the notes graph is reached only
            // post-unlock (nav gates on the auth screen), so currentPassphrase() is non-null here.
            // Migration preserves data: a migrated install reuses the legacy passphrase, so the DB
            // opens with the same key it was encrypted with.
            val passphrase = secureUnlockManager.currentPassphrase()
                ?: throw IllegalStateException("Database requested while locked; unlock must precede DB access")

            // KNOWN LIMITATION (review #4): SQLCipher's SupportOpenHelperFactory retains this
            // passphrase in SQLiteOpenHelper.mPassword for the helper's lifetime (verified in
            // sqlcipher-android 4.13.0 bytecode) and re-reads it on every (re)open. There is no
            // clearPassphrase option in this version, and zeroing the array would break DB reopen.
            // Fully purging it would require tearing down and rebuilding the whole DB instance.
            // Tracked as a follow-up security task (close DB / rebuild on background).
            val factory = SupportOpenHelperFactory(passphrase)
            
            return Room.databaseBuilder(
                context,
                NoteDatabase::class.java,
                EncryptionManager.DATABASE_NAME
            )
            .openHelperFactory(factory)
            .addMigrations(
                NoteDatabase.MIGRATION_1_2,
                NoteDatabase.MIGRATION_2_3,
                NoteDatabase.MIGRATION_3_4,
            )
            .build()
        }

        @Provides
        @Singleton
        fun provideNoteDao(database: NoteDatabase): NoteDao {
            return database.noteDao
        }

        @Provides
        @Singleton
        fun provideFolderDao(database: NoteDatabase): FolderDao {
            return database.folderDao
        }
    }
}
