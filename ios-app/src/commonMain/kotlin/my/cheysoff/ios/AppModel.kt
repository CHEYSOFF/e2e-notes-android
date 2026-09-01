package my.cheysoff.ios

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_domain.model.Note
import my.cheysoff.core_domain.model.NotesSortOrder
import my.cheysoff.core_domain.repository.NotesRepository
import my.cheysoff.core_domain.sync.HlcGenerator
import my.cheysoff.core_store.RecordNotesRepository
import my.cheysoff.core_store.RecordStore
import my.cheysoff.core_store.recordDatabase

/**
 * Where this device's Account Root Key lives.
 *
 * An interface rather than the concrete `ArkVault`, for one reason: `ArkVault` needs the Keychain
 * and the Keychain needs `platform.Security`, so it can only exist in `iosMain` — and everything in
 * this file, including the screens above it, is then testable and reviewable without it. It also
 * means the shape of the unlock contract is stated in one place, in six lines, rather than inferred
 * from a hundred lines of CoreFoundation.
 */
interface Vault {
    /** True once a PIN has been set on this device. */
    fun exists(): Boolean

    /** Creates the account. Null if one already exists — never mint a second ARK. */
    fun create(pin: CharArray): AccountKeys?

    /** Opens the vault, or null for a wrong PIN or a damaged item. */
    fun unlock(pin: CharArray): AccountKeys?
}

/** What the app is showing. */
sealed interface AppState {
    /** No PIN has been set: this device has never been used. */
    data object NeedsSetup : AppState

    /** A PIN exists and has not been entered yet. [error] is set after a failed attempt. */
    data class Locked(val error: String? = null, val busy: Boolean = false) : AppState

    /** Unlocked, showing the notes list or one note. */
    data class Unlocked(val notes: List<Note>, val editing: Note? = null) : AppState
}

/**
 * The whole app's state, in one object.
 *
 * ## Why there is no ViewModel here
 *
 * `androidx.lifecycle.ViewModel` has a Compose Multiplatform artifact now, and using it would buy
 * one thing: surviving a configuration change. iOS has no configuration changes. What it would cost
 * is a dependency whose iOS behaviour nobody on this branch can observe, in the one class that
 * every screen depends on. A plain object with an injected [CoroutineScope] does the same work and
 * its lifetime is visible at the call site — `MainViewController` creates it, and it lives as long
 * as the view controller does.
 *
 * ## Unlock is the only blocking call, and it blocks on purpose
 *
 * [unlock] runs PBKDF2 at 210,000 rounds, which is a few hundred milliseconds and is *supposed* to
 * be. It is dispatched off the main thread so the keypad stays responsive, and the UI shows
 * `busy` while it runs — the same "Checking…" affordance the Android app has, for the same reason:
 * an unlock that appears to do nothing for half a second reads as an unlock that did not register,
 * and the user taps again.
 *
 * ## COMPILED. NOT RUN.
 *
 * The Kotlin here compiles for both iOS targets this module has. Nothing in it has been executed,
 * and no screen below it has been rendered. See `docs/BUILDING-IOS.md`.
 */
class AppModel(
    private val vault: Vault,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {

    private val _state = MutableStateFlow<AppState>(
        if (vault.exists()) AppState.Locked() else AppState.NeedsSetup
    )
    val state: StateFlow<AppState> = _state

    private var repository: NotesRepository? = null

    /** Set a PIN for the first time. */
    fun setUp(pin: String) {
        scope.launch {
            val keys = vault.create(pin.toCharArray())
            if (keys == null) {
                _state.value = AppState.Locked("Could not create a vault on this device.")
            } else {
                open(keys)
            }
        }
    }

    /** Enter an existing PIN. */
    fun unlock(pin: String) {
        _state.value = AppState.Locked(busy = true)
        scope.launch {
            val keys = withContext(Dispatchers.Default) {
                vault.unlock(pin.toCharArray())
            }
            // Deliberately one message for every failure. A wrong PIN and a damaged Keychain item
            // are different problems and telling them apart at the lock screen tells an attacker
            // which one they have; `ArkVault` merges them for the same reason.
            if (keys == null) _state.value = AppState.Locked("That PIN did not work.") else open(keys)
        }
    }

    fun openNote(note: Note) {
        val current = _state.value
        if (current is AppState.Unlocked) _state.value = current.copy(editing = note)
    }

    fun closeNote() {
        val current = _state.value
        if (current is AppState.Unlocked) _state.value = current.copy(editing = null)
    }

    fun newNote() {
        val stamp = now()
        openNote(
            Note(
                // A time-based id is a placeholder and is called one. The Android app mints a UUID;
                // there is no multiplatform UUID in this project yet and inventing one here would be
                // a second identifier scheme for the sync protocol to disagree about. Two notes
                // created in the same millisecond on one device would collide, which is not
                // reachable by tapping but is not a property to ship. See docs/BUILDING-IOS.md.
                id = "note-$stamp",
                title = "",
                content = "",
                createdAt = stamp,
                updatedAt = stamp,
            )
        )
    }

    fun save(note: Note) {
        val repository = repository ?: return
        scope.launch { repository.saveNote(note.copy(updatedAt = now())) }
    }

    fun delete(note: Note) {
        val repository = repository ?: return
        scope.launch {
            repository.deleteNote(note.id)
            closeNote()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun open(keys: AccountKeys) {
        val store = RecordStore(
            database = recordDatabase(),
            keys = keys,
            // One thread for SQLite. `limitedParallelism(1)` rather than a dedicated thread because
            // it is the portable spelling and because the work is short: a read decrypts every
            // record, which for a notes library is milliseconds.
            dispatcher = Dispatchers.Default.limitedParallelism(1),
        )
        val repository = RecordNotesRepository(
            store = store,
            // The node is empty until this device has a `deviceId` to derive one from -- see
            // `HlcNode`'s KDoc, which argues that an unpaired device should have no pseudonym rather
            // than a locally invented one. Records written now cannot collide with another device's
            // anyway: no other device has seen their uuids.
            clock = HlcGenerator { "" },
            now = now,
        )
        this.repository = repository

        _state.value = AppState.Unlocked(notes = emptyList())
        scope.launch {
            repository.getNotes(NotesSortOrder.RECENTLY_EDITED).collect { notes ->
                val current = _state.value
                _state.value = when (current) {
                    is AppState.Unlocked -> current.copy(notes = notes)
                    else -> AppState.Unlocked(notes = notes)
                }
            }
        }
    }
}
