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
            val factory = SupportOpenHelperFactory(passphrase)
            
            return Room.databaseBuilder(
                context,
                NoteDatabase::class.java,
                "notes.db"
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
