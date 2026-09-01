package my.cheysoff.notes.sync

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_data.data.sync.SyncClock
import my.cheysoff.core_data.data.sync.SyncStoreFactory
import my.cheysoff.core_domain.sync.SyncController
import my.cheysoff.core_domain.sync.SyncPassState
import my.cheysoff.core_domain.sync.SyncPassSummary
import my.cheysoff.core_domain.sync.SyncTrigger
import my.cheysoff.core_sync_codec.RecordCodec
import my.cheysoff.core_sync_engine.ClockObserver
import my.cheysoff.core_sync_engine.HaltReason
import my.cheysoff.core_sync_engine.SyncEngine
import my.cheysoff.core_sync_engine.SyncOutcome
import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.DeviceCredentials
import my.cheysoff.core_sync_net.SyncApi
import my.cheysoff.core_sync_net.SyncException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that has to be true before a sync pass can run, checked in order, and then the pass.
 *
 * ## Why the preconditions are a list of early returns rather than a chain of nullable calls
 *
 * There are five ways this app can be unable to sync — locked, unpaired, no server address, no
 * account key, not enrolled — and each one is a different sentence to show a person. A design that
 * folded them into one "not configured" would send someone to the settings screen to fix a lock
 * screen. So each is checked separately and each produces its own [SyncPassState.Unavailable]
 * message, in the order that makes the first true one the useful one.
 *
 * ## Foreground only, and this class is where that is true
 *
 * The first check is the unlock state, and it is not a formality: `MainApplication.onStop` locks,
 * which zeroes the passphrase and the Account Root Key, so a pass that starts after a background
 * event has no keys and no database. §7 of the phase-3 plan is explicit that this is deliberate and
 * that background sync needs a ciphertext outbox rather than a weakened lock. Nothing here relaxes
 * it, and the store factory is taken as a `Lazy` so that merely constructing this object does not
 * try to open a database that may not be openable.
 *
 * ## One pass at a time, here as well as in the engine
 *
 * `SyncEngine` has its own `Mutex`, but a fresh engine is built for every pass — it is cheap, and
 * the alternative is caching an object that holds account keys for the life of the process. Two
 * engines over one store would each hold their own lock and neither would see the other, so the
 * exclusion has to be here too. `tryLock`, not `lock`: the caller that lost the race is a trigger,
 * and the pass already running is doing the work it wanted.
 *
 * ## The account keys live for one pass
 *
 * `AccountRootKey.derive` is called per pass and [AccountKeys.destroy] runs in a `finally`. Holding
 * `K_content` in a `@Singleton` for the life of the process would keep the key that decrypts every
 * note in memory across every lock — which is the property the lock exists to remove.
 */
@Singleton
class DefaultSyncController @Inject constructor(
    private val secureUnlock: SecureUnlockManager,
    private val transportProvider: SyncTransportProvider,
    private val enrolmentStore: SyncEnrolmentStore,
    private val stores: dagger.Lazy<SyncStoreFactory>,
    private val syncClock: SyncClock,
    @ApplicationScope private val scope: CoroutineScope,
) : SyncController {

    private val _state = MutableStateFlow<SyncPassState>(SyncPassState.Idle)
    override val state: StateFlow<SyncPassState> = _state.asStateFlow()

    private val pass = Mutex()

    override fun requestSync(trigger: SyncTrigger) {
        scope.launch { syncNow(trigger) }
    }

    override suspend fun syncNow(trigger: SyncTrigger): SyncPassState {
        // A pass that is already running is doing exactly what this caller wanted, so the honest
        // answer is the running one rather than a queued second pass that would take a 409 against
        // the first.
        if (!pass.tryLock()) return _state.value
        try {
            _state.value = SyncPassState.Running
            val result = try {
                runPass()
            } catch (e: Exception) {
                // `SyncEngine.runPass` never throws and the transport translates everything it can
                // — but this method is also called from a fire-and-forget `launch`, where an escaped
                // exception is a crash rather than a failed sync. Nothing above this line is
                // allowed to be the thing that takes the process down.
                Log.w(TAG, "A sync pass ended in an exception", e)
                SyncPassState.Deferred("Something went wrong during sync.")
            }
            _state.value = result
            return result
        } finally {
            pass.unlock()
        }
    }

    private suspend fun runPass(): SyncPassState {
        if (!secureUnlock.unlocked.value) {
            return SyncPassState.Unavailable("Unlock the app to sync.")
        }
        // `currentArk`, never `ensureArk`. Minting an Account Root Key here would fork the account
        // into two permanently unreadable halves for a user who has simply not paired yet -- F11 --
        // and generation is confined to the single call site that guards it.
        val ark = secureUnlock.currentArk()
            ?: return SyncPassState.Unavailable("This device has no account key yet. Pair it first.")

        val transport = when (val current = transportProvider.current()) {
            is SyncTransport.NotConfigured -> return SyncPassState.Unavailable(current.message)
            is SyncTransport.Ready -> current
        }

        val keys = AccountRootKey.derive(ark)
        try {
            ark.fill(0)
            val accountId = Base64Url.encode(keys.accountId)
            val deviceId = when (val enrolment = enrol(transport.api, accountId)) {
                is Enrolment.Enrolled -> enrolment.deviceId
                is Enrolment.Failed -> return SyncPassState.Deferred(enrolment.message)
                Enrolment.NeedsAuthorisation -> return SyncPassState.Unavailable(
                    "This device isn't authorised on the account yet. Authorise it from a device " +
                        "that already syncs."
                )
            }

            // `get()` here rather than at injection: constructing the factory opens the encrypted
            // database, which is only possible after the unlock check above.
            val factory = stores.get()
            val store = factory.create(accountId)

            // Before the first pull on this account and never again. One merge bug propagates to
            // every device in seconds and the Trash does not catch a merge, so the pre-sync library
            // is worth one file on disk. See `SyncSnapshot`.
            if (store.cursor() == 0L) factory.takeSnapshotOnce(accountId)

            val engine = SyncEngine(
                store = store,
                transport = EnvelopeSyncTransport(
                    api = transport.api,
                    credentials = DeviceCredentials(accountId, deviceId),
                    codec = RecordCodec(keys),
                    createdAtOf = factory::createdAtOf,
                ),
                // Every clock this device is shown is folded into the generator that mints local
                // writes. Without it a device could mint its next edit BELOW a record it has
                // already accepted, and a row whose clock went backwards loses to its own older
                // version on the next sync -- silently.
                clock = ClockObserver { syncClock.observe(it) },
            )

            return describe(engine.runPass())
        } finally {
            keys.destroy()
        }
    }

    /**
     * This device's server-assigned id, claiming the account if nobody has.
     *
     * Decision D2, as recommended: claim at first sync rather than at key creation, so a device
     * that never syncs has never touched a server. `AlreadyClaimed` is a **normal branch**, not an
     * error — it is what the second device of an account sees — and the honest answer for it is
     * null, because a joining device cannot enrol itself: it has to be vouched for by a device that
     * is already on the account, and that flow is not built.
     */
    private suspend fun enrol(api: SyncApi, accountId: String): Enrolment {
        enrolmentStore.deviceId(accountId)?.let { return Enrolment.Enrolled(it) }
        return try {
            when (val outcome = api.claimAccount(accountId, deviceLabel = Build.MODEL.orEmpty())) {
                is ClaimOutcome.Claimed -> {
                    enrolmentStore.setDeviceId(accountId, outcome.deviceId)
                    Enrolment.Enrolled(outcome.deviceId)
                }

                ClaimOutcome.AlreadyClaimed -> Enrolment.NeedsAuthorisation
            }
        } catch (e: SyncException) {
            // A claim that fails is not something the user can act on and the next pass tries
            // again, so it is a deferral rather than a state. Every `SyncException` message is
            // written to be safe to show and to log — `SyncExceptionSecrecyTest` holds the line
            // that no token, signature or account id appears in one.
            Log.w(TAG, "Could not claim the account; the next pass will try again", e)
            Enrolment.Failed(e.message ?: "The server didn't answer.")
        }
    }

    /** What [enrol] found. Three outcomes, because all three need a different response. */
    private sealed interface Enrolment {
        class Enrolled(val deviceId: String) : Enrolment

        /** Someone else claimed this account. This device has to be vouched for; see [enrol]. */
        data object NeedsAuthorisation : Enrolment

        /** The claim did not get an answer. Try again next pass. */
        class Failed(val message: String) : Enrolment
    }

    /**
     * What to say about a finished pass.
     *
     * Every string is a report of something that happened, never a claim about where the user's
     * notes now are. A halt in particular gets a sentence naming the thing a person has to decide,
     * because none of the halt reasons is recoverable automatically and telling someone to "try
     * again later" would be false for all six.
     */
    private fun describe(outcome: SyncOutcome): SyncPassState = when (outcome) {
        is SyncOutcome.Completed -> SyncPassState.Completed(
            SyncPassSummary(
                received = outcome.stats.received,
                applied = outcome.stats.applied,
                pushed = outcome.stats.pushed,
                conflictCopies = outcome.stats.conflictCopies,
                unreadable = outcome.stats.unreadable,
            )
        )

        is SyncOutcome.Deferred -> SyncPassState.Deferred(deferralMessage(outcome))
        is SyncOutcome.Halted -> SyncPassState.Halted(haltMessage(outcome.reason))
        SyncOutcome.AlreadyRunning -> _state.value
    }

    private fun deferralMessage(outcome: SyncOutcome.Deferred): String = when (outcome.fault) {
        my.cheysoff.core_sync_engine.TransportFault.NETWORK -> "Couldn't reach the server."
        my.cheysoff.core_sync_engine.TransportFault.RATE_LIMITED -> "The server asked this device to wait."
        my.cheysoff.core_sync_engine.TransportFault.UNAUTHORIZED -> "The server didn't accept this device's session."
        else -> "The server's answer wasn't one this app understands."
    }

    private fun haltMessage(reason: HaltReason): String = when (reason) {
        HaltReason.SERVER_ROLLED_BACK ->
            "The server's history is older than this device's. Syncing has stopped so nothing is " +
                "overwritten; this needs to be sorted out before it can start again."

        HaltReason.RECORDS_UNREADABLE ->
            "Records on the server can't be read with this device's account key. Syncing has stopped."

        HaltReason.UNSUPPORTED_PAYLOAD_VERSION ->
            "The account holds notes written by a newer version of this app. Syncing has stopped " +
                "rather than risk rewriting them."

        HaltReason.DEVICE_REVOKED ->
            "This device has been removed from the account. Pair it again to sync."

        HaltReason.RECORD_MISLABELLED, HaltReason.RECORD_IDENTITY_MISMATCH ->
            "A record on the server doesn't match its own identity. Syncing has stopped; this is a " +
                "bug worth reporting."
    }

    private companion object {
        const val TAG = "SyncController"
    }
}
