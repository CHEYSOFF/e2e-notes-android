package my.cheysoff.desktop.sync

import my.cheysoff.core_sync_net.ClaimOutcome
import my.cheysoff.core_sync_net.SyncException
import my.cheysoff.core_sync_net.SyncHttpClient
import my.cheysoff.core_sync_net.http.ServerEndpoint
import my.cheysoff.desktop.vault.DeviceKeyPair

/** What a claim did. */
sealed interface ClaimResult {

    /** The server opened the account and this computer is its first device. */
    class Claimed(val deviceId: String) : ClaimResult

    /**
     * It did not. [message] is a sentence for the user.
     *
     * Covers a server that cannot be reached, one that refused, and — distinctly —
     * `409 account_exists`, which on this path means the account handle derived from this vault's
     * brand-new ARK is somehow already known to that server. There is no honest recovery from it
     * here: the joining path is what a device uses to reach an account it did not create.
     */
    class Refused(val message: String) : ClaimResult
}

/** What a vouch did. */
sealed interface VouchResult {

    /** The server enrolled the joining device. [deviceId] is what it will authenticate as. */
    class Enrolled(val deviceId: String) : VouchResult

    /** It did not. [message] is a sentence for the user. */
    class Refused(val message: String) : VouchResult
}

/**
 * How this computer talks to a sync server about *devices*, as opposed to about notes.
 *
 * Two calls, and they are the two ends of the same authority:
 *
 *  - [claim] is trust on first use. It is signed by this computer's own key and installs that key
 *    as the account's first device. **It is only ever legitimate for an account this computer just
 *    created** — a claim against somebody else's account handle would be a stranger asking to be
 *    the device that vouches for everyone after.
 *  - [vouch] spends that authority on somebody else. The server checks the signature against the
 *    voucher's stored key and refuses a revoked one, which is the whole reason a joining device
 *    cannot enrol itself.
 *
 * ## The rule about which key may be vouched for
 *
 * [vouch]'s `joiningDeviceKey` must be the key that came out of the pairing exchange the user just
 * confirmed, and nothing else. Asking the server which key to authorise would work identically and
 * would hand the choice to whoever answered the request, which is precisely what
 * `POST /v1/devices/authorize` exists to prevent. In the invite direction that key did not cross an
 * authenticated visual channel, so what ties it to the phone in the user's hand is that it arrived
 * in the same reply whose ephemeral point produced the six digits they compared —
 * `SealAuthority.joiningDeviceKey` is the only copy that exists after that comparison, which is why
 * it is the one this is called with.
 *
 * Separate from [DesktopSyncService], which assembles the *record* pipeline: that one needs a
 * device id and this one is how a device id comes to exist.
 */
class DesktopAccountServer(
    endpoint: ServerEndpoint,
    deviceKey: DeviceKeyPair,
    /**
     * Hands back a **copy** of the account root key, which the label sealer zeroes after each use.
     * A provider returning the live array would have it wiped underneath its owner.
     */
    arkProvider: () -> ByteArray?,
) {

    private val api = SyncHttpClient.create(
        endpoint = endpoint,
        signer = DesktopDeviceSigner(deviceKey),
        labelSealer = VaultDeviceLabelSealer(arkProvider),
    )

    /** `GET /healthz` — did anything answer, and was it this protocol. */
    suspend fun isReachable(): Boolean = try {
        api.health()
        true
    } catch (e: SyncException) {
        false
    }

    suspend fun claim(accountId: String, deviceLabel: String): ClaimResult = try {
        when (val outcome = api.claimAccount(accountId, deviceLabel)) {
            is ClaimOutcome.Claimed -> ClaimResult.Claimed(outcome.deviceId)
            ClaimOutcome.AlreadyClaimed -> ClaimResult.Refused(
                "That server already holds an account with this identifier. Nothing was changed " +
                    "there, and this computer is not enrolled on it."
            )
        }
    } catch (e: SyncException) {
        // Every `SyncException` message is written to be safe to show: `SyncExceptionSecrecyTest`
        // holds the line that no token, signature or account id appears in one.
        ClaimResult.Refused(e.message ?: "The server did not answer.")
    }

    suspend fun vouch(
        accountId: String,
        voucherDeviceId: String,
        joiningDeviceKey: ByteArray,
        deviceLabel: String,
    ): VouchResult = try {
        val enrolled = api.authorizeDevice(
            accountId = accountId,
            voucherDeviceId = voucherDeviceId,
            newPublicKey = joiningDeviceKey,
            deviceLabel = deviceLabel,
        )
        VouchResult.Enrolled(enrolled.deviceId)
    } catch (e: SyncException) {
        VouchResult.Refused(e.message ?: "The server did not answer.")
    } catch (e: IllegalArgumentException) {
        // `SyncHttpClient` validates its arguments with `require`; the 65-byte check on a key that
        // came off the rendezvous is the one that could plausibly fire.
        VouchResult.Refused("That phone offered a device key this app cannot use.")
    }
}
