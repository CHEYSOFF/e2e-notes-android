package my.cheysoff.desktop.keychain

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path

/**
 * "Remember me" on Windows, via DPAPI.
 *
 * `CryptProtectData` encrypts under a key derived from the logged-in user's Windows credentials and
 * the machine; `CryptUnprotectData` reverses it for that same user on that same machine and for
 * nobody else. Windows keeps the key — this module never sees it, never stores it, and cannot
 * export it, which is as close as a desktop gets to the Android Keystore anchor the phone has.
 *
 * ## Why a file, when DPAPI is not a store
 *
 * DPAPI protects bytes; it does not keep them. So the protected blob is written next to the vault
 * as `remember.dpapi`. That is safe precisely because it is *not* a store: the file is inert
 * anywhere but this user account on this machine. It sits in the vault directory rather than
 * somewhere central so that deleting the vault deletes it too — a stale blob whose vault is gone is
 * a secret nothing will ever clean up.
 *
 * Windows Credential Manager (`CredWrite`) was the alternative and is not better here. It stores
 * the value with DPAPI underneath, so the protection is identical, and it adds a namespace whose
 * entries survive the vault directory being deleted.
 *
 * ## The entropy parameter
 *
 * `entropy` is a second input that must be supplied again at unprotect time. It is **not a secret**
 * — it is a constant in this binary and anyone can read it out. Its job is domain separation: a
 * blob produced by some other application under the same user cannot be handed to this one and
 * decrypt, and vice versa. Treating it as a secret would be the mistake; leaving it out would mean
 * any DPAPI blob on the machine is a candidate input here.
 *
 * ## What breaks it, and what that costs
 *
 * An administrator resetting the user's Windows password (as opposed to the user changing it)
 * discards the DPAPI master key, and every blob protected under it becomes undecryptable. So does
 * moving the file to another machine or another user. All of those surface as [recall] returning
 * null, which lands the user at the passphrase prompt — the vault itself is untouched, because the
 * passphrase wrap in `vault.json` never depended on any of this.
 */
class DpapiCredentialStore(
    private val blobFile: Path,
    /**
     * Injected so the tests can drive the failure paths.
     *
     * DPAPI failures are real (a reset password, a roamed profile) and every one of them has to
     * end at the passphrase prompt rather than at an exception out of `recall`. There is no way to
     * make the real `Crypt32Util` fail on demand, so the seam is here.
     */
    private val dpapi: Dpapi = SystemDpapi,
) : CredentialStore {

    /** The DPAPI calls this class makes, as a seam. */
    interface Dpapi {
        fun protect(data: ByteArray, entropy: ByteArray): ByteArray
        fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray
    }

    /** The real thing. */
    object SystemDpapi : Dpapi {
        override fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
            Crypt32Util.cryptProtectData(data, entropy, 0, DESCRIPTION, null)

        override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray =
            Crypt32Util.cryptUnprotectData(data, entropy, 0, null)
    }

    override val isAvailable: Boolean = true

    override val description: String = "Windows Data Protection (DPAPI)"

    override fun isRemembered(): Boolean = Files.exists(blobFile)

    override fun remember(secret: ByteArray): Boolean = try {
        val blob = dpapi.protect(secret, ENTROPY)
        Files.createDirectories(blobFile.parent)
        // Written whole rather than appended or updated in place: a half-written blob is not a
        // recoverable state, and DPAPI would reject it anyway.
        Files.write(blobFile, blob)
        true
    } catch (_: Exception) {
        // Broad on purpose: a Win32 error out of DPAPI, a read-only directory, a full disk and a
        // virus scanner holding the file open all arrive as different types and all mean the same
        // thing to the caller -- the secret is not stored, so keep asking for the passphrase.
        // Nothing partial is left behind that a later `recall` could mistake for a real blob,
        // because `recall` re-runs DPAPI over whatever it finds.
        false
    }

    override fun recall(): ByteArray? = try {
        if (!Files.exists(blobFile)) null else dpapi.unprotect(Files.readAllBytes(blobFile), ENTROPY)
    } catch (_: Exception) {
        // A blob this user/machine cannot decrypt is indistinguishable from no blob at all, and
        // both mean "ask for the passphrase". The file is deliberately NOT deleted here: an
        // administrator password reset is recoverable by the administrator putting the profile
        // back, and deleting the blob on the first failed launch would make that irreversible.
        null
    }

    override fun forget() {
        Files.deleteIfExists(blobFile)
    }

    private companion object {
        /** Shown in the Windows credential-usage UI; not security-relevant. */
        const val DESCRIPTION = "Manana vault key"

        /**
         * Domain-separation entropy. Public by construction — see the class KDoc.
         *
         * Changing these bytes makes every existing `remember.dpapi` undecryptable. That costs one
         * extra passphrase prompt per machine and nothing else, which is why it is a constant here
         * rather than a protocol constant in `SyncProtocol`: nothing on another device depends on
         * it.
         */
        val ENTROPY: ByteArray = "manana/desktop/v1/dpapi".toByteArray(Charsets.US_ASCII)
    }
}
