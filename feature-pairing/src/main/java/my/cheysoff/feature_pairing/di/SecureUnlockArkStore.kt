package my.cheysoff.feature_pairing.di

import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.Base64Url
import my.cheysoff.core_pairing.protocol.AccountBundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PairingKeyMaterial] over `SecureUnlockManager`'s wrapped-ARK storage.
 *
 * The whole of this module's contact with the account key, and it is four lines of real work:
 * ask for the ARK, derive the account handle from it, hand both over, and put a received one back.
 * Everything that makes those operations safe — that generation has exactly one call site, that a
 * stored `ark_ct` is never overwritten by a fresh key, that the ARK is wrapped under the database
 * passphrase and zeroed on lock — lives in [SecureUnlockManager] with the storage it guards.
 *
 * ## Key material handling
 *
 * `SecureUnlockManager` hands out copies the caller owns. The copies here are zeroed as soon as
 * they have been used, except the one inside the returned [AccountBundle]: that array is the
 * bundle, the session seals it and drops its reference immediately afterwards. Nothing here is
 * logged, and there is no `toString` that could carry it into a bug report.
 */
@Singleton
class SecureUnlockArkStore @Inject constructor(
    private val secureUnlock: SecureUnlockManager,
) : PairingKeyMaterial {

    /** The hierarchy is compiled in. See [PairingKeyMaterial.isBound]. */
    override val isBound: Boolean = true

    /**
     * True while the device is unlocked.
     *
     * Deliberately not "has an ARK". The first device to pair does not have one yet and is exactly
     * the device that must be able to choose this role — refusing it would make the very first
     * pairing impossible. What is genuinely required is an unlocked passphrase, because that is
     * what the new ARK gets wrapped under. In practice the pairing screen is only reachable behind
     * the unlock guard, so this is true whenever the chooser is on screen; it is checked anyway
     * rather than assumed.
     */
    override fun canShareAccount(): Boolean = secureUnlock.unlocked.value

    /**
     * The ARK plus its derived account handle, minting the ARK if this device has none.
     *
     * `accountId` is `HKDF(ARK, ".../account")` rendered as unpadded base64url, so both devices
     * compute the same 22 characters from the same ARK and the string is never the authority for
     * anything — it is a handle, recomputable at any time from the key it names.
     */
    override fun accountBundle(): AccountBundle? {
        val ark = secureUnlock.ensureArk() ?: return null
        val keys = AccountRootKey.derive(ark)
        try {
            return AccountBundle(ark = ark, accountId = Base64Url.encode(keys.accountId))
        } finally {
            // K_content and K_id are not wanted here; only the account handle is. Nothing else in
            // the app derives them yet, so they exist for the length of this call and no longer.
            keys.destroy()
        }
    }

    override fun adopt(bundle: AccountBundle) {
        secureUnlock.adoptArk(bundle.ark)
    }
}
