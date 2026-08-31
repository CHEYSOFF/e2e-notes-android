package my.cheysoff.notes.sync

import my.cheysoff.core_sync_net.auth.DeviceSigner
import my.cheysoff.feature_pairing.identity.DeviceIdentityKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the sync transport sign with the device identity key that pairing already provisioned.
 *
 * ## There is one key, and this is not where it is made
 *
 * `DeviceIdentityKey` creates a single EC P-256 pair in the AndroidKeyStore under the alias
 * `manana_device_identity_v1` when a device finishes pairing, and never exports it. Its `sign()`
 * was written for this moment and had no caller until now. This class adds nothing to it: it
 * forwards two calls and holds no state.
 *
 * **A second key here would be a silent, delayed catastrophe.** The server stores exactly one
 * public key per device row and verifies every `authorize` and every session challenge against it.
 * A device that enrolled with the pairing key and then signed with a key minted here would enrol
 * successfully -- the enrolment request is signed by the *voucher*, not by the joining device --
 * and would then fail its very first session handshake with `401 bad_signature`, on a device that
 * by then may be the only holder of some notes. `DeviceIdentityKey.publicKey()` is idempotent for
 * the same reason and says so.
 *
 * ## Why the adapter lives in `:app`
 *
 * `:app` is the only module that can see both `:feature-pairing` (which owns the key) and
 * `:core-sync-net` (which needs a signature). A `core-` module depending on a `feature-` module
 * would be backwards and would invite a dependency cycle the moment pairing wants to talk to a
 * server. So `:core-sync-net` declares the [DeviceSigner] interface, `:feature-pairing` keeps
 * owning the key, and the few lines that join them live here.
 */
@Singleton
class KeystoreDeviceSigner @Inject constructor(
    private val identityKey: DeviceIdentityKey,
) : DeviceSigner {

    /**
     * SEC1 uncompressed, `0x04 ‖ X(32) ‖ Y(32)`.
     *
     * `encodedPublicKey()` generates the pair on first call, which makes this safe on a device that
     * has paired but never synced -- and idempotent on every device that has.
     */
    override fun publicKeySec1(): ByteArray = identityKey.encodedPublicKey()

    /** DER `SHA256withECDSA`, produced inside the Keystore. The private key never leaves it. */
    override fun sign(message: ByteArray): ByteArray = identityKey.sign(message)
}
