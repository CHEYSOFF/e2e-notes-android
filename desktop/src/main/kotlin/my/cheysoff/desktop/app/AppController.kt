package my.cheysoff.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.desktop.pairing.DesktopPairingController
import my.cheysoff.desktop.store.RecordCodec
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
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
    private var pairedArk: ByteArray? = null

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
                when (val result = vault.setUp(passphrase, origin, pairedArk)) {
                    is SetupResult.Created -> {
                        // The wrap on disk now holds it; this copy has no further use. NOT done in
                        // the `finally` alongside the passphrase, because a Rejected verdict puts
                        // the user back on the same screen to retype, and zeroing here would turn
                        // "that passphrase is too short" into "start the pairing again".
                        pairedArk?.fill(0)
                        pairedArk = null
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
        screen = Screen.Open(session, repository, store)
        message = repository.diagnostics.total.takeIf { it > 0 }?.let {
            // Said out loud rather than logged. These records are still on disk and still belong to
            // the user; the app has simply not been able to read them, and a silent count is how a
            // partial data loss becomes a surprise months later.
            "$it record(s) on disk could not be read and are being left untouched."
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
