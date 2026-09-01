package my.cheysoff.notes.sync

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.first
import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_domain.repository.SyncSettingsRepository
import my.cheysoff.core_pairing.protocol.RendezvousUrl
import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.SyncException
import my.cheysoff.core_sync_net.SyncHttpClient
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.feature_pairing.identity.DeviceEnroller
import my.cheysoff.feature_pairing.identity.EnrolmentResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vouches for a joining device: claims the account if nobody has, then signs
 * `POST /v1/devices/authorize` for the key that came out of QR1.
 *
 * ## The two rules this class exists to keep
 *
 * **It vouches only for the key it was handed.** That key came from QR1, which a person read with a
 * camera pointed at a screen they were looking at. Nothing here asks the server which key to
 * authorise, and nothing here reads a key out of the rendezvous — either would move the choice of
 * what gets enrolled to whoever answers a request.
 *
 * **It vouches only as an enrolled device.** The server verifies the signature against the voucher's
 * stored public key and refuses a revoked one; a device that has never enrolled has no row for it to
 * check. So a device that cannot claim and has no stored id refuses rather than pretending — see
 * [ownDeviceId].
 *
 * ## Why it builds its own client
 *
 * [SyncTransportProvider] answers for the server in *settings*, and at this moment there may not be
 * one: the address is what the joining device just put in QR1 and the user has just been shown and
 * approved. So the endpoint is built from that address here. It is the same [SyncHttpClient] with
 * the same signer and the same label sealer — only the base URL differs, and only until [enrol]
 * records it.
 *
 * ## It records the address
 *
 * A successful vouch is proof that this device can reach that server, authenticate to it, and act on
 * this account there. Writing it to [SyncSettingsRepository] when nothing is stored is therefore not
 * trusting a QR code: it is recording something that has just been demonstrated. An address already
 * in settings is left alone — the user's own configuration outranks a hint, and silently
 * re-pointing a phone at a server a second device named is exactly the move an attacker with a
 * printed QR would want.
 */
@Singleton
class SyncDeviceEnroller @Inject constructor(
    private val secureUnlock: SecureUnlockManager,
    private val enrolmentStore: SyncEnrolmentStore,
    private val syncSettings: SyncSettingsRepository,
    private val signer: DeviceSigner,
    private val labelSealer: DeviceLabelSealer,
) : DeviceEnroller {

    override suspend fun enrol(
        server: RendezvousUrl,
        joiningDeviceKey: ByteArray,
        label: String,
    ): EnrolmentResult {
        val endpoint = try {
            ServerEndpoint(server.base)
        } catch (e: IllegalArgumentException) {
            // The only reachable case is plain http to something that is not loopback. Reported
            // rather than swallowed: it is the one configuration error a user can actually fix, and
            // "put the server behind https" is a sentence, where "enrolment failed" is not.
            return EnrolmentResult.Refused(
                "That computer's address cannot be used for sync: ${e.message}"
            )
        }

        // `currentArk`, never `ensureArk`. Minting an account key here would fork the account for a
        // user who is in the middle of sharing it -- the same rule `DefaultSyncController` states.
        val ark = secureUnlock.currentArk()
            ?: return EnrolmentResult.Refused(
                "This device's account key isn't available right now. Unlock it and try again."
            )
        val keys = try {
            AccountRootKey.derive(ark)
        } finally {
            ark.fill(0)
        }

        return try {
            val accountId = Base64Url.encode(keys.accountId)
            val api = SyncHttpClient.create(
                endpoint = endpoint,
                signer = signer,
                labelSealer = labelSealer,
            )
            val voucherId = ownDeviceId(api, accountId)
                ?: return EnrolmentResult.Refused(
                    "This phone isn't authorised on the account's server yet, so it can't " +
                        "authorise the computer. Sync this phone first, then pair again."
                )

            val enrolled = api.authorizeDevice(
                accountId = accountId,
                voucherDeviceId = voucherId,
                newPublicKey = joiningDeviceKey,
                deviceLabel = label,
            )
            rememberServerIfUnset(server)
            EnrolmentResult.Enrolled(enrolled.deviceId)
        } catch (e: SyncException) {
            // Every `SyncException` message is written to be safe to show and to log --
            // `SyncExceptionSecrecyTest` holds the line that no token, signature or account id
            // appears in one.
            Log.w(TAG, "Could not enrol the joining device", e)
            EnrolmentResult.Refused(e.message ?: "The server didn't answer.")
        } catch (e: IllegalArgumentException) {
            // `SyncHttpClient` validates its arguments with `require`; a 65-byte check on a key
            // that came off a QR code is the one that could plausibly fire.
            Log.w(TAG, "The sync client refused the enrolment request", e)
            EnrolmentResult.Refused("That computer offered a device key this app cannot use.")
        } finally {
            keys.destroy()
        }
    }

    /**
     * This device's server-assigned id, claiming the account if it is unclaimed.
     *
     * Null means this device is not enrolled and could not become enrolled, which is the one state
     * in which it must not vouch: the server would reject the signature, and pretending otherwise
     * here would seal a `deviceId` into the bundle that the joining device could never use.
     *
     * `AlreadyClaimed` is null for the same reason `DefaultSyncController` treats it as
     * `NeedsAuthorisation`: somebody else owns the account's first-device slot, and this phone has
     * not been vouched for either. A phone in that state cannot bring a third device in behind it.
     */
    private suspend fun ownDeviceId(api: my.cheysoff.core_sync_net.SyncApi, accountId: String): String? {
        enrolmentStore.deviceId(accountId)?.let { return it }
        return when (val outcome = api.claimAccount(accountId, deviceLabel = Build.MODEL.orEmpty())) {
            is ClaimOutcome.Claimed -> outcome.deviceId.also {
                enrolmentStore.setDeviceId(accountId, it)
            }

            ClaimOutcome.AlreadyClaimed -> null
        }
    }

    private suspend fun rememberServerIfUnset(server: RendezvousUrl) {
        if (syncSettings.serverUrl.first().isNullOrBlank()) {
            syncSettings.setServerUrl(server.base)
        }
    }

    private companion object {
        const val TAG = "SyncDeviceEnroller"
    }
}
