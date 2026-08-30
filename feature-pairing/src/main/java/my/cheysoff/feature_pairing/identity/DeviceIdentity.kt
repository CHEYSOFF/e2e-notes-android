package my.cheysoff.feature_pairing.identity

/**
 * The device's long-lived signing identity, as the pairing flow needs to see it.
 *
 * An interface with exactly one production implementation ([DeviceIdentityKey], backed by the
 * AndroidKeyStore) because the Keystore cannot exist in a JVM unit test: `KeyGenParameterSpec` and
 * the `"AndroidKeyStore"` provider are platform code with no desktop equivalent. Everything that
 * *decides* when the key is provisioned lives in the ViewModel above this line and is tested with
 * a fake; what is left below it is one `KeyPairGenerator` call, which is not.
 */
interface DeviceIdentity {

    /**
     * Create the device's identity key if it does not exist yet, and return its public half in the
     * SEC1 uncompressed encoding.
     *
     * Idempotent. Called when a pairing completes, because that is the moment a device joins an
     * account and therefore the moment the key starts to mean something.
     */
    fun ensureProvisioned(): ByteArray

    /** True once the key exists. Never creates one. */
    fun isProvisioned(): Boolean
}
