package my.cheysoff.ios.platform

import my.cheysoff.core_crypto.PassphraseCipher
import my.cheysoff.core_crypto.PinWrap
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.SyncProtocol

/**
 * Where this device keeps its Account Root Key: wrapped under the user's PIN, in the Keychain.
 *
 * ## How this differs from the Android build, and why
 *
 * On Android the ARK is wrapped under the **database passphrase** (`ArkCipher`), because there
 * already is one — SQLCipher needs a file key, both unlock paths produce it, and deriving from it
 * meant the ARK became available at exactly the moment the database did, with no second thing to
 * enable and no second thing to invalidate on fingerprint re-enrolment.
 *
 * There is no database passphrase here. `:core-store` does not encrypt its file, because every row
 * in it is already a sealed envelope — `RecordDriver.kt` sets out that trade. So the chain that on
 * Android runs *PIN → passphrase → ARK* runs *PIN → ARK* here, one link shorter, using
 * `PassphraseCipher` directly: PBKDF2-HMAC-SHA256 at 210,000 rounds, then AES-256-GCM.
 *
 * That is a smaller construction doing the same job, not a weaker one. What it does mean is that
 * `ArkCipher` and `SyncProtocol.INFO_ARK_WRAP` are unused on this platform, and a future change
 * that gave the iOS store a file key should reach for them rather than inventing a third shape.
 *
 * ## What the security actually rests on
 *
 * A six-digit PIN is a million candidates. At 210,000 PBKDF2 rounds each candidate costs on the
 * order of a tenth of a second, so the whole space is about a day of one CPU. **The wrap is not
 * safe against someone holding a copy of it.** Everything therefore rests on the copy being hard to
 * obtain, which is [Keychain]'s job and the reason it is a hundred lines rather than a call to
 * `NSUserDefaults`.
 *
 * ## The rule that must not be broken
 *
 * A vault that will not open is **not** a reason to mint a new ARK. `AccountRootKey.generateArk`
 * spells out what happens if that call is made twice for one account: the account forks in silence
 * and neither half can ever read the other's records. [unlock] therefore returns null for every
 * failure and [create] refuses to run while a wrap exists, which is the same guard
 * `SecureUnlockManager.ensureArk` enforces on Android.
 *
 * ## NOT RUN
 *
 * This compiles for every Apple target, and it has never executed. `PassphraseCipher` and
 * `AccountRootKey` underneath it are `commonMain` and are tested on the JVM; [Keychain] is not
 * tested anywhere. See `docs/BUILDING-IOS.md`.
 */
internal class ArkVault(private val keychain: Keychain = Keychain) {

    /** True once a PIN has been set on this device. */
    fun exists(): Boolean = keychain.read(KEY) != null

    /**
     * Creates the account: one fresh ARK, wrapped under [pin].
     *
     * Refuses if a wrap already exists, and returns null rather than throwing so that a caller
     * cannot handle it by retrying. See the class KDoc.
     */
    fun create(pin: CharArray): AccountKeys? {
        if (exists()) return null
        val ark = AccountRootKey.generateArk()
        return try {
            val wrap = PassphraseCipher.wrapWithPin(ark, pin)
            if (!keychain.store(KEY, encode(wrap))) return null
            AccountRootKey.derive(ark)
        } finally {
            // The derived keys hold their own copies; this one has done its job.
            ark.fill(0)
        }
    }

    /** Opens the vault, or null for a wrong PIN, a missing wrap, or a damaged one. */
    fun unlock(pin: CharArray): AccountKeys? {
        val stored = keychain.read(KEY) ?: return null
        val wrap = decode(stored) ?: return null
        val ark = PassphraseCipher.unwrapWithPin(wrap, pin) ?: return null
        if (ark.size != SyncProtocol.ARK_BYTES) {
            // A wrap that opens but is the wrong length is a damaged item rather than a wrong PIN --
            // GCM authenticated it, so the bytes are what was stored. Still nothing usable.
            ark.fill(0)
            return null
        }
        return try {
            AccountRootKey.derive(ark)
        } finally {
            ark.fill(0)
        }
    }

    /**
     * Destroys the wrap.
     *
     * **This destroys the account on this device**, and if it is the only paired device it destroys
     * the account: the records in `:core-store` remain, and nothing can open them again. It exists
     * because a "forget everything" affordance is worth having and because a half-removed vault
     * would be worse. It must never be called to recover from a failed unlock.
     */
    fun destroy(): Boolean = keychain.delete(KEY)

    /**
     * `iterations(4) ‖ saltLen(1) ‖ salt ‖ ivLen(1) ‖ iv ‖ ciphertext`.
     *
     * Length-prefixed rather than fixed-width, so that a future change to `PassphraseCipher`'s salt
     * or IV size does not silently misparse every existing wrap into a different one. The
     * iterations are stored rather than assumed for the same reason `PinWrap` carries them: raising
     * the count must not lock out a device that has not re-wrapped yet.
     */
    private fun encode(wrap: PinWrap): ByteArray {
        val out = ByteArray(4 + 1 + wrap.salt.size + 1 + wrap.iv.size + wrap.ciphertext.size)
        out[0] = (wrap.iterations ushr 24).toByte()
        out[1] = (wrap.iterations ushr 16).toByte()
        out[2] = (wrap.iterations ushr 8).toByte()
        out[3] = wrap.iterations.toByte()
        out[4] = wrap.salt.size.toByte()
        wrap.salt.copyInto(out, destinationOffset = 5)
        var offset = 5 + wrap.salt.size
        out[offset] = wrap.iv.size.toByte()
        wrap.iv.copyInto(out, destinationOffset = offset + 1)
        offset += 1 + wrap.iv.size
        wrap.ciphertext.copyInto(out, destinationOffset = offset)
        return out
    }

    private fun decode(bytes: ByteArray): PinWrap? {
        if (bytes.size < 6) return null
        val iterations = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        if (iterations <= 0) return null

        val saltLength = bytes[4].toInt() and 0xFF
        var offset = 5
        if (offset + saltLength >= bytes.size) return null
        val salt = bytes.copyOfRange(offset, offset + saltLength)
        offset += saltLength

        val ivLength = bytes[offset].toInt() and 0xFF
        offset += 1
        if (offset + ivLength > bytes.size) return null
        val iv = bytes.copyOfRange(offset, offset + ivLength)
        offset += ivLength

        return PinWrap(
            salt = salt,
            iv = iv,
            ciphertext = bytes.copyOfRange(offset, bytes.size),
            iterations = iterations,
        )
    }

    private companion object {
        /**
         * The Keychain account name.
         *
         * `v1` because a future format change has to be able to leave an old item alone rather than
         * overwrite it -- overwriting is the one operation that cannot be undone here.
         */
        const val KEY = "ark.wrap.v1"
    }
}
