package my.cheysoff.desktop.vault

import my.cheysoff.core_crypto.sync.Hkdf
import my.cheysoff.core_pairing.protocol.P256
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * This computer's long-lived signing identity: one P-256 key pair, generated when a pairing starts
 * and sealed into `vault.json` when the vault is created.
 *
 * ## Where the private key lives, and why not the OS credential store
 *
 * It is wrapped under the **vault key** — the same 32 random bytes the passphrase unwraps and the
 * ARK is wrapped under — and it therefore exists in the clear only while the vault is open. That is
 * the right bound for three reasons, and none of them is "it was easiest":
 *
 *  - **Sync is foreground-only on both platforms.** `DefaultSyncController` refuses to run a pass
 *    while the phone is locked because the keys are gone; the desktop is the same. A signing key
 *    available while the vault is closed would be available at a moment nothing is allowed to sync,
 *    which buys nothing.
 *  - **It is strictly less valuable than what is beside it.** Whoever can unwrap this can unwrap the
 *    ARK in the same file, and the ARK decrypts every note. Protecting the signing key *less* would
 *    be the only choice that changed anything, and it would change it for the worse.
 *  - **The credential store is the weaker option, not a stronger one.** `DpapiCredentialStore` and
 *    `MacKeychainCredentialStore` back "remember me on this computer": they hand the secret back to
 *    anyone logged in as this user, with no passphrase. That is an acceptable trade for a
 *    convenience the user opted into and can revoke; it is not where a key that authorises writes to
 *    an account belongs. A machine that *has* opted in ends up with the same exposure anyway through
 *    the vault key — which is exactly why this key must not be protected any less than the vault
 *    key, and need not be protected any more.
 *
 * ## What this is not
 *
 * It is not the AndroidKeyStore, and there is no desktop equivalent. While the vault is open the
 * private key is a byte array in this process's heap, and a debugger attached to a running, unlocked
 * app can read it. The phone's `DeviceIdentityKey` is genuinely stronger there — its key never
 * leaves the Keystore — and the honest statement of the desktop's threat model is that it protects
 * a stolen *disk*, not a machine someone is sitting at. That is the same protection `vault.json`
 * already gives the ARK, and it is the bound the whole desktop app lives inside.
 *
 * ## Both halves are stored
 *
 * PKCS#8 is what `PrivateKey.getEncoded()` produces and what `KeyFactory` reads back, so the wrap
 * holds the JCA's own serialisation rather than a hand-rolled scalar encoding that would have to
 * agree with it. The public point is stored beside it **in the clear** — it is public, the server
 * has it, and it appears in QR1 — rather than recomputed, because the JCA has no "public key from
 * private key" call and the alternative is hand-written curve arithmetic sitting under a signing
 * key. [DeviceKeyPair.verifySelfConsistent] is the cheap check that the two stored halves still
 * belong together.
 */
class DeviceKeyPair(
    /** The private key, PKCS#8. Secret; wrapped by [DeviceKeyCipher] before it reaches disk. */
    val privateKeyPkcs8: ByteArray,
    /** The public point, SEC1 uncompressed — what the server stores and what QR1 carries. */
    val publicKeySec1: ByteArray,
) {

    /** DER `SHA256withECDSA` over [message], the encoding the server verifies with. */
    fun sign(message: ByteArray): ByteArray = Signature.getInstance(SIGNATURE_ALGORITHM).run {
        initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8)))
        update(message)
        sign()
    }

    /**
     * True when [publicKeySec1] verifies a signature made by [privateKeyPkcs8].
     *
     * One signature and one verification, run once when a vault is opened. It is worth the
     * millisecond because the failure it catches is the worst one available here: a device that
     * enrolled with one key and signs with another enrols successfully and then fails **every**
     * session handshake, on a machine that by then may hold notes nothing else has. That is the
     * exact failure `KeystoreDeviceSigner`'s KDoc warns about on the phone, where it is prevented by
     * there only ever being one key; here there are two stored halves and this is the check.
     */
    fun verifySelfConsistent(): Boolean = try {
        val point = P256.decodePublicKey(publicKeySec1)
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(point)
            update(CONSISTENCY_PROBE)
            verify(sign(CONSISTENCY_PROBE))
        }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {

        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /** Arbitrary and constant. It is signed and verified in one breath and never stored. */
        private val CONSISTENCY_PROBE = "manana/desktop/v1/devicekey".toByteArray(Charsets.US_ASCII)

        /** A freshly generated pair. Called once, when a pairing attempt starts. */
        fun generate(random: SecureRandom = SecureRandom()): DeviceKeyPair {
            val pair = P256.generateKeyPair(random)
            return DeviceKeyPair(
                privateKeyPkcs8 = pair.private.encoded,
                publicKeySec1 = P256.encodePublicKey(pair.public as ECPublicKey),
            )
        }
    }
}

/** A sealed blob and the nonce it was produced under. Same shape as `ArkWrap`, different key. */
class DeviceKeyWrap(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * Wraps the device signing key under `HKDF(vaultKey, "manana/desktop/v1/devicekeywrap")`.
 *
 * A sibling of `ArkCipher` rather than a call to it: `ArkCipher.wrap` requires exactly 32 bytes and
 * a PKCS#8 EC key is about 140. The `info` string differs so that the two ciphertexts in one
 * `vault.json` are under two independent keys — the same domain-separation argument `ArkCipher`
 * makes for not using the passphrase directly, and here it is load-bearing rather than tidy, because
 * both blobs sit in the same file under the same `vaultKey`.
 *
 * Desktop-local on purpose. The phone has no use for it: its signing key is in the AndroidKeyStore
 * and is never exported, so a shared module would carry a wrap format only one platform can produce.
 */
object DeviceKeyCipher {

    /** Domain separation. Not `manana/sync/...`: nothing about this crosses a wire. */
    const val INFO = "manana/desktop/v1/devicekeywrap"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    private val secureRandom = SecureRandom()

    fun wrap(pkcs8: ByteArray, vaultKey: ByteArray): DeviceKeyWrap {
        val key = deriveWrapKey(vaultKey)
        try {
            val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            return DeviceKeyWrap(iv = iv, ciphertext = cipher.doFinal(pkcs8))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Unwrap, or null if GCM refuses.
     *
     * Null is not a reason to generate a replacement. A device key that will not unwrap is a device
     * the server no longer recognises, and minting a new one would enrol nothing and fail every
     * session handshake; the honest response is to report that this vault cannot sync and let the
     * user pair again.
     */
    fun unwrap(wrap: DeviceKeyWrap, vaultKey: ByteArray): ByteArray? {
        val key = deriveWrapKey(vaultKey)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, wrap.iv))
            cipher.doFinal(wrap.ciphertext)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun deriveWrapKey(vaultKey: ByteArray): ByteArray = Hkdf.derive(
        ikm = vaultKey,
        salt = null,
        info = INFO.toByteArray(Charsets.US_ASCII),
        length = KEY_BYTES,
    )
}
