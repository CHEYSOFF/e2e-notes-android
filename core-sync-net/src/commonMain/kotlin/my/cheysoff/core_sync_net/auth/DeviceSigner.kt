package my.cheysoff.core_sync_net.auth

/**
 * The device's signing identity, as the transport needs to see it.
 *
 * ## There is exactly one key, and this module does not own it
 *
 * The key is the EC P-256 pair `feature-pairing` provisions in the AndroidKeyStore during pairing
 * (`feature-pairing/.../identity/DeviceIdentityKey.kt`, alias `manana_device_identity_v1`,
 * `SHA256withECDSA`, never exported). That class was written *for* this: its own KDoc says the
 * signing method is "present for Phase 3 rather than used here".
 *
 * This interface therefore exists to **reach** that key, not to have one. Minting a second identity
 * key here would be an unambiguous bug: the server stores one public key per device row and
 * verifies every `authorize` and every session challenge against it, so a device that enrolled with
 * one key and signed with another would enrol successfully and then be unable to open a single
 * session -- and the failure would arrive later, on a device that is by then the only holder of
 * some notes.
 *
 * The production implementation is a thin adapter in `:app` (`KeystoreDeviceSigner`), because `:app`
 * is the module that can see both `feature-pairing` and this one, and because a `core-` module
 * depending on a `feature-` module is backwards.
 *
 * ## Why it is an interface at all
 *
 * The AndroidKeyStore cannot exist in a JVM unit test -- `KeyGenParameterSpec` and the
 * `"AndroidKeyStore"` provider are platform code with no desktop equivalent. `feature-pairing` split
 * `DeviceIdentity` out of `DeviceIdentityKey` for exactly this reason and says so. The same split
 * here is what lets `SyncServerContractTest` sign with a plain JCE P-256 key and prove that these
 * bytes, this algorithm and this encoding are the ones the real server accepts.
 */
interface DeviceSigner {

    /**
     * This device's public identity key, SEC1 **uncompressed**: `0x04 ‖ X(32) ‖ Y(32)`, 65 bytes.
     *
     * That is precisely what `feature-pairing`'s `P256.encodePublicKey` emits and what the server's
     * `P256Verify.decodePublicKey` accepts; any other encoding is rejected as
     * `invalid_public_key`.
     */
    fun publicKeySec1(): ByteArray

    /**
     * Signs [message] with the device key, returning a **DER-encoded** `SHA256withECDSA` signature.
     *
     * DER, not the fixed-width `r ‖ s` form: `Signature.getInstance("SHA256withECDSA")` produces DER
     * on both sides, and the server verifies with the same algorithm name. [message] is always the
     * output of [SignedMessage]; nothing else in this module signs anything.
     */
    fun sign(message: ByteArray): ByteArray
}
