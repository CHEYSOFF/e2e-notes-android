package my.cheysoff.desktop.app

import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.desktop.sync.DesktopSyncService
import my.cheysoff.core_sync_codec.RecordCodec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.desktop.pairing.DesktopPairingController
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.PairedEnrolment
import my.cheysoff.desktop.vault.PassphrasePolicy
import my.cheysoff.desktop.vault.SetupResult
import my.cheysoff.desktop.vault.UnlockResult
import my.cheysoff.desktop.vault.VaultSession

/**
 * The window's state machine: which screen is showing, and what the buttons on it do.
 *
 * Everything security-relevant is one layer down, in [DesktopVault] and the store, and is unit
 * tested there. What lives here is the sequencing — which is still worth keeping in one place,
 * because two of the transitions are ones a UI can get wrong in a way that matters:
 *
 *  - [Screen.Damaged] is terminal. There is no button on it that creates a vault, because a damaged
 *    header is the state in which minting a new ARK destroys an account (see [DesktopVault]).
 *  - Failing to remember the key in the OS credential store does not block unlocking and does not
 *    silently succeed; it sets [rememberFailed], which the unlocked screen shows.
 *
 * The UI agent owns everything this hands to Compose. The contract it can rely on: [screen] is the
 * only source of truth, [Screen.Open.repository] is a `NotesRepository`, and nothing here needs to
 * be called from a particular thread.
 */
class AppController(
    private val vault: DesktopVault,
    private val scope: CoroutineScope,
) {

    sealed interface Screen {
        /** No vault here. Pairing is the intended path; see the screen itself. */
        data object FirstRun : Screen

        /**
         * Joining the phone's account: QR code, poll, SAS.
         *
         * [controller] owns the attempt. It is created when this screen is entered and cancelled
         * when it is left, so an abandoned pairing does not leave a poll loop running or a bundle
         * in memory.
         */
        data class Pairing(val controller: DesktopPairingController) : Screen

        /** Choosing a passphrase, having chosen how the account will be obtained. */
        data class CreatePassphrase(val origin: AccountOrigin) : Screen

        /** A vault exists and is closed. */
        data object Unlock : Screen

        /** A vault exists and cannot be opened by any means this build has. Terminal. */
        data class Damaged(val reason: String) : Screen

        /** Open. [repository] is the app's ordinary `NotesRepository`. */
        data class Open(
            val session: VaultSession,
            val repository: RecordNotesRepository,
            val store: RecordStore,
        ) : Screen
    }

    var screen by mutableStateOf<Screen>(if (vault.isSetUp()) Screen.Unlock else Screen.FirstRun)
        private set

    /** A crypto operation is in flight. PBKDF2 at 600 000 iterations is visible to the eye. */
    var busy by mutableStateOf(false)
        private set

    /** The last thing that went wrong, for the current screen. Cleared on every attempt. */
    var message by mutableStateOf<String?>(null)
        private set

    /** True when the user asked to be remembered and the OS credential store refused. */
    var rememberFailed by mutableStateOf(false)
        private set

    /** Name of the credential store, or null when this machine has none to offer. */
    val credentialStoreName: String? = vault.credentialStoreDescription

    /**
     * The ARK a completed pairing produced, held between the SAS confirmation and the passphrase
     * being chosen.
     *
     * A private field rather than a value on [Screen], because [screen] is Compose state that the
     * runtime keeps, snapshots and reads from arbitrary threads. Cleared and zeroed by
     * [abandonPairing], which every path out of pairing goes through — including the successful one,
     * once `setUp` has copied it.
     */
    /**
     * What the last sync pass did, for the workspace to show.
     *
     * Held here rather than in the workspace because a pass outlives the screen that started it:
     * the window can be resized, a note opened, the sidebar collapsed, and the answer still has to
     * arrive somewhere that survives recomposition.
     */
    var syncState by mutableStateOf<DesktopSyncState>(DesktopSyncState.Unavailable)
        private set

    /** Built when the vault opens, and only when this device is actually enrolled on a server. */
    private var syncService: DesktopSyncService? = null

    /** Cancelled and replaced whenever a vault opens, so a locked vault stops pushing. */
    private var localEditJob: Job? = null

    private var pairedArk: ByteArray? = null

    /**
     * What the pairing said about the account's server, held alongside [pairedArk].
     *
     * Null when the pairing agreed nothing usable — see `DesktopPairingController.enrolmentFrom`.
     * It carries a private key, so it is dropped and zeroed on every path out of pairing, exactly
     * as the ARK is.
     */
    private var pairedEnrolment: PairedEnrolment? = null

    /**
     * Tries the OS credential store, once, at startup.
     *
     * Failure is silent by design — a machine that was never asked to remember anything is the
     * common case and is not worth a message. It lands on the passphrase prompt, which is where a
     * user who never ticked the box expects to be.
     */
    fun tryStoredKey() {
        if (screen !is Screen.Unlock) return
        withVault { vault.unlockFromCredentialStore()?.let { open(it) } }
    }

    fun chooseStandalone() {
        message = null
        abandonPairing()
        screen = Screen.CreatePassphrase(AccountOrigin.CREATED_HERE)
    }

    /** Start a pairing attempt. Nothing is created on disk until the SAS is confirmed. */
    fun choosePairing() {
        message = null
        abandonPairing()
        screen = Screen.Pairing(DesktopPairingController(scope))
    }

    /**
     * The user confirmed the six digits match on both screens.
     *
     * This is where the ARK crosses from the pairing attempt into this class, and it is the only
     * place it does. It is held in a private field rather than in [screen], because [screen] is
     * Compose state that the runtime keeps, copies and reads from arbitrary threads, and an account
     * root key does not belong in any of that.
     */
    fun pairingConfirmed() {
        val pairing = screen as? Screen.Pairing ?: return
        val bundle = pairing.controller.takeBundle()
        if (bundle == null) {
            abandonPairing()
            message = "The pairing result was already discarded. Start over."
            screen = Screen.FirstRun
            return
        }
        pairedArk = bundle.ark
        pairedEnrolment = pairing.controller.enrolmentFrom(bundle)
        pairing.controller.cancel()
        screen = Screen.CreatePassphrase(AccountOrigin.PAIRED)
    }

    fun backToFirstRun() {
        message = null
        abandonPairing()
        screen = Screen.FirstRun
    }

    /**
     * Creates the vault. [passphrase] is zeroed here whatever the outcome.
     *
     * For [AccountOrigin.PAIRED] the ARK is the one [pairingConfirmed] received. [DesktopVault.setUp]
     * refuses the combination in either direction — a PAIRED origin with no ARK, or a CREATED_HERE
     * origin with one — so losing [pairedArk] between the two screens is a refusal rather than a
     * silently minted second account. It is checked here as well, so that the refusal is a sentence
     * the user can act on rather than an `IllegalArgumentException`.
     */
    fun create(passphrase: CharArray, confirmation: CharArray, origin: AccountOrigin) {
        message = null
        if (!passphrase.contentEquals(confirmation)) {
            passphrase.fill('\u0000')
            confirmation.fill('\u0000')
            message = "The two passphrases do not match."
            return
        }
        confirmation.fill('\u0000')
        if (origin == AccountOrigin.PAIRED && pairedArk == null) {
            passphrase.fill('\u0000')
            message = "The paired account key is no longer available. Start the pairing again."
            screen = Screen.FirstRun
            return
        }
        withVault {
            try {
                when (val result = vault.setUp(passphrase, origin, pairedArk, pairedEnrolment)) {
                    is SetupResult.Created -> {
                        // The wrap on disk now holds it; this copy has no further use. NOT done in
                        // the `finally` alongside the passphrase, because a Rejected verdict puts
                        // the user back on the same screen to retype, and zeroing here would turn
                        // "that passphrase is too short" into "start the pairing again".
                        pairedArk?.fill(0)
                        pairedArk = null
                        pairedEnrolment?.deviceKey?.privateKeyPkcs8?.fill(0)
                        pairedEnrolment = null
                        open(result.session)
                    }

                    is SetupResult.AlreadySetUp -> {
                        // Another window, or another process, got there first. Do not offer to
                        // overwrite: that vault holds an ARK this one has no copy of.
                        message = "A vault already exists here."
                        screen = Screen.Unlock
                    }

                    is SetupResult.Rejected -> message = explain(result.verdict)
                    is SetupResult.NotWritable ->
                        message = "The vault could not be written: ${result.message}"
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    /** Opens the vault. [passphrase] is zeroed here whatever the outcome. */
    fun unlock(passphrase: CharArray) {
        message = null
        withVault {
            try {
                when (val result = vault.unlock(passphrase)) {
                    is UnlockResult.Unlocked -> open(result.session)
                    is UnlockResult.WrongPassphrase -> message = "That passphrase did not open the vault."
                    is UnlockResult.NotSetUp -> screen = Screen.FirstRun
                    is UnlockResult.Damaged -> screen = Screen.Damaged(result.reason)
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    /** Asks the OS to hold the vault key. Reports refusal rather than swallowing it. */
    fun rememberOnThisComputer() {
        val open = screen as? Screen.Open ?: return
        rememberFailed = !vault.rememberOnThisComputer(open.session)
    }

    fun forgetOnThisComputer() {
        vault.forgetOnThisComputer()
        rememberFailed = false
    }

    fun isRemembered(): Boolean = vault.isRemembered()

    /** Closes the vault: the store, then the keys. */
    fun lock() {
        (screen as? Screen.Open)?.let { open ->
            open.store.close()
            open.session.close()
        }
        message = null
        screen = Screen.Unlock
    }

    private fun open(session: VaultSession) {
        val store = RecordStore.open(vault.recordsFile)
        val repository = RecordNotesRepository.load(
            store = store,
            codec = RecordCodec(session.accountKeys),
            node = session.hlcNode,
        )
        syncService = session.sync?.let { identity ->
            val endpoint = try {
                ServerEndpoint(identity.serverUrl)
            } catch (e: IllegalArgumentException) {
                // A stored address that no longer validates. The vault still opens and the notes
                // are readable; it simply cannot sync, and saying so beats discovering it at the
                // first pass.
                syncState = DesktopSyncState.Unavailable
                null
            }
            endpoint?.let {
                DesktopSyncService(
                    endpoint = it,
                    deviceKey = identity.deviceKey,
                    credentials = DeviceCredentials(
                        accountId = Base64Url.encode(session.accountKeys.accountId),
                        deviceId = identity.deviceId,
                    ),
                    codec = RecordCodec(session.accountKeys),
                    store = store,
                    // A copy per call: the label sealer zeroes what it is handed, and zeroing the
                    // session's own ARK would take the vault down with it.
                    arkProvider = { session.ark.copyOf() },
                    clockObserver = repository.clockObserver,
                )
            }
        }
        syncState = if (syncService == null) DesktopSyncState.Unavailable else DesktopSyncState.Idle

        // Push what was just typed, without pushing on every keystroke.
        //
        // A pass previously ran only on unlock and on the title-bar control, so a note written and
        // left alone stayed on this machine indefinitely while the app cheerfully reported its last
        // successful sync. Debounced rather than immediate because the editor autosaves as you
        // type, and a pass per character would be absurd.
        localEditJob?.cancel()
        localEditJob = repository.localWrites
            .drop(1)
            .debounce(LOCAL_EDIT_SYNC_DELAY_MS)
            .onEach { syncNow() }
            .launchIn(scope)
        screen = Screen.Open(session, repository, store)
        message = repository.diagnostics.total.takeIf { it > 0 }?.let {
            // Said out loud rather than logged. These records are still on disk and still belong to
            // the user; the app has simply not been able to read them, and a silent count is how a
            // partial data loss becomes a surprise months later.
            "$it record(s) on disk could not be read and are being left untouched."
        }
    }

    /**
     * Runs one sync pass, if this device can sync at all.
     *
     * Foreground and on demand, deliberately: there is no timer and no background service, because
     * a desktop that synced while locked would need key material available while locked, and
     * lock-on-close is one of this app's stronger properties.
     *
     * Concurrent calls are refused rather than queued. Two passes at once would each push the same
     * dirty rows and the second would take a `409` on every one of them -- correct, since the merge
     * is idempotent, but a guaranteed round trip of conflicts for no reason.
     */
    fun syncNow() {
        val service = syncService ?: return
        if (syncState is DesktopSyncState.Syncing) return
        syncState = DesktopSyncState.Syncing
        scope.launch {
            syncState = try {
                when (val outcome = withContext(Dispatchers.IO) { service.syncOnce() }) {
                    is SyncOutcome.Completed -> {
                        // The engine wrote straight into the record store, which the repository's
                        // in-memory snapshot knows nothing about. Without this the screen keeps
                        // showing what it held before the pass -- and a message saying twenty-six
                        // notes arrived, over an empty list, is worse than no message at all.
                        (screen as? Screen.Open)?.repository?.refreshFromStore()
                        DesktopSyncState.Done(outcome.stats.applied)
                    }
                    is SyncOutcome.Deferred -> DesktopSyncState.Deferred
                    // A halt is persisted and does not clear itself, so this device will not sync
                    // again until somebody deals with it. Named on screen rather than logged.
                    is SyncOutcome.Halted -> DesktopSyncState.Halted(outcome.reason.name)
                    // Another pass beat this one to it and will report its own result. Resolved
                    // to Idle rather than left at Syncing: with the guard at the top of this
                    // method, a state that stays Syncing means every later call returns
                    // immediately and this device never syncs again until it restarts.
                    SyncOutcome.AlreadyRunning -> DesktopSyncState.Idle
                }
            } catch (e: Throwable) {
                // Throwable, not Exception. A NoClassDefFoundError from a runtime missing a module
                // is an Error, and catching only Exception left `syncState` stuck at Syncing --
                // which the guard above then reads as "a pass is in flight", disabling sync for
                // the rest of the session with no message and nothing in the UI to explain it.
                // That is exactly how a packaged build failed here once already.
                DesktopSyncState.Failed(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /**
     * Runs [block] off the UI thread with [busy] set.
     *
     * PBKDF2 at [PassphrasePolicy.ITERATIONS] is roughly a third of a second, which is long enough
     * to freeze a window visibly. It also must not run twice concurrently: two unlocks in flight
     * would race to replace [screen].
     *
     * [block] assigns Compose state from a background thread, which is supported — snapshot state
     * writes are thread-safe and the global snapshot notifies Compose's own applier, which is what
     * schedules the recomposition. What is NOT supported is touching a Swing component that way,
     * and nothing here does.
     */
    private fun withVault(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) { block() }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Destroy anything a pairing attempt left behind.
     *
     * Called on every path out of pairing, successful or not. The ARK is zeroed rather than
     * dropped: a desktop process stays open for hours and a dropped array survives in the heap
     * until a collection that may never come.
     */
    private fun abandonPairing() {
        (screen as? Screen.Pairing)?.controller?.cancel()
        pairedArk?.fill(0)
        pairedArk = null
        pairedEnrolment?.deviceKey?.privateKeyPkcs8?.fill(0)
        pairedEnrolment = null
    }

    private fun explain(verdict: PassphrasePolicy.Verdict): String = when (verdict) {
        PassphrasePolicy.Verdict.Accepted -> ""
        PassphrasePolicy.Verdict.TooShort ->
            "Use at least ${PassphrasePolicy.MIN_LENGTH} characters."

        PassphrasePolicy.Verdict.AllDigits ->
            "Digits only is a PIN, and a PIN on a desktop can be guessed offline in about a " +
                "minute. Add words or other characters."
    }
}

/**
 * How long to wait after the last local edit before pushing.
 *
 * Long enough to cover the editor's 600 ms autosave and an ordinary pause in typing, short enough
 * that a note written and left alone reaches the other device while the person still expects it to.
 */
private const val LOCAL_EDIT_SYNC_DELAY_MS = 2_500L
