package my.cheysoff.core_data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_data.data.DataStoreSettingsRepository
import my.cheysoff.core_data.data.RoomNotesRepository
import my.cheysoff.core_data.data.RoomSketchesRepository
import my.cheysoff.core_data.data.local.AttachmentDao
import my.cheysoff.core_data.data.local.FolderDao
import my.cheysoff.core_data.data.local.NoteDao
import my.cheysoff.core_data.data.local.NoteDatabase
import my.cheysoff.core_data.data.local.SketchDao
import my.cheysoff.core_data.data.local.SyncStateDao
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_domain.repository.AttachmentsRepository
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.repository.SettingsRepository
import my.cheysoff.core_domain.repository.SketchesRepository
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
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

    @Binds
    @Singleton
    abstract fun bindSketchesRepository(
        roomSketchesRepository: RoomSketchesRepository
    ): SketchesRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentsRepository(
        roomNotesRepository: RoomNotesRepository
    ): AttachmentsRepository

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
            // If the secure-unlock state had to be discarded (Keystore key gone — e.g. a restore
            // onto a new device brings back the prefs file but never the non-exportable master
            // key), every wrap of the old passphrase is gone with it, so any surviving notes.db is
            // permanently undecryptable. Drop it, or SQLCipher fails with "file is not a database"
            // on every launch with no recovery path.
            if (secureUnlockManager.wasStateReset) {
                deleteUndecryptableDatabase(context)
            }

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
                NoteDatabase.DATABASE_NAME
            )
            .openHelperFactory(factory)
            // Spread the canonical list rather than naming each migration here: a chain written
            // out twice is a chain that can disagree with itself, and the half that goes stale is
            // whichever one nobody is looking at.
            .addMigrations(*NoteDatabase.ALL_MIGRATIONS)
            .build()
        }

        /**
         * Remove the notes database left undecryptable by a secure-unlock state reset, or fail
         * loudly rather than let Room open it.
         *
         * `deleteDatabase()` alone is not enough. If a file survives (an open handle from another
         * process, a read-only or otherwise unwritable databases dir), Room proceeds to open stale
         * ciphertext with the brand-new passphrase and SQLCipher raises "file is not a database"
         * on every launch forever — precisely the crash-loop this whole branch exists to prevent,
         * only now with a useless error message.
         *
         * Its return value cannot drive the decision either, because false means both "the main
         * file survived" and "there was nothing to delete", and a journal sibling can outlive the
         * main file in either case. So every member of the set gets a hand-rolled second pass —
         * SQLite's deleteDatabase gives up on the set as a whole if one member resists — and only
         * a file that is still there afterwards throws, so the crash at least names the cause.
         */
        private fun deleteUndecryptableDatabase(context: Context) {
            val dbFile = context.getDatabasePath(NoteDatabase.DATABASE_NAME)
            context.deleteDatabase(NoteDatabase.DATABASE_NAME)

            // Sweep the siblings whatever deleteDatabase reported, and do NOT short-circuit on the
            // main file being absent. deleteDatabase returns false both when the main file
            // survived and when there was nothing to delete, and in the second case it may still
            // have failed to remove a sibling: a stale `notes.db-wal` with no `notes.db` is enough
            // on its own, because SQLCipher then creates a fresh database and tries to recover a
            // WAL written under the OLD passphrase — the same "file is not a database" crash-loop
            // by another route. Nothing here is undecryptable-but-wanted; it is all being dropped.
            val undeletable = listOf("", "-wal", "-shm", "-journal")
                .map { suffix -> File(dbFile.path + suffix) }
                .filter { it.exists() && !it.delete() }
            if (undeletable.isNotEmpty()) {
                throw IllegalStateException(
                    "Secure-unlock state was reset but the undecryptable database could not be " +
                        "deleted (${undeletable.joinToString { it.name }}). Opening it with the " +
                        "new passphrase would fail with \"file is not a database\" on every launch."
                )
            }
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

        @Provides
        @Singleton
        fun provideSyncStateDao(database: NoteDatabase): SyncStateDao {
            return database.syncStateDao
        }

        @Provides
        @Singleton
        fun provideSketchDao(database: NoteDatabase): SketchDao {
            return database.sketchDao
        }

        @Provides
        @Singleton
        fun provideAttachmentDao(database: NoteDatabase): AttachmentDao {
            return database.attachmentDao
        }

        /**
         * The process-wide hybrid logical clock.
         *
         * `@Singleton` is not a preference here, it is the correctness requirement: two
         * [SyncClock]s would keep two independent counters and could mint the same clock twice for
         * two different writes, which is precisely the situation the counter exists to prevent.
         *
         * It depends on [SecureUnlockManager] only for the node pseudonym, and takes it as a
         * function rather than a value because the node arrives at unlock and changes when the
         * device joins an account. Note that this deliberately does NOT depend on [NoteDatabase]:
         * the clock has to be constructible while the app is locked, and asking for the database
         * here would throw. The database-derived seed is applied by `RoomNotesRepository` before
         * its first write instead.
         */
        @Provides
        @Singleton
        fun provideSyncClock(secureUnlockManager: SecureUnlockManager): SyncClock =
            SyncClock(node = { secureUnlockManager.hlcNode })
    }
}
