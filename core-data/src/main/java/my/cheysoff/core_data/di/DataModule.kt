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
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_domain.repository.NotesRepository
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

    companion object {
        @Provides
        @Singleton
        fun provideNoteDatabase(
            @ApplicationContext context: Context,
            encryptionManager: EncryptionManager
        ): NoteDatabase {
            val passphrase = encryptionManager.getDatabasePassphrase()
            
            if (encryptionManager.wasPassphraseReset) {
                context.deleteDatabase(EncryptionManager.DATABASE_NAME)
            }

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
            .build()
        }

        @Provides
        @Singleton
        fun provideNoteDao(database: NoteDatabase): NoteDao {
            return database.noteDao
        }
    }
}
