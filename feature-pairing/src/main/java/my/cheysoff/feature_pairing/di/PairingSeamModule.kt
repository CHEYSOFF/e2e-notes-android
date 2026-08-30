package my.cheysoff.feature_pairing.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.feature_pairing.protocol.AccountBundle
import my.cheysoff.feature_pairing.protocol.KeyDerivation
import my.cheysoff.feature_pairing.protocol.MonotonicClock
import javax.inject.Singleton

/**
 * Everything the pairing flow needs from the sync key hierarchy that Phase 1 owns.
 *
 * Two things, both opaque here:
 *  - whether this device already belongs to an account, and if so its [AccountBundle];
 *  - somewhere to put the bundle a pairing produces.
 *
 * The ARK inside that bundle is 32 bytes this module seals, transports and hands back. It never
 * generates one, never wraps one, never writes one to disk, and never logs one. Generation and
 * storage are `SecureUnlockManager`'s job (`ark_ct`/`ark_iv` wrapped under
 * `HKDF(dbPassphrase, ".../arkwrap")`), which is Phase 1's work and lives in `:core-crypto`.
 */
interface PairingKeyMaterial {

    /**
     * Whether the Phase-1 key hierarchy exists in this build at all.
     *
     * **This is `false` on the `sync-phase2-pairing` branch**, and [Phase1NotLandedKeyMaterial] is
     * the reason. It is a build-time fact rather than a per-device one: there is no ARK on any
     * device yet because no code anywhere creates one. The UI checks it before starting a session
     * and shows an honest "sync is not available in this build" state instead of a flow that
     * cannot complete.
     */
    val isBound: Boolean

    /**
     * This device's account bundle, or null if it does not have one — i.e. if it is the *new*
     * device in a pairing rather than the one holding the account.
     */
    fun accountBundle(): AccountBundle?

    /** Store the bundle a completed pairing produced. Called exactly once, on the new device. */
    fun adopt(bundle: AccountBundle)
}

/**
 * The Phase-1 seam.
 *
 * ## What binds here, and what a reviewer should check
 *
 * Two providers, both placeholders, both replaced by a one-line change when `sync-phase1-crypto`
 * lands:
 *
 * ```kotlin
 *   @Binds fun bindKeyDerivation(impl: HkdfSha256): KeyDerivation
 *   @Binds fun bindKeyMaterial(impl: SecureUnlockArkStore): PairingKeyMaterial
 * ```
 *
 * There is deliberately **no working HKDF in this module**. A second implementation of the same
 * KDF is how the two halves of one protocol drift: pairing would agree with itself and disagree
 * with the record envelope, and both sides' tests would pass. The unit tests bind their own RFC
 * 5869 fake (checked against the RFC's published vectors) so everything above the seam is testable
 * without one.
 */
@Module
@InstallIn(SingletonComponent::class)
object PairingSeamModule {

    /**
     * The monotonic clock the pairing sessions measure their TTL against.
     *
     * `SystemClock.elapsedRealtime()` counts milliseconds since boot including deep sleep, and
     * cannot be moved by the user. `System.currentTimeMillis()` deliberately is not used: it is
     * user-settable, so a TTL measured against it could be shortened or extended from the Settings
     * app. `LockoutPolicy` already makes the same choice for the wrong-PIN backoff.
     */
    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }

    /**
     * Placeholder for Phase 1's HKDF-SHA256.
     *
     * Throws rather than returning something plausible: a silently wrong KDF would produce a
     * pairing that *appears* to work and yields an account key nothing else can use. It is
     * unreachable in the shipped UI — [PairingKeyMaterial.isBound] is false in this build, and the
     * ViewModel refuses to start a session before any derivation happens — and the throw is the
     * backstop if some future caller skips that check.
     */
    @Provides
    @Singleton
    fun provideKeyDerivation(): KeyDerivation = Phase1NotLandedKeyDerivation

    @Provides
    @Singleton
    fun providePairingKeyMaterial(): PairingKeyMaterial = Phase1NotLandedKeyMaterial
}

/** See [PairingSeamModule.provideKeyDerivation]. */
private object Phase1NotLandedKeyDerivation : KeyDerivation {
    override fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray =
        throw UnsupportedOperationException(
            "HKDF is not bound in this build: the Phase 1 crypto core has not landed. " +
                "Bind KeyDerivation in PairingSeamModule."
        )
}

/** See [PairingKeyMaterial.isBound]. */
private object Phase1NotLandedKeyMaterial : PairingKeyMaterial {
    override val isBound: Boolean = false
    override fun accountBundle(): AccountBundle? = null
    override fun adopt(bundle: AccountBundle) =
        throw UnsupportedOperationException(
            "There is nowhere to store an ARK in this build: the Phase 1 crypto core has not " +
                "landed. Bind PairingKeyMaterial in PairingSeamModule."
        )
}
