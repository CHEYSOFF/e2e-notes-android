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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.desktop.pairing.DesktopAccountPairingController
import my.cheysoff.desktop.pairing.DesktopPairingController
import my.cheysoff.desktop.pairing.InviteAccount
import my.cheysoff.desktop.pairing.PairingServer
import my.cheysoff.desktop.sync.ClaimResult
import my.cheysoff.desktop.sync.DesktopAccountServer
import my.cheysoff.desktop.sync.VouchResult
import my.cheysoff.desktop.vault.DeviceKeyPair
import my.cheysoff.desktop.store.RecordNotesRepository
import my.cheysoff.desktop.store.RecordStore
import my.cheysoff.desktop.vault.AccountOrigin
import my.cheysoff.desktop.vault.DesktopVault
import my.cheysoff.desktop.vault.ServerEnrolment
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

        /**
         * Naming the server an account created on this computer will sync through.
         *
         * Before the passphrase, and therefore before the vault, because the two steps are not
         * equally reversible: a mistyped address costs a correction and an Account Root Key cannot
         * be un-minted. See `NameServerScreen`.
         */
        data class NameServer(val url: String, val message: String? = null) : Screen

        /**
         * Choosing a passphrase, having chosen how the account will be obtained.
         *
         * [server] is set only for an account being created on this computer, and is the address
         * the claim will be made against once the vault exists. Null for a paired vault (the
         * address came out of the sealed bundle) and for a standalone one (there is none).
         */
        data class CreatePassphrase(
            val origin: AccountOrigin,
            val server: RendezvousUrl? = null,
        ) : Screen

        /**
         * Admitting a phone to the account this computer holds.
         *
         * [returnTo] is the open workspace this screen was entered from and is where every exit
         * goes. It is carried rather than rebuilt because the vault, the store and the repository
         * are already open behind this screen — closing and reopening them to draw a QR code would
         * unwrap the ARK a second time for no reason.
         */
        data class Invite(
            val controller: DesktopAccountPairingController,
            val returnTo: Open,
        ) : Screen

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

    private var pairedArk: ByteArray? = null

    /**
     * What the pairing said about the account's server, held alongside [pairedArk].
     *
     * Null when the pairing agreed nothing usable — see `DesktopPairingController.enrolmentFrom`.
     * It carries a private key, so it is dropped and zeroed on every path out of pairing, exactly
     * as the ARK is.
     */
    private var pairedEnrolment: ServerEnrolment? = null

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

    /**
     * Start creating an account on this computer, beginning with the server it will sync through.
     *
     * Nothing is minted yet. The ARK is created by [create], after the address has been checked
     * against a live server, because a vault created against an address that answers nothing is an
     * account no phone can ever be added to — and it looks like a working app until somebody tries.
     */
    fun chooseCreateAccountHere() {
        message = null
        abandonPairing()
        screen = Screen.NameServer(PairingServer.remembered())
    }

    fun editServerUrl(url: String) {
        val current = screen
        if (current is Screen.NameServer) screen = current.copy(url = url, message = null)
    }

    /**
     * Check the typed address, then move on to the passphrase.
     *
     * The check is `GET /healthz` — unauthenticated, and the one call that can be made before any
     * key material exists. It does not prove the server is honest and is not meant to; it proves
     * the address reaches something that speaks this protocol, which is what a typo does not.
     */
    fun confirmServer() {
        val current = screen as? Screen.NameServer ?: return
        val parsed = RendezvousUrl.parse(current.url)
        if (parsed == null) {
            screen = current.copy(
                message = "That is not an http:// or https:// address this can reach."
            )
            return
        }
        val endpoint = endpointFor(parsed)
        if (endpoint == null) {
            screen = current.copy(
                message = "Plain http:// is only allowed to this computer itself. Put the server " +
                    "behind https:// and try again."
            )
            return
        }
        if (busy) return
        busy = true
        scope.launch {
            val reachable = withContext(Dispatchers.IO) {
                DesktopAccountServer(endpoint, DeviceKeyPair.generate(), { null }).isReachable()
            }
            busy = false
            screen = if (reachable) {
                Screen.CreatePassphrase(AccountOrigin.CREATED_HERE, parsed)
            } else {
                current.copy(
                    message = "Nothing at ${parsed.host} answered as a Manana server. Check the " +
                        "address and that the server is running."
                )
            }
        }
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
    fun create(
        passphrase: CharArray,
        confirmation: CharArray,
        origin: AccountOrigin,
        /**
         * The server an account created here will be claimed on, or null.
         *
         * Not passed to [DesktopVault.setUp]: the handle a server files the account under is
         * derived from the ARK, so it does not exist until the vault does. The claim therefore
         * happens after the vault is written, and [DesktopVault.recordEnrolment] adds the result
         * to the header.
         */
        server: RendezvousUrl? = null,
    ) {
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
                        if (server != null) claimAccountOn(server, result.session)
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

    /**
     * Install this computer as the account's first device on [server].
     *
     * Trust on first use, and legitimate here for exactly one reason: the account was minted
     * moments ago in [create], so there is nobody else who could have vouched and nothing on that
     * server to take over. Any other path to a server goes through pairing, where an existing
     * device does the vouching.
     *
     * A failure is reported and not retried in a loop. The vault is real, the notes are safe, and
     * what is missing is the ability to add a phone — which is a sentence the user can act on, and
     * which [addDevice] offers to fix by sending them back to the address field.
     */
    private fun claimAccountOn(server: RendezvousUrl, session: VaultSession) {
        val endpoint = endpointFor(server) ?: return
        val deviceKey = DeviceKeyPair.generate()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                DesktopAccountServer(
                    endpoint = endpoint,
                    deviceKey = deviceKey,
                    // A copy per call: the label sealer zeroes what it is handed, and zeroing the
                    // session's own ARK would take the vault down with it.
                    arkProvider = { session.ark.copyOf() },
                ).claim(
                    accountId = Base64Url.encode(session.accountKeys.accountId),
                    deviceLabel = DESKTOP_DEVICE_LABEL,
                )
            }
            when (outcome) {
                is ClaimResult.Claimed -> {
                    val recorded = vault.recordEnrolment(
                        session,
                        ServerEnrolment(server.base, outcome.deviceId, deviceKey),
                    )
                    if (recorded) {
                        PairingServer.remember(server)
                        attachSync(session)
                        // Straight to the invite: adding the phone is the reason this path exists,
                        // and a user who has just named a server and typed a passphrase is not
                        // helped by being dropped into an empty note list to find a button.
                        addDevice()
                    } else {
                        message = "This computer was authorised on ${server.host}, but that could " +
                            "not be written to the vault. It will not sync yet."
                    }
                }

                is ClaimResult.Refused -> {
                    deviceKey.privateKeyPkcs8.fill(0)
                    message = "Your notes are safe on this computer, but it is not set up on " +
                        "${server.host}: ${outcome.message} You can add a phone once it is."
                }
            }
        }
    }

    /**
     * Show the code a phone scans to join this account.
     *
     * Refused when this computer is not itself enrolled, and the refusal is the honest one: the
     * server checks the voucher's signature against a device row it holds, so a computer with no
     * row cannot authorise anybody. The user is sent back to the address field rather than shown a
     * QR code that would fail at the last step.
     */
    fun addDevice() {
        val open = screen as? Screen.Open ?: (screen as? Screen.Invite)?.returnTo ?: return
        message = null
        val sync = open.session.sync
        if (sync == null) {
            screen = Screen.NameServer(
                url = PairingServer.remembered(),
                message = "This computer is not set up on a server yet, so it cannot authorise a " +
                    "phone. Name the server first.",
            )
            return
        }
        val server = RendezvousUrl.parse(sync.serverUrl)
        val endpoint = server?.let(::endpointFor)
        if (server == null || endpoint == null) {
            message = "This vault's server address is one this build cannot use, so a phone " +
                "cannot be added."
            return
        }
        val accountId = Base64Url.encode(open.session.accountKeys.accountId)
        val accountServer = DesktopAccountServer(
            endpoint = endpoint,
            deviceKey = sync.deviceKey,
            arkProvider = { open.session.ark.copyOf() },
        )
        screen = Screen.Invite(
            controller = DesktopAccountPairingController(
                scope = scope,
                server = server,
                account = InviteAccount(
                    arkProvider = { open.session.ark.copyOf() },
                    accountId = accountId,
                    voucherDeviceId = sync.deviceId,
                ),
                voucher = { joiningDeviceKey ->
                    val result = withContext(Dispatchers.IO) {
                        accountServer.vouch(
                            accountId = accountId,
                            voucherDeviceId = sync.deviceId,
                            joiningDeviceKey = joiningDeviceKey,
                            deviceLabel = JOINING_DEVICE_LABEL,
                        )
                    }
                    (result as? VouchResult.Enrolled)?.deviceId
                },
            ),
            returnTo = open,
        )
    }

    /** Leave the invite screen, whatever it ended on, and go back to the notes. */
    fun inviteFinished() {
        val invite = screen as? Screen.Invite ?: return
        invite.controller.cancel()
        screen = invite.returnTo
    }

    /** Abandon this invite and start a fresh one. A new attempt means a new `sid` and new keys. */
    fun inviteStartOver() {
        val invite = screen as? Screen.Invite ?: return
        invite.controller.cancel()
        screen = invite.returnTo
        addDevice()
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
        attachSync(session)
        message = repository.diagnostics.total.takeIf { it > 0 }?.let {
            // Said out loud rather than logged. These records are still on disk and still belong to
            // the user; the app has simply not been able to read them, and a silent count is how a
            // partial data loss becomes a surprise months later.
            "$it record(s) on disk could not be read and are being left untouched."
        }
    }

    /**
     * Build the record pipeline for [session], if this vault has a server at all.
     *
     * Called on every unlock, and again after a claim: a vault created on this computer has no
     * enrolment when it opens and gains one seconds later, and a sync service built before that
     * would be a permanently unavailable one on a device that can in fact sync.
     */
    private fun attachSync(session: VaultSession) {
        val open = screen as? Screen.Open ?: return
        syncService = session.sync?.let { identity ->
            val endpoint = try {
                ServerEndpoint(identity.serverUrl)
            } catch (e: IllegalArgumentException) {
                // A stored address that no longer validates. The vault still opens and the notes
                // are readable; it simply cannot sync, and saying so beats discovering it at the
                // first pass.
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
                    store = open.store,
                    // A copy per call: the label sealer zeroes what it is handed, and zeroing the
                    // session's own ARK would take the vault down with it.
                    arkProvider = { session.ark.copyOf() },
                    clockObserver = open.repository.clockObserver,
                )
            }
        }
        syncState = if (syncService == null) DesktopSyncState.Unavailable else DesktopSyncState.Idle
    }

    /**
     * The sync endpoint for a rendezvous address, or null when this build will not talk to it.
     *
     * `ServerEndpoint`'s rule and not a second one: https, or plain http to loopback. The two
     * addresses are the same string -- the rendezvous and the sync API are one server -- so a
     * pairing that named a host the sync transport would refuse is a pairing worth stopping at the
     * address field rather than at the first sync.
     */
    private fun endpointFor(server: RendezvousUrl): ServerEndpoint? = try {
        ServerEndpoint(server.base)
    } catch (e: IllegalArgumentException) {
        null
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
                    is SyncOutcome.Completed -> DesktopSyncState.Done(outcome.stats.applied)
                    is SyncOutcome.Deferred -> DesktopSyncState.Deferred
                    // A halt is persisted and does not clear itself, so this device will not sync
                    // again until somebody deals with it. Named on screen rather than logged.
                    is SyncOutcome.Halted -> DesktopSyncState.Halted(outcome.reason.name)
                    // Another pass beat this one to it; its result will land through the same
                    // state, so there is nothing to report and nothing to retry.
                    SyncOutcome.AlreadyRunning -> DesktopSyncState.Syncing
                }
            } catch (e: Exception) {
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

    private companion object {

        /**
         * What this computer is called in the account's device list.
         *
         * A constant, because the alternative is the machine's hostname -- which is a personal
         * detail ("vova-thinkpad") that would be sealed into the account's own device label and is
         * not worth collecting to say "the laptop". The user can rename it from a device list.
         */
        const val DESKTOP_DEVICE_LABEL = "Computer"

        /**
         * What the joining device is called.
         *
         * "Phone" rather than a name, because this computer does not know one: the reply carries a
         * key, not a hostname. An unnamed row would be worse than a generic one -- a device the
         * user cannot identify is a device they will not revoke.
         */
        const val JOINING_DEVICE_LABEL = "Phone"
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
