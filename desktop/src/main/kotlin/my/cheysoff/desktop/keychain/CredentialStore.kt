package my.cheysoff.desktop.keychain

/**
 * "Remember me on this computer": somewhere the OS will hold the vault key so the user does not
 * retype the passphrase on every launch.
 *
 * ## This is a convenience layer, never a replacement
 *
 * The passphrase wrap in `vault.json` is written at setup and is never removed, whatever this
 * store does. That is the invariant the whole design rests on, and it has two consequences worth
 * stating separately because they are separately easy to break:
 *
 *  1. **A vault stays openable off this machine.** Copy the directory to another computer and the
 *     passphrase still opens it. A DPAPI blob or a Keychain item does not travel, and if either
 *     were the only copy of the key, moving the vault would destroy it.
 *  2. **A failure here costs convenience and nothing else.** [remember] returning false, [recall]
 *     returning null, the credential store being uninstalled, the user's Windows password being
 *     reset (which invalidates DPAPI) — every one of those ends at the passphrase prompt. None of
 *     them may end at a weaker place to put the key, and there is no fallback implementation that
 *     writes it anywhere else. [NoCredentialStore] is what "no store" means here: ask the user.
 *
 * ## What it is worth
 *
 * Exactly what the OS gives it. On Windows, DPAPI ties the blob to the logged-in user account on
 * the specific machine; on macOS, the Keychain item is unlocked with the login keychain. Both mean
 * an attacker who is already running as that user, on that machine, while they are logged in, can
 * recover the vault key — the same attacker who could read the decrypted notes out of the running
 * app's memory anyway. Neither helps against a stolen powered-off disk, which is what the
 * passphrase is for.
 */
interface CredentialStore {

    /**
     * True when this store can be used at all on this machine.
     *
     * Checked before offering the user the option, so the checkbox is absent rather than present
     * and broken. It does not promise [remember] will succeed — the store can be available and
     * still refuse — which is why [remember] reports its own result.
     */
    val isAvailable: Boolean

    /** Short human-readable name of the backing store, for the checkbox label. */
    val description: String

    /** True when a secret is currently held. Cheap; does not decrypt anything. */
    fun isRemembered(): Boolean

    /**
     * Stores [secret], replacing anything already stored, and returns whether it worked.
     *
     * **Returns false rather than throwing, and false must reach the user.** A silent failure here
     * is the one that matters: the user ticks "remember me", believes they no longer need the
     * passphrase, and discovers otherwise at some later launch — possibly after forgetting it.
     */
    fun remember(secret: ByteArray): Boolean

    /**
     * The stored secret, or null if there is none or it cannot be recovered.
     *
     * Null is not an error condition and must not be reported as one: it is the ordinary state of
     * a machine where the user never ticked the box, and it is also what a machine looks like after
     * the OS has invalidated the stored item. Both lead to the passphrase prompt.
     */
    fun recall(): ByteArray?

    /** Removes the stored secret. A no-op when there is none. */
    fun forget()
}

/**
 * The store for a platform this build has no credential store for — and for every failure that
 * makes the real one unusable.
 *
 * Every method is the honest answer rather than a working-looking one: nothing is stored, nothing
 * is recalled, and [remember] says false so the caller tells the user the passphrase is still
 * required. There is deliberately **no** file-backed fallback: a "fallback" for a credential store
 * is a place to put a secret that the OS is not protecting, which is worse than typing a
 * passphrase.
 */
object NoCredentialStore : CredentialStore {
    override val isAvailable: Boolean = false
    override val description: String = "not available on this system"
    override fun isRemembered(): Boolean = false
    override fun remember(secret: ByteArray): Boolean = false
    override fun recall(): ByteArray? = null
    override fun forget() = Unit
}
