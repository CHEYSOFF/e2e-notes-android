package my.cheysoff.notes.sync

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the one [CoroutineScope] whose lifetime is the process rather than a screen.
 *
 * A qualifier rather than a bare `@Provides CoroutineScope` because a scope is exactly the kind of
 * dependency that must be asked for by name: an unqualified one would be injectable anywhere, and
 * the next thing to want a `CoroutineScope` would silently get the one whose work must never be
 * cancelled.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The app-scoped coroutine scope, and the reason there was not one before.
 *
 * ## Why `viewModelScope` cannot run a sync pass
 *
 * Every asynchronous thing this app does today runs in a `viewModelScope`, which is cancelled when
 * its screen leaves the back stack. That is right for loading a list and wrong for a sync pass:
 * navigation would cancel it mid-push, and `docs/design/e2e-sync-phase3-plan.md` §3.3 sets out what
 * a cancelled push costs — the server may have committed the write, the client never reads the
 * acknowledgement, the row stays dirty against a stale `baseSeq`, and the next pass spends a `409`
 * merging the device's own record back into itself. Recoverable, and pure waste, every time the
 * user taps back at the wrong moment.
 *
 * ## `SupervisorJob`
 *
 * So that one failed pass does not cancel the scope and take every future pass with it. A sync
 * failure is a normal event — the network is unreliable — and `SyncEngine.runPass` never throws
 * past its own boundary anyway, so the supervisor is the belt to that braces.
 *
 * ## `Dispatchers.IO`
 *
 * Everything on this scope is a socket or SQLCipher. Neither belongs on `Default`, whose pool is
 * sized for CPU work and would be occupied by a blocked read.
 *
 * ## Foreground only, still
 *
 * A scope that outlives a screen is not a scope that outlives the foreground. `MainApplication`
 * locks on `onStop`, which drops the passphrase and the Account Root Key, and this scope does
 * nothing to change that: a pass that starts after a lock finds no keys and reports
 * `SyncPassState.Unavailable`. Lock-on-background is one of this app's genuinely strong
 * properties and §7 of the plan is explicit that background sync needs a ciphertext outbox rather
 * than a relaxed lock.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
