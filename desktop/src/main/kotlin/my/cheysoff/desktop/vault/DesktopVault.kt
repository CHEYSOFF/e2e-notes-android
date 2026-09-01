package my.cheysoff.desktop.vault

import my.cheysoff.core_crypto.HlcNode
import my.cheysoff.core_crypto.PassphraseCipher
import my.cheysoff.core_crypto.sync.AccountKeys
import my.cheysoff.core_crypto.sync.AccountRootKey
import my.cheysoff.core_crypto.sync.ArkCipher
import my.cheysoff.core_crypto.sync.SyncProtocol
import my.cheysoff.desktop.keychain.CredentialStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID

/**
 * The keys of an open vault, and the only object in this module that holds live key material.
 *
 * [close] zeroes what can be zeroed. That is worth doing and worth not overstating: the JVM copies
 * arrays freely, `SecretKeySpec` keeps a copy that has no working `destroy()`, and a heap dump of a
 * running desktop app will contain note text regardless. Zeroing narrows the window in which a
 * *locked* app still holds its keys — which is the window this actually closes.
 */
class VaultSession(
    /** The 32 random bytes the passphrase (or the credential store) unwraps. */
    val vaultKey: ByteArray,
    /** The Account Root Key. Created once per account; see [DesktopVault]. */
    val ark: ByteArray,
    /** `K_content`, `K_id` and `accountId`, derived from [ark]. */
    val accountKeys: AccountKeys,
    /** This device's HLC node pseudonym: `HlcNode.derive(ark, deviceId)`. */
    val hlcNode: String,
    /**
     * How this vault syncs, or null when it has never been paired to a server.
     *
     * Null is an ordinary state, not a failure: a vault created standalone, or paired with a phone
     * that named no server, has an account and nowhere to sync it. The UI says so; nothing here
     * treats it as an error.
     */
    val sync: SyncSession? = null,
) : AutoCloseable {
    override fun close() {
        vaultKey.fill(0)
        ark.fill(0)
        accountKeys.destroy()
        sync?.deviceKey?.privateKeyPkcs8?.fill(0)
    }
}

/**
 * The unwrapped half of `VaultHeader.SyncIdentity`: where to sync, as whom, signing with what.
 *
 * [deviceKey]'s private half is live key material and is zeroed by [VaultSession.close], for the
 * reason everything else in that class is: a desktop process stays open for hours and a dropped
 * array survives in the heap until a collection that may never come.
 */
class SyncSession(
    val serverUrl: String,
    val deviceId: String,
    val deviceKey: DeviceKeyPair,
)

/**
 * What a pairing agreed about the account's server, on its way into a new vault.
 *
 * All three travel together because they are useless apart: the key signs the session challenge,
 * the id names which device row the server checks it against, and the address says where. Two of
 * the three came out of the **sealed** pairing bundle (`PairingConfig`), so they are the account
 * device's authenticated statement rather than the unauthenticated hint this computer put in QR1.
 */
class PairedEnrolment(
    val serverUrl: String,
    val deviceId: String,
    val deviceKey: DeviceKeyPair,
)

/** How a first run obtained its ARK. Recorded so the UI can say which one happened. */
enum class AccountOrigin {
    /** This computer minted the account. Only legitimate when there is no other device. */
    CREATED_HERE,

    /** The ARK came from an already-existing account, through pairing. */
    PAIRED,
}

/** What [DesktopVault.setUp] did, or why it refused. */
sealed interface SetupResult {
    data class Created(val session: VaultSession, val origin: AccountOrigin) : SetupResult

    /** A vault already exists here. Setting one up again would destroy the account it holds. */
    data object AlreadySetUp : SetupResult

    /** The proposed passphrase does not meet [PassphrasePolicy]. */
    data class Rejected(val verdict: PassphrasePolicy.Verdict) : SetupResult

    /** The header could not be written — a full disk, a read-only directory. Nothing was created. */
    data class NotWritable(val message: String) : SetupResult
}

/** What an unlock attempt produced. */
sealed interface UnlockResult {
    data class Unlocked(val session: VaultSession) : UnlockResult

    /** The passphrase did not open the wrap. Also what a tampered `vault.json` looks like. */
    data object WrongPassphrase : UnlockResult

    /** No vault here yet. The caller shows first-run, not a passphrase prompt. */
    data object NotSetUp : UnlockResult

    /**
     * The vault opened but its ARK did not, or the header is unreadable.
     *
     * **The caller must not respond by creating a new ARK.** See [DesktopVault.setUp].
     */
    data class Damaged(val reason: String) : UnlockResult
}

/**
 * The desktop's key store: creates a vault, opens one, and holds nothing between calls.
 *
 * ## The one rule
 *
 * **`AccountRootKey.generateArk()` is called in exactly one place — [setUp], and only when
 * [AccountOrigin.CREATED_HERE] was asked for and no header exists.** A second ARK does not fail and
 * does not warn. It forks the account: records sealed under the first are unopenable under the
 * second, `accountId` changes so the two halves do not even reach the same server bucket, and
 * neither half's plaintext is recoverable from the other's key. `AccountRootKey.generateArk`'s own
 * KDoc says this at length, and `SecureUnlockManager.ensureArk` is the phone's version of the same
 * guard.
 *
 * That is why [UnlockResult.Damaged] exists as a distinct outcome and why nothing in this class
 * repairs it. A header that will not parse, or an `ark` that will not unwrap, is a bug or a damaged
 * file — and on a device that has never paired it is the *only* copy of the account key. Minting a
 * replacement would present a working, empty app to a user whose notes are still on disk and now
 * permanently unreadable.
 *
 * ## Pairing is the intended first run
 *
 * [setUp] takes the ARK as a parameter, so the pairing flow fills the same seam as the standalone
 * path: it obtains an ARK from the phone and calls [setUp] with it and [AccountOrigin.PAIRED].
 * Nothing else in this class or below it needs to change when pairing lands.
 */
class DesktopVault(
    /** The vault directory. Created on demand by [setUp]; never created by an unlock. */
    val directory: Path,
    private val credentialStore: CredentialStore,
    private val random: SecureRandom = SecureRandom(),
) {

    /** `vault.json` — the wrapped keys. */
    val headerFile: Path get() = directory.resolve(HEADER_FILE)

    /** `records.db` — the sealed records. */
    val recordsFile: Path get() = directory.resolve(RECORDS_FILE)

    /** True when this directory already holds a vault. */
    fun isSetUp(): Boolean = Files.isRegularFile(headerFile)

    /**
     * Creates the vault.
     *
     * @param passphrase checked against [PassphrasePolicy] before anything is written. The caller
     *   owns it and should zero it afterwards.
     * @param ark the account key. Pass null **only** for [AccountOrigin.CREATED_HERE]; the pairing
     *   flow passes the ARK it received. The parameter is what keeps the two paths one code path.
     */
    fun setUp(
        passphrase: CharArray,
        origin: AccountOrigin,
        ark: ByteArray? = null,
        /**
         * What the pairing agreed about the account's server, or null when it agreed nothing.
         *
         * Only [AccountOrigin.PAIRED] can supply one: a standalone vault has not been enrolled
         * anywhere, and an id it invented for itself would be an id no server has ever heard of.
         */
        enrolment: PairedEnrolment? = null,
    ): SetupResult {
        // The account-forking guard, and it is checked FIRST: on a directory that already holds a
        // vault, "a vault already exists here" is the answer whatever the passphrase looks like,
        // and reporting a passphrase problem instead would invite the user to retype and try again
        // against a vault that is never going to be replaced. Checked against the file rather than
        // an in-memory flag, because the file is what a second window and a second launch see too.
        if (isSetUp()) return SetupResult.AlreadySetUp

        val verdict = PassphrasePolicy.check(passphrase)
        if (verdict != PassphrasePolicy.Verdict.Accepted) return SetupResult.Rejected(verdict)

        require(origin == AccountOrigin.PAIRED || ark == null) {
            "an ARK may only be supplied when adopting an existing account"
        }
        require(origin == AccountOrigin.CREATED_HERE || ark != null) {
            "pairing must supply the ARK it received; it must not be minted here"
        }
        require(origin == AccountOrigin.PAIRED || enrolment == null) {
            "only a paired vault can carry a server enrolment"
        }

        // THE one call site. Guarded by `isSetUp()` above, exactly as `ensureArk` is guarded by the
        // presence of `ark_ct` on the phone.
        val accountKey = ark?.copyOf() ?: AccountRootKey.generateArk()
        require(accountKey.size == SyncProtocol.ARK_BYTES) {
            "ARK must be ${SyncProtocol.ARK_BYTES} bytes, was ${accountKey.size}"
        }

        val vaultKey = ByteArray(SyncProtocol.DERIVED_KEY_BYTES).also(random::nextBytes)
        val header = VaultHeader(
            version = VaultHeader.CURRENT_VERSION,
            keyWrap = PassphraseCipher.wrapWithPin(vaultKey, passphrase, PassphrasePolicy.ITERATIONS),
            arkWrap = ArkCipher.wrap(accountKey, vaultKey),
            deviceId = UUID.randomUUID().toString(),
            sync = enrolment?.let {
                VaultHeader.SyncIdentity(
                    serverUrl = it.serverUrl,
                    deviceId = it.deviceId,
                    deviceKeyWrap = DeviceKeyCipher.wrap(it.deviceKey.privateKeyPkcs8, vaultKey),
                    devicePublicKey = it.deviceKey.publicKeySec1,
                )
            },
        )

        return try {
            writeHeader(header)
            SetupResult.Created(session(vaultKey, accountKey, header.deviceId, header.sync), origin)
        } catch (e: Exception) {
            // Nothing is left behind: `writeHeader` publishes atomically, so a failure means no
            // `vault.json` exists and the next launch is a first run again. The freshly minted ARK
            // is dropped here, unsaved -- which is safe precisely because no record was ever sealed
            // under it.
            vaultKey.fill(0)
            accountKey.fill(0)
            SetupResult.NotWritable(e.message ?: e::class.java.simpleName)
        }
    }

    /** Opens the vault with [passphrase]. The caller owns [passphrase] and should zero it. */
    fun unlock(passphrase: CharArray): UnlockResult {
        val header = readHeader() ?: return if (isSetUp()) {
            UnlockResult.Damaged("vault.json is present but could not be read")
        } else {
            UnlockResult.NotSetUp
        }
        val vaultKey = PassphraseCipher.unwrapWithPin(header.keyWrap, passphrase)
            ?: return UnlockResult.WrongPassphrase
        return openWithVaultKey(vaultKey, header)
    }

    /**
     * Opens the vault from the OS credential store, or returns null if it cannot.
     *
     * Null — no stored secret, a store that has forgotten it, a stored secret that no longer
     * unwraps the ARK — is not an error and is not reported as one. It means "ask for the
     * passphrase", which is the same thing this returns on a machine that never used the store at
     * all.
     */
    fun unlockFromCredentialStore(): VaultSession? {
        val header = readHeader() ?: return null
        val vaultKey = credentialStore.recall() ?: return null
        return when (val result = openWithVaultKey(vaultKey, header)) {
            is UnlockResult.Unlocked -> result.session
            else -> null
        }
    }

    /** True when the credential store currently holds this machine's vault key. */
    fun isRemembered(): Boolean = credentialStore.isRemembered()

    /** Whether the credential store can be offered at all, and what to call it. */
    val credentialStoreDescription: String? =
        credentialStore.description.takeIf { credentialStore.isAvailable }

    /**
     * Asks the OS to hold [session]'s vault key, and reports whether it agreed.
     *
     * **False must reach the user.** The passphrase wrap is untouched either way, so a false here
     * costs a passphrase prompt next launch and nothing else — but a user who ticked the box and
     * was not told it failed is a user who may stop rehearsing the passphrase they still need.
     */
    fun rememberOnThisComputer(session: VaultSession): Boolean =
        credentialStore.isAvailable && credentialStore.remember(session.vaultKey)

    /** Drops the stored vault key. The vault still opens with the passphrase; it always did. */
    fun forgetOnThisComputer() = credentialStore.forget()

    private fun openWithVaultKey(vaultKey: ByteArray, header: VaultHeader): UnlockResult {
        val ark = ArkCipher.unwrap(header.arkWrap, vaultKey)
        if (ark == null) {
            vaultKey.fill(0)
            // The passphrase opened the outer wrap, so it was right. The ARK not opening under the
            // key it produced means the header's two halves no longer belong together -- a partial
            // restore, or a file edited by hand. Reported as Damaged rather than WrongPassphrase
            // because retyping the passphrase cannot help, and because the caller must not treat
            // this as a reason to create a new account.
            return UnlockResult.Damaged("the account key could not be unwrapped")
        }
        return UnlockResult.Unlocked(session(vaultKey, ark, header.deviceId, header.sync))
    }

    private fun session(
        vaultKey: ByteArray,
        ark: ByteArray,
        deviceId: String,
        sync: VaultHeader.SyncIdentity?,
    ) = VaultSession(
        vaultKey = vaultKey,
        ark = ark,
        accountKeys = AccountRootKey.derive(ark),
        hlcNode = HlcNode.derive(ark, deviceId),
        sync = sync?.let { openSync(it, vaultKey) },
    )

    /**
     * Unwrap the device key, or answer null.
     *
     * Null for a wrap GCM refuses **and** for a pair whose two halves no longer agree, and both are
     * reported the same way for the same reason: neither can be repaired here, and both produce a
     * device that would enrol nothing and fail every session handshake. The vault still opens —
     * the notes are readable and the ARK is intact — it simply cannot sync, which the UI says out
     * loud rather than discovering at the first pass. Minting a replacement key would be strictly
     * worse: the server holds one public key per device row and would reject every signature the
     * new one made.
     */
    private fun openSync(identity: VaultHeader.SyncIdentity, vaultKey: ByteArray): SyncSession? {
        val pkcs8 = DeviceKeyCipher.unwrap(identity.deviceKeyWrap, vaultKey) ?: return null
        val pair = DeviceKeyPair(pkcs8, identity.devicePublicKey)
        if (!pair.verifySelfConsistent()) {
            pkcs8.fill(0)
            return null
        }
        return SyncSession(
            serverUrl = identity.serverUrl,
            deviceId = identity.deviceId,
            deviceKey = pair,
        )
    }

    private fun readHeader(): VaultHeader? =
        if (!Files.isRegularFile(headerFile)) null else VaultHeader.decode(Files.readAllBytes(headerFile))

    /**
     * Writes `vault.json` through a temporary file and an atomic move.
     *
     * A direct write can be interrupted between truncating the file and filling it, and the
     * resulting empty `vault.json` is a vault whose ARK is gone. `ATOMIC_MOVE` on the same
     * filesystem makes the file either the old one or the new one, never half of either. There is
     * no old one at this call site today — [setUp] refuses to run over an existing header — but the
     * passphrase-change path this class will grow rewrites it, and the guarantee has to be in the
     * writer rather than remembered at each call.
     */
    private fun writeHeader(header: VaultHeader) {
        Files.createDirectories(directory)
        val temporary = directory.resolve("$HEADER_FILE.tmp")
        Files.write(temporary, VaultHeader.encode(header))
        Files.move(temporary, headerFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val HEADER_FILE = "vault.json"
        const val RECORDS_FILE = "records.db"
    }
}
