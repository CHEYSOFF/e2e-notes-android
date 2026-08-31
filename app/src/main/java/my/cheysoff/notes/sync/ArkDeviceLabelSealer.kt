package my.cheysoff.notes.sync

import my.cheysoff.core_crypto.SecureUnlockManager
import my.cheysoff.core_crypto.sync.DeviceLabelCipher
import my.cheysoff.core_sync_net.auth.DeviceLabelSealer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seals device labels under the Account Root Key that `SecureUnlockManager` guards.
 *
 * ## Where the key comes from
 *
 * `SecureUnlockManager.currentArk()` — the in-memory ARK, unwrapped from `ark_ct` when the user
 * unlocked and zeroed again on lock. It hands out a **copy** the caller owns, so every method here
 * zeroes it in a `finally` as soon as `DeviceLabelCipher` has finished with it. Nothing else in
 * this class holds key material, and there is no field to leak one.
 *
 * `currentArk()` rather than `ensureArk()` on purpose. `ensureArk()` *mints* an ARK when a device
 * has none, and minting one has exactly one legitimate call site — pairing — because a second ARK
 * silently forks the account into two halves neither of which can read the other. Naming a device
 * is not a reason to create an account key.
 *
 * ## When it returns null
 *
 * A locked device has no ARK in memory, so [seal] and [open] both answer null. For [seal] that
 * means the device enrols with no label; for [open] it means the list renders every row as unnamed.
 * Both are honest and both are recoverable — the alternative for [seal] would be putting the name
 * on the wire in the clear, which is the thing this exists to stop. In practice enrolment happens
 * on the far side of the unlock screen, so the null branch is a guard rather than the common case.
 *
 * ## Why the adapter lives in `:app`
 *
 * The same reason [KeystoreDeviceSigner] does: `:app` is the module that can see both the key's
 * owner and `:core-sync-net`, and a `core-` module reaching for another module's key storage would
 * be backwards.
 */
@Singleton
class ArkDeviceLabelSealer @Inject constructor(
    private val secureUnlock: SecureUnlockManager,
) : DeviceLabelSealer {

    override fun seal(devicePublicKeyB64: String, label: String): ByteArray? {
        val trimmed = trimToSealableLength(label)
        if (trimmed.isEmpty()) return null
        val ark = secureUnlock.currentArk() ?: return null
        return try {
            DeviceLabelCipher.seal(ark, devicePublicKeyB64, trimmed)
        } finally {
            ark.fill(0)
        }
    }

    override fun open(devicePublicKeyB64: String, sealed: ByteArray): String? {
        val ark = secureUnlock.currentArk() ?: return null
        return try {
            DeviceLabelCipher.open(ark, devicePublicKeyB64, sealed)
        } finally {
            ark.fill(0)
        }
    }

    /**
     * The longest prefix of [label] that fits in [DeviceLabelCipher.MAX_LABEL_UTF8_BYTES] UTF-8
     * bytes, cut on a character boundary.
     *
     * `DeviceLabelCipher.seal` throws for anything longer and says why: it will not truncate,
     * because cutting UTF-8 at an arbitrary byte can split a multi-byte character and leave a
     * replacement glyph in somebody's device name. Capping is the caller's job, and this is the
     * caller. It is done by dropping whole code points from the end, so a Cyrillic or emoji name
     * comes back shorter rather than broken — and an over-long name must not be able to stop a
     * device enrolling, which a thrown exception from a coroutine on the enrolment path would.
     */
    private fun trimToSealableLength(label: String): String {
        var candidate = label
        while (candidate.toByteArray(Charsets.UTF_8).size > DeviceLabelCipher.MAX_LABEL_UTF8_BYTES) {
            // offsetByCodePoints backwards: drops one whole code point, surrogate pairs included.
            candidate = candidate.substring(0, candidate.offsetByCodePoints(candidate.length, -1))
        }
        return candidate
    }
}
