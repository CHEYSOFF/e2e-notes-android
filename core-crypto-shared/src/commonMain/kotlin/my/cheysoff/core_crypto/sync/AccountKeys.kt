package my.cheysoff.core_crypto.sync

import my.cheysoff.core_crypto.platform.secureRandomBytes

/**
 * The three values derived from the Account Root Key, as one immutable bundle.
 *
 * Two of them are secrets that never leave the device ([kContent], [kId]); the third
 * ([accountId]) is the only ARK-derived value the server is ever shown. Because it is a one-way
 * HKDF output, nobody can name — and therefore nobody can squat — an account whose ARK they do
 * not hold, which is what makes trust-on-first-use account claiming safe.
 *
 * The arrays are handed out by reference rather than copied, so a caller must not mutate them.
 * [destroy] zeroes the two secrets when a bundle is finished with.
 */
class AccountKeys(
    /** `K_content` — the AEAD root for record encryption. Per-record keys are derived from it. */
    val kContent: ByteArray,
    /** `K_id` — the HMAC key that turns a local note UUID into a blinded record ID. */
    val kId: ByteArray,
    /** `accountId` — 16 bytes, the server-visible account handle. Not a secret, but unguessable. */
    val accountId: ByteArray,
) {
    /**
     * Zeroes the two secret keys. [accountId] is left alone: it is public by design and the caller
     * usually still needs it (it is what URLs are built from).
     */
    fun destroy() {
        kContent.fill(0)
        kId.fill(0)
    }
}

/**
 * The sync key hierarchy: pure derivations from a 32-byte Account Root Key.
 *
 * ```
 * ARK (32 bytes, SecureRandom, created ONCE per account)
 *   ├─ K_content = HKDF(ARK, "manana/sync/v1/content")   record AEAD root
 *   ├─ K_id      = HKDF(ARK, "manana/sync/v1/recordid")  blinded record IDs
 *   └─ accountId = HKDF(ARK, "manana/sync/v1/account")   server-visible handle, 128 bits
 * ```
 *
 * Everything here takes the ARK as a parameter and returns new arrays. Nothing in this file reads
 * storage, touches Android, or holds state, which is what makes it unit-testable — and it is also
 * what kept the wiring small: `SecureUnlockManager.currentArk()` and `ensureArk()` own the
 * storage, and every caller simply passes what they return to [derive].
 *
 * See `docs/design/e2e-sync-architecture.md` §"Key hierarchy".
 */
object AccountRootKey {

    /**
     * Generates a fresh Account Root Key.
     *
     * **This function must be called in exactly ONE place, ever, for the lifetime of an account.**
     *
     * A second call does not fail and does not warn. It returns another perfectly good 32-byte
     * key, and from that moment the account is silently forked: records sealed under the first ARK
     * cannot be opened under the second and vice versa, `accountId` changes so the two halves do
     * not even talk to the same server bucket, and there is no way to reconcile them afterwards
     * because neither half's plaintext is recoverable from the other's key. The user's notes are
     * not corrupted — they are split into two sets, each of which is permanently unreadable to the
     * other device.
     *
     * The discipline that prevents this is the same one `SecureUnlockManager.setupPin` already
     * uses: exactly one call site, guarded by a check that no wrapped ARK exists yet. That guard
     * belongs with the storage, so it is not in this file — this file only promises that the bytes
     * are 32 uniformly random ones.
     *
     * That one call site is `SecureUnlockManager.ensureArk()`, which returns null rather than
     * calling this whenever `ark_ct` is already stored — including when the stored wrap will not
     * open. `SecureUnlockManagerArkTest` is where that is held to. The caller owns the returned
     * array and should zero it once it has been wrapped.
     */
    fun generateArk(): ByteArray = secureRandomBytes(SyncProtocol.ARK_BYTES)

    /**
     * Derives [AccountKeys] from [ark].
     *
     * Deterministic: the same ARK always yields the same three values, on every device and every
     * app version. That is the whole point — a paired device derives its keys from the ARK it was
     * handed, and never needs to be told them.
     *
     * The ARK is used as HKDF input keying material with no salt. A salt would have to be shared
     * between devices anyway (it is not a per-device value), and the ARK is already 32 uniformly
     * random bytes, so it buys nothing here. [ark] is not modified and remains the caller's.
     */
    fun derive(ark: ByteArray): AccountKeys {
        require(ark.size == SyncProtocol.ARK_BYTES) {
            "ARK must be ${SyncProtocol.ARK_BYTES} bytes, was ${ark.size}"
        }
        return AccountKeys(
            kContent = deriveOne(ark, SyncProtocol.INFO_CONTENT, SyncProtocol.DERIVED_KEY_BYTES),
            kId = deriveOne(ark, SyncProtocol.INFO_RECORD_ID, SyncProtocol.DERIVED_KEY_BYTES),
            accountId = deriveOne(ark, SyncProtocol.INFO_ACCOUNT, SyncProtocol.ACCOUNT_ID_BYTES),
        )
    }

    private fun deriveOne(ark: ByteArray, info: String, length: Int): ByteArray =
        // `encodeToByteArray()` rather than `toByteArray(Charsets.US_ASCII)`: `Charsets` is a JVM
        // type, and the info strings are the ASCII literals in `SyncProtocol`, for which UTF-8 and
        // US-ASCII are the same bytes. Confirmed against the committed protocol vectors.
        Hkdf.derive(ikm = ark, salt = null, info = info.encodeToByteArray(), length = length)
}
