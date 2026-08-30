package my.cheysoff.feature_pairing.di

import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.cheysoff.feature_pairing.protocol.AccountBundle
import my.cheysoff.feature_pairing.protocol.HkdfKeyDerivation
import my.cheysoff.feature_pairing.protocol.KeyDerivation
import my.cheysoff.feature_pairing.protocol.MonotonicClock
import javax.inject.Singleton

/**
 * Everything the pairing flow needs from the sync key hierarchy that `:core-crypto` owns.
 *
 * Three things, all opaque here:
 *  - whether this device is in a position to share an account at all;
 *  - the [AccountBundle] to seal when it does;
 *  - somewhere to put the bundle a pairing produces.
 *
 * The ARK inside that bundle is 32 bytes this module seals, transports and hands back. It never
 * generates one, never wraps one, never writes one to disk, and never logs one. Generation and
 * storage are `SecureUnlockManager`'s job (`ark_ct`/`ark_iv` wrapped under
 * `HKDF(dbPassphrase, ".../arkwrap")`), which lives in `:core-crypto`.
 */
interface PairingKeyMaterial {

    /**
     * Whether the sync key hierarchy is bound in this build.
     *
     * A build-time fact rather than a per-device one, and true in every shipped build since
     * [SecureUnlockArkStore] replaced the Phase-1 placeholder. It is kept because it is the gate
     * the screen consults before starting a session: an unbound build shows an honest "not
     * available" state instead of a flow that cannot complete, and that is a cheaper backstop than
     * discovering the gap at the point where an ARK would be sealed.
     */
    val isBound: Boolean

    /**
     * Whether this device can play the account-holder role.
     *
     * A **pure read**. It is consulted whenever the role chooser is drawn, so it must never create
     * key material — see [accountBundle], which does.
     */
    fun canShareAccount(): Boolean

    /**
     * The bundle this device shares with a new one, or null if it cannot produce one.
     *
     * Called once, after the user has chosen the account-holder role. On a device that has never
     * synced this MINTS the account: the ARK is created here, on the first device, exactly as the
     * design says it should be. That is why it is not called to populate the role chooser —
     * opening a screen must not create an account.
     */
    fun accountBundle(): AccountBundle?

    /** Store the bundle a completed pairing produced. Called exactly once, on the new device. */
    fun adopt(bundle: AccountBundle)
}

/**
 * The `:core-crypto` seam.
 *
 * ## What binds here, and what a reviewer should check
 *
 * Both bindings are now real. [KeyDerivation] resolves to [HkdfKeyDerivation], which is a thin
 * adapter over `core-crypto`'s `Hkdf` — **the only HKDF-SHA256 in the codebase**. The RFC 5869
 * fake this module's tests used to bind has been deleted rather than left alongside it: two copies
 * of a protocol primitive each pass their own tests and disagree only on two real phones, which is
 * the failure `HkdfSeamTest` now pins to literal bytes.
 *
 * [PairingKeyMaterial] resolves to [SecureUnlockArkStore], which is where the ARK is minted,
 * wrapped and read back.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PairingSeamModule {

    @Binds
    @Singleton
    abstract fun bindPairingKeyMaterial(impl: SecureUnlockArkStore): PairingKeyMaterial

    companion object {

        /**
         * The monotonic clock the pairing sessions measure their TTL against.
         *
         * `SystemClock.elapsedRealtime()` counts milliseconds since boot including deep sleep, and
         * cannot be moved by the user. `System.currentTimeMillis()` deliberately is not used: it is
         * user-settable, so a TTL measured against it could be shortened or extended from the
         * Settings app. `LockoutPolicy` already makes the same choice for the wrong-PIN backoff.
         */
        @Provides
        @Singleton
        fun provideMonotonicClock(): MonotonicClock =
            MonotonicClock { SystemClock.elapsedRealtime() }

        /**
         * HKDF-SHA256, from `:core-crypto`.
         *
         * A `@Provides` of an existing object rather than a `@Binds` of an injectable class,
         * because [HkdfKeyDerivation] is a stateless `object` with nothing to inject — the same
         * instance the tests call directly, so nothing about the derivation differs between a test
         * run and a device.
         */
        @Provides
        @Singleton
        fun provideKeyDerivation(): KeyDerivation = HkdfKeyDerivation
    }
}
