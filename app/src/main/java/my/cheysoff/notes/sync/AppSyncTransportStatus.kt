package my.cheysoff.notes.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.cheysoff.core_domain.sync.SyncServerCheck
import my.cheysoff.core_domain.sync.SyncTransportStatus
import my.cheysoff.core_sync_net.SyncException
import my.cheysoff.feature_pairing.identity.DeviceIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings screen's window onto the sync transport.
 *
 * Joins the two things it needs — the pairing identity key from `:feature-pairing` and the client
 * from [SyncTransportProvider] — behind the `:core-domain` interface, so `:feature-settings`
 * depends on neither. Same argument, and the same module, as [KeystoreDeviceSigner].
 */
@Singleton
class AppSyncTransportStatus @Inject constructor(
    private val deviceIdentity: DeviceIdentity,
    private val transportProvider: SyncTransportProvider,
) : SyncTransportStatus {

    /**
     * `isProvisioned()` never creates the key — it only reports whether pairing already did. That
     * distinction is the whole reason this reads `isProvisioned()` and not `ensureProvisioned()`:
     * asking a settings screen "are you paired?" must not make the answer yes.
     */
    override suspend fun isPaired(): Boolean = withContext(Dispatchers.IO) {
        deviceIdentity.isProvisioned()
    }

    /**
     * One unauthenticated `GET /healthz`.
     *
     * This is the only network call the app makes anywhere, it happens only when the user taps
     * "Check server", and it happens only once [SyncTransportProvider] says the device is paired
     * *and* has a usable address — the `NotConfigured` branch returns without touching the network.
     *
     * The result is deliberately narrow. A reachable server means the address answers and speaks
     * this protocol. It does not mean this device is enrolled, that the account exists, or that
     * anything would sync: `/healthz` requires no credentials and is answered before any of that
     * is known.
     */
    override suspend fun checkServer(): SyncServerCheck = withContext(Dispatchers.IO) {
        when (val transport = transportProvider.current()) {
            is SyncTransport.NotConfigured -> SyncServerCheck.NotConfigured(transport.message)

            is SyncTransport.Ready -> try {
                transport.api.health()
                SyncServerCheck.Reachable
            } catch (e: SyncException) {
                // Every failure mode of a call on SyncApi is a SyncException, and every message it
                // carries is written to be safe to display: SyncExceptionSecrecyTest holds the line
                // that no token, signature, account ID or record ID appears in one. So it is shown
                // rather than replaced with a generic string that would hide a pin mismatch behind
                // "couldn't connect".
                SyncServerCheck.Unreachable(e.message ?: "The server didn't answer.")
            }
        }
    }
}
